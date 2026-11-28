package app.dshmobile.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.ShortBufferException
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encrypts small secrets (the database passphrase, the media data key) under a key that never leaves its holder. */
interface KeyWrap {
    fun wrap(plain: ByteArray, aad: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] (or a Keystore [ProviderException]) when [blob] was not sealed by this key. */
    fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM sealing shared by the key wraps and the media vault: `version (1 byte) | IV (12 bytes) | ciphertext + tag`.
 * The version byte is authenticated too (it leads the associated data), so a later format cannot be confused with this one.
 * The cipher picks the IV (the Keystore requires that), so an IV is never reused by our code.
 */
object Gcm {
    const val VERSION: Byte = 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * Allocates one output array, `version | IV | ciphertext + tag`, and encrypts straight into it, so sealing a large photo
     * or recording holds only the plaintext and that one sealed copy (plus what the cipher itself buffers).
     */
    fun seal(key: SecretKey, plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected IV size" }
        cipher.updateAAD(byteArrayOf(VERSION))
        cipher.updateAAD(aad)
        val head = 1 + IV_BYTES
        val out = ByteArray(head + cipher.getOutputSize(plain.size))
        out[0] = VERSION
        iv.copyInto(out, 1)
        val n = try {
            cipher.doFinal(plain, 0, plain.size, out, head)
        } catch (_: ShortBufferException) {
            // The cipher gave too small a size: the call may be repeated (the cipher is not reset) with its own buffer.
            return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
        }
        return if (head + n == out.size) out else out.copyOf(head + n)
    }

    fun open(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != VERSION) throw GeneralSecurityException("not a sealed blob")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        cipher.updateAAD(blob, 0, 1)
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }
}

/** A [KeyWrap] over a key it is handed (tests use a plain JVM AES key; the app uses [KeystoreWrap]). */
open class SecretKeyWrap(private val key: () -> SecretKey) : KeyWrap {
    override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray = Gcm.seal(key(), plain, aad)
    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray = Gcm.open(key(), blob, aad)
}

/** Where [KeystoreWrap] finds its keys: the Android Keystore in the app, a map in JVM tests. */
internal interface AliasKeys {
    /** The key under [alias], or null when there is none. Never creates one. */
    fun find(alias: String): SecretKey?

    /** The key under [alias], created when there is none. */
    fun findOrCreate(alias: String): SecretKey
}

/**
 * A non-exportable AES-256-GCM key in the Android Keystore under [alias] (`xiaoyu-db`, `xiaoyu-media`, `xiaoyu-keys`). The alias is created
 * only by [wrap], i.e. for a new secret: [unwrap] never creates a key (that would replace the one the blob was sealed with), so
 * a missing key is a [KeyStoreException], which [WrappedSecret] reports as [DataLockedException].
 * Not bound to the lock screen: alarms read the database while the phone is locked. Robolectric has no AndroidKeyStore, so
 * JVM tests hand in [AliasKeys]; a device test uses the real one.
 */
class KeystoreWrap internal constructor(private val alias: String, private val keys: AliasKeys) : KeyWrap {
    constructor(alias: String) : this(alias, AndroidKeystoreKeys)

    override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray = Gcm.seal(keys.findOrCreate(alias), plain, aad)

    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray =
        Gcm.open(keys.find(alias) ?: throw KeyStoreException("no Keystore key $alias"), blob, aad)

    companion object {
        const val DB_ALIAS = "xiaoyu-db"
        const val MEDIA_ALIAS = "xiaoyu-media"
        const val KEYS_ALIAS = "xiaoyu-keys"
    }
}

private object AndroidKeystoreKeys : AliasKeys {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Synchronized override fun find(alias: String): SecretKey? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

    @Synchronized override fun findOrCreate(alias: String): SecretKey {
        find(alias)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }
}

/**
 * The app's data cannot be opened now; nothing was deleted or replaced. Two kinds, so the app can tell them apart:
 * [DataLockedException] does not go away by trying again (「資料鎖打不開」); [TransientDataException] may (offer 「再試一次」).
 */
sealed class DataUnavailableException(message: String, cause: Throwable?) : Exception(message, cause)

/**
 * The key is lost: the stored secret cannot be opened (its Keystore key is gone), data exists whose secret is missing, the
 * secrets file is damaged, or the database file does not open with its key (or is damaged). Trying again does not help.
 */
class DataLockedException(message: String, cause: Throwable? = null) : DataUnavailableException(message, cause)

