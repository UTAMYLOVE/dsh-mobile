package app.dshmobile.agency.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.agency.data.DbKey
import app.dshmobile.agency.media.MediaVault
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database passphrase and the media key are kept only wrapped: no file of the app (secrets file, SharedPreferences,
 * anything) holds their 32 raw bytes or a Base64 or hex form of them. A JVM AES key stands in for the Keystore key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PassphraseNotPlainTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wrapKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val wrap = SecretKeyWrap { wrapKey }

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { i -> part.indices.all { this[i + it] == part[it] } }

    private fun forms(secret: ByteArray): List<ByteArray> {
        val hex = secret.joinToString("") { "%02x".format(it) }
        return listOf(
            secret,
            Base64.getEncoder().encode(secret),
            Base64.getEncoder().withoutPadding().encode(secret),
            Base64.getUrlEncoder().encode(secret),
            Base64.getUrlEncoder().withoutPadding().encode(secret),
            Base64.getMimeEncoder().encode(secret),
            hex.toByteArray(),
            hex.uppercase().toByteArray(),
        )
    }

    @Test fun noFileHoldsTheSecretsUnwrapped() {
        val secrets = DbKey.secretsFile(context)
        val passphrase = DbKey(secrets, wrap).passphrase(dbExists = false)
        val mediaKey = WrappedSecret(secrets, MediaVault.KEY_NAME, wrap).get(dataExists = false)
        assertEquals(32, passphrase.size)
        assertEquals(32, mediaKey.size)
        // Something else of the app's writes prefs too, so the scan covers real SharedPreferences files.
        context.getSharedPreferences("agency_prefs", Context.MODE_PRIVATE).edit().putString("probe", "x").commit()

        val root = context.filesDir.parentFile!!
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val secretsFile = File(context.noBackupFilesDir, SecretsFile.NAME)
        assertTrue("the secrets file is written", secretsFile in files)
        assertTrue("prefs were scanned", files.any { it.parentFile?.name == "shared_prefs" })
        for (f in files) {
            val bytes = f.readBytes()
            for (secret in listOf(passphrase, mediaKey)) {
                for (form in forms(secret)) assertTrue("${f.name} holds a secret in the clear", !bytes.contains(form))
            }
        }
    }

    @Test fun theSqlcipherKeyIsTheHexOfThePassphrase() {
        val key = DbKey(DbKey.secretsFile(context), wrap)
        val raw = key.passphrase(dbExists = false)
        val sql = String(key.sqlcipherKey(dbExists = true), Charsets.US_ASCII)
        assertEquals("x'" + raw.joinToString("") { "%02x".format(it) } + "'", sql)
    }
}
