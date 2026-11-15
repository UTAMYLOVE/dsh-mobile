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
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 「星屑迎接」 ([IntroStyle.STARDUST]): its beats (moves keep their length, holds stretch), the portrait's pop, the greeting's
 * rhythm, the camera that settles on the home screen's crop, the particle counts per effect level and the layout; then drawn for
 * real (native graphics, PixelCopy): no grey over the blush sky, the welcome in ink inside white, the portrait framing the
 * picture's face, and the last frame before the iris being exactly the home screen's picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroStardustTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- the beats ----

    @Test fun movesKeepTheirLengthAndOnlyTheHoldsStretch() {
        val t3 = StardustTimeline.of(3000f)
        val t8 = StardustTimeline.of(8000f)
        for (t in listOf(t3, t8)) {
            assertEquals(1f, t.pace, 0f)
            assertEquals(280f, t.plan.length(StardustTimeline.ARRIVE), .01f)
            assertEquals(340f, t.plan.length(StardustTimeline.GREET), .01f)
            assertEquals(340f, t.plan.length(StardustTimeline.NAME), .01f)
            assertEquals(360f, t.plan.length(StardustTimeline.SETTLE), .01f)
            assertEquals(300f, t.plan.length(StardustTimeline.EXIT), .01f)
            assertEquals(t.total, t.plan.end(StardustTimeline.EXIT), .01f)
            // The pop, the greeting and the name come in exactly as fast at 8 s as at 3 s...
            assertEquals(280f, t.hit, .01f)
            assertEquals(560f, t.popMs, .01f)
            assertEquals(620f, t.greetMs, .01f)
        }
        // ...and 8 s only waits longer: the welcome holds, and the name follows the greeting a little later.
        assertTrue(t8.plan.length(StardustTimeline.HOLD) > t3.plan.length(StardustTimeline.HOLD) + 4000f)
        assertTrue(t8.nameStart > t3.nameStart)
        assertTrue(t8.nameStart - t3.nameStart < 1000f)
    }

    @Test fun theShortestRunTightensTheMovesAndStillSettlesBeforeTheIris() {
        val t = StardustTimeline.of(1500f)
        assertEquals(.80f, t.pace, .01f)
        assertEquals(1500f, t.plan.end(StardustTimeline.EXIT), .01f)
        assertEquals(200f, t.plan.length(StardustTimeline.HOLD), .01f)
        assertTrue(t.compact)
        // No time to read the status line at 1.5 s; at 3 s there is.
        assertTrue(!t.showsStatus)
        assertTrue(StardustTimeline.of(3000f).showsStatus)
        // The name has stamped in (two glyphs: 40 ms apart, 420 ms each, × pace) before the settle starts.
        assertTrue(t.nameStart + (40f + 420f) * t.pace <= t.settle)
    }

    @Test fun thePortraitPopsPastFullSizeAtTheHitAndRests() {
        val t = StardustTimeline.of(3000f)
        assertEquals(0f, t.popScale(t.popStart - 1f), 0f)
        assertEquals(1.10f, t.popScale(t.hit), .001f)
        var most = 0f
        var ms = t.popStart
        while (ms < t.popStart + t.popMs) { most = maxOf(most, t.popScale(ms)); ms += 4f }
        assertTrue("never bigger than 1.10: $most", most <= 1.1001f)
        assertTrue(t.popScale(t.hit + 100f) < 1.10f)
        assertEquals(1f, t.popScale(t.popStart + t.popMs + 1f), 0f)
    }

    @Test fun theGreetingRisesEightyMsApartAndALongOneStillWithinAThird() {
        val t = StardustTimeline.of(3000f)
        assertEquals(t.greetStart, t.greetAt(0, 4), 0f)
        assertEquals(80f, t.greetAt(1, 4) - t.greetAt(0, 4), .01f)
        assertEquals(240f, t.greetAt(3, 4) - t.greetAt(0, 4), .01f)
        // A 24-character greeting does not trickle in for two seconds: its last character starts within 360 ms.
        assertEquals(360f, t.greetAt(23, 24) - t.greetStart, .01f)
        // The welcome is announced as the name comes in, well before 2.5 s of a 3 s run.
        assertTrue(t.announce < 2500f)
    }

    @Test fun theCameraPushesInThenSettlesExactlyOnTheHomeCrop() {
        val t = StardustTimeline.of(3000f)
        val early = t.zoom(200f, 0f, reduced = false)
        val late = t.zoom(t.settle - 1f, 0f, reduced = false)
        assertTrue("it starts a little in: $early", early > 1f)
        assertTrue("and pushes further in: $early → $late", late > early + .03f)
        // Handed over (the settle done, or a tap's iris), it is the home screen's crop: zoom 1.
        assertEquals(1f, t.zoom(t.exit, t.handK(t.exit, 0f), reduced = false), 1e-4f)
        assertEquals(1f, t.zoom(1000f, t.handK(1000f, 1f), reduced = false), 0f)
        assertEquals(1f, t.zoom(1000f, 0f, reduced = true), 0f)
        // The stage has left and the type has faded by then; the bloom peaks once, early in the settle.
        assertEquals(1f, t.stageOut(t.exit), 0f)
        assertEquals(1f, t.textOut(t.exit), 0f)
        assertEquals(StardustTimeline.BLOOM, t.bloom(t.settle + .22f * 380f), .001f)
        assertEquals(0f, t.bloom(t.settle - 1f), 0f)
    }

    @Test fun theStillFrameIsTheSettledWelcome() {
        for (seconds in listOf(1.5f, 3f, 8f)) {
            val t = StardustTimeline.of(seconds * 1000f)
            assertTrue(t.still >= t.holdStart)
            assertTrue(t.still < t.settle)
            assertEquals(0f, t.stageOut(t.still), 0f)
        }
    }

    @Test fun theGreetingLosesATrailingCommaSinceTheNameFollows() {
        assertEquals("歡迎回來", stardustWords("", "歡迎回來"))
        assertEquals("歡迎回來", stardustWords("  ", "歡迎回來"))
        assertEquals("早安", stardustWords("早安，", "歡迎回來"))
        assertEquals("Hello", stardustWords("Hello!", "歡迎回來"))
        assertEquals("歡迎回來", stardustWords("，", "歡迎回來"))
    }

    @Test fun particleCountsFollowTheEffectLevel() {
        val fx = IntroFx()
        val full = StardustCounts.of(EffectLevel.FULL, fx, false)
        val balanced = StardustCounts.of(EffectLevel.BALANCED, fx, false)
        val saver = StardustCounts.of(EffectLevel.SAVER, fx, false)
        assertTrue(full.total > balanced.total && balanced.total > saver.total)
        assertTrue(full.total <= 80)
        // The reduced-motion still has the saver's few things and no sparks (they only fly).
        val still = StardustCounts.of(EffectLevel.FULL, fx, true)
        assertEquals(0, still.sparks)
        assertEquals(saver.stars, still.stars)
        // 星光 off: no stars; 數量 0: no ambient things at all, but the portrait's sparks stay.
        assertEquals(0, StardustCounts.of(EffectLevel.FULL, IntroFx(sparkles = false), false).stars)
        val none = StardustCounts.of(EffectLevel.FULL, IntroFx(scale = 0f), false)
        assertEquals(0, none.stars + none.motes + none.petals + none.season)
        assertEquals(10, none.sparks)
    }

    @Test fun theLayoutKeepsThePortraitTheRingsAndTheTypeOnScreen() {
        val dp = 2f
        for ((w, h) in listOf(412f to 915f, 360f to 640f, 600f to 960f)) {
            val lay = StardustLayout(w * dp, h * dp, dp, 24f * dp, 16f * dp)
            assertTrue(!lay.wide)
            assertTrue("$w×$h: the outer ring fits across", lay.c.x - lay.ring2 > 0f && lay.c.x + lay.ring2 < lay.w)
            assertTrue("$w×$h: the outer ring is below the status bar", lay.c.y - lay.ring2 > lay.top)
            // The type starts under the outer ring, with room for the greeting and a big name above the bottom.
            assertTrue(lay.textTop(0f) > lay.c.y + lay.ring2)
            assertTrue("$w×$h", lay.textTop(0f) + 150f * dp * lay.s < lay.bottom - 60f * dp)
        }
        val wide = StardustLayout(915f * dp, 412f * dp, dp, 24f * dp, 0f)
        assertTrue(wide.wide)
        // On a wide screen the portrait stands on the left, the type's column on the right, apart.
        assertTrue(wide.c.x + wide.ring2 < wide.textX - wide.textW / 2f)
        assertTrue(wide.textX + wide.textW / 2f < wide.w)
        assertTrue(wide.c.y - wide.ring2 > wide.top && wide.c.y + wide.ring2 < wide.bottom)
    }

    // ---- drawn ----

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
        intro: Intro, vararg frames: Long, picture: ImageBitmap? = null, level: EffectLevel = EffectLevel.FULL, fx: IntroFx = IntroFx(),
    ): List<Bitmap> {
        rule.mainClock.autoAdvance = false
        rule.setContent { FogTheme(effectsFor(level, false)) { StartupIntro(intro, picture, false, fx = fx) {} } }
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

    /** Grey: the channels within 6 of each other and darker than near-white (what aqua over pink, or ink over white, turns into). */
    private fun grey(r: Int, g: Int, b: Int) = maxOf(r, g, b) - minOf(r, g, b) < 6 && .2126f * r + .7152f * g + .0722f * b < 235f

    /** Grey pixels outside the host's own 跳過 label at the bottom right (not this look's drawing). */
    private fun Bitmap.greys(): Int {
        val skipLeft = width - 300
        val skipTop = height - 130
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (x >= skipLeft && y >= skipTop) continue
            val c = getPixel(x, y)
            if (grey((c shr 16) and 255, (c shr 8) and 255, c and 255)) n++
        }
        return n
    }

    @Test fun noGreyOverTheBlushSkyFromThePopToTheSettle() {
        // The pop and its flash (280–450), sparks flying (600), the welcome (1300, 1900), the settle (2450, 2600).
        val frames = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 300, 450, 700, 1300, 1900, 2450, 2600)
        frames.forEachIndexed { i, f -> assertEquals("grey pixels in frame $i", 0, f.greys()) }
    }

    @Test fun inWinterTheSnowLeavesNoGreyEither() {
        val frames = play(
            Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 900, 1900,
            fx = IntroFx(season = IntroSeason.SNOW),
        )
        frames.forEachIndexed { i, f -> assertEquals("grey pixels in frame $i", 0, f.greys()) }
    }

    @Test fun theWelcomeIsInkInsideWhite() {
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 1900).single()
        // The type's band: under the outer ring (ring bottom ≈ 0.335 × 915 + 106 dp) down to about 640 dp, at xhdpi × 2.
        val top = ((.335f * 915f + 106f) * 2f).toInt()
        val bottom = 640 * 2
        val ink = frame.count(0, top, frame.width, bottom) { r, g, b -> abs(r - 0x5E) <= 6 && abs(g - 0x44) <= 6 && abs(b - 0x45) <= 6 }
        val white = frame.count(0, top, frame.width, bottom) { r, g, b -> r >= 252 && g >= 250 && b >= 250 }
        assertTrue("ink pixels: $ink", ink > 4000)
        assertTrue("white outline pixels: $white", white > 2000)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
    }

    @Test fun thePortraitFramesThePicturesFace() {
        // An invented portrait picture: blush, with a green dot where a face would be (50 %, 35 %).
        val bmp = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 800) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 280) * (y - 280) <= 12 * 12) setPixel(x, y, 0xFF00C800.toInt())
        }
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 2100, picture = bmp.asImageBitmap()).single()
        // Green in the portrait only (the full-screen backdrop's own dot is blended with the blush at 80 %, never this pure).
        var sx = 0L; var sy = 0L; var n = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val c = frame.getPixel(x, y)
            if ((c shr 16) and 255 < 40 && (c shr 8) and 255 > 170 && c and 255 < 40) { sx += x; sy += y; n++ }
        }
        assertTrue("the dot shows in the portrait: $n", n > 20)
        // The portrait's centre: (206, 0.335 × 915) dp, within 6 dp (it bobs ±2 dp).
        val dx = sx / n.toFloat() / 2f - 206f
        val dy = sy / n.toFloat() / 2f - .335f * 915f
        assertTrue("the face lands ${hypot(dx, dy)} dp from the portrait's centre", hypot(dx, dy) <= 6f)
    }

    @Test fun justBeforeTheIrisTheBackdropIsTheHomeScreensPicture() {
        // An invented picture, pink on the left half and aqua on the right. The home screen draws it cropped (ContentScale.Crop,
        // centred) at the 透明度 setting (80) over the blush: the settled backdrop must be exactly that, with nothing left on it.
        val bmp = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 800) for (x in 0 until 400) setPixel(x, y, if (x < 200) 0xFFDC9FB4.toInt() else 0xFFA5DEE4.toInt())
        }
        val t = StardustTimeline.of(3000f)
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), t.exit.toLong() - 4, picture = bmp.asImageBitmap()).single()
        fun expect(c: Int, bg: Int) = (.8f * c + .2f * bg)
        val y = frame.height / 2
        for ((x, rgb) in listOf(200 to Triple(0xDC, 0x9F, 0xB4), 620 to Triple(0xA5, 0xDE, 0xE4))) {
            val c = frame.getPixel(x, y)
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            assertEquals("red at $x", expect(rgb.first, 0xFE), r.toFloat(), 4f)
            assertEquals("green at $x", expect(rgb.second, 0xDF), g.toFloat(), 4f)
            assertEquals("blue at $x", expect(rgb.third, 0xE1), b.toFloat(), 4f)
        }
        // And the middle band is clean: no type, rings or glow left anywhere across it.
        val off = frame.count(0, y - 200, frame.width, y + 200) { r, g, b ->
            val pink = abs(r - expect(0xDC, 0xFE)) <= 6 && abs(g - expect(0x9F, 0xDF)) <= 6 && abs(b - expect(0xB4, 0xE1)) <= 6
            val aqua = abs(r - expect(0xA5, 0xFE)) <= 6 && abs(g - expect(0xDE, 0xDF)) <= 6 && abs(b - expect(0xE4, 0xE1)) <= 6
            !pink && !aqua
        }
        // Only the seam between the halves may differ (a pixel or two wide).
        assertTrue("pixels off the home picture: $off", off <= 400 * 4)
    }

    @Test fun onSaverWithSakuraItStillPlays() {
        play(
            Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 300, 1300, 2600,
            level = EffectLevel.SAVER, fx = IntroFx(season = IntroSeason.SAKURA),
        ).forEach { assertTrue(it.width > 0) }
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test fun aSmallPhoneWithALongNameRendersEveryBeat() {
        play(Intro(enabled = true, name = "星野夢美子桜庭ひなた🌸", seconds = 3f, style = IntroStyle.STARDUST), 300, 900, 1500, 2600)
            .forEach { assertTrue(it.width > 0) }
    }

    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun landscapeWithoutANameRendersEveryBeat() {
        val frames = play(Intro(enabled = true, name = "", seconds = 1.5f, style = IntroStyle.STARDUST), 300, 700, 1100)
        frames.forEach { assertTrue(it.width > 0) }
        rule.onNodeWithText("歡迎回來").assertExists()
    }
}
