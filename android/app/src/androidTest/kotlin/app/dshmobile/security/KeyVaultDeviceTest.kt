package app.dshmobile.security

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.metering.Provider
import app.dshmobile.secure.KeystoreWrap
import app.dshmobile.secure.SecretsFile
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real key vault: keys wrapped by an Android Keystore key, round trip, new instance, clear, and nothing in the clear on disk. */
class KeyVaultDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "vault-" + UUID.randomUUID()).also { it.mkdirs() }
    private val alias = "xiaoyu-test-keys-" + UUID.randomUUID()
    private fun vault() = WrappedKeyVault(SecretsFile(File(dir, SecretsFile.NAME)), KeystoreWrap(alias))

    private val claude = "sk-ant-invented-e2e-0123456789"
    private val deepseek = "sk-invented-e2e-9876543210"

    @After fun cleanUp() {
        dir.deleteRecursively()
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    @Test fun roundTripThroughTheKeystore() {
        val v = vault()
        assertFalse(v.has(Provider.ANTHROPIC))
        v.set(Provider.ANTHROPIC, "  $claude ")
        v.set(Provider.DEEPSEEK, deepseek)
        assertTrue(v.has(Provider.ANTHROPIC))
        // A fresh instance over the same file reads them again (the Keystore key is the only secret kept).
        val again = vault()
        assertEquals(claude, again.get(Provider.ANTHROPIC))
        assertEquals(deepseek, again.get(Provider.DEEPSEEK))
        again.clear(Provider.ANTHROPIC)
        assertNull(vault().get(Provider.ANTHROPIC))
        assertEquals(deepseek, vault().get(Provider.DEEPSEEK))
    }

    @Test fun noFileHoldsAKeyInTheClear() {
        val v = vault()
        v.set(Provider.ANTHROPIC, claude)
        v.set(Provider.DEEPSEEK, deepseek)
        val all = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(all.isNotEmpty())
        all.forEach { f ->
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(f.name, text.contains(claude) || text.contains(deepseek) || text.contains("sk-ant"))
        }
    }

    @Test fun aKeyCannotBeMovedToTheOtherProvider() {
        val v = vault()
        v.set(Provider.ANTHROPIC, claude)
        val file = File(dir, SecretsFile.NAME)
        val text = file.readText()
        // The provider is bound in as associated data: the same blob under the other name does not open.
        file.writeText(text.replace("key-claude", "key-deepseek"))
        assertNull(vault().get(Provider.DEEPSEEK))
    }
}
