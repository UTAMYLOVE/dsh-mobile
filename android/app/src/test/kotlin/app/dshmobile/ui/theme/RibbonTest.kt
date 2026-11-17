package app.dshmobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The one-stroke geometry (drawn §2.6, war D5), on plain arithmetic: no Android, no drawing. */
class RibbonTest {
    private val w = 200f
    private val h = 80f

    /** A Round(18) frame at one px per dp: perimeter 2·(200 − 36) + 2·(80 − 36) + 2π·18. */
    private val perimeter = 2 * (w - 36f) + 2 * (h - 36f) + 2 * PI.toFloat() * 18f

    @Test fun theBrushLoopRunsOnceRoundAndOnPastItsStart() {
        val line = Ribbon.loop(Corners.Round(18f), BrushStart.TopStartPlus22dp, w, h, dp = 1f, rtl = false, overshoot = .055f)
        assertEquals(perimeter, Ribbon.perimeter(w, h, Ribbon.radii(Corners.Round(18f), w, h, 1f)), .01f)
        // It lands 22 dp past the top-start corner, on the top edge.
        assertEquals(18f + 22f, line.x[0], .01f)
        assertEquals(0f, line.y[0], .01f)
        // Once round and 5.5 % more: the centre line closes the frame and runs on past where it landed.
        assertEquals(perimeter * 1.055f, line.length, 1.5f)
        val end = line.size - 1
        assertEquals(40f + .055f * perimeter, line.x[end], 1f)
        // The overshoot drifts outward (up, off the top edge) by 1.6 dp at its end.
        assertEquals(-Ribbon.DRIFT_DP, line.y[end], .2f)
        // Every point of the outline is on the centre line (or under the drift): the loop is closed.
        val q = FloatArray(4)
        val r = Ribbon.radii(Corners.Round(18f), w, h, 1f)
        for (k in 0 until 200) {
            Ribbon.perimeterPoint(w, h, r, perimeter * k / 200f, q)
            assertTrue("perimeter point $k is off the brush: ${distanceTo(line, q[0], q[1])}", distanceTo(line, q[0], q[1]) < .8f)
        }
        // The ribbon's outline is one closed ring: the left side, a cap, the right side back, a cap.
        val ring = Ribbon.outline(Ribbon.sides(line, 2f, 1f, RibbonProfile.Frame))
        assertEquals((line.size * 2 + 14) * 2, ring.size)
    }

    @Test fun rtlMirrorsTheLoop() {
        val ltr = Ribbon.loop(Corners.Round(18f), BrushStart.TopStartPlus22dp, w, h, 1f, rtl = false, overshoot = .055f)
        val rtl = Ribbon.loop(Corners.Round(18f), BrushStart.TopStartPlus22dp, w, h, 1f, rtl = true, overshoot = .055f)
        assertEquals(w - 40f, rtl.x[0], .01f)
        for (i in 0 until ltr.size step 37) {
            assertEquals(w - ltr.x[i], rtl.x[i], .01f)
            assertEquals(ltr.y[i], rtl.y[i], .01f)
        }
    }

    @Test fun aFlickBrushStartsAtTheTailTipAndRunsOnPastIt() {
        val line = Ribbon.loop(Corners.Flick(18f, Corner.BottomEnd), BrushStart.Tail, w, h, dp = 1f, rtl = false, overshoot = .055f)
        // T is 6 dp out and 3 dp down from the bottom-end corner (kit.js bubbleUser).
        assertEquals(w + 6f, line.x[0], .01f)
        assertEquals(h + 3f, line.y[0], .01f)
        // It goes round and comes back past T by 4 dp, on along the tail.
        val end = line.size - 1
        val pastTip = hypot(line.x[end] - (w + 6f), line.y[end] - (h + 3f))
        assertEquals(Ribbon.TAIL_DP, pastTip, .3f)
        assertTrue("the tail runs out and down", line.x[end] > w + 6f && line.y[end] > h + 3f)
        // On a bottom-start corner it is mirrored.
        val start = Ribbon.loop(Corners.Flick(18f, Corner.BottomStart), BrushStart.Tail, w, h, 1f, rtl = false, overshoot = .055f)
        assertEquals(-6f, start.x[0], .01f)
        assertEquals(h + 3f, start.y[0], .01f)
    }

    @Test fun aRoundButtonBrushStartsAtOneOClockAndSweeps384Degrees() {
        val line = Ribbon.loop(Corners.Pill, BrushStart.Clock1, 40f, 40f, dp = 1f, rtl = false, overshoot = .055f)
        val start = Math.toRadians(-60.0)
        assertEquals(20f + 20f * kotlin.math.cos(start).toFloat(), line.x[0], .01f)
        assertEquals(20f + 20f * sin(start).toFloat(), line.y[0], .01f)
        // 384° of a 20 dp circle, plus the drift at its end.
        assertEquals(384f / 360f * 2f * PI.toFloat() * 20f, line.length, 3f)
        val end = line.size - 1
        assertEquals(20f + Ribbon.CIRCLE_DRIFT_DP, hypot(line.x[end] - 20f, line.y[end] - 20f), .05f)
    }

