package app.agency.core.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The backup could not be opened: [reason] says why (wrong password or damaged file, not a backup, a newer version). */
class BackupException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { NOT_A_BACKUP, NEWER_VERSION, WRONG_PASSWORD_OR_DAMAGED, TRUNCATED, WEAK_PASSWORD, OLDER_VERSION }
}

/**
 * The encrypted backup file (owner 2026-11-23: 加密備份檔匯出／匯回), a stream format:
 *
 * `XYBK | version (1) | kdf (1: PBKDF2-HMAC-SHA256) | iterations (4) | salt (16) | nonce prefix (8)` then segments, each
 * `last flag (1) | length (4) | AES-256-GCM ciphertext + tag` of at most [SEGMENT] plaintext bytes. The key comes from his
 * password through PBKDF2 ([ITERATIONS] rounds, a random salt); each segment's nonce is the prefix and its index, and its
 * associated data is the whole header, its index and its flag, so segments cannot be reordered, dropped or cut off, and the
 * header (version, rounds) cannot be changed. The last segment always exists and is the only one flagged.
 *
 * Nothing here knows what is inside (the app writes a zip of its records and photos); keys never are.
 */
object BackupCipher {
    val MAGIC = "XYBK".toByteArray(Charsets.US_ASCII)
    const val VERSION: Byte = 1
    const val KDF_PBKDF2_SHA256: Byte = 1
    const val ITERATIONS = 600_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 10_000_000
    const val MIN_PASSWORD = 8
    const val SEGMENT = 1 shl 20
    private const val SALT = 16
    private const val PREFIX = 8
    private const val TAG_BITS = 128
    private const val HEADER = 4 + 1 + 1 + 4 + SALT + PREFIX

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, index: Int) = ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    private fun aad(header: ByteArray, index: Int, last: Boolean) =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index).put(if (last) 1 else 0).array()

    /** An output stream that seals what is written to it into [out]; close it to write the last segment. */
    fun encrypt(out: OutputStream, password: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): OutputStream {
        if (password.size < MIN_PASSWORD) throw BackupException(BackupException.Reason.WEAK_PASSWORD, "password too short")
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "bad iterations" }
        val salt = ByteArray(SALT).also(random::nextBytes)
        val prefix = ByteArray(PREFIX).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).put(KDF_PBKDF2_SHA256).putInt(iterations).put(salt).put(prefix).array()
        val k = key(password, salt, iterations)
        out.write(header)
        return object : OutputStream() {
            private val buf = ByteArray(SEGMENT)
            private var n = 0
            private var index = 0
            private var closed = false

            private fun seal(last: Boolean) {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
                c.updateAAD(aad(header, index, last))
                val ct = c.doFinal(buf, 0, n)
                out.write(if (last) 1 else 0)
                out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
                out.write(ct)
                buf.fill(0, 0, n)
                n = 0
                index++
            }

            override fun write(b: Int) {
                if (n == SEGMENT) seal(false)
                buf[n++] = b.toByte()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var left = len
                while (left > 0) {
                    if (n == SEGMENT) seal(false)
                    val take = minOf(left, SEGMENT - n)
                    System.arraycopy(b, o, buf, n, take)
                    n += take
                    o += take
                    left -= take
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                seal(true)
                out.flush()
                out.close()
            }
        }
    }

    /**
     * The plaintext of a backup in [input] as a stream; every segment is checked as it is read and the end must be the flagged
     * last segment. Throws [BackupException] (also from the returned stream's reads).
     */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val din = DataInputStream(input)
        val header = ByteArray(HEADER)
        try {
            din.readFully(header)
        } catch (e: EOFException) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP, "too short", e)
        }
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "not a backup")
        if (header[4] > VERSION || header[5] != KDF_PBKDF2_SHA256) throw BackupException(BackupException.Reason.NEWER_VERSION, "newer backup format")
        if (header[4] < 1) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "bad version")
        val bb = ByteBuffer.wrap(header, 6, HEADER - 6)
        val iterations = bb.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "bad iterations")
        val salt = ByteArray(SALT).also { bb.get(it) }
        val prefix = ByteArray(PREFIX).also { bb.get(it) }
        val k = key(password, salt, iterations)
        return object : InputStream() {
            private var plain = ByteArray(0)
            private var pos = 0
            private var index = 0
            private var done = false

            private fun next(): Boolean {
                if (done) return false
                val flag = try {
                    din.read()
                } catch (e: IOException) {
                    throw BackupException(BackupException.Reason.TRUNCATED, "read failed", e)
                }
                if (flag < 0) throw BackupException(BackupException.Reason.TRUNCATED, "cut off")
                if (flag > 1) throw BackupException(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED, "bad segment")
                val len = try { din.readInt() } catch (e: EOFException) { throw BackupException(BackupException.Reason.TRUNCATED, "cut off", e) }
                if (len < 16 || len > SEGMENT + 16) throw BackupException(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED, "bad segment length")
                val ct = ByteArray(len)
                try { din.readFully(ct) } catch (e: EOFException) { throw BackupException(BackupException.Reason.TRUNCATED, "cut off", e) }
                val last = flag == 1
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
                c.updateAAD(aad(header, index, last))
                plain = try {
                    c.doFinal(ct)
                } catch (e: AEADBadTagException) {
                    throw BackupException(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED, "does not open", e)
                }
                pos = 0
                index++
                if (last) {
                    done = true
                    if (din.read() != -1) throw BackupException(BackupException.Reason.WRONG_PASSWORD_OR_DAMAGED, "data after the end")
                }
                return true
            }

            override fun read(): Int {
                while (pos >= plain.size) if (!next()) return -1
                return plain[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= plain.size) if (!next()) return -1
                val take = minOf(len, plain.size - pos)
                System.arraycopy(plain, pos, b, off, take)
                pos += take
                return take
            }

            override fun close() = input.close()
        }
    }
}
