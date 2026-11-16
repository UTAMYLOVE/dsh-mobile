package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
    var crest: Boolean = landscape || !split
    /** The crest's radius; capped once the welcome block is measured, so the crest and its halo never touch the eyebrow. */
    var crestR: Float

    val hud1Top = iT + 14f * dp
    val hud2Top = iT + 32f * dp
    val hud2 = !small
    val topLine = iT + 54f * dp
    val progressY = h - iB - 52f * dp
    val hudBottomTop = progressY + 10f * dp
    /** The skip label's baseline (12 sp text on the bottom HUD row). */
    val skipBaseline = hudBottomTop + 13f * s * dp

    init {
        // The floor circle, data ring included, keeps 28 dp above the skip label's baseline, so 跳過 never sits on its lines.
        val floorBottom = skipBaseline - 28f * dp
        if (landscape) {
            // Centred between the two chrome lines, with at least 16 dp between ring A and each (the status bar may take the
            // top of a landscape screen).
            c = Offset(.30f * w, (topLine + progressY) / 2f)
            r = minOf(.32f * h, .22f * w, (progressY - topLine) / 2f - 16f * dp)
            colLeft = .56f * w
            colWidth = w - colLeft - g
            captionTop = .34f * h
            // The block stands on the floor circle in the right column; the crest floats in the left one, where the sigil was.
            rx = .24f * w
            ry = .28f * rx
            cf = Offset(.62f * w, floorBottom - ry)
            welcomeCenterX = cf.x
            welcomeBottom = cf.y - ry - WELCOME_GAP * dp
            crestR = .17f * h
        } else {
            // The system half sits low enough that the beat block fills the lower third: at .36H / .66H a band of empty sky sat
            // between the sigil and the caption and another between the sub and the progress line.
            c = Offset(w / 2f, .39f * h)
            r = if (split) .19f * h else min(.40f * wc, .19f * h)
            colLeft = (w - wc) / 2f + g
            colWidth = wc - 2f * g
            captionTop = if (split) c.y + r + 24f * dp else max(.69f * h, c.y + r + 48f * dp)
            rx = if (wide) .78f * wc else .62f * w
            ry = .28f * rx
            cf = Offset(w / 2f, min(h - iB - .115f * h, floorBottom - ry))
            welcomeCenterX = w / 2f
            welcomeBottom = cf.y - ry - WELCOME_GAP * dp
            // 80 dp at 0.30H, on its own bloom: at 66 dp and 0.385H the top quarter of the welcome was empty sky and the payoff
            // frame was quieter than the scan.
            crestR = CREST_DP * s * dp
        }
    }

    val heroTop = captionTop + 22f * s * dp
    val rulerX = w - 14f * dp

    /**
     * How far the sigil may lie down before the eyebrow morph clears the hero's slot: its bottom edge (its right edge in
     * landscape, where the beat block stands beside it), at the hit's largest pulse, stops 8 dp short of the beat block.
     */
    val floorLimit: Float = run {
        val p = SIGIL_PULSE_MAX
        if (landscape) ((colLeft - 8f * dp - c.x - p * r) / (cf.x - c.x - p * r + p * rx)).coerceIn(0f, 1f)
        else ((captionTop - 8f * dp - c.y - p * r) / (cf.y - c.y - p * r + p * ry)).coerceIn(0f, 1f)
    }
    val ruler = !landscape
    val halftone = !small
    /**
     * The crest floats up from where the sigil stood to 0.30H (never into the top HUD); a short screen, where the welcome block
     * reaches up to it, lifts or shrinks it (see [IntroTexts]). In landscape it stays where the sigil stood, beside the block.
     */
    var crestC = if (landscape) c else Offset(w / 2f, max(.30f * h, hud1Top + 30f * dp + CREST_HALO * dp + crestR))

    companion object {
        /** The crest's radius on the reference phone, in dp (times `s`). */
        const val CREST_DP = 80f
        /** How far the crest's decoration reaches past its radius: the orbit sparkles at +26 and the outer ring at +30, in dp. */
        const val CREST_HALO = 31f
        /** The ID row's bottom keeps this far above the floor ellipse's top (dp), so the row never sits on the data ring's text. */
        const val WELCOME_GAP = 20f
    }
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
    out.ox = clampTo(tx - fx * iw * k, w - iw * k, 0f)
    out.oy = clampTo(ty - fy * ih * k, h - ih * k, 0f)
}

