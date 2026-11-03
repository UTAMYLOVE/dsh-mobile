package app.dshmobile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.ProviderException

/** The decrypt-failure policy of [AndroidKeyVault.get] (Robolectric has no AndroidKeyStore, so the cipher itself is not run here). */
class AndroidKeyVaultTest {
    @Test fun aKeystoreProviderExceptionReadsAsNoKey() {
        // Keystore2 reports some failures (e.g. a key invalidated by a lock-screen change) as a runtime ProviderException.
        assertNull(AndroidKeyVault.decryptOrNull { throw ProviderException("Keystore operation failed") })
    }

    @Test fun theOtherDecryptFailuresStillReadAsNoKey() {
        assertNull(AndroidKeyVault.decryptOrNull { throw javax.crypto.AEADBadTagException("tag") })
        assertNull(AndroidKeyVault.decryptOrNull { throw IllegalArgumentException("bad base64") })
        assertNull(AndroidKeyVault.decryptOrNull { throw IndexOutOfBoundsException() })
    }

    @Test fun aSuccessfulDecryptIsReturned() {
        assertEquals("sk-x", AndroidKeyVault.decryptOrNull { "sk-x" })
    }
}
