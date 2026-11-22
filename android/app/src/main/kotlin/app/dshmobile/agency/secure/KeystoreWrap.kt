package app.dshmobile.agency.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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
 * The cipher picks the IV (the Keystore requires that), so an IV is never reused by our code.
 */
object Gcm {
    const val VERSION: Byte = 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV size" }
        return byteArrayOf(VERSION) + iv + body
    }

    fun open(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != VERSION) throw GeneralSecurityException("not a sealed blob")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }
}

/** A [KeyWrap] over a key it is handed (tests use a plain JVM AES key; the app uses [KeystoreWrap]). */
open class SecretKeyWrap(private val key: () -> SecretKey) : KeyWrap {
    override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray = Gcm.seal(key(), plain, aad)
    override fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray = Gcm.open(key(), blob, aad)
}

/**
 * A non-exportable AES-256-GCM key in the Android Keystore under [alias] (`agency-db`, `agency-media`), created on first use.
 * Not bound to the lock screen: alarms read the database while the phone is locked. Not unit-tested: Robolectric has no
 * AndroidKeyStore (the instrumented `AgencyDatabaseEncryptedTest` uses it).
 */
class KeystoreWrap(alias: String) : SecretKeyWrap({ keystoreKey(alias) }) {
    companion object {
        const val DB_ALIAS = "agency-db"
        const val MEDIA_ALIAS = "agency-media"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        @Synchronized private fun keystoreKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
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
}

/** The stored secrets exist but cannot be opened (the Keystore key is gone), or data exists whose secret is missing. */
class DataLockedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `noBackupFilesDir/agency_secrets.json`: name → Base64 of a wrapped blob. Only wrapped bytes are ever written here.
 * Written through [AtomicFile], so a crash mid-write keeps the previous content.
 */
class SecretsFile(file: File) {
    private val atomic = AtomicFile(file)

    @Synchronized fun read(name: String): ByteArray? =
        all()[name]?.let { Base64.decode(it, Base64.NO_WRAP) }

    @Synchronized fun write(name: String, wrapped: ByteArray) {
        val next = all() + (name to Base64.encodeToString(wrapped, Base64.NO_WRAP))
        val json = JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString()
        val out = atomic.startWrite()
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun all(): Map<String, String> {
        if (!atomic.baseFile.exists()) return emptyMap()
        val text = String(atomic.readFully(), Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    companion object {
        const val NAME = "agency_secrets.json"
    }
}

/**
 * A 32-byte random secret, created once, kept only wrapped by [wrap] in [file] under [name]. It is never replaced: when data
 * sealed under it exists ([get]'s `dataExists`) a missing or unopenable secret is [DataLockedException], and nothing is wiped.
 */
class WrappedSecret(
    private val file: SecretsFile,
    private val name: String,
    private val wrap: KeyWrap,
    private val random: SecureRandom = SecureRandom(),
) {
    private val aad = "agency-secret:$name".toByteArray(Charsets.UTF_8)

    @Synchronized fun get(dataExists: Boolean): ByteArray {
        val stored = try {
            file.read(name)
        } catch (e: IllegalArgumentException) {
            throw DataLockedException("the secrets file is unreadable", e) // corrupt Base64 or JSON
        }
        if (stored == null) {
            if (dataExists) throw DataLockedException("the secret for existing data is missing")
            val fresh = ByteArray(SIZE).also(random::nextBytes)
            file.write(name, wrap.wrap(fresh, aad))
            return fresh
        }
        return try {
            wrap.unwrap(stored, aad)
        } catch (e: GeneralSecurityException) {
            throw DataLockedException("the stored secret cannot be opened", e)
        } catch (e: ProviderException) {
            throw DataLockedException("the stored secret cannot be opened", e)
        }
    }

    companion object {
        const val SIZE = 32
    }
}
