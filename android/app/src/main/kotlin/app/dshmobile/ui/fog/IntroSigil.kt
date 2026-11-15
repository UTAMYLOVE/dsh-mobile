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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

/**
 * One rosette petal pointing up, in unit space (the base-left corner first, for draw-on): a narrow claw at r .31, widest (half
 * .13) at r .50, a rounded top at r .64 with a V notch .045 deep. Taller than wide, so the five read as one sakura flower; the
 * old petal was wider than long and read as teeth or clam shells.
 */
private fun rosettePetal(rotateDeg: Float): Path {
    val p = Path().apply {
        moveTo(-0.035f, -0.31f)
        cubicTo(-0.07f, -0.37f, -0.13f, -0.42f, -0.13f, -0.50f)
        cubicTo(-0.13f, -0.585f, -0.075f, -0.645f, -0.0175f, -0.64f)
        lineTo(0f, -0.595f)
        lineTo(0.0175f, -0.64f)
        cubicTo(0.075f, -0.645f, 0.13f, -0.585f, 0.13f, -0.50f)
        cubicTo(0.13f, -0.42f, 0.07f, -0.37f, 0.035f, -0.31f)
        quadraticTo(0f, -0.30f, -0.035f, -0.31f)
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
internal class SigilKit(val r: Float, private val dp: Float, burstCount: Int, private val floorScale: Float) {
    private fun w(dpW: Float) = dpW * dp / r
    /** A stroke for the floor circle: [dpW] wide on screen on average once the circle is squashed to rx × ry. */
    private fun fw(dpW: Float) = w(dpW) / floorScale

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
    // The floor: a white core over a pink under-glow, so the lying circle reads as light, not as a print on a tablecloth.
    val floorCore = Stroke(fw(2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val floorGlow = Stroke(fw(6f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val floorRim = Stroke(fw(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** The petals' fill before the hit: white at the tips, pink at the base (a flat white read as five blobs). */
    val petalFill = Brush.radialGradient(
        0f to Sakura.Primary.copy(alpha = .55f), (.31f / .64f) to Sakura.Primary.copy(alpha = .55f), 1f to Sakura.White.copy(alpha = .95f),
        center = Offset.Zero, radius = .64f,
    )
    /** The flower's centre band (.30 → .345) that joins the five petals at their base. */
    val heart = Stroke(.045f)

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

    /** The hit's speed lines (集中線): pink triangles from r 1.08 out to about 2, each with a thin white core. */
    val burst = burstPath(burstCount, 1.6f * dp / r, 0f)
    val burstCore = burstPath(burstCount, .5f * dp / r, .08f)

    private fun burstPath(count: Int, hw: Float, inset: Float) = Path().apply {
        for (i in 0 until count) {
            val h = ih(i, 41)
            val a = rad(i * (360f / count) + 4f * (h - .5f))
            val dx = cos(a); val dy = sin(a)
            val base = 1.08f + inset
            val apex = 1.9f + .35f * h - inset
            moveTo(dx * base - dy * hw, dy * base + dx * hw)
            lineTo(dx * apex, dy * apex)
            lineTo(dx * base + dy * hw, dy * base - dx * hw)
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
    /** Each ribbon is one tapered fill (a stroke with a white core read as a hose), at these widths, with a 4 dp pink glow. */
    val ribbonWidths = floatArrayOf(w(10f), w(7f), w(5f))
    val ribbonGlow = w(4f)
    val ribbonGlowColor = Sakura.Primary.copy(alpha = .25f)
    /** Ribbon samples (unit space): position and normal along the visible window, reused every frame. */
    val ribbonX = FloatArray(RIBBON_SAMPLES + 1)
    val ribbonY = FloatArray(RIBBON_SAMPLES + 1)
    val ribbonNx = FloatArray(RIBBON_SAMPLES + 1)
    val ribbonNy = FloatArray(RIBBON_SAMPLES + 1)

    /** A unit circle, for clipping to ring A's disc (scaled by the transform). */
    val unitDisc = Path().apply { addOval(Rect(-1f, -1f, 1f, 1f)) }

    /** Scratch paths reused every frame (getSegment, wedge clips), so a frame allocates no paths. */
    val scratch = Path()
    val scratch2 = Path()

    companion object {
        const val RIBBON_SAMPLES = 24
    }
}

/** Where the sigil is and how it is shaped at [ms]: upright at C, then lying down as the floor circle. */
internal class SigilPose {
    var cx = 0f; var cy = 0f; var sx = 1f; var sy = 1f; var floor = 0f; var disc = 0f
}

/**
 * How far the sigil has lain down as the floor circle, 0..1. It stays upright at C through 核驗通過 (the transformation pose:
 * flood, pulse, shock rings, burst) and starts down just before the welcome, 60 ms ahead of the eyebrow morph. Until that morph
 * has cleared the hero's slot the circle may not reach the beat block, so the big 核驗通過 never has line art behind it.
 */
internal fun IntroScene.floorIn(ms: Float): Float = if (reduced) 1f else floorProgress(ms, marks, lay.floorLimit)

/** [IntroScene.floorIn] as a pure function of the clock, the marks and the layout's [limit]. */
internal fun floorProgress(ms: Float, m: IntroMarks, limit: Float): Float {
    val p = m.pace
    val raw = seg(ms, m.d - 60f * p, 700f * p, IntroEase.EmphDecel)
    val morph = seg(ms, m.d, 420f * p, IntroEase.EmphDecel)
    return min(raw, lerp(limit, 1f, morph))
}

internal fun IntroScene.pose(ms: Float, amb: Float, out: SigilPose) {
    val m = marks
    val k = floorIn(ms)
    val pulse = sigilScale(ms, m)
    out.cx = lerp(lay.c.x, lay.cf.x, k)
    out.cy = lerp(lay.c.y, lay.cf.y, k)
    out.sx = lerp(1f, lay.rx / lay.r, k) * pulse
    out.sy = lerp(1f, lay.ry / lay.r, k) * pulse
    out.floor = k
    out.disc = 6f * max(0f, amb - m.d / 1000f) * k
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
    val fl = pose.floor
    val inh = sc.inhale(ms)
    val kA = seg(ms, m.a, 560f * p, IntroEase.CubicInOut)
    val kB = seg(ms, m.a + 60f * p, 560f * p, IntroEase.CubicInOut)
    if (kA <= 0f) return
    val unitBox = Offset(-1f, -1f)
    // Rose-brown line art upright; on the floor ring A, ring C and the petal outlines turn into light (white core, pink glow).
    val line = 1f - fl
    withTransform({
        translate(pose.cx, pose.cy)
        scale(pose.sx * R, pose.sy * R, Offset.Zero)
        rotate(pose.disc, Offset.Zero)
    }) {
        // Ring A's white under-glow right after the hit.
        val glow = .5f * (1f - seg(ms, m.hit, 500f, IntroEase.QuintOut)) * (if (ms >= m.hit) 1f else 0f)
        if (glow > 0f) drawCircle(Sakura.White.copy(alpha = glow), 1f, Offset.Zero, style = k.ringAGlow)
        if (fl > 0f) {
            drawCircle(Sakura.Primary.copy(alpha = .35f * fl), 1f, Offset.Zero, style = k.floorGlow)
            drawCircle(Sakura.Primary.copy(alpha = .35f * fl), .62f, Offset.Zero, style = k.floorGlow)
        }
        // Ring A and its inner white line, drawn by the comet.
        if (line > 0f) drawArc(Sakura.RoseBrown.copy(alpha = .80f * line), -90f, 360f * kA, false, unitBox, Size(2f, 2f), style = k.ringA)
        drawArc(Sakura.White.copy(alpha = .70f * line), -90f, 360f * kA, false, Offset(-.988f, -.988f), Size(1.976f, 1.976f), style = k.ringAInner)
        if (fl > 0f) drawCircle(Sakura.White.copy(alpha = .85f * fl), 1f, Offset.Zero, style = k.floorCore)
        if (kB > 0f) drawArc(Sakura.RoseBrown.copy(alpha = .45f), -90f, 360f * kB, false, Offset(-.91f, -.91f), Size(1.82f, 1.82f), style = k.ringB)
        // Ticks, revealed behind the comet's head.
        if (kA >= 1f) {
            drawPath(k.ticksMinor, Sakura.RoseBrown.copy(alpha = .55f), style = k.tickMinorStroke)
            drawPath(k.ticksMajor, Sakura.RoseBrown.copy(alpha = .75f), style = k.tickMajorStroke)
        } else {
            val wedge = k.scratch
            wedge.reset()
            wedge.moveTo(0f, 0f)
            wedge.arcTo(Rect(-1.2f, -1.2f, 1.2f, 1.2f), -90f, 360f * kA, false)
            wedge.close()
            clipPath(wedge) {
                drawPath(k.ticksMinor, Sakura.RoseBrown.copy(alpha = .55f), style = k.tickMinorStroke)
                drawPath(k.ticksMajor, Sakura.RoseBrown.copy(alpha = .75f), style = k.tickMajorStroke)
            }
        }
        // The tick runner: one tick lit white, running round at 90°/s while the system half waits.
        if (kA >= 1f && fl < 1f && !sc.reduced) {
            val i = ((amb * 90f / 5f).toInt() % 72 + 72) % 72
            val a = rad(i * 5f - 90f)
            val from = if (i % 6 == 0) .79f else .82f
            drawLine(Sakura.White.copy(alpha = .9f * (1f - fl)), Offset(cos(a) * from, sin(a) * from), Offset(cos(a) * .86f, sin(a) * .86f), k.tickMajorStroke.width)
        }
        // The rosette group, on its uneven clock.
        val build = m.build
        if (ms >= build) {
            val g2 = stutter(amb) - 14f * inh
            rotate(g2, Offset.Zero) {
                val kC = seg(ms, build, 300f * p, IntroEase.CubicInOut)
                if (line > 0f) drawArc(Sakura.RoseBrown.copy(alpha = .60f * line), -90f, 360f * kC, false, Offset(-.62f, -.62f), Size(1.24f, 1.24f), style = k.ringC)
                if (fl > 0f) drawCircle(Sakura.White.copy(alpha = .85f * fl), .62f, Offset.Zero, style = k.floorCore)
                for (i in 0 until 8) {
                    val t = seg(ms, build + (120f + 20f * i) * p, 160f * p)
                    if (t <= 0f) continue
                    drawPath(k.spokes[i], Sakura.RoseBrown.copy(alpha = .40f * t), style = k.thin)
                    val pop = IntroEase.BackOut.transform(t)
                    drawCircle(Sakura.White, k.spokeDot * pop, k.spokeEnds[i])
                    drawCircle(Sakura.RoseBrown.copy(alpha = .8f * t), k.spokeDot * pop, k.spokeEnds[i], style = k.spokeDotRing)
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
                drawRosette(sc, ms, amb, fl)
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
            ring.alpha = lerp(a, 1f, fl)
            drawLayer(ring)
        }
    }
}

/**
 * The five petals: outlines that draw themselves over a white-tipped, pink-based fill, the pink flood at the hit (full pink for
 * its first 200 ms, so it still reads through the flash), and on the floor a light pink fill with a white rim.
 */
private fun DrawScope.drawRosette(sc: IntroScene, ms: Float, amb: Float, fl: Float) {
    val k = sc.kit
    val m = sc.marks
    val p = m.pace
    val d = ms - m.hit
    val pink = if (d < 0f) 0f else (d / 60f).coerceAtMost(1f)
    val settle = IntroEase.QuintOut.transform(((d - 200f) / 500f).coerceIn(0f, 1f))
    val breath = if (d > 700f) .05f * sin((amb - m.hit / 1000f) * (2f * PI.toFloat() / 1.6f)) else 0f
    val pinkA = lerp(lerp(1f, .60f, settle) + breath * settle, .45f, fl)
    val line = 1f - fl
    // The heart band that joins the petals at their base.
    val heartK = seg(ms, m.build + 40f * p, 240f * p, IntroEase.CubicOut)
    if (heartK > 0f) drawCircle(Sakura.Primary.copy(alpha = lerp(.55f, .45f, fl) * heartK), .3225f, Offset.Zero, style = k.heart)
    for (i in 0 until 5) {
        val t = seg(ms, m.build + (40f + 40f * i) * p, 240f * p, IntroEase.CubicOut)
        if (t <= 0f) continue
        val grow = t * t
        if (pink < 1f) drawPath(k.petals[i], k.petalFill, alpha = grow * (1f - pink))
        if (pink > 0f) drawPath(k.petals[i], Sakura.Primary.copy(alpha = pinkA * pink * grow))
        if (t >= 1f) {
            if (line > 0f) drawPath(k.petals[i], Sakura.RoseBrown.copy(alpha = .80f * line), style = k.petalLine)
            if (fl > 0f) {
                drawPath(k.petals[i], Sakura.Primary.copy(alpha = .35f * fl), style = k.floorGlow)
                drawPath(k.petals[i], Sakura.White.copy(alpha = fl), style = k.floorRim)
            }
        } else {
            val seg = k.scratch2
            seg.reset()
            k.petalMeasures[i].getSegment(0f, k.petalLength * t, seg, true)
            drawPath(seg, Sakura.RoseBrown.copy(alpha = .80f), style = k.petalLine)
        }
    }
}

internal fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** The beat effects around the upright sigil before the hit: comet, ping, scan and the converge ring. */
internal fun DrawScope.drawSigilEffects(sc: IntroScene, ms: Float) {
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
        drawCircle(Sakura.White.copy(alpha = headFade), 4.75f * dp, Offset(hx, hy))
        drawCircle(Sakura.Aqua.copy(alpha = .95f * headFade), 3.5f * dp, Offset(hx, hy))
        drawCircle(Sakura.White.copy(alpha = headFade), 1.6f * dp, Offset(hx, hy))
    }
    // The ring-close glint at the top.
    val gl = seg(ms, m.a + 560f * p, 200f * p, IntroEase.QuintOut)
    if (gl > 0f && gl < 1f) drawArc(Sakura.White.copy(alpha = .6f * (1f - gl)), -105f, 30f, false, Offset(c.x - R, c.y - R), Size(2 * R, 2 * R), style = Stroke(3f * dp, cap = StrokeCap.Round))
    // The access ping, once.
    val pg = seg(ms, m.a + 80f * p, 640f * p, IntroEase.QuintOut)
    if (pg > 0f && pg < 1f) {
        // A white ring under the aqua one: a fading aqua ring straight over pink passes through grey.
        drawCircle(Sakura.White.copy(alpha = .55f * (1f - pg)), R * lerp(.30f, 1.25f, pg), c, style = Stroke(3.5f * dp))
        drawCircle(Sakura.Aqua.copy(alpha = .55f * (1f - pg)), R * lerp(.30f, 1.25f, pg), c, style = Stroke(1.5f * dp))
    }
    // The scan: a white line across ring A's chord over a white trail with a pink one beneath it (no aqua: aqua over the pink
    // field read as a grey band), aqua dots at the chord's ends and a pink tick on the ruler.
    val sk = seg(ms, m.scanStart, m.scanMs, IntroEase.CubicInOut)
    if (ms >= m.scanStart && sk < 1f) {
        val y = c.y - R + 2f * R * sk
        val half = sqrt(max(0f, R * R - (y - c.y) * (y - c.y)))
        clipPath(sc.ringDisc) {
            withTransform({ translate(0f, y) }) {
                drawRect(sc.scanPink, Offset(c.x - R, -18f * dp), Size(2f * R, 18f * dp))
                drawRect(sc.scanWhite, Offset(c.x - R, -44f * dp), Size(2f * R, 44f * dp))
            }
        }
        drawLine(Sakura.White.copy(alpha = .95f), Offset(c.x - half, y), Offset(c.x + half, y), 1f * dp)
        if (half > 2f * dp) for (sx in floatArrayOf(c.x - half, c.x + half)) {
            drawCircle(Sakura.White, 2.5f * dp, Offset(sx, y))
            drawCircle(Sakura.Aqua, 1.5f * dp, Offset(sx, y))
        }
        if (L.ruler) drawLine(Sakura.Primary, Offset(L.rulerX - 4f * dp, y), Offset(L.rulerX + 4f * dp, y), 1.5f * dp)
    }
    // The converge ring, pulled in during the inhale.
    if (ms >= m.c && ms < m.hit) {
        val t = seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn)
        drawCircle(Sakura.Primary.copy(alpha = .6f * t), R * lerp(1.35f, 1f, t), c, style = Stroke(1.5f * dp))
    }
}

/**
 * The hit's own effects, drawn above the flash so they read through it: shock rings, pink speed lines with a white core, and
 * three ribbons whipping round the upright sigil.
 */
internal fun DrawScope.drawHitFx(sc: IntroScene, ms: Float) {
    val k = sc.kit
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val R = L.r
    val c = L.c
    val dp = L.dp
    if (ms < m.hit || sc.reduced) return
    val s1 = seg(ms, m.hit, 520f * p, IntroEase.QuintOut)
    if (s1 < 1f) drawCircle(Sakura.White.copy(alpha = .7f * (1f - s1)), R * lerp(1f, 1.7f, s1), c, style = Stroke(2f * dp))
    val s2 = seg(ms, m.hit + 60f * p, 480f * p, IntroEase.QuintOut)
    if (ms >= m.hit + 60f * p && s2 < 1f) drawCircle(Sakura.Primary.copy(alpha = .7f * (1f - s2)), R * lerp(1f, 1.45f, s2), c, style = Stroke(1.5f * dp))
    if (sc.level == EffectLevel.SAVER) return
    val b = seg(ms, m.hit, 380f * p, IntroEase.QuintOut)
    if (b < 1f) withTransform({ translate(c.x, c.y); scale(R * lerp(1f, 1.45f, b), R * lerp(1f, 1.45f, b), Offset.Zero) }) {
        drawPath(k.burst, Sakura.Primary.copy(alpha = .90f * (1f - b)))
        drawPath(k.burstCore, Sakura.White.copy(alpha = 1f - b))
    }
    withTransform({ translate(c.x, c.y); scale(R, R, Offset.Zero) }) {
        for (i in 0 until 3) {
            val h = 1.5f * seg(ms, m.hit + 70f * i * p, 640f * p, IntroEase.CubicInOut)
            if (ms < m.hit + 70f * i * p || h >= 1.5f) continue
            val a = (h - .5f).coerceIn(0f, 1f)
            val bb = h.coerceIn(0f, 1f)
            if (bb - a <= .002f) continue
            ribbon(sc, i, a, bb)
        }
    }
}

/**
 * Ribbon [i] between fractions [from] and [to] of its arc, as one filled shape tapering to points at both ends (widest toward
 * the head), with a soft pink glow round it. A piece that shows less than 40 dp on screen is skipped, so no short scrap hangs at
 * the screen's edge. Drawn in unit space (under the sigil's translate and scale).
 */
private fun DrawScope.ribbon(sc: IntroScene, i: Int, from: Float, to: Float) {
    val k = sc.kit
    val L = sc.lay
    val pm = k.ribbonMeasures[i]
    val total = pm.length
    val n = SigilKit.RIBBON_SAMPLES
    val R = L.r
    var onScreen = 0f
    var lastX = 0f
    var lastY = 0f
    var lastIn = false
    for (j in 0..n) {
        val d = (from + (to - from) * j / n) * total
        val pos = pm.getPosition(d)
        val tan = pm.getTangent(d)
        k.ribbonX[j] = pos.x
        k.ribbonY[j] = pos.y
        val len = hypot(tan.x, tan.y).coerceAtLeast(1e-4f)
        k.ribbonNx[j] = -tan.y / len
        k.ribbonNy[j] = tan.x / len
        val sx = L.c.x + pos.x * R
        val sy = L.c.y + pos.y * R
        val inside = sx in 0f..L.w && sy in 0f..L.h
        if (j > 0 && inside && lastIn) onScreen += hypot(sx - lastX, sy - lastY)
        lastX = sx; lastY = sy; lastIn = inside
    }
    if (onScreen < 40f * L.dp) return
    val w = k.ribbonWidths[i]
    ribbonShape(k, w / 2f + k.ribbonGlow)
    drawPath(k.scratch2, k.ribbonGlowColor)
    ribbonShape(k, w / 2f)
    drawPath(k.scratch2, k.ribbonColors[i])
}

/** Fills [SigilKit.scratch2] with the sampled ribbon offset by a tapered half-width that peaks at [half], 60 % along. */
private fun ribbonShape(k: SigilKit, half: Float) {
    val n = SigilKit.RIBBON_SAMPLES
    val path = k.scratch2
    path.reset()
    for (j in 0..n) {
        val hw = half * taper(j / n.toFloat())
        val x = k.ribbonX[j] + k.ribbonNx[j] * hw
        val y = k.ribbonY[j] + k.ribbonNy[j] * hw
        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (j in n downTo 0) {
        val hw = half * taper(j / n.toFloat())
        path.lineTo(k.ribbonX[j] - k.ribbonNx[j] * hw, k.ribbonY[j] - k.ribbonNy[j] * hw)
    }
    path.close()
}

/** 0 at both ends, 1 about 60 % along (toward the head). */
private fun taper(u: Float): Float = sin(PI.toFloat() * u.pow(1.35f)).coerceAtLeast(0f).pow(.7f)

/**
 * The window in the middle of the sigil (you: your picture or your monogram). Without a picture it grows, where it stands, into
 * the welcome's crest: the sakura mon on a white disc, with its halo and two orbiting sparkles.
 */
internal fun DrawScope.drawWindow(sc: IntroScene, ms: Float, amb: Float) {
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    val R = L.r
    // Long runs show the plate and the ghost early, so the dial never sits empty while ring A waits for the inner sigil.
    val plateAt = if (m.total >= 5000f) m.a + 300f * p else m.build + 40f * p
    val plate = seg(ms, plateAt, 200f * p)
    val appear = seg(ms, m.build + 40f * p, 260f * p, IntroEase.CubicOut)
    if (plate <= 0f && appear <= 0f) return
    val pulse = sigilScale(ms, m)
    val kw = if (sc.backdrop || !L.crest) 0f else if (sc.reduced) 1f else seg(ms, m.d - 60f * p, 700f * p, IntroEase.EmphDecel)
    val gone = if (sc.backdrop) seg(ms, m.hit + 30f * p, 200f * p) else if (!L.crest) seg(ms, m.d - 60f * p, 300f * p) else 0f
    val alpha = 1f - gone
    if (alpha <= 0f) return
    val bob = if (kw > 0f && !sc.reduced) 4f * dp * sin(amb * (2f * PI.toFloat() / 3.2f)) * kw else 0f
    val cx = lerp(L.c.x, L.crestC.x, kw)
    val cy = lerp(L.c.y, L.crestC.y, kw) + bob
    val rw = lerp(.30f * R * pulse, L.crestR, kw)
    val center = Offset(cx, cy)
    val mon = (kw / .6f).coerceIn(0f, 1f)
    // Plate and content, clipped to the window.
    val clip = sc.kit.scratch
    clip.reset()
    clip.addOval(Rect(center, rw - .75f * dp))
    val ghost = .18f * plate
    val sk = seg(ms, m.scanStart, m.scanMs, IntroEase.CubicInOut)
    val scanY = if (ms < m.scanStart) Float.NEGATIVE_INFINITY else L.c.y - R + 2f * R * sk
    clipPath(clip) {
        if (sc.windowPicture) {
            sc.drawPictureAt(this, sc.windowPlacement, ghost * alpha)
            if (scanY > -1e6f) clipRect(top = 0f, bottom = scanY) { sc.drawPictureAt(this, sc.windowPlacement, alpha) }
        } else {
            if (mon < 1f) {
                drawCircle(Sakura.MilkTea.copy(alpha = .22f * alpha * plate * (1f - mon)), rw, center)
                sc.drawMonogram(this, center, pulse, ghost * alpha * (1f - mon), scanY, alpha * (1f - mon))
            }
            if (mon > 0f) sc.drawMon(this, center, rw, mon * alpha)
        }
        // Scanlines inside the window while scanning.
        if (ms >= m.scanStart && sk < 1f) {
            var y = cy - rw
            while (y < cy + rw) { drawLine(Sakura.White.copy(alpha = .10f), Offset(cx - rw, y), Offset(cx + rw, y), 1f); y += 3f * dp }
        }
    }
    if (appear <= 0f) return
    // Window rings, drawing themselves.
    drawArc(Sakura.White.copy(alpha = alpha), -90f, 360f * appear, false, Offset(cx - rw, cy - rw), Size(2 * rw, 2 * rw), style = Stroke(2f * dp))
    val ro = lerp(rw * (.335f / .30f), rw + 5f * dp, kw)
    drawArc(Sakura.RoseBrown.copy(alpha = .60f * alpha), -90f, 360f * appear, false, Offset(cx - ro, cy - ro), Size(2 * ro, 2 * ro), style = Stroke(1f * dp))
    // The crest halo and its orbit sparkles.
    if (kw > 0f) {
        val hk = if (sc.reduced) 1f else seg(ms, m.d + 200f * p, 360f * p, IntroEase.CubicOut)
        if (hk > 0f) {
            val hr = L.crestR + 11f * dp
            drawArc(Sakura.RoseBrown.copy(alpha = .6f), -90f, 360f * hk, false, Offset(cx - hr, cy - hr), Size(2 * hr, 2 * hr), style = Stroke(1f * dp))
            val ticks = (24 * hk).toInt()
            for (i in 0 until ticks) {
                val a = rad(i * 15f - 90f)
                drawLine(Sakura.RoseBrown.copy(alpha = .6f), Offset(cx + cos(a) * (L.crestR + 15f * dp), cy + sin(a) * (L.crestR + 15f * dp)), Offset(cx + cos(a) * (L.crestR + 20f * dp), cy + sin(a) * (L.crestR + 20f * dp)), 1f * dp)
            }
            val orbit = L.crestR + 27f * dp
            val spin = 24f * max(0f, amb - (m.d + 200f * p) / 1000f)
            for (j in 0 until 2) {
                val a = rad(-60f + 180f * j + spin)
                sc.drawSparkle(this, Offset(cx + cos(a) * orbit, cy + sin(a) * orbit), (if (j == 0) 7f else 5f) * dp / 2f, if (j == 0) Sakura.Primary else Sakura.Aqua, hk)
            }
        }
    }
}