/** [v] within [lo]..[hi]; when rounding leaves the range a hair inverted (a picture that fits exactly), its middle. */
private fun clampTo(v: Float, lo: Float, hi: Float): Float = if (lo > hi) (lo + hi) / 2f else v.coerceIn(lo, hi)

/**
 * The identity window's crop: the avatar square ([crop], cut with [avatarSquare]) filling the window's own square box (2 × 0.30
 * R) round C. A full-screen crop seen through the hole showed whatever part of the picture fell behind it (on a wide screen a
 * hair ornament, not the face); a fixed guess at the face showed the chest of a full-body picture.
 */
internal fun windowCrop(iw: Float, ih: Float, lay: IntroLayout, crop: AvatarCrop, out: Placement) {
    val side = 2f * .30f * lay.r
    val sq = avatarSquare(iw.roundToInt(), ih.roundToInt(), crop)
    out.k = side / sq.side
    out.ox = lay.c.x - side / 2f - sq.left * out.k
    out.oy = lay.c.y - side / 2f - sq.top * out.k
}

/**
 * The picture's placement while the window bursts open (iris-in progress [ki], circle radius [r] round C): the face point
 * travels on a straight line from the window's crop ([from]) to the full-screen crop ([to]) while the scale grows
 * geometrically, so the face stays steady (a plain lerp of offset and scale makes it swim). It is never smaller than the
 * circle's visible box and is shifted only as far as keeps that box covered, so no picture edge shows inside the circle; at the
 * end that box is the screen and this meets [to].
 */
internal fun irisPlacement(iw: Float, ih: Float, lay: IntroLayout, fx: Float, fy: Float, from: Placement, to: Placement, ki: Float, r: Float, out: Placement) {
    val px = fx * iw
    val py = fy * ih
    val ax = lerp(from.ox + px * from.k, to.ox + px * to.k, ki)
    val ay = lerp(from.oy + py * from.k, to.oy + py * to.k, ki)
    val left = max(0f, lay.c.x - r)
    val top = max(0f, lay.c.y - r)
    val right = min(lay.w, lay.c.x + r)
    val bottom = min(lay.h, lay.c.y + r)
    out.k = max(from.k * (to.k / from.k).pow(ki), max((right - left) / iw, (bottom - top) / ih))
    out.ox = clampTo(ax - px * out.k, right - iw * out.k, left)
    out.oy = clampTo(ay - py * out.k, bottom - ih * out.k, top)
}

