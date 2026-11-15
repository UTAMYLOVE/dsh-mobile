package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.util.lerp
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where everything sits, from the screen size and the system bars (all in px). The reference is a 412 × 915 dp portrait phone;
 * `s` scales type and decoration for other phones, and small, split-screen, wide and landscape screens get their own placements.
 */
internal class IntroLayout(val w: Float, val h: Float, val dp: Float, val iT: Float, val iB: Float) {
    val wDp = w / dp
    val hDp = h / dp
    val s = min(wDp / 412f, hDp / 915f).coerceIn(.8f, 1.2f)
    val landscape = w > h
    val small = hDp < 700f
    val split = hDp < 600f
    val wide = wDp >= 600f
    val wc = min(w, 520f * dp)
    val g = if (wDp < 360f) 20f * dp else 28f * s * dp
    /** The HUD's side margin, the same as the hairlines' ends. */
    val m = 20f * dp

    val c: Offset
    val r: Float
    val colLeft: Float
    val colWidth: Float
    val captionTop: Float
    val cf: Offset
    val rx: Float
    val ry: Float
    val welcomeCenterX: Float
    val welcomeBottom: Float
    /** False on split screens: no crest there, the welcome block alone (landscape has room for it beside the block). */
    val crest: Boolean = landscape || !split
    val crestR: Float

    init {
        if (landscape) {
            c = Offset(.30f * w, .50f * h)
            r = min(.36f * h, .22f * w)
            colLeft = .56f * w
            colWidth = w - colLeft - g
            captionTop = .34f * h
            // The block stands on the floor circle in the right column; the crest floats in the left one.
            cf = Offset(.72f * w, .90f * h)
            rx = .26f * w
            welcomeCenterX = cf.x
            welcomeBottom = cf.y - .28f * rx - 10f * dp
            crestR = .14f * h
        } else {
            c = Offset(w / 2f, .36f * h)
            r = if (split) .19f * h else min(.40f * wc, .19f * h)
            colLeft = (w - wc) / 2f + g
            colWidth = wc - 2f * g
            captionTop = if (split) c.y + r + 24f * dp else max(.645f * h, c.y + r + 40f * dp)
            cf = Offset(w / 2f, h - iB - .115f * h)
            rx = if (wide) .78f * wc else .62f * w
            welcomeCenterX = w / 2f
            welcomeBottom = cf.y - .28f * rx - 10f * dp
            crestR = (if (small) 56f else 66f) * s * dp
        }
        ry = .28f * rx
    }

    val heroTop = captionTop + 22f * s * dp
    val hud1Top = iT + 14f * dp
    val hud2Top = iT + 32f * dp
    val hud2 = !small
    val topLine = iT + 54f * dp
    val progressY = h - iB - 52f * dp
    val hudBottomTop = progressY + 10f * dp
    val rulerX = w - 14f * dp
    val ruler = !landscape
    val halftone = !small
    /** The crest's centre; set once the welcome block is measured (it sits above the eyebrow). */
    var crestC = Offset(w / 2f, .32f * h)
}

/** A picture placement: drawn at (ox, oy) scaled by k. */
internal class Placement(var ox: Float = 0f, var oy: Float = 0f, var k: Float = 1f) {
    fun set(o: Placement) { ox = o.ox; oy = o.oy; k = o.k }
}

/**
 * Offset and scale that draw an [iw] × [ih] picture covering [w] × [h] at zoom [z], with the focus [fx], [fy] (0..1 of the picture)
 * as near [tx], [ty] as the crop allows. With the focus at the target's own fraction of the screen and zoom 1 this is exactly
 * ContentScale.Crop with the same bias, which is how the home screen draws the picture: the hand-off through the iris matches.
 */
internal fun cover(iw: Float, ih: Float, w: Float, h: Float, z: Float, fx: Float, fy: Float, tx: Float, ty: Float, out: Placement) {
    val k = max(w / iw, h / ih) * z
    out.k = k
    out.ox = (tx - fx * iw * k).coerceIn(w - iw * k, 0f)
    out.oy = (ty - fy * ih * k).coerceIn(h - ih * k, 0f)
}

