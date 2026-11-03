package app.dshmobile.ui

import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Proxy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saves a WebView download from the local dsh into `cacheDir/export/` (the directory the share sheet reads from).
 * Only `http://127.0.0.1:<dshPort>/...` is fetched, with the WebView's cookie, without following redirects. Other
 * sources, `blob:` URLs included, are [Result.Unsupported].
 */
class DownloadHandler(
    private val exportDir: () -> File,
    http: OkHttpClient,
    private val dshPort: () -> Int,
    private val cookies: (url: String) -> String?,
    private val maxBytes: Long = MAX_BYTES,
) {
    sealed interface Result {
        data class Saved(val file: File) : Result
        data object Unsupported : Result
        /** [reason] never contains the URL. */
        data class Failed(val reason: String) : Result
    }

    // Loopback only: a system or Wi-Fi proxy must never be asked for 127.0.0.1.
    private val client = http.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).build()

    suspend fun download(url: String, contentDisposition: String?, mime: String?): Result {
        if (DshWebViewClient.decide(url, dshPort()) != DshWebViewClient.Decision.ALLOW) return Result.Unsupported
        return withContext(Dispatchers.IO) { fetch(url, contentDisposition, mime) }
    }

    private fun fetch(url: String, contentDisposition: String?, mime: String?): Result {
        val dir = exportDir()
        val part = File(dir, ".download-${System.nanoTime()}.part")
        try {
            dir.mkdirs()
            val request = Request.Builder().url(url).get().apply { cookies(url)?.let { header("Cookie", it) } }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Result.Failed("HTTP ${response.code}")
                val body = response.body ?: return Result.Failed("empty response")
                var total = 0L
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return Result.Failed("file too large")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val target = File(dir, sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mime)))
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return Result.Saved(target)
        } catch (e: IOException) {
            return Result.Failed("network or file error")
        } catch (e: IllegalArgumentException) {
            return Result.Failed("invalid download")
        } finally {
            part.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        private const val BUF = 16 * 1024
        private const val MAX_NAME = 120

        /** A plain file name: path separators and control characters removed, never empty, `.` or `..`, at most 120 chars. */
        fun sanitizeFileName(raw: String?): String {
            val cleaned = (raw ?: "").filterNot { it == '/' || it == '\\' || it.isISOControl() }.trim()
            if (cleaned.isEmpty() || cleaned.all { it == '.' }) return "download"
            if (cleaned.length <= MAX_NAME) return cleaned
            val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }?.let { ".$it" }.orEmpty()
            return cleaned.take(MAX_NAME - ext.length) + ext
        }
    }
}
