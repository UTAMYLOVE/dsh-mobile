package app.dshmobile.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP client for the one dsh instance behind [launch]: exchanges the launch token for dsh's session cookie
 * ([login]) and sends later requests with it. It only ever talks to `127.0.0.1:<port>`, keeps cookies for that
 * origin only and never follows a redirect to another origin. Nothing here puts the launch URL (it carries the
 * token) into an exception message.
 */
class DshClient(base: OkHttpClient, private val launch: LaunchUrl) {
    private val origin: HttpUrl = "http://127.0.0.1:${launch.port}/".toHttpUrl()
    private val jar = LoopbackCookieJar(origin)
    private val client = base.newBuilder()
        .cookieJar(jar)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** GET the launch URL, follow dsh's 303 to `/` and require a final 200. */
    suspend fun login() {
        var url: HttpUrl = launch.url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            requireOrigin(url)
            val response = client.newCall(Request.Builder().url(url).get().build()).await()
            response.use { r ->
                if (r.isRedirect) {
                    val location = r.header("Location") ?: throw IOException("dsh login redirect without Location")
                    url = url.resolve(location) ?: throw IOException("dsh login redirect to an invalid URL")
                } else if (r.code == 200) {
                    return
                } else {
                    throw IOException("dsh login failed: HTTP ${r.code}")
                }
            }
        }
        throw IOException("dsh login: too many redirects")
    }

    /** The caller closes the response. */
    suspend fun get(path: String): Response = client.newCall(request(path).get().build()).await()

    /** The caller closes the response. */
    suspend fun postJson(path: String, json: String): Response =
        client.newCall(request(path).post(json.toRequestBody(JSON)).build()).await()

    /**
     * POST [json] and read the answer as text (at most [MAX_TEXT_BYTES] of it) without leaving anything open. Unlike
     * [postJson] the whole exchange, body included, is cancelled with the calling coroutine, so a timeout around it
     * also holds while the server dribbles its body.
     */
    suspend fun postJsonForText(path: String, json: String): TextResponse = coroutineScope {
        val call = client.newCall(request(path).post(json.toRequestBody(JSON)).build())
        val exchange = async(Dispatchers.IO) {
            try {
                call.execute().use { TextResponse(it.code, it.peekBody(MAX_TEXT_BYTES).string()) }
            } catch (e: IOException) {
                // Our own cancel() surfaces as an IOException; it must not outrank the cancellation that caused it.
                if (call.isCanceled()) throw CancellationException("dsh request cancelled").also { it.initCause(e) }
                throw e
            }
        }
        try {
            exchange.await()
        } catch (e: CancellationException) {
            call.cancel() // unblocks the thread reading the body
            throw e
        }
    }

    /** Status code and (bounded) body text of a finished exchange. */
    data class TextResponse(val code: Int, val body: String)

    private fun request(path: String): Request.Builder {
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains('\\')) { "path must be absolute on dsh" }
        val url = origin.resolve(path)
        require(url != null && sameOrigin(url)) { "path leaves the dsh origin" }
        return Request.Builder().url(url)
    }

    private fun requireOrigin(url: HttpUrl) {
        if (!sameOrigin(url)) throw IOException("dsh login redirected away from dsh")
    }

    private fun sameOrigin(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port

    /** Keeps cookies for the dsh origin in memory, drops everything else. */
    private class LoopbackCookieJar(private val origin: HttpUrl) : CookieJar {
        private val cookies = ArrayList<Cookie>()

        private fun HttpUrl.isOrigin() = host == origin.host && port == origin.port

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!url.isOrigin()) return
            for (c in cookies) {
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.expiresAt > System.currentTimeMillis()) this.cookies.add(c)
            }
        }

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!url.isOrigin()) return emptyList()
            val now = System.currentTimeMillis()
            return cookies.filter { it.expiresAt > now && it.matches(url) }
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_TEXT_BYTES = 1L shl 20
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
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
