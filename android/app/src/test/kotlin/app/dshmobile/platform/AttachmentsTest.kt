package app.dshmobile.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import app.agency.core.agent.Attachment
import app.dshmobile.platform.media.MediaVault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A content provider serving invented bytes under invented names, like a document picker would. */
class FakeDocs : ContentProvider() {
    class Doc(val name: String, val type: String?, val bytes: ByteArray)

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = docs[uri.lastPathSegment]?.type
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? {
        val d = docs[uri.lastPathSegment] ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(d.name, d.bytes.size.toLong())) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = File.createTempFile("doc", ".bin", context!!.cacheDir)
        f.writeBytes(docs.getValue(uri.lastPathSegment!!).bytes)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        val docs = HashMap<String, Doc>()
        fun uri(id: String): Uri = Uri.parse("content://fake.docs/$id")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vault by lazy { MediaVault(tmp.newFolder("vault")) { key } }
    private val attachments by lazy { Attachments(context, vault) { "photo-${ids++}" } }
    private var ids = 1

    @Before fun provider() {
        FakeDocs.docs.clear()
        Robolectric.setupContentProvider(FakeDocs::class.java, "fake.docs")
    }

    private fun serve(id: String, name: String, type: String?, bytes: ByteArray): Uri {
        FakeDocs.docs[id] = FakeDocs.Doc(name, type, bytes)
        return FakeDocs.uri(id)
    }

    private fun file(r: AttachResult) = (r as AttachResult.Ok).attachment as Attachment.File

    @Test fun aTextFileIsReadAsUtf8() {
        val uri = serve("a", "筆記.txt", "text/plain", "﻿第一行\n第二行".toByteArray())
        val f = file(attachments.file(uri))
        assertEquals("筆記.txt", f.name)
        assertEquals("第一行\n第二行", f.text)
    }

    @Test fun badBytesBecomeReplacementCharacters() {
        val uri = serve("b", "x.csv", "text/csv", byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))
        assertEquals("a�b", file(attachments.file(uri)).text)
    }

    @Test fun markdownReportedAsOctetStreamIsAcceptedByItsName() {
        val uri = serve("c", "note.md", "application/octet-stream", "# 標題".toByteArray())
        assertEquals("# 標題", file(attachments.file(uri)).text)
        assertTrue(Attachments.typeAllowed("application/json", null))
        assertTrue(Attachments.typeAllowed("text/markdown", "x"))
    }

    @Test fun theSizeCapIsTwoHundredKilobytes() {
        val ok = serve("d", "big.txt", "text/plain", ByteArray(Attachments.MAX_FILE_BYTES) { 'a'.code.toByte() })
        assertEquals(Attachments.MAX_FILE_BYTES, file(attachments.file(ok)).text.length)
        val big = serve("e", "big.txt", "text/plain", ByteArray(Attachments.MAX_FILE_BYTES + 1) { 'a'.code.toByte() })
        val r = attachments.file(big) as AttachResult.Refused
        assertTrue(r.reason.contains("太大"))
    }

    @Test fun otherTypesAreRefusedInChinese() {
        listOf(
            serve("f", "a.pdf", "application/pdf", ByteArray(10)),
            serve("g", "a.png", "image/png", ByteArray(10)),
            serve("h", "a.bin", "application/octet-stream", ByteArray(10)),
            serve("i", "fake.txt", "text/plain", byteArrayOf(1, 0, 2)), // binary under a text name
            serve("j", "a.docx", null, "x".toByteArray()),
        ).forEach { uri ->
            val r = attachments.file(uri)
            assertTrue(uri.toString(), r is AttachResult.Refused)
            assertTrue((r as AttachResult.Refused).reason.contains("不能附上"))
        }
        assertTrue(attachments.file(serve("k", "e.txt", "text/plain", ByteArray(0))) is AttachResult.Refused)
    }

    private fun jpegWithExif(): ByteArray {
        val bmp = Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val f = tmp.newFile("exif.jpg").apply { writeBytes(out.toByteArray()) }
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "25/1,3/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_MAKE, "InventedMaker")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2030:01:07 12:00:00")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return f.readBytes()
    }

    @Test fun aPhotoIsCleanedForTheModelAndTheOriginalIsSealed() {
        val original = jpegWithExif()
        val exifBefore = ExifInterface(ByteArrayInputStream(original))
        assertNotNull(exifBefore.getAttribute(ExifInterface.TAG_GPS_LATITUDE)) // the fixture really carries a location
        val uri = serve("p", "p.jpg", "image/jpeg", original)

        val photo = (attachments.photo(uri, fromGallery = true) as AttachResult.Ok).attachment as Attachment.Photo
        assertTrue(photo.fromGallery)
        assertEquals("photo-1", photo.photoId)
        val exif = ExifInterface(ByteArrayInputStream(photo.jpeg))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, it) }
        assertTrue(maxOf(size.outWidth, size.outHeight) <= 1568)
        assertTrue("turned upright by its orientation", size.outHeight > size.outWidth)
        // the vault holds the untouched original, encrypted
        assertArrayEquals(original, vault.get(photo.photoId))
        val onDisk = File(tmp.root, "vault/${photo.photoId}.bin").readBytes()
        assertTrue(String(onDisk, Charsets.ISO_8859_1).indexOf("InventedMaker") < 0)
    }

    @Test fun aPastedImageIsAPhotoNotFromTheGallery() {
        val uri = serve("q", "q.jpg", "image/jpeg", jpegWithExif())
        val photo = (attachments.pasted(uri) as AttachResult.Ok).attachment as Attachment.Photo
        assertEquals(false, photo.fromGallery)
        val text = serve("r", "r.txt", "text/plain", "hi".toByteArray())
        assertTrue((attachments.pasted(text) as AttachResult.Ok).attachment is Attachment.File)
    }

    @Test fun somethingThatIsNotAnImageIsRefused() {
        val r = attachments.photo(serve("s", "s.jpg", "image/jpeg", "not an image".toByteArray()))
        assertTrue((r as AttachResult.Refused).reason.contains("打不開"))
    }

    @Test fun thePickersAreTheRightKinds() {
        assertTrue(AttachPickers.documentTypes.contains("text/markdown"))
        assertTrue(AttachPickers.documentTypes.contains("application/json"))
        assertNotNull(AttachPickers.photos())
        assertNotNull(AttachPickers.document())
    }
}
