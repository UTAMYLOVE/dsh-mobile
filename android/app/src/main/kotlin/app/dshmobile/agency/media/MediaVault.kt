package app.dshmobile.agency.media

import android.content.Context
import app.dshmobile.agency.data.DbKey
import app.dshmobile.agency.secure.Gcm
import app.dshmobile.agency.secure.KeystoreWrap
import app.dshmobile.agency.secure.WrappedSecret
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** A media file is missing, was changed on disk, or was sealed under another key. */
class MediaVaultException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Every photo and recording, AES-256-GCM encrypted as `<dir>/<id>.bin` (`version | IV | ciphertext + tag`, the id bound in as
 * associated data so files cannot be swapped). The data key comes from [dataKey]: in the app a random key wrapped by the
 * Keystore key `agency-media` ([forApp]); in tests a plain JVM key. Plaintext only ever exists in memory or, briefly, in the
 * camera cache ([sweepCameraCache]).
 */
class MediaVault(private val dir: File, private val dataKey: () -> SecretKey) {
    /** What was sealed: size and SHA-256 (hex) of the plaintext, for the `media` row. */
    data class Sealed(val bytes: Long, val sha256: String)

    fun put(id: String, plain: ByteArray): Sealed {
        val target = fileOf(id)
        dir.mkdirs()
        val tmp = File(dir, "$id.bin.tmp")
        tmp.writeBytes(Gcm.seal(dataKey(), plain, aad(id)))
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not store media $id")
        }
        return Sealed(plain.size.toLong(), sha256(plain))
    }

    /** Seals the plaintext file [plain] (e.g. a camera shot) and deletes it, also when sealing fails. */
    fun putFile(id: String, plain: File): Sealed = try {
        put(id, plain.readBytes())
    } finally {
        plain.delete()
    }

    fun get(id: String): ByteArray {
        val file = fileOf(id)
        if (!file.isFile) throw MediaVaultException("no media $id")
        return try {
            Gcm.open(dataKey(), file.readBytes(), aad(id))
        } catch (e: GeneralSecurityException) {
            throw MediaVaultException("media $id cannot be opened", e)
        }
    }

    fun has(id: String): Boolean = fileOf(id).isFile

    fun delete(id: String): Boolean = fileOf(id).delete()

    private fun fileOf(id: String): File {
        require(ID.matches(id)) { "bad media id" }
        return File(dir, "$id.bin")
    }

    private fun aad(id: String) = "agency-media:$id".toByteArray(Charsets.UTF_8)

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val KEY_NAME = "media"

        fun mediaDir(context: Context) = File(context.noBackupFilesDir, "agency/media")

        /** Where the system camera writes through the FileProvider (`cache/camera/<uuid>.jpg`). */
        fun cameraDir(context: Context) = File(context.cacheDir, "camera")

        fun forApp(context: Context): MediaVault {
            val dir = mediaDir(context)
            val secret = WrappedSecret(DbKey.secretsFile(context), KEY_NAME, KeystoreWrap(KeystoreWrap.MEDIA_ALIAS))
            val key by lazy {
                val sealedFilesExist = dir.listFiles { f -> f.name.endsWith(".bin") }?.isNotEmpty() == true
                val raw = secret.get(sealedFilesExist)
                SecretKeySpec(raw, "AES").also { raw.fill(0) }
            }
            return MediaVault(dir) { key }
        }

        /**
         * Deletes every plaintext file left in the camera cache [dir] except the names in [keep] (a capture still waiting for
         * its result); returns how many files went. Meant for every start (the start-up wiring arrives with the cutover).
         */
        fun sweepCameraCache(dir: File, keep: Set<String> = emptySet()): Int =
            dir.listFiles()?.count { it.name !in keep && it.deleteRecursively() } ?: 0

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
