package app.dshmobile.remote

import app.dshmobile.chat.ChatRemote
import app.dshmobile.runtime.AppPaths
import app.dshmobile.runtime.DshClient
import app.dshmobile.ui.shell.ShellRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * The app's typed view of one running dsh: unary calls over `POST /api/<namespace>/<method>` and streams over one
 * `/api/remote.mux` WebSocket ([mux]), both with the session cookie of [client] (which only talks to
 * `127.0.0.1:<launch port>`). Exception messages carry the method name, an HTTP status or a [DshErrors.safeCode]; never
 * the launch URL, a token, a cookie or dsh's free-text error message.
 *
 * [scope] belongs to the caller (the shell session); the mux and [events] (started here) run in it. [zone] is sent with
 * every prompt. A new dsh instance gets a new [DshRemote]; [close] the old one.
 */
class DshRemote(
    private val client: DshClient,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Where requests are serialized and answers parsed (final review A-I2). Callers are often on the main thread; a prompt with
     * pictures, an upload's base64 or a long `session/list` must not be built or read there.
     */
    private val codec: CoroutineContext = Dispatchers.Default,
    /** The request text to the UTF-8 bytes that are sent; runs on [codec] (a seam for the test that checks where). */
    private val encodeBody: (String) -> ByteArray = { it.toByteArray(Charsets.UTF_8) },
) : ShellRemote {
    private val loginLock = Mutex()
    @Volatile private var loggedIn = false
    @Volatile private var closed = false

    /** The one stream connection; it logs in (once, shared with [call]) before its first upgrade and again on a 401. */
    val mux: MuxConnection = MuxConnection(
        open = { listener -> client.openWebSocket(MUX_PATH, listener) },
        relogin = { loginLock.withLock { client.login(); loggedIn = true } },
        scope = scope,
        beforeConnect = { ensureLoggedIn() },
    )

    /** `$events`: prompts to answer and session lifecycle, open from construction on. */
    val events: DshEvents = DshEvents(mux, ::call, scope)

    override val hostEvents: SharedFlow<HostEvent> get() = events.hostEvents
    override val muxState: StateFlow<MuxState> get() = mux.state
    override val eventsClientId: StateFlow<String?> get() = events.clientId

    /** History and live events of [sessionId] (`session/follow`, with the live assistant stream). */
    override fun follow(sessionId: String): Flow<StreamSignal> = mux.stream("session/follow", request {
        putJsonObject("address") { put("kind", "session"); put("sessionId", sessionId) }
        put("assistantStream", true)
        put("maxMessages", FOLLOW_MAX_MESSAGES)
        putJsonObject("turnWindow") { put("minMessages", FOLLOW_MIN_MESSAGES); put("minTurns", FOLLOW_MIN_TURNS) }
    })

    /** Host-wide live projections of every session (`session/control`). */
    override fun control(): Flow<StreamSignal> = mux.stream("session/control")

    /** Workspaces (`workspace/follow`). */
    override fun workspaces(): Flow<StreamSignal> = mux.stream("workspace/follow")

    /**
     * One unary call. Logs in on first use and once more when dsh answers 401 (then retries once).
     * @throws DshRpcException dsh answered `ok:false` or an unreadable body.
     * @throws IOException transport or authentication failure (HTTP status other than 200).
     */
    suspend fun call(method: String, args: JsonObject): JsonElement {
        require(METHOD.matches(method)) { "not a dsh method name" }
        if (closed) throw IOException("dsh remote closed")
        ensureLoggedIn()
        // Serialized and UTF-8 encoded on the codec: the client sends these bytes as they are, nothing is encoded on the caller.
        val body = withContext(codec) { encodeBody(RpcEnvelope.request(method, args, "dshm-${UUID.randomUUID()}")) }
        var response = post(method, body)
        if (response.code == 401) {
            loginLock.withLock { client.login() }
            response = post(method, body)
        }
        if (response.code != 200) throw IOException("dsh $method: HTTP ${response.code}")
        return when (val result = withContext(codec) { RpcEnvelope.parse(response.body) }) {
            is RpcResult.Ok -> result.value
            is RpcResult.Err -> throw rpcError(method, result.code)
        }
    }

    override suspend fun listSessions(): List<SessionSummary> {
        val value = call(LIST, buildJsonObject { putJsonObject("_request") {} })
        return withContext(codec) { items(LIST, value).mapNotNull { (it as? JsonObject)?.let(SessionSummary::from) } }
    }

    override suspend fun searchSessions(query: String): List<SearchHit> {
        val value = call(SEARCH, request { put("query", query) })
        return items(SEARCH, value).mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHit(o.string("sessionId") ?: return@mapNotNull null, o.string("snippet").orEmpty())
        }
    }

    /** A new session in workspace [workspaceId], or in the app's workspace directory when there is none. Returns its id. */
    override suspend fun createSession(workspaceId: String?): String {
        val value = call(CREATE, request { if (workspaceId != null) put("workspaceId", workspaceId) else put("cwd", AppPaths.GUEST_WORKSPACE) })
        return field(CREATE, value, "sessionId")
    }

    /** Always `mode:"queue"`: stage 1 never steers a running turn. The content (inline pictures) is built on [codec]. */
    override suspend fun prompt(sessionId: String, requestId: String, parts: List<PromptPart>) {
        val args = withContext(codec) {
            request {
                put("requestId", requestId)
                put("sessionId", sessionId)
                put("mode", "queue")
                put("content", buildJsonArray { parts.forEach { add(contentPart(it)) } })
                put("clientTimeZone", zone.id)
            }
        }
        call("session/prompt", args)
    }

    override suspend fun cancel(sessionId: String) {
        call("session/cancel", request { put("sessionId", sessionId) })
    }

    /** Returns the title dsh stored. */
    override suspend fun rename(sessionId: String, title: String): String {
        val value = call(RENAME, request { put("sessionId", sessionId); put("title", title) })
        return field(RENAME, value, "title")
    }

    override suspend fun archive(sessionId: String) {
        call("workspace/archiveSession", request { put("sessionId", sessionId); put("stopActivity", true) })
    }

    override suspend fun selectModelEffort(sessionId: String, provider: String, model: String, reasoningEffort: String?) {
        call("session/selectModel", request {
            put("sessionId", sessionId); put("provider", provider); put("model", model)
            reasoningEffort?.let { put("reasoningEffort", it) }
        })
    }

    override suspend fun runCommand(sessionId: String, line: String) {
        call("commands/execute", buildJsonObject {
            put("agentId", sessionId)
            put("line", line)
            putJsonArray("submittedAttachments") {}
        })
    }

    override suspend fun selectModel(sessionId: String, provider: String, model: String) {
        call("session/selectModel", request { put("sessionId", sessionId); put("provider", provider); put("model", model) })
    }

    /**
     * Stages [bytes] for [sessionId]'s next prompt; returns the receipt id for [PromptPart.File]. The base64 text is made on
     * [codec], off the caller's thread, and so are the serialized request and its UTF-8 bytes. While it is sent, the caller's
     * bytes, the base64 text (4/3 of them), the serialized request (another 4/3) and its UTF-8 body (another 4/3) are all in
     * memory: about five times the file at the peak, none of it made on the caller's thread.
     */
    override suspend fun upload(sessionId: String, name: String, bytes: ByteArray): String {
        val args = withContext(codec) {
            buildJsonObject {
                put("agentId", sessionId)
                putJsonObject("request") {
                    put("data", String(Base64.getEncoder().encode(bytes), Charsets.ISO_8859_1))
                    put("name", name)
                }
            }
        }
        val value = call(UPLOAD, args)
        return field(UPLOAD, value, "receiptId")
    }

    /** Older history of [sessionId] before [beforeSeq]; the raw `SessionPage`. */
    override suspend fun page(sessionId: String, beforeSeq: Long, maxMessages: Int): JsonElement = call("session/page", request {
        putJsonObject("address") { put("kind", "session"); put("sessionId", sessionId) }
        put("beforeSeq", beforeSeq)
        put("maxMessages", maxMessages)
    })

    override suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) {
        call("subagents/interruptByParent", buildJsonObject {
            put("childSessionId", childSessionId)
            put("parentSessionId", parentSessionId)
            put("mode", "continuable")
        })
    }

    /** Later calls fail with an [IOException]; the mux closes and every stream completes. */
    override fun close() {
        closed = true
        mux.close()
    }

    /** Answers are read whole up to [MAX_ANSWER_BYTES] (a long `session/list` or `session/page`); longer ones fail. */
    private suspend fun post(method: String, body: ByteArray): DshClient.TextResponse = try {
        client.postJsonForBoundedText("/api/$method", body, MAX_ANSWER_BYTES)
    } catch (_: DshClient.ResponseTooLargeException) {
        throw rpcError(method, ANSWER_TOO_LARGE)
    }

    private suspend fun ensureLoggedIn() {
        if (loggedIn) return
        loginLock.withLock {
            if (!loggedIn) {
                client.login()
                loggedIn = true
            }
        }
    }

    private fun contentPart(part: PromptPart): JsonObject = buildJsonObject {
        when (part) {
            is PromptPart.Text -> { put("type", "text"); put("text", part.text) }
            is PromptPart.Image -> {
                put("type", "image")
                put("mediaType", part.mediaType)
                put("data", part.base64)
                if (part.name != null) put("name", part.name)
            }
            is PromptPart.File -> { put("type", "file"); put("receiptId", part.receiptId) }
        }
    }

    private companion object {
        const val MUX_PATH = "/api/remote.mux"
        const val MAX_ANSWER_BYTES = 16L shl 20
        const val ANSWER_TOO_LARGE = "response-too-large"
        const val FOLLOW_MAX_MESSAGES = 500
        const val FOLLOW_MIN_MESSAGES = 50
        const val FOLLOW_MIN_TURNS = 2
        const val LIST = "session/list"
        const val SEARCH = "session/search"
        const val CREATE = "session/create"
        const val RENAME = "session/rename"
        const val UPLOAD = "fileUploads/upload"
        /** `namespace/method`, including dsh's `$events/result`; keeps the request path on `/api/`. */
        val METHOD = Regex("[A-Za-z0-9\$_-]+(/[A-Za-z0-9_-]+)+")

        inline fun request(crossinline build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
            buildJsonObject { putJsonObject("request") { build() } }

        fun rpcError(method: String, code: String) = DshRpcException(code, "dsh $method: $code")

        fun items(method: String, value: JsonElement): JsonArray =
            (value as? JsonObject)?.get("items") as? JsonArray ?: throw rpcError(method, DshErrors.UNEXPECTED_ANSWER)

        fun field(method: String, value: JsonElement, key: String): String =
            (value as? JsonObject)?.string(key) ?: throw rpcError(method, DshErrors.UNEXPECTED_ANSWER)
    }
}
