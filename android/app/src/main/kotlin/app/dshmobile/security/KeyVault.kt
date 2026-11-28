package app.dshmobile.security

import android.content.Context
import app.agency.core.metering.Provider
import app.dshmobile.secure.KeyWrap
import app.dshmobile.secure.KeystoreWrap
import app.dshmobile.secure.SecretsFile
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * The two API keys he types in (Claude = [Provider.ANTHROPIC], DeepSeek = [Provider.DEEPSEEK]). They live only wrapped by the
 * Android Keystore. Implementations never log, print or put a key into an exception or a `toString`.
 */
interface KeyVault {
    /** Whether a key is stored, without opening it (screens ask on the main thread). [get] is the authority for using it. */
    fun has(p: Provider): Boolean

    /** The key, or null when none is stored or the Keystore can no longer open it (he types it again). Never throws. */
    fun get(p: Provider): String?

    /** Stores [key] trimmed; a blank key is ignored. */
    fun set(p: Provider, key: String)

    fun clear(p: Provider)
}

/**
 * [KeyVault] over a [SecretsFile] (one JSON file of Base64 blobs) and a [KeyWrap] (AES-256-GCM under a non-exportable Keystore
 * key). The provider is bound in as associated data, so a blob cannot be moved to the other provider. A plain key is never
 * written anywhere: the file holds wrapped bytes only. Robolectric has no AndroidKeyStore, so tests pass a JVM key wrap.
 */
class WrappedKeyVault(private val file: SecretsFile, private val wrap: KeyWrap) : KeyVault {
    override fun has(p: Provider): Boolean = try {
        file.read(name(p)) != null
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IOException) {
        false
    }

    override fun get(p: Provider): String? = try {
        val blob = file.read(name(p))
        blob?.let { String(wrap.unwrap(it, aad(p)), Charsets.UTF_8) }
    } catch (_: GeneralSecurityException) {
        null // e.g. the Keystore key was invalidated
    } catch (_: ProviderException) {
        null // Keystore failures that Android reports as a runtime exception
    } catch (_: IllegalArgumentException) {
        null // a damaged file
    } catch (_: IOException) {
        null
    }

    override fun set(p: Provider, key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) return
        file.write(name(p), wrap.wrap(clean.toByteArray(Charsets.UTF_8), aad(p)))
    }

    override fun clear(p: Provider) {
        file.remove(name(p))
    }

    override fun toString(): String = "WrappedKeyVault"

    private fun name(p: Provider) = "key-" + label(p)
    private fun aad(p: Provider) = ("xiaoyu-key:" + label(p)).toByteArray(Charsets.UTF_8)
    private fun label(p: Provider) = if (p == Provider.ANTHROPIC) "claude" else "deepseek"

    companion object {
        /** The app's vault: `noBackupFilesDir/xiaoyu_secrets.json`, wrapped by the Keystore key `xiaoyu-keys`. */
        fun forApp(context: Context): KeyVault =
            WrappedKeyVault(SecretsFile(File(context.noBackupFilesDir, SecretsFile.NAME)), KeystoreWrap(KeystoreWrap.KEYS_ALIAS))
    }
}
