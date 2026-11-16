package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The avatar square on real (drawn, invented) pictures: the drawn looks cut their portrait with it, the guess is worked out once
 * per picture and kept, and the square a community look gets as avatar.img is cut from the picture as the app shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Four colour quarters: red top left, green top right, blue bottom left, white bottom right. */
    private fun quarters(w: Int, h: Int, alpha: Boolean = false): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) for (x in 0 until w) {
            val c = when {
                x < w / 2 && y < h / 2 -> Color.RED
                x >= w / 2 && y < h / 2 -> Color.GREEN
                x < w / 2 -> Color.BLUE
                else -> Color.WHITE
            }
            setPixel(x, y, if (alpha && x < 4 && y < 4) Color.TRANSPARENT else c)
        }
    }

    /** A soft vertical gradient from blush to aqua: nothing a face finder could take for a face. */
    private fun gradient(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) {
            val k = y.toFloat() / h
            val c = Color.rgb((254 - 89 * k).toInt(), (223 - 1 * k).toInt(), (225 + 3 * k).toInt())
            for (x in 0 until w) setPixel(x, y, c)
        }
    }

    private fun keep(bitmap: Bitmap, png: Boolean = false): File {
        val dir = tmp.newFolder()
        val file = File(dir, LookController.PICTURE_FILE)
        file.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
        return file
    }

    private fun near(a: Int, b: Int, by: Int = 40) =
        abs(Color.red(a) - Color.red(b)) <= by && abs(Color.green(a) - Color.green(b)) <= by && abs(Color.blue(a) - Color.blue(b)) <= by

    @Test fun theStardustPortraitIsCutWithTheSharedSquare() {
        val picture = quarters(400, 800).asImageBitmap()
        // The green quarter's middle, a fifth of the width across: the portrait is all green.
        val face = faceCrop(picture, 64, AvatarCrop(.75f, .25f, .2f))!!.asAndroidBitmap()
        assertEquals(64, face.width)
        assertEquals(64, face.height)
        for ((x, y) in listOf(2 to 2, 32 to 32, 61 to 61)) assertTrue("($x, $y)", near(face.getPixel(x, y), Color.GREEN))
        // The blue quarter, another square: the portrait follows.
        val blue = faceCrop(picture, 64, AvatarCrop(.25f, .75f, .2f))!!.asAndroidBitmap()
        assertTrue(near(blue.getPixel(32, 32), Color.BLUE))
    }

    @Test fun eachLookGetsTheChosenSquareOrElseTheRuleForThePicturesShape() {
        val tall = quarters(400, 800).asImageBitmap()
        fun scope(picture: androidx.compose.ui.graphics.ImageBitmap?, avatar: AvatarCrop?) =
            IntroScope(IntroRun(), picture, 1080f, 1920f, Density(2f), 0f, 0f, EffectLevel.FULL, false, IntroFx(), PictureAlign.CENTER, avatar)
        assertEquals(AvatarCrop(.2f, .7f, .3f), scope(tall, AvatarCrop(.2f, .7f, .3f)).avatar)
        assertEquals(AvatarGuess.heuristic(400, 800), scope(tall, null).avatar)
        assertEquals(AvatarCrop.WHOLE, scope(null, null).avatar)
        // The 對齊 setting stays the full-screen backdrops' own: it does not move the square.
        val top = IntroScope(IntroRun(), tall, 1080f, 1920f, Density(2f), 0f, 0f, EffectLevel.FULL, false, IntroFx(), PictureAlign.TOP, null)
        assertEquals(scope(tall, null).avatar, top.avatar)
    }

    @Test fun theFaceFinderIsBestEffortAndTheGuessAlwaysFits() {
        for ((w, h) in listOf(540 to 960, 960 to 540, 600 to 600, 41 to 83)) {
            val b = gradient(w, h)
            // Under Robolectric the face finder may find nothing; on a phone it finds no face in a plain gradient either.
            AvatarGuess.detect(b)?.let { found ->
                val sq = avatarSquare(w, h, found)
                assertTrue(sq.left >= 0 && sq.top >= 0 && sq.left + sq.side <= w && sq.top + sq.side <= h)
            }
            val g = AvatarGuess.guess(b)
            val sq = avatarSquare(w, h, g)
            assertTrue("$w x $h: $g", sq.left >= 0 && sq.top >= 0 && sq.left + sq.side <= w && sq.top + sq.side <= h)
            if (AvatarGuess.detect(b) == null) assertEquals(AvatarGuess.heuristic(w, h), g)
        }
    }

    @Test fun theGuessIsWorkedOutOncePerPictureAndKeptNextToIt() = runBlocking {
        val file = keep(gradient(540, 960))
        val first = AvatarGuess.auto(file, 11L)!!
        val sq = avatarSquare(540, 960, first)
        assertTrue(sq.left >= 0 && sq.top >= 0 && sq.left + sq.side <= 540 && sq.top + sq.side <= 960)
        assertEquals(listOf("picture.jpg.face-11.txt"), AvatarGuess.files(file).map { it.name })
        assertEquals(first, AvatarGuess.peek(file, 11L))
        assertNull(AvatarGuess.peek(file, 12L))
        // A kept guess is read back, not worked out again (this one was written by hand).
        File(file.parentFile, "picture.jpg.face-12.txt").writeText("0.25000 0.75000 0.30000")
        assertEquals(AvatarCrop(.25f, .75f, .3f), AvatarGuess.auto(file, 12L))
        // A new version's guess replaces the old one's file.
        AvatarGuess.auto(file, 13L)
        assertEquals(listOf("picture.jpg.face-13.txt"), AvatarGuess.files(file).map { it.name })
        // No picture that can be read: no guess.
        val bad = File(tmp.newFolder(), LookController.PICTURE_FILE).apply { writeText("not a picture") }
        assertNull(AvatarGuess.auto(bad, 1L))
    }

    @Test fun theCutIsASquareOfThePictureAsShownKeptUnderItsVersionAndSquare() {
        val file = keep(quarters(400, 800))
        val cut = AvatarCuts.cut(file, file, 5L, 0, 55, AvatarCrop(.75f, .25f, .4f))!!
        assertTrue(cut.name, cut.name.startsWith("picture.jpg.avatar-5-0-55-"))
        assertTrue(cut.name, cut.name.endsWith(".jpg"))
        assertEquals(listOf(0xFF, 0xD8), cut.readBytes().take(2).map { it.toInt() and 0xFF })
        val b = BitmapFactory.decodeFile(cut.path)
        assertEquals(AvatarCuts.SIDE, b.width)
        assertEquals(AvatarCuts.SIDE, b.height)
        assertTrue(near(b.getPixel(256, 256), Color.GREEN))
        assertTrue(near(b.getPixel(10, 500), Color.GREEN))
        // The same square again is the kept file; another square replaces it.
        assertEquals(cut, AvatarCuts.cut(file, file, 5L, 0, 55, AvatarCrop(.75f, .25f, .4f)))
        val other = AvatarCuts.cut(file, file, 5L, 0, 55, AvatarCrop(.25f, .75f, .4f))!!
        assertEquals(listOf(other.name), AvatarCuts.files(file).map { it.name })
        assertTrue(near(BitmapFactory.decodeFile(other.path).getPixel(256, 256), Color.BLUE))
        // No square chosen and no guess: the rule for a tall picture (near the top, in the middle: the red and green halves).
        val ruled = BitmapFactory.decodeFile(AvatarCuts.cut(file, file, 5L, 0, 55, null)!!.path)
        assertTrue(near(ruled.getPixel(100, 256), Color.RED))
        assertTrue(near(ruled.getPixel(412, 256), Color.GREEN))
        // A tiny picture is cut and drawn up to the same size.
        val tiny = keep(quarters(4, 6))
        assertEquals(AvatarCuts.SIDE, BitmapFactory.decodeFile(AvatarCuts.cut(tiny, tiny, 1L, 0, 55, AvatarCrop.WHOLE)!!.path).width)
        // What cannot be read gives no cut.
        val bad = File(tmp.newFolder(), LookController.PICTURE_FILE).apply { writeText("not a picture") }
        assertNull(AvatarCuts.cut(bad, bad, 1L, 0, 55, AvatarCrop.WHOLE))
    }

    @Test fun aSeeThroughPictureIsCutAsPng() {
        val file = keep(quarters(400, 800, alpha = true), png = true)
        val cut = AvatarCuts.cut(file, file, 9L, 0, 55, AvatarCrop(0f, 0f, .2f))
        assertNotNull(cut)
        assertTrue(cut!!.name.endsWith(".png"))
        assertEquals(0x89, cut.readBytes()[0].toInt() and 0xFF)
        // Its see-through corner stays see-through (a JPEG would have turned it black).
        assertEquals(0, Color.alpha(BitmapFactory.decodeFile(cut.path).getPixel(1, 1)))
    }
}