/** The welcome's seven light pillars: x from the floor's centre (in rx), widths (dp) and heights (H above the floor's centre). */
internal val PILLAR_X = floatArrayOf(-.56f, -.34f, -.17f, 0f, .17f, .34f, .56f)
internal val PILLAR_W = floatArrayOf(10f, 4f, 22f, 36f, 18f, 5f, 12f)
internal val PILLAR_H = floatArrayOf(.34f, .42f, .48f, .55f, .46f, .40f, .32f)

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
    /** The focus inside the picture (0..1), from the picture's own alignment setting: where the home screen's crop holds it. */
    val focusX: Float,
    val focusY: Float,
    /** The part of the picture the identity window shows: the avatar square (調整頭像, or the app's guess). */
    val avatar: AvatarCrop,
    val fx: IntroFx,
    val reduced: Boolean,
    val texts: IntroTexts,
    val skippable: Boolean,
) {
    val backdrop = picture != null && IntroConst.BACKDROP
    val windowPicture = picture != null && portrait
    val kit = SigilKit(lay.r, lay.dp, if (level == EffectLevel.FULL) 28 else 18, sqrt(lay.rx * lay.ry) / lay.r)
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
    // Pink under the white, never aqua: aqua over the pink field measured as a grey-blue band (#DFE3E4).
    val scanPink = Brush.verticalGradient(listOf(Sakura.Primary.copy(alpha = 0f), Sakura.Primary.copy(alpha = .35f)), -18f * dp, 0f)
    val ringDisc = Path().apply { addOval(Rect(lay.c, lay.r)) }
    /**
     * The flash, drawn over everything: full white at C, a hot core round the sigil, and a veil of about half strength from the
     * beat block outward, so at its peak the rosette and monogram wash to about 70 % white and 核驗通過 to about 40 %.
     */
    val flashBrush = Brush.radialGradient(
        0f to Color.White, .18f to Color.White.copy(alpha = .88f), .34f to Color.White.copy(alpha = .58f), 1f to Color.White.copy(alpha = .55f),
        center = lay.c, radius = hypot(w, h),
    )
    /** Over the user's picture the brand and date need 4.5:1 against busy key art: blush .80 at the top, gone by .16H. */
    val topScrim = Brush.verticalGradient(listOf(Sakura.Background.copy(alpha = .80f), Sakura.Background.copy(alpha = 0f)), 0f, .16f * h)
    /**
     * Blush .90 only where the welcome block's type sits (eyebrow top − 16 dp to the ID row's bottom + 16 dp), easing in over
     * .10H above it, down to .70 by .85H under the floor circle and back to .80 behind the skip label (#5E4445 stays above 4.5:1
     * there even over rose brown). A flat .92 from .58H to the bottom turned the lower half of the art to milk.
     */
    val bottomScrim: Brush = run {
        val full = texts.eyebrowTop - 16f * dp
        val held = max(full, texts.idTop + texts.idNo.height + 16f * dp)
        val from = max(0f, full - .10f * h)
        val low = min(h, max(.85f * h, held + 24f * dp))
        val skip = min(h, max(lay.hudBottomTop - 8f * dp, low + 8f * dp))
        val span = (h - from).coerceAtLeast(1f)
        fun at(y: Float) = ((y - from) / span).coerceIn(0f, 1f)
        val b = Sakura.Background
        Brush.verticalGradient(
            0f to b.copy(alpha = 0f), at(full) to b.copy(alpha = .90f), at(held) to b.copy(alpha = .90f), at(low) to b.copy(alpha = .70f),
            at(skip) to b.copy(alpha = .80f), 1f to b.copy(alpha = .80f), startY = from, endY = h,
        )
    }

    /** White halftone dots in the lower-left corner, larger toward the corner: a few hundred circles in one path. */
    val halftone = Kawaii.halftone(w, h, 9f * dp, 3f * dp, .4f * dp)

    /** The floor's own light: a round white bloom over the lying circle, so it reads as a lit summon circle, not a print. */
    val floorBloom = Brush.radialGradient(
        listOf(Color.White.copy(alpha = .45f), Color.White.copy(alpha = .20f), Color.White.copy(alpha = 0f)), lay.cf, .6f * lay.rx,
    )

    /** The base of the seven light pillars (where they fade out, just below the floor's centre) and each one's own gradient. */
    val pillarBase = lay.cf.y + .2f * lay.ry
    val pillarTop = FloatArray(7) { lay.cf.y - PILLAR_H[it] * h }
    val pillars: Array<Brush> = Array(7) { pillarBrush(it, Color.White) }
    val pillarHalos: Array<Brush> = Array(7) { pillarBrush(it, Sakura.Primary) }

    /** Pillar [i]'s vertical gradient in [c]: 0 at its top, full just above the floor's centre, 0 again at its base. */
    private fun pillarBrush(i: Int, c: Color): Brush {
        val top = pillarTop[i]
        val peak = ((lay.cf.y - .15f * lay.ry - top) / (pillarBase - top)).coerceIn(.01f, .99f)
        return Brush.verticalGradient(0f to c.copy(alpha = 0f), peak to c, 1f to c.copy(alpha = 0f), startY = top, endY = pillarBase)
    }

    // ---- the picture ----
    val windowPlacement = Placement()
    val welcomePlacement = Placement()
    private val tmpPlacement = Placement()
    val rMax: Float = run {
        val c = lay.c
        maxOf(hypot(c.x, c.y), hypot(w - c.x, c.y), hypot(c.x, h - c.y), hypot(w - c.x, h - c.y)) + 16f * dp
    }

    /** The avatar square's centre (0..1 of the picture): the face point that travels as the window bursts open. */
    private val windowFocusX = picture?.let { avatarSquare(it.width, it.height, avatar).centerX(it.width) } ?: .5f
    private val windowFocusY = picture?.let { avatarSquare(it.width, it.height, avatar).centerY(it.height) } ?: .5f

    init {
        picture?.let { p ->
            runCatching { p.prepareToDraw() }
            windowCrop(p.width.toFloat(), p.height.toFloat(), lay, avatar, windowPlacement)
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
    /** The iris-in's progress at [ms], 0..1 (1 at once for the reduced-motion still). */
    fun irisK(ms: Float): Float = if (reduced) 1f else seg(ms, marks.hit + 30f * marks.pace, 560f * marks.pace, IntroEase.ExpoOut)

    fun backdropPlacement(ms: Float, out: Placement) {
        val m = marks
        val p = m.pace
        val ki = irisK(ms)
        val kbStart = m.hit + 590f * p
        val z = if (ms <= kbStart || reduced) (if (reduced) 1f else 1.06f) else lerp(1.06f, 1f, seg(ms, kbStart, m.e - kbStart, IntroEase.SineInOut))
        welcomeAt(z, tmpPlacement)
        val pic = picture
        if (ki >= 1f || reduced || pic == null) { out.set(tmpPlacement); return }
        irisPlacement(pic.width.toFloat(), pic.height.toFloat(), lay, windowFocusX, windowFocusY, windowPlacement, tmpPlacement, ki, irisRadius(ki), out)
    }

    /** The opening circle's radius at iris-in progress [ki]. */
    fun irisRadius(ki: Float): Float = lerp(.30f * lay.r, rMax, ki)

    /**
     * The window's content without a picture: the monogram (or, with no name or an emoji first, the sakura mon), ghosted below
     * the scan line and solid above it, at [scale] of its 52 sp. The ghost is rose brown, not faded ink: ink at a fifth of its
     * strength over the plate reads as grey. [crest] (0..1) is how far the window has become the crest: the monogram stands on a
     * pink sakura and becomes a sticker like the name (white outline, pink drop), so the character the scan found is the crest.
     */
    fun drawMonogram(scope: DrawScope, center: Offset, rw: Float, scale: Float, ghost: Float, scanY: Float, alpha: Float, crest: Float) = with(scope) {
        val run = texts.monogram
        if (run == null) {
            // The sakura mon alone: .73 of the window (.22 R), growing to .86 of the crest; faint below the scan line.
            val r = lerp(.733f, .86f, crest) * rw
            val mon: DrawScope.(Float) -> Unit = { a ->
                withTransform({ translate(center.x, center.y); scale(r, r, Offset.Zero) }) {
                    drawPath(sakura, Sakura.Primary.copy(alpha = .90f * a))
                    drawPath(sakura, Sakura.RoseBrown.copy(alpha = a), style = Stroke(lerp(.75f, 1f, crest) * dp / r))
                    if (crest > 0f) drawPath(monVeins, Sakura.White.copy(alpha = .55f * a * crest), style = Stroke(1.25f * dp / r, cap = StrokeCap.Round))
                }
                drawCircle(Sakura.White.copy(alpha = a), lerp(3f, 3.5f, crest) * dp, center)
            }
            mon((ghost * 1.6f).coerceAtMost(alpha))
            if (scanY > -1e6f) clipRect(top = -h * 4f, bottom = scanY.coerceAtMost(h * 4f)) { mon(alpha) }
            return@with
        }
        if (crest > 0f) {
            // The backing plate: the sakura at .86 of the crest in pink, with a fine rose-brown outline.
            val r = .86f * rw
            withTransform({ translate(center.x, center.y); scale(r, r, Offset.Zero) }) {
                drawPath(sakura, Sakura.Primary.copy(alpha = .90f * alpha * crest))
                drawPath(sakura, Sakura.RoseBrown.copy(alpha = alpha * crest), style = Stroke(1f * dp / r, join = StrokeJoin.Round))
            }
        }
        val at = Offset(center.x - run.advance[0] / 2f, center.y + .36f * run.fontPx - run.baseline)
        withTransform({ scale(scale, scale, center) }) {
            if (crest > 0f) {
                // Under the scale, so the outline is .11 em and the drop .045 em of the crest's 68 sp.
                drawGlyph(run, 0, at + Offset(0f, .045f * run.fontPx), Sakura.Primary, .5f * alpha * crest, stroke = monogramStroke)
                drawGlyph(run, 0, at + Offset(0f, .045f * run.fontPx), Sakura.Primary, .5f * alpha * crest)
                drawGlyph(run, 0, at, Sakura.White, alpha * crest, stroke = monogramStroke)
            }
            val g = (ghost * 1.6f).coerceAtMost(alpha) * (1f - crest)
            if (g > 0f) drawGlyph(run, 0, at, Sakura.RoseBrown, g)
            if (scanY > -1e6f) clipRect(top = -h * 4f, bottom = ((scanY - center.y) / scale + center.y).coerceAtMost(h * 4f)) {
                drawGlyph(run, 0, at, Sakura.Text, alpha)
            }
        }
    }

    private val monogramStroke: Stroke? = texts.monogram?.let { stickerStroke(it) }

    val sakura: Path = sakuraSilhouette()

    /** Five short white lines from the mon's centre toward its petal notches: the flower's stamens, so it reads as sakura. */
    private val monVeins = Path().apply {
        for (i in 0 until 5) {
            val a = Math.toRadians(-90.0 + 72.0 * i)
            moveTo((kotlin.math.cos(a) * .16).toFloat(), (kotlin.math.sin(a) * .16).toFloat())
            lineTo((kotlin.math.cos(a) * .52).toFloat(), (kotlin.math.sin(a) * .52).toFloat())
        }
    }
    private val sparkle: Path = Kawaii.sparklePath()

    fun drawSparkle(scope: DrawScope, at: Offset, r: Float, color: Color, alpha: Float, rot: Float = 0f) = with(scope) {
        if (alpha <= 0f || r <= 0f) return@with
        withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(r, r, Offset.Zero) }) {
            // Aqua always sits on a white halo: its soft edge straight over pink mixes to grey.
            if (color.copy(alpha = 1f) == Sakura.Aqua) withTransform({ scale(1.6f, 1.6f, Offset.Zero) }) {
                drawPath(sparkle, Color.White.copy(alpha = (1.6f * alpha).coerceAtMost(1f)))
            }
            drawPath(sparkle, color.copy(alpha = color.alpha * alpha))
        }
    }

    /** The exit's origin: the tap point, else the crest, or the upper third over a backdrop. */
    fun exitOrigin(): Offset = if (backdrop || !lay.crest) Offset(w / 2f, .30f * h) else lay.crestC

    /**
     * When the name starts to stamp in: at d + 40 ms, or once the floor circle sinking under it has its top 24 dp below the name's
     * last baseline (its first glyph rose through the data ring's text and the petals), but at most 120 ms later (40 at 1.5 s,
     * where the welcome is short).
     */
    val nameStart: Float = run {
        val m = marks
        val first = m.d + 40f * m.pace
        if (reduced) return@run first
        val clear = texts.nameBaseline(texts.nameLines.size - 1) + 24f * dp
        val last = first + (if (m.compact) 40f else 120f) * m.pace
        var t = first
        while (t < last) {
            val k = floorIn(t)
            if (lerp(lay.c.y, lay.cf.y, k) - lerp(lay.r, lay.ry, k) * sigilScale(t, m) >= clear) break
            t += 4f
        }
        min(t, last)
    }

    /** How much later than d + 40 ms the name starts; the welcome words, divider and ID row follow it by as much. */
    val welcomeDelay: Float get() = nameStart - (marks.d + 40f * marks.pace)

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

/**
 * L1–L4: sky, light leak, halftone, blooms and rays, the picture backdrop with its scrims, then the magic half's lights. The
 * system half's light (the bloom and rays at C, the halftone) lies under the picture: drawn over it, they washed the art inside
 * the opening circle to a lavender grey. Only the top-layer flash tints the picture, and it is gone by hit + 380 ms.
 */
internal fun DrawScope.drawField(sc: IntroScene, ms: Float, amb: Float, place: Placement) {
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val R = L.r
    val skyIn = seg(ms, 40f, 400f, IntroEase.SineInOut)
    if (skyIn > 0f) {
        drawRect(sc.sky, alpha = skyIn)
        drawRect(sc.leak, alpha = skyIn)
    }
    val fm = sc.magic(ms)
    // L3: halftone (stronger in the system half, so the space under the beat lines has some structure).
    if (L.halftone) {
        val sys = .80f * seg(ms, 80f, 400f)
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
    // Rays at C; over a backdrop they stay on through the hit and fade out between +200 and +700, round the opening circle.
    val raysAtC = ray * ((1f - fm) + if (sc.backdrop) fm * (1f - seg(ms, m.hit + 200f * p, 500f * p)) else 0f)
    if (raysAtC > 0f) withTransform({ translate(L.c.x, L.c.y); scale(R, R, Offset.Zero); rotate(rot, Offset.Zero) }) {
        drawPath(sc.kit.rays, sc.kit.rayBrush, alpha = raysAtC.coerceAtMost(1f))
    }
    // L2: the picture opens from the window into the full screen, at full strength (its rim is drawn over the sigil, with the
    // crest layer).
    if (sc.backdrop && ms >= m.hit + 30f * p) {
        sc.backdropPlacement(ms, place)
        val ki = sc.irisK(ms)
        if (ki < 1f) {
            val clip = sc.kit.scratch
            clip.reset()
            clip.addOval(Rect(L.c, sc.irisRadius(ki)))
            clipPath(clip) { sc.drawPictureAt(this, place, 1f) }
        } else sc.drawPictureAt(this, place, 1f)
        // The scrims arrive with the picture (not with the slower field change), so the HUD and 核驗通過 stay readable over it.
        val scrim = max(fm, ki)
        drawRect(sc.topScrim, alpha = scrim)
        drawRect(sc.bottomScrim, alpha = scrim)
    }
    if (fm <= 0f) return
    // The magic half: a top bloom, the floor's shadow and its light, and rays behind the crest (none over a picture). Over the
    // user's picture the blooms stay faint: at full strength they bleached the key art exactly when it should star.
    val breathe = if (sc.reduced) 0f else .5f + .5f * sin(amb * (2f * PI.toFloat() / 4f))
    val top = if (sc.backdrop) .12f + .03f * breathe else .55f + .10f * breathe
    withTransform({ translate(L.w / 2f, -.1f * L.h); scale(.85f * L.h, .85f * L.h, Offset.Zero) }) {
        drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = top * fm)
    }
    val fl = fm * sc.floorIn(ms)
    if (fl > 0f) {
        withTransform({ translate(L.cf.x, L.cf.y); scale(L.rx * 1.1f, L.ry * 1.1f, Offset.Zero) }) { drawCircle(sc.unitShadow, 1f, Offset.Zero, alpha = .6f * fl) }
        drawRect(sc.floorBloom, alpha = fl)
        withTransform({ translate(L.cf.x, L.cf.y); scale(L.rx * .8f, L.ry * .8f, Offset.Zero) }) { drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = .50f * fl) }
    }
    drawPillars(sc, ms, amb)
    if (!sc.backdrop && L.crest) {
        val a = ray * fm
        withTransform({ translate(L.crestC.x, L.crestC.y); scale(R, R, Offset.Zero); rotate(rot + if (sc.reduced) 0f else 2f * amb, Offset.Zero) }) {
            drawPath(sc.kit.rays, sc.kit.rayBrush, alpha = a.coerceAtMost(1f))
        }
        // The crest's own light: a white bloom of 2.2 crest radii, breathing .55 ↔ .65, so the payoff glows instead of sitting
        // on empty sky.
        val kw = sc.crestK(ms)
        if (kw > 0f) {
            val at = sc.crestCenter(kw, amb)
            val br = 2.2f * L.crestR
            withTransform({ translate(at.x, at.y); scale(br, br, Offset.Zero) }) {
                drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = (.55f + .10f * breathe) * kw)
            }
        }
    }
}

