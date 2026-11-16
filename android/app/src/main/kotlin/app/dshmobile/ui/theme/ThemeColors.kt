package app.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.dshmobile.chat.FileKind
import kotlin.math.max
import kotlin.math.min

/**
 * Every colour role a theme gives, as opaque colours: call sites add alpha (`p.accent.copy(alpha = .6f)`) exactly as they do
 * with Sakura today. Translucent strokes and fills of a particular surface belong in that surface's Frame, not here.
 * Text roles: ink, ink2, skyInk, quietInk, accentText, onAccent, onInk, onInk2, error, onError. accent and cool are never text.
 */
@Immutable
data class ThemeColors(
    val window: Color, val ground: Color, val stage: Color, val paper: Color,
    val ink: Color, val ink2: Color, val skyInk: Color, val quietInk: Color, val accentText: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color, val cool: Color,
    val milk: Color, val line: Color, val shadow: Color,
    val inkFill: Color, val onInk: Color, val onInk2: Color, val scrim: Color,
    val running: Color, val waiting: Color, val done: Color, val failed: Color, val idle: Color,
    val error: Color = ErrorRed, val onError: Color = Sakura.White,
) {
    /** True when the window is dark (粉色霓虹敘事, both modes): light bar icons, dark start window. */
    val darkWindow: Boolean get() = window.luminance() < .2f

    companion object {
        /** Kept in every theme and mode: a warning must stand out (SakuraTheme.kt's scheme error). */
        val ErrorRed = Color(0xFFB3261E)

        /** Today's colours role by role. CLASSIC draws exactly these (ThemeColorsTest pins each against Sakura). */
        val Classic = ThemeColors(
            window = Sakura.Background, ground = Sakura.Background, stage = Sakura.Background, paper = Sakura.White,
            ink = Sakura.Text, ink2 = Sakura.TextSecondary, skyInk = Sakura.TextSecondary, quietInk = Sakura.RoseBrown,
            accentText = Sakura.TextSecondary,
            accent = Sakura.Primary, accentSoft = Sakura.Background, onAccent = Sakura.Text, cool = Sakura.Aqua,
            milk = Sakura.MilkTea, line = Sakura.RoseBrown, shadow = Sakura.RoseBrown,
            inkFill = Sakura.Text, onInk = Sakura.White, onInk2 = Sakura.White, scrim = Sakura.Text,
            running = Sakura.Aqua, waiting = Sakura.Primary, done = Sakura.MilkTea, failed = Sakura.RoseBrown,
            idle = Color(0xFFF3E6E2),   // Spirits.kt's idle mood literal
        )
    }
}

/** Every palette in one place, so the contrast and six-colour tests can walk them all. Theme builders take Palettes.of(id, mode). */
object Palettes {
    /**
     * The five drawn looks: the six colours. Text straight on the sky and small accent text are INK: INK2 is 3.9:1 on the
     * drawn skies (#F4CCD4) and only 4.58 on BG.
     */
    val Drawn = ThemeColors.Classic.copy(skyInk = Sakura.Text, quietInk = Sakura.TextSecondary, accentText = Sakura.Text)

    /** 六個粉色 of a community look: the drawn set, with BG as the secondary ink on dark boxes (7.06:1 on INK). */
    val Six = Drawn.copy(onInk2 = Sakura.Background)

    val SakuraOwn = ThemeColors(
        window = Color(0xFFF7F1F8), ground = Color(0xFFF7F1F8), stage = Color(0xFFF7F1F8), paper = Color.White,
        ink = Color(0xFF4F4260), ink2 = Color(0xFF5C4E70), skyInk = Color(0xFF4F4260), quietInk = Color(0xFF5C4E70),
        accentText = Color(0xFF6E6084),
        accent = Color(0xFFF6B8CD), accentSoft = Color(0xFFFFD0E0), onAccent = Color(0xFF4F4260), cool = Color(0xFFB9A4E6),
        milk = Color(0xFFDCCFED), line = Color(0xFF6E6084), shadow = Color(0xFF785AA0),
        inkFill = Color(0xFF4F4260), onInk = Color.White, onInk2 = Color(0xFFFFD0E0), scrim = Color(0xFF4F4260),
        running = Color(0xFFB9A4E6), waiting = Color(0xFFF6B8CD), done = Color(0xFFE9E0F8), failed = Color(0xFF6E6084),
        idle = Color(0xFFF7F1F8),
    )

