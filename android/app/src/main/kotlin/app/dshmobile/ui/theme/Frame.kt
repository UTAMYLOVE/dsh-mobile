package app.dshmobile.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.GlassRecipe
import kotlin.math.max
import kotlin.math.min

/** Every chrome surface a theme paints. Each maps to one [ThemeFrames] field. */
enum class Surf {
    BAR, BAR_BUTTON, BAR_BUTTON_ACCENT, HEADER, DOCK, FLOAT, SHEET, PROMPT, PANEL,
    USER, USER_PENDING, REPLY, LIVE, SEGMENT_USER, SEGMENT_REPLY,
    CARD, CARD_COOL, CHIP, CHIP_ACCENT, CHIP_COOL, MODEL_CHIP, NOTICE, FIELD, CODE, POPUP, TILE, BADGE,
    BUTTON_PINK, BUTTON_COOL, BUTTON_GHOST, ATTACH, SEND,
    SEG_TRACK, SEG_ON, SEG_TRACK_OFF, SEG_ON_OFF, TAB_PILL,
}

/** A theme's frames: the seven a theme must draw and the four Segmented ones, the rest defaulting to their nearest kin. */
@Immutable data class ThemeFrames(
    val panel: Frame, val reply: Frame, val user: Frame, val chip: Frame, val chipAccent: Frame, val field: Frame, val dock: Frame,
    val segTrack: Frame, val segOn: Frame, val segTrackOff: Frame, val segOnOff: Frame,
    val header: Frame = panel, val bar: Frame = Frame.clear(panel.ink, panel.ink2),
    val barButton: Frame = chip, val barButtonAccent: Frame = chipAccent, val float: Frame = chip,
    val sheet: Frame = panel, val prompt: Frame = sheet, val card: Frame = panel, val cardCool: Frame = card,
    val chipCool: Frame = chip, val modelChip: Frame = chip, val notice: Frame = chip, val code: Frame = field,
    val popup: Frame = sheet, val tile: Frame = card, val badge: Frame = chipAccent, val live: Frame = reply,
    val userPending: Frame = if (user.ink.luminance() > .5f) user.darkPending() else user.faded(.5f),
    val segmentUser: Frame = user.forSegment(), val segmentReply: Frame = reply.forSegment(),
    val buttonPink: Frame = chipAccent, val buttonCool: Frame = chipCool, val buttonGhost: Frame = chip,
    val attach: Frame = chip, val send: Frame = chipAccent, val tabPill: Frame = chipAccent,
) {
    /** Total (D31): every Surf has a frame. */
    fun of(role: Surf): Frame = when (role) {
        Surf.BAR -> bar
        Surf.BAR_BUTTON -> barButton
        Surf.BAR_BUTTON_ACCENT -> barButtonAccent
        Surf.HEADER -> header
        Surf.DOCK -> dock
        Surf.FLOAT -> float
        Surf.SHEET -> sheet
        Surf.PROMPT -> prompt
        Surf.PANEL -> panel
        Surf.USER -> user
        Surf.USER_PENDING -> userPending
        Surf.REPLY -> reply
        Surf.LIVE -> live
        Surf.SEGMENT_USER -> segmentUser
        Surf.SEGMENT_REPLY -> segmentReply
        Surf.CARD -> card
        Surf.CARD_COOL -> cardCool
        Surf.CHIP -> chip
        Surf.CHIP_ACCENT -> chipAccent
        Surf.CHIP_COOL -> chipCool
        Surf.MODEL_CHIP -> modelChip
        Surf.NOTICE -> notice
        Surf.FIELD -> field
        Surf.CODE -> code
        Surf.POPUP -> popup
        Surf.TILE -> tile
        Surf.BADGE -> badge
        Surf.BUTTON_PINK -> buttonPink
        Surf.BUTTON_COOL -> buttonCool
        Surf.BUTTON_GHOST -> buttonGhost
        Surf.ATTACH -> attach
        Surf.SEND -> send
        Surf.SEG_TRACK -> segTrack
        Surf.SEG_ON -> segOn
        Surf.SEG_TRACK_OFF -> segTrackOff
        Surf.SEG_ON_OFF -> segOnOff
        Surf.TAB_PILL -> tabPill
    }

    /** One frame per [Surf], in Surf order (the same frame may appear more than once). */
    fun all(): List<Frame> = Surf.entries.map(::of)
}

