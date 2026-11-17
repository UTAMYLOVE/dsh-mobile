package app.dshmobile.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The one-stroke geometry FrameNode draws (drawn §2.6, from the approved mock's generator theme-mocks/onestroke/kit.js:52-147):
// brush ribbons round a frame's outline, the open ribbon war frames draw past a title gap (war D5), and dots along an outline.
// Everything here but the last few Path adapters is plain arithmetic on float arrays, so it is tested on the JVM (RibbonTest).

/** Points (px) along a line and their arc lengths [s] from the first point. */
internal class Polyline(val x: FloatArray, val y: FloatArray) {
    init { require(x.size == y.size) }

    val size: Int get() = x.size

    val s: FloatArray = FloatArray(x.size).also { s -> for (i in 1 until x.size) s[i] = s[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1]) }

    val length: Float get() = if (s.isEmpty()) 0f else s[s.size - 1]

    /** The point at arc length [d] (clamped to the line), linearly between the two points around it. */
    fun at(d: Float): Offset {
        if (size == 0) return Offset.Zero
        if (d <= 0f || size == 1) return Offset(x[0], y[0])
        if (d >= length) return Offset(x[size - 1], y[size - 1])
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (s[m] <= d) lo = m else hi = m
        }
        val span = s[hi] - s[lo]
        val t = if (span > 0f) (d - s[lo]) / span else 0f
        return Offset(x[lo] + (x[hi] - x[lo]) * t, y[lo] + (y[hi] - y[lo]) * t)
    }

    /** The part from arc length [from] to [to]; a closed line ([closed]) wraps past its end when [to] < [from]. */
    fun slice(from: Float, to: Float, step: Float, closed: Boolean): Polyline {
        val l = length
        if (l <= 0f) return this
        val a = if (closed) mod(from, l) else from.coerceIn(0f, l)
        var b = if (closed) mod(to, l) else to.coerceIn(0f, l)
        if (closed && b <= a) b += l
        val span = b - a
        val n = max(1, ceil(span / step).toInt())
        val out = Trace(step)
        for (i in 0..n) {
            val d = a + span * i / n
            val p = at(if (closed) mod(d, l) else d)
            if (i == 0) out.move(p.x, p.y) else out.point(p.x, p.y)
        }
        return out.build()
    }

    /** The same line with x and/or y mirrored in a [w] × [h] box. */
    fun mirrored(w: Float, h: Float, mx: Boolean, my: Boolean): Polyline {
        if (!mx && !my) return this
        return Polyline(FloatArray(size) { if (mx) w - x[it] else x[it] }, FloatArray(size) { if (my) h - y[it] else y[it] })
    }
}

private fun mod(v: Float, m: Float): Float = ((v % m) + m) % m

private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

/** kit.js's `smooth`: 3u² − 2u³. */
internal fun smooth01(u: Float): Float = u * u * (3f - 2f * u)

/** A growing list of points, the counterpart of kit.js's path builder. */
private class Trace(private val step: Float) {
    private var xs = FloatArray(64)
    private var ys = FloatArray(64)
    private var n = 0

    val lastX: Float get() = xs[n - 1]
    val lastY: Float get() = ys[n - 1]

    fun point(x: Float, y: Float) {
        if (n == xs.size) {
            xs = xs.copyOf(n * 2)
            ys = ys.copyOf(n * 2)
        }
        xs[n] = x
        ys[n] = y
        n++
    }

    fun move(x: Float, y: Float) = point(x, y)

    fun line(x: Float, y: Float) {
        val x0 = lastX
        val y0 = lastY
        val k = max(1, ceil(hypot(x - x0, y - y0) / step).toInt())
        for (i in 1..k) point(x0 + (x - x0) * i / k, y0 + (y - y0) * i / k)
    }

