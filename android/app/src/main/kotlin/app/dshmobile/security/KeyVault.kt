package app.dshmobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.dshmobile.gateway.Provider
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Holds the real provider API keys. Implementations must never log or print a key. */
interface KeyVault {
    fun get(p: Provider): String?
    fun put(p: Provider, key: String)
    fun clear(p: Provider)
}

/**
 * Keys encrypted with an AES-256-GCM key that lives in the Android Keystore (never leaves it) and stored as
 * `Base64(iv):Base64(ciphertext)` in SharedPreferences. The provider name is bound in as associated data.
 * Not unit-tested: Robolectric has no AndroidKeyStore.
 */
class AndroidKeyVault(context: Context) : KeyVault {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized override fun get(p: Provider): String? {
        val stored = prefs.getString(p.name, null) ?: return null
        return decryptOrNull {
            val (iv, ciphertext) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(p.name.toByteArray())
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    }

    @Synchronized override fun put(p: Provider, key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(p.name.toByteArray())
        val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(p.name, stored).apply()
    }

    @Synchronized override fun clear(p: Provider) {
        prefs.edit().remove(p.name).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    internal companion object {
        /**
         * Runs a decrypt; any failure means "no key set" (the user enters it again), never an exception: the gateway calls
         * [get] for every request and would otherwise answer each with a 500.
         */
        internal fun <T> decryptOrNull(decrypt: () -> T): T? = try {
            decrypt()
        } catch (_: GeneralSecurityException) {
            null // e.g. the Keystore key was invalidated
        } catch (_: ProviderException) {
            null // Keystore failures that Android reports as a runtime exception
        } catch (_: IllegalArgumentException) {
            null // corrupt stored value
        } catch (_: IndexOutOfBoundsException) {
            null // corrupt stored value
        }

        private const val ALIAS = "dshm-keyvault"
        private const val PREFS = "dshm_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
