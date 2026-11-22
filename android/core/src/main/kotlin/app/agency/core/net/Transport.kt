package app.agency.core.net

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
 */
class Transport(
    private val http: OkHttpClient,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun postJson(url: HttpUrl, headers: Map<String, String>, body: String): HttpOutcome = withContext(Dispatchers.IO) {
        val first = attempt(url, headers, body)
        if (!first.retryable) return@withContext first.outcome
        sleep(RETRY_DELAY_MS)
        attempt(url, headers, body).outcome
    }

    private class Attempt(val outcome: HttpOutcome, val retryable: Boolean)

    private fun attempt(url: HttpUrl, headers: Map<String, String>, body: String): Attempt {
        val request = try {
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody(JSON)).build()
        } catch (_: IllegalArgumentException) {
            return Attempt(HttpOutcome.Offline, retryable = false) // a header value OkHttp refuses; nothing was sent
        }
        return try {
            http.newCall(request).execute().use { r ->
                val source = r.body?.source() ?: return Attempt(HttpOutcome.Response(r.code, ""), retryable(r.code))
                if (source.request(MAX_BODY_BYTES + 1)) return Attempt(HttpOutcome.TooLarge, retryable = false)
                Attempt(HttpOutcome.Response(r.code, source.buffer.readUtf8()), retryable(r.code))
            }
        } catch (e: BlockedHostException) {
            Attempt(HttpOutcome.Blocked(e.host), retryable = false)
        } catch (_: InterruptedIOException) {
            Attempt(HttpOutcome.Offline, retryable = true) // socket or call timeout
        } catch (_: IOException) {
            Attempt(HttpOutcome.Offline, retryable = false)
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun retryable(code: Int) = code == 408 || code == 429 || code >= 500

    companion object {
        const val RETRY_DELAY_MS = 2_000L
        const val MAX_BODY_BYTES = 4L shl 20
        private val JSON = "application/json".toMediaType()
    }
}