    /** An arc of radius [r] round ([cx], [cy]) from [a0] to [a1] degrees (screen angles: 90 is down). */
    fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float) {
        val k = max(3, ceil(abs(a1 - a0) * PI.toFloat() / 180f * r / step).toInt())
        for (i in 1..k) {
            val a = Math.toRadians((a0 + (a1 - a0) * i / k).toDouble())
            point(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }
    }

    fun quad(cx: Float, cy: Float, x: Float, y: Float) {
        val x0 = lastX
        val y0 = lastY
        val k = 24
        for (i in 1..k) {
            val t = i.toFloat() / k
            val u = 1f - t
            point(u * u * x0 + 2 * u * t * cx + t * t * x, u * u * y0 + 2 * u * t * cy + t * t * y)
        }
    }

    fun build(): Polyline = Polyline(xs.copyOf(n), ys.copyOf(n))
}

/** kit.js's `resample`: points every [step] of arc length (the last point kept). */
internal fun Polyline.resampled(step: Float): Polyline {
    if (size < 2 || length <= 0f) return this
    val n = max(1, ceil(length / step).toInt())
    val xs = FloatArray(n + 1)
    val ys = FloatArray(n + 1)
    for (i in 0..n) {
        val p = at(length * i / n)
        xs[i] = p.x
        ys[i] = p.y
    }
    return Polyline(xs, ys)
}

/**
 * A brush's width along its line, as a fraction of the brush's full width: [u] = s / L (0..1), [s] and [length] in dp. Frame
 * ribbons use [Frame]; short strokes (rules, a code block's brush bar) use [Rule].
 */
internal fun interface RibbonProfile {
    fun at(u: Float, s: Float, length: Float): Float

    companion object {
        /** drawn §2.6 step 2 (kit.js framePressure): lands thin over 26 dp, swells by .24 round the frame, lifts to .12 over the last 7 %. */
        val Frame: RibbonProfile = RibbonProfile { u, s, _ ->
            val land = .32f + .68f * smooth01(clamp01(s / 26f))
            val body = 1f + .24f * sin(PI.toFloat() * u)
            val lift = 1f - .88f * smooth01(clamp01((u - .93f) / .07f))
            land * body * lift
        }

        /** drawn §2.7's brush rule: thin in, full, thin out (.1 + .9 sin πu). */
        val Rule: RibbonProfile = RibbonProfile { u, _, _ -> .1f + .9f * sin(PI.toFloat() * clamp01(u)) }
    }
}

/** The two sides of a ribbon round a centre line: [left] and [right] (x, y pairs) and the full [widths] (px) at each point. */
internal class RibbonSides(val left: FloatArray, val right: FloatArray, val widths: FloatArray, val line: Polyline)

/** A frame's corner radii in px (logical: start/end as in an LTR layout) and whether its corners are cut (straight chamfers). */
internal class Radii(val ts: Float, val te: Float, val be: Float, val bs: Float, val cut: Boolean = false) {
    fun of(c: Corner): Float = when (c) {
        Corner.TopStart -> ts
        Corner.TopEnd -> te
        Corner.BottomEnd -> be
        Corner.BottomStart -> bs
    }
}

internal object Ribbon {
    /** Points along a ribbon's centre line, every this many dp (drawn §2.6 step 1). */
    const val STEP_DP = 1f
    /** How far the overshoot of a frame loop drifts outward (kit.js loopRect). */
    const val DRIFT_DP = 1.6f
    /** How far a round button's loop drifts outward (kit.js loopCircle). */
    const val CIRCLE_DRIFT_DP = 1.4f
    /** TopStartPlus22dp: the brush lands this far along the top edge past the top-start corner. */
    const val START_DP = 22f
    /** A flick's tail runs on this far past its tip (kit.js:139). */
    const val TAIL_DP = 4f
    /** The under-ink is this much wider than the ink (kit.js inkLine). */
    const val UNDER_SCALE = 2.3f
    /** Where a flick's quads leave the edges, and their control points (kit.js:135-147; Frame.kt's FlickShape). */
    private const val FLICK_REACH_DP = 16f
    private const val FLICK_CONTROL_DP = 3f