    val NeonOwn = ThemeColors(
        window = Color(0xFF0B0B0E), ground = Color(0xFF0B0B0E), stage = Color(0xFFF4F4F6), paper = Color.White,
        ink = Color(0xFF101014), ink2 = Color(0xFF616164), skyInk = Color(0xFF101014), quietInk = Color(0xFF616164),
        accentText = Color(0xFFC2185B),
        accent = Color(0xFFFF3D84), accentSoft = Color(0xFFFFAECB), onAccent = Color(0xFF101014), cool = Color(0xFF788CAA),
        milk = Color(0xFFD6D6D9), line = Color(0xFF101014), shadow = Color(0xFF1E141E),
        inkFill = Color(0xFF101014), onInk = Color.White, onInk2 = Color(0xFFFF6CA2), scrim = Color(0xFF0B0B0E),
        running = Color(0xFFFF3D84), waiting = Color(0xFFFFAECB), done = Color(0xFFD9D9DE), failed = Color(0xFFC2185B),
        idle = Color(0xFFF4F4F6),
    )

    /** The frame ground and window are INK: 霓虹's dark frame stays dark in 六個粉色 too. */
    val NeonSix = Six.copy(window = Sakura.Text, ground = Sakura.Text, shadow = Sakura.Text)

    val WaveOwn = ThemeColors(
        window = Color(0xFFF7F8FA), ground = Color(0xFFF7F8FA), stage = Color(0xFFF7F8FA), paper = Color.White,
        ink = Color(0xFF0B0D10), ink2 = Color(0xFF5B6672), skyInk = Color(0xFF0B0D10), quietInk = Color(0xFF5B6672),
        accentText = Color(0xFF253EDC),
        accent = Color(0xFF4D6BFE), accentSoft = Color(0xFFE3E9FF), onAccent = Color.White, cool = Color(0xFF4D6BFE),
        milk = Color(0xFFD6D7D9), line = Color(0xFF5B6672), shadow = Color(0xFF8FA1BA),
        inkFill = Color(0xFF141922), onInk = Color(0xFFF7F8FA), onInk2 = Color(0xFFB4BFCE), scrim = Color(0xFF0B0D10),
        running = Color(0xFF4D6BFE), waiting = Color(0xFF8430E8), done = Color(0xFFC9D3E2), failed = Color(0xFF5F6A7C),
        idle = Color(0xFFEEF1F7),
    )

    val AccessOwn = ThemeColors(
        window = Color(0xFFE5E3DF), ground = Color(0xFFE5E3DF), stage = Color(0xFFE5E3DF), paper = Color.White,
        ink = Color(0xFF0A0A0A), ink2 = Color(0xFF3A3936), skyInk = Color(0xFF3A3936), quietInk = Color(0xFF3A3936),
        accentText = Color(0xFF0A0A0A),
        accent = Color(0xFFFF7500), accentSoft = Color(0xFFECEAE6), onAccent = Color(0xFF0A0A0A), cool = Color.White,
        milk = Color(0xFFC2C0BD), line = Color(0xFF6D6C6A), shadow = Color(0xFF0A0A0A),
        inkFill = Color(0xFF0A0A0A), onInk = Color.White, onInk2 = Color(0xFFCFCCC6), scrim = Color(0xFF0A0A0A),
        running = Color(0xFFFF7500), waiting = Color(0xFF0A0A0A), done = Color(0xFFB4B1AB), failed = Color(0xFFFF3B30),
        idle = Color(0xFFECEAE6),
    )

    /** 檔案's small accent text is the ink box colour in 六個粉色 too (no pink text), which Six already gives. */
    val AccessSix = Six

    fun of(id: ThemeId, mode: ColorMode): ThemeColors = when (id) {
        ThemeId.CLASSIC -> ThemeColors.Classic
        ThemeId.SIGIL, ThemeId.STARDUST, ThemeId.ONE_STROKE, ThemeId.PETAL_STORM, ThemeId.TUNNEL -> Drawn
        ThemeId.WEB_SAKURA -> if (mode == ColorMode.ORIGINAL) SakuraOwn else Six
        ThemeId.WEB_NEON -> if (mode == ColorMode.ORIGINAL) NeonOwn else NeonSix
        ThemeId.WEB_WAVE -> if (mode == ColorMode.ORIGINAL) WaveOwn else Six
        ThemeId.WEB_ACCESS -> if (mode == ColorMode.ORIGINAL) AccessOwn else AccessSix
    }