/**
 * The welcome's seven light pillars rising from the floor circle (§6.1): each grows upward from the floor over 520 ms from
 * hit + 360, 40 ms apart from the centre out, and breathes on its own rhythm. White alone on the light blush does not show (at .26,
 * and even at .40, a row scan moved by 2 to 7 levels), so each is a white core with a hotter centre line in a pink halo 2.6 times
 * as wide: light on the pink, the way a summon circle's pillars are drawn. Softer over the user's picture.
 */
private fun DrawScope.drawPillars(sc: IntroScene, ms: Float, amb: Float) {
    val m = sc.marks
    val L = sc.lay
    val halo = if (sc.backdrop) .10f else .22f
    val core = if (sc.backdrop) .30f else .70f
    val base = sc.pillarBase
    // They stand on the floor circle wherever it is: while it is still sinking they rise from it, not from below it.
    val k = sc.floorIn(ms)
    val dx = lerp(L.c.x, L.cf.x, k) - L.cf.x
    val dy = lerp(L.c.y, L.cf.y, k) - L.cf.y
    val spread = lerp(L.r, L.rx, k) / L.rx
    // From hit + 360 ms, but never before the circle has started to lie down (at 8 s the sigil stands upright until d, and the
    // pillars rose behind 核驗通過 from an empty floor).
    val start = if (sc.backdrop) m.hit + 360f * m.pace else max(m.hit + 360f * m.pace, m.d + 60f * m.pace)
    for (i in 0 until 7) {
        val g = if (sc.reduced) 1f else seg(ms, start + 40f * kotlin.math.abs(i - 3) * m.pace, 520f * m.pace, IntroEase.ExpoOut)
        if (g <= 0f) continue
        val hh = ih(i, 61)
        // Faded in over their first third, so a pillar squashed to a few pixels never shows as a block.
        val breath = (if (sc.reduced) .8f else .75f + .25f * sin(amb * (.9f + .4f * hh) + 6.28f * hh)) * (g / .35f).coerceAtMost(1f)
        val x = L.cf.x + PILLAR_X[i] * L.rx * spread
        val pw = PILLAR_W[i] * L.dp * L.s
        val top = sc.pillarTop[i]
        val tall = base - top
        withTransform({ translate(dx, dy); scale(1f, g, Offset(x, base)) }) {
            drawRect(sc.pillarHalos[i], Offset(x - 1.3f * pw, top), Size(2.6f * pw, tall), alpha = halo * breath)
            drawRect(sc.pillars[i], Offset(x - pw / 2f, top), Size(pw, tall), alpha = core * breath)
            drawRect(sc.pillars[i], Offset(x - .15f * pw, top), Size(.3f * pw, tall), alpha = .55f * core * breath)
        }
    }
}

/** The opening circle's rim (white 2.5 dp over a 12 dp glow), drawn over the sigil so it reads as the window bursting open. */
internal fun DrawScope.drawIrisRim(sc: IntroScene, ms: Float) {
    if (!sc.backdrop || sc.reduced || ms < sc.marks.hit + 30f * sc.marks.pace) return
    val ki = sc.irisK(ms)
    if (ki >= 1f) return
    val r = sc.irisRadius(ki)
    val rim = 1f - ((r / sc.rMax - .8f) / .2f).coerceIn(0f, 1f)
    if (rim <= 0f) return
    val dp = sc.lay.dp
    drawCircle(Sakura.White.copy(alpha = .25f * rim), r, sc.lay.c, style = Stroke(12f * dp))
    drawCircle(Sakura.White.copy(alpha = .95f * rim), r, sc.lay.c, style = Stroke(2.5f * dp))
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