    /** [c]'s radii in a [w] × [h] px box at [dp] px per dp, clamped to half the shorter side as the shapes do. */
    fun radii(c: Corners, w: Float, h: Float, dp: Float): Radii {
        val half = max(0f, min(w, h) / 2f)
        fun r(v: Float) = v.coerceIn(0f, half)
        return when (c) {
            is Corners.Round -> Radii(r(c.ts * dp), r(c.te * dp), r(c.be * dp), r(c.bs * dp))
            is Corners.Percent -> r(min(w, h) * c.p / 100f).let { Radii(it, it, it, it) }
            is Corners.Cut -> Radii(r(c.ts * dp), r(c.te * dp), r(c.be * dp), r(c.bs * dp), cut = true)
            is Corners.Leaf -> {
                fun v(k: Corner) = r((if (k == c.leafCorner) c.leaf else c.round) * dp)
                Radii(v(Corner.TopStart), v(Corner.TopEnd), v(Corner.BottomEnd), v(Corner.BottomStart))
            }
            is Corners.Flick -> r(c.r * dp).let { Radii(it, it, it, it) }
            Corners.Pill -> Radii(half, half, half, half)
            Corners.Square -> Radii(0f, 0f, 0f, 0f)
        }
    }

    /** The perimeter of a rounded (or cut) rectangle. */
    fun perimeter(w: Float, h: Float, r: Radii): Float {
        val corner = if (r.cut) sqrt(2f) else PI.toFloat() / 2f
        val edges = (w - r.ts - r.te) + (h - r.te - r.be) + (w - r.be - r.bs) + (h - r.bs - r.ts)
        return max(0f, edges) + corner * (r.ts + r.te + r.be + r.bs)
    }

