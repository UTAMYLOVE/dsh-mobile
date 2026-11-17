package app.dshmobile.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dshmobile.chat.ToolStatus
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.LocalPageActive
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Built once per size in CacheDrawScope; the returned lambda draws one frame at clock t (seconds). */
fun interface ThemeDrawing { fun build(scope: CacheDrawScope): DrawScope.(t: Float) -> Unit }

/**
 * A theme's small drawn mark (status dot, thinking dots, caret, loader, bullet…). [size] is the art's size: the layout keeps the
 * call site's slot and the art is drawn centred and unclipped (D10). It moves only when [moves]; otherwise, and whenever motion
 * is off, it shows [stillAt].
 */
@Immutable data class Mark(val size: DpSize, val drawing: ThemeDrawing, val moves: Boolean = false, val stillAt: Float = 0f)

/** A drawing along a line from [from] to [to] (dividers, rules, bar and dock edges), built once per size. */
fun interface EdgeDrawing { fun build(scope: CacheDrawScope, from: Offset, to: Offset): DrawScope.(t: Float) -> Unit }

/** The theme's lines. Each null keeps today's line there. */
@Immutable data class ThemeEdges(
    /** SimpleBar's bottom, x 12 dp..w − 12 dp at insetTop + 62 dp (PetalEdges' line). */
    val barBottom: EdgeDrawing? = null,
    val dockTop: EdgeDrawing? = null,
    /** Replaces HorizontalDivider(color = MilkTea). */
    val divider: EdgeDrawing? = null,
    /** FogPanel heading rule, Markdown rule. */
    val rule: EdgeDrawing? = null,
    /** Conversation row bottom. */
    val listRule: EdgeDrawing? = null,
    /** Under the selected tab / segmented cell. */
    val tabMark: EdgeDrawing? = null,
)

/** What the send button shows: a reply is running, or there is something to send. */
@Immutable data class SendState(val running: Boolean, val ready: Boolean)

/** The theme's marks. E11: [status], [statusStrip] and [sendDecor] return the same instance for the same argument. */
@Immutable data class ThemeMarks(
    /** Tool/search card dot slot. */
    val status: ((ToolStatus) -> Mark)? = null,
    /** Tool/search card bottom padding (D12). */
    val statusStrip: ((ToolStatus) -> EdgeDrawing)? = null,
    /** TypingBubble's dots. */
    val thinking: Mark? = null,
    /** Caret at the end of streaming text. */
    val cursor: Mark? = null,
    /** StatusPanel's LoadingMark; the 主題 row badge (still). */
    val loader: Mark? = null,
    /** FogPanel heading dot, ThinkingToggle, ModelMenu dot, idle list dot. */
    val dot: Mark? = null,
    /** Markdown bullet. */
    val bullet: Mark? = null,
    /** The running conversation dot only (D15). */
    val live: Mark? = null,
    /** Around the send button, unclipped (D11). */
    val sendDecor: ((SendState) -> Mark?)? = null,
    /** ContextBar, TodoBar, Meter, StatusLine fill, themed Slider track. */
    val progress: ProgressSkin? = null,
)

/** A themed progress bar. */
@Immutable data class ProgressSkin(
    val height: Dp, val track: Fill, val fill: Fill, val warn: Fill? = null,
    val head: Mark? = null, val ticks: Ticks? = null,
)

/** A tick meter: [count] null fills the width; [glow] is one band behind the lit range. */
@Immutable data class Ticks(
    val count: Int?, val width: Dp, val height: Dp, val gap: Dp, val on: Color, val off: Color,
    val glow: Color? = null,
)

/** The theme's controls. */
@Immutable data class ThemeControls(
    val caret: Color, val iconInk: Color,
    val switch: SwitchSkin? = null, val slider: SliderSkin? = null,
    /** StatusLine bg/fg; null = SegColors. */
    val statusSegments: List<Pair<Color, Color>>? = null,
    val tabInkOn: Color, val tabInkOff: Color,
)

/** Solid tracks (D16). */
@Immutable data class SwitchSkin(val colors: @Composable () -> SwitchColors, val thumb: Mark?)

@Immutable data class SliderSkin(val thumb: Mark, val track: ProgressSkin)

// ───── entry points (F4): drawings, marks, edges, dividers, progress bars and themed controls ─────

/** t is read only in the draw lambda (a tick redraws; never recomposes or rebuilds the cache). t null draws t = 0. */
fun Modifier.themeDrawing(d: ThemeDrawing, t: State<Float>?, front: Boolean = false): Modifier = this then ThemeDrawingElement(d, t, front)

