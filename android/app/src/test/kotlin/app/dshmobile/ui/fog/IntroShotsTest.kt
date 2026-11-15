package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The startup animation drawn for real (native graphics, captured with PixelCopy so blur and layers show): it renders at phone,
 * small and landscape sizes, the welcome's name is a sticker, ink #5E4445 inside a white outline, the scan leaves no grey over
 * the pink, and on a wide screen the identity window still frames the picture's face.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(): Bitmap {
        rule.waitForIdle()
        val window = rule.activity.window
        val b = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        var result = -1
        rule.runOnUiThread { PixelCopy.request(window, b, { result = it }, Handler(Looper.getMainLooper())) }
        shadowOf(Looper.getMainLooper()).idle()
        check(result == PixelCopy.SUCCESS)
        return b
    }

    private fun play(intro: Intro, vararg frames: Long, picture: ImageBitmap? = null): List<Bitmap> {
        rule.mainClock.autoAdvance = false
        rule.setContent { FogTheme(effectsFor(EffectLevel.FULL, false)) { StartupIntro(intro, picture, false) {} } }
        val start = rule.mainClock.currentTime
        return frames.map { ms ->
            rule.mainClock.advanceTimeBy(start + ms - rule.mainClock.currentTime)
            shot()
        }
    }

    private fun Bitmap.count(left: Int, top: Int, right: Int, bottom: Int, match: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) {
            val c = getPixel(x, y)
            if (match((c shr 16) and 255, (c shr 8) and 255, c and 255)) n++
        }
        return n
    }

    @Test fun theNameIsAnInkStickerWithAWhiteOutline() {
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), 600, 1260, 2250)
        val box = rule.onNodeWithText("歡迎回來，小雨").fetchSemanticsNode().boundsInRoot
        val welcome = frames.last()
        val l = box.left.roundToInt(); val t = box.top.roundToInt(); val r = box.right.roundToInt(); val b = box.bottom.roundToInt()
        val ink = welcome.count(l, t, r, b) { red, g, bl -> red in 0x58..0x64 && g in 0x3e..0x4a && bl in 0x3f..0x4b }
        val white = welcome.count(l, t, r, b) { red, g, bl -> red >= 252 && g >= 250 && bl >= 250 }
        val area = (r - l) * (b - t)
        assertTrue("ink fill: $ink of $area", ink > area / 20)
        assertTrue("white outline: $white of $area", white > area / 40)
        // The flash frame is bright but still in the palette: never more than a white veil over blush.
        val flash = frames[1]
        assertTrue(flash.count(0, 0, flash.width, flash.height) { red, g, bl -> red < 200 && g < 200 && bl < 200 && maxOf(red, g, bl) - minOf(red, g, bl) < 6 } == 0)
    }

    /** Grey: the channels within 6 of each other and darker than near-white (the old aqua scan trail measured #DFE3E4). */
    private fun grey(r: Int, g: Int, b: Int) = maxOf(r, g, b) - minOf(r, g, b) < 6 && .2126f * r + .7152f * g + .0722f * b < 235f

    @Test fun theScanLeavesNoGreyBandOverTheSigil() {
        // Mid-scan at 3 s (scan 640 → 1040 ms). Ring A's disc is at C = (206, 329.4) dp, R = 164.8 dp; at xhdpi × 2.
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f), 900).single()
        val cx = 412f; val cy = 658.8f; val r = 329.6f
        var n = 0
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r) continue
            val c = frame.getPixel(x, y)
            if (grey((c shr 16) and 255, (c shr 8) and 255, c and 255)) n++
        }
        assertEquals("grey pixels over the sigil while scanning", 0, n)
    }

    @Config(qualifiers = "w600dp-h960dp-port-xhdpi")
    @Test fun onAWideScreenTheWindowStillFramesTheFace() {
        // An invented portrait picture: blush, with a green dot where a face would be (50 %, 35 %).
        val bmp = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 800) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 280) * (y - 280) <= 15 * 15) setPixel(x, y, 0xFF00C800.toInt())
        }
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f), 1150, picture = bmp.asImageBitmap()).single()
        var sx = 0L; var sy = 0L; var n = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val c = frame.getPixel(x, y)
            if ((c shr 16) and 255 < 60 && (c shr 8) and 255 > 150 && c and 255 < 60) { sx += x; sy += y; n++ }
        }
        assertTrue("the dot shows in the window", n > 20)
        // C = (300, 0.36 × 960) dp; within 8 dp.
        val dx = sx / n.toFloat() / 2f - 300f
        val dy = sy / n.toFloat() / 2f - .36f * 960f
        assertTrue("the face lands ${kotlin.math.hypot(dx, dy)} dp from the centre", kotlin.math.hypot(dx, dy) <= 8f)
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test fun aSmallPhoneRendersEveryBeat() {
        play(Intro(enabled = true, name = "Mika", seconds = 3f), 300, 900, 1300, 2200).forEach { assertTrue(it.width > 0) }
    }

    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun landscapeRendersEveryBeat() {
        play(Intro(enabled = true, name = "星野夢美子桜庭ひなた🌸", seconds = 1.5f), 300, 700, 1100).forEach { assertTrue(it.width > 0) }
    }
}
