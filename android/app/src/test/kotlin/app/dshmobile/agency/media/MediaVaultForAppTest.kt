package app.dshmobile.agency.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.agency.secure.DataLockedException
import app.dshmobile.agency.secure.KeyWrap
import app.dshmobile.agency.secure.SecretKeyWrap
import app.dshmobile.agency.secure.SecretsFile
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review M6: the app vault's key-loss rule (sealed files exist, so a missing or unopenable data key is locked). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaVaultForAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun newWrap(): KeyWrap = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { k -> SecretKeyWrap { k } }
    private val secretsFile = File(context.noBackupFilesDir, SecretsFile.NAME)
    private val sealedFile get() = File(MediaVault.mediaDir(context), "p-1.bin")
    private val photo = ByteArray(4_000) { (it * 7 % 253).toByte() }

    private fun assertLocked(body: () -> Unit) {
        try {
            body()
            fail("expected DataLockedException")
        } catch (_: DataLockedException) {
        }
    }

    private fun entries() = Json.parseToJsonElement(secretsFile.readText()).jsonObject.keys

    @Test fun aVaultReopensWithItsOwnKey() {
        val wrap = newWrap()
        MediaVault.forApp(context, wrap).put("p-1", photo)
        assertArrayEquals(photo, MediaVault.forApp(context, wrap).get("p-1"))
    }

    @Test fun anotherWrappingKeyIsLockedAndNothingIsChanged() {
        MediaVault.forApp(context, newWrap()).put("p-1", photo)
        val sealed = sealedFile.readBytes()
        val secrets = secretsFile.readBytes()

        assertLocked { MediaVault.forApp(context, newWrap()).get("p-1") }
        assertArrayEquals(sealed, sealedFile.readBytes())
        assertArrayEquals(secrets, secretsFile.readBytes())
    }

    @Test fun aMissingDataKeyWithSealedFilesIsLockedAndNotReplaced() {
        val wrap = newWrap()
        MediaVault.forApp(context, wrap).put("p-1", photo)
        val sealed = sealedFile.readBytes()
        val others = Json.parseToJsonElement(secretsFile.readText()).jsonObject.filterKeys { it != MediaVault.KEY_NAME }
        secretsFile.writeText(JsonObject(others).toString())

        val vault = MediaVault.forApp(context, wrap)
        assertLocked { vault.get("p-1") }
        assertLocked { vault.put("p-2", photo) }
        assertEquals(others.keys, entries()) // no new media entry
        assertArrayEquals(sealed, sealedFile.readBytes())
        assertEquals(listOf("p-1.bin"), MediaVault.mediaDir(context).list()!!.toList())
    }
}
