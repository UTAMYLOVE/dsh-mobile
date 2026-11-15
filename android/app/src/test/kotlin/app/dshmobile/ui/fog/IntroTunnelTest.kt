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
 * tunnel's fast start and the rays' closed-form depth, colours and fan-out, the core that grows the portrait and opens onto the
 * picture, the one true-white burst, the layout on every screen (the panels never cover the core, the discs hover fully on
 * screen), the name's line break and the landscape card; then drawn for real (native graphics, PixelCopy): no grey anywhere
 * (the 跳過 pill included), sakura light with few rose-brown accents, the welcome in ink inside white, the picture in the core,
 * the still's frozen tunnel, and the odd screens (landscape, a small phone with a long name, saver with petals off).
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
            // The white void is short: the spark comes within 150 ms.
            assertTrue("$s", 0f < t.tunnelAt && t.tunnelAt <= 150f && t.tunnelAt < t.discAt(0))
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
        // On a long run the discs fly one at a time; at 3 s two or three hover at once.
        val t8 = TunnelTimeline.of(8000f)
        assertEquals(420f, t8.discStep, .01f)
        assertTrue(t8.discStep < t8.discMs)
        assertEquals(320f / 3f, t3.discStep, .01f)
    }

    @Test fun shortRunsLeaveOutTheConfirmPanel() {
        val short = TunnelTimeline.of(1500f)
        assertTrue(short.compact)
        assertTrue(TunnelTimeline.of(2199f).compact)
        assertTrue(!TunnelTimeline.of(2200f).compact)
        assertEquals(0f, short.plan.length(B_CONFIRM), 0f)
        assertEquals(short.dashAt, short.confirmAt, .01f)
        assertEquals(short.dashAt, short.loginOutAt, .01f)
        assertEquals(3, short.discs)
        assertEquals(4, TunnelTimeline.of(3000f).discs)
        // The moves tighten together, about by half, and the name types in at most 220 ms × pace.
        assertTrue(short.pace in .45f..0.6f)
        assertEquals(70f * short.pace, short.typeStep(2), .01f)
        assertTrue(short.typeStep(24) * 24 <= 220f * short.pace + .1f)
    }

    @Test fun theTunnelStartsFastBuildsSlowsBehindThePanelsAndDashes() {
        val t = TunnelTimeline.of(3000f)
        assertEquals(0f, t.speed(t.tunnelAt - 1f), 0f)
        assertEquals(0f, t.distance(t.tunnelAt - 8f), 1e-4f)
        // It starts moving at once (a non-zero start, not a crawl), gets up to cruise before the panel, calms down behind it,
        // and is fastest at the very end of the dash.
        assertEquals(TunnelTimeline.START_SPEED, t.speed(t.tunnelAt), .01f)
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

    @Test fun byThreeHundredMillisecondsTheTunnelIsFullOfLongRays() {
        // A phone-like frame: half-diagonal 1000 px, the core 88 px. A long ray reaches past 45 % of the way to the corners and
        // starts within 30 % of it (it spans the screen from near the core), at every run length and effect level.
        for (s in listOf(1500f, 3000f, 8000f)) for (n in listOf(110, 76, 40)) {
            val t = TunnelTimeline.of(s)
            val rays = TunnelRays(t, n, focal = 200f, reach = 1000f, core = 88f, dp = 2f)
            val flown = t.distance(300f)
            var long = 0
            for (i in 0 until n) if (rays.pose(i, 300f, flown, t.dashK(300f)) && rays.head >= 450f && rays.tail <= 300f) long++
            assertTrue("$s s, $n rays: $long long", long >= 12)
        }
        // Nothing before the spark.
        val t = TunnelTimeline.of(3000f)
        val rays = TunnelRays(t, 110, 200f, 1000f, 88f, 2f)
        assertTrue((0 until 110).none { rays.pose(it, t.tunnelAt - 1f, 0f, 0f) })
    }

    @Test fun rayColoursAreSakuraLightWithFewRoseBrownAccents() {
        val t = TunnelTimeline.of(8000f)
        val rays = TunnelRays(t, 110, 200f, 1000f, 88f, 2f)
        val groups = IntArray(6)
        var ms = 400f
        while (ms < t.loginAt) {
            val flown = t.distance(ms)
            for (i in 0 until 110) if (rays.pose(i, ms, flown, 0f)) {
                groups[rays.group]++
                // Rose-brown only as a thin accent.
                if (rays.group == 5) assertTrue(rays.half <= rays.head * .011f + 1.6f)
            }
            ms += 97f
        }
        val all = groups.sum().toFloat()
        // pink ×4, blush ×3, white ×2, aqua ×2, milk tea ×1 (aqua outside its sectors turns white), rose-brown a few per cent.
        assertTrue("pink ${groups[4] / all}", groups[4] / all > .28f)
        assertTrue("milk tea ${groups[3] / all}", groups[3] / all < .12f)
        assertTrue("rose-brown ${groups[5] / all}", groups[5] / all in .005f..0.05f)
        assertTrue("aqua ${groups[0] / all}", groups[0] / all in .05f..0.16f)
    }

    @Test fun theCoreIsALightThatGrowsThePortraitAndOpensAtTheBurst() {
        for (s in listOf(1500f, 3000f, 8000f)) {
            val t = TunnelTimeline.of(s)
            val dp = 2f
            val cover = 1000f
            assertEquals(0f, t.coreRadius(t.tunnelAt - 21f * t.pace, dp, cover), 0f)
            // The core at its largest through the panels: what the layout keeps clear.
            val panel = t.coreRadius(t.dashAt - 1f, dp, cover)
            assertTrue("$panel", panel in (TunnelLayout.CORE_DP - 3f) * dp..TunnelLayout.CORE_DP * dp + .01f)
            assertEquals(cover, t.coreRadius(t.hit, dp, cover), 0f)
            assertEquals(cover, t.coreRadius(t.still, dp, cover), 0f)
            // It opens mostly at the end (the dash eases in).
            assertTrue(t.coreRadius(t.dashAt + .5f * (t.hit - t.dashAt), dp, cover) < .3f * cover)
            // The user's picture grows in the core during the cruise, fully there before the panels.
            assertEquals(0f, t.portraitK(t.tunnelAt + 249f * t.pace), 0f)
            assertEquals(1f, t.portraitK(t.loginAt), 1e-4f)
        }
    }

    @Test fun oneTrueWhiteBurstAtTheHitAndTheWordsOnlyAfterTheFlare() {
        for (s in listOf(1500f, 3000f, 8000f)) {
            val t = TunnelTimeline.of(s)
            assertEquals(0f, t.flash(t.hit - 51f * t.pace), 0f)
            // A true white peak held two frames.
            assertTrue(t.flash(t.hit) >= .97f)
            assertTrue(t.flash(t.hit + 33f * t.pace) >= .97f)
            assertEquals(0f, t.flash(t.hit + 275f * t.pace), 1e-4f)
            assertEquals(0f, t.flash(t.still), 0f)
            // The flood comes in during the dash and is gone 140 ms after the burst.
            assertEquals(0f, t.flood(t.dashAt), 0f)
            assertEquals(0f, t.flood(t.dashAt + .34f * (t.hit - t.dashAt)), 0f)
            assertTrue(t.flood(t.hit - 1f) > .9f)
            assertEquals(0f, t.flood(t.hit + 141f * t.pace), 0f)
            // The flare's blades are gone by hit + 110 ms × pace: the greeting starts after.
            assertTrue(t.greetAt >= t.hit + 110f * t.pace)
        }
    }

    @Test fun aPanelLosesItsWordsBeforeItIsPulledIntoTheCore() {
        val t = TunnelTimeline.of(3000f)
        val at = t.dashAt
        assertEquals(1f, t.panelText(at - 1f, at), 0f)
        assertEquals(0f, t.panelText(at + 60f, at), 0f)
        assertEquals(0f, t.panelPull(at + 40f, at), 0f)
        assertEquals(1f, t.panelPull(at + 180f, at), 0f)
        // The words are gone before the card has shrunk noticeably: no squashed glyph is ever drawn.
        assertTrue(t.panelPull(at + 60f, at) < .05f)
        assertTrue(at + 180f <= t.hit + .01f)
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

    // ---- the layout ----

    private class Screen(val w: Float, val h: Float, val dp: Float, val top: Float, val bottom: Float)

    private val screens = listOf(412f to 915f, 360f to 640f, 915f to 412f, 640f to 360f, 320f to 568f).flatMap { (w, h) ->
        listOf(Screen(w * 2f, h * 2f, 2f, 0f, 0f), Screen(w * 2.625f, h * 2.625f, 2.625f, 24f * 2.625f, 48f * 2.625f))
    }

    @Test fun thePanelsNeverCoverTheCore() {
        for (s in screens) for (card in listOf(110f, 140f, 180f)) {
            val lay = TunnelLayout.of(s.w, s.h, s.dp, s.top, s.bottom, card * s.dp)
            val where = "${s.w / s.dp}×${s.h / s.dp} dp, card $card dp"
            assertTrue("$where: ${lay.coreBox} vs ${lay.panelBox}", !lay.coreBox.overlaps(lay.panelBox))
            assertTrue(where, lay.panelBox.left >= 0f && lay.panelBox.right <= s.w)
            assertTrue(where, lay.coreBox.top >= s.top && lay.coreBox.bottom <= s.h)
            // Landscape puts them beside the core, portrait under it with a 20 dp gap.
            if (lay.wide) assertTrue(where, lay.panelBox.left >= lay.coreBox.right + TunnelLayout.GAP_DP * s.dp - .5f)
            else assertTrue(where, lay.panelBox.top >= lay.coreBox.bottom + TunnelLayout.GAP_DP * s.dp - .5f)
        }
        // The three screens of the review, with a real card's height: the vanishing point stays put where it fits, moves up
        // where it does not.
        assertEquals(.45f * 1830f, TunnelLayout.of(824f, 1830f, 2f, 0f, 0f, 260f).vp.y, .5f)
        val wide = TunnelLayout.of(1830f, 824f, 2f, 0f, 0f, 260f)
        assertEquals(.35f * 1830f, wide.vp.x, .5f)
        val cramped = TunnelLayout.of(720f, 1280f, 2f, 48f, 96f, 360f)
        assertTrue(cramped.vp.y < .45f * 1280f)
        assertTrue(!cramped.coreBox.overlaps(cramped.panelBox))
    }

    @Test fun theDiscsHoverFullyOnScreenBigEnoughToRead() {
        for (s in screens) {
            val lay = TunnelLayout.of(s.w, s.h, s.dp, s.top, s.bottom, 140f * s.dp)
            val where = "${s.w / s.dp}×${s.h / s.dp} dp"
            for (i in lay.discs.indices) {
                val b = lay.discBox(i)
                assertTrue("$where disc $i $b", b.left >= 16f * s.dp - .5f && b.right <= s.w - 16f * s.dp + .5f)
                assertTrue("$where disc $i $b", b.top >= s.top + 64f * s.dp - .5f && b.bottom <= s.h - s.bottom - 16f * s.dp + .5f)
            }
            // The hero (編號) takes the most central path.
            val d = lay.discs.map { (it - lay.vp).getDistance() }
            assertTrue("$where $d", d[0] <= d.minOrNull()!! + 1f)
            // Full size on the review's three screens (and any phone); smaller only on a short landscape with system bars.
            if (s.w / s.dp to s.h / s.dp in listOf(412f to 915f, 360f to 640f, 915f to 412f, 320f to 568f)) assertEquals(where, 1f, lay.discScale, 0f)
        }
        // While it hovers a disc is at least 140 dp across and it flips to OK then, before it flies off.
        var k = DISC_HOVER_FROM
        while (k < DISC_HOVER_TO) {
            assertTrue("$k", 2f * TunnelLayout.DISC_DP * 2f / tunnelDiscDepth(k) >= 140f)
            k += .01f
        }
        assertTrue(DISC_FLIP - .05f >= DISC_HOVER_FROM && DISC_FLIP + .05f <= DISC_HOVER_TO)
        // Out of the depths of the tunnel, and past the camera at the end.
        assertTrue("${tunnelDiscDepth(.02f)}", tunnelDiscDepth(.02f) > 5f)
        assertEquals(.3f, tunnelDiscDepth(1f), .01f)
    }

    @Test fun aLongMixedNameBreaksWhereItsScriptChanges() {
        fun brk(name: String, maxW: Float): Int {
            val g = graphemes(name)
            return tunnelNameBreak(g, FloatArray(g.size) { it.toFloat() }, FloatArray(g.size) { 1f }, maxW)
        }
        // 星野夢美子桜庭 | ひなた🌸: never inside 桜庭, and the flower stays with ひなた.
        assertEquals(7, brk("星野夢美子桜庭ひなた🌸", 8f))
        assertEquals(2, brk("小雨Amy", 4f))
        // One script only, or a side too long for the line: the sticker balances it by width instead.
        assertEquals(-1, brk("星野夢美子桜庭", 5f))
        assertEquals(-1, brk("星野夢美子桜庭ひなた", 6f))
    }

    @Test fun aTallPictureOnAWideScreenStandsAsACard() {
        // Portrait picture, landscape phone: a crop would show only a band across the chest.
        assertTrue(tunnelCardMode(1024f, 1536f, 1830f, 824f))
        // Portrait picture, portrait phone; landscape picture, landscape phone: the crop shows most of it.
        assertTrue(!tunnelCardMode(1024f, 1536f, 824f, 1830f))
        assertTrue(!tunnelCardMode(1536f, 1024f, 1830f, 824f))
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

    /** Grey pixels anywhere, the host's 跳過 SKIP pill included (it stands on the look's opaque mist face). */
    private fun Bitmap.greys(): Int = count(0, 0, width, height) { r, g, b -> grey(r, g, b) }

    private fun roseBrown(r: Int, g: Int, b: Int) = abs(r - 0x9E) <= 4 && abs(g - 0x7A) <= 4 && abs(b - 0x7A) <= 4
    private fun pink(r: Int, g: Int, b: Int) = abs(r - 0xDC) <= 8 && abs(g - 0x9F) <= 8 && abs(b - 0xB4) <= 8
    private fun aqua(r: Int, g: Int, b: Int) = abs(r - 0xA5) <= 8 && abs(g - 0xDE) <= 8 && abs(b - 0xE4) <= 8
    private fun ink(r: Int, g: Int, b: Int) = abs(r - 0x5E) <= 6 && abs(g - 0x44) <= 6 && abs(b - 0x45) <= 6
    private val green: (Int, Int, Int) -> Boolean = { r, g, b -> g > r + 60 && g > b + 60 }

    private fun Bitmap.countIn(cx: Float, cy: Float, r: Float, inside: Boolean, match: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            val d = kotlin.math.hypot(x - cx, y - cy)
            if ((d <= r) != inside) continue
            val c = getPixel(x, y)
            if (match((c shr 16) and 255, (c shr 8) and 255, c and 255)) n++
        }
        return n
    }

    @Test fun noGreyFromTheVoidToTheIris() {
        // The void (60), the spark (150), the fan-out (300), the discs hovering and flipping (540, 600, 900), the panels
        // (1150–1700), the panel pulled into the core and the dash (1750, 1790, 1860), the burst (1900, 2000), the welcome
        // (2200, 2400) and the iris (2700).
        val frames = play(tunnel(), 60, 150, 300, 540, 600, 900, 1150, 1350, 1500, 1700, 1750, 1790, 1860, 1900, 2000, 2200, 2400, 2700)
        frames.forEachIndexed { i, f -> assertEquals("grey pixels in frame $i", 0, f.greys()) }
    }

    @Test fun onALongRunNoGreyUnderTheSkipLabel() {
        // The review's 8 s frame: rays of every colour pass under the 跳過 SKIP pill for seconds.
        play(tunnel(8f), 600, 1800, 3200, 4400).forEachIndexed { i, f -> assertEquals("8 s frame $i", 0, f.greys()) }
    }

    @Test fun inWinterTheSnowLeavesNoGrey() {
        // The snow falls under the blush veil: an aqua flake never lies straight under the welcome's ink.
        play(tunnel(), 900, 1790, 2200, 2400, fx = IntroFx(season = IntroSeason.SNOW)).forEachIndexed { i, f -> assertEquals("snow frame $i", 0, f.greys()) }
    }

    @Test fun withPetalsOffTheSparklesLeaveNoGrey() {
        play(tunnel(), 900, 1950, fx = IntroFx(petals = false)).forEachIndexed { i, f -> assertEquals("sparkle frame $i", 0, f.greys()) }
    }

    @Test fun theTunnelIsSakuraLightBeforeThePanelAndGoneAtTheWelcome() {
        val (early, tunnel, welcome) = play(tunnel(), 300, 900, 2400)
        val all = tunnel.width * tunnel.height
        // Already at 300 ms the screen is full of pink light (round one had none then), and still at 900 ms.
        val pinkEarly = early.count(0, 0, all, all) { r, g, b -> pink(r, g, b) }
        val pinkTunnel = tunnel.count(0, 0, all, all) { r, g, b -> pink(r, g, b) }
        assertTrue("pink at 300 ms: $pinkEarly", pinkEarly > 10_000)
        assertTrue("pink at 900 ms: $pinkTunnel", pinkTunnel > 10_000)
        // Rose-brown only as thin accents: at most 3 % of the screen.
        val rose = tunnel.count(0, 0, all, all) { r, g, b -> roseBrown(r, g, b) }
        assertTrue("rose-brown: $rose", rose <= all * .03f)
        // (A few anti-aliased edge pixels of the ink can land on the shade; a ray would be thousands.)
        assertTrue(welcome.count(0, 0, all, all) { r, g, b -> roseBrown(r, g, b) } < 200)
    }

    @Test fun theWelcomeIsInkInsideWhite() {
        val frame = play(tunnel(), 2400).single()
        // The name's band: from 58 % of the height down to the welcome's foot.
        val top = (frame.height * .58f).toInt()
        val bottom = top + 420
        val ink = frame.count(0, top, frame.width, bottom) { r, g, b -> ink(r, g, b) }
        val white = frame.count(0, top, frame.width, bottom) { r, g, b -> r >= 252 && g >= 250 && b >= 250 }
        assertTrue("ink pixels: $ink", ink > 4000)
        assertTrue("white outline pixels: $white", white > 2000)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
    }

    @Test fun thePictureGrowsInTheCoreAndOpensOntoTheStage() {
        // An invented picture: blush, with a large green disc a little under where a face would be.
        val bmp = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFEDFE1.toInt())
            for (y in 0 until 1000) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 500) * (y - 500) <= 160 * 160) setPixel(x, y, 0xFF00C800.toInt())
        }
        val t = TunnelTimeline.of(3000f)
        val (cruise, dash, welcome) = play(tunnel(), (t.loginAt + 200f).toLong(), (t.dashAt + 30f).toLong(), 2400, picture = bmp.asImageBitmap())
        // The vanishing point on this screen (824 × 1830 px, the panels fit under it): the middle, 45 % down. The core is
        // 44 dp (88 px) at most before the dash.
        val vx = cruise.width / 2f
        val vy = cruise.height * .45f
        assertTrue("the picture in the core", cruise.countIn(vx, vy, 90f, true, green) > 300)
        assertEquals("only in the core", 0, cruise.countIn(vx, vy, 92f, false, green))
        // Early in the dash the core has hardly begun to open.
        assertEquals("not outside the core yet", 0, dash.countIn(vx, vy, 170f, false, green))
        assertTrue("and the welcome stands on it", welcome.count(0, 0, welcome.width, welcome.height, green) > 20_000)
    }

    @Test fun theStillIsAFrozenTunnelBehindTheWelcome() {
        val frame = play(tunnel(8f), 600, reduced = true).single()
        assertTrue(frame.count(0, 0, frame.width, frame.height) { r, g, b -> roseBrown(r, g, b) } < 200)
        assertEquals(0, frame.greys())
        val top = (frame.height * .58f).toInt()
        assertTrue(frame.count(0, top, frame.width, top + 420) { r, g, b -> ink(r, g, b) } > 4000)
        // The light core (pure white round the vanishing point) and one OK disc (its aqua ring) behind the words.
        assertTrue("core", frame.countIn(frame.width / 2f, frame.height * .45f, 86f, true) { r, g, b -> r >= 253 && g >= 253 && b >= 253 } > 5000)
        assertTrue("disc", frame.count(0, 0, frame.width, (frame.height * .45f).toInt()) { r, g, b -> aqua(r, g, b) } > 300)
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

    /** A tall invented picture with a green "face" near its top: cropped to a wide screen it would be cut off. */
    private fun tallPicture(): ImageBitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xFFFEDFE1.toInt())
        for (y in 0 until 1000) for (x in 0 until 500) if ((x - 250) * (x - 250) + (y - 200) * (y - 200) <= 80 * 80) setPixel(x, y, 0xFF00C800.toInt())
    }.asImageBitmap()

    /** The whole face shows (a disc of 80 px in the picture, about 50 px on the card) and the name stands beside it. */
    private fun assertCardBesideTheName(f: Bitmap) {
        val g = f.count(0, 0, f.width, f.height, green)
        assertTrue("face pixels: $g", g > 5000)
        val inked = f.count(f.width / 2 + 40, 0, f.width, f.height) { r, gg, b -> ink(r, gg, b) }
        assertTrue("welcome ink right of the card: $inked", inked > 2000)
        assertEquals(0, f.greys())
    }

    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun inLandscapeATallPictureStandsWholeAsACard() = assertCardBesideTheName(play(tunnel(), 2400, picture = tallPicture()).single())

    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun inLandscapeTheStillShowsTheCardToo() = assertCardBesideTheName(play(tunnel(), 600, picture = tallPicture(), reduced = true).single())

    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun landscapeWithoutANameRendersEveryBeat() {
        val frames = play(tunnel(1.5f, name = ""), 300, 600, 900, 1250)
        frames.forEach { assertTrue(it.width > 0) }
        // With no name the greeting is the sticker; it stays above the bottom edge.
        val last = frames.last()
        val inked = last.count(0, 0, last.width, last.height) { r, g, b -> ink(r, g, b) }
        assertTrue("welcome ink: $inked", inked > 2000)
        assertEquals(0, last.count(0, last.height - 40, last.width - 300, last.height) { r, g, b -> ink(r, g, b) })
        rule.onNodeWithText("歡迎回來").assertExists()
    }
}
