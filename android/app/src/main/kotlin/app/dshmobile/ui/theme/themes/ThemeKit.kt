package app.dshmobile.ui.theme.themes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.PictureTone
import app.dshmobile.ui.theme.ColorMode
import app.dshmobile.ui.theme.Drop
import app.dshmobile.ui.theme.Fill
import app.dshmobile.ui.theme.Rim
import app.dshmobile.ui.theme.Sakura
import app.dshmobile.ui.theme.Sides
import app.dshmobile.ui.theme.ThemeColors
import app.dshmobile.ui.theme.ThemeLevel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The shared theme kit (D23): shorthands for the frame tables and the colour maths every theme and its tests use.

internal val BG = Sakura.Background
internal val MILK = Sakura.MilkTea
internal val PINK = Sakura.Primary
internal val W = Color.White
internal val AQUA = Sakura.Aqua
internal val ROSE = Sakura.RoseBrown
internal val INK = Sakura.Text
internal val INK2 = Sakura.TextSecondary

/** PINK .80 over BG: the darkest pink under INK labels (4.56:1). */
internal val AccentCap = Color(0xFFE3ACBD)

/** AccentCap as palette colours: a BG base with PINK .80 over it (D20, D33). */
internal val AccentCapFills: List<Fill> = listOf(solid(BG), solid(PINK.a(.80f)))

/** This colour at alpha [v]. */
internal fun Color.a(v: Float): Color = copy(alpha = v)

internal fun solid(c: Color): Fill = Fill.Solid(c)

internal fun linear(deg: Float, vararg s: Pair<Float, Color>): Fill = Fill.Linear(deg, s.toList())

internal fun radial(fx: Float, fy: Float, r: Float, vararg s: Pair<Float, Color>): Fill = Fill.Radial(fx, fy, r, r, s.toList())

/** A rim line; [inset] is the distance of the line's centre from the shape edge, positive inside, negative outside. */
internal fun hair(w: Dp, c: Color, inset: Dp = 0.dp, dash: Pair<Dp, Dp>? = null, sides: Sides = Sides.All): Rim =
    Rim.Line(w, solid(c), inset, dash = dash, sides = sides)

/** A rim line drawn with a gradient brush (wave's split rim). */
internal fun gradRim(
    w: Dp, stops: List<Pair<Float, Color>>, deg: Float = 180f, inset: Dp = 0.dp, dx: Dp = 0.dp, dy: Dp = 0.dp,
    dash: Pair<Dp, Dp>? = null,
): Rim = Rim.Line(w, Fill.Linear(deg, stops), inset, dx, dy, dash)

internal fun soft(c: Color, blur: Dp, dy: Dp = 0.dp, spread: Dp = 0.dp): Drop = Drop.Soft(c, blur, dy = dy, spread = spread)

internal fun hard(c: Color, dx: Dp, dy: Dp): Drop = Drop.Hard(c, dx, dy)

/** The value for this colour mode: 原本的顏色 first, 六個粉色 second. */
internal fun <T> ColorMode.pick(original: T, six: T): T = if (this == ColorMode.ORIGINAL) original else six

/** Every stop's alpha multiplied by [a] (gradient rims and glows given at full strength in the tokens). */
internal fun List<Pair<Float, Color>>.alpha(a: Float): List<Pair<Float, Color>> = map { (p, c) -> p to c.copy(alpha = c.alpha * a) }

/** How many of [full] moving parts a level draws: FULL all, BALANCED half (rounded up), STILL none. */
internal fun ThemeLevel.count(full: Int): Int = when (this) {
    ThemeLevel.FULL -> full
    ThemeLevel.BALANCED -> (full + 1) / 2
    ThemeLevel.STILL -> 0
}

// ───── colour maths (web §2.3, D33) ─────

/** WCAG 2.x contrast ratio of two colours' sRGB (alpha ignored: flatten first with [over]). */
internal fun contrast(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (max(x, y) + .05f) / (min(x, y) + .05f)
}

/**
 * This colour laid over [under] (source-over in sRGB, as Compose and _web/cr.py composite). The result, like every sRGB Color,
 * has 8-bit channels: that rounding is what makes [veilFloor] reproduce web.md §2.3's table.
 */
internal fun Color.over(under: Color): Color {
    val a = alpha
    val outA = a + under.alpha * (1f - a)
    if (outA <= 0f) return Color.Transparent
    fun ch(top: Float, bottom: Float) = ((top * a + bottom * under.alpha * (1f - a)) / outA).coerceIn(0f, 1f)
    return Color(ch(red, under.red), ch(green, under.green), ch(blue, under.blue), outA.coerceIn(0f, 1f))
}

/** [top]'s RGB at alpha [a] over the opaque [under] (8-bit channels, as [over]). */
private fun mix(top: Color, a: Float, under: Color): Color = Color(
    (top.red * a + under.red * (1f - a)).coerceIn(0f, 1f),
    (top.green * a + under.green * (1f - a)).coerceIn(0f, 1f),
    (top.blue * a + under.blue * (1f - a)).coerceIn(0f, 1f),
)

