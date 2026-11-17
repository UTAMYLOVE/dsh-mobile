package app.dshmobile.ui.theme.themes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.theme.BackdropScene
import app.dshmobile.ui.theme.ColorMode
import app.dshmobile.ui.theme.Corners
import app.dshmobile.ui.theme.Frame
import app.dshmobile.ui.theme.Palettes
import app.dshmobile.ui.theme.Rim
import app.dshmobile.ui.theme.ShellGeometry
import app.dshmobile.ui.theme.SystemBars
import app.dshmobile.ui.theme.Theme
import app.dshmobile.ui.theme.ThemeBackdrop
import app.dshmobile.ui.theme.ThemeColors
import app.dshmobile.ui.theme.ThemeControls
import app.dshmobile.ui.theme.ThemeDrawing
import app.dshmobile.ui.theme.ThemeFrames
import app.dshmobile.ui.theme.ThemeId
import app.dshmobile.ui.theme.ThemeType
import app.dshmobile.ui.theme.materialOf

/**
 * The placeholder every theme builder returns until its own look lands: the theme's palette on one plain recipe (white paper
 * washes with a thin line, CLASSIC's radii, no marks or edges, a still backdrop of the ground colour, or of the stage inside a
 * dark ground band where the two differ). It already keeps every rule a real theme keeps (text contrast on every frame and on
 * the sky, six-colour purity in 六個粉色), so the app is whole under any choice.
 */
internal fun provisionalTheme(id: ThemeId, mode: ColorMode): Theme {
    val c = Palettes.of(id, mode)
    val six = !id.ownColours || mode == ColorMode.SIX
    val line = hair(1.dp, c.line.a(.35f))
    val paper = listOf(c.paper.a(.86f))

    /** A frame of [fills] (solid colours over the stage) whose secondary ink is [ink2], or [ink] where that fails on the fill. */
    fun frame(corners: Corners, fills: List<Color>, rims: List<Rim> = listOf(line), ink: Color = c.ink, ink2: Color = c.ink2) =
        Frame(corners, fills.map(::solid), rims, ink = ink, ink2 = if (contrast(ink2, fills.flatOver(c.stage)) >= 4.5f) ink2 else ink)

    // A palette whose ink box is its own dark colour (not just its text ink) puts the user's words in it.
    val darkUser = c.inkFill.luminance() < .2f && c.inkFill != c.ink
    val userCorners = Corners.Round(22f, 22f, 8f, 22f)
    val user = if (darkUser) {
        frame(userCorners, listOf(c.inkFill), ink = c.onInk, ink2 = c.onInk2)
    } else {
        frame(userCorners, listOf(c.accentSoft))
    }
    // Label-bearing accent fills: AccentCap (BG with PINK .80 over it) in the six colours, the soft accent otherwise. The six-colour
    // list is AccentCapFills as colours (frame() flattens colours to check ink2); keep the two the same.
    val accent = if (six) listOf(BG, PINK.a(.80f)) else listOf(c.accentSoft)
    val chip = frame(Corners.Pill, paper)
    val chipAccent = frame(Corners.Pill, accent)
    val frames = ThemeFrames(
        panel = frame(Corners.Round(22f), paper),
        reply = frame(Corners.Round(22f, 22f, 22f, 8f), paper),
        user = user,
        chip = chip,
        chipAccent = chipAccent,
        field = frame(Corners.Pill, paper),
        dock = frame(Corners.Round(30f), paper),
        segTrack = frame(Corners.Pill, listOf(c.paper.a(.50f))),
        segOn = chipAccent,
        segTrackOff = frame(Corners.Pill, listOf(c.paper.a(.50f)), listOf(hair(1.dp, c.line.a(.42f), dash = 3.dp to 2.dp))),
        segOnOff = frame(Corners.Pill, listOf(c.paper.a(.74f)), listOf(hair(1.dp, c.milk))),
        card = frame(Corners.Round(16f), paper),
    )
    return Theme(
        id = id,
        mode = mode,
        colors = c,
        type = ThemeType(
            latin = FogFonts.ChakraPetch,
            numbers = FogText.Number.copy(color = c.ink),
            label = FogText.Label.copy(color = c.ink2),
            heading = FogText.Heading.copy(color = c.ink),
            display = FogFonts.Orbitron,
        ),
        frames = frames,
        controls = ThemeControls(caret = c.accent, iconInk = c.ink, tabInkOn = c.ink, tabInkOff = c.ink2),
        backdrop = GroundBackdrop(c),
        bars = SystemBars.of(c),
        material = materialOf(id, mode, c),
    )
}

/** These colours laid over [stage] in order. */
private fun List<Color>.flatOver(stage: Color): Color = fold(stage) { under, top -> top.over(under) }

/**
 * A still backdrop. Where the palette's ground is its stage (all but 霓虹's two), one drawing of that colour for every scene.
 * Where the ground is a dark frame round a light stage (霓虹 in both modes), the placeholder has no lens to light the stage, and
 * the sky's text (skyInk, the clear BAR frame's ink) is the stage's: so it fills the stage and keeps the ground only under the
 * status bar (0 … insetTop, behind its light icons) and the navigation bar (the bottom navInset). The insets are read in the draw
 * lambda, so a change redraws without a rebuild; the drawing is kept for the last [ShellGeometry] it was asked for (E11: the
 * same scene gets the same instance).
 */
private class GroundBackdrop(c: ThemeColors) : ThemeBackdrop {
    private val ground = c.ground
    private val stage = c.stage
    private val plain = ThemeDrawing { { drawRect(ground) } }

    /** The last shell and its framed drawing, swapped as one value. */
    @Volatile private var last: Pair<ShellGeometry, ThemeDrawing>? = null

    override val moves: Boolean get() = false

    override fun ground(scene: BackdropScene): ThemeDrawing {
        if (ground == stage) return plain
        val shell = scene.shell
        last?.let { (s, d) -> if (s === shell) return d }
        return framed(shell).also { last = shell to it }
    }

    private fun framed(shell: ShellGeometry) = ThemeDrawing {
        {
            drawRect(stage)
            val top = shell.insetTop.value.coerceIn(0f, size.height)
            if (top > 0f) drawRect(ground, size = Size(size.width, top))
            val nav = shell.navInset.value.coerceIn(0f, size.height - top)
            if (nav > 0f) drawRect(ground, topLeft = Offset(0f, size.height - nav), size = Size(size.width, nav))
        }
    }
}
