package app.dshmobile.ui.kit

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The app's accent (arch T10): his 代表色 as the fill of buttons, the card band and the selected marks, with the text colour
 * that goes on it. [adjusted] says the colour he picked was made lighter or darker to be readable.
 */
data class Accent(val fill: Color, val onFill: Color, val adjusted: Boolean = false) {
    companion object {
        /** No 代表色 yet: the Sakura pink as it always was. */
        val DEFAULT = Accent(Sakura.Primary, Sakura.Text)
    }
}

/** The accent the screens draw with ([SakuraTheme] provides it). */
val LocalAccent = staticCompositionLocalOf { Accent.DEFAULT }

/**
 * 代表色 → accent. Text on the fill is [Sakura.Text] or white, whichever reads better; when neither reaches [MIN_CONTRAST]
 * (WCAG 4.5:1), the colour's OKLab lightness is moved (hue and chroma kept, as far as sRGB allows) by the smallest step that
 * makes one of them readable.
 */
object MemberColour {
    const val MIN_CONTRAST = 4.5
    private const val HEX = "#%02X%02X%02X"
    private const val SAKURA_PINK = "#F4A7B9"
    private const val LAVENDER = "#C7A6E8"
    private const val SKY = "#8EC5FC"
    private const val MINT = "#98DDCA"
    private const val LEMON = "#FFE680"
    private const val CORAL = "#FF9F80"
    private const val WINE = "#8E2440"
    private const val MIDNIGHT = "#233A7A"

    /** The palette he picks from (藝名 and 代表色 are his; names are what the chips say). */
    val PRESETS: List<Pair<String, String>> = listOf(
        "櫻花粉" to SAKURA_PINK, "薰衣草紫" to LAVENDER, "天空藍" to SKY, "薄荷綠" to MINT,
        "檸檬黃" to LEMON, "珊瑚橘" to CORAL, "酒紅" to WINE, "午夜藍" to MIDNIGHT,
    )

    fun parse(hex: String?): Color? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        val v = h.toLongOrNull(16) ?: return null
        return Color(0xFF000000 or v)
    }

    fun hex(c: Color): String =
        String.format(java.util.Locale.ROOT, HEX, (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())

    /** The accent for [hex]; [Accent.DEFAULT] when he has none (or it does not parse). */
    fun accent(hex: String?): Accent = parse(hex)?.let(::accentOf) ?: Accent.DEFAULT

    fun accentOf(c: Color): Accent {
        readable(c)?.let { return Accent(c, it) }
        val lab = toOklab(c)
        for (step in 1..100) {
            val d = step / 100.0
            for (l in listOf(lab[0] + d, lab[0] - d)) {
                if (l !in 0.0..1.0) continue
                val moved = fromOklab(doubleArrayOf(l, lab[1], lab[2]))
                readable(moved)?.let { return Accent(moved, it, adjusted = true) }
            }
        }
        return Accent(Color.Black, Color.White, adjusted = true)
    }

    /** The text colour that reads on [fill] at [MIN_CONTRAST] (Sakura.Text first), or null. */
    private fun readable(fill: Color): Color? = when {
        contrast(fill, Sakura.Text) >= MIN_CONTRAST -> Sakura.Text
        contrast(fill, Color.White) >= MIN_CONTRAST -> Color.White
        else -> null
    }

    /** WCAG relative luminance. */
    fun luminance(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun gam(v: Double): Float = (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()

    fun toOklab(c: Color): DoubleArray {
        val r = lin(c.red)
        val g = lin(c.green)
        val b = lin(c.blue)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    fun fromOklab(lab: DoubleArray): Color {
        val l = (lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2]).pow(3)
        val m = (lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2]).pow(3)
        val s = (lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2]).pow(3)
        return Color(
            gam(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
            gam(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
            gam(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
        )
    }

    /** How far apart two colours' OKLab lightness is (tests). */
    fun lightnessShift(a: Color, b: Color): Double = abs(toOklab(a)[0] - toOklab(b)[0])
}
