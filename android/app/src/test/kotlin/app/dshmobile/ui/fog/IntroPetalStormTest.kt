package app.dshmobile.ui.fog

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.dshmobile.ui.theme.Sakura
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
import kotlin.math.sqrt

/**
 * 「花吹雪聚形」 ([IntroStyle.PETAL_STORM]): its beats (moves keep their speed, only the holds stretch; the crest is skipped below
 * 2.5 s), the meter, the grain counts per effect level, where the petals land (the app's top bar and dock, every stretch covered,
 * in order), and the look drawn for real: only the palette's colours, the petals on the edges, the app growing out of the bar's
 * line and the dock's outline with no centre iris, the picture kept clear above a calm field, and a still with nothing in flight.
 * (The shared contract, on time, tap, reduced motion and TalkBack, is IntroStylesTest's.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroPetalStormTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- beats ---------------------------------------------------------------------------------------------------------------

    @Test fun everyLengthEndsOnTimeAndOnlyTheHoldsStretch() {
        for (s in listOf(1.5f, 2f, 2.49f, 2.5f, 3f, 5f, 8f)) {
            val b = PetalStormBeats.of(s)
            assertEquals("$s s ends on time", s * 1000f, b.plan.total, .01f)
            assertEquals("$s s: the exit runs to the end", s * 1000f, b.plan.end(b.exit), .01f)
            assertTrue("$s s: the beats are in order", b.burst < b.nameFormed && b.nameFormed <= b.landStart && b.landStart < b.exitStart)
        }
        // From 3 s on every move has its own length: 8 s only waits longer between them.
        val three = PetalStormBeats.of(3f)
        val eight = PetalStormBeats.of(8f)
        assertEquals(1f, three.pace, 0f)
        assertEquals(1f, eight.pace, 0f)
        for (i in listOf(0, three.gather, three.morph, three.land, three.exit)) {
            assertEquals("move $i keeps its length", three.plan.length(i), eight.plan.length(i), .01f)
        }
        assertEquals(480f, eight.burst, .01f)
        assertTrue(eight.plan.length(eight.crest) > 1500f)
        assertTrue(eight.plan.length(eight.name) > 3000f)
        assertTrue("the name holds longer than the crest", eight.plan.length(eight.name) > eight.plan.length(eight.crest))
    }

    @Test fun belowTwoAndAHalfSecondsTheGrainsGatherStraightIntoTheName() {
        val short = PetalStormBeats.of(1.5f)
        assertTrue(short.compact)
        assertEquals(-1, short.crest)
        assertEquals(-1, short.morph)
        assertEquals("the gather flies into the name", short.plan.end(short.gather), short.nameFormed, .01f)
        assertTrue("1.5 s only tightens a little", short.pace > .75f)
        assertTrue(PetalStormBeats.of(2.49f).compact)
        assertFalse(PetalStormBeats.of(2.5f).compact)
        assertFalse(PetalStormBeats.of(3f).compact)
    }

    @Test fun theMeterClimbsToFullWhenTheNameHasFormed() {
        for (s in listOf(1.5f, 3f, 8f)) {
            val b = PetalStormBeats.of(s)
            assertEquals(0f, b.meter(0f), 0f)
            var last = 0f
            var t = 0f
            while (t <= b.plan.total) {
                val m = b.meter(t)
                assertTrue("$s s: the meter never goes back ($t ms)", m >= last - 1e-4f)
                assertTrue(m in 0f..1f)
                last = m
                t += 10f
            }
            assertTrue("$s s: not full before the name", b.meter(b.nameFormed - 50f) < 1f)
            assertEquals("$s s: full when the name has formed", 1f, b.meter(b.nameFormed), 0f)
            assertEquals(1f, b.meter(b.still), 0f)
        }
        // It moves in every beat of the storm, not in one jump.
        val b = PetalStormBeats.of(3f)
        assertTrue(b.meter(b.burst) in 0.1f..0.25f)
        assertTrue(b.meter(b.plan.end(b.gather)) in 0.4f..0.5f)
    }

    @Test fun theWelcomeIsAnnouncedOnceTheNameIsThereAndTheStillIsSettled() {
        for (s in listOf(1.5f, 3f, 8f)) {
            val b = PetalStormBeats.of(s)
            assertTrue("$s s: announced after the name starts", b.announce > b.stickerStart)
            assertTrue("$s s: announced before the petals leave", b.announce < b.landStart)
            // The still (reduced motion draws the sticker settled at any time) shows the formed name, before the flight.
            assertTrue("$s s: the still shows the name", b.still >= b.nameFormed)
            assertTrue("$s s: the still is before the flight", b.still < b.landStart)
            // From 3 s even a long name's last glyph has settled before the petals leave: the glyphs start within 240 ms of each
            // other and their 420 ms rise (ExpoOut) is above 99 % after 80 % of it.
            if (s >= 3f) assertTrue("$s s: the name is settled before the flight", b.landStart >= b.stickerStart + (240f + .8f * 420f) * b.pace)
        }
        assertTrue("IntroStylesTest reads the welcome at 2.5 s of a 3 s run", PetalStormBeats.of(3f).announce < 2400f)
        // The haptic tick is the wave breaking; the host's exit is the run's last instant, so its sakura iris never opens on the
        // planned path (the look hands over to the app itself).
        for (s in listOf(1.5f, 3f, 8f)) {
            val b = PetalStormBeats.of(s)
            val timing = petalStormTiming(b)
            assertEquals(b.plan.total, timing.exit, 0f)
            assertEquals(b.plan.total, timing.total, 0f)
            assertEquals(b.burst, timing.hit, 0f)
            assertEquals(b.announce, timing.announce, 0f)
            assertEquals(b.still, timing.still, 0f)
        }
    }

    @Test fun theMeterAndSkipLeaveBeforeThePetalsAndTheAppAppearsWhereTheyLand() {
        for (s in listOf(1.5f, 3f, 8f)) {
            val b = PetalStormBeats.of(s)
            // 準備好了 is typed while the meter closes on 100 %; meter and skip are gone when the petals take off.
            assertTrue("$s s: ready typed before the name has formed", b.readyAt < b.nameFormed)
            assertEquals("$s s: meter out by take-off", b.landStart, b.meterOutStart + b.meterOutLen, .01f)
            assertTrue("$s s: within 150 ms", b.meterOutLen <= 150f)
            assertTrue("$s s: still on screen at 100 %", b.meterOutStart > b.readyAt)
            // The app develops once most petals have landed, inside the run, and the rest fades over the last ~250 ms.
            assertTrue("$s s: reveal after take-off", b.revealAt > b.landStart + .5f * b.landLen)
            assertTrue("$s s: reveal before the end", b.revealAt + b.revealLen < b.plan.total)
            assertTrue("$s s: the fade is about 250 ms", b.fadeLen in 150f..250f)
            assertEquals(b.plan.total, b.fadeStart + b.fadeLen, .01f)
            assertTrue("$s s: the fade starts after the reveal", b.fadeStart > b.revealAt)
        }
    }

    @Test fun petalsTumbleInFlightAndOnlySwayAtRest() {
        val b = PetalStormBeats.of(8f)
        val crest = b.plan.at(b.crest)
        val flying = b.motion(400f) - b.motion(200f)
        val resting = b.motion(crest + 600f) - b.motion(crest + 400f)
        assertEquals(.2f, flying, .001f)
        assertEquals(.03f, resting, .001f)
    }

    // ---- counts --------------------------------------------------------------------------------------------------------------

    @Test fun grainCountFollowsTheEffectLevel() {
        val fx = IntroFx()
        assertEquals(1500, petalStormCount(EffectLevel.FULL, fx, false))
        assertEquals(1000, petalStormCount(EffectLevel.BALANCED, fx, false))
        assertEquals(600, petalStormCount(EffectLevel.SAVER, fx, false))
        assertEquals("the still is drawn with the fewest", 600, petalStormCount(EffectLevel.FULL, fx, true))
        // The user's 數量 thins them (to 60 % at most), never past the level's budget, never below what still reads.
        assertEquals(900, petalStormCount(EffectLevel.FULL, IntroFx(scale = .3f), false))
        assertEquals(1050, petalStormCount(EffectLevel.FULL, IntroFx(scale = .7f), false))
        assertEquals(1500, petalStormCount(EffectLevel.FULL, IntroFx(scale = 1.4f), false))
        assertEquals(600, petalStormCount(EffectLevel.SAVER, IntroFx(scale = 0f), false))
    }

    // ---- where the petals land -----------------------------------------------------------------------------------------------

    private val w = 824f
    private val h = 1830f
    private val dp = 2f

    @Test fun theEdgesAreTheTopBarAndTheDock() {
        assertEquals(48f + 124f, PetalEdges.headerY(48f, dp), 0f)
        val dock = PetalEdges.dock(w, h, 32f, dp)
        assertEquals(24f, dock.left, 0f)
        assertEquals(w - 24f, dock.right, 0f)
        assertEquals(h - 32f - 24f, dock.bottom, 0f)
        assertEquals(136f, dock.height, 0f)
        val edges = PetalEdges.segments(w, h, dp, 48f, 32f)
        val length = edges.sumOf { it.length.toDouble() }.toFloat()
        val bar = w - 48f
        val round = 2 * (dock.width - 120f) + 2 * (dock.height - 120f) + 2 * Math.PI.toFloat() * 60f
        // The arcs are six chords each, a touch shorter than the true arc.
        assertEquals(bar + round, length, 6f)
        // Every piece is on the bar's line or on the dock's outline.
        for (e in edges) {
            val onBar = e.ay == 172f && e.by == 172f
            val onDock = e.ay >= dock.top - .5f && e.ay <= dock.bottom + .5f && e.by >= dock.top - .5f && e.by <= dock.bottom + .5f
            assertTrue(onBar || onDock)
        }
    }

    @Test fun everyGrainLandsOnAnEdgeInOrderAndNoStretchStaysBare() {
        val edges = PetalEdges.segments(w, h, dp, 0f, 0f)
        val n = 1500
        // The grains round the name in the middle of the screen, as they are when they leave.
        val px = FloatArray(n) { w / 2f + 160f * (ih(it, 1) - .5f) * 2f }
        val py = FloatArray(n) { 780f + 90f * (ih(it, 2) - .5f) * 2f }
        val ex = FloatArray(n) { Float.NaN }
        val ey = FloatArray(n) { Float.NaN }
        PetalEdges.allocate(px, py, n, edges, ex, ey)
        val total = edges.sumOf { it.length.toDouble() }.toFloat()
        val counts = IntArray(edges.size)
        for (i in 0 until n) {
            assertFalse("grain $i has a place", ex[i].isNaN())
            val s = edges.indices.minByOrNull { edges[it].distance2(ex[i], ey[i]) }!!
            assertTrue("grain $i is on an edge", edges[s].distance2(ex[i], ey[i]) < .01f)
            counts[s]++
        }
        // Each piece gets its share by length (± the rounding of shared corner points).
        for ((s, e) in edges.withIndex()) {
            val share = n * e.length / total
            assertTrue("piece $s: ${counts[s]} for a share of $share", abs(counts[s] - share) <= 2f)
        }
        // On each piece the grains keep their order along it (no crossing over each other on the way).
        assertTrue((0 until n).count { abs(ey[it] - 124f) < .01f } > n / 5)
        for ((s, e) in edges.withIndex()) {
            val on = (0 until n).filter { i -> edges.indices.minByOrNull { edges[it].distance2(ex[i], ey[i]) } == s }.sortedBy { e.project(ex[it], ey[it]) }
            for (k in 1 until on.size) {
                assertTrue("piece $s keeps its order", e.project(px[on[k]], py[on[k]]) >= e.project(px[on[k - 1]], py[on[k - 1]]) - 1e-4f)
            }
        }
        // The same input lands the same way (a fixed hash, no random).
        val ex2 = FloatArray(n)
        val ey2 = FloatArray(n)
        PetalEdges.allocate(px, py, n, edges, ex2, ey2)
        assertTrue(ex.contentEquals(ex2) && ey.contentEquals(ey2))
    }

    @Test fun theDotColoursAreThePalettesAndAquaDrawsOverWhite() {
        val six = setOf(Sakura.Background, Sakura.MilkTea, Sakura.Primary, Sakura.White, Sakura.Aqua, Sakura.RoseBrown)
        assertTrue(PetalStormScene.PALETTE.all { it in six })
        val p = PetalStormScene.PALETTE
        // White rims first (under the pinks), then the pinks, then white, then aqua (on its white halo).
        assertEquals(Sakura.White, p[PetalStormScene.HALO])
        assertTrue(PetalStormScene.HALO < p.indexOf(Sakura.Primary))
        assertTrue(p.indexOf(Sakura.Primary) < PetalStormScene.WHITE && p.indexOf(Sakura.RoseBrown) < PetalStormScene.WHITE)
        assertTrue(PetalStormScene.WHITE < PetalStormScene.AQUA)
        assertEquals(Sakura.Aqua, p[PetalStormScene.AQUA])
    }

    // ---- drawn for real ------------------------------------------------------------------------------------------------------

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

    /**
     * Plays [intro] over a plain white "app" (so what shows through the look is easy to tell from the look's own pink) and
     * captures [frames] (ms on the run's clock).
     */
    private fun play(intro: Intro, vararg frames: Long, picture: ImageBitmap? = null, level: EffectLevel = EffectLevel.FULL, reduced: Boolean = false): List<Bitmap> {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FogTheme(effectsFor(level, false)) {
                Box(Modifier.fillMaxSize().background(Sakura.White)) { StartupIntro(intro, picture, reduced) {} }
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

    /** The first few grey pixels, for the failure message. */
    private fun greys(f: Bitmap): String {
        val out = ArrayList<String>()
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val c = f.getPixel(x, y)
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
            if (grey(r, g, bl) && out.size < 6) out += "($x,$y)=#%02X%02X%02X".format(r, g, bl)
        }
        return out.joinToString()
    }

    private fun grey(r: Int, g: Int, b: Int) = r < 200 && g < 200 && b < 200 && maxOf(r, g, b) - minOf(r, g, b) < 6
    private fun petal(r: Int, g: Int, b: Int) = (abs(r - 0xDC) < 14 && abs(g - 0x9F) < 18 && abs(b - 0xB4) < 18) || (r >= 248 && g >= 248 && b >= 248)

    @Test fun thePetalsLandOnTheBarAndTheDockAndTheAppGrowsOutOfThem() {
        val intro = Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.PETAL_STORM)
        val b = PetalStormBeats.of(3f)
        val (burst, crest, name, landed, develop) = play(
            intro, b.burst.toLong() + 80, (b.plan.at(b.crest) + 60f).toLong(), 2100L, (b.revealAt - 10f).toLong(), (b.fadeStart + 20f).toLong(),
        )
        // Only the palette: no grey where pink and aqua meet, in the burst, the crest, the welcome, the landing or the hand-off.
        for ((label, f) in listOf("burst" to burst, "crest" to crest, "name" to name, "landed" to landed, "develop" to develop)) {
            assertEquals("$label: grey pixels ${greys(f)}", 0, f.count(0, 0, f.width, f.height, ::grey))
        }
        // The crest: a ring of petal-pink round the middle of the circle.
        val dock = PetalEdges.dock(824f, 1830f, 0f, 2f)
        val headerY = PetalEdges.headerY(0f, 2f).toInt()
        val crestPink = crest.count(220, 460, 604, 1060) { r, g, bl -> abs(r - 0xDC) < 14 && abs(g - 0x9F) < 18 && abs(bl - 0xB4) < 18 }
        assertTrue("crest grains: $crestPink", crestPink > 2000)
        // Landed: the bar's line and the dock's outline are lined with petals; before, only their faint dotted ghosts were there.
        fun bar(f: Bitmap) = f.count(24, headerY - 5, 800, headerY + 6, ::petal)
        fun dockTop(f: Bitmap) = f.count(dock.left.toInt() + 60, dock.top.toInt() - 5, dock.right.toInt() - 60, dock.top.toInt() + 6, ::petal)
        assertTrue("bar: ${bar(landed)} vs ${bar(name)}", bar(landed) > 1500 && bar(landed) > 3 * bar(name))
        assertTrue("dock: ${dockTop(landed)} vs ${dockTop(name)}", dockTop(landed) > 1500 && dockTop(landed) > 3 * dockTop(name))
        // The hand-off: the app (white here) shows under the bar and inside the dock, grown out of where the petals landed, while
        // the middle of the screen is still the look's (no sakura iris punched through the centre).
        fun white(f: Bitmap, x: Int, y: Int) = f.getPixel(x, y).let { ((it shr 16) and 255) >= 250 && ((it shr 8) and 255) >= 250 && (it and 255) >= 250 }
        assertFalse(white(landed, 412, dock.center.y.toInt()))
        assertFalse(white(landed, 412, headerY / 2))
        assertTrue("the dock developed", white(develop, 412, dock.center.y.toInt()))
        assertTrue("the bar developed", white(develop, 412, headerY / 2))
        assertFalse("the middle is still the look's", white(develop, 412, 760))
        assertFalse("the sky is still the look's", white(develop, 60, 1400))
    }

    @Test fun theHandOffFadesTheRestIntoTheAppByTheEnd() {
        val intro = Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.PETAL_STORM)
        val (end) = play(intro, 2990L)
        // Ten ms before the end the look is all but gone: the app (white) shows nearly everywhere.
        val whiteish = end.count(0, 0, end.width, end.height) { r, g, b -> r >= 240 && g >= 240 && b >= 240 }
        assertTrue("whiteish: $whiteish", whiteish > .9f * end.width * end.height)
    }

    @Test fun theStillIsComposedWithNothingInFlight() {
        val intro = Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.PETAL_STORM)
        val (still) = play(intro, 300L, reduced = true)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
        assertEquals("grey pixels ${greys(still)}", 0, still.count(0, 0, still.width, still.height, ::grey))
        // Between the rings and the meter (and right across the screen) no grain hangs in the air.
        val pink = still.count(0, 1110, still.width, 1220, ::petal)
        assertEquals("grains in flight in the still", 0, pink)
        // The name and the full meter are there.
        val ink = still.count(220, 600, 604, 900) { r, g, b -> abs(r - 0x5E) < 20 && abs(g - 0x44) < 20 && abs(b - 0x45) < 20 }
        assertTrue("the name's ink: $ink", ink > 1500)
    }

    /**
     * A picture: an aqua sky over a pink lower half (the two colours a pink veil or pink grains could blend to grey), with a
     * white band between them so the picture itself passes through white.
     */
    private fun picture(): ImageBitmap = ImageBitmap(64, 96).also { img ->
        val c = Canvas(img)
        c.drawRect(0f, 0f, 64f, 44f, Paint().apply { color = Sakura.Aqua })
        c.drawRect(0f, 44f, 64f, 52f, Paint().apply { color = Sakura.White })
        c.drawRect(0f, 52f, 64f, 96f, Paint().apply { color = Sakura.Primary })
    }

    private fun overAPicture(name: String, level: EffectLevel) {
        val intro = Intro(enabled = true, name = name, seconds = 2f, style = IntroStyle.PETAL_STORM)
        val frames = play(intro, 300, 900, 1300, picture = picture(), level = level)
        val line = if (name.isEmpty()) "歡迎回來" else "歡迎回來，$name"
        rule.onNodeWithText(line).assertExists()
        for ((k, f) in frames.withIndex()) {
            // The top keeps the picture as the home screen shows it (its aqua sky, not washed out) ...
            val top = f.getPixel(412, 330)
            assertTrue("$name, frame $k: the picture is clear at the top (#%06X)".format(top and 0xFFFFFF), (top and 255) > ((top shr 16) and 255) + 25)
            // ... and the storm plays in a calm blush field below it.
            val field = f.getPixel(60, 1500)
            val lum = sqrt((((field shr 16) and 255) * ((field shr 8) and 255)).toFloat())
            assertTrue("$name, frame $k: calm field ($lum)", lum > 205f)
            // Pink grains get white rims over the picture's aqua: no grey anywhere.
            assertEquals("$name, frame $k: grey pixels ${greys(f)}", 0, f.count(0, 0, f.width, f.height, ::grey))
        }
    }

    @Test fun aLongLatinNameOverAPicture() = overAPicture("Christopher Robin Milne", EffectLevel.BALANCED)
    @Test fun anEmojiInTheNameOverAPicture() = overAPicture("小雨🌸", EffectLevel.FULL)
    @Test fun noNameOverAPictureOnSaver() = overAPicture("", EffectLevel.SAVER)
}