    @Test fun theWidthFollowsTheFramePressure() {
        val width = 10f
        val line = Ribbon.loop(Corners.Round(18f), BrushStart.TopStartPlus22dp, w, h, dp = 1f, rtl = false, overshoot = .055f)
        val sides = Ribbon.sides(line, width, 1f, RibbonProfile.Frame)
        fun expected(u: Float, s: Float): Float {
            val land = .32f + .68f * smooth01((s / 26f).coerceIn(0f, 1f))
            val lift = 1f - .88f * smooth01(((u - .93f) / .07f).coerceIn(0f, 1f))
            return width * land * (1f + .24f * sin(PI.toFloat() * u)) * lift
        }
        // It lands thin: .32 of the width at u = 0.
        assertEquals(.32f * width, sides.widths[0], .001f)
        for (u in listOf(0f, .5f, .97f)) {
            val i = nearest(line, u * line.length)
            assertEquals("u = $u", expected(line.s[i] / line.length, line.s[i]), sides.widths[i], .001f)
            // The two sides are that width apart.
            val apart = hypot(sides.left[2 * i] - sides.right[2 * i], sides.left[2 * i + 1] - sides.right[2 * i + 1])
            assertEquals("u = $u", sides.widths[i], apart, .01f)
        }
        // At u = .5 it swells to 1.24, and at .97 it is lifting (≈ .48), as drawn §2.6 step 2 computes.
        assertEquals(1.24f * width, sides.widths[nearest(line, .5f * line.length)], .02f)
        assertEquals(.477f * width, sides.widths[nearest(line, .97f * line.length)], .25f)
        // It lifts to .12 at its end.
        assertEquals(.12f * width, sides.widths[line.size - 1], .001f)
    }

    @Test fun dotsAreMaxFourOrRoundOfLengthOverPitchEvenlySpaced() {
        val r = Ribbon.radii(Corners.Round(22f), w, h, 1f)
        val outline = Ribbon.roundLoop(w, h, r, 0f, overshoot = 0f, drift = 0f, step = 1f)
        val l = outline.length
        assertEquals(Ribbon.perimeter(w, h, r), l, .5f)
        for (pitch in listOf(7f, 6f, 9.5f)) {
            val n = (l / pitch).roundToInt()
            assertEquals(n, Ribbon.dotCount(l, pitch))
            val d = Ribbon.dotDistances(l, pitch)
            assertEquals(n, d.size)
            assertEquals(.5f * l / n, d[0], .001f)
            for (i in 1 until n) assertEquals("pitch $pitch gap $i", l / n, d[i] - d[i - 1], .001f)
            // The last gap, round the end back to the first dot, is the same: no short gap.
            assertEquals(l / n, l - d[n - 1] + d[0], .001f)
            val dots = Ribbon.dots(outline, pitch)
            assertEquals(n * 2, dots.size)
            for (i in 0 until n) {
                val p = outline.at(d[i])
                assertEquals(p.x, dots[2 * i], .001f)
                assertEquals(p.y, dots[2 * i + 1], .001f)
            }
        }
        // A tiny outline still gets four.
        assertEquals(4, Ribbon.dotCount(10f, 7f))
        assertEquals(4, Ribbon.dotDistances(10f, 7f).size)
    }

    @Test fun theOpenRibbonLandsThinAfterTheGapAndLiftsBeforeIt() {
        val r = Ribbon.radii(Corners.Round(14f), w, h, 1f)
        val outline = Ribbon.roundLoop(w, h, r, 0f, overshoot = 0f, drift = 0f, step = 1f)
        val l = outline.length
        // A title gap from s = 20 to s = 90 along the top edge: the ribbon runs from 90 round to 20 (wrapping past the end).
        val width = 8f
        val sides = Ribbon.openSides(outline, fromS = 90f, toS = 20f, width = width, dp = 1f)
        val line = sides.line
        assertEquals(l - 70f, line.length, 1f)
        val first = outline.at(90f)
        val last = outline.at(20f)
        assertEquals(first.x, line.x[0], .01f)
        assertEquals(first.y, line.y[0], .01f)
        assertEquals(last.x, line.x[line.size - 1], .3f)
        assertEquals(last.y, line.y[line.size - 1], .3f)
        // Thin where it lands (the gap's end), full in the middle, lifted where it ends (the gap's start).
        assertEquals(.32f * width, sides.widths[0], .001f)
        assertTrue(sides.widths[line.size / 2] > width)
        assertEquals(.12f * width, sides.widths[line.size - 1], .001f)
        // Its outline is one closed ring.
        assertEquals((line.size * 2 + 14) * 2, Ribbon.open(outline, 90f, 20f, width, 1f).size)
    }

    @Test fun cornersResolveToRadiiAsTheShapesClampThem() {
        val pill = Ribbon.radii(Corners.Pill, 200f, 40f, 1f)
        assertEquals(20f, pill.ts, 0f)
        val round = Ribbon.radii(Corners.Round(30f, 8f, 30f, 8f), 200f, 40f, 2f)
        // 30 dp at 2 px/dp is 60 px, clamped to half of the 40 px side.
        assertEquals(20f, round.ts, 0f)
        assertEquals(16f, round.te, 0f)
        val leaf = Ribbon.radii(Corners.Leaf(22f, Corner.TopStart, 6f), 200f, 100f, 1f)
        assertEquals(6f, leaf.ts, 0f)
        assertEquals(22f, leaf.be, 0f)
        assertEquals(15f, Ribbon.radii(Corners.Percent(30), 200f, 50f, 1f).bs, .001f)
        assertTrue(Ribbon.radii(Corners.Cut(4f, 0f, 4f, 0f), 50f, 50f, 1f).cut)
    }

    /** The shortest distance from ([x], [y]) to [line]'s points. */
    private fun distanceTo(line: Polyline, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until line.size) best = min(best, hypot(line.x[i] - x, line.y[i] - y))
        return best
    }

    private fun nearest(line: Polyline, s: Float): Int {
        var best = 0
        for (i in 0 until line.size) if (abs(line.s[i] - s) < abs(line.s[best] - s)) best = i
        return best
    }
}
