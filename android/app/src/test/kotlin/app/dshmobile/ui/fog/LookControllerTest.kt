package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    @Test fun theRecolouredCopyIsKeptForTheNextStartAndGoesWithThePicture() = runBlocking {
        val dir = tmp.newFolder("look")
        val c = LookController(LookStore(InMemorySharedPreferences()), dir)
        val bytes = png(40, 80)
        c.setPicture { ByteArrayInputStream(bytes) }
        val v = c.look.value.picture
        assertTrue(LookPictures.load(c.pictureFile, v, 100, 55) != null)
        assertEquals(1, LookPictures.copies(c.pictureFile).size)
        // Another strength replaces the copy instead of piling up files.
        LookPictures.load(c.pictureFile, v, 60, 55)
        assertEquals(listOf("picture.jpg.six-$v-60-55.jpg"), LookPictures.copies(c.pictureFile).map { it.name })
        c.removePicture()
        assertEquals(emptyList<java.io.File>(), LookPictures.copies(c.pictureFile))
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
