package app.dshmobile.agency.data

import android.content.Context
import app.dshmobile.agency.secure.DataLockedException
import app.dshmobile.agency.secure.KeyWrap
import app.dshmobile.agency.secure.KeystoreWrap
import app.dshmobile.agency.secure.SecretsFile
import app.dshmobile.agency.secure.WrappedSecret
import java.io.File

/**
 * The database passphrase: 32 random bytes made once, stored only wrapped by the Keystore key `agency-db` in
 * `noBackupFilesDir/agency_secrets.json`. Never logged, never in SharedPreferences.
 */
class DbKey(secrets: SecretsFile, wrap: KeyWrap) {
    private val secret = WrappedSecret(secrets, NAME, wrap)

    /**
     * The raw 32 bytes. [dbExists]: the database file is already there, so a missing passphrase means it was lost and
     * [DataLockedException] is thrown instead of making a new one (which could never open the old file).
     */
    fun passphrase(dbExists: Boolean): ByteArray = secret.get(dbExists)

    /** SQLCipher's raw-key form `x'<64 hex>'`: the key is already random, so SQLCipher skips its password derivation. */
    fun sqlcipherKey(dbExists: Boolean): ByteArray {
        val raw = passphrase(dbExists)
        val hex = raw.joinToString("") { "%02x".format(it) }
        raw.fill(0)
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        const val NAME = "db"

        fun secretsFile(context: Context) = SecretsFile(File(context.noBackupFilesDir, SecretsFile.NAME))

        fun forApp(context: Context) = DbKey(secretsFile(context), KeystoreWrap(KeystoreWrap.DB_ALIAS))
    }
}
