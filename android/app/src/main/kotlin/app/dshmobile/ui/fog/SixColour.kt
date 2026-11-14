package app.dshmobile.ui.fog

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Moves a picture's colours to the app's six (#FEDFE1 #D7C4BB #DC9FB4 #FFFFFF #A5DEE4 #9E7A7A) while keeping what makes it the
 * same picture: its light and shade, whites and greys, and skin. In OKLab, each pixel keeps its lightness (a little brighter, and
 * the darkest lifted so no black is left) and its hue family moves: reds, magentas and purples to the pink (the deepest of them to
 * light aqua), golds half way to milk tea, greens and blues to aqua, never more saturated than that palette colour; blacks become
 * a soft plum. Skin (warm, softly coloured, light) is
 * left alone: the owner's first try turned skin pink (「膚色都沒了」, 2026-11-15).
 */
object SixColour {
    private class Lch(val l: Float, val c: Float, val h: Float)

    private val toLinear = FloatArray(256) { i -> val c = i / 255f; if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f) }
    private const val OUT_STEPS = 4096
    private val toSrgb = IntArray(OUT_STEPS + 1) { i ->
        val c = i.toFloat() / OUT_STEPS
        val s = if (c <= .0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - .055f
        (s * 255f + .5f).toInt().coerceIn(0, 255)
    }

    private fun lab(r: Int, g: Int, b: Int, out: FloatArray) {
        val lr = toLinear[r]; val lg = toLinear[g]; val lb = toLinear[b]
        val l = cbrt(.4122214708f * lr + .5363325363f * lg + .0514459929f * lb)
        val m = cbrt(.2119034982f * lr + .6806995451f * lg + .1073969566f * lb)
        val s = cbrt(.0883024619f * lr + .2817188376f * lg + .6299787005f * lb)
        out[0] = .2104542553f * l + .7936177850f * m - .0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + .4505937099f * s
        out[2] = .0259040371f * l + .7827717662f * m - .8086757660f * s
    }

    private fun channel(v: Float): Int = toSrgb[(v.coerceIn(0f, 1f) * OUT_STEPS + .5f).toInt()]

    private fun rgb(lab0: Float, lab1: Float, lab2: Float, alpha: Int): Int {
        val l = (lab0 + .3963377774f * lab1 + .2158037573f * lab2).let { it * it * it }
        val m = (lab0 - .1055613458f * lab1 - .0638541728f * lab2).let { it * it * it }
        val s = (lab0 - .0894841775f * lab1 - 1.2914855480f * lab2).let { it * it * it }
        val r = channel(4.0767416621f * l - 3.3077115913f * m + .2309699292f * s)
        val g = channel(-1.2684380046f * l + 2.6097574011f * m - .3413193965f * s)
        val b = channel(-.0041960863f * l - .7034186147f * m + 1.7076147010f * s)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lch(argb: Long): Lch {
        val v = FloatArray(3)
        lab(((argb shr 16) and 0xFF).toInt(), ((argb shr 8) and 0xFF).toInt(), (argb and 0xFF).toInt(), v)
        return Lch(v[0], hypot(v[1], v[2]), deg(atan2(v[2], v[1])))
    }

    private fun deg(rad: Float): Float = (Math.toDegrees(rad.toDouble()).toFloat() + 360f) % 360f
    private fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()

    private val PINK = lch(0xFFDC9FB4)
    private val AQUA = lch(0xFFA5DEE4)
    private val MILK = lch(0xFFD7C4BB)
    private val ROSE = lch(0xFF9E7A7A)
    private val pinkX = cos(rad(PINK.h)); private val pinkY = sin(rad(PINK.h))
    private val milkX = cos(rad(MILK.h)); private val milkY = sin(rad(MILK.h))
    private val aquaX = cos(rad(AQUA.h)); private val aquaY = sin(rad(AQUA.h))
    private val plumX = cos(rad(PINK.h - 20f)); private val plumY = sin(rad(PINK.h - 20f))
    private val floor = ROSE.l * .55f
    private const val BRIGHT = .85f
    const val DEFAULT_AQUA = .55f

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    /** 1 at hue [centre], falling to 0 at [width] degrees away (around the circle). */
    private fun near(h: Float, centre: Float, width: Float): Float {
        val d = kotlin.math.abs(((h - centre + 540f) % 360f) - 180f)
        return clamp01(1f - d / width)
    }

    /** How much of a pixel is skin: warm hue, soft colour, not dark. Kept as it is. */
    internal fun skin(l: Float, c: Float, h: Float): Float {
        // Fully kept within 25 degrees of a peach hue, fading out by 55 (light pinkish skin sits near 15-25).
        val d = kotlin.math.abs(((h - 35f + 540f) % 360f) - 180f)
        return clamp01((55f - d) / 30f) * clamp01((.16f - c) / .05f) * clamp01((l - .42f) / .15f)
    }

    /**
     * [pixels] (ARGB) recoloured in place by [strength] (0 = unchanged, 1 = fully moved to the six colours). [aqua] (0..1) is how
     * much of the deep reds turns light aqua instead of pink: the owner's reference picture (2026-11-15) had a pink-and-aqua robe.
     * Alpha is kept.
     */
    fun recolour(pixels: IntArray, strength: Float, aqua: Float = DEFAULT_AQUA) {
        val k0 = clamp01(strength)
        if (k0 == 0f) return
        val deepMax = clamp01(aqua)
        val v = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24
            lab((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, v)
            val l = v[0]; val a = v[1]; val b = v[2]
            val c = hypot(a, b)
            val h = deg(atan2(b, a))
            val k = k0 * (1f - skin(l, c, h))
            if (k <= 0f) continue
            val wp = max(near(h, 25f, 45f), near(h, 340f, 60f)) + 1e-4f
            val wm = near(h, 80f, 35f)
            val wa = near(h, 210f, 95f)
            val tot = wp + wm + wa
            val deep = clamp01((.5f - l) / .08f) * deepMax * clamp01((c - .08f) / .05f) * (wp / tot)
            // Golds go half way to milk tea (the reference kept its gold).
            val ox = if (c > 1e-6f) a / c else 0f
            val oy = if (c > 1e-6f) b / c else 0f
            var ux = (wp * pinkX + wm * (milkX + ox) / 2f + wa * aquaX) / tot
            var uy = (wp * pinkY + wm * (milkY + oy) / 2f + wa * aquaY) / tot
            val n = max(hypot(ux, uy), 1e-6f)
            ux /= n; uy /= n
            val cmax = (wp * PINK.c + wm * MILK.c * 1.8f + wa * AQUA.c) / tot
            // Pastel: never more colourful than the palette colour; the darks take a soft plum.
            val c2 = max(min(c, cmax * 1.15f), ROSE.c * 1.6f * clamp01(1f - l * 1.2f))
            val l2 = floor + l.pow(BRIGHT) * (1f - floor)
            // The coloured route: pink/milk tea/aqua, with the deep reds lerped (as colours, so never through violet) to light aqua.
            val cl = l2 + (max(l2, .8f) - l2) * deep
            val ca = c2 * ux + (AQUA.c * aquaX - c2 * ux) * deep
            val cb = c2 * uy + (AQUA.c * aquaY - c2 * uy) * deep
            // Greys and blacks: a plum between rose brown and the pink.
            val grey = clamp01(1f - c / .03f)
            val nl = cl * (1f - grey) + l2 * grey
            val na = ca * (1f - grey) + c2 * plumX * grey
            val nb = cb * (1f - grey) + c2 * plumY * grey
            pixels[i] = rgb(l + (nl - l) * k, a + (na - a) * k, b + (nb - b) * k, alpha)
        }
    }

    /** A recoloured copy of [source] (the source is left as it is). */
    fun recolour(source: Bitmap, strength: Float, aqua: Float = DEFAULT_AQUA): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        recolour(pixels, strength, aqua)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
