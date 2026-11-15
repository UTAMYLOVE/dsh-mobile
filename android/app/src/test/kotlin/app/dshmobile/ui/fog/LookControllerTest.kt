package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.dshmobile.gateway.InMemorySharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real decoding: a non-picture must not decode
class LookControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun png(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()

    private fun halves(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) Color.RED else Color.BLUE }
        setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun isRed(c: Int) = Color.red(c) > 180 && Color.blue(c) < 80
    private fun isBlue(c: Int) = Color.blue(c) > 180 && Color.red(c) < 80

    /**
     * [jpeg] with an EXIF APP1 segment right after SOI holding only IFD0's Orientation, as a phone camera writes it (big-endian
     * TIFF header, one SHORT entry), built by hand so the test does not rely on the reader it checks.
     */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4d, 0x4d, 0x00, 0x2a, 0x00, 0x00, 0x00, 0x08, // "MM", 42, IFD0 at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00, // Orientation, SHORT, 1
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test fun aPictureIsKeptScaledDownAndCanBeRemoved() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val dir = tmp.newFolder("look")
        val c = LookController(LookStore(prefs), dir)
        val bytes = png(3000, 6000)
        assertTrue(c.setPicture { ByteArrayInputStream(bytes) })
        assertTrue(c.look.value.hasPicture)
        val kept = BitmapFactory.decodeFile(c.pictureFile.path)
        assertTrue("long side ${kept.height}", maxOf(kept.width, kept.height) <= LookController.MAX_SIDE)
        // Saved: a new controller (the next start) shows the same picture.
        assertEquals(c.look.value.picture, LookController(LookStore(prefs), dir).look.value.picture)
        c.removePicture()
        assertFalse(c.look.value.hasPicture)
        assertFalse(c.pictureFile.exists())
    }

    // A cut-out PNG was kept as JPEG: its transparent parts turned black, a dark block over the pink ground.
    @Test fun aTransparentPictureKeepsItsTransparency() = runBlocking {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(240, 120, 160))
            for (x in 0 until 10) for (y in 0 until 10) setPixel(x, y, Color.TRANSPARENT)
        }
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertTrue(c.setPicture { ByteArrayInputStream(bytes) })
        val kept = BitmapFactory.decodeFile(c.pictureFile.path)
        assertEquals(40, kept.width)
        assertEquals(0, Color.alpha(kept.getPixel(2, 2)))
        assertEquals(255, Color.alpha(kept.getPixel(30, 60)))
        assertEquals(Color.rgb(240, 120, 160), kept.getPixel(30, 60))
    }

    @Test fun aPhotoWithoutTransparencyIsStillKeptAsJpeg() = runBlocking {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        assertTrue(c.setPicture { ByteArrayInputStream(jpeg(halves(80, 40))) })
        val head = c.pictureFile.readBytes().take(2).map { it.toInt() and 0xff }
        assertEquals(listOf(0xff, 0xd8), head)
    }

    // A camera photo stores "turn me 90°" in EXIF instead of turning its pixels; the background showed it on its side.
    @Test fun aCameraPhotoTurnedByExifComesOutUpright() = runBlocking {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        // Stored landscape, left half red and right half blue; Orientation 6 means "turn 90° clockwise to show".
        val bytes = withOrientation(jpeg(halves(80, 40)), 6)
        assertTrue(c.setPicture { ByteArrayInputStream(bytes) })
        val kept = BitmapFactory.decodeFile(c.pictureFile.path)
        assertEquals("portrait", 40, kept.width)
        assertEquals("portrait", 80, kept.height)
        // Turned clockwise: the left (red) half is now on top.
        assertTrue(isRed(kept.getPixel(20, 10)))
        assertTrue(isBlue(kept.getPixel(20, 70)))
    }

    @Test fun aLargeTurnedPhotoIsScaledAndTurnedInOnePass() {
        val big = withOrientation(jpeg(halves(3000, 1500)), 8)
        val out = LookController.decodeScaled({ ByteArrayInputStream(big) }, LookController.MAX_SIDE)!!
        assertEquals(LookController.MAX_SIDE, out.height)
        assertEquals(LookController.MAX_SIDE / 2, out.width)
        // Turned counter-clockwise (Orientation 8): the left (red) half is now at the bottom.
        assertTrue(isRed(out.getPixel(out.width / 2, out.height - 10)))
        assertTrue(isBlue(out.getPixel(out.width / 2, 10)))
        // Without EXIF nothing is turned.
        val plain = LookController.decodeScaled({ ByteArrayInputStream(jpeg(halves(80, 40))) }, LookController.MAX_SIDE)!!
        assertEquals(80, plain.width)
    }

    @Test fun somethingThatIsNotAPictureChangesNothing() = runBlocking {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        assertFalse(c.setPicture { ByteArrayInputStream("not a picture".toByteArray()) })
        assertFalse(c.setPicture { null })
        assertFalse(c.look.value.hasPicture)
    }

    @Test fun restoreDefaultsKeepsThePicture() = runBlocking {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        val bytes = png(40, 80)
        c.setPicture { ByteArrayInputStream(bytes) }
        c.update { it.copy(style = BackdropStyle.KAWAII, amount = 90, six = 10, intro = it.intro.copy(enabled = true)) }
        val picture = c.look.value.picture
        c.restoreDefaults()
        assertEquals(Look(picture = picture), c.look.value)
    }

    @Test fun aPictureWhoseFileIsGoneIsForgotten() {
        val prefs = InMemorySharedPreferences()
        LookStore(prefs).save(Look(picture = 99L))
        assertFalse(LookController(LookStore(prefs), tmp.newFolder("look")).look.value.hasPicture)
    }

    @Test fun previewPlaysTheIntroAgain() {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        val before = c.introTick.value
        c.previewIntro()
        assertEquals(before + 1, c.introTick.value)
    }
}
