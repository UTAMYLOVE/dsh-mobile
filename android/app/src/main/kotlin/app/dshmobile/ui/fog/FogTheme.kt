package app.dshmobile.ui.fog

import android.animation.ValueAnimator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import app.dshmobile.ui.theme.SakuraTheme

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
}

object FogText {
    /** Tabular figures, so counting numbers do not jitter. */
    val Number = TextStyle(fontFamily = FogFonts.ChakraPetch, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", color = Sakura.Text)
    val Label = TextStyle(
        fontFamily = FogFonts.ChakraPetch, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 0.14.em, color = Sakura.TextSecondary,
    )
    val Heading = TextStyle(
        fontFamily = FogFonts.Orbitron, fontWeight = FontWeight.Medium, fontSize = 18.sp, letterSpacing = 0.26.em, color = Sakura.Text,
    )
}

enum class EffectLevel { FULL, BALANCED, SAVER }

/** What the screen may animate or blur; see the plan's Global Constraints. Static glows are always on and not a flag. */
data class Effects(
    val blur: Boolean,
    val ambient: Boolean,
    val spin: Boolean,
    val depthTransitions: Boolean,
    val countUp: Boolean,
    val pageMs: Int,
)

fun effectsFor(level: EffectLevel, reducedMotion: Boolean): Effects {
    val base = when (level) {
        EffectLevel.FULL -> Effects(blur = true, ambient = true, spin = true, depthTransitions = true, countUp = true, pageMs = FogMotion.PAGE_MS)
        EffectLevel.BALANCED -> Effects(blur = false, ambient = true, spin = true, depthTransitions = true, countUp = true, pageMs = FogMotion.PAGE_MS)
        EffectLevel.SAVER -> Effects(blur = false, ambient = false, spin = false, depthTransitions = false, countUp = false, pageMs = FogMotion.SAVER_PAGE_MS)
    }
    return if (reducedMotion) base.copy(ambient = false, spin = false, depthTransitions = false, countUp = false, pageMs = 0) else base
}

/** The system "remove animations" setting. */
fun systemReducedMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

val LocalEffects: ProvidableCompositionLocal<Effects> = compositionLocalOf { effectsFor(EffectLevel.FULL, false) }
val LocalBackdrop: ProvidableCompositionLocal<Backdrop?> = compositionLocalOf { null }
val LocalPageActive: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }
val LocalIdle: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

private fun fogTypography(base: Typography): Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = FogFonts.Orbitron),
    displayMedium = base.displayMedium.copy(fontFamily = FogFonts.Orbitron),
    displaySmall = base.displaySmall.copy(fontFamily = FogFonts.Orbitron),
    headlineLarge = base.headlineLarge.copy(fontFamily = FogFonts.Orbitron),
    headlineMedium = base.headlineMedium.copy(fontFamily = FogFonts.Orbitron),
    headlineSmall = base.headlineSmall.copy(fontFamily = FogFonts.Orbitron),
    titleLarge = base.titleLarge.copy(fontFamily = FogFonts.ChakraPetch),
    titleMedium = base.titleMedium.copy(fontFamily = FogFonts.ChakraPetch),
    titleSmall = base.titleSmall.copy(fontFamily = FogFonts.ChakraPetch),
    bodyLarge = base.bodyLarge.copy(fontFamily = FogFonts.ChakraPetch),
    bodyMedium = base.bodyMedium.copy(fontFamily = FogFonts.ChakraPetch),
    bodySmall = base.bodySmall.copy(fontFamily = FogFonts.ChakraPetch),
    labelLarge = base.labelLarge.copy(fontFamily = FogFonts.ChakraPetch),
    labelMedium = base.labelMedium.copy(fontFamily = FogFonts.ChakraPetch),
    labelSmall = base.labelSmall.copy(fontFamily = FogFonts.ChakraPetch),
)

@Composable
fun FogTheme(effects: Effects, content: @Composable () -> Unit) {
    SakuraTheme {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            shapes = MaterialTheme.shapes,
            typography = fogTypography(MaterialTheme.typography),
        ) {
            CompositionLocalProvider(LocalEffects provides effects, content = content)
        }
    }
}
