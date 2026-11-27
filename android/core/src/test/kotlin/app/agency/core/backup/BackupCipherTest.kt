package app.agency.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** The backup file: his password, PBKDF2 and AES-GCM in segments; anything changed, cut or opened wrongly is refused. */
class BackupCipherTest {
    private val password = "invented-pass-123".toCharArray()

    private fun seal(plain: ByteArray, iterations: Int = BackupCipher.MIN_ITERATIONS): ByteArray =
        ByteArrayOutputStream().also { out -> BackupCipher.encrypt(out, password.copyOf(), iterations).use { it.write(plain) } }.toByteArray()

    private fun open(sealed: ByteArray, pw: CharArray = password.copyOf()) = BackupCipher.decrypt(ByteArrayInputStream(sealed), pw).use { it.readBytes() }

    private fun refused(reason: BackupException.Reason, block: () -> Unit) {
        try {
            block()
            fail("not refused")
        } catch (e: BackupException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test fun itRoundTripsAcrossSegmentsAndHidesThePlaintext() {
        val plain = Random(7).nextBytes(BackupCipher.SEGMENT * 2 + 123) + "我的備份".toByteArray()
        val sealed = seal(plain)
        assertArrayEquals(plain, open(sealed))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("我的備份"))
        assertEquals("XYBK", String(sealed, 0, 4, Charsets.US_ASCII))
        assertEquals(BackupCipher.VERSION, sealed[4])
        // Empty is fine too (one empty last segment).
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0))))
    }

    @Test fun theDefaultIsAStrongKeyDerivation() {
        val sealed = ByteArrayOutputStream().also { BackupCipher.encrypt(it, password.copyOf()).use { s -> s.write(1) } }.toByteArray()
        assertEquals(BackupCipher.ITERATIONS, java.nio.ByteBuffer.wrap(sealed, 6, 4).int)
        assertEquals(600_000, BackupCipher.ITERATIONS)
    }

    @Test fun aWrongPasswordAChangeOrACutIsRefused() {
        val plain = Random(1).nextBytes(BackupCipher.SEGMENT + 10)
        val sealed = seal(plain)
        refused(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED) { open(sealed, "other-password".toCharArray()) }
        refused(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED) { open(sealed.copyOf().also { it[sealed.size - 5] = (it[sealed.size - 5] + 1).toByte() }) }
        // Fewer rounds in the header: the key and the authenticated header no longer match.
        refused(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED) { open(sealed.copyOf().also { java.nio.ByteBuffer.wrap(it, 6, 4).putInt(BackupCipher.MIN_ITERATIONS + 1) }) }
        // Cut after the first segment: the flagged last one is missing.
        val first = 34 + 5 + BackupCipher.SEGMENT + 16
        refused(BackupException.Reason.TRUNCATED) { open(sealed.copyOf(first)) }
        refused(BackupException.Reason.NOT_A_BACKUP) { open("PK\u0003\u0004 a zip".toByteArray()) }
        refused(BackupException.Reason.NEWER_VERSION) { open(sealed.copyOf().also { it[4] = 9 }) }
        refused(BackupException.Reason.WEAK_PASSWORD) { BackupCipher.encrypt(ByteArrayOutputStream(), "short".toCharArray()) }
    }
}
