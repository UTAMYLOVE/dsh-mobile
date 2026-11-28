package app.dshmobile.wire

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.agency.core.metering.Provider
import app.dshmobile.security.KeyVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One-time carry-over of the API keys 0.9 saved, so he does not have to type them again. 0.9 kept each key in the preferences
 * file `dshm_keys` (entry name = `ANTHROPIC` / `DEEPSEEK`) as `Base64(iv):Base64(ciphertext)`, AES-256-GCM under the Keystore key
 * `dshm-keyvault`, the entry name as associated data. [run] copies into the new vault only when the new vault holds no key at
 * all; any failure (no old file, the Keystore key gone, a damaged value) is silent and changes nothing. The old preferences are
 * left alone (the old-data page deletes them with the rest). No key is ever logged or put into an exception.
 */
class LegacyKeys(
    private val prefs: () -> SharedPreferences?,
    private val secretKey: () -> SecretKey?,
) {
    fun run(into: KeyVault) {
        runCatching {
            if (into.has(Provider.ANTHROPIC) || into.has(Provider.DEEPSEEK)) return
            val p = prefs() ?: return
            for (provider in Provider.entries) {
                val key = read(p, provider.name) ?: continue
                into.set(provider, key)
            }
        }
    }

    private fun read(p: SharedPreferences, name: String): String? = runCatching {
        val stored = p.getString(name, null) ?: return null
        val (iv, ciphertext) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey() ?: return null, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray())
        String(cipher.doFinal(ciphertext), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        const val PREFS = "dshm_keys"
        const val ALIAS = "dshm-keyvault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** The real source: 0.9's preferences and its Keystore key (null when that key does not exist: it is never created here). */
        fun forApp(context: Context): LegacyKeys = LegacyKeys(
            prefs = { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).takeIf { it.all.isNotEmpty() } },
            secretKey = {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey
            },
        )
    }
}