/** A data class, so a recomposition that passes the same drawing (E11) and time keeps the node and its cache. */
private data class ThemeDrawingElement(val drawing: ThemeDrawing, val time: State<Float>?, val front: Boolean) :
    ModifierNodeElement<ThemeDrawingNode>() {
    override fun create() = ThemeDrawingNode(drawing, time, front)
    override fun update(node: ThemeDrawingNode) = node.update(drawing, time, front)
    override fun InspectorInfo.inspectableProperties() {
        name = "themeDrawing"
        properties["front"] = front
    }
}

private class ThemeDrawingNode(private var drawing: ThemeDrawing, private var time: State<Float>?, private var front: Boolean) :
    DelegatingNode() {
    private val cache = delegate(
        CacheDrawModifierNode {
            val draw = drawing.build(this)
            if (front) {
                onDrawWithContent {
                    drawContent()
                    draw(time?.value ?: 0f)
                }
            } else {
                onDrawBehind { draw(time?.value ?: 0f) }
            }
        },
    )

    fun update(drawing: ThemeDrawing, time: State<Float>?, front: Boolean) {
        if (drawing !== this.drawing || front != this.front) {
            this.drawing = drawing
            this.time = time
            this.front = front
            cache.invalidateDrawCache()
        } else if (time !== this.time) {
            this.time = time
            cache.invalidateDraw()
        }
    }
}

/** A time that never changes (a drawing's still): reading it records no snapshot read, so nothing ever redraws it. */
private class StillTime(override val value: Float) : State<Float>

/**
 * Layout size comes from [modifier] (the call site's existing slot, D10); the art is mark.size, centred, unclipped. Moves only
 * while mark.moves && live && LocalEffects.spin && LocalPageActive; otherwise shows stillAt. Holds the ThemeClock while moving.
 */
@Composable
fun ThemeMark(mark: Mark, live: Boolean, modifier: Modifier) {
    val clock = LocalThemeClock.current
    val moving = mark.moves && live && LocalEffects.current.spin && LocalPageActive.current
    clock.Hold(moving)
    val still = remember(mark.stillAt) { StillTime(mark.stillAt) }
    // The art box is the mark's own size, centred in the slot; its own layer, so a tick redraws only the mark.
    Box(modifier.then(ArtSizeElement(mark.size)).graphicsLayer { }.themeDrawing(mark.drawing, if (moving) clock.time else still))
}

/** Reports the incoming minimum size (the slot) and lays its content out at [size], centred: the art may overhang the slot. */
private data class ArtSizeElement(val size: DpSize) : ModifierNodeElement<ArtSizeNode>() {
    override fun create() = ArtSizeNode(size)
    override fun update(node: ArtSizeNode) {
        if (node.size != size) {
            node.size = size
            node.invalidateMeasurement()
        }
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "markArt"
        properties["size"] = size
    }
}

private class ArtSizeNode(var size: DpSize) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val art = measurable.measure(Constraints.fixed(size.width.roundToPx().coerceAtLeast(0), size.height.roundToPx().coerceAtLeast(0)))
        val w = constraints.minWidth
        val h = constraints.minHeight
        return layout(w, h) { art.place((w - art.width) / 2, (h - art.height) / 2) }
    }
}

/** Keeps a 1 dp layout height; the drawing may reach ≤ 7 dp each side, unclipped. CLASSIC (or null) runs [classic]. */
@Composable
fun ThemeDivider(drawing: EdgeDrawing?, modifier: Modifier = Modifier, classic: @Composable () -> Unit) {
    if (drawing == null || LocalTheme.current.classic) {
        classic()
        return
    }
    Box(modifier.fillMaxWidth().height(1.dp).then(EdgeElement(drawing, EdgeAt.Middle, 0.dp, 0.dp, null)))
}

/** Draws [drawing] along [side] of the modified box, [inset] from both ends, [gap] beyond the side (SimpleBar bottom, dock top). */
fun Modifier.edge(drawing: EdgeDrawing, side: Side, inset: Dp = 0.dp, gap: Dp = 0.dp, t: State<Float>? = null): Modifier =
    this then EdgeElement(
        drawing,
        when (side) {
            Side.Top -> EdgeAt.Top
            Side.Bottom -> EdgeAt.Bottom
            Side.Start -> EdgeAt.Start
            Side.End -> EdgeAt.End
        },
        inset, gap, t,
    )

/** Where an edge drawing runs: along a side, or through the middle of the box (a divider). */
private enum class EdgeAt { Top, Bottom, Start, End, Middle }

private data class EdgeElement(val drawing: EdgeDrawing, val at: EdgeAt, val inset: Dp, val gap: Dp, val time: State<Float>?) :
    ModifierNodeElement<EdgeNode>() {
    override fun create() = EdgeNode(this)
    override fun update(node: EdgeNode) = node.update(this)
    override fun InspectorInfo.inspectableProperties() {
        name = "edge"
        properties["at"] = at
    }
}

