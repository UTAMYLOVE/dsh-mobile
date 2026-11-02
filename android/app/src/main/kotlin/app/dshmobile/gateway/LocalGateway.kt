package app.dshmobile.gateway

import app.dshmobile.security.KeyVault
import app.dshmobile.security.SessionToken
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A request from a dsh plugin (or a hook) to show an Android notification. */
data class NotifyRequest(val title: String, val body: String, val sessionId: String?)

/**
 * Loopback-only HTTP gateway between dsh (inside proot) and the model providers. dsh talks to
 * `http://127.0.0.1:<port>/<token>/deepseek` or `/<token>/anthropic` with a fake key; the gateway swaps in the real
 * key from [vault], strips dsh's upload-style body fields, forwards the body untouched and streaming, and meters cost.
 * Nothing here may log or echo the token or a key.
 */
class LocalGateway(
    token: SessionToken,
    private val vault: KeyVault,
    private val meter: CostMeter,
    private val upstreams: Map<Provider, HttpUrl>,
    private val onNotify: (NotifyRequest) -> Unit,
    private val onCostAlert: (usd: Double) -> Unit,
    private val onRateGuard: (Provider) -> Unit,
    private val rateGuard: RateGuard = RateGuard(clock = Clock.systemUTC()),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val tokenBytes = token.value.toByteArray(Charsets.UTF_8)
    private val client = OkHttpClient.Builder()
        // dsh may run 16 concurrent subagents; OkHttp's default of 5 per host would queue them silently.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES) // no overall call timeout: streams can run long, but a dead socket must not hang forever
        .build()
    internal val upstreamDispatcher get() = client.dispatcher
    private val lastRateNotice = HashMap<Provider, Long>()
    private var server: EmbeddedServer<*, *>? = null

    /** Starts listening on 127.0.0.1 at a system-chosen port and returns that port. */
    fun start(): Int {
        val s = embeddedServer(
            CIO,
            applicationEnvironment { },
            configure = {
                connector {
                    host = LOOPBACK
                    port = 0
                }
            },
        ) {
            routing { route("{...}") { handle { handle(call) } } }
        }
        s.start(wait = false)
        server = s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private suspend fun handle(call: ApplicationCall) {
        val uri = call.request.uri
        val queryAt = uri.indexOf('?')
        val path = if (queryAt < 0) uri else uri.substring(0, queryAt)
        val query = if (queryAt < 0) "" else uri.substring(queryAt)
        val parts = path.removePrefix("/").split('/', limit = 3) // token, route, rest
        if (!MessageDigest.isEqual(parts[0].toByteArray(Charsets.UTF_8), tokenBytes)) {
            return call.respondJson(HttpStatusCode.Forbidden, """{"error":"dshm_forbidden"}""")
        }
        val rest = if (parts.size == 3) "/" + parts[2] else ""
        when {
            parts.getOrNull(1) == "notify" && parts.size == 2 && call.request.httpMethod == HttpMethod.Post -> notify(call)
            parts.getOrNull(1) == "deepseek" && !isUnsafePath(rest) -> forward(call, Provider.DEEPSEEK, rest + query)
            parts.getOrNull(1) == "anthropic" && !isUnsafePath(rest) -> forward(call, Provider.ANTHROPIC, rest + query)
            else -> call.respondJson(HttpStatusCode.NotFound, """{"error":"dshm_not_found"}""")
        }
    }

    private suspend fun notify(call: ApplicationCall) {
        val request = parseNotify(call.receive<ByteArray>().decodeToString())
            ?: return call.respondJson(HttpStatusCode.BadRequest, """{"error":"dshm_bad_request"}""")
        onNotify(request)
        call.respondJson(HttpStatusCode.OK, """{"ok":true}""")
    }

    private fun parseNotify(text: String): NotifyRequest? {
        val obj = parseObject(text) ?: return null
        val title = obj.string("title") ?: return null
        val body = obj.string("body") ?: return null
        val session = obj["sessionId"]
        val sessionId = when {
            session == null || session is JsonNull -> null
            else -> obj.string("sessionId") ?: return null
        }
        return NotifyRequest(title, body, sessionId)
    }

    private suspend fun forward(call: ApplicationCall, p: Provider, restAndQuery: String) {
        val upstream = upstreams[p] ?: return call.respondJson(HttpStatusCode.NotFound, """{"error":"dshm_not_found"}""")
        if (meter.isBlocked()) return call.respondJson(HttpStatusCode.TooManyRequests, """{"error":"dshm_daily_cap"}""")
        if (!rateGuard.tryAcquire(p)) {
            noticeRateGuard(p)
            return call.respondJson(HttpStatusCode.TooManyRequests, """{"error":"dshm_rate_guard"}""")
        }
        val key = vault.get(p)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return call.respondJson(HttpStatusCode.Unauthorized, """{"error":"dshm_missing_key","provider":"${p.name}"}""")
        if (!key.all { it in ' '..'~' }) {
            return call.respondJson(HttpStatusCode.Unauthorized, """{"error":"dshm_invalid_key","provider":"${p.name}"}""")
        }
        val target = (upstream.toString().trimEnd('/') + restAndQuery).toHttpUrlOrNull()
            ?: return call.respondJson(HttpStatusCode.BadRequest, """{"error":"dshm_bad_request"}""")
        if (!isConfinedTo(upstream, target)) return call.respondJson(HttpStatusCode.NotFound, """{"error":"dshm_not_found"}""")

        val prepared = prepareBody(call.receive<ByteArray>())
        val method = call.request.httpMethod.value
        val request = try {
            val builder = Request.Builder().url(target)
            call.request.headers.forEach { name, values ->
                if (!isDroppedRequestHeader(name)) values.forEach { builder.addHeader(name, it) }
            }
            builder.header("x-api-key", key)
            builder.header("Accept-Encoding", "identity") // keep bodies uncompressed so usage can be read from them
            val contentType = call.request.headers["Content-Type"]?.toMediaTypeOrNull()
            val needsBody = method in BODY_METHODS || (prepared.bytes.isNotEmpty() && method != "GET" && method != "HEAD")
            builder.method(method, if (needsBody) prepared.bytes.toRequestBody(contentType) else null)
            builder.build()
        } catch (_: IllegalArgumentException) {
            // OkHttp's message would quote the offending header value, so say nothing about it.
            return call.respondJson(HttpStatusCode.BadRequest, """{"error":"dshm_bad_request"}""")
        }

        val upstreamCall = client.newCall(request)
        val response = try {
            upstreamCall.await()
        } catch (_: IOException) {
            return call.respondJson(HttpStatusCode.BadGateway, """{"error":"dshm_upstream_unreachable"}""")
        }
        stream(call, p, prepared.model, upstreamCall, response)
    }

    private suspend fun stream(call: ApplicationCall, p: Provider, model: String, upstreamCall: Call, response: Response) {
        val tap = UsageTap(p, response.header("Content-Type"))
        try {
            response.headers.forEach { (name, value) ->
                if (name.lowercase() !in DROPPED_RESPONSE_HEADERS) call.response.headers.append(name, value)
            }
            val status = HttpStatusCode.fromValue(response.code)
            val contentType = response.header("Content-Type")?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            val bodyless = call.request.httpMethod == HttpMethod.Head || response.code in 100..199 ||
                response.code == 204 || response.code == 304
            if (bodyless) {
                call.respondBytes(ByteArray(0), contentType, status)
            } else {
                call.respond(UpstreamContent(upstreamCall, response, status, contentType, tap))
            }
        } finally {
            // Runs after a clean end, an upstream disconnect and a client cancel alike: usage is booked exactly once.
            response.close()
            settle(p, model, tap.usage())
        }
    }

    private fun settle(p: Provider, model: String, usage: Usage?) {
        if (usage == null) return
        meter.record(p, model, usage)
        if (meter.consumeAlert()) onCostAlert(meter.todayUsd())
    }

    private fun noticeRateGuard(p: Provider) {
        val now = clock.millis()
        val due = synchronized(lastRateNotice) {
            val last = lastRateNotice[p]
            (last == null || now - last >= RATE_NOTICE_INTERVAL_MS).also { if (it) lastRateNotice[p] = now }
        }
        if (due) onRateGuard(p)
    }

    /** The upstream response body, relayed chunk by chunk as it arrives. */
    private class UpstreamContent(
        private val upstreamCall: Call,
        private val response: Response,
        override val status: HttpStatusCode,
        override val contentType: ContentType?,
        private val tap: UsageTap,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long? = (response.body?.contentLength() ?: -1L).takeIf { it >= 0 }

        override suspend fun writeTo(channel: ByteWriteChannel) = coroutineScope {
            // A blocked socket read cannot see coroutine cancellation (client went away), so cancel the call from here.
            val watchdog = launch {
                try {
                    awaitCancellation()
                } finally {
                    upstreamCall.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    val source = checkNotNull(response.body) { "upstream response without body" }.source()
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = source.read(buffer) // returns what has arrived, not a full buffer
                        if (n < 0) break
                        tap.feed(buffer, 0, n)
                        channel.writeFully(buffer, 0, n)
                        channel.flush()
                    }
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private class PreparedBody(val bytes: ByteArray, val model: String)

    /** Drops dsh's upload-style fields from a JSON object body and reads `model`; anything else passes through as is. */
    private fun prepareBody(bytes: ByteArray): PreparedBody {
        val first = bytes.firstOrNull { (it.toInt() and 0xFF) > 0x20 }
        val obj = (if (first == '{'.code.toByte()) parseObject(bytes.decodeToString()) else null)
            ?: return PreparedBody(bytes, "")
        val model = obj.string("model") ?: ""
        val kept = obj.filterKeys { it !in STRIPPED_BODY_FIELDS }
        val out = if (kept.size == obj.size) bytes else JsonObject(kept).toString().toByteArray(Charsets.UTF_8)
        return PreparedBody(out, model)
    }

    /** Dot segments and backslashes (which OkHttp also treats as `/`) could climb out of the upstream base path. */
    private fun isDroppedRequestHeader(name: String): Boolean {
        val lower = name.lowercase()
        return lower in DROPPED_REQUEST_HEADERS || lower.startsWith(HARNESS_HEADER_PREFIX)
    }

    private fun isUnsafePath(rest: String): Boolean {
        if (rest.contains('\\') || rest.contains("%5c", ignoreCase = true)) return true
        return rest.split('/').any {
            val s = it.replace("%2e", ".", ignoreCase = true)
            s == "." || s == ".."
        }
    }

    /** True only if [target] is on the upstream's own origin and below its base path. */
    private fun isConfinedTo(upstream: HttpUrl, target: HttpUrl): Boolean {
        if (target.scheme != upstream.scheme || target.host != upstream.host || target.port != upstream.port) return false
        val base = upstream.encodedPath.trimEnd('/')
        return target.encodedPath == base || target.encodedPath.startsWith("$base/")
    }

    private fun parseObject(text: String): JsonObject? =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
        respondText(body, ContentType.Application.Json, status)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BUFFER_BYTES = 8 * 1024
        const val RATE_NOTICE_INTERVAL_MS = 10 * 60 * 1_000L

        val STRIPPED_BODY_FIELDS = setOf("dsh_session_log", "dsh_plugin_packages")
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade",
        )
        val DROPPED_REQUEST_HEADERS = HOP_BY_HOP + setOf(
            "authorization", "x-api-key", "host", "content-length", "accept-encoding",
        )
        // Privacy: dsh identifies user and session with x-deepseek-harness-* headers; none of them leave the device.
        const val HARNESS_HEADER_PREFIX = "x-deepseek-harness-"
        // Content-Length and Content-Type are set by Ktor from the outgoing content.
        val DROPPED_RESPONSE_HEADERS = HOP_BY_HOP + setOf("content-length", "content-type")
    }
}
