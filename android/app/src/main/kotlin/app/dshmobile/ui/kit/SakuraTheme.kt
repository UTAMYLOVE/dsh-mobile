package app.dshmobile.ui.kit

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One set of the company's colours (the design tokens, 0.9.1 redesign with the owner's idol palette of 2026-11-27): a bright,
 * glossy pink ([primary]) with soft lilac ([lilac]) beside it, a pink-white page, and thin gold lines ([gold]) like a photocard's
 * foil edge. [white] is the surface of cards (white by day, a deep plum at night). Text is only ever [text] or [textSecondary].
 */
data class Palette(
    val page: Color,
    val background: Color,
    val primary: Color,
    val primaryDeep: Color,
    val lilac: Color,
    val lilacSoft: Color,
    val gold: Color,
    val milkTea: Color,
    val milkTeaSoft: Color,
    val aqua: Color,
    val aquaSoft: Color,
    val aquaInk: Color,
    val roseBrown: Color,
    val text: Color,
    val textSecondary: Color,
    val white: Color,
    val divider: Color,
    /** The soft pink → lilac of the idol pieces (練習生證, 今天要做的事, 升級典禮, 小卡); text on it stays [text]. */
    val idolFrom: Color,
    val idolTo: Color,
    val warn: Color,
    val warnLine: Color,
    val dark: Boolean,
) {
    companion object {
        val Light = Palette(
            page = Color(0xFFFFF7FA), background = Color(0xFFFFE3EF), primary = Color(0xFFFF5FA2), primaryDeep = Color(0xFFE0408A),
            lilac = Color(0xFFB9A6FF), lilacSoft = Color(0xFFEEE9FF), gold = Color(0xFFE8C27A),
            milkTea = Color(0xFFE6D3CC), milkTeaSoft = Color(0xFFF6EEEB), aqua = Color(0xFFA5DEE4), aquaSoft = Color(0xFFE3F5F7),
            aquaInk = Color(0xFF2F5F64), roseBrown = Color(0xFF8E5A6A), text = Color(0xFF4A2E3A), textSecondary = Color(0xFF6E5360),
            white = Color(0xFFFFFFFF), divider = Color(0xFFF5E6EC), idolFrom = Color(0xFFFFE0EE), idolTo = Color(0xFFEDE6FF),
            warn = Color(0xFFFFF0F0), warnLine = Color(0xFFF3C6C6), dark = false,
        )
        val Dark = Palette(
            page = Color(0xFF1E1418), background = Color(0xFF3A2430), primary = Color(0xFFFF7AB3), primaryDeep = Color(0xFFFF8FC0),
            lilac = Color(0xFFC9BAFF), lilacSoft = Color(0xFF34304A), gold = Color(0xFFE8C27A),
            milkTea = Color(0xFF5A4650), milkTeaSoft = Color(0xFF3A2E33), aqua = Color(0xFF6FB9C1), aquaSoft = Color(0xFF203638),
            aquaInk = Color(0xFFBFE9EE), roseBrown = Color(0xFFE6AFBE), text = Color(0xFFF7E9EF), textSecondary = Color(0xFFD9BFCB),
            white = Color(0xFF2A1C22), divider = Color(0xFF3A2A31), idolFrom = Color(0xFF462536), idolTo = Color(0xFF2F2A4A),
            warn = Color(0xFF3A1F24), warnLine = Color(0xFF7A3B45), dark = true,
        )
    }
}

/**
 * The company's colours shared by every screen. Read through [Sakura] so one place changes them all; [SakuraTheme] picks the
 * day or night set (the phone's dark mode). [Aqua] is a fill, never text.
 */
object Sakura {
    @Volatile var palette: Palette = Palette.Light

    val Page: Color get() = palette.page
    val Background: Color get() = palette.background
    val Primary: Color get() = palette.primary
    val PrimaryDeep: Color get() = palette.primaryDeep
    val Lilac: Color get() = palette.lilac
    val LilacSoft: Color get() = palette.lilacSoft
    val Gold: Color get() = palette.gold
    val MilkTea: Color get() = palette.milkTea
    val MilkTeaSoft: Color get() = palette.milkTeaSoft
    val Aqua: Color get() = palette.aqua
    val AquaSoft: Color get() = palette.aquaSoft
    val AquaInk: Color get() = palette.aquaInk
    val RoseBrown: Color get() = palette.roseBrown
    val Text: Color get() = palette.text
    val TextSecondary: Color get() = palette.textSecondary
    val White: Color get() = palette.white
    val Divider: Color get() = palette.divider
    val Warn: Color get() = palette.warn
    val WarnLine: Color get() = palette.warnLine
    /** Card and field outlines. */
    val Border: Color get() = palette.milkTea

    /** The soft idol gradient, tinted by his accent when he has one. */
    fun idol(accent: Accent = Accent.DEFAULT): Brush =
        Brush.linearGradient(listOf(if (accent == Accent.DEFAULT) palette.idolFrom else blend(accent.fill, palette.white, if (palette.dark) .75f else .82f), palette.idolTo))

    private fun blend(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)
}

internal fun sakuraColors(p: Palette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.primary, onPrimary = p.text, primaryContainer = p.white, onPrimaryContainer = p.text, inversePrimary = p.primary,
        secondary = p.aqua, onSecondary = p.text, secondaryContainer = p.milkTea, onSecondaryContainer = p.text,
        tertiary = p.roseBrown, onTertiary = p.white, background = p.page, onBackground = p.text, surface = p.page, onSurface = p.text,
        surfaceVariant = p.white, onSurfaceVariant = p.textSecondary, surfaceTint = p.primary, surfaceBright = p.white, surfaceDim = p.milkTea,
        surfaceContainerLowest = p.white, surfaceContainerLow = p.white, surfaceContainer = p.white, surfaceContainerHigh = p.white,
        surfaceContainerHighest = p.white, outline = p.milkTea, outlineVariant = p.milkTea,
        // Errors keep Material's red: a warning must stand out from a pink screen.
        error = Color(0xFFB3261E), onError = Color.White,
    )
}

/** Radii of the redesign: fields 12, chips 16, cards 20, sheets 28; buttons are capsules. */
internal val SakuraShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The company's theme. [accent] is his 代表色 (arch T10): it becomes the primary colour (buttons, the idol pieces' tint, the
 * selected marks), made darker or lighter when its text would not read (WCAG AA); with none the bright pink. [dark] follows
 * the phone.
 */
@Composable
fun SakuraTheme(accent: Accent? = null, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    Sakura.palette = if (dark) Palette.Dark else Palette.Light
    val a = accent ?: Accent.DEFAULT
    val colors = sakuraColors(Sakura.palette).copy(primary = a.fill, onPrimary = a.onFill, inversePrimary = a.fill, surfaceTint = a.fill)
    CompositionLocalProvider(LocalAccent provides a) {
        MaterialTheme(colorScheme = colors, shapes = SakuraShapes, typography = Type.material, content = content)
    }
}
