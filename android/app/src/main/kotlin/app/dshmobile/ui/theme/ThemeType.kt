package app.dshmobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.FogText

/**
 * A theme's type accents. Chinese stays in the system font in every theme (R7); only Latin faces, Latin tracking and colours
 * change, and text sizes stay what each call site sets (E2).
 */
@Immutable
data class ThemeType(
    /** Decorative Latin: model chip, tool names, cites, captions, the tab's English line. */
    val latin: FontFamily,
    /** Colour = the palette's ink. "tnum" is a no-op (no bundled font has it): counting digits use fixed cells. */
    val numbers: TextStyle,
    /** Colour = ink2 or accentText (never accent or cool). */
    val label: TextStyle,
    /** Colour = ink. */
    val heading: TextStyle,
    /** M3 display* and headline* (fogTypography). */
    val display: FontFamily,
    /** UPPERCASE plus [captionTracking] on Latin captions (never on Chinese). */
    val upperCaptions: Boolean = false,
    val captionTracking: TextUnit = 0.16.em,
) {
    companion object {
        /** Today's type: Chakra Petch for small Latin and numbers, the system font for labels and headings, Orbitron for display. */
        val Classic = ThemeType(FogFonts.ChakraPetch, FogText.Number, FogText.Label, FogText.Heading, FogFonts.Orbitron)
    }
}