/** The sRGB grey whose WCAG relative luminance is [lum] (0 black .. 1 white). */
private fun grey(lum: Float): Color {
    val l = lum.coerceIn(0f, 1f)
    val v = if (l <= .0031308f) 12.92f * l else 1.055f * l.pow(1f / 2.4f) - .055f
    return Color(v.coerceIn(0f, 1f), v.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
}

/**
 * web §2.3: the least veil alpha (≥ [designed]; ≤ .96 unless [designed] is above it) at which [ink] reaches [target]:1 on [veil]
 * laid over the picture's darkest common pixel (a grey of [tone].p05 at [pictureAlpha] over [ground]; a null tone is black), then
 * under [shade] (a colour carrying its own alpha: the vignette at its strongest where text sits). [veil]'s own alpha is ignored:
 * this finds it. Pure; a 12-step binary search, called in a veil's build(), never per frame. [target] 3.0 is the status band's
 * (§2.4.5).
 */
internal fun veilFloor(
    ink: Color, veil: Color, ground: Color, pictureAlpha: Float, tone: PictureTone?, designed: Float, shade: Color? = null,
    target: Float = 4.6f,
): Float {
    val picture = mix(grey(tone?.p05 ?: 0f), pictureAlpha, ground)
    fun ok(a: Float): Boolean {
        var sky = mix(veil, a, picture)
        if (shade != null) sky = shade.over(sky)
        return contrast(ink, sky) >= target
    }
    if (ok(designed)) return designed
    var lo = designed
    // A designed veil above the cap is the theme's own art: never return less than it.
    var hi = max(designed, VeilCap)
    if (!ok(hi)) return hi
    repeat(12) {
        val m = (lo + hi) / 2f
        if (ok(m)) hi = m else lo = m
    }
    return hi
}

/** The most a floored veil may cover the picture. */
private const val VeilCap = .96f

/** Every stop's alpha raised by (floor − the smallest stop alpha) when that is positive, capped at .96 (never lowered): the gradient keeps its shape. */
internal fun List<Pair<Float, Color>>.raisedTo(floor: Float): List<Pair<Float, Color>> {
    if (isEmpty()) return this
    val lift = floor - minOf { it.second.alpha }
    if (lift <= 0f) return this
    return map { (p, c) -> p to c.copy(alpha = max(c.alpha, min(c.alpha + lift, VeilCap))) }
}

/** The largest alpha at which a face of [tone] (null = black) keeps [ink] at 4.6:1 on [stage] (wave's watermark disc). */
internal fun faceCap(ink: Color, stage: Color, tone: PictureTone?): Float {
    val face = grey(tone?.p05 ?: 0f)
    fun ok(a: Float) = contrast(ink, mix(face, a, stage)) >= 4.6f
    if (ok(1f)) return 1f
    var lo = 0f
    var hi = 1f
    repeat(12) {
        val m = (lo + hi) / 2f
        if (ok(m)) lo = m else hi = m
    }
    return lo
}

/** The ends a six-colour lerp may run between: the eight Sakura colours. */
private val LerpEnds: List<Color> = listOf(BG, MILK, PINK, W, AQUA, ROSE, INK, INK2)

/** Allowed as themselves only, not as lerp ends: the idle mood and the error red every palette keeps. */
private val LoneColours: List<Color> = listOf(Color(0xFFF3E6E2), ThemeColors.ErrorRed)

/**
 * D33 (ruling R-F3C): [c]'s RGB (alpha ignored) is within ±[tolerance] per channel of a palette colour, of a two-colour lerp of
 * the eight Sakura colours (any pair except PINK–AQUA: sRGB pink-to-aqua greys out, tokens/sakura.md), or of #F3E6E2 or #B3261E
 * themselves.
 */
internal fun isPaletteLerp(c: Color, tolerance: Int = 3): Boolean {
    val x = c.rgb255()
    val tol = tolerance.toDouble()
    if (LoneColours.any { lone -> lone.rgb255().zip(x).all { (l, v) -> abs(l - v) <= tol } }) return true
    for (i in LerpEnds.indices) for (j in i until LerpEnds.size) {
        val a = LerpEnds[i]
        val b = LerpEnds[j]
        if ((a == PINK && b == AQUA) || (a == AQUA && b == PINK)) continue
        if (onLerp(a.rgb255(), b.rgb255(), x, tol)) return true
    }
    return false
}

private fun Color.rgb255(): List<Double> = listOf(red * 255.0, green * 255.0, blue * 255.0)

/** Whether some t in 0..1 puts a + t·(b − a) within [tol] of [x] in every channel: the t ranges of the three channels meet. */
private fun onLerp(a: List<Double>, b: List<Double>, x: List<Double>, tol: Double): Boolean {
    var lo = 0.0
    var hi = 1.0
    for (k in 0..2) {
        val d = b[k] - a[k]
        val e = x[k] - a[k]
        if (abs(d) < 1e-9) {
            if (abs(e) > tol) return false
            continue
        }
        val t1 = (e - tol) / d
        val t2 = (e + tol) / d
        lo = max(lo, min(t1, t2))
        hi = min(hi, max(t1, t2))
    }
    return lo <= hi + 1e-9
}
