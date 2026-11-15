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
import kotlin.math.min
import kotlin.math.sin

/**
 * 「一筆描」: the short look's beats (1.95 s at the default setting, 2.5 s only as the ceiling at 8 s, a 1.5 s run gives up the pen
 * before the name), the pen's even pace, the charge running back along the line, the crest as one unbroken brush line, the name's
 * strokes in writing order, and the look drawn for real (native graphics, PixelCopy): the pen draws before anything is written,
 * the name is written in ink, the words all stop together, an emoji hangs outside the centring, the flower opens onto the
 * user's picture and the picture develops, petals never cover the name, and no grey shows (snow included, at SAVER too).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroOneStrokeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun itIsTheShortLook() {
        // The default 3 s setting runs 1.95 s: everything has moved by 1.35 s, the finished name holds 0.32 s, then the exit.
        val t = OneStrokeTimes.of(3000f)
        assertEquals(1950f, t.plan.total, .01f)
        assertEquals(OneStrokeTimes.NATURAL_MS, t.plan.total, .01f)
        assertEquals(1f, t.pace, 0f)
        assertEquals(90f, t.drawAt, .01f)
        assertEquals(740f, t.drawMs, .01f)
        assertEquals(830f, t.hit, .01f)
        assertEquals(1350f, t.settled, .01f)
        assertEquals(1670f, t.exit, .01f)
        assertEquals(t.exit - 1f, t.still, 0f)
    }

    @Test fun onlyALongerSettingLengthensTheHoldUpToTheCeiling() {
        val t3 = OneStrokeTimes.of(3000f)
        // Up to 3 s the length is the look's own (a setting under it ends on the setting).
        assertEquals(1950f, OneStrokeTimes.of(2300f).plan.total, .01f)
        assertEquals(1800f, OneStrokeTimes.of(1800f).plan.total, .01f)
        // Above 3 s only the hold grows, evenly, to 2.5 s at 8 s, never past the setting.
        val t5 = OneStrokeTimes.of(5000f)
        val t8 = OneStrokeTimes.of(8000f)
        assertEquals(2170f, t5.plan.total, .01f)
        assertEquals(2500f, t8.plan.total, .01f)
        assertEquals(OneStrokeTimes.MAX_MS, t8.plan.total, 0f)
        var last = 0f
        var s = 1500f
        while (s <= 8000f) {
            val total = OneStrokeTimes.of(s).plan.total
            assertTrue("$s ms: $total", total <= min(s, OneStrokeTimes.MAX_MS) + .01f && total >= last - .01f)
            last = total
            s += 100f
        }
        // The moves are the same; only the wait before the exit is longer.
        for (t in listOf(t5, t8)) {
            for ((a, b) in listOf(t3.drawAt to t.drawAt, t3.hit to t.hit, t3.writeMs to t.writeMs, t3.settled to t.settled)) assertEquals(a, b, 0f)
            assertEquals(OneStrokeTimes.EXIT_MS, t.plan.total - t.exit, .01f)
        }
        assertEquals(2500f - 280f - 1350f, t8.exit - t8.settled, .01f)
    }

    @Test fun aShortRunGivesUpThePenBeforeTheName() {
        val t = OneStrokeTimes.of(1500f)
        assertEquals(1500f, t.plan.end(4), .01f)
        assertTrue(t.pace < 1f && t.pace > .5f)
        // The name keeps its floor and the finished name holds 0.32 s...
        assertTrue("write ${t.plan.length(2)}", t.plan.length(2) >= OneStrokeTimes.WRITE_FLOOR - .01f)
        assertTrue("hold ${t.exit - t.settled}", t.exit - t.settled >= OneStrokeTimes.HOLD_MS - .01f)
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
        // The picture develops once the name is whole, by the exit (and is developed in the reduced-motion still).
        assertEquals(0f, t.develop(t.settled), 0f)
        assertEquals(1f, t.develop(t.exit), 0f)
        assertEquals(1f, OneStrokeTimes.of(8000f).develop(t.settled + OneStrokeTimes.DEVELOP_MS), 0f)
        assertEquals(1f, t.develop(0f, reduced = true), 0f)
        val short = OneStrokeTimes.of(1500f)
        assertEquals(1f, short.develop(short.exit), 0f)
    }

    @Test fun thePenKeepsAnEvenPace() {
        // The real crest on the test screen (412 × 915 dp at xhdpi): the pen never runs faster than 2.6 dp/ms (no more than 45 dp
        // in a 60 Hz frame), never more than 1.25× its average, and lands and lifts from standstill.
        val dp = 2f
        val crest = OneStrokeCrest(824f, 1830f, Offset(412f, 677f), 263.7f, dp, 0)
        val t = OneStrokeTimes.of(3000f)
        val lengthDp = crest.length / dp
        var peak = 0f
        var ms = t.drawAt
        while (ms < t.hit) { peak = maxOf(peak, (t.drawK(ms + 1f) - t.drawK(ms)) * lengthDp); ms += 1f }
        val avg = lengthDp / t.drawMs
        assertTrue("peak $peak dp/ms (average $avg)", peak <= 2.6f && peak / avg <= 1.25f)
        var frame = t.drawAt
        while (frame < t.hit) {
            val step = (t.drawK(frame + 1000f / 60f) - t.drawK(frame)) * lengthDp
            assertTrue("$step dp in the frame at $frame ms", step <= 45f)
            frame += 1000f / 60f
        }
        assertTrue((t.drawK(t.drawAt + 1f)) * lengthDp < .05f)
        assertTrue((1f - t.drawK(t.hit - 1f)) * lengthDp < .05f)
        // The ring (the line's first half) is drawn at that even pace too, over a quarter of a second or more.
        var ringAt = t.drawAt
        while (t.drawK(ringAt) < crest.ringEnd) ringAt += 1f
        assertTrue("ring drawn in ${ringAt - t.drawAt} ms", ringAt - t.drawAt >= 300f)
        // A soft start and stop on either side of the even pace, continuous where they meet.
        for (u in listOf(.1f, .2f, .5f, .8f, .9f)) {
            val g = OneStrokeTimes.glide(u, .12f, .15f)
            assertEquals(g, OneStrokeTimes.glide(u + 1e-4f, .12f, .15f), 1e-3f)
        }
    }

    @Test fun theChargeRunsBackAlongTheLineThenSettles() {
        val t = OneStrokeTimes.of(3000f)
        assertEquals(0f, t.chargeGlow(t.hit - 1f), 0f)
        assertEquals(0f, t.charge(t.hit), 0f)
        assertTrue(t.charge(t.hit + 130f) in .3f..0.7f)
        assertEquals(1f, t.charge(t.hit + OneStrokeTimes.CHARGE_MS), 0f)
        // Lit while it runs, brightest while the whole line holds, then gone into the breathing.
        assertEquals(.8f, t.chargeGlow(t.hit + 100f), 0f)
        assertEquals(1f, t.chargeGlow(t.hit + OneStrokeTimes.CHARGE_MS + 80f), 0f)
        assertEquals(0f, t.chargeGlow(t.hit + OneStrokeTimes.CHARGE_MS + OneStrokeTimes.PEAK_MS + OneStrokeTimes.SETTLE_MS), 0f)
        assertEquals(0f, t.glowIn(t.hit + OneStrokeTimes.CHARGE_MS), 0f)
        assertEquals(1f, t.glowIn(t.hit + OneStrokeTimes.CHARGE_MS + 360f), 0f)
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
        // The brush lands at 1.5 dp (no hairline), is at its full width 40 dp on, and lifts to a hairline.
        val swell = crest.pressure(crest.ringEnd / 2f)
        assertEquals(1.5f, crest.pressure(0f) * OneStrokeCrest.INK_DP, .02f)
        assertTrue(crest.pressure(40f * dp / crest.length) >= 1f)
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

    private fun Bitmap.where(match: (Int, Int, Int) -> Boolean): List<String> {
        val out = ArrayList<String>()
        for (y in 0 until height) for (x in 0 until width) {
            val c = getPixel(x, y)
            if (out.size < 6 && match((c shr 16) and 255, (c shr 8) and 255, c and 255)) out += "($x,$y)=#%06X".format(c and 0xFFFFFF)
        }
        return out
    }

    // The screen is 824 × 1830 px: the crest's ring is round (412, 677), radius 264; the words sit below y 980 and the name in
    // rows 1090–1240. No petals or sparkles (IntroFx scale 0) unless a test asks, so a pixel test never lands on one.
    private fun ink(r: Int, g: Int, b: Int) = abs(r - 0x5E) <= 10 && abs(g - 0x44) <= 10 && abs(b - 0x45) <= 10
    // The crest's line: rose brown at .8 over the blush.
    private fun crestInk(r: Int, g: Int, b: Int) = abs(r - 177) <= 12 && abs(g - 142) <= 12 && abs(b - 143) <= 12
    private fun grey(r: Int, g: Int, b: Int) = r < 200 && g < 200 && b < 200 && maxOf(r, g, b) - minOf(r, g, b) < 6
    // The art director's stricter check: any colourless pixel darker than near-white.
    private fun lightGrey(r: Int, g: Int, b: Int) = maxOf(r, g, b) - minOf(r, g, b) < 6 && .2126f * r + .7152f * g + .0722f * b < 235f
    private fun brown(r: Int, g: Int, b: Int) = abs(r - 0x9E) <= 3 && abs(g - 0x7A) <= 3 && abs(b - 0x7A) <= 3

    @Test fun thePenDrawsTheCrestThenTheNameIsWrittenInInk() {
        val (drawing, written) = play(Intro(enabled = true, name = "小雨", seconds = 3f), 450, 1500)
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
        val (part, whole) = play(Intro(enabled = true, name = "小", seconds = 3f), 1160, 1500)
        val p = part.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) }
        val w = whole.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) }
        assertTrue("part $p of $w", p in 1 until w)
        assertEquals(0, part.count(0, 980, 824, 1550) { r, g, b -> grey(r, g, b) })
    }

    @Test fun theFlowerOpensOntoThePictureAndThePictureDevelops() {
        // An invented flat rose-brown picture: veiled before the line closes, clear inside the flower once it opens.
        val bmp = Bitmap.createBitmap(412, 915, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF9E7A7A.toInt()) }
        val (before, written, developed) = play(Intro(enabled = true, name = "小雨", seconds = 3f), 600, 1300, 1650, picture = bmp.asImageBitmap())
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
        val f = play(Intro(enabled = true, name = "小雨", seconds = 3f, portrait = false), 1500, picture = bmp.asImageBitmap()).single()
        assertEquals(0, f.count(392, 580, 432, 620) { r, g, b -> brown(r, g, b) })
    }

    @Test fun aNameTheHandwritingLacksStillShows() {
        // An emoji has no outline to write along: it fades and grows in instead; nothing throws and the rest is written in ink.
        val (early, f) = play(Intro(enabled = true, name = "小雨🌸", seconds = 3f), 1200, 1500)
        assertTrue(f.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) } > 2000)
        // The emoji (pink) shows only once the characters' strokes are written.
        fun pink(r: Int, g: Int, b: Int) = abs(r - 0xDC) <= 8 && abs(g - 0x9F) <= 8 && abs(b - 0xB4) <= 8
        assertEquals(0, early.count(0, 1060, 824, 1260) { r, g, b -> pink(r, g, b) })
        assertTrue(f.count(0, 1060, 824, 1260) { r, g, b -> pink(r, g, b) } > 200)
        // The characters stay on the crest's axis (x 412; they sat 95 px left of it): the emoji hangs outside the centring.
        var lo = f.width
        var hi = -1
        for (y in 1060 until 1260) for (x in 0 until f.width) {
            val c = f.getPixel(x, y)
            if (ink((c shr 16) and 255, (c shr 8) and 255, c and 255)) { lo = minOf(lo, x); hi = maxOf(hi, x) }
        }
        assertTrue("小雨 at $lo..$hi", abs((lo + hi) / 2 - 412) <= 12)
    }

    @Test fun aLongNameFitsTheScreen() {
        val long = play(Intro(enabled = true, name = "小雨小雨小雨小雨小雨小雨小雨小雨小雨小雨小雨小雨", seconds = 3f), 1500).single()
        // It fits: no ink in the side margins.
        assertEquals(0, long.count(0, 980, 40, 1600) { r, g, b -> ink(r, g, b) } + long.count(784, 980, 824, 1600) { r, g, b -> ink(r, g, b) })
        assertTrue(long.count(0, 980, 824, 1600) { r, g, b -> ink(r, g, b) } > 5000)
    }

    @Test fun withoutANameTheWelcomeIsWrittenLarge() {
        // No name: the welcome words are the line written in (large), with no small line above them.
        val f = play(Intro(enabled = true, name = "", seconds = 3f), 1500).single()
        val words = f.count(0, 980, 824, 1550) { r, g, b -> ink(r, g, b) }
        assertTrue("ink pixels of 歡迎回來 written large: $words", words > 5000)
    }

    // Winter snow (a white flake with a faint aqua rim) falls behind the crest and fades out over the words: aqua over the
    // rose-brown line, over the ink's soft edge or at a third over the blush made grey. At SAVER as well, where it is fewest.
    private fun snowStaysClear(level: EffectLevel) {
        val times = LongArray(10) { 300L + 130L * it }
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), *times, fx = IntroFx(season = IntroSeason.SNOW, scale = 1.4f), level = level)
        for ((i, f) in frames.withIndex()) {
            assertEquals("$level: grey pixels at ${times[i]} ms", 0, f.count(0, 0, f.width, f.height) { r, g, b -> grey(r, g, b) })
            assertEquals("$level: light grey at ${times[i]} ms ${f.where { r, g, b -> lightGrey(r, g, b) }}", 0, f.count(0, 0, f.width, f.height) { r, g, b -> lightGrey(r, g, b) })
        }
    }

    @Test fun snowNeverTurnsAnythingGrey() = snowStaysClear(EffectLevel.FULL)
    @Test fun snowNeverTurnsAnythingGreyAtSaver() = snowStaysClear(EffectLevel.SAVER)

    @Test fun sparklesNeverTurnGrey() {
        // Aqua sparkles by the line: a white star in a white glow with only a faint aqua tint (aqua at a third over the blush is grey).
        val times = LongArray(12) { 950L + 60L * it }
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), *times, fx = IntroFx(petals = false, scale = 1.4f))
        for ((i, f) in frames.withIndex()) assertEquals("light grey at ${times[i]} ms", 0, f.count(0, 0, f.width, f.height) { r, g, b -> lightGrey(r, g, b) })
    }

    @Test fun theWordsAllStopTogether() {
        // Once the name is whole the caption is typed too: nothing in the words moves through the hold (the cursor is on in both).
        val (a, b) = play(Intro(enabled = true, name = "小雨", seconds = 3f), 1390, 1640)
        var diff = 0
        for (y in 990 until 1700) for (x in 0 until 824) if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
        assertEquals(0, diff)
    }

    @Test fun petalsNeverCoverTheName() {
        // Plenty of petals (pink and white) drift past while the name is read: none shows over its rows.
        val times = LongArray(12) { 1300L + 30L * it }
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f), *times, fx = IntroFx(scale = 1.4f))
        fun petal(r: Int, g: Int, b: Int) = (abs(r - 0xDC) <= 10 && abs(g - 0x9F) <= 10 && abs(b - 0xB4) <= 10) || (r >= 253 && g >= 252 && b >= 252)
        for ((i, f) in frames.withIndex()) assertEquals("petal pixels over the name at ${times[i]} ms", 0, f.count(0, 1090, 824, 1240) { r, g, b -> petal(r, g, b) })
        // They still drift elsewhere.
        assertTrue(frames.sumOf { f -> f.count(0, 0, 824, 900) { r, g, b -> petal(r, g, b) } } > 0)
    }
}
