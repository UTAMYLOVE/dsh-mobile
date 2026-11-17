package app.dshmobile.ui.fog

import android.animation.ValueAnimator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import app.dshmobile.ui.theme.LocalInk
import app.dshmobile.ui.theme.LocalTheme
import app.dshmobile.ui.theme.Theme
import app.dshmobile.ui.theme.Themes

/** Orbitron for English titles (variable weight), Chakra Petch for numbers and small English labels; Chinese uses the system font. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object FogFonts {
    val Orbitron: FontFamily = FontFamily(
        Font(R.font.orbitron, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.orbitron, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
    val ChakraPetch: FontFamily = FontFamily(
        Font(R.font.chakra_petch_regular, FontWeight.Normal),
        Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    )
    /**
     * Quicksand, the startup looks' Latin face (res/font/intro_latin.ttf, the same declarations as the intro's Latin type plus
     * Light 300): the drawn themes' Latin captions, labels and numbers. A variable font, so every weight is the one file.
     */
    val Quicksand: FontFamily = FontFamily(
        Font(R.font.intro_latin, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
        Font(R.font.intro_latin, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.intro_latin, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.intro_latin, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}

object FogText {
    /** Tabular figures, so counting numbers do not jitter. */
    val Number = TextStyle(fontFamily = FogFonts.ChakraPetch, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", color = Sakura.Text)
    // 0.8.0 (the owner: 「不要科技感了，來一個舒服的感覺」): labels and headings use the system's own rounded-friendly face with
    // ordinary spacing; the display faces stay only for numbers.
    val Label = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.02.em, color = Sakura.TextSecondary)
    val Heading = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.04.em, color = Sakura.Text)
}

enum class EffectLevel { FULL, BALANCED, SAVER }

/**
 * What the screen may animate or blur; see the plan's Global Constraints. Static glows are always on and not a flag.
 * [perspectiveGrid] is not a motion flag: it says how the background grid is drawn (true: projected in 3D like the mockup;
 * false, SAVER only: the same lines pre-projected and drawn flat, no 3D transform), so the system's reduced motion leaves it alone.
 */
data class Effects(
    val blur: Boolean,
    val ambient: Boolean,
    val spin: Boolean,
    val depthTransitions: Boolean,
    val countUp: Boolean,
    val pageMs: Int,
    val perspectiveGrid: Boolean = true,
)

fun effectsFor(level: EffectLevel, reducedMotion: Boolean): Effects {
    val base = when (level) {
        EffectLevel.FULL -> Effects(blur = true, ambient = true, spin = true, depthTransitions = true, countUp = true, pageMs = FogMotion.PAGE_MS)
        EffectLevel.BALANCED -> Effects(blur = false, ambient = true, spin = true, depthTransitions = true, countUp = true, pageMs = FogMotion.PAGE_MS)
        EffectLevel.SAVER -> Effects(blur = false, ambient = false, spin = false, depthTransitions = false, countUp = false, pageMs = FogMotion.SAVER_PAGE_MS, perspectiveGrid = false)
    }
    return if (reducedMotion) base.copy(ambient = false, spin = false, depthTransitions = false, countUp = false, pageMs = 0) else base
}

/** The system "remove animations" setting. */
fun systemReducedMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

val LocalEffects: ProvidableCompositionLocal<Effects> = compositionLocalOf { effectsFor(EffectLevel.FULL, false) }
val LocalBackdrop: ProvidableCompositionLocal<Backdrop?> = compositionLocalOf { null }
val LocalPageActive: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }
/** True after a while without a touch ([FogShell][app.dshmobile.ui.shell.FogShell]); a State, read where it is used (the clocks), never in composition. */
val LocalIdle: ProvidableCompositionLocal<State<Boolean>> = staticCompositionLocalOf { NeverIdle }
private val NeverIdle: State<Boolean> = mutableStateOf(false)

/**
 * [display] (Orbitron, or the worn theme's display face) only for the big English display styles; titles, body and labels keep
 * the system font so Chinese and long text stay easy to read.
 */
internal fun fogTypography(base: Typography, display: FontFamily = FogFonts.Orbitron): Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = display),
    displayMedium = base.displayMedium.copy(fontFamily = display),
    displaySmall = base.displaySmall.copy(fontFamily = display),
    headlineLarge = base.headlineLarge.copy(fontFamily = display),
    headlineMedium = base.headlineMedium.copy(fontFamily = display),
    headlineSmall = base.headlineSmall.copy(fontFamily = display),
)

/**
 * The app's theme: [theme]'s Material colours, shapes and display face, the [effects] level, and the worn theme itself
 * ([LocalTheme]). One structural path for every theme (plan D1): a branch here would dispose the whole app's state on each
 * switch between 原本的樣子 and a theme, and replay the startup animation. Under CLASSIC this one call resolves to exactly what
 * today's nested `SakuraTheme { MaterialTheme(…) }` gave (SakuraColors, SakuraShapes, Orbitron on display), and content colour
 * is left as it was; under any other theme a Text() without a colour outside a Surface reads the theme's ink (eng E31).
 */
@Composable
fun FogTheme(effects: Effects, theme: Theme = Themes.Classic, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = theme.material.scheme,
        shapes = theme.material.shapes,
        typography = fogTypography(Typography(), display = theme.type.display),
    ) {
        CompositionLocalProvider(
            LocalEffects provides effects,
            LocalTheme provides theme,
            LocalInk provides theme.colors.ink,
            LocalContentColor provides (if (theme.classic) LocalContentColor.current else theme.colors.ink),
            content = content,
        )
    }
}