    /** The swatches the 顏色 switch shows under each label (copied from the approved settings mocks). */
    val SixSwatches = listOf(Sakura.Background, Sakura.MilkTea, Sakura.Primary, Sakura.White, Sakura.Aqua, Sakura.RoseBrown)

    fun swatches(id: ThemeId, mode: ColorMode): List<Color> = if (mode == ColorMode.SIX || !id.ownColours) SixSwatches else when (id) {
        ThemeId.WEB_SAKURA -> listOf(Color(0xFFF7F1F8), Color(0xFFFFD0E0), Color(0xFFF6B8CD), Color(0xFFB9A4E6), Color(0xFF4F4260))
        ThemeId.WEB_NEON -> listOf(Color(0xFF0B0B0E), Color(0xFFFF3D84), Color(0xFFFFAECB), Color.White, Color(0xFFC2185B))
        ThemeId.WEB_WAVE -> listOf(Color(0xFFF7F8FA), Color(0xFF4D6BFE), Color(0xFF0B0D10))
        ThemeId.WEB_ACCESS -> listOf(Color(0xFFE5E3DF), Color(0xFF0A0A0A), Color(0xFFFF7500), Color.White)
        else -> SixSwatches
    }
}

/**
 * The M3 colours that every Settings control, dialog and progress bar takes. With ThemeColors.Classic and the defaults it equals
 * SakuraColors field by field (ThemeMaterialTest). [primary] is what M3 uses for TextButton/OutlinedButton labels, radio dots,
 * checkboxes, slider tracks and focused field outlines, so a theme whose accent is too light for that passes a darker one.
 */
fun themeScheme(c: ThemeColors, primary: Color = c.accent, onPrimary: Color = c.onAccent, outline: Color = primary): ColorScheme =
    lightColorScheme(
        primary = primary, onPrimary = onPrimary, primaryContainer = c.paper, onPrimaryContainer = c.ink, inversePrimary = primary,
        secondary = c.cool, onSecondary = c.ink, secondaryContainer = c.milk, onSecondaryContainer = c.ink,
        tertiary = c.line, onTertiary = c.paper,
        background = c.stage, onBackground = c.ink, surface = c.stage, onSurface = c.ink,
        surfaceVariant = c.paper, onSurfaceVariant = c.ink2, surfaceTint = primary,
        surfaceBright = c.paper, surfaceDim = c.milk,
        surfaceContainerLowest = c.paper, surfaceContainerLow = c.paper, surfaceContainer = c.paper,
        surfaceContainerHigh = c.paper, surfaceContainerHighest = c.paper,
        outline = outline, outlineVariant = c.milk,
        error = c.error, onError = c.onError,
    )

/** M3 shapes from the five corner radii in dp: extraSmall (fields), small, medium, large, extraLarge (dialogs). */
fun shapes(xs: Int, s: Int, m: Int, l: Int, xl: Int): Shapes = Shapes(
    extraSmall = RoundedCornerShape(xs.dp), small = RoundedCornerShape(s.dp), medium = RoundedCornerShape(m.dp),
    large = RoundedCornerShape(l.dp), extraLarge = RoundedCornerShape(xl.dp),
)

/**
 * The M3 skin of each theme and colour mode, [c] being that theme's palette (Palettes.of(id, mode)). CLASSIC is
 * MaterialSkin(SakuraColors, SakuraShapes) itself. The drawn looks and the 六個粉色 modes of 櫻花動漫風, 粉色霓虹敘事 and
 * 光點聚形波浪 keep the six-colour scheme, which equals SakuraColors, so their M3 controls look as today. A community look's
 * own colours pass a primary dark enough for M3 labels on white (≥ 4.5), with white on it.
 */
