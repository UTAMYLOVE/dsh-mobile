package app.dshmobile.platform.media

import android.content.Context
import app.dshmobile.data.DbKey
import app.dshmobile.secure.DataLockedException
import app.dshmobile.secure.Durable
import app.dshmobile.secure.Gcm
import app.dshmobile.secure.KeyWrap
import app.dshmobile.secure.KeystoreWrap
import app.dshmobile.secure.TransientDataException
import app.dshmobile.secure.WrappedSecret
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A media file is missing, was changed on disk, or was sealed under another key. */
class MediaVaultException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Every photo and recording, AES-256-GCM encrypted as `<dir>/<id>.bin`, the id bound in as associated data so files cannot be
 * swapped. The data key comes from [dataKey]: in the app a random key wrapped by the Keystore key `xiaoyu-media` ([forApp]); in
 * tests a plain JVM key. Plaintext only ever exists in memory or, briefly, in the camera and recorder caches
 * ([sweepCameraCache]).
 *
 * Two file formats (arch T07, from the FIX-T03 review M-f):
 * - **version 1** (the T02 importer's files): `1 | IV | ciphertext + tag` of the whole file, sealed in memory. Still read.
 * - **version 2** (every new file): `2 | segment size (4 bytes, big endian) | segments`, each segment `IV | ciphertext + tag`
 *   of at most [SEGMENT] plaintext bytes, sealed one at a time as the bytes stream in, so a three-minute recording never has
 *   to fit in memory whole. Each segment's associated data is the id, the header, its index (8 bytes) and a last-segment flag:
 *   segments cannot be reordered, dropped, cut off at the end or moved to another file. The last segment always exists (it is
 *   empty for an empty file) and is the only one flagged. Its IV is random (12 bytes), new for every segment.
 *
 * Errors: [MediaVaultException] for one file (missing, changed, sealed under another key); [DataLockedException] when the
 * data key itself is lost (its secret is missing while sealed files exist, or its Keystore key is gone);
 * [TransientDataException] when the key's secret could not be read or stored right now. A locked vault is not a locked
 * database: meals and records still work, only photos and recordings are unavailable.
 * [sync] is the fsync before the rename, [syncDir] the folder's fsync after it (tests observe both). [dataKey] is asked once
 * per put and once per read.
 */
class MediaVault internal constructor(
    private val dir: File,
    private val dataKey: () -> SecretKey,
    private val sync: (FileDescriptor) -> Unit,
    private val syncDir: (File) -> Unit,
    private val segment: Int = SEGMENT,
) {
    constructor(dir: File, dataKey: () -> SecretKey) : this(dir, dataKey, FileDescriptor::sync, Durable::syncDir)

    init {
        require(segment in 1..MAX_SEGMENT) { "bad segment size" }
    }

    /** What was sealed: size and SHA-256 (hex) of the plaintext, for the `media` row. */
    data class Sealed(val bytes: Long, val sha256: String)

    /** Seals [plain] as `<id>.bin` (version 2). See [putStream]. */
    fun put(id: String, plain: ByteArray): Sealed = putStream(id, ByteArrayInputStream(plain))

    /**
     * Seals what [input] gives as `<id>.bin` (version 2), segment by segment, without holding more than two segments of it:
     * written to a temp file, synced to disk, renamed, then the folder synced, so a power loss leaves the old file or the whole
     * new one, never a cut one, and once this returns the new one is on disk (best effort where the filesystem refuses a folder
     * sync). [input] is read to its end, not closed.
     * @throws DataLockedException when the data key is lost.
     */
    fun putStream(id: String, input: InputStream): Sealed {
        val target = fileOf(id)
        dir.mkdirs()
        val tmp = File(dir, "$id.bin.tmp")
        val key = dataKey()
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileOutputStream(tmp).use { raw ->
                val out = BufferedOutputStream(raw, segment + OVERHEAD)
                val header = header(segment)
                out.write(header)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                var cur = ByteArray(segment)
                var next = ByteArray(segment)
                var curLen = readFully(input, cur)
                var index = 0L
                while (true) {
                    val nextLen = if (curLen < segment) 0 else readFully(input, next)
                    val last = curLen < segment || nextLen == 0
                    md.update(cur, 0, curLen)
                    total += curLen
                    out.write(sealSegment(cipher, key, cur, curLen, aad(id, header, index, last)))
                    if (last) break
                    cur = next.also { next = cur }
                    curLen = nextLen
                    index++
                }
                out.flush()
                sync(raw.fd)
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not store media $id")
        }
        syncDir(dir)
        return Sealed(total, md.digest().hex())
    }

    /**
     * Seals the plaintext file [plain] (a camera shot, a recording) as a stream and deletes it, also when sealing fails.
     * @throws DataLockedException when the data key cannot be had (the file is still deleted).
     */
    fun putFile(id: String, plain: File): Sealed = try {
        plain.inputStream().use { putStream(id, it) }
    } finally {
        plain.delete()
    }

    /**
     * The whole plaintext (photos and short recordings; for long ones use [open]).
     * @throws MediaVaultException when the file is missing, changed or sealed under another key.
     * @throws DataLockedException when the data key cannot be had.
     */
    fun get(id: String): ByteArray = open(id).use { it.readBytes() }

    /**
     * The plaintext as a stream: a version-2 file is opened one segment at a time, and only segments that check out are given
     * out; a file that was changed or cut off fails with [MediaVaultException] at the bad segment (callers that must not use a
     * partial file read to the end before using it, as [get] does).
     * @throws MediaVaultException when the file is missing, or its header is not one of ours.
     * @throws DataLockedException when the data key cannot be had.
     */
    fun open(id: String): InputStream {
        val file = fileOf(id)
        if (!file.isFile) throw MediaVaultException("no media $id")
        val input = BufferedInputStream(FileInputStream(file))
        try {
            val version = input.read()
            if (version == Gcm.VERSION.toInt()) {
                val rest = input.readBytes()
                val blob = ByteArray(1 + rest.size).also { it[0] = Gcm.VERSION; rest.copyInto(it, 1) }
                return try {
                    ByteArrayInputStream(Gcm.open(dataKey(), blob, v1Aad(id)))
                } catch (e: GeneralSecurityException) {
                    throw MediaVaultException("media $id cannot be opened", e)
                }
            }
            if (version != VERSION_2.toInt()) throw MediaVaultException("media $id has an unknown format")
            val sizeBytes = ByteArray(4)
            if (readFully(input, sizeBytes) != 4) throw MediaVaultException("media $id is cut off")
            val size = ByteBuffer.wrap(sizeBytes).int
            if (size !in 1..MAX_SEGMENT) throw MediaVaultException("media $id has a bad segment size")
            return SegmentStream(id, input, dataKey(), header(size), size)
        } catch (e: Throwable) {
            input.close()
            throw e
        }
    }

    /** SHA-256 (hex) and size of the plaintext of [id], read as a stream (the importer's check). */
    fun digest(id: String): Sealed = open(id).use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
        Sealed(total, md.digest().hex())
    }

    fun has(id: String): Boolean = fileOf(id).isFile

    /** The ids of every sealed file (the backup's restore removes the ones its records no longer name). */
    fun ids(): List<String> = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") }.map { it.name.removeSuffix(".bin") }.filter { ID.matches(it) }

    fun delete(id: String): Boolean = fileOf(id).delete()

    private fun fileOf(id: String): File {
        require(ID.matches(id)) { "bad media id" }
        return File(dir, "$id.bin")
    }

    /** Opens a version-2 file one segment at a time. */
    private class SegmentStream(
        private val id: String,
        private val input: InputStream,
        private val key: SecretKey,
        private val header: ByteArray,
        size: Int,
    ) : InputStream() {
        private val cipher = Cipher.getInstance(TRANSFORMATION)
        private var cur = ByteArray(size + OVERHEAD)
        private var next = ByteArray(size + OVERHEAD)
        private var nextLen = -1 // read ahead: -1 = not read yet
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) {
                if (done) return -1
                fill()
            }
            val n = minOf(len, plain.size - pos)
            plain.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }

        private fun fill() {
            val curLen = if (nextLen >= 0) nextLen.also { cur = next.also { next = cur } } else readFully(input, cur)
            if (curLen < OVERHEAD) throw MediaVaultException("media $id is cut off")
            // Full segment: the last one only when nothing follows it.
            nextLen = if (curLen < cur.size) 0 else readFully(input, next)
            val last = nextLen == 0
            plain = try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, cur, 0, IV_BYTES))
                cipher.updateAAD(aad(id, header, index, last))
                cipher.doFinal(cur, IV_BYTES, curLen - IV_BYTES)
            } catch (e: GeneralSecurityException) {
                throw MediaVaultException("media $id cannot be opened", e)
            }
            pos = 0
            index++
            if (last) done = true
        }

        override fun close() = input.close()
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val KEY_NAME = "media"

        /** The version byte of the segmented format. */
        const val VERSION_2: Byte = 2

        /** Plaintext bytes per segment of a new file. */
        const val SEGMENT = 64 * 1024
        private const val MAX_SEGMENT = 1 shl 20
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** What a segment adds to its plaintext: IV and tag. */
        const val OVERHEAD = IV_BYTES + TAG_BITS / 8
        private val random = SecureRandom()

        private fun header(size: Int): ByteArray = ByteBuffer.allocate(5).put(VERSION_2).putInt(size).array()

        private fun v1Aad(id: String) = "xiaoyu-media:$id".toByteArray(Charsets.UTF_8)

        private fun aad(id: String, header: ByteArray, index: Long, last: Boolean): ByteArray =
            v1Aad(id) + header + ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

        private fun sealSegment(cipher: Cipher, key: SecretKey, plain: ByteArray, len: Int, aad: ByteArray): ByteArray {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plain, 0, len)
        }

        /** Reads until [buf] is full or the stream ends; returns how many bytes came. */
        private fun readFully(input: InputStream, buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        fun mediaDir(context: Context) = File(context.noBackupFilesDir, "xiaoyu/media")

        /** Where the system camera writes through the FileProvider (`cache/camera/<uuid>.jpg`). */
        fun cameraDir(context: Context) = File(context.cacheDir, "camera")

        /**
         * The app's vault, its data key wrapped by [wrap] (the Keystore key `xiaoyu-media`). Once sealed files exist, a missing
         * or unopenable key is [DataLockedException] and nothing is replaced. Safe to call more than once and from several
         * threads: every instance shares the process-wide lock of the secrets file, so concurrent first runs agree on one key.
         */
        fun forApp(context: Context, wrap: KeyWrap = KeystoreWrap(KeystoreWrap.MEDIA_ALIAS)): MediaVault {
            val dir = mediaDir(context)
            val secret = WrappedSecret(DbKey.secretsFile(context), KEY_NAME, wrap)
            val key by lazy {
                val sealedFilesExist = dir.listFiles { f -> f.name.endsWith(".bin") }?.isNotEmpty() == true
                val raw = secret.get(sealedFilesExist)
                SecretKeySpec(raw, "AES").also { raw.fill(0) }
            }
            return MediaVault(dir) { key }
        }

        /**
         * Deletes every plaintext file left in the camera cache [dir] except the names in [keep] (a capture still waiting for
         * its result); returns how many files went. Meant for every start (the start-up wiring arrives with the cutover).
         */
        fun sweepCameraCache(dir: File, keep: Set<String> = emptySet()): Int =
            dir.listFiles()?.count { it.name !in keep && it.deleteRecursively() } ?: 0

    }
}