private class EdgeNode(private var spec: EdgeElement) : DelegatingNode() {
    private val cache = delegate(
        CacheDrawModifierNode {
            val s = spec
            val w = size.width
            val h = size.height
            val inset = s.inset.toPx()
            val gap = s.gap.toPx()
            val rtl = layoutDirection == LayoutDirection.Rtl
            // From the start end to the far end: left to right in LTR, right to left in RTL; top to bottom on the sides.
            fun across(y: Float) = if (rtl) Offset(w - inset, y) to Offset(inset, y) else Offset(inset, y) to Offset(w - inset, y)
            fun down(x: Float) = Offset(x, inset) to Offset(x, h - inset)
            val (from, to) = when (s.at) {
                EdgeAt.Top -> across(-gap)
                EdgeAt.Bottom -> across(h + gap)
                EdgeAt.Middle -> across(h / 2f)
                EdgeAt.Start -> down(if (rtl) w + gap else -gap)
                EdgeAt.End -> down(if (rtl) -gap else w + gap)
            }
            val draw = s.drawing.build(this, from, to)
            onDrawWithContent {
                drawContent()
                draw(spec.time?.value ?: 0f)
            }
        },
    )

    fun update(next: EdgeElement) {
        val old = spec
        spec = next
        if (old.drawing !== next.drawing || old.at != next.at || old.inset != next.inset || old.gap != next.gap) {
            cache.invalidateDrawCache()
        } else if (old.time !== next.time) {
            cache.invalidateDraw()
        }
    }
}

/**
 * A themed bar where today's code draws one; [classic] under CLASSIC or a null skin (E4: classic must use roles). The layout is
 * [modifier]'s (the call site's box); the skin's bar ([ProgressSkin.height] tall) is drawn centred in it, unclipped.
 */
@Composable
fun ThemeProgress(fraction: Float, warn: Boolean, modifier: Modifier, classic: @Composable () -> Unit) {
    val theme = LocalTheme.current
    val skin = theme.marks.progress
    if (theme.classic || skin == null) {
        classic()
        return
    }
    ProgressBar(skin, fraction, warn, modifier)
}

/** [skin] at [fraction] in [modifier]'s box: track and fill (or ticks), and the head mark at the fill's end (still). */
@Composable
internal fun ProgressBar(skin: ProgressSkin, fraction: Float, warn: Boolean, modifier: Modifier) {
    val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    Box(modifier.then(ProgressElement(skin, f, warn))) {
        // matchParentSize: the head takes no part in the box's size; it is laid in the whole box and placed at the fill's end.
        skin.head?.let { ThemeMark(it, live = false, Modifier.matchParentSize().then(HeadAtElement(f))) }
    }
}

private data class ProgressElement(val skin: ProgressSkin, val fraction: Float, val warn: Boolean) : ModifierNodeElement<ProgressNode>() {
    override fun create() = ProgressNode(skin, fraction, warn)
    override fun update(node: ProgressNode) = node.update(skin, fraction, warn)
    override fun InspectorInfo.inspectableProperties() {
        name = "themeProgress"
        properties["fraction"] = fraction
        properties["warn"] = warn
    }
}

private class ProgressNode(private var skin: ProgressSkin, private var fraction: Float, private var warn: Boolean) : DelegatingNode() {
    private val cache = delegate(
        CacheDrawModifierNode {
            val art = ProgressArt(this, skin)
            onDrawBehind { art.draw(this, fraction, warn) }
        },
    )

    fun update(skin: ProgressSkin, fraction: Float, warn: Boolean) {
        if (skin != this.skin) {
            this.skin = skin
            this.fraction = fraction
            this.warn = warn
            cache.invalidateDrawCache()
        } else if (fraction != this.fraction || warn != this.warn) {
            this.fraction = fraction
            this.warn = warn
            cache.invalidateDraw()
        }
    }
}

/** A progress skin's paint at one size, built once. */
private class ProgressArt(scope: CacheDrawScope, private val skin: ProgressSkin) {
    private val w = scope.size.width
    private val rtl = scope.layoutDirection == LayoutDirection.Rtl
    private val bar: Rect
    private val radius: CornerRadius
    private val track: Brush?
    private val fill: Brush?
    private val warnFill: Brush?
    private val tickW: Float
    private val tickH: Float
    private val tickPitch: Float
    private val tickCount: Int
    private val pad: Float