fun materialOf(id: ThemeId, mode: ColorMode, c: ThemeColors): MaterialSkin {
    val own = id.ownColours && mode == ColorMode.ORIGINAL
    return when (id) {
        ThemeId.CLASSIC -> MaterialSkin(SakuraColors, SakuraShapes)
        // Panels and sheets are 20 in these two looks.
        ThemeId.SIGIL, ThemeId.TUNNEL -> MaterialSkin(themeScheme(c), shapes(12, 16, 20, 20, 20))
        ThemeId.STARDUST, ThemeId.ONE_STROKE, ThemeId.PETAL_STORM -> MaterialSkin(themeScheme(c), shapes(12, 16, 22, 30, 30))
        // #F6B8CD is 1.7:1 on white, unusable for M3 labels; #6E6084 is 5.72.
        ThemeId.WEB_SAKURA -> MaterialSkin(
            if (own) themeScheme(c, primary = Color(0xFF6E6084), onPrimary = Color.White) else themeScheme(c),
            shapes(12, 18, 24, 24, 24),
        )
        // #FF3D84 is 3.36 on white: #C2185B (5.87) carries labels and white text, the outline keeps the neon pink.
        ThemeId.WEB_NEON -> MaterialSkin(
            if (own) themeScheme(c, primary = Color(0xFFC2185B), onPrimary = Color.White, outline = Color(0xFFFF3D84)) else themeScheme(c),
            shapes(9, 9, 14, 14, 14),
        )
        // #4D6BFE is 4.33 on white; #253EDC is 7.50.
        ThemeId.WEB_WAVE -> MaterialSkin(
            if (own) themeScheme(c, primary = Color(0xFF253EDC), onPrimary = Color.White, outline = Color(0xFF5B6672)) else themeScheme(c),
            shapes(8, 14, 18, 18, 18),
        )
        // Ink-box toggles and buttons, square everywhere; in 六個粉色 the ink box turns rose-ink.
        ThemeId.WEB_ACCESS -> MaterialSkin(
            if (own) themeScheme(c, primary = Color(0xFF0A0A0A), onPrimary = Color.White)
            else themeScheme(c, primary = Sakura.Text, onPrimary = Color.White),
            shapes(0, 0, 0, 0, 0),
        )
    }
}

/**
 * A file badge's fill and label. ThemeColors.Classic.badge(k) is today's badgeColors(k) table exactly (ResultCards.kt); any
 * other palette maps MD→line, DOC→inkFill, XLS→cool, PPT/PDF→accent, WEB→ink2, SVG→stage, CSV/IMG/OTHER→milk, each labelled
 * with whichever of ink/onInk has the higher contrast (≥ 4.0, D20: bold badge text; the six-colour MD badge keeps CLASSIC's
 * ROSE/White, 3.81).
 */
fun ThemeColors.badge(kind: FileKind): Pair<Color, Color> {
    if (this == ThemeColors.Classic) return classicBadge(kind)
    val fill = when (kind) {
        FileKind.MD -> line
        FileKind.DOC -> inkFill
        FileKind.XLS -> cool
        FileKind.PPT, FileKind.PDF -> accent
        FileKind.WEB -> ink2
        FileKind.SVG -> stage
        FileKind.CSV, FileKind.IMG, FileKind.OTHER -> milk
    }
    return fill to if (ratio(ink, fill) >= ratio(onInk, fill)) ink else onInk
}

/** Today's badge table (ResultCards.kt's badgeColors), kept here because ui/theme never imports ui/chat. */
private fun classicBadge(kind: FileKind): Pair<Color, Color> = when (kind) {
    FileKind.MD -> Sakura.RoseBrown to Color.White
    FileKind.DOC -> Sakura.Text to Color.White
    FileKind.XLS -> Sakura.Aqua to Sakura.Text
    FileKind.PPT -> Sakura.Primary to Color.White
    FileKind.WEB -> Sakura.TextSecondary to Color.White
    FileKind.SVG -> Sakura.Background to Sakura.Text
    FileKind.PDF -> Sakura.Primary to Color.White
    FileKind.CSV -> Sakura.MilkTea to Sakura.Text
    FileKind.IMG -> Sakura.MilkTea to Sakura.Text
    FileKind.OTHER -> Sakura.MilkTea to Sakura.Text
}

/** WCAG contrast ratio of two opaque colours. */
private fun ratio(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (max(x, y) + .05f) / (min(x, y) + .05f)
}
