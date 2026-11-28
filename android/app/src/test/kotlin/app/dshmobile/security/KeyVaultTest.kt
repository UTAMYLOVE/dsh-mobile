package app.dshmobile.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.agency.core.metering.Provider
import app.dshmobile.secure.SecretKeyWrap
import app.dshmobile.secure.SecretsFile
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A key is only ever on disk wrapped; the clear text appears in no file and no preference of the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyVaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val jvmKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file = File(context.noBackupFilesDir, SecretsFile.NAME)
    private fun vault() = WrappedKeyVault(SecretsFile(file), SecretKeyWrap { jvmKey })

    private val claude = "sk-ant-invented-0123456789-abcdef"
    private val deepseek = "sk-invented-deepseek-9876543210"

    @Test fun keysRoundTripPerProvider() {
        val v = vault()
        assertFalse(v.has(Provider.ANTHROPIC))
        v.set(Provider.ANTHROPIC, "  $claude \n")
        v.set(Provider.DEEPSEEK, deepseek)
        assertTrue(v.has(Provider.ANTHROPIC))
        assertEquals(claude, vault().get(Provider.ANTHROPIC))
        assertEquals(deepseek, vault().get(Provider.DEEPSEEK))
        v.clear(Provider.ANTHROPIC)
        assertFalse(v.has(Provider.ANTHROPIC))
        assertNull(v.get(Provider.ANTHROPIC))
        assertEquals(deepseek, v.get(Provider.DEEPSEEK))
    }

    @Test fun aBlankKeyIsIgnored() {
        val v = vault()
        v.set(Provider.ANTHROPIC, "   ")
        assertFalse(v.has(Provider.ANTHROPIC))
    }

    @Test fun noFileOrPreferenceHoldsAKeyInTheClear() {
        val v = vault()
        v.set(Provider.ANTHROPIC, claude)
        v.set(Provider.DEEPSEEK, deepseek)
        context.getSharedPreferences("anything", Context.MODE_PRIVATE).edit().putString("x", "y").commit()
        val roots = listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir, File(context.applicationInfo.dataDir))
        val files = roots.flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }.distinct()
        assertTrue(files.any { it.name == SecretsFile.NAME })
        files.forEach { f ->
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(f.path, claude in text || deepseek in text || claude.substring(5) in text || deepseek.substring(5) in text)
        }
    }

    @Test fun aKeyCannotBeMovedToTheOtherProvider() {
        val v = vault()
        v.set(Provider.ANTHROPIC, claude)
        val json = file.readText().replace("key-claude", "key-deepseek")
        file.writeText(json)
        assertNull(vault().get(Provider.DEEPSEEK))
    }

    @Test fun aLostWrappingKeyReadsAsNoKey() {
        vault().set(Provider.ANTHROPIC, claude)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val v = WrappedKeyVault(SecretsFile(file), SecretKeyWrap { other })
        assertTrue(v.has(Provider.ANTHROPIC))
        assertNull(v.get(Provider.ANTHROPIC))
        assertFalse(v.toString().contains(claude))
    }
}
