package app.dshmobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextMeasurer
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.Effects
import app.dshmobile.ui.fog.Intro
import app.dshmobile.ui.fog.PictureAlign
import app.dshmobile.ui.fog.PictureTone

/**
 * What a theme draws behind the pages (rendered by ThemedBackground in FogBackground.kt): a static [ground] under the picture,
 * an optional static [veil] over it, and at most one [motion] layer that reads the theme clock.
 */
@Stable interface ThemeBackdrop {
    val moves: Boolean
    val stillAt: Float get() = 0f
    /** Minimum ms between ticks while only the backdrop holds the clock (neon 33). */
    val frameMs: Long get() = 0L
    /** When idle and only the backdrop holds the clock, stop instead of slowing (neon). */
    val idlePause: Boolean get() = false
    /**
     * true: this backdrop keeps sky text ≥ 4.5 over any picture itself (sakura, neon via veilFloor; wave, access by design).
     * false: ThemedBackground adds the generic sky floor (D9).
     */
    val floorsOwnSky: Boolean get() = false
    /** Under the picture; static (recorded into LocalBackdrop when the theme blurs). Must not read t. */
    fun ground(scene: BackdropScene): ThemeDrawing
    /** Over the picture, only with a picture; static. null = PictureScrim (FogBackground.kt); [NoVeil] = nothing. */
    fun veil(scene: BackdropScene): ThemeDrawing? = null
    /** Above the veil, under the floats, outside the record; reads t. One layer. */
    fun motion(scene: BackdropScene): ThemeDrawing? = null
    val picture: PictureStage get() = PictureStage.Cover()
}

/** A veil that draws nothing (the picture shows as it is). */
val NoVeil: ThemeDrawing = ThemeDrawing { { } }

/** How much a backdrop may draw. !ambient → STILL; blur → FULL; else BALANCED. */
enum class ThemeLevel { FULL, BALANCED, STILL }

val Effects.themeLevel: ThemeLevel
    get() = when {
        !ambient -> ThemeLevel.STILL   // SAVER, reduced motion, or the jank cap at SAVER
        blur -> ThemeLevel.FULL        // only FULL blurs (effectsFor)
        else -> ThemeLevel.BALANCED
    }

/** Everything a backdrop's drawings may depend on. */
@Immutable data class BackdropScene(
    val hasPicture: Boolean, val style: BackdropStyle,
    /** Avatar crop centre in window px (null = none). */
    val face: Offset?,
    /** The face square (faceCrop of the recoloured picture). */
    val avatar: ImageBitmap?,
    /** look.opacity / 100. */
    val opacity: Float,
    val align: PictureAlign,
    /** Never the whole Effects: a governor cap or a pageMs change must not re-record static art. */
    val level: ThemeLevel,
    val shell: ShellGeometry,
    /** Number, level and name for ring texts. */
    val intro: Intro,
    /** rememberTextMeasurer() in ThemedBackground; never the hand font. */
    val measurer: TextMeasurer,
    val pictureTone: PictureTone?, val faceTone: PictureTone?,
)

/** Shell positions in window px, read in draw only. */
@Stable class ShellGeometry(
    val insetTop: State<Float>, val insetBottom: State<Float>, val barBottom: State<Float>,
    val stackTop: State<Float>, val stackBottom: State<Float>, val navInset: State<Float>, val stageTop: State<Float>,
)

/** How the user's picture is placed. */
@Immutable sealed interface PictureStage {
    /** Today's full-screen picture (Crop, BiasAlignment(0, align.bias), α = opacity/100), then filter/alphaScale/scale. */
    data class Cover(val filter: ColorFilter? = null, val alphaScale: Float = 1f, val scale: Float = 1f) : PictureStage
    /** WEB_ACCESS: a print in [box] (dp of a 360×780 frame, scaled), cover-cropped by align, α = alphaScale × opacity/80, masked. */
    data class Print(val box: Rect, val filter: ColorFilter, val alphaScale: Float, val mask: ThemeDrawing) : PictureStage
    /** WEB_WAVE: no full-screen picture; the scene still gets avatar/face/tones and FogSemantics.Picture is set. */
    data object InMark : PictureStage
}

/** The page change sweep. */
sealed interface Sweep {
    /** Today's SweepLook (FogShell.kt) with a far glow colour (web D21). */
    data class Glow(val far: Color) : Sweep
    data object None : Sweep
    /** t = progress 0..1. */
    data class Custom(val ms: Int, val drawing: ThemeDrawing) : Sweep
    companion object { val Classic: Sweep = Glow(Sakura.Primary.copy(alpha = .45f)) }
}