/** A passing failure (disk full, an I/O error, the file busy) reading or writing the data or its secret: try again later. */
class TransientDataException(message: String, cause: Throwable? = null) : DataUnavailableException(message, cause)

/**
 * `noBackupFilesDir/xiaoyu_secrets.json` (the 0.9 file `agency_secrets.json` is only read by OldData): name → Base64 of a wrapped blob. Only wrapped bytes are ever written here.
 * Every instance shares one process-wide [LOCK] (callers each build their own instance over the same file), so two writers
 * can never drop each other's entries. Read through [AtomicFile] (which drops a stale `.new` file); written by [write]
 * itself, because AtomicFile only logs a failed sync or rename. [sync], [rename] and [syncDir] are seams for tests.
 */
class SecretsFile internal constructor(
    file: File,
    private val sync: (FileDescriptor) -> Unit,
    private val rename: (File, File) -> Boolean,
    private val syncDir: (File) -> Unit,
) {
    constructor(file: File) : this(file, FileDescriptor::sync, File::renameTo, Durable::syncDir)

    private val atomic = AtomicFile(file)

    fun read(name: String): ByteArray? = synchronized(LOCK) {
        all()[name]?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    /**
     * Writes the whole file anew: `<file>.new`, synced to disk, renamed over the old file, then the folder synced. A failed
     * write, sync or rename throws [IOException] and leaves the old file as it was, so no secret is handed out that may never
     * reach the disk.
     */
    fun write(name: String, wrapped: ByteArray): Unit = synchronized(LOCK) {
        commit(all() + (name to Base64.encodeToString(wrapped, Base64.NO_WRAP)))
    }

    /** Drops [name] (the same atomic rewrite as [write]); nothing happens when it is not there. */
    fun remove(name: String): Unit = synchronized(LOCK) {
        val now = all()
        if (name in now) commit(now - name)
    }

    private fun commit(next: Map<String, String>) {
        val bytes = JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
        val base = atomic.baseFile
        val folder = base.absoluteFile.parentFile
        folder?.mkdirs()
        val tmp = File(base.path + ".new")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                sync(out.fd)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        if (!rename(tmp, base)) {
            tmp.delete()
            throw IOException("could not store the secrets")
        }
        folder?.let(syncDir)
    }

    private fun all(): Map<String, String> {
        if (!atomic.baseFile.exists()) return emptyMap()
        val text = String(atomic.readFully(), Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    companion object {
        const val NAME = "xiaoyu_secrets.json"

        /** Guards every read and write of every secrets file, and [WrappedSecret.get]'s whole read → generate → write. */
        internal val LOCK = Any()
    }
}

/**
 * A 32-byte random secret, created once, kept only wrapped by [wrap] in [file] under [name]. It is never replaced: when data
 * sealed under it exists ([get]'s `dataExists`) a missing or unopenable secret is [DataLockedException], and nothing is wiped.
 * [get] holds [SecretsFile.LOCK] from the read to the write, so concurrent first runs (any number of instances) make one
 * secret and all return it.
 */
class WrappedSecret(
    private val file: SecretsFile,
    private val name: String,
    private val wrap: KeyWrap,
    private val random: SecureRandom = SecureRandom(),
) {
    private val aad = "agency-secret:$name".toByteArray(Charsets.UTF_8)

    /**
     * @throws DataLockedException when the secret is lost (see there).
     * @throws TransientDataException when the secrets file or the Keystore could not be read or written (nothing is stored
     *   then, and the next call tries again).
     */
    fun get(dataExists: Boolean): ByteArray = synchronized(SecretsFile.LOCK) {
        try {
            val stored = try {
                file.read(name)
            } catch (e: IllegalArgumentException) {
                throw DataLockedException("the secrets file is unreadable", e) // corrupt Base64 or JSON
            }
            if (stored == null) {
                if (dataExists) throw DataLockedException("the secret for existing data is missing")
                val fresh = ByteArray(SIZE).also(random::nextBytes)
                file.write(name, wrap.wrap(fresh, aad))
                return@synchronized fresh
            }
            try {
                wrap.unwrap(stored, aad)
            } catch (e: GeneralSecurityException) {
                throw DataLockedException("the stored secret cannot be opened", e)
            } catch (e: ProviderException) {
                throw DataLockedException("the stored secret cannot be opened", e)
            }
        } catch (e: IOException) {
            throw TransientDataException("the secret could not be read or stored", e)
        }
    }

    companion object {
        const val SIZE = 32
    }
}
