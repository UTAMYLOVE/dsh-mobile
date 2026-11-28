package app.dshmobile.wire

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.agency.core.metering.Provider
import app.dshmobile.secure.SecretKeyWrap
import app.dshmobile.secure.SecretsFile
import app.dshmobile.security.WrappedKeyVault
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 0.9 keys carried over once into an empty vault; every failure is silent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val oldKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val newKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val prefs = context.getSharedPreferences("legacy_test", Context.MODE_PRIVATE)

    private fun vault() = WrappedKeyVault(SecretsFile(File(context.noBackupFilesDir, SecretsFile.NAME)), SecretKeyWrap { newKey })

    /** The 0.9 format: Base64(iv):Base64(ciphertext), AES-GCM, the entry name as associated data. */
    private fun old(name: String, key: String, with: SecretKey = oldKey) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, with)
        c.updateAAD(name.toByteArray())
        val ct = c.doFinal(key.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).commit()
    }

    @Test fun bothKeysMoveIntoAnEmptyVault() {
        old("ANTHROPIC", "sk-ant-old-invented-1")
        old("DEEPSEEK", "sk-old-invented-2")
        val v = vault()
        LegacyKeys({ prefs }, { oldKey }).run(v)
        assertEquals("sk-ant-old-invented-1", v.get(Provider.ANTHROPIC))
        assertEquals("sk-old-invented-2", v.get(Provider.DEEPSEEK))
    }

    @Test fun aVaultThatHasAKeyIsLeftAlone() {
        old("ANTHROPIC", "sk-ant-old-invented-1")
        val v = vault()
        v.set(Provider.DEEPSEEK, "sk-new-invented")
        LegacyKeys({ prefs }, { oldKey }).run(v)
        assertFalse(v.has(Provider.ANTHROPIC))
        assertEquals("sk-new-invented", v.get(Provider.DEEPSEEK))
    }

    @Test fun failuresAreSilentAndChangeNothing() {
        val v = vault()
        LegacyKeys({ null }, { oldKey }).run(v) // no old file
        LegacyKeys({ prefs }, { null }).run(v) // the Keystore key is gone
        old("ANTHROPIC", "sk-ant-old-invented-1", with = newKey)
        LegacyKeys({ prefs }, { oldKey }).run(v) // sealed under another key
        prefs.edit().putString("DEEPSEEK", "not-a-sealed-value").commit()
        LegacyKeys({ prefs }, { oldKey }).run(v) // damaged
        LegacyKeys({ throw IllegalStateException("boom") }, { oldKey }).run(v)
        assertFalse(v.has(Provider.ANTHROPIC))
        assertFalse(v.has(Provider.DEEPSEEK))
        assertNull(v.get(Provider.DEEPSEEK))
    }

    @Test fun oneGoodKeyIsKeptWhenTheOtherIsDamaged() {
        old("ANTHROPIC", "sk-ant-old-invented-1")
        prefs.edit().putString("DEEPSEEK", "garbage:garbage").commit()
        val v = vault()
        LegacyKeys({ prefs }, { oldKey }).run(v)
        assertEquals("sk-ant-old-invented-1", v.get(Provider.ANTHROPIC))
        assertFalse(v.has(Provider.DEEPSEEK))
    }
}