/** The fixed choices the spec leaves to settings; the owner asked for no new settings, so they are constants at their defaults. */
internal object IntroConst {
    /** The user's picture opens into the full-screen welcome background. */
    const val BACKDROP = true
    /** One CONFIRM tick at 核驗通過. */
    const val HAPTIC = true
    /** The name's white outline and pink hard shadow are drawn graphics; false keeps the outline only. */
    const val NAME_STICKER = true
}

/**
 * One run's frame-independent state: the layout, every path, brush and text layout, built once per size. The draw lambdas read
 * only this and the clock, so a frame allocates nothing and no text is laid out again.
 */
internal class IntroScene(
    val lay: IntroLayout,
    val marks: IntroMarks,
    val level: EffectLevel,
    val picture: ImageBitmap?,
    portrait: Boolean,
    /** The focus inside the picture (0..1), from the picture's own alignment setting. */
    val focusX: Float,
    val focusY: Float,
    val fx: IntroFx,
    val reduced: Boolean,
    val texts: IntroTexts,
    val skippable: Boolean,
) {
    val backdrop = picture != null && IntroConst.BACKDROP
    val windowPicture = picture != null && portrait
    val kit = SigilKit(lay.r, lay.dp, if (level == EffectLevel.FULL) 28 else 18)
    private val dp = lay.dp
    private val w = lay.w
    private val h = lay.h

    // ---- light and field ----
    val sky = Brush.verticalGradient(
        0f to lerpColor(Sakura.Background, Sakura.Primary, .45f), .12f to lerpColor(Sakura.Background, Sakura.Primary, .28f),
        .34f to Sakura.Background, .60f to lerpColor(Sakura.Background, Sakura.White, .45f), .78f to Sakura.Background,
        1f to lerpColor(Sakura.Background, Sakura.Primary, .40f), startY = 0f, endY = h,
    )
    val leak = Brush.radialGradient(listOf(Sakura.White.copy(alpha = .45f), Sakura.White.copy(alpha = 0f)), Offset(w, 0f), .9f * w)
    /** A soft white disc of radius 1 at the origin, scaled where it is drawn (blooms, head glows). */
    val unitBloom = Brush.radialGradient(0f to Color.White, .4f to Color.White.copy(alpha = .55f), 1f to Color.White.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    val unitShadow = Brush.radialGradient(0f to Sakura.MilkTea.copy(alpha = .28f), 1f to Sakura.MilkTea.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    val softGlow = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), Offset.Zero, 12f * dp)
    val cometTail = Brush.sweepGradient(0f to Color.White.copy(alpha = 0f), (310f / 360f) to Color.White.copy(alpha = 0f), 1f to Color.White.copy(alpha = .9f), center = Offset.Zero)
    val scanWhite = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = .55f)), -44f * dp, 0f)
    val scanAqua = Brush.verticalGradient(listOf(Sakura.Aqua.copy(alpha = 0f), Sakura.Aqua.copy(alpha = .28f)), -18f * dp, 0f)
    val ringDisc = Path().apply { addOval(Rect(lay.c, lay.r)) }
    val flashBrush = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = .55f)), lay.c, hypot(w, h))
    val topScrim = Brush.verticalGradient(listOf(Sakura.Background.copy(alpha = .60f), Sakura.Background.copy(alpha = 0f)), 0f, .14f * h)
    val bottomScrim = Brush.verticalGradient(
        0f to Sakura.Background.copy(alpha = 0f), (.52f - .42f) / .58f to Sakura.Background.copy(alpha = .85f),
        (.58f - .42f) / .58f to Sakura.Background.copy(alpha = .92f), 1f to Sakura.Background.copy(alpha = .92f), startY = .42f * h, endY = h,
    )

    /** White halftone dots in the lower-left corner, larger toward the corner: a few hundred circles in one path. */
    val halftone = Kawaii.halftone(w, h, 9f * dp, 3f * dp, .4f * dp)

    /** The seven light pillars of the welcome: x offsets (× rx), widths (dp) and heights (× H), each with its own gradient. */
    private val pillarX = floatArrayOf(-.56f, -.34f, -.17f, 0f, .17f, .34f, .56f)
    private val pillarW = floatArrayOf(10f, 4f, 22f, 36f, 18f, 5f, 12f)
    private val pillarH = floatArrayOf(.34f, .42f, .48f, .55f, .46f, .40f, .32f)
    /** Pillars grow from the centre out, 40 ms apart. */
    val pillarOrder = IntArray(7) { kotlin.math.abs(it - 3) }
    val pillarRects: Array<Rect> = Array(7) { i ->
        val cx = lay.cf.x + pillarX[i] * lay.rx
        val half = pillarW[i] * dp * lay.s / 2f
        Rect(cx - half, lay.cf.y - pillarH[i] * h, cx + half, lay.cf.y + .2f * lay.ry)
    }
    val pillarBrushes: Array<Brush> = Array(7) { i ->
        val r = pillarRects[i]
        val peak = if (backdrop) .22f else .26f
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0f),
            ((lay.cf.y - .15f * lay.ry - r.top) / r.height).coerceIn(0f, 1f) to Color.White.copy(alpha = peak),
            1f to Color.White.copy(alpha = 0f), startY = r.top, endY = r.bottom,
        )
    }

    // ---- the picture ----
    val windowPlacement = Placement()
    val welcomePlacement = Placement()
    private val tmpPlacement = Placement()
    val rMax: Float = run {
        val c = lay.c
        maxOf(hypot(c.x, c.y), hypot(w - c.x, c.y), hypot(c.x, h - c.y), hypot(w - c.x, h - c.y)) + 16f * dp
    }

    init {
        picture?.let { p ->
            runCatching { p.prepareToDraw() }
            cover(p.width.toFloat(), p.height.toFloat(), w, h, 1f, focusX, focusY, lay.c.x, lay.c.y, windowPlacement)
        }
    }

    /** The welcome placement at Ken Burns zoom [z]: the focus held at the same spot the home screen's crop puts it. */
    fun welcomeAt(z: Float, out: Placement) {
        val p = picture ?: return
        cover(p.width.toFloat(), p.height.toFloat(), w, h, z, focusX, focusY, focusX * w, focusY * h, out)
    }

    fun drawPictureAt(scope: DrawScope, at: Placement, alpha: Float) {
        val p = picture ?: return
        if (alpha <= 0f) return
        scope.withTransform({ translate(at.ox, at.oy); scale(at.k, at.k, Offset.Zero) }) { drawImage(p, alpha = alpha) }
    }

    /** The picture's placement at [ms] in the magic half: from the window's crop, opening out, then a slow Ken Burns to rest. */
    fun backdropPlacement(ms: Float, out: Placement) {
        val m = marks
        val p = m.pace
        val ki = seg(ms, m.hit + 30f * p, 560f * p, IntroEase.ExpoOut)
        val kbStart = m.hit + 590f * p
        val z = if (ms <= kbStart || reduced) (if (reduced) 1f else 1.06f) else lerp(1.06f, 1f, seg(ms, kbStart, m.e - kbStart, IntroEase.SineInOut))
        welcomeAt(z, tmpPlacement)
        if (ki >= 1f || reduced) { out.set(tmpPlacement); return }
        out.ox = lerp(windowPlacement.ox, tmpPlacement.ox, ki)
        out.oy = lerp(windowPlacement.oy, tmpPlacement.oy, ki)
        out.k = lerp(windowPlacement.k, tmpPlacement.k, ki)
    }

    /** The monogram (or, with no name or an emoji first, the sakura mon), ghosted below the scan line and solid above it. */
    fun drawMonogram(scope: DrawScope, center: Offset, scale: Float, ghost: Float, scanY: Float, alpha: Float) = with(scope) {
        val run = texts.monogram
        val draw: DrawScope.(Float) -> Unit = { a ->
            withTransform({ scale(scale, scale, center) }) {
                if (run == null) {
                    val r = .22f * lay.r
                    withTransform({ translate(center.x, center.y); scale(r, r, Offset.Zero) }) {
                        drawPath(sakura, Sakura.Primary.copy(alpha = .90f * a))
                        drawPath(sakura, Sakura.RoseBrown.copy(alpha = a), style = Stroke(.75f * dp / r))
                    }
                    drawCircle(Sakura.White.copy(alpha = a), 3f * dp, center)
                } else {
                    val base = center.y + .36f * run.fontPx
                    drawGlyph(run, 0, Offset(center.x - run.advance[0] / 2f, base - run.baseline), Sakura.Text, a)
                }
            }
        }
        draw(ghost)
        if (scanY > -1e6f) clipRect(top = 0f, bottom = scanY.coerceAtMost(h * 4f)) { draw(alpha) }
    }

    val sakura: Path = sakuraSilhouette()
    private val sparkle: Path = Kawaii.sparklePath()

    fun drawSparkle(scope: DrawScope, at: Offset, r: Float, color: Color, alpha: Float, rot: Float = 0f) = with(scope) {
        if (alpha <= 0f || r <= 0f) return@with
        withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(r, r, Offset.Zero) }) { drawPath(sparkle, color.copy(alpha = color.alpha * alpha)) }
    }

    /** The exit's origin: the tap point, else the crest, or the upper third over a backdrop. */
    fun exitOrigin(): Offset = if (backdrop || !lay.crest) Offset(w / 2f, .30f * h) else lay.crestC

    // ---- particles ----
    val parts = IntroParticles(this)
}

