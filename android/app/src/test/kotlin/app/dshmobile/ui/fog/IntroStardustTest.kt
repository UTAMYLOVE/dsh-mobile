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
import androidx.compose.runtime.CompositionLocalProvider
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
 * 「星屑迎接」 ([IntroStyle.STARDUST]): its beats (each at the same share of the run, moves stretching at most 1.6×, middle beats
 * from 4 s), the portrait's pop, the exit's bloom, light iris and the host's last cut, the camera that settles on the home
 * screen's crop, the group's glide as the pill comes, the particle counts per effect level and the layout; then drawn for real
 * (native graphics, PixelCopy): no grey over the blush sky, the welcome in ink inside white, the status line in the lighter
 * brown, the portrait framing the picture's face, frosted white glass behind it that comes with the pop, the sky held still
 * through the hit's nudge, a wide screen keeping the face behind the portrait, and the light iris opening onto the home crop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroStardustTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- the beats ----

    @Test fun everyBeatSitsAtTheSameShareOfTheRun() {
        val t15 = StardustTimeline.of(1500f)
        val t3 = StardustTimeline.of(3000f)
        val t8 = StardustTimeline.of(8000f)
        // 1.5 s is the 3 s run in half the time: the same shares, the exit iris at 77 %.
        for (t in listOf(t15, t3)) {
            assertEquals(.77f, t.exit / t.total, .001f)
            assertEquals(.10f, t.hit / t.total, .001f)
            assertEquals(.16f, t.greetStart / t.total, .001f)
            assertEquals(.30f, t.nameStart / t.total, .001f)
            assertEquals(.40f, t.lineStart / t.total, .001f)
        }
        // 8 s tells the same story spread over the whole run: the greeting is not in by 0.6 s and the name not by 1.2 s.
        assertEquals(.15f, t8.greetStart / t8.total, .001f)
        assertEquals(.28f, t8.nameStart / t8.total, .001f)
        assertEquals(.35f, t8.lineStart / t8.total, .001f)
        assertEquals(.44f, t8.statusIn / t8.total, .001f)
        assertTrue(t8.greetStart > 1000f && t8.nameStart > 2000f)
        // The beats come in the same order at every length.
        for (t in listOf(t15, t3, StardustTimeline.of(5000f), t8)) {
            assertTrue(t.popStart >= 0f)
            assertTrue(t.hit < t.greetStart && t.greetStart < t.nameStart && t.nameStart < t.lineStart && t.lineStart < t.statusIn)
            assertTrue(t.statusIn < t.bloomStart && t.bloomStart < t.exit && t.exit < t.hand && t.hand < t.total)
        }
    }

    @Test fun movesStretchWithTheRunButAtMostOnePointSixFold() {
        val t3 = StardustTimeline.of(3000f)
        val t8 = StardustTimeline.of(8000f)
        assertEquals(1f, t3.str, 0f)
        assertEquals(1.6f, t8.str, 0f)
        assertEquals(.6f, StardustTimeline.of(1500f).str, 0f)
        assertEquals(620f * 1.6f, t8.greetMs, .01f)
        assertEquals(520f * 1.6f, t8.lineMs, .01f)
        // The impacts stretch even less, and the 8 s iris is 1.6 x the 3 s one, not 2.7 x: it opens at 86 % there.
        assertTrue(t8.popMs <= 1.15f * t3.popMs + .01f)
        assertEquals(1.6f * t3.exitMs, t8.exitMs, .01f)
        assertEquals(.862f, t8.exit / t8.total, .001f)
    }

    @Test fun aLongRunAddsMiddleBeatsAndTheBarIsFullWhenTheExitStarts() {
        val t3 = StardustTimeline.of(3000f)
        val t8 = StardustTimeline.of(8000f)
        // From 4 s: an echo of the pop at 50 %, the name's sheen at 55 %, a wave of twinkles at 62 % and a pulse of shooting
        // stars and sparks at 70 %; at 3 s none but the sheen.
        assertEquals(.50f, t8.echo / t8.total, .001f)
        assertEquals(.55f, t8.sheen / t8.total, .001f)
        assertEquals(.62f, t8.wave / t8.total, .001f)
        assertEquals(.70f, t8.pulse / t8.total, .001f)
        assertTrue(t8.statusIn < t8.echo && t8.echo < t8.sheen && t8.sheen < t8.wave && t8.wave < t8.pulse)
        assertTrue(t8.wave + t8.waveMs < t8.bloomStart)
        assertEquals(2, t8.streaks.count { it >= t8.pulse })
        assertEquals(-1f, t3.pulse, 0f)
        assertEquals(-1f, t3.echo, 0f)
        assertEquals(-1f, t3.wave, 0f)
        assertTrue(t3.sheen > 0f && t3.sheen + t3.sheenMs <= t3.bloomStart)
        // No sheen at 1.5 s: it would run into the bloom.
        assertEquals(-1f, StardustTimeline.of(1500f).sheen, 0f)
        // Every shooting star has landed before the exit.
        for (t in listOf(t3, t8)) for (at in t.streaks) assertTrue(at + t.streakMs <= t.exit)
        // The bar: empty when the pill arrives, still filling at 75 % of an 8 s run, full exactly when the exit starts.
        for (t in listOf(t3, t8)) {
            assertEquals(0f, t.barFill(t.statusIn), 0f)
            assertTrue(t.barFill(t.exit - 50f) < 1f)
            assertEquals(1f, t.barFill(t.exit), 0f)
        }
        assertTrue("at 6 s: ${t8.barFill(6000f)}", t8.barFill(6000f) < .9f)
        // No time to read the status line at 1.5 s; at 3 s there is.
        assertTrue(!StardustTimeline.of(1500f).showsStatus)
        assertTrue(t3.showsStatus)
    }

    @Test fun thePortraitPopsFromPointSixAndIsLargestOnTheHitsFrame() {
        for (seconds in listOf(1.5f, 3f, 8f)) {
            val t = StardustTimeline.of(seconds * 1000f)
            assertEquals(0f, t.popScale(t.popStart - 1f), 0f)
            assertEquals(StardustTimeline.POP_FROM, t.popScale(t.popStart), .001f)
            var most = 0f
            var at = 0f
            var ms = t.popStart
            while (ms <= t.popStart + t.popMs) {
                if (t.popScale(ms) > most) { most = t.popScale(ms); at = ms }
                ms += 2f
            }
            assertEquals("$seconds s: the peak is on the hit", t.hit, at, 6f)
            assertTrue("an overshoot to 1.08: $most", most in 1.07f..1.09f)
            assertEquals(1f, t.popScale(t.popStart + t.popMs + 1f), 0f)
            // Whole well before the flash: the flash rings round a face, never over a ghost.
            assertEquals(1f, t.popAlpha(t.hit - 1f), 0f)
            assertTrue(t.popAlpha(t.popStart + 10f) < .5f)
        }
        // At 3 s the pop peaks at 300 ms, and at 150 ms the portrait is still small (no fade-in before the pop).
        val t3 = StardustTimeline.of(3000f)
        assertEquals(300f, t3.hit, .01f)
        assertTrue(t3.popScale(150f) < .9f)
        assertTrue(t3.ringsIn < t3.hit && t3.ringsIn > t3.popStart)
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

    @Test fun theExitIsALightIrisFromThePortraitThenTheHostsQuickCut() {
        val t = StardustTimeline.of(3000f)
        // The bloom rises over 180 ms to the exit; the light iris opens from it over 510 ms; the host's cut takes the last 180.
        assertEquals(0f, t.bloom(t.bloomStart - 1f), 0f)
        assertEquals(180f, t.bloomMs, .01f)
        assertEquals(1f, t.bloom(t.exit), .001f)
        assertEquals(180f, t.handMs, .01f)
        assertEquals(510f, t.liftMs, .01f)
        assertEquals(t.total - t.handMs, t.hand, .01f)
        assertEquals(0f, t.lift(t.exit), 0f)
        assertEquals(1f, t.lift(t.hand), 0f)
        assertTrue("at 2.76 s the light iris is nearly open: ${t.lift(2760f)}", t.lift(2760f) > .9f)
        // A short run's cut is shorter, and never more than .4 of the exit; 8 s keeps the 3 s pace (at most 1.15 x).
        val t15 = StardustTimeline.of(1500f)
        assertTrue(t15.handMs <= .4f * t15.exitMs && t15.liftMs > 0f)
        assertTrue(StardustTimeline.of(8000f).handMs <= 1.15f * t.handMs + .01f)
        // A tap before the bloom gets a quick one from the tap.
        assertTrue(t.bloom(1060f, exitAt = 1000f) > .9f)
        assertEquals(0f, t.bloom(1300f, exitAt = 1000f), 0f)
        // The words fade over 150 ms (x the stretch) from the exit, or from a tap before it; not before either.
        assertEquals(150f, t.textFadeMs, .01f)
        assertEquals(1f, t.wordsFade(t.exit), 0f)
        assertEquals(0f, t.wordsFade(t.exit + t.textFadeMs), 0f)
        assertEquals(1f, t.wordsFade(999f, exitAt = 1000f), 0f)
        assertEquals(0f, t.wordsFade(1000f + t.textFadeMs, exitAt = 1000f), 0f)
        // A tap during the light iris changes nothing for them: they are already going from the exit.
        assertEquals(t.wordsFade(t.exit + 100f), t.wordsFade(t.exit + 100f, exitAt = t.exit + 50f), 0f)
    }

    @Test fun theGroupStandsLowerUntilThePillComesThenGlidesUp() {
        val t3 = StardustTimeline.of(3000f)
        val rest = 88f
        // No pill (a 1.5 s run): lower the whole time.
        assertEquals(rest, stardustShift(500f, StardustTimeline.of(1500f), showsStatus = false, rest = rest, reduced = false), 0f)
        // With the pill: lower until it comes, then up, settled once it is in.
        assertEquals(rest, stardustShift(t3.statusIn - 200f, t3, true, rest, false), 0f)
        assertEquals(0f, stardustShift(t3.statusIn + 600f, t3, true, rest, false), 0f)
        assertTrue(stardustShift(t3.statusIn + 150f, t3, true, rest, false) in 1f..rest - 1f)
        // The still has the pill, so it stands where it settles.
        assertEquals(0f, stardustShift(0f, t3, true, rest, reduced = true), 0f)
    }

    @Test fun theCameraPushesInThenSettlesOnTheHomeCropBeforeTheLightIris() {
        val t = StardustTimeline.of(3000f)
        val early = t.zoom(200f, 0f, reduced = false)
        val late = t.zoom(t.exit - 1f, 0f, reduced = false)
        assertTrue("it starts a little in: $early", early > 1f)
        assertTrue("and pushes further in: $early → $late", late > early + .03f)
        // It settles before the light iris opens (so the picture inside and outside the iris is the same): zoom 1 at the exit.
        assertEquals(0f, t.settle(t.exit - StardustTimeline.SETTLE_MS - 1f), 0f)
        assertEquals(1f, t.settle(t.exit), 1e-5f)
        assertEquals(1f, t.zoom(t.exit, t.settle(t.exit), reduced = false), 1e-4f)
        assertEquals(1f, t.zoom(1000f, 0f, reduced = true), 0f)
        // Upright the push holds the face, then the home's own focus.
        val start = stardustFocus(wide = false, faceY = .29f, homeY = .5f, hk = 0f)
        assertEquals(.29f, start.fy, 0f)
        assertEquals(start.fy, start.ty, 0f)
        val upright = stardustFocus(wide = false, faceY = .29f, homeY = .5f, hk = 1f)
        assertEquals(.5f, upright.fy, 0f)
        assertEquals(.5f, upright.ty, 0f)
        // On a wide screen the face sits behind the portrait (29 % across), then the home crop.
        val wide = stardustFocus(wide = true, faceY = .29f, homeY = .5f, hk = 0f, portX = .29f, portY = .5f)
        assertEquals(.29f, wide.fy, 0f)
        assertEquals(.29f, wide.tx, 0f)
        assertEquals(.5f, wide.ty, 0f)
        val home = stardustFocus(wide = true, faceY = .29f, homeY = .5f, hk = 1f, portX = .29f, portY = .5f)
        assertEquals(.5f, home.tx, 0f)
        assertEquals(.5f, home.fy, 0f)
        // For that a tall picture on a wide screen is zoomed in about 1.42 x (the face can then sit at 29 % with the screen
        // covered); a picture that already has the room is not.
        assertEquals(1.42f, stardustWideZoom(945f, 2048f, 1830f, 824f, .5f, .29f, .29f, .5f), .01f)
        assertEquals(1f, stardustWideZoom(945f, 2048f, 824f, 1830f, .5f, .29f, .5f, .29f), 1e-4f)
    }

    @Test fun theStillFrameIsTheSettledWelcome() {
        for (seconds in listOf(1.5f, 3f, 8f)) {
            val t = StardustTimeline.of(seconds * 1000f)
            assertTrue(t.still >= t.lineStart)
            assertTrue(t.still < t.bloomStart)
            assertEquals(0f, t.bloom(t.still), 0f)
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
        assertTrue(full.total <= 100)
        // The reduced-motion still has the saver's few things and no sparks (they only fly).
        val still = StardustCounts.of(EffectLevel.FULL, fx, true)
        assertEquals(0, still.sparks)
        assertEquals(saver.stars, still.stars)
        // 星光 off: no stars; 數量 0: no ambient things at all, but the portrait's sparks stay.
        assertEquals(0, StardustCounts.of(EffectLevel.FULL, IntroFx(sparkles = false), false).stars)
        val none = StardustCounts.of(EffectLevel.FULL, IntroFx(scale = 0f), false)
        assertEquals(0, none.stars + none.top + none.twinkles + none.motes + none.petals + none.season + none.bigs)
        assertEquals(10, none.sparks)
        // The seasons are plenty, so each reads in one frame, with a few big soft ones in front; none on SAVER.
        val snow = StardustCounts.of(EffectLevel.FULL, IntroFx(season = IntroSeason.SNOW), false)
        assertEquals(48, snow.season)
        assertEquals(3, snow.bigs)
        val spring = StardustCounts.of(EffectLevel.FULL, IntroFx(season = IntroSeason.SAKURA), false)
        assertEquals(36, spring.season)
        assertEquals(0, StardustCounts.of(EffectLevel.FULL, IntroFx(season = IntroSeason.SAKURA, petals = false), false).season)
        assertEquals(0, StardustCounts.of(EffectLevel.SAVER, IntroFx(season = IntroSeason.SNOW), false).bigs)
        assertTrue(StardustCounts.of(EffectLevel.SAVER, IntroFx(season = IntroSeason.SNOW), false).season < snow.season)
        assertEquals(0, full.season + full.bigs)
    }

    @Test fun theStackSitsLowerWithTheGreetingGroupedWithTheName() {
        val dp = 2f
        for ((w, h) in listOf(412f to 915f, 360f to 640f, 600f to 960f)) {
            val lay = StardustLayout(w * dp, h * dp, dp, 24f * dp, 16f * dp)
            assertTrue(!lay.wide)
            assertTrue("$w×$h: the outer ring fits across", lay.c.x - lay.ring2 > 0f && lay.c.x + lay.ring2 < lay.w)
            assertTrue("$w×$h: the outer ring is below the status bar", lay.c.y - lay.ring2 > lay.top)
            // The name's baseline at 60 % and the pill at 75 % of the usable height.
            assertEquals(.60f, (lay.nameBase - lay.top) / lay.usable, .001f)
            assertEquals(.75f, (lay.pillMid - lay.top) / lay.usable, .001f)
            // From the outer ring to the greeting's ink is gap1; from the greeting's ink to the name's .6 of it.
            val greetInkTop = lay.greetInkBottom - (StardustLayout.INK_TOP + StardustLayout.INK_BOTTOM) * lay.greetSp * lay.s * dp
            assertEquals(lay.gap1, greetInkTop - (lay.c.y + lay.ring2), .5f)
            assertEquals(.6f * lay.gap1, lay.nameBase - StardustLayout.NAME_INK * lay.nameSp * lay.s * dp - lay.greetInkBottom, .5f)
        }
        // No name: the greeting alone sits a little higher, still under the rings.
        val alone = StardustLayout(412f * dp, 915f * dp, dp, 24f * dp, 16f * dp, hasName = false)
        assertTrue(alone.greetInkBottom < StardustLayout(412f * dp, 915f * dp, dp, 24f * dp, 16f * dp).nameBase)
        assertTrue(alone.c.y + alone.ring2 < alone.greetInkBottom - 30f * dp)
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
        look: Look = Look(),
    ): List<Bitmap> {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FogTheme(effectsFor(level, false)) { CompositionLocalProvider(LocalLook provides look) { StartupIntro(intro, picture, false, fx = fx) {} } }
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

    /** The phone's layout as the scene builds it (Robolectric's window has no insets). */
    private val phone = StardustLayout(824f, 1830f, 2f, 0f, 0f)

    @Test fun noGreyOverTheBlushSkyFromThePopToTheIris() {
        // The pop and its flash (240–450), sparks flying (700), the welcome (1300, 1900), the bloom (2200), the words going (2380)
        // and the light iris (2450, 2600). In the 粉嫩 style: the light iris opens onto the home's own glows, and the cosy
        // style's aqua and milk-tea glows are the home's (greyish by design), not this look's.
        val frames = play(
            Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 240, 300, 450, 700, 1300, 1900, 2200, 2380, 2450, 2600,
            look = Look(style = BackdropStyle.KAWAII),
        )
        frames.forEachIndexed { i, f -> assertEquals("grey pixels in frame $i", 0, f.greys()) }
    }

    @Test fun inWinterTheSnowLeavesNoGreyEither() {
        val frames = play(
            Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 300, 900, 1900, 2450,
            fx = IntroFx(season = IntroSeason.SNOW),
        )
        frames.forEachIndexed { i, f -> assertEquals("grey pixels in frame $i", 0, f.greys()) }
    }

    @Test fun inSpringThePetalsLeaveNoGrey() {
        val frames = play(
            Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 450, 1300, 1900,
            fx = IntroFx(season = IntroSeason.SAKURA),
        )
        frames.forEachIndexed { i, f -> assertEquals("grey pixels in frame $i", 0, f.greys()) }
    }

    @Test fun theWelcomeIsInkInsideWhite() {
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 1900).single()
        // The type's band: under the outer ring down to just under the name's baseline.
        val top = (phone.c.y + phone.ring2).toInt()
        val bottom = (phone.nameBase + 40f).toInt()
        val ink = frame.count(0, top, frame.width, bottom) { r, g, b -> abs(r - 0x5E) <= 6 && abs(g - 0x44) <= 6 && abs(b - 0x45) <= 6 }
        val white = frame.count(0, top, frame.width, bottom) { r, g, b -> r >= 252 && g >= 250 && b >= 250 }
        assertTrue("ink pixels: $ink", ink > 4000)
        assertTrue("white outline pixels: $white", white > 2000)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
    }

    @Test fun theStatusLineIsInTheLighterBrownAndLargeEnoughToRead() {
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 1900).single()
        // Around the pill at 75 %: the handwriting in #7D5F60, thickened (a hairline would leave a few dozen pixels).
        val y = phone.pillMid.toInt()
        val ink = frame.count(0, y - 60, frame.width, y + 60) { r, g, b -> abs(r - 0x7D) <= 8 && abs(g - 0x5F) <= 8 && abs(b - 0x60) <= 8 }
        assertTrue("status ink pixels: $ink", ink > 400)
    }

    @Test fun theFrostedDiscBehindThePortraitComesWithThePopAndReadsAsWhiteGlass() {
        // An invented busy picture: dark ink-brown and pink squares, the kind that blurred into a muddy smudge.
        val bmp = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 800) for (x in 0 until 500) setPixel(x, y, if ((x / 20 + y / 20) % 2 == 0) 0xFF5E4445.toInt() else 0xFFDC9FB4.toInt())
        }
        val t = StardustTimeline.of(8000f)
        val frames = play(
            Intro(enabled = true, name = "小雨", seconds = 8f, style = IntroStyle.STARDUST), t.popStart.toLong() - 30, t.hit.toLong() + 900,
            picture = bmp.asImageBitmap(),
        )
        // The band between the portrait's rim and the inner orbit ring (the group stands 44 dp lower before the pill).
        val lay = StardustLayout(824f, 1830f, 2f, 0f, 0f)
        val cy = lay.c.y + StardustScene.SHIFT_DP * 2f * lay.s
        fun lum(f: Bitmap): Float {
            var sum = 0f
            var n = 0
            for (k in 0 until 72) {
                val a = k * Math.PI / 36
                for (d in listOf(lay.r + lay.rim + 12f, (lay.ring1 + lay.r + lay.rim) / 2f)) {
                    val c = f.getPixel((lay.c.x + Math.cos(a) * d).toInt(), (cy + Math.sin(a) * d).toInt())
                    sum += .2126f * ((c shr 16) and 255) + .7152f * ((c shr 8) and 255) + .0722f * (c and 255)
                    n++
                }
            }
            return sum / n
        }
        val before = lum(frames[0])
        val after = lum(frames[1])
        assertTrue("with the pop the disc is light: $before → $after", after > before + 20f && after > 215f)
    }

    @Test fun thePortraitFramesThePicturesFace() {
        // An invented portrait picture: blush, with a green dot where a face would be (50 %, 29 %).
        val bmp = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 800) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 232) * (y - 232) <= 12 * 12) setPixel(x, y, 0xFF00C800.toInt())
        }
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 2000, picture = bmp.asImageBitmap()).single()
        // Green in the portrait only (the full-screen backdrop's own dot is blended with the blush and softened, never this pure).
        var sx = 0L; var sy = 0L; var n = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val c = frame.getPixel(x, y)
            if ((c shr 16) and 255 < 40 && (c shr 8) and 255 > 170 && c and 255 < 40) { sx += x; sy += y; n++ }
        }
        assertTrue("the dot shows in the portrait: $n", n > 20)
        // The portrait's centre, within 6 dp (it bobs ±2 dp).
        val dx = (sx / n.toFloat() - phone.c.x) / 2f
        val dy = (sy / n.toFloat() - phone.c.y) / 2f
        assertTrue("the face lands ${hypot(dx, dy)} dp from the portrait's centre", hypot(dx, dy) <= 6f)
    }

    @Test fun theSkyHoldsStillThroughTheHitsNudge() {
        // At the hit the host nudges the screen 2 dp down; the sky is held still, so no strip of what is underneath opens at the
        // top edge (it showed as a light band).
        val t = StardustTimeline.of(3000f)
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), t.hit.toLong() + 40).single()
        val mid = frame.width / 2
        val off = frame.count(mid - 120, 0, mid + 120, 6) { r, g, _ -> r - g < 20 }
        assertEquals("non-blush pixels along the top edge", 0, off)
    }

    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun landscapeKeepsTheFaceBehindThePortraitNotBesideTheWords() {
        // An invented tall picture: blush, a green face at 29 % of its height and a red band (the chest) at 52–60 %.
        val bmp = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 800) for (x in 0 until 500) {
                if ((x - 250) * (x - 250) + (y - 232) * (y - 232) <= 40 * 40) setPixel(x, y, 0xFF00C800.toInt())
                if (y in 416..480) setPixel(x, y, 0xFFFF0000.toInt())
            }
        }
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), 1500, picture = bmp.asImageBitmap()).single()
        // The face is behind the portrait on the left (where the frosted disc covers it): none of it from the middle rightwards,
        // where it showed as a second, giant face beside the words.
        // (Green, not the underline's aqua end.)
        val green = frame.count(frame.width * 45 / 100, 0, frame.width, frame.height) { r, g, b -> g > r + 30 && g > b + 30 }
        assertEquals("green right of the portrait", 0, green)
        val left = frame.count(0, 0, frame.width * 45 / 100, frame.height) { r, g, b -> g > r + 30 && g > b + 30 }
        assertTrue("the face is on the left: $left", left > 2000)
        val red = frame.count(0, 0, frame.width, frame.height) { r, g, b -> r > g + 80 && r > b + 80 }
        assertEquals("the chest is out of frame", 0, red)
    }

    @Test fun theLightIrisOpensOntoTheHomeScreensOwnPicture() {
        // A blush picture with a green dot at (50 %, 29 %): the home screen (ContentScale.Crop, centred, 80 %) shows it at
        // (412, 531) on this phone.
        val bmp = Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 800) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 232) * (y - 232) <= 12 * 12) setPixel(x, y, 0xFF00C800.toInt())
        }
        val t = StardustTimeline.of(3000f)
        val frame = play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.STARDUST), t.hand.toLong() - 20, picture = bmp.asImageBitmap()).single()
        var sx = 0L; var sy = 0L; var n = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val c = frame.getPixel(x, y)
            if ((c shr 8) and 255 > ((c shr 16) and 255) + 100) { sx += x; sy += y; n++ }
        }
        assertTrue("the dot shows: $n", n > 1000)
        assertEquals(412f, sx / n.toFloat(), 4f)
        assertEquals(531f, sy / n.toFloat(), 4f)
        // Nothing of the welcome is left: no ink (but the host's own 跳過 at the bottom right, which stays until its cut).
        val ink = frame.count(0, 0, frame.width, frame.height - 130) { r, g, b -> abs(r - 0x5E) <= 8 && abs(g - 0x44) <= 8 && abs(b - 0x45) <= 8 }
        assertEquals("ink left", 0, ink)
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