/** How one surface is painted. Drawn by FrameNode; GlassNode draws a [glass] frame's fill, rim and shadow. */
@Immutable data class Frame(
    val corners: Corners,
    /** Painted in order, clipped to the shape. */
    val fills: List<Fill>,
    /** After the fills. */
    val rims: List<Rim> = emptyList(),
    /** Before the fills, clipped OUT of the shape (Drop: E12). */
    val shades: List<Drop> = emptyList(),
    /** After the rims, unclipped. */
    val ornaments: List<Ornament> = emptyList(),
    /** Non-null only on BAR, HEADER, DOCK, FLOAT: GlassNode draws fill/rim/shade and may blur. */
    val glass: GlassRecipe? = null,
    /** Text and icons on this surface. */
    val ink: Color,
    /** Secondary text (≥ 4.5:1 on the flattened fill; = ink under LocalPictureBehind). */
    val ink2: Color,
    /** Moves along the outline (HEADER/DOCK). */
    val runner: Runner? = null,
) {
    /** Built once from [corners]. */
    val shape: Shape = corners.toShape()

    /** Fills, rims, ornaments and a glass recipe's stops × [alpha]; shades (and glass shadows) dropped; ink and ink2 kept (D13). */
    fun faded(alpha: Float): Frame = copy(
        fills = fills.map { it.fade(alpha) },
        rims = rims.map { it.fade(alpha) },
        shades = emptyList(),
        ornaments = ornaments.map { it.fade(alpha) },
        glass = glass?.let { g -> g.copy(fill = g.fill.fade(alpha), rim = g.rim?.fade(alpha), shadows = emptyList()) },
    )

    /** A long message's piece: shades dropped; Dots and Brush rims become a 1 dp line of their colour at half its alpha. */
    fun forSegment(): Frame = copy(
        shades = emptyList(),
        rims = rims.map { r ->
            when (r) {
                is Rim.Line -> r
                is Rim.Dots -> Rim.Line(1.dp, Fill.Solid(r.color.fade(.5f)))
                is Rim.Brush -> Rim.Line(1.dp, Fill.Solid(r.ink.fade(.5f)))
            }
        },
    )

    /** The same box with its rims dashed and no glow or shadow: a message still waiting for dsh, text at full contrast. */
    fun darkPending(dash: Pair<Dp, Dp> = 4.dp to 3.dp): Frame =
        copy(rims = rims.map { (it as? Rim.Line)?.copy(dash = dash) ?: it }, shades = emptyList())

    companion object {
        /** No fills, rims or shades (a transparent BAR). */
        fun clear(ink: Color, ink2: Color): Frame = Frame(Corners.Square, emptyList(), ink = ink, ink2 = ink2)
    }
}

/** A frame's outline. Radii are dp; start/end follow the layout direction. */
@Immutable sealed interface Corners {
    data class Round(val ts: Float, val te: Float = ts, val be: Float = ts, val bs: Float = ts) : Corners
    /** Percent corners; Percent(50) = pill. */
    data class Percent(val p: Int) : Corners
    data class Cut(val ts: Float, val te: Float, val be: Float, val bs: Float) : Corners
    /** Round([round]) with [leafCorner] at [leaf]. */
    data class Leaf(val round: Float, val leafCorner: Corner, val leaf: Float) : Corners
    /**
     * One-stroke bubble: Round(r) with [corner] replaced by two quads to a tip [out] dp out and [down] dp down (drawn D1). The
     * tip lies outside the layout box; only drawing uses it. On a top corner "down" points up, away from the box.
     */
    data class Flick(val r: Float, val corner: Corner, val out: Float = 6f, val down: Float = 3f) : Corners
    data object Pill : Corners
    data object Square : Corners
}

