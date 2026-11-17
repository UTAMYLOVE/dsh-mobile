package app.dshmobile.ui.theme

import androidx.compose.material3.SwitchColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import app.dshmobile.chat.ToolStatus

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
