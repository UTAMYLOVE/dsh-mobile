package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.util.lerp
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shared sakura outline (the exit iris, the window's mon): five petals with a notch at each tip, as a polar curve of 360 points,
 * petal 0 pointing up, in unit space. The valley between petals sits exactly at radius .70 (the iris uses that to know when the
 * whole screen is open); the tips reach .99 with the notch at .88.
 */
fun sakuraSilhouette(): Path = Path().apply {
    for (i in 0 until 360) {
        val r = sakuraRadius(i)
        val th = Math.toRadians(i.toDouble())
        val x = (r * cos(th)).toFloat()
        val y = (r * sin(th)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The silhouette's radius at [deg] degrees (0 = 3 o'clock, clockwise, y down; petal 0 is at 270, straight up). */
internal fun sakuraRadius(deg: Int): Float {
    val phi = ((deg + 126) % 72) - 36f // degrees from the nearest petal centre
    return .70f + .30f * cos(Math.toRadians(phi * 2.5)).toFloat() - .12f * max(0f, 1f - abs(phi) / 6f)
}

/** One rosette petal pointing up, base at .32 and notched tip at .60, in unit space (the base-left corner first, for draw-on). */
private fun rosettePetal(rotateDeg: Float): Path {
    val p = Path().apply {
        moveTo(-0.085f, -0.33f)
        cubicTo(-0.19f, -0.42f, -0.13f, -0.56f, -0.045f, -0.60f)
        lineTo(0f, -0.565f)
        lineTo(0.045f, -0.60f)
        cubicTo(0.13f, -0.56f, 0.19f, -0.42f, 0.085f, -0.33f)
        quadraticTo(0f, -0.31f, -0.085f, -0.33f)
        close()
    }
    if (rotateDeg != 0f) p.transform(Matrix().apply { rotateZ(rotateDeg) })
    return p
}

/** A tiny fixed sequence instead of Random: the same run always draws the same picture (and tests can rely on it). */
internal fun ih(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777216f
}

private fun rad(deg: Float) = deg * (PI.toFloat() / 180f)

/**
 * Everything of 「櫻紋陣」 that is geometry, built once per size in unit space (R = 1, centre at the origin, y down) and drawn under
 * one transform, so the same paths serve the upright sigil and the floor circle lying flat. Strokes are in unit space too (a dp
 * width divided by R), which is why they are built here and not per frame.
 */
internal class SigilKit(val r: Float, private val dp: Float, burstCount: Int) {
    private fun w(dpW: Float) = dpW * dp / r

    val petals: Array<Path> = Array(5) { rosettePetal(it * 72f) }
    // Compose's PathMeasure only measures a path's first contour, so each petal keeps its own.
    val petalMeasures: Array<PathMeasure> = Array(5) { i -> PathMeasure().apply { setPath(petals[i], false) } }
    val petalLength: Float = petalMeasures[0].length

    val ringA = Stroke(w(2f))
    val ringAInner = Stroke(w(1f))
    val ringAGlow = Stroke(w(5f))
    val ringB = Stroke(w(.75f))
    val ringC = Stroke(w(1.25f), cap = StrokeCap.Round)
    val windowRing = Stroke(w(2f))
    val windowOuter = Stroke(w(1f))
    val petalLine = Stroke(w(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(w(.75f))
    val tickMinorStroke = Stroke(w(1f))
    val tickMajorStroke = Stroke(w(1.25f))
    val pingStroke = Stroke(w(1.5f))
    val shock1 = Stroke(w(2f))
    val shock2 = Stroke(w(1.5f))
    val glintStroke = Stroke(w(2f), cap = StrokeCap.Round)

    /** 72 ticks: minor .82 → .86, every sixth (30°) .79 → .86. Two paths, one per weight. */
    val ticksMinor = Path()
    val ticksMajor = Path()
    /** 8 spokes .62 → .79 at 22.5° + k·45°. */
    val spokes = Array(8) { Path() }
    val spokeEnds = Array(8) { Offset.Zero }

    init {
        for (i in 0 until 72) {
            val a = rad(i * 5f - 90f)
            val major = i % 6 == 0
            val from = if (major) .79f else .82f
            val path = if (major) ticksMajor else ticksMinor
            path.moveTo(cos(a) * from, sin(a) * from)
            path.lineTo(cos(a) * .86f, sin(a) * .86f)
        }
        for (k in 0 until 8) {
            val a = rad(22.5f + k * 45f - 90f)
            spokes[k].moveTo(cos(a) * .62f, sin(a) * .62f)
            spokes[k].lineTo(cos(a) * .79f, sin(a) * .79f)
            spokeEnds[k] = Offset(cos(a) * .79f, sin(a) * .79f)
        }
    }

    val spokeDot = 2.5f * dp / r
    val spokeDotRing = Stroke(w(.75f))

    /** A diamond with an 8 dp diagonal, at the origin. */
    val diamond = Path().apply {
        val d = 4f * dp / r
        moveTo(0f, -d); lineTo(d, 0f); lineTo(0f, d); lineTo(-d, 0f); close()
    }
    val diamondAngles = floatArrayOf(45f, 135f, 225f, 315f)

    /** 16 light wedges from the centre out to r 2.7, slightly uneven (by the hash), as one path. */
    val rays = Path().apply {
        for (i in 0 until 16) {
            val h = ih(i, 31)
            val mid = i * 22.5f + 7f * (h - .5f)
            val half = 1.2f + 2.4f * h
            moveTo(0f, 0f)
            lineTo(cos(rad(mid - half)) * 2.7f, sin(rad(mid - half)) * 2.7f)
            lineTo(cos(rad(mid + half)) * 2.7f, sin(rad(mid + half)) * 2.7f)
            close()
        }
    }
    val rayBrush = Brush.radialGradient(
        0f to Color.White.copy(alpha = 0f), .36f to Color.White.copy(alpha = 0f), .44f to Color.White.copy(alpha = .24f),
        .70f to Color.White.copy(alpha = .10f), 1f to Color.White.copy(alpha = 0f), center = Offset.Zero, radius = 2.7f,
    )

    /** The hit's speed lines (集中線): thin triangles from r 1.08 out to about 2. */
    val burst = Path().apply {
        val hw = 1.6f * dp / r
        for (i in 0 until burstCount) {
            val h = ih(i, 41)
            val a = rad(i * (360f / burstCount) + 4f * (h - .5f))
            val dx = cos(a); val dy = sin(a)
            val apex = 1.9f + .35f * h
            moveTo(dx * 1.08f - dy * hw, dy * 1.08f + dx * hw)
            lineTo(dx * apex, dy * apex)
            lineTo(dx * 1.08f + dy * hw, dy * 1.08f - dx * hw)
            close()
        }
    }

    /** The three ribbons that whip round at the hit, each with its own measure for the moving, tapered window. */
    val ribbons: Array<Path> = arrayOf(
        Path().apply { moveTo(-1.55f, .80f); cubicTo(-.40f, 1.60f, .50f, -1.60f, 1.55f, -.70f) },
        Path().apply { moveTo(1.55f, .50f); cubicTo(.65f, 1.55f, -.95f, -1.35f, -1.55f, -.30f) },
        Path().apply { moveTo(-1.25f, -1.05f); cubicTo(.25f, -1.65f, 1.35f, .25f, .55f, 1.45f) },
    )
    val ribbonMeasures: Array<PathMeasure> = Array(3) { i -> PathMeasure().apply { setPath(ribbons[i], false) } }
    val ribbonColors = arrayOf(Sakura.Primary.copy(alpha = .92f), Sakura.White.copy(alpha = .95f), Sakura.Primary.copy(alpha = .70f))
    val ribbonCores = booleanArrayOf(true, false, true)
    private val ribbonWidths = floatArrayOf(7f, 5f, 4f)
    val ribbonStrokes: Array<Array<Stroke>> = Array(3) { i ->
        arrayOf(.35f, .70f, 1f).map { Stroke(w(ribbonWidths[i] * it), cap = StrokeCap.Round) }.toTypedArray()
    }
    val ribbonCore = Stroke(w(1f), cap = StrokeCap.Round)

    /** A unit circle, for clipping to ring A's disc (scaled by the transform). */
    val unitDisc = Path().apply { addOval(Rect(-1f, -1f, 1f, 1f)) }

    /** Scratch paths reused every frame (getSegment, wedge clips), so a frame allocates no paths. */
    val scratch = Path()
    val scratch2 = Path()
}

/** Where the sigil is and how it is shaped at [ms]: upright at C, then lying down as the floor circle. */
internal class SigilPose {
    var cx = 0f; var cy = 0f; var sx = 1f; var sy = 1f; var floor = 0f; var lines = 1f; var disc = 0f
}

internal fun IntroScene.pose(ms: Float, amb: Float, out: SigilPose) {
    val m = marks
    val p = m.pace
    val k = seg(ms, m.hit + 80f * p, 840f * p, IntroEase.EmphDecel)
    val pulse = sigilScale(ms, m)
    out.cx = lerp(lay.c.x, lay.cf.x, k)
    out.cy = lerp(lay.c.y, lay.cf.y, k)
    out.sx = lerp(1f, lay.rx / lay.r, k) * pulse
    out.sy = lerp(1f, lay.ry / lay.r, k) * pulse
    out.floor = k
    out.lines = lerp(1f, if (backdrop) .85f else .75f, k)
    out.disc = 6f * max(0f, amb - m.hit / 1000f) * k
}

/** How far the inhale (c → hit) has gone, 0..1, eased in; it relaxes again over the hit's first 300 ms. */
internal fun IntroScene.inhale(ms: Float): Float {
    val m = marks
    return if (ms < m.hit) seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn)
    else 1f - seg(ms, m.hit, 300f * m.pace, IntroEase.QuintOut)
}

/** The sigil itself (rings, ticks, data ring, rosette, spokes, diamonds), drawn upright or as the floor circle. */
internal fun DrawScope.drawSigil(sc: IntroScene, ms: Float, amb: Float, pose: SigilPose, ring: GraphicsLayer?) {
    val k = sc.kit
    val m = sc.marks
    val p = m.pace
    val R = sc.lay.r
    val la = pose.lines
    val inh = sc.inhale(ms)
    val kA = seg(ms, m.a, 560f * p, IntroEase.CubicInOut)
    val kB = seg(ms, m.a + 60f * p, 560f * p, IntroEase.CubicInOut)
    if (kA <= 0f) return
    val unitBox = Offset(-1f, -1f)
    withTransform({
        translate(pose.cx, pose.cy)
        scale(pose.sx * R, pose.sy * R, Offset.Zero)
        rotate(pose.disc, Offset.Zero)
    }) {
        // Ring A's white under-glow right after the hit.
        val glow = .5f * (1f - seg(ms, m.hit, 500f, IntroEase.QuintOut)) * (if (ms >= m.hit) 1f else 0f)
        if (glow > 0f) drawCircle(Sakura.White.copy(alpha = glow), 1f, Offset.Zero, style = k.ringAGlow)
        // Ring A and its inner white line, drawn by the comet.
        drawArc(Sakura.RoseBrown.copy(alpha = .80f * la), -90f, 360f * kA, false, unitBox, Size(2f, 2f), style = k.ringA)
        drawArc(Sakura.White.copy(alpha = .70f * la), -90f, 360f * kA, false, Offset(-.988f, -.988f), Size(1.976f, 1.976f), style = k.ringAInner)
        if (kB > 0f) drawArc(Sakura.RoseBrown.copy(alpha = .45f * la), -90f, 360f * kB, false, Offset(-.91f, -.91f), Size(1.82f, 1.82f), style = k.ringB)
        // Ticks, revealed behind the comet's head.
        if (kA >= 1f) {
            drawPath(k.ticksMinor, Sakura.RoseBrown.copy(alpha = .55f * la), style = k.tickMinorStroke)
            drawPath(k.ticksMajor, Sakura.RoseBrown.copy(alpha = .75f * la), style = k.tickMajorStroke)
        } else {
            val wedge = k.scratch
            wedge.reset()
            wedge.moveTo(0f, 0f)
            wedge.arcTo(Rect(-1.2f, -1.2f, 1.2f, 1.2f), -90f, 360f * kA, false)
            wedge.close()
            clipPath(wedge) {
                drawPath(k.ticksMinor, Sakura.RoseBrown.copy(alpha = .55f * la), style = k.tickMinorStroke)
                drawPath(k.ticksMajor, Sakura.RoseBrown.copy(alpha = .75f * la), style = k.tickMajorStroke)
            }
        }
        // The tick runner: one tick lit white, running round at 90°/s while the system half waits.
        if (kA >= 1f && pose.floor < 1f && !sc.reduced) {
            val i = ((amb * 90f / 5f).toInt() % 72 + 72) % 72
            val a = rad(i * 5f - 90f)
            val from = if (i % 6 == 0) .79f else .82f
            drawLine(Sakura.White.copy(alpha = .9f * (1f - pose.floor)), Offset(cos(a) * from, sin(a) * from), Offset(cos(a) * .86f, sin(a) * .86f), k.tickMajorStroke.width)
        }
        // The rosette group, on its uneven clock.
        val build = m.build
        if (ms >= build) {
            val g2 = stutter(amb) - 14f * inh
            rotate(g2, Offset.Zero) {
                val kC = seg(ms, build, 300f * p, IntroEase.CubicInOut)
                drawArc(Sakura.RoseBrown.copy(alpha = .60f * la), -90f, 360f * kC, false, Offset(-.62f, -.62f), Size(1.24f, 1.24f), style = k.ringC)
                for (i in 0 until 8) {
                    val t = seg(ms, build + (120f + 20f * i) * p, 160f * p)
                    if (t <= 0f) continue
                    drawPath(k.spokes[i], Sakura.RoseBrown.copy(alpha = .40f * t * la), style = k.thin)
                    val pop = IntroEase.BackOut.transform(t)
                    drawCircle(Sakura.White.copy(alpha = la), k.spokeDot * pop, k.spokeEnds[i])
                    drawCircle(Sakura.RoseBrown.copy(alpha = .8f * la * t), k.spokeDot * pop, k.spokeEnds[i], style = k.spokeDotRing)
                }
                for (i in 0 until 4) {
                    val t = IntroEase.BackOut.transform(seg(ms, build + (260f + 30f * i) * p, 180f * p))
                    if (t <= 0f) continue
                    val a = rad(k.diamondAngles[i] - 90f)
                    withTransform({ translate(cos(a) * .62f, sin(a) * .62f); rotate(k.diamondAngles[i], Offset.Zero); scale(t, t, Offset.Zero) }) {
                        drawPath(k.diamond, Sakura.Primary)
                        drawPath(k.diamond, Sakura.RoseBrown.copy(alpha = .60f), style = k.thin)
                    }
                }
                drawRosette(sc, ms, amb, la)
            }
        }
    }
    // The data ring: real data written round the circle, recorded once and turned as a whole.
    if (ring != null && ms >= m.build + 80f * p) {
        val a = .75f * seg(ms, m.build + 80f * p, 300f * p)
        val rot = -24f * (1f - seg(ms, m.build + 80f * p, 420f * p, IntroEase.ExpoOut)) + 5f * amb + 36f * seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn)
        val half = ring.size.width / 2f
        withTransform({
            translate(pose.cx, pose.cy)
            scale(pose.sx, pose.sy, Offset.Zero)
            rotate(rot + pose.disc, Offset.Zero)
            translate(-half, -half)
        }) {
            ring.alpha = a * la
            drawLayer(ring)
        }
    }
}

/** The five petals: outlines that draw themselves, a white fill that rises with them, then the pink flood at the hit. */
private fun DrawScope.drawRosette(sc: IntroScene, ms: Float, amb: Float, la: Float) {
    val k = sc.kit
    val m = sc.marks
    val p = m.pace
    // Fill colour: white .90 until the hit, pink .90 within 60 ms, settling to pink .60; then a slow breath.
    val d = ms - m.hit
    val pink = if (d < 0f) 0f else (d / 60f).coerceAtMost(1f)
    val settle = if (d < 60f) 0f else IntroEase.QuintOut.transform(((d - 60f) / 640f).coerceIn(0f, 1f))
    val breath = if (d > 700f) .05f * sin((amb - m.hit / 1000f) * (2f * PI.toFloat() / 1.6f)) else 0f
    val fillA = if (d < 0f) .90f else lerp(.90f, .60f, settle) + breath * settle
    val fill = lerpColor(Sakura.White, Sakura.Primary, pink)
    for (i in 0 until 5) {
        val t = seg(ms, m.build + (40f + 40f * i) * p, 240f * p, IntroEase.CubicOut)
        if (t <= 0f) continue
        drawPath(k.petals[i], fill.copy(alpha = fillA * t * t * la))
        if (t >= 1f) drawPath(k.petals[i], Sakura.RoseBrown.copy(alpha = .80f * la), style = k.petalLine)
        else {
            val seg = k.scratch2
            seg.reset()
            k.petalMeasures[i].getSegment(0f, k.petalLength * t, seg, true)
            drawPath(seg, Sakura.RoseBrown.copy(alpha = .80f * la), style = k.petalLine)
        }
    }
}

internal fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** The beat effects around the upright sigil: comet, ping, scan, converge ring, shock rings, speed lines and ribbons. */
internal fun DrawScope.drawSigilEffects(sc: IntroScene, ms: Float) {
    val k = sc.kit
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val R = L.r
    val c = L.c
    val dp = L.dp
    // The comet that draws ring A: an aqua head with a white core and glow, and a 50° white tail.
    val kA = seg(ms, m.a, 560f * p, IntroEase.CubicInOut)
    val headFade = 1f - seg(ms, m.a + 560f * p, 120f * p)
    if (ms >= m.a && headFade > 0f) {
        val deg = -90f + 360f * kA
        val hx = c.x + cos(rad(deg)) * R
        val hy = c.y + sin(rad(deg)) * R
        withTransform({ translate(c.x, c.y); rotate(deg, Offset.Zero) }) {
            drawArc(sc.cometTail, -50f, 50f, false, Offset(-R, -R), Size(2f * R, 2f * R), alpha = headFade * minOf(1f, kA * 6f), style = Stroke(2.5f * dp, cap = StrokeCap.Round))
        }
        withTransform({ translate(hx, hy) }) { drawCircle(sc.softGlow, 12f * dp, Offset.Zero, alpha = .5f * headFade) }
        drawCircle(Sakura.Aqua.copy(alpha = .95f * headFade), 3.5f * dp, Offset(hx, hy))
        drawCircle(Sakura.White.copy(alpha = headFade), 1.6f * dp, Offset(hx, hy))
    }
    // The ring-close glint at the top.
    val gl = seg(ms, m.a + 560f * p, 200f * p, IntroEase.QuintOut)
    if (gl > 0f && gl < 1f) drawArc(Sakura.White.copy(alpha = .6f * (1f - gl)), -105f, 30f, false, Offset(c.x - R, c.y - R), Size(2 * R, 2 * R), style = Stroke(3f * dp, cap = StrokeCap.Round))
    // The access ping, once.
    val pg = seg(ms, m.a + 80f * p, 640f * p, IntroEase.QuintOut)
    if (pg > 0f && pg < 1f) drawCircle(Sakura.Aqua.copy(alpha = .55f * (1f - pg)), R * lerp(.30f, 1.25f, pg), c, style = Stroke(1.5f * dp))
    // The scan: a white line across ring A's chord with a white then aqua trail above it, end ticks and a tick on the ruler.
    val sk = seg(ms, m.scanStart, m.scanMs, IntroEase.CubicInOut)
    if (ms >= m.scanStart && sk < 1f) {
        val y = c.y - R + 2f * R * sk
        val half = sqrt(max(0f, R * R - (y - c.y) * (y - c.y)))
        clipPath(sc.ringDisc) {
            withTransform({ translate(0f, y) }) {
                drawRect(sc.scanWhite, Offset(c.x - R, -44f * dp), Size(2f * R, 44f * dp))
                drawRect(sc.scanAqua, Offset(c.x - R, -18f * dp), Size(2f * R, 18f * dp))
            }
        }
        drawLine(Sakura.White.copy(alpha = .95f), Offset(c.x - half, y), Offset(c.x + half, y), 1f * dp)
        drawLine(Sakura.RoseBrown.copy(alpha = .8f), Offset(c.x - half, y - 3f * dp), Offset(c.x - half, y + 3f * dp), 1f * dp)
        drawLine(Sakura.RoseBrown.copy(alpha = .8f), Offset(c.x + half, y - 3f * dp), Offset(c.x + half, y + 3f * dp), 1f * dp)
        if (L.ruler) drawLine(Sakura.Primary, Offset(L.rulerX - 4f * dp, y), Offset(L.rulerX + 4f * dp, y), 1.5f * dp)
    }
    // The converge ring, pulled in during the inhale.
    if (ms >= m.c && ms < m.hit) {
        val t = seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn)
        drawCircle(Sakura.Primary.copy(alpha = .6f * t), R * lerp(1.35f, 1f, t), c, style = Stroke(1.5f * dp))
    }
    if (ms < m.hit) return
    // Shock rings.
    val s1 = seg(ms, m.hit, 520f * p, IntroEase.QuintOut)
    if (s1 < 1f) drawCircle(Sakura.White.copy(alpha = .7f * (1f - s1)), R * lerp(1f, 1.7f, s1), c, style = Stroke(2f * dp))
    val s2 = seg(ms, m.hit + 60f * p, 480f * p, IntroEase.QuintOut)
    if (ms >= m.hit + 60f * p && s2 < 1f) drawCircle(Sakura.Primary.copy(alpha = .5f * (1f - s2)), R * lerp(1f, 1.45f, s2), c, style = Stroke(1.5f * dp))
    // Speed lines.
    if (sc.level != EffectLevel.SAVER) {
        val b = seg(ms, m.hit, 380f * p, IntroEase.QuintOut)
        if (b < 1f) withTransform({ translate(c.x, c.y); scale(R * lerp(1f, 1.45f, b), R * lerp(1f, 1.45f, b), Offset.Zero) }) {
            drawPath(k.burst, Sakura.White.copy(alpha = .6f * (1f - b)))
        }
    }
    // Ribbons: a tapered window running along each arc.
    if (sc.level != EffectLevel.SAVER) {
        withTransform({ translate(c.x, c.y); scale(R, R, Offset.Zero) }) {
            for (i in 0 until 3) {
                val h = 1.5f * seg(ms, m.hit + 70f * i * p, 640f * p, IntroEase.CubicInOut)
                if (ms < m.hit + 70f * i * p || h >= 1.5f) continue
                val a = (h - .5f).coerceIn(0f, 1f)
                val bb = h.coerceIn(0f, 1f)
                val len = bb - a
                if (len <= .002f) continue
                val total = k.ribbonMeasures[i].length
                val color = k.ribbonColors[i]
                ribbonSpan(k, i, a * total, bb * total, color, k.ribbonStrokes[i][0])
                ribbonSpan(k, i, (a + .15f * len) * total, (bb - .10f * len) * total, color, k.ribbonStrokes[i][1])
                ribbonSpan(k, i, (a + .35f * len) * total, (bb - .25f * len) * total, color, k.ribbonStrokes[i][2])
                if (k.ribbonCores[i]) ribbonSpan(k, i, (a + .35f * len) * total, (bb - .25f * len) * total, Sakura.White.copy(alpha = .85f), k.ribbonCore)
            }
        }
    }
}

private fun DrawScope.ribbonSpan(k: SigilKit, i: Int, from: Float, to: Float, color: Color, stroke: Stroke) {
    if (to <= from) return
    val s = k.scratch2
    s.reset()
    k.ribbonMeasures[i].getSegment(from, to, s, true)
    drawPath(s, color, style = stroke)
}

/**
 * The window in the middle of the sigil (you: your picture or your monogram), then, without a picture, the crest it floats up to
 * in the welcome, with its halo and two orbiting sparkles.
 */
internal fun DrawScope.drawWindow(sc: IntroScene, ms: Float, amb: Float) {
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    val R = L.r
    val appear = seg(ms, m.build + 40f * p, 260f * p, IntroEase.CubicOut)
    if (appear <= 0f) return
    val pulse = sigilScale(ms, m)
    // Without a backdrop the window floats up to the crest; with one, it fades as the picture bursts open from it.
    val kw = if (sc.backdrop || !L.crest) 0f else seg(ms, m.hit + 80f * p, 640f * p, IntroEase.EmphDecel)
    val gone = if (sc.backdrop) seg(ms, m.hit + 30f * p, 200f * p) else if (!L.crest) seg(ms, m.hit + 80f * p, 400f * p) else 0f
    val alpha = 1f - gone
    if (alpha <= 0f) return
    val bob = if (kw > 0f) 4f * dp * sin(amb * (2f * PI.toFloat() / 3.2f)) * kw else 0f
    val cx = lerp(L.c.x, L.crestC.x, kw)
    val cy = lerp(L.c.y, L.crestC.y, kw) + bob
    val rw = lerp(.30f * R * pulse, L.crestR, kw)
    val center = Offset(cx, cy)
    // Plate and content, clipped to the window.
    val clip = sc.kit.scratch
    clip.reset()
    clip.addOval(Rect(center, rw - .75f * dp))
    val ghost = .18f * seg(ms, m.build + 40f * p, 200f * p)
    val sk = seg(ms, m.scanStart, m.scanMs, IntroEase.CubicInOut)
    val scanY = if (ms < m.scanStart) Float.NEGATIVE_INFINITY else L.c.y - R + 2f * R * sk
    clipPath(clip) {
        if (sc.windowPicture) {
            sc.drawPictureAt(this, sc.windowPlacement, ghost * alpha)
            if (scanY > -1e6f) clipRect(top = 0f, bottom = scanY) { sc.drawPictureAt(this, sc.windowPlacement, alpha) }
        } else {
            drawCircle(Sakura.MilkTea.copy(alpha = .22f * alpha * appear), rw, center)
            val mono = lerp(pulse, (68f / 52f) * (L.crestR / (66f * L.s * dp)), kw)
            sc.drawMonogram(this, center, mono, ghost * alpha, if (kw > 0f) Float.POSITIVE_INFINITY else scanY, alpha)
        }
        // Scanlines inside the window while scanning.
        if (ms >= m.scanStart && sk < 1f) {
            var y = cy - rw
            while (y < cy + rw) { drawLine(Sakura.White.copy(alpha = .10f), Offset(cx - rw, y), Offset(cx + rw, y), 1f); y += 3f * dp }
        }
    }
    // Window rings, drawing themselves.
    drawArc(Sakura.White.copy(alpha = alpha), -90f, 360f * appear, false, Offset(cx - rw, cy - rw), Size(2 * rw, 2 * rw), style = Stroke(2f * dp))
    val ro = rw * (.335f / .30f)
    drawArc(Sakura.RoseBrown.copy(alpha = .60f * alpha), -90f, 360f * appear, false, Offset(cx - ro, cy - ro), Size(2 * ro, 2 * ro), style = Stroke(1f * dp))
    // The crest halo and its orbit sparkles.
    if (kw > 0f) {
        val hk = seg(ms, m.hit + 500f * p, 360f * p, IntroEase.CubicOut)
        if (hk > 0f) {
            val hr = L.crestR + 10f * dp
            drawArc(Sakura.RoseBrown.copy(alpha = .6f), -90f, 360f * hk, false, Offset(cx - hr, cy - hr), Size(2 * hr, 2 * hr), style = Stroke(1f * dp))
            val ticks = (24 * hk).toInt()
            for (i in 0 until ticks) {
                val a = rad(i * 15f - 90f)
                drawLine(Sakura.RoseBrown.copy(alpha = .6f), Offset(cx + cos(a) * (L.crestR + 14f * dp), cy + sin(a) * (L.crestR + 14f * dp)), Offset(cx + cos(a) * (L.crestR + 19f * dp), cy + sin(a) * (L.crestR + 19f * dp)), 1f * dp)
            }
            val orbit = L.crestR + 26f * dp
            val spin = 24f * max(0f, amb - (m.hit + 500f * p) / 1000f)
            for (j in 0 until 2) {
                val a = rad(-60f + 180f * j + spin)
                sc.drawSparkle(this, Offset(cx + cos(a) * orbit, cy + sin(a) * orbit), (if (j == 0) 7f else 5f) * dp / 2f, if (j == 0) Sakura.Primary else Sakura.Aqua, hk)
            }
        }
    }
}