/** The ray strength multiplier: wakes, swells in the inhale, flares at the hit and settles. */
internal fun IntroScene.ray(ms: Float): Float {
    val m = marks
    return when {
        ms < m.c -> .55f * seg(ms, 0f, 600f, IntroEase.SineInOut)
        ms < m.hit -> lerp(.55f, 1.3f, seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn))
        ms < m.hit + 60f -> lerp(1.3f, 2.0f, (ms - m.hit) / 60f)
        else -> lerp(2.0f, .9f, seg(ms, m.hit + 60f, 900f * m.pace, IntroEase.QuintOut))
    }
}

/** System half → magic half for the field (blooms, halftone, rays), 0..1. */
internal fun IntroScene.magic(ms: Float): Float = seg(ms, marks.hit, 700f * marks.pace, IntroEase.SineInOut)

/** L1–L4: sky, light leak, the picture backdrop with its scrims, halftone, blooms, rays and pillars. */
internal fun DrawScope.drawField(sc: IntroScene, ms: Float, amb: Float, place: Placement) {
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    val R = L.r
    val skyIn = seg(ms, 40f, 400f, IntroEase.SineInOut)
    if (skyIn > 0f) {
        drawRect(sc.sky, alpha = skyIn)
        drawRect(sc.leak, alpha = skyIn)
    }
    val fm = sc.magic(ms)
    // L2: the picture opens from the window into the full screen.
    if (sc.backdrop && ms >= m.hit + 30f * p) {
        sc.backdropPlacement(ms, place)
        val ki = if (sc.reduced) 1f else seg(ms, m.hit + 30f * p, 560f * p, IntroEase.ExpoOut)
        val r = lerp(.30f * R, sc.rMax, ki)
        if (ki < 1f) {
            val clip = sc.kit.scratch
            clip.reset()
            clip.addOval(Rect(L.c, r))
            clipPath(clip) { sc.drawPictureAt(this, place, 1f) }
            val rim = 1f - ((r / sc.rMax - .8f) / .2f).coerceIn(0f, 1f)
            if (rim > 0f) {
                drawCircle(Sakura.White.copy(alpha = .25f * rim), r, L.c, style = Stroke(12f * dp))
                drawCircle(Sakura.White.copy(alpha = .95f * rim), r, L.c, style = Stroke(2.5f * dp))
            }
        } else sc.drawPictureAt(this, place, 1f)
        // The scrims arrive with the picture (not with the slower field change), so the HUD and 核驗通過 stay readable over it.
        val scrim = max(fm, ki)
        drawRect(sc.topScrim, alpha = scrim)
        drawRect(sc.bottomScrim, alpha = scrim)
    }
    // L3: halftone.
    if (L.halftone) {
        val sys = .60f * seg(ms, 80f, 400f)
        val a = lerp(sys, if (sc.backdrop) 0f else .80f, fm)
        if (a > 0f) drawPath(sc.halftone, Sakura.White.copy(alpha = .55f * a))
    }
    // L4: light. The sigil bloom at C (the light point of the opening grows into it), fading as the magic half takes over.
    val inh = if (ms < m.hit) seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn) else 1f - seg(ms, m.hit, 400f * p, IntroEase.QuintOut)
    val point = IntroEase.ExpoOut.transform((ms / 260f).coerceIn(0f, 1f))
    val bloomA = lerp(lerp(.95f, .70f, seg(ms, 260f, 340f)), .95f, inh) * (1f - fm)
    if (bloomA > 0f && point > 0f) {
        val br = lerp(.55f, .75f, inh) * R * point
        withTransform({ translate(L.c.x, L.c.y); scale(br, br, Offset.Zero) }) { drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = bloomA) }
    }
    val ray = sc.ray(ms)
    val rot = if (sc.level == EffectLevel.SAVER) 0f else 18f * seg(ms, m.hit, 600f * p, IntroEase.QuintOut)
    val raysAtC = ray * (1f - fm)
    if (raysAtC > 0f) withTransform({ translate(L.c.x, L.c.y); scale(R, R, Offset.Zero); rotate(rot, Offset.Zero) }) {
        drawPath(sc.kit.rays, sc.kit.rayBrush, alpha = raysAtC.coerceAtMost(1f))
    }
    if (fm <= 0f) return
    // The magic half: a top bloom, the floor's shadow and bloom, the pillars, and rays behind the crest (none over a picture).
    val breathe = if (sc.reduced) 0f else .5f + .5f * sin(amb * (2f * PI.toFloat() / 4f))
    withTransform({ translate(L.w / 2f, -.1f * L.h); scale(.85f * L.h, .85f * L.h, Offset.Zero) }) {
        drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = (.55f + .10f * breathe) * fm)
    }
    withTransform({ translate(L.cf.x, L.cf.y); scale(L.rx * 1.1f, L.ry * 1.1f, Offset.Zero) }) { drawCircle(sc.unitShadow, 1f, Offset.Zero, alpha = fm) }
    withTransform({ translate(L.cf.x, L.cf.y); scale(L.rx * .8f, L.ry * .8f, Offset.Zero) }) { drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = .50f * fm) }
    if (!sc.backdrop && L.crest) {
        val a = ray * fm
        withTransform({ translate(L.crestC.x, L.crestC.y); scale(R, R, Offset.Zero); rotate(rot + if (sc.reduced) 0f else 2f * amb, Offset.Zero) }) {
            drawPath(sc.kit.rays, sc.kit.rayBrush, alpha = a.coerceAtMost(1f))
        }
    } else if (sc.backdrop) {
        val a = ray * (1f - seg(ms, m.hit + 200f * p, 500f * p)) * fm
        if (a > 0f) withTransform({ translate(L.c.x, L.c.y); scale(R, R, Offset.Zero); rotate(rot, Offset.Zero) }) {
            drawPath(sc.kit.rays, sc.kit.rayBrush, alpha = a.coerceAtMost(1f))
        }
    }
    for (i in 0 until 7) {
        val k = if (sc.reduced) .8f else IntroEase.ExpoOut.transform(((ms - m.hit - (360f + 40f * sc.pillarOrder[i]) * p) / (520f * p)).coerceIn(0f, 1f))
        if (k <= 0f) continue
        val h = ih(i, 71)
        val b = if (sc.reduced) 1f else .75f + .25f * sin(amb * (.9f + .4f * h) + 6.28f * h)
        val r = sc.pillarRects[i]
        val top = r.bottom - r.height * k
        drawRect(sc.pillarBrushes[i], Offset(r.left, top), Size(r.width, r.bottom - top), alpha = b)
    }
}

