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
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_BURST
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_CONFIRM
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_CRUISE
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_DASH
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_EXIT
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_LOGIN
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_READ
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_TUNNEL
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_VOID
import app.dshmobile.ui.fog.TunnelTimeline.Companion.B_WELCOME
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

/**
 * 「櫻花隧道」 ([IntroStyle.TUNNEL]): its beats (fixed moves, stretching holds, the 確認 panel left out of short runs), the
 * tunnel's speed and the rays' closed-form depth, the core that opens onto the picture, the one white burst, the counts per
 * effect level; then drawn for real (native graphics, PixelCopy): no grey from the void to the iris (aqua never under pink or
 * ink), the welcome in ink inside white, the picture hidden in the tunnel and shown through the opening core, the still without
 * a single ray, and the odd screens (landscape, a small phone with a long name, saver with petals off).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroTunnelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- the beats ----

    @Test fun movesKeepTheirLengthAndOnlyTheHoldsStretch() {
        val t3 = TunnelTimeline.of(3000f)
        val t8 = TunnelTimeline.of(8000f)
        assertEquals(1f, t3.pace, 0f)
        assertEquals(1f, t8.pace, 0f)
        // At 8 s every move is exactly as long as at 3 s: nothing slows down.
        for (b in listOf(B_VOID, B_TUNNEL, B_LOGIN, B_CONFIRM, B_DASH, B_BURST, B_EXIT)) {
            assertEquals("beat $b", t3.plan.length(b), t8.plan.length(b), .01f)
        }
        // The extra five seconds go to the cruise, the panels and above all the welcome.
        assertTrue(t8.plan.length(B_CRUISE) > 1200f)
        assertTrue(t8.plan.length(B_READ) > 500f)
        assertTrue(t8.plan.length(B_WELCOME) > 2000f)
        assertTrue(t8.plan.length(B_WELCOME) > t8.plan.length(B_CRUISE))
        // At 3 s the welcome still holds its minimum.
        assertEquals(TunnelTimeline.WELCOME_MIN, t3.plan.length(B_WELCOME), 1f)
        // Every length ends exactly on time.
        for (s in listOf(1500f, 2199f, 2200f, 3000f, 5000f, 8000f)) {
            val t = TunnelTimeline.of(s)
            assertEquals(s, t.total, .01f)
            assertEquals(s, t.plan.end(B_EXIT), .01f)
        }
    }

    @Test fun theBeatsComeInOrderAtEveryLength() {
        for (s in listOf(1500f, 2000f, 2200f, 3000f, 4500f, 8000f)) {
            val t = TunnelTimeline.of(s)
            assertTrue("$s", 0f < t.tunnelAt && t.tunnelAt < t.discAt(0))
            for (i in 1 until t.discs) assertTrue("$s disc $i", t.discAt(i) > t.discAt(i - 1))
            // The last disc is past the camera by the time the panel has wiped open.
            assertTrue("$s", t.discAt(t.discs - 1) + t.discMs <= t.loginAt + 160f * t.pace)
            assertTrue("$s", t.loginAt < t.typeAt && t.typeAt < t.confirmAt + .01f && t.confirmAt <= t.dashAt)
            assertTrue("$s", t.dashAt < t.hit && t.hit < t.greetAt && t.greetAt < t.nameAt && t.nameAt < t.still)
            assertTrue("$s", t.still < t.exit && t.exit < t.total)
        }
        // At 3 s the burst (and the announcement) comes well before 2.5 s, the welcome then holds 0.7 s before the iris.
        val t3 = TunnelTimeline.of(3000f)
        assertEquals(1880f, t3.hit, .01f)
        assertEquals(2620f, t3.exit, .01f)
        // On a long run the discs fly one at a time.
        val t8 = TunnelTimeline.of(8000f)
        assertEquals(420f, t8.discStep, .01f)
        assertTrue(t8.discStep < t8.discMs)
        assertEquals(80f, t3.discStep, .01f)
    }

    @Test fun shortRunsLeaveOutTheConfirmPanel() {
        val short = TunnelTimeline.of(1500f)
        assertTrue(short.compact)
        assertTrue(TunnelTimeline.of(2199f).compact)
        assertTrue(!TunnelTimeline.of(2200f).compact)
        assertEquals(0f, short.plan.length(B_CONFIRM), 0f)
        assertEquals(short.dashAt, short.confirmAt, .01f)
        assertEquals(3, short.discs)
        assertEquals(4, TunnelTimeline.of(3000f).discs)
        // The moves tighten together, about by half, and the name types in at most 220 ms × pace.
        assertTrue(short.pace in .45f..0.6f)
        assertEquals(70f * short.pace, short.typeStep(2), .01f)
        assertTrue(short.typeStep(24) * 24 <= 220f * short.pace + .1f)
    }

    @Test fun theTunnelBuildsSlowsBehindThePanelsAndDashes() {
        val t = TunnelTimeline.of(3000f)
        assertEquals(0f, t.speed(t.tunnelAt - 1f), 0f)
        assertEquals(0f, t.distance(t.tunnelAt - 8f), 1e-4f)
        // It gets up to cruise before the panel, calms down behind it, and is fastest at the very end of the dash.
        assertEquals(TunnelTimeline.CRUISE, t.speed(t.loginAt - 1f), .01f)
        assertEquals(TunnelTimeline.SLOW, t.speed(t.loginAt + 300f), .01f)
        assertTrue(t.speed(t.hit - 1f) > 40f)
        // Flown distance only ever grows.
        var last = -1f
        var ms = 0f
        while (ms <= t.total) {
            val d = t.distance(ms)
            assertTrue("at $ms", d >= last)
            last = d
            ms += 7f
        }
        // A short run flies the same tunnel faster rather than a shorter one.
        val short = TunnelTimeline.of(1500f)
        assertEquals(TunnelTimeline.CRUISE / short.pace, short.speed(short.loginAt - 1f), .01f)
    }

    @Test fun aRayGoesRoundFromFarToTheCameraAndBack() {
        val v = 1f
        val len = 2f
        val far = 8f
        val start = 5f
        assertEquals(start, tunnelRayDepth(0f, v, len, far, start), 1e-4f)
        assertEquals(0, tunnelRayCycle(0f, v, len, far, start))
        assertEquals(3f, tunnelRayDepth(2f, v, len, far, start), 1e-4f)
        // Its tail passes .12 at depth .12 − len: then it starts again far away, one round on.
        val round = far - (.12f - len)
        val wrap = round - (far - start) + 1e-3f
        assertEquals(.12f - len, tunnelRayDepth(wrap - 2e-3f, v, len, far, start), 1e-2f)
        assertEquals(0, tunnelRayCycle(wrap - 2e-3f, v, len, far, start))
        assertEquals(far, tunnelRayDepth(wrap, v, len, far, start), 1e-2f)
        assertEquals(1, tunnelRayCycle(wrap, v, len, far, start))
        var f = 0f
        while (f < 60f) {
            val z = tunnelRayDepth(f, v, len, far, start)
            assertTrue("$f: $z", z <= far + 1e-4f && z > .12f - len - 1e-4f)
            f += .37f
        }
    }

    @Test fun theCoreOpensOntoThePictureAndCoversTheScreenAtTheBurst() {
        val t = TunnelTimeline.of(3000f)
        val dp = 2f
        val cover = 1000f
        assertEquals(0f, t.coreRadius(t.tunnelAt - 61f, dp, cover), 0f)
        // A small white void through the tunnel and the panels.
        val panel = t.coreRadius(t.dashAt - 1f, dp, cover)
        assertTrue("$panel", panel in 20f * dp..32f * dp)
        assertEquals(cover, t.coreRadius(t.hit, dp, cover), 0f)
        assertEquals(cover, t.coreRadius(t.still, dp, cover), 0f)
        // It opens mostly at the end (the dash eases in).
        assertTrue(t.coreRadius(t.dashAt + .5f * (t.hit - t.dashAt), dp, cover) < .3f * cover)
        // The picture is hidden until the dash and fully there before the burst.
        assertEquals(0f, t.pictureK(t.dashAt), 0f)
        assertEquals(1f, t.pictureK(t.dashAt + .6f * (t.hit - t.dashAt)), 1e-4f)
    }

    @Test fun oneWhiteBurstAtTheHit() {
        for (s in listOf(1500f, 3000f, 8000f)) {
            val t = TunnelTimeline.of(s)
            assertEquals(0f, t.flash(t.hit - 51f * t.pace), 0f)
            assertEquals(TunnelTimeline.FLASH_PEAK, t.flash(t.hit), 1e-4f)
            assertEquals(0f, t.flash(t.hit + 421f * t.pace), 1e-4f)
            assertEquals(0f, t.flash(t.still), 0f)
            // The pink edges come in during the dash and are gone 120 ms after the burst.
            assertEquals(0f, t.vignette(t.dashAt), 0f)
            assertEquals(0f, t.vignette(t.dashAt + .39f * (t.hit - t.dashAt)), 0f)
            assertTrue(t.vignette(t.hit - 1f) > .6f)
            assertEquals(0f, t.vignette(t.hit + 121f * t.pace), 0f)
        }
    }

    @Test fun theWelcomeLightSettlesFainterOverAPicture() {
        val t = TunnelTimeline.of(3000f)
        assertEquals(0f, t.afterglow(t.hit - 1f, .25f), 0f)
        assertEquals(.25f, t.afterglow(t.still, .25f), 1e-4f)
        assertEquals(.75f, t.afterglow(t.still, .75f), 1e-4f)
        assertTrue(t.afterglow(t.hit + 170f, .25f) > .9f)
    }

    @Test fun theGreetingLosesTrailingPunctuation() {
        assertEquals("歡迎回來", tunnelGreet("", "歡迎回來"))
        assertEquals("早安", tunnelGreet(" 早安！", "歡迎回來"))
        assertEquals("Hi", tunnelGreet("Hi,", "歡迎回來"))
        assertEquals("歡迎回來", tunnelGreet("，", "歡迎回來"))
    }

    @Test fun countsFollowTheEffectLevel() {
        val fx = IntroFx()
        val full = TunnelCounts.of(EffectLevel.FULL, fx, false)
        val balanced = TunnelCounts.of(EffectLevel.BALANCED, fx, false)
        val saver = TunnelCounts.of(EffectLevel.SAVER, fx, false)
        assertTrue(full.rays > balanced.rays && balanced.rays > saver.rays)
        assertTrue(full.petals > balanced.petals && balanced.petals > saver.petals)
        assertTrue(full.lines > saver.lines)
        assertTrue(full.rays <= 130)
        // Reduced motion: nothing flies, only the still's drift.
        val still = TunnelCounts.of(EffectLevel.FULL, fx, true)
        assertEquals(0, still.rays + still.petals + still.burst + still.lines)
        assertEquals(saver.drift, still.drift)
        // 數量 0 takes the petals away but never the tunnel itself; the season adds only in season.
        val none = TunnelCounts.of(EffectLevel.FULL, IntroFx(scale = 0f), false)
        assertEquals(0, none.petals + none.burst + none.drift)
        assertEquals(full.rays, none.rays)
        assertEquals(0, full.season)
        assertEquals(10, TunnelCounts.of(EffectLevel.FULL, IntroFx(season = IntroSeason.SNOW), false).season)
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
        reduced: Boolean = false,
    ): List<Bitmap> {
        rule.mainClock.autoAdvance = false
        rule.setContent { FogTheme(effectsFor(level, false)) { StartupIntro(intro, picture, reduced, fx = fx) {} } }
        val start = rule.mainClock.currentTime
        return frames.map { ms ->
            rule.mainClock.advanceTimeBy(start + ms - rule.mainClock.currentTime)
            shot()
        }
    }

    private fun tunnel(seconds: Float = 3f, name: String = "小雨") = Intro(enabled = true, name = name, seconds = seconds, style = IntroStyle.TUNNEL)

    private fun Bitmap.count(left: Int, top: Int, right: Int, bottom: Int, match: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) {
            val c = getPixel(x, y)
            if (match((c shr 16) and 255, (c shr 8) and 255, c and 255)) n++
        }
        return n
    }

    /** Grey as the render harness defines it: the channels within 6 of each other and all under 200. */
    private fun grey(r: Int, g: Int, b: Int) = maxOf(r, g, b) - minOf(r, g, b) < 6 && maxOf(r, g, b) < 200

    /** Grey pixels outside the host's own 跳過 label at the bottom right (not this look's drawing). */
    private fun Bitmap.greys(): Int = count(0, 0, width, height - 130) { r, g, b -> grey(r, g, b) } +
        count(0, height - 130, width - 300, height) { r, g, b -> grey(r, g, b) }

    private fun roseBrown(r: Int, g: Int, b: Int) = abs(r - 0x9E) <= 4 && abs(g - 0x7A) <= 4 && abs(b - 0x7A) <= 4

    @Test fun noGreyFromTheVoidToTheIris() {
        // The void (150), the tunnel and its discs (600, 900), the panels (1150–1700, with a disc's chip turning over at 540),
        // the dash (1790, 1860), the burst (1900, 2000), the welcome (2200, 2400) and the iris (2700).
        val frames = play(tunnel(), 150, 540, 600, 900, 1150, 1350, 1500, 1700, 1790, 1860, 1900, 2000, 2200, 2400, 2700)
        frames.forEachIndexed { i, f -> assertEquals("grey pixels in frame $i", 0, f.greys()) }
    }

    @Test fun inWinterTheSnowLeavesNoGrey() {
        // The snow falls under the blush veil: an aqua flake never lies straight under the welcome's ink.
        play(tunnel(), 900, 1790, 2200, 2400, fx = IntroFx(season = IntroSeason.SNOW)).forEachIndexed { i, f -> assertEquals("snow frame $i", 0, f.greys()) }
    }

    @Test fun withPetalsOffTheSparklesLeaveNoGrey() {
        play(tunnel(), 900, 1950, fx = IntroFx(petals = false)).forEachIndexed { i, f -> assertEquals("sparkle frame $i", 0, f.greys()) }
    }

    @Test fun theTunnelIsFullOfRaysBeforeThePanelAndGoneAtTheWelcome() {
        val (tunnel, welcome) = play(tunnel(), 900, 2400)
        assertTrue("rose-brown rays in the tunnel", tunnel.count(0, 0, tunnel.width, tunnel.height) { r, g, b -> roseBrown(r, g, b) } > 20_000)
        // (A few anti-aliased edge pixels of the ink can land on the shade; a ray would be thousands.)
        assertTrue(welcome.count(0, 0, welcome.width, welcome.height) { r, g, b -> roseBrown(r, g, b) } < 200)
    }

    @Test fun theWelcomeIsInkInsideWhite() {
        val frame = play(tunnel(), 2400).single()
        // The name's band: from 58 % of the height down to the welcome's foot.
        val top = (frame.height * .58f).toInt()
        val bottom = top + 420
        val ink = frame.count(0, top, frame.width, bottom) { r, g, b -> abs(r - 0x5E) <= 6 && abs(g - 0x44) <= 6 && abs(b - 0x45) <= 6 }
        val white = frame.count(0, top, frame.width, bottom) { r, g, b -> r >= 252 && g >= 250 && b >= 250 }
        assertTrue("ink pixels: $ink", ink > 4000)
        assertTrue("white outline pixels: $white", white > 2000)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
    }

    @Test fun thePictureStaysHiddenInTheTunnelAndOpensThroughTheCore() {
        // An invented picture: blush, with a large green disc in its middle (where the screen's middle shows it).
        val bmp = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 1000) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 500) * (y - 500) <= 160 * 160) setPixel(x, y, 0xFF00C800.toInt())
        }
        val t = TunnelTimeline.of(3000f)
        val (inTunnel, dash, welcome) = play(
            tunnel(), (t.loginAt + 200f).toLong(), (t.dashAt + .55f * (t.hit - t.dashAt)).toLong(), 2400, picture = bmp.asImageBitmap(),
        )
        val green: (Int, Int, Int) -> Boolean = { r, g, b -> g > r + 60 && g > b + 60 }
        assertEquals("no picture in the tunnel", 0, inTunnel.count(0, 0, inTunnel.width, inTunnel.height, green))
        // In the dash it shows only inside the opening core round the vanishing point (the middle, 45 % down): the green disc
        // reaches 330 px below the screen's middle, the core is still about 240 px across its radius.
        val vy = (dash.height * .45f).toInt()
        assertTrue("the picture in the core", dash.count(dash.width / 2 - 60, vy - 60, dash.width / 2 + 60, vy + 60, green) > 6000)
        assertEquals("not outside the core yet", 0, dash.count(dash.width / 2 - 60, vy + 330, dash.width / 2 + 60, vy + 410, green))
        assertTrue("and the welcome stands on it", welcome.count(0, 0, welcome.width, welcome.height, green) > 20_000)
    }

    @Test fun theStillIsTheWelcomeWithoutARay() {
        val frame = play(tunnel(8f), 600, reduced = true).single()
        assertTrue(frame.count(0, 0, frame.width, frame.height) { r, g, b -> roseBrown(r, g, b) } < 200)
        assertEquals(0, frame.greys())
        val top = (frame.height * .58f).toInt()
        assertTrue(frame.count(0, top, frame.width, top + 420) { r, g, b -> abs(r - 0x5E) <= 6 && abs(g - 0x44) <= 6 && abs(b - 0x45) <= 6 } > 4000)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
    }

    @Test fun onSaverWithPetalsOffItStillPlays() {
        play(tunnel(), 300, 1300, 1700, 1950, 2600, level = EffectLevel.SAVER, fx = IntroFx(petals = false, season = IntroSeason.SAKURA))
            .forEach { assertTrue(it.width > 0) }
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test fun aSmallPhoneWithALongNameRendersEveryBeat() {
        play(tunnel(name = "星野夢美子桜庭ひなた🌸"), 300, 900, 1300, 1700, 1850, 2400).forEach { assertTrue(it.width > 0) }
    }

    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun landscapeWithoutANameRendersEveryBeat() {
        val frames = play(tunnel(1.5f, name = ""), 300, 600, 900, 1250)
        frames.forEach { assertTrue(it.width > 0) }
        // With no name the greeting is the sticker; it stays above the bottom edge.
        val last = frames.last()
        val ink = last.count(0, 0, last.width, last.height) { r, g, b -> abs(r - 0x5E) <= 6 && abs(g - 0x44) <= 6 && abs(b - 0x45) <= 6 }
        assertTrue("welcome ink: $ink", ink > 2000)
        assertEquals(0, last.count(0, last.height - 40, last.width - 300, last.height) { r, g, b -> abs(r - 0x5E) <= 6 && abs(g - 0x44) <= 6 && abs(b - 0x45) <= 6 })
        rule.onNodeWithText("歡迎回來").assertExists()
    }
}