    /**
     * The point at arc length [s] round a rounded rectangle, clockwise from (r.ts, 0) on the top edge, and its outward normal,
     * written into [out] as x, y, nx, ny.
     */
    fun perimeterPoint(w: Float, h: Float, r: Radii, s: Float, out: FloatArray) {
        val p = perimeter(w, h, r)
        var d = if (p > 0f) mod(s, p) else 0f
        // Eight pieces: top, TE corner, right, BE corner, bottom, BS corner, left, TS corner.
        fun edge(len: Float, x0: Float, y0: Float, dx: Float, dy: Float, nx: Float, ny: Float): Boolean {
            if (d <= len) {
                out[0] = x0 + dx * d; out[1] = y0 + dy * d; out[2] = nx; out[3] = ny
                return true
            }
            d -= len
            return false
        }
        fun corner(radius: Float, cx: Float, cy: Float, a0: Float, fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean {
            if (radius <= 0f) return false
            val len = if (r.cut) radius * sqrt(2f) else radius * PI.toFloat() / 2f
            if (d <= len) {
                val t = d / len
                if (r.cut) {
                    out[0] = fromX + (toX - fromX) * t; out[1] = fromY + (toY - fromY) * t
                    val a = Math.toRadians((a0 + 45f).toDouble())
                    out[2] = cos(a).toFloat(); out[3] = sin(a).toFloat()
                } else {
                    val a = Math.toRadians((a0 + 90f * t).toDouble())
                    out[0] = cx + radius * cos(a).toFloat(); out[1] = cy + radius * sin(a).toFloat()
                    out[2] = cos(a).toFloat(); out[3] = sin(a).toFloat()
                }
                return true
            }
            d -= len
            return false
        }
        if (edge(max(0f, w - r.ts - r.te), r.ts, 0f, 1f, 0f, 0f, -1f)) return
        if (corner(r.te, w - r.te, r.te, -90f, w - r.te, 0f, w, r.te)) return
        if (edge(max(0f, h - r.te - r.be), w, r.te, 0f, 1f, 1f, 0f)) return
        if (corner(r.be, w - r.be, h - r.be, 0f, w, h - r.be, w - r.be, h)) return
        if (edge(max(0f, w - r.be - r.bs), w - r.be, h, -1f, 0f, 0f, 1f)) return
        if (corner(r.bs, r.bs, h - r.bs, 90f, r.bs, h, 0f, h - r.bs)) return
        if (edge(max(0f, h - r.bs - r.ts), 0f, h - r.bs, 0f, -1f, -1f, 0f)) return
        if (corner(r.ts, r.ts, r.ts, 180f, 0f, r.ts, r.ts, 0f)) return
        out[0] = r.ts; out[1] = 0f; out[2] = 0f; out[3] = -1f
    }

    /**
     * kit.js loopRect (drawn §2.6 step 1): a rounded rectangle run once round clockwise from arc length [startS], then on for
     * [overshoot] × the perimeter, drifting outward by smooth(clamp((s − P(1 − .2o)) / (1.2·o·P))) × [drift] along the normal.
     * Logical coordinates (an LTR box); a point every [step].
     */
    fun roundLoop(w: Float, h: Float, r: Radii, startS: Float, overshoot: Float, drift: Float, step: Float): Polyline {
        val p = perimeter(w, h, r)
        if (p <= 0f) return Polyline(floatArrayOf(0f), floatArrayOf(0f))
        val total = p * (1f + overshoot)
        val n = max(2, ceil(total / step).toInt())
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        val q = FloatArray(4)
        for (i in 0..n) {
            val s = total * i / n
            perimeterPoint(w, h, r, startS + s, q)
            val k = if (overshoot > 0f) smooth01(clamp01((s - p * (1f - overshoot * .2f)) / (p * overshoot * 1.2f))) * drift else 0f
            xs[i] = q[0] + q[2] * k
            ys[i] = q[1] + q[3] * k
        }
        return Polyline(xs, ys)
    }

    /**
     * kit.js loopCircle (drawn D2, BrushStart.Clock1): the ellipse in a [w] × [h] box from [startDeg] (−60: one o'clock) clockwise
     * through [sweepDeg] (384), drifting out by [drift] over the part past 340°.
     */
    fun circleLoop(w: Float, h: Float, drift: Float, step: Float, startDeg: Float = -60f, sweepDeg: Float = 384f): Polyline {
        val rx = w / 2f
        val ry = h / 2f
        val n = max(8, ceil(sweepDeg * PI.toFloat() / 180f * max(rx, ry) / step).toInt())
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        for (i in 0..n) {
            val deg = startDeg + sweepDeg * i / n
            val k = smooth01(clamp01((deg - startDeg - 340f) / (sweepDeg - 340f))) * drift
            val a = Math.toRadians(deg.toDouble())
            xs[i] = rx + (rx + k) * cos(a).toFloat()
            ys[i] = ry + (ry + k) * sin(a).toFloat()
        }
        return Polyline(xs, ys)
    }

    /**
     * kit.js bubbleUser (drawn D1): the flick bubble's stroke for its bottom-end corner in an LTR [w] × [h] box. It starts at the
     * tip T ([out], [down] beyond the corner), quads into the bottom edge, runs round the bubble, comes back down the tail side,
     * quads to T and on [tail] past it in the flick's direction. Every length in px.
     */
    fun flickLoop(w: Float, h: Float, r: Float, out: Float, down: Float, reach: Float, control: Float, tail: Float, step: Float): Polyline {
        val rr = r.coerceIn(0f, max(0f, min(w, h) / 2f))
        val re = reach.coerceAtMost(min(w, h) - rr).coerceAtLeast(0f)
        val co = control.coerceAtMost(re)
        val tx = w + out
        val ty = h + down
        val b = Trace(step / 2f)
        b.move(tx, ty)
        b.quad(w - co, h, w - re, h)
        b.line(rr, h)
        b.arc(rr, h - rr, rr, 90f, 180f)
        b.line(0f, rr)
        b.arc(rr, rr, rr, 180f, 270f)
        b.line(w - rr, 0f)
        b.arc(w - rr, rr, rr, 270f, 360f)
        b.line(w, h - re)
        b.quad(w, h - co, tx, ty)
        // On past the tip along the tangent there (from the last control point to T).
        val dx = tx - w
        val dy = ty - (h - co)
        val d = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        b.line(tx + dx / d * tail, ty + dy / d * tail)
        return b.build().resampled(step)
    }

    /**
     * A frame's brush loop in a [w] × [h] px box at [dp] px per dp (drawn §2.6 step 1): Clock1 runs the ellipse; a Flick runs from
     * its tail tip; every other outline runs from 22 dp past the top-start corner (TopStartPlus22dp) or from the middle of its
     * smallest corner (Tail). [rtl] mirrors it.
     */
    fun loop(corners: Corners, start: BrushStart, w: Float, h: Float, dp: Float, rtl: Boolean, overshoot: Float): Polyline {
        val step = STEP_DP * dp
        if (start == BrushStart.Clock1) return circleLoop(w, h, CIRCLE_DRIFT_DP * dp, step).mirrored(w, h, rtl, false)
        if (corners is Corners.Flick) {
            val line = flickLoop(
                w, h, corners.r * dp, corners.out * dp, corners.down * dp, FLICK_REACH_DP * dp, FLICK_CONTROL_DP * dp, TAIL_DP * dp, step,
            )
            val endSide = corners.corner == Corner.TopEnd || corners.corner == Corner.BottomEnd
            val top = corners.corner == Corner.TopStart || corners.corner == Corner.TopEnd
            return line.mirrored(w, h, endSide == rtl, top)
        }
        val r = radii(corners, w, h, dp)
        val p = perimeter(w, h, r)
        val startS = when (start) {
            BrushStart.TopStartPlus22dp -> min(START_DP * dp, max(0f, p - 1f))
            else -> tailStart(w, h, r)
        }
        return roundLoop(w, h, r, startS, overshoot, DRIFT_DP * dp, step).mirrored(w, h, rtl, false)
    }

    /** The arc length (from (r.ts, 0), clockwise) of the middle of the smallest corner: where a Tail brush lands on a plain outline. */
    private fun tailStart(w: Float, h: Float, r: Radii): Float {
        val k = if (r.cut) sqrt(2f) else PI.toFloat() / 2f
        val top = max(0f, w - r.ts - r.te)
        val right = max(0f, h - r.te - r.be)
        val bottom = max(0f, w - r.be - r.bs)
        val left = max(0f, h - r.bs - r.ts)
        val te = top + r.te * k / 2f
        val be = top + r.te * k + right + r.be * k / 2f
        val bs = top + r.te * k + right + r.be * k + bottom + r.bs * k / 2f
        val ts = top + r.te * k + right + r.be * k + bottom + r.bs * k + left + r.ts * k / 2f
        // Ties go to the bottom-end corner, the usual tail.
        val candidates = listOf(r.be to be, r.bs to bs, r.te to te, r.ts to ts)
        return candidates.minBy { it.first }.second
    }

    /**
     * kit.js brush (drawn §2.6 step 3): the left and right offsets of [line] at half the width [width] × [profile] (s in dp at [dp] px
     * per dp), with normals from the points two steps either side.
     */
    fun sides(line: Polyline, width: Float, dp: Float, profile: RibbonProfile): RibbonSides {
        val n = line.size
        val left = FloatArray(n * 2)
        val right = FloatArray(n * 2)
        val widths = FloatArray(n)
        val l = line.length
        val ldp = l / dp
        for (i in 0 until n) {
            val a = max(0, i - 2)
            val b = min(n - 1, i + 2)
            var dx = line.x[b] - line.x[a]
            var dy = line.y[b] - line.y[a]
            val d = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            dx /= d
            dy /= d
            val u = if (l > 0f) line.s[i] / l else 0f
            val wi = width * profile.at(u, line.s[i] / dp, ldp)
            widths[i] = wi
            val half = wi / 2f
            left[2 * i] = line.x[i] - dy * half
            left[2 * i + 1] = line.y[i] + dx * half
            right[2 * i] = line.x[i] + dy * half
            right[2 * i + 1] = line.y[i] - dx * half
        }
        return RibbonSides(left, right, widths, line)
    }

    /** The closed outline of [sides]: the left side forward, an 8-step round cap, the right side backward, an 8-step round cap (x, y pairs). */
    fun outline(sides: RibbonSides): FloatArray {
        val line = sides.line
        val n = line.size
        if (n < 2) return FloatArray(0)
        val out = FloatArray((n * 2 + 14) * 2)
        var k = 0
        fun put(x: Float, y: Float) { out[k++] = x; out[k++] = y }
        fun cap(i: Int, back: Boolean) {
            val a = max(0, i - 2)
            val b = min(n - 1, i + 2)
            val base = atan2(line.y[b] - line.y[a], line.x[b] - line.x[a]) + if (back) PI.toFloat() else 0f
            val half = sides.widths[i] / 2f
            for (j in 1 until 8) {
                val ang = base + PI.toFloat() / 2f - PI.toFloat() * j / 8f
                put(line.x[i] + cos(ang) * half, line.y[i] + sin(ang) * half)
            }
        }
        for (i in 0 until n) put(sides.left[2 * i], sides.left[2 * i + 1])
        cap(n - 1, false)
        for (i in n - 1 downTo 0) put(sides.right[2 * i], sides.right[2 * i + 1])
        cap(0, true)
        return out
    }

    /** How many dots go round an outline of [length]: max(4, round(L / pitch)). */
    fun dotCount(length: Float, pitch: Float): Int = if (pitch <= 0f) 4 else max(4, (length / pitch).roundToInt())

    /** Where the dots sit along an outline of [length] (drawn §2.6): (i + .5) · L / n, so the pitch is even and no gap is short. */
    fun dotDistances(length: Float, pitch: Float): FloatArray {
        val n = dotCount(length, pitch)
        return FloatArray(n) { (it + .5f) * length / n }
    }

    /** The dots of a closed [line] (x, y pairs). */
    fun dots(line: Polyline, pitch: Float): FloatArray {
        val d = dotDistances(line.length, pitch)
        val out = FloatArray(d.size * 2)
        for (i in d.indices) {
            val p = line.at(d[i])
            out[2 * i] = p.x
            out[2 * i + 1] = p.y
        }
        return out
    }

    /**
     * war D5: an open ribbon along the closed [outline] from arc length [fromS] round to [toS] (wrapping past the end), [width] px
     * at full pressure: it lands thin at [fromS] (a title gap's end) and lifts at [toS] (the gap's start). x, y pairs.
     */
    fun open(outline: Polyline, fromS: Float, toS: Float, width: Float, dp: Float, profile: RibbonProfile = RibbonProfile.Frame): FloatArray =
        outline(openSides(outline, fromS, toS, width, dp, profile))

    /** [open]'s two sides and widths (before the caps join them). */
    fun openSides(outline: Polyline, fromS: Float, toS: Float, width: Float, dp: Float, profile: RibbonProfile = RibbonProfile.Frame): RibbonSides =
        sides(outline.slice(fromS, toS, STEP_DP * dp, closed = true), width, dp, profile)
}

// ───── Path adapters (Android) ─────

/** A closed path through the (x, y) pairs of [ring]. */
internal fun ringPath(ring: FloatArray): Path = Path().apply {
    if (ring.size < 4) return@apply
    moveTo(ring[0], ring[1])
    var i = 2
    while (i + 1 < ring.size) {
        lineTo(ring[i], ring[i + 1])
        i += 2
    }
    close()
}

/** [path] sampled every [step] px of its length (the closing segment included when [closed]). */
internal fun Path.toPolyline(step: Float, closed: Boolean = true): Polyline {
    val measure = PathMeasure()
    measure.setPath(this, closed)
    val l = measure.length
    if (l <= 0f) return Polyline(floatArrayOf(0f), floatArrayOf(0f))
    val n = max(2, ceil(l / step).toInt())
    val xs = FloatArray(n + 1)
    val ys = FloatArray(n + 1)
    for (i in 0..n) {
        val p = measure.getPosition(l * i / n)
        xs[i] = p.x
        ys[i] = p.y
    }
    return Polyline(xs, ys)
}

/** The dots along [path] (drawn §2.6): [Ribbon.dotCount] of them at (i + .5) · L / n, measured on the path itself. */
internal fun dotsAlong(path: Path, pitch: Float): List<Offset> {
    val measure = PathMeasure()
    measure.setPath(path, true)
    val l = measure.length
    if (l <= 0f) return emptyList()
    return Ribbon.dotDistances(l, pitch).map { measure.getPosition(it) }
}

/**
 * war D5: the open ribbon along a war frame's closed [outline] from arc length [fromS] (px) round to [toS], [width] at full
 * pressure: it lands thin at the gap's end and lifts at its start. A filled path; build it once per size (in a draw cache).
 */
internal fun Density.ribbonOpen(outline: Path, fromS: Float, toS: Float, width: Dp, profile: RibbonProfile = RibbonProfile.Frame): Path {
    val dp = density
    return ringPath(Ribbon.open(outline.toPolyline(Ribbon.STEP_DP * dp / 2f), fromS, toS, width.toPx(), dp, profile))
}
