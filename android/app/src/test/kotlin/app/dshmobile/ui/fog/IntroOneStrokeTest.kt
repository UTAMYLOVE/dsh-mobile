package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.dshmobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 「一筆描」: the short look's beats (it ends at min(setting, 2.5 s), its moves are done by 1.53 s, a 1.5 s run gives up the pen
 * before the name), the crest as one unbroken brush line, the name's strokes in writing order, and the look drawn for real (native
 * graphics, PixelCopy): the pen draws before anything is written, the name is written in ink, the flower opens onto the user's
 * picture and the picture develops, petals never cover the name, and no grey shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroOneStrokeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun itIsTheShortLook() {
        // The default 3 s setting runs 2.5 s: everything has moved by 1.53 s, the finished name holds about 0.6 s, then the exit.
        val t = OneStrokeTimes.of(3000f)
        assertEquals(2500f, t.plan.total, 0f)
        assertEquals(1f, t.pace, 0f)
        assertEquals(1530f, t.settled, .01f)
        assertEquals(2140f, t.exit, .01f)
        assertTrue("settled at ${t.settled}", t.settled < 2000f)
        assertEquals(t.exit - 1f, t.still, 0f)
    }

    @Test fun aLongerSettingDoesNotMakeItLonger() {
        val t3 = OneStrokeTimes.of(3000f)
        val t8 = OneStrokeTimes.of(8000f)
        assertEquals(2500f, t8.plan.total, 0f)
        assertEquals(2140f, t8.exit, .01f)
        for ((a, b) in listOf(t3.drawAt to t8.drawAt, t3.hit to t8.hit, t3.writeMs to t8.writeMs, t3.settled to t8.settled)) assertEquals(a, b, 0f)
        // Under the cap it ends on the setting, holding a little less.
        val t2 = OneStrokeTimes.of(2300f)
        assertEquals(2300f, t2.plan.total, 0f)
        assertEquals(1f, t2.pace, 0f)
        assertEquals(t3.settled, t2.settled, .01f)
    }

    @Test fun aShortRunGivesUpThePenBeforeTheName() {
        val t = OneStrokeTimes.of(1500f)
        assertEquals(1500f, t.plan.end(4), .01f)
        assertTrue(t.pace < 1f && t.pace > .5f)
        // The name keeps its floor and the finished name holds at least 0.3 s...
        assertTrue("write ${t.plan.length(2)}", t.plan.length(2) >= OneStrokeTimes.WRITE_FLOOR - .01f)
        assertTrue("hold ${t.exit - t.settled}", t.exit - t.settled >= OneStrokeTimes.HOLD_MIN - .01f)
        // ...because the pen shrank more than the writing did.
        assertTrue(t.drawMs / OneStrokeTimes.DRAW_MS < t.plan.length(2) / OneStrokeTimes.WRITE_MS)
        assertEquals(OneStrokeTimes.DRAW_MS * t.pace, t.drawMs, .01f)
    }

    @Test fun thePenGoesOneWayTheGhostLeavesAndThePictureDevelops() {
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
        // The picture develops once the name is whole, before the exit (and is developed in the reduced-motion still).
        assertEquals(0f, t.develop(t.settled), 0f)
        assertEquals(1f, t.develop(t.settled + OneStrokeTimes.DEVELOP_MS), 0f)
        assertTrue(t.settled + OneStrokeTimes.DEVELOP_MS <= t.exit)
        assertEquals(1f, t.develop(0f, reduced = true), 0f)
        val short = OneStrokeTimes.of(1500f)
        assertEquals(1f, short.develop(short.exit), 0f)
    }

    @Test fun theCrestIsOneUnbrokenBrushLine() {
        val dp = 2f
        val c = Offset(412f, 660f)
        val r = 260f
        val crest = OneStrokeCrest(824f, 1830f, c, r, dp, 6)
        val pm = android.graphics.PathMeasure(crest.path.asAndroidPath(), false)
        assertTrue(pm.length > 9f * r)
        assertFalse("the crest has a second contour", pm.nextContour())
        // The pen lands on the ring at one o'clock (-60°) and lifts inside it, on the flower.
        val start = crest.penAt(0f)
        val a = Math.toRadians(OneStrokeCrest.RING_START.toDouble())
        assertEquals(c.x + r * cos(a).toFloat(), start.x, 1f)
        assertEquals(c.y + r * sin(a).toFloat(), start.y, 1f)
        val end = crest.penAt(1f)
        assertTrue(hypot(end.x - c.x, end.y - c.y) < .7f * r)
        // Ring, then curl, then flower.
        assertTrue(crest.ringEnd > .4f && crest.ringEnd < crest.curlEnd && crest.curlEnd < .8f)
        // The brush lands at about a fifth of its width and lifts to a hairline.
        val swell = crest.pressure(crest.ringEnd / 2f)
        assertTrue(crest.pressure(0f) < .25f * swell)
        assertTrue(crest.pressure(1f) < .15f * swell)
        // No corner from the ring's end, through the curl, onto the flower (up to the petal's notch, 6° on): the curl joins the
        // petal along its edge.
        var k = crest.ringEnd - .01f
        var prev = crest.tangentAt(k)
        var worst = 0f
        var at = 0f
        while (k < crest.curlEnd + .004f) {
            k += .001f
            val t = crest.tangentAt(k)
            val turn = Math.toDegrees(acos((prev.x * t.x + prev.y * t.y).coerceIn(-1f, 1f).toDouble())).toFloat()
            if (turn > worst) { worst = turn; at = k }
            prev = t
        }
        assertTrue("sharpest turn through the curl: $worst° at $at (curl ${crest.ringEnd}..${crest.curlEnd})", worst < 6f)
    }

    @Test fun theNameIsWrittenInStrokeOrder() {
        val hand = rule.activity.resources.getFont(R.font.intro_hand)
        fun strokes(ch: String): OneStrokeSkeleton.Strokes {
            val paint = android.graphics.Paint().apply { typeface = hand; textSize = 200f }
            val path = android.graphics.Path()
            paint.getTextPath(ch, 0, ch.length, 0f, 200f, path)
            val s = OneStrokeSkeleton.of(path, 200f)
            assertNotNull(s)
            return s!!
        }
        fun top(line: FloatArray): Float { var m = Float.MAX_VALUE; for (i in 1 until line.size step 2) m = minOf(m, line[i]); return m }
        fun midX(line: FloatArray): Float { var s = 0f; for (i in 0 until line.size step 2) s += line[i]; return s / (line.size / 2) }
        // 小: the tall middle hook first (it starts highest), then the left dot, then the right one.
        val xiao = strokes("小").lines
        assertTrue("小 has ${xiao.size} strokes", xiao.size >= 3)
        assertEquals(xiao.minOf { top(it) }, top(xiao[0]), .5f)
        assertTrue(midX(xiao[1]) < midX(xiao[0]) && midX(xiao[2]) > midX(xiao[0]))
        // 林: the left 木 is written before the right one.
        val lin = strokes("林").lines
        val half = lin.size / 2
        val left = lin.take(half).map { midX(it) }.average()
        val right = lin.drop(half).map { midX(it) }.average()
        assertTrue("left part first: $left < $right", left < right)
        // A stroke runs from where the pen starts: top first, then left.
        for (line in xiao) assertTrue(line[1] + .5f * line[0] <= line[line.size - 1] + .5f * line[line.size - 2] + .5f)
        assertTrue(strokes("小").pen in 2f..30f)
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

    // The screen is 824 × 1830 px: the crest's ring is round (412, 677), radius 264; the words sit below y 980 and the name in
    // rows 1090–1240. No petals or sparkles (IntroFx scale 0) unless a test asks, so a pixel test never lands on one.
    private fun ink(r: Int, g: Int, b: Int) = abs(r - 0x5E) <= 10 && abs(g - 0x44) <= 10 && abs(b - 0x45) <= 10
    // The crest's line: rose brown at .8 over the blush.
    private fun crestInk(r: Int, g: Int, b: Int) = abs(r - 177) <= 12 && abs(g - 142) <= 12 && abs(b - 143) <= 12
    private fun grey(r: Int, g: Int, b: Int) = r < 200 && g < 200 && b < 200 && maxOf(r, g, b) - minOf(r, g, b) < 6
    private fun brown(r: Int, g: Int, b: Int) = abs(r - 0x9E) <= 3 && abs(g - 0x7A) <= 3 && abs(b - 0x7A) <= 3

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

    @Test fun halfWrittenNameShowsOnlyWholeStrokesSoFar() {
        // Part way through 小 only some of its ink shows (the brush has not reached the rest), and it is never grey.
        val (part, whole) = play(Intro(enabled = true, name = "小", seconds = 3f), 1160, 1860)
        val p = part.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) }
        val w = whole.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) }
        assertTrue("part $p of $w", p in 1 until w)
        assertEquals(0, part.count(0, 980, 824, 1550) { r, g, b -> grey(r, g, b) })
    }

    @Test fun theFlowerOpensOntoThePictureAndThePictureDevelops() {
        // An invented flat rose-brown picture: veiled before the line closes, clear inside the flower once it opens.
        val bmp = Bitmap.createBitmap(412, 915, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF9E7A7A.toInt()) }
        val (before, written, developed) = play(Intro(enabled = true, name = "小雨", seconds = 3f), 600, 1500, 2080, picture = bmp.asImageBitmap())
        fun clear(f: Bitmap) = f.count(392, 580, 432, 620) { r, g, b -> brown(r, g, b) }
        assertEquals(0, clear(before))
        assertEquals(40 * 40, clear(written))
        // Outside the flower the veil stays (thinner once developed), so the line and the name read on any picture...
        for (f in listOf(written, developed)) assertEquals(0, f.count(20, 1300, 120, 1400) { r, g, b -> brown(r, g, b) })
        // ...and away from the words the picture shows through more once the name is written.
        fun red(f: Bitmap) = (f.getPixel(40, 200) shr 16) and 255
        assertTrue("red ${red(written)} -> ${red(developed)}", red(developed) < red(written) - 12)
        // The band behind the words keeps them as easy to read: its middle hardly changes.
        val mid = (written.getPixel(30, 1180) shr 16 and 255) - (developed.getPixel(30, 1180) shr 16 and 255)
        assertTrue("band moved by $mid", abs(mid) <= 8)
    }

    @Test fun withThePortraitOffTheFlowerBloomsPinkInstead() {
        val bmp = Bitmap.createBitmap(412, 915, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF9E7A7A.toInt()) }
        val f = play(Intro(enabled = true, name = "小雨", seconds = 3f, portrait = false), 1860, picture = bmp.asImageBitmap()).single()
        assertEquals(0, f.count(392, 580, 432, 620) { r, g, b -> brown(r, g, b) })
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
        // Winter snow (an aqua ring in a white disc) fades out over the words: over them, or behind them, the ink's soft edge and
        // the aqua ring made grey pixels round the name.
        val times = LongArray(18) { 1500L + 50L * it }
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), *times, fx = IntroFx(season = IntroSeason.SNOW, scale = 1.4f))
        for ((i, f) in frames.withIndex()) assertEquals("grey pixels at ${times[i]} ms", 0, f.count(0, 980, 824, 1600) { r, g, b -> grey(r, g, b) })
    }

    @Test fun petalsNeverCoverTheName() {
        // Plenty of petals (pink and white) drift past while the name is read: none shows over its rows.
        val times = LongArray(12) { 1550L + 50L * it }
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), *times, fx = IntroFx(scale = 1.4f))
        fun petal(r: Int, g: Int, b: Int) = (abs(r - 0xDC) <= 10 && abs(g - 0x9F) <= 10 && abs(b - 0xB4) <= 10) || (r >= 253 && g >= 252 && b >= 252)
        for ((i, f) in frames.withIndex()) assertEquals("petal pixels over the name at ${times[i]} ms", 0, f.count(0, 1090, 824, 1240) { r, g, b -> petal(r, g, b) })
        // They still drift elsewhere.
        assertTrue(frames.sumOf { f -> f.count(0, 0, 824, 900) { r, g, b -> petal(r, g, b) } } > 0)
    }
}
