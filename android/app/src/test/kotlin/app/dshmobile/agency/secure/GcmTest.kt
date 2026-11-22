package app.dshmobile.agency.secure

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

/** Review M4: the format's version byte is authenticated with the associated data, not only checked. */
class GcmTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val plain = "invented bytes".toByteArray()
    private val aad = "agency-media:p-1".toByteArray()

    /** Seals by hand: `header | IV | ciphertext + tag` with [authenticated] as the associated data. */
    private fun sealByHand(header: Byte, authenticated: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(authenticated)
        val body = cipher.doFinal(plain)
        return byteArrayOf(header) + cipher.iv + body
    }

    private fun assertRefused(blob: ByteArray) {
        try {
            Gcm.open(key, blob, aad)
            fail("expected GeneralSecurityException")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test fun theAssociatedDataIsTheVersionThenTheCallersBytes() {
        assertArrayEquals(plain, Gcm.open(key, sealByHand(Gcm.VERSION, byteArrayOf(Gcm.VERSION) + aad), aad))
        assertArrayEquals(plain, Gcm.open(key, Gcm.seal(key, plain, aad), aad))
    }

    @Test fun aBlobWhoseVersionWasNotAuthenticatedIsRefused() {
        assertRefused(sealByHand(Gcm.VERSION, aad))
    }

    @Test fun anotherVersionByteIsRefused() {
        val blob = Gcm.seal(key, plain, aad)
        blob[0] = 2
        assertRefused(blob)
    }
}