enum class Corner { TopStart, TopEnd, BottomEnd, BottomStart }

@Immutable sealed interface Fill {
    data class Solid(val color: Color) : Fill
    /** CSS angle, via cssLinear (Glass.kt). */
    data class Linear(val angleDeg: Float, val stops: List<Pair<Float, Color>>) : Fill
    /** Centre and radii as fractions of the box. */
    data class Radial(val fx: Float, val fy: Float, val rx: Float, val ry: Float, val stops: List<Pair<Float, Color>>) : Fill
    /** Centre as fractions of the box, radii in dp (web D4). */
    data class RadialDp(val fx: Float, val fy: Float, val rx: Dp, val ry: Dp, val stops: List<Pair<Float, Color>>) : Fill
    /** CRT lines, a 1 × pitch ImageShader tile. */
    data class Lines(val color: Color, val pitch: Dp, val width: Dp, val top: Float = 0f, val bottom: Float = 1f) : Fill
    /** Kawaii.halftone in one corner, path cached. */
    data class Halftone(val corner: Corner, val pitch: Dp, val maxR: Dp, val minR: Dp, val extent: Dp, val color: Color) : Fill
}

@Immutable sealed interface Rim {
    /** inset: distance of the line's centre from the edge (+ inside, − outside); dx/dy shift it (wave split rim). */
    data class Line(
        val width: Dp, val brush: Fill, val inset: Dp = 0.dp, val dx: Dp = 0.dp, val dy: Dp = 0.dp,
        val dash: Pair<Dp, Dp>? = null, val sides: Sides = Sides.All,
    ) : Rim
    /** n = max(4, round(L / pitch)) dots at (i + .5)·L/n; glow line, halo points, dots: 3 draw calls (drawn §2.6). */
    data class Dots(
        val pitch: Dp, val dot: Dp, val color: Color, val halo: Dp, val haloColor: Color,
        val glow: Color? = null, val glowWidth: Dp = 5.dp,
    ) : Rim
    /** One brush ribbon (drawn §2.6 geometry; outline polygon cached per size). */
    data class Brush(val width: Dp, val ink: Color, val under: Color?, val start: BrushStart, val overshoot: Float = .055f) : Rim
}

enum class Sides { All, TopBottom, Top, Bottom }
enum class BrushStart { TopStartPlus22dp, Tail, Clock1 }

/** A frame's shadow (named Drop because ui/chat already has a Shade, E12). */
@Immutable sealed interface Drop {
    /** fogShadowPaint. */
    data class Soft(val color: Color, val blur: Dp, val dx: Dp = 0.dp, val dy: Dp = 0.dp, val spread: Dp = 0.dp) : Drop
    /** An offset copy, no blur. */
    data class Hard(val color: Color, val dx: Dp, val dy: Dp, val grow: Dp = 0.dp) : Drop
}

