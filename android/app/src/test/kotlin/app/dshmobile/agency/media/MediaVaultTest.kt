package app.dshmobile.agency.media

import java.io.File
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
        assertEquals(1, onDisk[0].toInt()) // version
        assertEquals(1 + 12 + photo.size + 16, onDisk.size) // version, IV, ciphertext, tag
        assertFalse(onDisk.copyOfRange(13, 13 + 64).contentEquals(photo.copyOfRange(0, 64)))
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
}