    init {
        val dp = scope.density
        val h = skin.height.value * dp
        val cy = scope.size.height / 2f
        bar = Rect(0f, cy - h / 2f, w, cy + h / 2f)
        radius = CornerRadius(h / 2f)
        track = fillBrush(skin.track, bar, dp)
        fill = fillBrush(skin.fill, bar, dp)
        warnFill = skin.warn?.let { fillBrush(it, bar, dp) }
        val t = skin.ticks
        tickW = (t?.width?.value ?: 0f) * dp
        tickH = (t?.height?.value ?: 0f) * dp
        tickPitch = tickW + (t?.gap?.value ?: 0f) * dp
        tickCount = when {
            t == null -> 0
            t.count != null -> max(0, t.count)
            tickPitch > 0f -> max(1, floor((w + (tickPitch - tickW)) / tickPitch).toInt())
            else -> 0
        }
        pad = 2f * dp
    }

    fun draw(d: DrawScope, f: Float, warn: Boolean) = with(d) {
        val t = skin.ticks
        if (t != null && tickCount > 0) {
            val lit = (f * tickCount).roundToInt().coerceIn(0, tickCount)
            val cy = bar.center.y
            if (t.glow != null && lit > 0) {
                val span = (lit - 1) * tickPitch + tickW
                val x0 = if (rtl) w - span else 0f
                drawRoundRect(t.glow, Offset(x0 - pad, cy - tickH / 2f - pad), Size(span + 2f * pad, tickH + 2f * pad), CornerRadius(1.5f * pad))
            }
            for (i in 0 until tickCount) {
                val x = if (rtl) w - i * tickPitch - tickW else i * tickPitch
                drawRect(if (i < lit) t.on else t.off, Offset(x, cy - tickH / 2f), Size(tickW, tickH))
            }
            return@with
        }
        track?.let { drawRoundRect(it, bar.topLeft, bar.size, radius) }
        val brush = (if (warn) warnFill else null) ?: fill ?: return@with
        val fw = f * w
        if (fw <= 0f) return@with
        drawRoundRect(brush, Offset(if (rtl) w - fw else 0f, bar.top), Size(fw, bar.height), radius)
    }
}

/** Lays its content out at no size and places it at [fraction] along the box (from the start), centred vertically. */
private data class HeadAtElement(val fraction: Float) : ModifierNodeElement<HeadAtNode>() {
    override fun create() = HeadAtNode(fraction)
    override fun update(node: HeadAtNode) {
        if (node.fraction != fraction) {
            node.fraction = fraction
            node.invalidatePlacement()
        }
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "progressHead"
        properties["fraction"] = fraction
    }
}

private class HeadAtNode(var fraction: Float) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val head = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        return layout(w, h) {
            val along = (fraction * w).roundToInt()
            val x = if (layoutDirection == LayoutDirection.Rtl) w - along else along
            head.place(x - head.width / 2, (h - head.height) / 2)
        }
    }
}

/**
 * M3 Switch/Slider with the skin; same tags, values, enabled state and semantics. CLASSIC = the exact current call (with
 * [classicColors] when the call site passes custom colours today: HelpersScreen.kt, ProjectScreen.kt). A non-CLASSIC theme with
 * a null skin = the M3 call with the theme's scheme (never classicColors, E4).
 */
@Composable
fun ThemedSwitch(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, classicColors: SwitchColors? = null,
) {
    val theme = LocalTheme.current
    val skin = if (theme.classic) null else theme.controls?.switch
    val colors = when {
        theme.classic -> classicColors ?: SwitchDefaults.colors()
        skin != null -> skin.colors()
        else -> SwitchDefaults.colors()
    }
    val thumb = skin?.thumb?.let { m -> remember(m) { @Composable { ThemeMark(m, live = false, Modifier.size(SwitchDefaults.IconSize)) } } }
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, thumbContent = thumb, enabled = enabled, colors = colors)
}

/** M3 1.4's slider slots (SliderTokens): a themed thumb and track keep the default handle's 4 × 44 dp and the 16 dp track, so the slider lays out as before. */
private val SliderThumbSlot = DpSize(4.dp, 44.dp)
private val SliderTrackSlot = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
) {
    val theme = LocalTheme.current
    val skin = if (theme.classic) null else theme.controls?.slider
    if (skin == null) {
        Slider(
            value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, valueRange = valueRange,
            steps = steps, onValueChangeFinished = onValueChangeFinished,
        )
        return
    }
    val dim = if (enabled) 1f else .38f
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, steps = steps,
        thumb = { ThemeMark(skin.thumb, live = false, Modifier.size(SliderThumbSlot).alpha(dim)) },
        track = { state -> ProgressBar(skin.track, state.fraction(), warn = false, Modifier.fillMaxWidth().height(SliderTrackSlot).alpha(dim)) },
        valueRange = valueRange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private fun SliderState.fraction(): Float {
    val span = valueRange.endInclusive - valueRange.start
    return if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
}
