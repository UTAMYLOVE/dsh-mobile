package app.dshmobile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloads a [ReleaseInfo]'s APK into `cacheDir/update/`, verifies its SHA-256 and hands it to the system installer.
 * A download whose hash differs is deleted and never offered for install. The APK reaches the installer through the
 * app's FileProvider (`<applicationId>.export`, which exposes `cache/update/` next to `cache/export/`); no permission is
 * added: `REQUEST_INSTALL_PACKAGES` already exists and Android asks the user for every install. Android itself also
 * refuses an update that is not signed with this app's key, so the hash only guards against a damaged or swapped download.
 */
class ApkInstaller(
    private val context: Context,
    http: OkHttpClient,
    private val allowedPrefixes: List<String> = ApkUpdateChecker.DEFAULT_ALLOWED_PREFIXES,
) {
    // No https -> http downgrade on a redirect (GitHub redirects release assets to its own CDN host).
    private val client = http.newBuilder().followSslRedirects(false).build()
    private val updateDir: File get() = File(context.cacheDir, "update")

    /**
     * `cacheDir/update/dsh-mobile-<versionCode>.apk` once its SHA-256 equals [ReleaseInfo.sha256]; null for a disallowed URL,
     * a failed or truncated transfer or a hash mismatch (the partial file is deleted). Files of other versions are removed;
     * an `.apk` already there that matches the hash is reused without a new download.
     */
    suspend fun download(info: ReleaseInfo): File? = coroutineScope {
        val call = client.newCall(
            Request.Builder().url(info.apkUrl.takeIf { ApkUpdateChecker.isAllowed(it, allowedPrefixes) }?.toHttpUrlOrNull() ?: return@coroutineScope null).get().build(),
        )
        val work = async(Dispatchers.IO) {
            try {
                fetch(info, call)
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("download cancelled").also { it.initCause(e) }
                null
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            call.cancel() // unblocks the thread reading the body
            throw e
        }
    }

    private fun fetch(info: ReleaseInfo, call: okhttp3.Call): File? {
        val expected = info.sha256.lowercase()
        val dir = updateDir.also { it.mkdirs() }
        val target = File(dir, "dsh-mobile-${info.versionCode}.apk")
        val part = File(dir, "${target.name}.part")
        if (target.isFile && sha256Of(target) == expected) {
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            return target
        }
        dir.listFiles()?.forEach { it.delete() }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                var total = 0L
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) return null
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (digest.digest().toHex() != expected) return null
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            part.delete()
        }
    }

    /**
     * Opens the system installer for [apk] (a file in `cache/update/`, else [IllegalArgumentException]). If this app may not
     * install packages yet, the "install unknown apps" settings page for it opens instead; the user comes back and taps again.
     */
    fun install(apk: File) {
        context.startActivity(buildInstallIntent(apk).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun buildInstallIntent(apk: File): Intent {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val BUF = 64 * 1024
        private const val MAX_BYTES = 1L * 1024 * 1024 * 1024
    }
}
