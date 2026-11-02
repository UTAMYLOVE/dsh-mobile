package app.dshmobile.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Proves a freshly started dsh is usable: the launch-token login works and both of our plugins answer their
 * authenticated health routes with `{"ok":true}`. Retries every [retryIntervalMs] until [timeoutMs] is used up.
 */
class HealthCheck(
    private val http: OkHttpClient,
    private val timeoutMs: Long = 60_000,
    private val retryIntervalMs: Long = 500,
) {
    suspend fun check(launch: LaunchUrl): Boolean = withTimeoutOrNull(timeoutMs) { poll(DshClient(http, launch)) } ?: false

    private suspend fun poll(client: DshClient): Boolean {
        var loggedIn = false
        while (true) {
            try {
                if (!loggedIn) {
                    client.login()
                    loggedIn = true
                }
                if (ROUTES.all { isOk(client, it) }) return true
            } catch (_: IOException) {
                // dsh is probably still booting; try again.
            }
            delay(retryIntervalMs)
        }
    }

    private suspend fun isOk(client: DshClient, path: String): Boolean =
        client.get(path).use { r ->
            if (r.code != 200) return false
            val body = try {
                Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
            (body?.get("ok") as? JsonPrimitive)?.content == "true"
        }

    private companion object {
        val ROUTES = listOf("/dshm/health/memory", "/dshm/health/notify")
    }
}
