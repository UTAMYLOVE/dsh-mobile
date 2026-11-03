package app.dshmobile.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** A newer shell app release: [sha256] is lower-case hex of the APK published next to it. */
data class ReleaseInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String)

/**
 * Asks the public releases repo whether a newer APK exists. Nothing is authenticated: the repo is public and no token
 * ever leaves the app. A release counts only if its tag is exactly `v<x.y.z>+<versionCode>` with a versionCode above
 * [currentVersionCode], it carries exactly one `.apk` and that name plus `.sha256`, both download URLs are on the
 * allow-list and the `.sha256` file starts with 64 hex digits. Anything else (including a non-2xx answer) is `null`.
 * Network failures are not swallowed: [latest] throws the [IOException], the caller logs it.
 */
class ApkUpdateChecker(
    http: OkHttpClient,
    private val repo: String,
    private val currentVersionCode: Int,
    private val apiBase: HttpUrl = "https://api.github.com".toHttpUrl(),
    private val allowedDownloadPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES,
) {
    // No https -> http downgrade on a redirect.
    private val http = http.newBuilder().followSslRedirects(false).build()

    suspend fun latest(): ReleaseInfo? {
        val releaseUrl = apiBase.newBuilder().addPathSegments("repos/$repo/releases/latest").build()
        val body = fetch(releaseUrl, "application/vnd.github+json", MAX_RELEASE_BYTES) ?: return null
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val tag = (root["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val match = TAG.matchEntire(tag) ?: return null
        val versionCode = match.groupValues[2].toIntOrNull() ?: return null
        if (versionCode <= currentVersionCode) return null

        val assets = (root["assets"] as? JsonArray)?.mapNotNull { asset ->
            val obj = asset as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            val url = (obj["browser_download_url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            name to url
        } ?: return null

        val apk = assets.filter { it.first.endsWith(".apk") }.singleOrNull() ?: return null
        val sha = assets.filter { it.first == apk.first + ".sha256" }.singleOrNull() ?: return null
        if (!isAllowed(apk.second, allowedDownloadPrefixes) || !isAllowed(sha.second, allowedDownloadPrefixes)) return null

        val shaUrl = sha.second.toHttpUrlOrNull() ?: return null
        val shaText = fetch(shaUrl, null, MAX_SHA_FILE_BYTES) ?: return null
        val hash = shaText.trim().split(WHITESPACE).firstOrNull()?.takeIf { HEX_64.matches(it) } ?: return null
        return ReleaseInfo(versionCode, match.groupValues[1], apk.second, hash.lowercase())
    }

    /** The body of a 2xx answer, or null for any other status or a body above [limit] bytes. */
    private suspend fun fetch(url: HttpUrl, accept: String?, limit: Long): String? = coroutineScope {
        val call = http.newCall(Request.Builder().url(url).get().apply { if (accept != null) header("Accept", accept) }.build())
        val exchange = async(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val source = response.body?.source() ?: return@use null
                    // A body longer than the limit is a refusal, not a truncated parse.
                    if (source.request(limit + 1)) null else source.readUtf8()
                }
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("update check cancelled").also { it.initCause(e) }
                throw e
            }
        }
        try {
            exchange.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    companion object {
        val DEFAULT_ALLOWED_PREFIXES = listOf("https://github.com/", "https://objects.githubusercontent.com/")
        private val TAG = Regex("""^v(\d+\.\d+\.\d+)\+(\d+)$""")
        private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
        private val WHITESPACE = Regex("\\s+")
        private const val MAX_RELEASE_BYTES = 1L * 1024 * 1024
        private const val MAX_SHA_FILE_BYTES = 4L * 1024

        /** True when [url] starts with one of [prefixes]. The prefixes end in a slash, so `https://github.com.evil/` never matches. */
        fun isAllowed(url: String, prefixes: List<String>): Boolean = prefixes.any { url.startsWith(it) }
    }
}
