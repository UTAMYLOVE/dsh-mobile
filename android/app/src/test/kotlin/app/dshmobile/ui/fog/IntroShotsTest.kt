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
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), 600, 1275, 2250)
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
        // The flash is over the sigil: at its peak even the monogram's ink (#5E4445) at C is washed most of the way to white.
        // Drawn behind the sigil it only paled the sky and the ink stayed dark.
        val dark = flash.count(372, 674, 452, 754) { red, g, bl -> red + g + bl < 3 * 150 }
        assertEquals("dark monogram pixels at the flash peak", 0, dark)
        // ...but under the type: 核驗通過 lands as sharp ink over the white burst (over it, the slam greyed to #A49596).
        val slam = flash.count(56, 1300, 780, 1480) { red, g, bl -> red < 0x70 && g < 0x58 && bl < 0x58 }
        assertTrue("sharp ink pixels in the slam at the flash peak: $slam", slam > 3000)
    }

    @Test fun theTypeIsGoneBeforeTheIrisReachesIt() {
        // From the exit (2620 ms at 3 s) the brand, date and skip label fade first and the welcome block right after, so the
        // iris never cuts the type in half or leaves the date over the app's own buttons.
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), 2500, 2800)
        // Dark type (ink, or the brand at .75): nothing else in these bands is this dark.
        fun type(b: Bitmap, top: Int, bottom: Int) = b.count(0, top, b.width, bottom) { red, g, bl -> red < 0x90 && g < 0x78 && bl < 0x78 }
        // The HUD strip, and the welcome block between the crest and the floor circle (the crest's own monogram is wiped by the
        // iris itself, and the floor's data ring is not type).
        assertTrue(type(frames[0], 0, 80) > 100 && type(frames[0], 900, 1400) > 2000)
        assertEquals(0, type(frames[1], 0, 80))
        assertEquals(0, type(frames[1], 900, 1400))
    }

    @Test fun overAPictureTheBrandStaysFullInk() {
        // An invented busy picture (rose-brown and pink stripes): the brand and date keep full #5E4445 over it, not .75.
        val bmp = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 800) for (x in 0 until 400) setPixel(x, y, if ((x / 6 + y / 9) % 2 == 0) 0xFF9E7A7A.toInt() else 0xFFDC9FB4.toInt())
        }
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f), 2250, picture = bmp.asImageBitmap()).single()
        val ink = frame.count(36, 20, 240, 60) { red, g, bl -> kotlin.math.abs(red - 0x5E) <= 4 && kotlin.math.abs(g - 0x44) <= 4 && kotlin.math.abs(bl - 0x45) <= 4 }
        assertTrue("full-ink brand pixels: $ink", ink > 30)
    }

    /** Grey: the channels within 6 of each other and darker than near-white (the old aqua scan trail measured #DFE3E4). */
    private fun grey(r: Int, g: Int, b: Int) = maxOf(r, g, b) - minOf(r, g, b) < 6 && .2126f * r + .7152f * g + .0722f * b < 235f

    @Test fun theScanLeavesNoGreyBandOverTheSigil() {
        // Mid-scan at 3 s (scan 640 → 1040 ms). Ring A's disc is at C = (206, 356.85) dp, R = 164.8 dp; at xhdpi × 2.
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f), 900).single()
        val cx = 412f; val cy = 713.7f; val r = 329.6f
        var n = 0
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r) continue
            val c = frame.getPixel(x, y)
            if (grey((c shr 16) and 255, (c shr 8) and 255, c and 255)) n++
        }
        assertEquals("grey pixels over the sigil while scanning", 0, n)
    }

    @Test fun theRibbonsWrapRoundTheCircleInsteadOfCuttingThroughIt() {
        val kit = SigilKit(320f, 2f, 28, .5f)
        for ((i, pm) in kit.ribbonMeasures.withIndex()) {
            assertTrue(pm.length > 1f)
            var d = 0f
            while (d <= pm.length) {
                val p = pm.getPosition(d)
                assertTrue("ribbon $i passes ${kotlin.math.hypot(p.x, p.y)} R from the centre", kotlin.math.hypot(p.x, p.y) >= .8f)
                d += pm.length / 200f
            }
            // Each ends higher than it starts, so the welcome words under the circle are clear by the time they come in.
            assertTrue(pm.getPosition(pm.length).y < pm.getPosition(0f).y)
        }
    }

    @Config(qualifiers = "w600dp-h960dp-port-xhdpi")
    @Test fun onAWideScreenTheWindowStillFramesTheFace() {
        // An invented portrait picture: blush, with a green dot where the guess for a tall picture puts the face (50 %, 21 %).
        val bmp = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 800) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 168) * (y - 168) <= 15 * 15) setPixel(x, y, 0xFF00C800.toInt())
        }
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f), 1150, picture = bmp.asImageBitmap()).single()
        var sx = 0L; var sy = 0L; var n = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val c = frame.getPixel(x, y)
            if ((c shr 16) and 255 < 60 && (c shr 8) and 255 > 150 && c and 255 < 60) { sx += x; sy += y; n++ }
        }
        assertTrue("the dot shows in the window", n > 20)
        // C = (300, 0.39 × 960) dp; within 8 dp.
        val dx = sx / n.toFloat() / 2f - 300f
        val dy = sy / n.toFloat() / 2f - .39f * 960f
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