@Immutable sealed interface Ornament {
    /** outset > 0: square L marks outside the corner; outset == 0: an arc of the frame's own corner plus straight arms (web D9). */
    data class CornerMarks(
        val arm: Dp, val width: Dp, val color: Color, val outset: Dp, val corners: Set<Corner>,
        val inner: Color? = null, val innerArm: Dp = 0.dp,
    ) : Ornament
    /** On [corner], or the frame's smallest corner when null (drawn D13). */
    data class TailMark(
        val kind: TailKind, val size: Dp, val color: Color, val rim: Color?, val rimWidth: Dp,
        val dot: Color? = null, val corner: Corner? = null,
    ) : Ornament
    data class Stub(val corner: Corner, val length: Dp, val thickness: Dp, val color: Color, val outside: Boolean) : Ornament
    /** Clipped inside the shape; [fill] is a Radial or RadialDp. In segment mode a top-anchored glow draws only on FIRST, a bottom one only on LAST. */
    data class Glow(val fill: Fill) : Ornament
    /** A vertical bar on the start edge from top + inset to bottom − inset (code blocks; drawn D3). */
    data class StartBar(val width: Dp, val fill: Fill, val inset: Dp = 6.dp, val ticks: Dp? = null, val brush: Boolean = false) : Ornament
    /** An EdgeDrawing along one side, [gap] dp beyond it, [length] long (null = whole side), unclipped (web D3: sakura header underline). */
    data class Edge(val side: Side, val length: Dp?, val gap: Dp, val drawing: EdgeDrawing, val align: EdgeAlign = EdgeAlign.Center) : Ornament
}

enum class TailKind { Diamond, Petal }
enum class Side { Top, Bottom, Start, End }
enum class EdgeAlign { Center, Start }

/** slowMs == 0: drawn only while busy. stillPhase: the phase shown when motion is off (D14). */
@Immutable data class Runner(val slowMs: Int, val fastMs: Int, val drawing: OutlineDrawing, val stillPhase: Float = 0f)

fun interface OutlineDrawing { fun build(scope: CacheDrawScope, outline: Path): DrawScope.(phase: Float) -> Unit }

/** The ink Markdown and plain text in a bubble use; FogTheme provides colors.ink; bubbles provide their frame ink. */
val LocalInk: ProvidableCompositionLocal<Color> = compositionLocalOf { Sakura.Text }

/**
 * D9: the shown picture is dark (p05 < .20) or unmeasurable. Provided by FogShell from the one hoisted rememberLookPicture.
 * compositionLocalOf (eng E20): FrameNode reads it with currentValueOf inside draw, so a flip redraws frames and recomposes only
 * the composables that read it (ink2Of, tab/segmented text).
 */
val LocalPictureBehind: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

// ───── shapes ─────

private fun Corners.toShape(): Shape = when (this) {
    is Corners.Round -> RoundedCornerShape(ts.dp, te.dp, be.dp, bs.dp)
    is Corners.Percent -> RoundedCornerShape(p)
    is Corners.Cut -> CutCornerShape(ts.dp, te.dp, be.dp, bs.dp)
    is Corners.Leaf -> {
        fun r(c: Corner) = (if (c == leafCorner) leaf else round).dp
        RoundedCornerShape(r(Corner.TopStart), r(Corner.TopEnd), r(Corner.BottomEnd), r(Corner.BottomStart))
    }
    is Corners.Flick -> FlickShape(r, corner, out, down)
    Corners.Pill -> RoundedCornerShape(50)
    Corners.Square -> RectangleShape
}

/** Where the flick's quads leave the two edges, measured from the corner (kit.js:135-147). */
private const val FlickReachDp = 16f
/** The quads' control points sit this far in from the corner along each edge. */
private const val FlickControlDp = 3f
/** A cubic Bézier's handle length for a quarter circle, as a fraction of the radius. */
private const val Kappa = .5522848f

/**
 * [Corners.Flick]'s outline: a rounded rectangle of radius [r] whose [corner] is replaced by two quads that meet at the tip T,
 * [out] dp beyond the corner's side and [down] dp beyond its bottom (or top). Built for the bottom-end corner of a left-to-right
 * layout and mirrored onto the asked corner.
 */
