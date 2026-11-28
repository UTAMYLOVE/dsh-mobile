package app.dshmobile.data

import android.content.Context
import app.dshmobile.secure.DataLockedException
import app.dshmobile.secure.KeyWrap
import app.dshmobile.secure.KeystoreWrap
import app.dshmobile.secure.SecretsFile
import app.dshmobile.secure.WrappedSecret
import java.io.File

/**
 * The database passphrase: 32 random bytes made once, stored only wrapped by the Keystore key `xiaoyu-db` in
 * `noBackupFilesDir/xiaoyu_secrets.json`. Never logged, never in SharedPreferences.
 */
class DbKey(secrets: SecretsFile, wrap: KeyWrap) {
    private val secret = WrappedSecret(secrets, NAME, wrap)

    /**
     * The raw 32 bytes. [dbExists]: the database file is already there, so a missing passphrase means it was lost and
     * [DataLockedException] is thrown instead of making a new one (which could never open the old file).
     */
    fun passphrase(dbExists: Boolean): ByteArray = secret.get(dbExists)

    /** SQLCipher's raw-key form `x'<64 hex>'`: the key is already random, so SQLCipher skips its password derivation. */
    fun sqlcipherKey(dbExists: Boolean): ByteArray = rawKey(passphrase(dbExists))

    companion object {
        const val NAME = "db"

        fun secretsFile(context: Context) = SecretsFile(File(context.noBackupFilesDir, SecretsFile.NAME))

        /** The app's passphrase source; every instance shares the process-wide lock of [SecretsFile]. */
        fun forApp(context: Context) = DbKey(secretsFile(context), KeystoreWrap(KeystoreWrap.DB_ALIAS))

        /** `x'<hex>'` of [raw]; [raw] is wiped. */
        fun rawKey(raw: ByteArray): ByteArray {
            val hex = raw.joinToString("") { "%02x".format(it) }
            raw.fill(0)
            return "x'$hex'".toByteArray(Charsets.US_ASCII)
        }
    }
}
