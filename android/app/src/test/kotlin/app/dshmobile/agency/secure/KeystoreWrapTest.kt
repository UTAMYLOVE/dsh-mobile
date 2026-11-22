package app.dshmobile.agency.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review I1: opening a stored secret never creates a Keystore key (creating one replaces the key the blob was sealed with).
 * Robolectric has no AndroidKeyStore, so the aliases live in a map; the device test covers the real Keystore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeystoreWrapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secretsFile = File(context.noBackupFilesDir, SecretsFile.NAME)

    private class MapKeys : AliasKeys {
        val keys = mutableMapOf<String, SecretKey>()
        var created = 0

        override fun find(alias: String): SecretKey? = keys[alias]

        override fun findOrCreate(alias: String): SecretKey = keys.getOrPut(alias) {
            created++
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val keys = MapKeys()
    private val wrap = KeystoreWrap("agency-test", keys)
    private fun secret() = WrappedSecret(SecretsFile(secretsFile), "db", wrap)

    private fun assertLocked(body: () -> Unit) {
        try {
            body()
            fail("expected DataLockedException")
        } catch (_: DataLockedException) {
        }
    }

    @Test fun aNewSecretCreatesTheKeyOnceAndReopensWithIt() {
        val first = secret().get(dataExists = false)
        assertEquals(1, keys.created)
        assertArrayEquals(first, secret().get(dataExists = true))
        assertEquals(1, keys.created)
    }

    @Test fun aMissingKeyIsLockedAndNoKeyIsCreated() {
        secret().get(dataExists = false)
        val before = secretsFile.readBytes()
        keys.keys.clear() // the Keystore lost the alias (or briefly reports it missing)

        assertLocked { secret().get(dataExists = true) }
        assertLocked { secret().get(dataExists = false) } // the stored secret exists: still opened, never replaced
        assertEquals(1, keys.created)
        assertFalse("the alias must not be re-created", "agency-test" in keys.keys)
        assertArrayEquals(before, secretsFile.readBytes())
    }

    @Test fun unwrapWithoutTheKeyIsAKeystoreError() {
        val blob = wrap.wrap(ByteArray(32), "aad".toByteArray())
        keys.keys.clear()
        try {
            wrap.unwrap(blob, "aad".toByteArray())
            fail("expected KeyStoreException")
        } catch (_: KeyStoreException) {
        }
        assertEquals(emptyMap<String, SecretKey>(), keys.keys)
    }
}
