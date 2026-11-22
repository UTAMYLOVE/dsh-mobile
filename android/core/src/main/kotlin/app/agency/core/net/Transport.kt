package app.agency.core.net

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** What one POST came back with. Bodies are never logged. */
sealed interface HttpOutcome {
    data class Response(val code: Int, val body: String) : HttpOutcome
    data class Blocked(val host: String) : HttpOutcome
    data object Offline : HttpOutcome
    data object TooLarge : HttpOutcome
}

/**
 * JSON POST with the one retry policy of the app: a 408, 429 or 5xx status or a timeout is retried once after [RETRY_DELAY_MS];
 * every other status (400, 401, 402, 403, 404, 422…) is returned at once. [sleep] is replaceable so tests do not wait.
 *
 * Cancelling the calling coroutine (he leaves the chat, the brain is cancelled) cancels the HTTP call at once: the socket is
 * closed and the retry is never made, instead of the call running on for up to the call timeout and maybe being billed.
 */
class Transport(
    private val http: OkHttpClient,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun postJson(url: HttpUrl, headers: Map<String, String>, body: String): HttpOutcome {
        val first = attempt(url, headers, body)
        if (!first.retryable) return first.outcome
        sleep(RETRY_DELAY_MS)
        return attempt(url, headers, body).outcome
    }

    private class Attempt(val outcome: HttpOutcome, val retryable: Boolean)

    private suspend fun attempt(url: HttpUrl, headers: Map<String, String>, body: String): Attempt {
        val request = try {
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody(JSON)).build()
        } catch (_: IllegalArgumentException) {
            return Attempt(HttpOutcome.Offline, retryable = false) // a header value OkHttp refuses; nothing was sent
        }
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(failed(e))

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { read(response) }
                    val error = result.exceptionOrNull()
                    when {
                        error == null -> cont.resume(result.getOrThrow())
                        error is IOException -> cont.resume(failed(error))
                        else -> cont.resumeWithException(error) // as execute() would have thrown it to the caller
                    }
                }
            })
        }
    }

    /** Reads the body on OkHttp's thread (the caller is suspended, not blocked), at most [MAX_BODY_BYTES]. */
    private fun read(response: Response): Attempt = response.use { r ->
        val source = r.body?.source() ?: return Attempt(HttpOutcome.Response(r.code, ""), retryable(r.code))
        if (source.request(MAX_BODY_BYTES + 1)) return Attempt(HttpOutcome.TooLarge, retryable = false)
        Attempt(HttpOutcome.Response(r.code, source.buffer.readUtf8()), retryable(r.code))
    }

    private fun failed(e: IOException): Attempt = when (e) {
        is BlockedHostException -> Attempt(HttpOutcome.Blocked(e.host), retryable = false)
        is InterruptedIOException -> Attempt(HttpOutcome.Offline, retryable = true) // socket or call timeout
        else -> Attempt(HttpOutcome.Offline, retryable = false)
    }

    private fun retryable(code: Int) = code == 408 || code == 429 || code >= 500

    companion object {
        const val RETRY_DELAY_MS = 2_000L
        const val MAX_BODY_BYTES = 4L shl 20
        private val JSON = "application/json".toMediaType()
    }
}
