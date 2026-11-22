package app.dshmobile.agency.media

import java.io.File
import java.io.SyncFailedException
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The vault with a plain JVM AES key (the Keystore wraps the real one). Invented bytes stand in for photos. */
class MediaVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val dir by lazy { tmp.newFolder("media") }
    private val vault by lazy { MediaVault(dir) { key } }
    private val photo = ByteArray(50_000) { (it * 31 % 251).toByte() }

    private fun assertFails(body: () -> Unit) {
        try {
            body()
            fail("expected MediaVaultException")
        } catch (_: MediaVaultException) {
        }
    }

    @Test fun aPhotoRoundTripsAndIsNotStoredInTheClear() {
        val sealed = vault.put("p-1", photo)
        assertEquals(photo.size.toLong(), sealed.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(photo).joinToString("") { "%02x".format(it) }, sealed.sha256)
        assertArrayEquals(photo, vault.get("p-1"))
        val onDisk = File(dir, "p-1.bin").readBytes()
        assertEquals(2, onDisk[0].toInt()) // version 2: segmented
        assertEquals(MediaVault.SEGMENT, java.nio.ByteBuffer.wrap(onDisk, 1, 4).int)
        assertEquals(5 + photo.size + MediaVault.OVERHEAD, onDisk.size) // header, one segment: IV, ciphertext, tag
        assertFalse(onDisk.copyOfRange(17, 17 + 64).contentEquals(photo.copyOfRange(0, 64)))
        assertEquals(listOf("p-1.bin"), dir.list()!!.toList()) // no temp file left
    }

    @Test fun aChangedFileIsAnError() {
        vault.put("p-1", photo)
        val f = File(dir, "p-1.bin")
        val bytes = f.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        assertFails { vault.get("p-1") }
    }

    @Test fun aFileUnderAnotherIdIsAnError() {
        vault.put("p-1", photo)
        File(dir, "p-1.bin").copyTo(File(dir, "p-2.bin"))
        assertFails { vault.get("p-2") }
    }

    @Test fun anotherKeyIsAnError() {
        vault.put("p-1", photo)
        val other = newKey()
        assertFails { MediaVault(dir) { other }.get("p-1") }
    }

    @Test fun aMissingFileIsAnErrorAndDeleteRemovesTheFile() {
        assertFails { vault.get("nope") }
        vault.put("p-1", photo)
        assertTrue(vault.has("p-1"))
        assertTrue(vault.delete("p-1"))
        assertFalse(vault.has("p-1"))
        assertFails { vault.get("p-1") }
    }

    @Test fun putFileSealsTheCameraShotAndDeletesThePlaintext() {
        val shot = tmp.newFile("shot.jpg").apply { writeBytes(photo) }
        vault.putFile("p-3", shot)
        assertFalse(shot.exists())
        assertArrayEquals(photo, vault.get("p-3"))
    }

    @Test fun anIdCannotLeaveTheFolder() {
        try {
            vault.put("../escape", photo)
            fail("a path in an id must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(File(dir.parentFile, "escape.bin").exists())
    }

    @Test fun theCameraSweepDeletesEverythingButThePendingShot() {
        val camera = tmp.newFolder("camera")
        File(camera, "old-1.jpg").writeBytes(photo)
        File(camera, "old-2.jpg").writeBytes(photo)
        File(camera, "pending.jpg").writeBytes(photo)
        assertEquals(2, MediaVault.sweepCameraCache(camera, keep = setOf("pending.jpg")))
        assertEquals(listOf("pending.jpg"), camera.list()!!.toList())
        assertEquals(1, MediaVault.sweepCameraCache(camera))
        assertEquals(0, MediaVault.sweepCameraCache(File(tmp.root, "missing")))
    }

    @Test fun theSealedFileIsSyncedWholeBeforeItIsRenamed() {
        val seen = mutableListOf<String>()
        val synced = MediaVault(dir, { key }, sync = { fd ->
            fd.sync()
            val tmpFile = File(dir, "p-1.bin.tmp")
            seen += "tmp=${tmpFile.length()} target=${File(dir, "p-1.bin").exists()}"
        }, syncDir = { d -> seen += "dir=${d == dir} target=${File(dir, "p-1.bin").exists()} tmp=${File(dir, "p-1.bin.tmp").exists()}" })
        synced.put("p-1", photo)
        // Review FIX-T01-T02 m2: after the rename the folder is synced too, so the new name is on disk when put returns.
        assertEquals(listOf("tmp=${5 + photo.size + MediaVault.OVERHEAD} target=false", "dir=true target=true tmp=false"), seen)
        assertArrayEquals(photo, synced.get("p-1"))
    }

    @Test fun aFailedSyncStoresNothing() {
        val failing = MediaVault(dir, { key }, sync = { throw SyncFailedException("invented") }, syncDir = { fail("nothing was renamed") })
        try {
            failing.put("p-1", photo)
            fail("a failed sync must fail the put")
        } catch (_: SyncFailedException) {
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    // Format version 2 (arch T07): segments sealed as they stream in; version 1 files still open.

    private fun small(seg: Int = 1000) = MediaVault(dir, { key }, { it.sync() }, {}, segment = seg)

    @Test fun aVersionOneFileStillOpens() {
        // Exactly as the T02 vault wrote it: version 1, IV, ciphertext + tag of the whole file, the id as associated data.
        val v1 = app.dshmobile.agency.secure.Gcm.seal(key, photo, "agency-media:old-1".toByteArray())
        assertEquals(1, v1[0].toInt())
        File(dir, "old-1.bin").writeBytes(v1)
        assertArrayEquals(photo, vault.get("old-1"))
        assertEquals(MediaVault.Sealed(photo.size.toLong(), sha(photo)), vault.digest("old-1"))
        File(dir, "old-2.bin").writeBytes(v1)
        assertFails { vault.get("old-2") } // still bound to its id
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun manySegmentsRoundTripAtEverySize() {
        val v = small()
        for (size in listOf(0, 1, 999, 1000, 1001, 2000, 4321)) {
            val bytes = ByteArray(size) { (it * 7 % 253).toByte() }
            val sealed = v.putStream("s-$size", java.io.ByteArrayInputStream(bytes))
            assertEquals(size.toLong(), sealed.bytes)
            assertEquals(sha(bytes), sealed.sha256)
            assertArrayEquals("size $size", bytes, v.get("s-$size"))
            val segments = maxOf(1, (size + 999) / 1000 + if (size % 1000 == 0 && size > 0) 0 else 0)
            val expected = 5 + size + MediaVault.OVERHEAD * (if (size == 0) 1 else segments)
            assertEquals("size $size", expected.toLong(), File(dir, "s-$size.bin").length())
        }
    }

    @Test fun aFileCutAtASegmentEdgeIsAnError() {
        val v = small()
        val bytes = ByteArray(3000) { it.toByte() }
        v.put("c-1", bytes)
        val f = File(dir, "c-1.bin")
        val whole = f.readBytes()
        // Drop the last segment: what is left ends on a segment edge, but its last segment was not sealed as the last one.
        f.writeBytes(whole.copyOf(5 + 2 * (1000 + MediaVault.OVERHEAD)))
        assertFails { v.get("c-1") }
        // Swap two segments.
        val seg = 1000 + MediaVault.OVERHEAD
        val swapped = whole.copyOf()
        whole.copyInto(swapped, 5, 5 + seg, 5 + 2 * seg)
        whole.copyInto(swapped, 5 + seg, 5, 5 + seg)
        f.writeBytes(swapped)
        assertFails { v.get("c-1") }
        // A byte added at the end.
        f.writeBytes(whole + byteArrayOf(0))
        assertFails { v.get("c-1") }
        // A changed segment size in the header.
        val header = whole.copyOf().also { it[4] = (it[4] + 1).toByte() }
        f.writeBytes(header)
        assertFails { v.get("c-1") }
        f.writeBytes(whole)
        assertArrayEquals(bytes, v.get("c-1"))
    }

    @Test fun aStreamIsSealedWithoutReadingItWhole() {
        // A source that hands out 5 MB a little at a time and records the most it was asked for at once.
        var biggest = 0
        val total = 5 * 1024 * 1024
        val source = object : java.io.InputStream() {
            var left = total
            override fun read(): Int = if (left-- > 0) 7 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                biggest = maxOf(biggest, len)
                val n = minOf(len, left, 10_000)
                java.util.Arrays.fill(b, off, off + n, 7)
                left -= n
                return n
            }
        }
        val sealed = vault.putStream("big", source)
        assertEquals(total.toLong(), sealed.bytes)
        assertTrue(biggest <= MediaVault.SEGMENT)
        assertEquals(sealed, vault.digest("big"))
        assertEquals(5L + total + MediaVault.OVERHEAD * (total / MediaVault.SEGMENT), File(dir, "big.bin").length())
    }

    @Test fun aFailedStreamLeavesNoFile() {
        val broken = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 70_000) 1 else throw java.io.IOException("invented")
        }
        try {
            vault.putStream("x-1", broken)
            fail("a broken stream must fail the put")
        } catch (_: java.io.IOException) {
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun theKeyIsAskedOncePerPutAndPerRead() {
        var asked = 0
        val counting = MediaVault(dir) { asked++; key }
        counting.put("k-1", ByteArray(200_000) { 3 })
        assertEquals(1, asked)
        counting.get("k-1")
        assertEquals(2, asked)
    }
}