/** L5–L6: far stars and the system chrome (power-on line, hairlines with ticks, ruler, progress), which retracts at the hit. */
internal fun DrawScope.drawChrome(sc: IntroScene, ms: Float, amb: Float) {
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    sc.parts.drawStars(this, ms, amb)
    // The CRT power-on line: trims out from the centre, then fades.
    val pk = seg(ms, 40f, 300f, IntroEase.ExpoOut)
    val pa = .9f * (1f - seg(ms, 340f, 300f))
    if (pk > 0f && pa > 0f) {
        val half = (L.w / 2f - 20f * dp) * pk
        drawLine(Sakura.White.copy(alpha = pa), Offset(L.c.x - half, L.c.y), Offset(L.c.x + half, L.c.y), 1.5f * dp, StrokeCap.Round)
    }
    // The wand glint at C: a four-point star that pops, turns 45° and stays small until the window takes its place.
    val wk = seg(ms, 20f, 300f)
    val wa = 1f - seg(ms, m.build + 40f * p, 200f)
    if (wk > 0f && wa > 0f) {
        val sz = if (wk < .5f) IntroEase.BackOut.transform(wk / .5f) else lerp(1f, .4f, (wk - .5f) / .5f)
        sc.drawSparkle(this, L.c, 9f * dp * sz, Sakura.White, wa, 45f * wk)
    }
    // Hairlines, ruler and progress: trim out from the centre, and back to it after the hit.
    val out = seg(ms, 60f, 360f, IntroEase.ExpoOut)
    val back = 1f - seg(ms, m.hit + 40f * p, 260f * p, IntroEase.CubicIn)
    val k = out * back
    if (k <= 0f) return
    val line = Sakura.RoseBrown.copy(alpha = .45f)
    val tick = Sakura.RoseBrown.copy(alpha = .35f)
    for (y in floatArrayOf(L.topLine, L.progressY)) {
        val cx = L.w / 2f
        val half = (L.w / 2f - 20f * dp) * k
        drawLine(line, Offset(cx - half, y), Offset(cx + half, y), .75f * dp)
        var x = 24f * dp
        while (x < half) {
            drawLine(tick, Offset(cx - x, y), Offset(cx - x, y + 3f * dp), .75f * dp)
            drawLine(tick, Offset(cx + x, y), Offset(cx + x, y + 3f * dp), .75f * dp)
            x += 24f * dp
        }
        val pop = IntroEase.BackOut.transform(seg(ms, 60f, 200f)) * back
        if (pop > 0f) withTransform({ translate(cx, y); rotate(45f, Offset.Zero); scale(pop, pop, Offset.Zero) }) {
            val d = 2.5f * dp
            drawRect(Sakura.RoseBrown.copy(alpha = .5f), Offset(-d / 1.414f, -d / 1.414f), Size(d * 1.414f, d * 1.414f))
        }
    }
    if (L.ruler) {
        val top = L.c.y - L.r
        val len = 2f * L.r
        val shown = len * seg(ms, 60f, 360f, IntroEase.ExpoOut)
        val mid = top + len / 2f
        val from = if (back >= 1f) top else mid - (mid - top) * back
        val to = if (back >= 1f) top + shown else mid + (top + shown - mid) * back
        val rc = Sakura.RoseBrown.copy(alpha = .28f)
        if (to > from) drawLine(rc, Offset(L.rulerX, from), Offset(L.rulerX, to), .75f * dp)
        var y = 0f
        var i = 0
        while (y <= len) {
            val ty = top + y
            if (ty in from..to) drawLine(rc, Offset(L.rulerX - (if (i % 5 == 0) 5f else 2f) * dp, ty), Offset(L.rulerX, ty), .75f * dp)
            y += 8f * dp
            i++
        }
    }
    // Progress: 24 % with the access beat, then with the scan to 100 %.
    val fill = progress(sc, ms)
    if (fill > 0f) {
        val left = 20f * dp
        val full = L.w - 40f * dp
        val cx = L.w / 2f
        val x0 = lerp(cx, left, back)
        val x1 = lerp(cx, left + full * fill, back)
        drawLine(Sakura.Primary, Offset(x0, L.progressY), Offset(x1, L.progressY), 2f * dp, StrokeCap.Round)
        drawCircle(Sakura.Primary.copy(alpha = back), 3f * dp, Offset(x1, L.progressY))
        drawCircle(Sakura.White.copy(alpha = back), 1.25f * dp, Offset(x1, L.progressY))
    }
}

/** The authorisation progress, 0..1: to .24 with the access beat, then along with the scan to 1. */
internal fun progress(sc: IntroScene, ms: Float): Float {
    val m = sc.marks
    val first = .24f * seg(ms, m.a, 600f * m.pace, IntroEase.ExpoOut)
    val scan = seg(ms, m.scanStart, m.scanMs, IntroEase.CubicInOut)
    return if (ms < m.scanStart) first else lerp(first, 1f, scan)
}
