package app.dshmobile.agency.secure

import java.lang.management.ManagementFactory
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
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

    /** Review FIX-T03 F2: sealing encrypts into one output array, not a ciphertext that is then copied behind a header. */
    @Test fun sealingAllocatesOnlyOneSealedCopy() {
        val big = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        Gcm.seal(key, ByteArray(1024 * 1024), aad) // warm up the cipher classes
        // HotSpot's per-thread allocation counter; another JVM may lack it (or the class), and then the test is skipped.
        val counter = try {
            ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        } catch (_: LinkageError) {
            null
        }
        assumeTrue(
            "no per-thread allocation counter on this JVM",
            counter != null && counter.isThreadAllocatedMemorySupported && counter.isThreadAllocatedMemoryEnabled,
        )
        val threads = counter!!
        val me = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(me)
        val blob = Gcm.seal(key, big, aad)
        val allocated = threads.getThreadAllocatedBytes(me) - before
        assertEquals(1 + 12 + big.size + 16, blob.size)
        // One sealed copy is ~8 MiB; the old seal made two (the ciphertext, then header + IV + ciphertext): ~16 MiB.
        assertTrue("allocated $allocated bytes", allocated < big.size * 3L / 2)
        assertArrayEquals(big, Gcm.open(key, blob, aad))
    }

    @Test fun anotherVersionByteIsRefused() {
        val blob = Gcm.seal(key, plain, aad)
        blob[0] = 2
        assertRefused(blob)
    }
}