private data class FlickShape(val r: Float, val corner: Corner, val out: Float, val down: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val d = density.density
        val rr = (r * d).coerceIn(0f, min(w, h) / 2f)
        val reach = (FlickReachDp * d).coerceAtMost(min(w, h) - rr).coerceAtLeast(0f)
        val control = (FlickControlDp * d).coerceAtMost(reach)
        val k = rr * (1f - Kappa)
        val endSide = corner == Corner.TopEnd || corner == Corner.BottomEnd
        val left = endSide == (layoutDirection == LayoutDirection.Rtl)
        val top = corner == Corner.TopStart || corner == Corner.TopEnd
        fun x(v: Float) = if (left) w - v else v
        fun y(v: Float) = if (top) h - v else v
        val path = Path().apply {
            moveTo(x(w + out * d), y(h + down * d))
            quadraticTo(x(w - control), y(h), x(w - reach), y(h))
            lineTo(x(rr), y(h))
            cubicTo(x(k), y(h), x(0f), y(h - k), x(0f), y(h - rr))
            lineTo(x(0f), y(rr))
            cubicTo(x(0f), y(k), x(k), y(0f), x(rr), y(0f))
            lineTo(x(w - rr), y(0f))
            cubicTo(x(w - k), y(0f), x(w), y(k), x(w), y(rr))
            lineTo(x(w), y(h - reach))
            quadraticTo(x(w), y(h - control), x(w + out * d), y(h + down * d))
            close()
        }
        return Outline.Generic(path)
    }
}

// ───── fading ─────

private fun Color.fade(a: Float): Color = copy(alpha = alpha * a)

private fun List<Pair<Float, Color>>.fade(a: Float): List<Pair<Float, Color>> = map { (p, c) -> p to c.fade(a) }

private fun Fill.fade(a: Float): Fill = when (this) {
    is Fill.Solid -> copy(color = color.fade(a))
    is Fill.Linear -> copy(stops = stops.fade(a))
    is Fill.Radial -> copy(stops = stops.fade(a))
    is Fill.RadialDp -> copy(stops = stops.fade(a))
    is Fill.Lines -> copy(color = color.fade(a))
    is Fill.Halftone -> copy(color = color.fade(a))
}

private fun Rim.fade(a: Float): Rim = when (this) {
    is Rim.Line -> copy(brush = brush.fade(a))
    is Rim.Dots -> copy(color = color.fade(a), haloColor = haloColor.fade(a), glow = glow?.fade(a))
    is Rim.Brush -> copy(ink = ink.fade(a), under = under?.fade(a))
}

private fun Ornament.fade(a: Float): Ornament = when (this) {
    is Ornament.CornerMarks -> copy(color = color.fade(a), inner = inner?.fade(a))
    is Ornament.TailMark -> copy(color = color.fade(a), rim = rim?.fade(a), dot = dot?.fade(a))
    is Ornament.Stub -> copy(color = color.fade(a))
    is Ornament.Glow -> copy(fill = fill.fade(a))
    is Ornament.StartBar -> copy(fill = fill.fade(a))
    // An EdgeDrawing's colours are its own: it is drawn through one alpha layer instead.
    is Ornament.Edge -> copy(drawing = (drawing as? FadedEdge)?.let { it.copy(alpha = it.alpha * a) } ?: FadedEdge(drawing, a))
}

/** How far past its line an edge drawing may reach (glow bands are at most 7 dp each side); the fade layer covers it. */
private val EdgeReach = 16.dp

/** [drawing] drawn through one layer at [alpha]; the layer's paint and bounds are built once per size, in [build]. */
private data class FadedEdge(val drawing: EdgeDrawing, val alpha: Float) : EdgeDrawing {
    override fun build(scope: CacheDrawScope, from: Offset, to: Offset): DrawScope.(t: Float) -> Unit {
        val inner = drawing.build(scope, from, to)
        val reach = with(scope) { EdgeReach.toPx() }
        val bounds = Rect(
            min(from.x, to.x) - reach, min(from.y, to.y) - reach,
            max(from.x, to.x) + reach, max(from.y, to.y) + reach,
        )
        val paint = Paint().also { it.alpha = alpha }
        return { t ->
            drawIntoCanvas { canvas ->
                canvas.saveLayer(bounds, paint)
                inner(this, t)
                canvas.restore()
            }
        }
    }
}
