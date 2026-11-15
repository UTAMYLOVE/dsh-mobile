package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 「一筆描」: the short look's beats (its moves stay under 2.5 s and are done by 1.53 s at the default 3 s; longer settings only
 * hold), the crest as one unbroken line, and the look drawn for real (native graphics, PixelCopy): the pen draws before anything is
 * written, the name is written in ink, the flower opens onto the user's picture, and no grey shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroOneStrokeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun itIsTheShortLook() {
        // Every move together, with the shortest hold, fits in 2.5 s...
        assertTrue(OneStrokeTimes.MOVES_MS + OneStrokeTimes.HOLD_MIN <= 2500f)
        // ...and at the default 3 s the crest, the glow and the name are all done in under 2 s.
        val t = OneStrokeTimes.of(3000f)
        assertEquals(1f, t.pace, 0f)
        assertTrue("settled at ${t.settled}", t.settled < 2000f)
        assertEquals(3000f - OneStrokeTimes.EXIT_MS, t.exit, .01f)
    }

    @Test fun longerRunsOnlyHoldLonger() {
        val t3 = OneStrokeTimes.of(3000f)
        val t8 = OneStrokeTimes.of(8000f)
        // The pen draws, the crest closes and the name is written at the same moments at 3 and 8 s...
        assertEquals(t3.drawAt, t8.drawAt, 0f)
        assertEquals(t3.drawMs, t8.drawMs, 0f)
        assertEquals(t3.hit, t8.hit, 0f)
        assertEquals(t3.writeMs, t8.writeMs, 0f)
        assertEquals(t3.settled, t8.settled, 0f)
        // ...and 8 s only waits longer before the exit, which ends exactly on time.
        assertEquals(8000f - OneStrokeTimes.EXIT_MS, t8.exit, .01f)
        assertEquals(8000f, t8.plan.total, 0f)
        assertEquals(t8.exit - 1f, t8.still, 0f)
    }

    @Test fun aShortRunTightensEveryMoveAndStillEndsOnTime() {
        val t = OneStrokeTimes.of(1500f)
        assertTrue(t.pace < 1f && t.pace > .65f)
        assertEquals(OneStrokeTimes.DRAW_MS * t.pace, t.drawMs, .01f)
        assertEquals(1500f, t.plan.end(4), .01f)
        // The finished name still shows a moment before the exit.
        assertTrue(t.exit - t.settled >= OneStrokeTimes.HOLD_MIN - .01f)
    }

    @Test fun thePenGoesOneWayAndTheGhostLeavesWhenTheLineCloses() {
        val t = OneStrokeTimes.of(3000f)
        assertEquals(0f, t.drawK(t.drawAt), 0f)
        assertEquals(1f, t.drawK(t.hit), 0f)
        var last = -1f
        var ms = t.drawAt
        while (ms <= t.hit) { val k = t.drawK(ms); assertTrue(k >= last); last = k; ms += 10f }
        assertEquals(0f, t.ghost(0f), 0f)
        assertTrue(t.ghost(t.drawAt + 100f) > .99f)
        assertEquals(0f, t.ghost(t.hit + 300f), 0f)
        // The flower opens after the line closes, at once for reduced motion.
        assertEquals(0f, t.open(t.hit), 0f)
        assertEquals(1f, t.open(t.hit + 500f), 0f)
        assertEquals(1f, t.open(0f, reduced = true), 0f)
    }

    @Test fun theCrestIsOneUnbrokenLine() {
        val dp = 2f
        val crest = OneStrokeCrest(824f, 1830f, androidx.compose.ui.geometry.Offset(412f, 660f), 260f, dp, 6)
        val pm = android.graphics.PathMeasure(crest.path.asAndroidPath(), false)
        assertTrue(pm.length > 9f * 260f)
        assertFalse("the crest has a second contour", pm.nextContour())
        // The pen lands at 12 o'clock on the ring and lifts inside it, on the flower.
        val start = crest.penAt(0f)
        assertEquals(412f, start.x, 1f)
        assertEquals(660f - 260f, start.y, 1f)
        val end = crest.penAt(1f)
        assertTrue(hypot(end.x - 412f, end.y - 660f) < .7f * 260f)
        // Ring, then curl, then flower; the brush lands and lifts thinner than it swells round the ring.
        assertTrue(crest.ringEnd > .4f && crest.ringEnd < crest.curlEnd && crest.curlEnd < .8f)
        val swell = crest.pressure(crest.ringEnd / 2f)
        assertTrue(crest.pressure(0f) < .7f * swell)
        assertTrue(crest.pressure(1f) < .5f * swell)
    }

    // --- Drawn for real ---

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

    private fun play(
        intro: Intro, vararg frames: Long, picture: ImageBitmap? = null, fx: IntroFx = IntroFx(scale = 0f), level: EffectLevel = EffectLevel.FULL,
    ): List<Bitmap> {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FogTheme(effectsFor(level, false)) {
                StartupIntro(intro.copy(style = IntroStyle.ONE_STROKE), picture, false, fx = fx) {}
            }
        }
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

    // The screen is 824 × 1830 px: the crest's ring is round (412, 677), radius 264; the words sit below y 980. No petals or
    // sparkles (IntroFx scale 0), so a pixel test never lands on one.
    private fun ink(r: Int, g: Int, b: Int) = abs(r - 0x5E) <= 10 && abs(g - 0x44) <= 10 && abs(b - 0x45) <= 10
    private fun crestInk(r: Int, g: Int, b: Int) = abs(r - 186) <= 14 && abs(g - 140) <= 14 && abs(b - 148) <= 14
    private fun grey(r: Int, g: Int, b: Int) = r < 200 && g < 200 && b < 200 && maxOf(r, g, b) - minOf(r, g, b) < 6

    @Test fun thePenDrawsTheCrestThenTheNameIsWrittenInInk() {
        val (drawing, written) = play(Intro(enabled = true, name = "小雨", seconds = 3f), 450, 1860)
        val crestNow = drawing.count(100, 370, 724, 975) { r, g, b -> crestInk(r, g, b) }
        assertTrue("crest line pixels while drawing: $crestNow", crestNow > 300)
        assertEquals("nothing is written while the pen draws", 0, drawing.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) })
        val name = written.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) }
        assertTrue("ink pixels of the written name: $name", name > 2500)
        // The finished crest has more line than the half-drawn one.
        assertTrue(written.count(100, 370, 724, 975) { r, g, b -> crestInk(r, g, b) } > crestNow)
        for (f in listOf(drawing, written)) assertEquals(0, f.count(0, 0, f.width, f.height) { r, g, b -> grey(r, g, b) })
    }

    @Test fun theFlowerOpensOntoThePicture() {
        // An invented flat rose-brown picture: veiled before the line closes, clear inside the flower once it opens.
        val bmp = Bitmap.createBitmap(412, 915, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF9E7A7A.toInt()) }
        val (before, after) = play(Intro(enabled = true, name = "小雨", seconds = 3f), 600, 1860, picture = bmp.asImageBitmap())
        fun clear(f: Bitmap) = f.count(392, 580, 432, 620) { r, g, b -> abs(r - 0x9E) <= 3 && abs(g - 0x7A) <= 3 && abs(b - 0x7A) <= 3 }
        assertEquals(0, clear(before))
        assertEquals(40 * 40, clear(after))
        // Outside the flower the veil stays, so the line and the name read on any picture.
        assertEquals(0, after.count(20, 1300, 120, 1400) { r, g, b -> abs(r - 0x9E) <= 3 && abs(g - 0x7A) <= 3 && abs(b - 0x7A) <= 3 })
    }

    @Test fun withThePortraitOffTheFlowerBloomsPinkInstead() {
        val bmp = Bitmap.createBitmap(412, 915, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF9E7A7A.toInt()) }
        val f = play(Intro(enabled = true, name = "小雨", seconds = 3f, portrait = false), 1860, picture = bmp.asImageBitmap()).single()
        assertEquals(0, f.count(392, 580, 432, 620) { r, g, b -> abs(r - 0x9E) <= 3 && abs(g - 0x7A) <= 3 && abs(b - 0x7A) <= 3 })
    }

    @Test fun aNameTheHandwritingLacksStillShows() {
        // An emoji has no outline to write along: it wipes in instead; nothing throws and the rest is written in ink.
        val f = play(Intro(enabled = true, name = "小雨🌸", seconds = 3f), 1860).single()
        assertTrue(f.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) } > 2000)
    }

    @Test fun aLongNameFitsTheScreen() {
        val long = play(Intro(enabled = true, name = "小雨小雨小雨小雨小雨小雨小雨小雨小雨小雨小雨小雨", seconds = 3f), 1860).single()
        // It fits: no ink in the side margins.
        assertEquals(0, long.count(0, 980, 40, 1600) { r, g, b -> ink(r, g, b) } + long.count(784, 980, 824, 1600) { r, g, b -> ink(r, g, b) })
        assertTrue(long.count(0, 980, 824, 1600) { r, g, b -> ink(r, g, b) } > 5000)
    }

    @Test fun withoutANameTheWelcomeIsWrittenLarge() {
        // No name: the welcome words are the line written in (large), with no small line above them.
        val f = play(Intro(enabled = true, name = "", seconds = 3f), 1860).single()
        val words = f.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) }
        assertTrue("ink pixels of 歡迎回來 written large: $words", words > 5000)
    }

    @Test fun snowNeverTurnsTheNameGrey() {
        // Winter snow (an aqua ring in a white disc) drifts in front of the words: behind them, the ink's soft edge over the
        // aqua ring made grey pixels round the name.
        val times = LongArray(21) { 1600L + 50L * it }
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), *times, fx = IntroFx(season = IntroSeason.SNOW, scale = 1.4f))
        for ((i, f) in frames.withIndex()) assertEquals("grey pixels at ${times[i]} ms", 0, f.count(0, 980, 824, 1600) { r, g, b -> grey(r, g, b) })
    }
}
