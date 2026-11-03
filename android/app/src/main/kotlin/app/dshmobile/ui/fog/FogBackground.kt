package app.dshmobile.ui.fog

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.Sakura

object FogSemantics {
    /** True while a glass surface actually blurs what is behind it. */
    val Blur = SemanticsPropertyKey<Boolean>("FogBlur")
    /** True while the background (glows, grid) is being animated. */
    val Animating = SemanticsPropertyKey<Boolean>("FogAnimating")
}

/** The background drawn into a [GraphicsLayer], which [glass] surfaces blur behind themselves, plus where that layer sits in the window. */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null

    internal fun originInWindow(): Offset? = coordinates?.takeIf { it.isAttached }?.positionInWindow()
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/**
 * Seconds of animation time, advancing only while [running]; slowed to [FogMotion.IDLE_SPEED] while [idle]. Read the returned state in a
 * draw lambda so a frame redraws without recomposing.
 */
@Composable
internal fun rememberFogClock(running: Boolean, idle: Boolean): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val speed by rememberUpdatedState(if (idle) FogMotion.IDLE_SPEED else 1f)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            val now = withInfiniteAnimationFrameNanos { it }
            time.floatValue += (now - last) / 1_000_000_000f * speed
            last = now
        }
    }
    return time
}

/** 0..1..0 over `2 * oneWay` seconds, eased in and out like CSS `alternate` + `ease-in-out`. */
private fun alternate(t: Float, oneWay: Float): Float {
    val x = (t % (2 * oneWay)) / oneWay
    val tri = if (x <= 1f) x else 2f - x
    return tri * tri * (3f - 2f * tri)
}

@Composable
fun FogBackground(backdrop: Backdrop, modifier: Modifier = Modifier) {
    val effects = LocalEffects.current
    val animating = effects.ambient && LocalPageActive.current
    val clock = rememberFogClock(animating, LocalIdle.current)
    Box(
        modifier
            .semantics { set(FogSemantics.Animating, animating) }
            .onGloballyPositioned { backdrop.coordinates = it }
            .drawWithContent {
                backdrop.layer.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop.layer)
            },
    ) {
        Box(Modifier.fillMaxSize().background(Sakura.Background))
        Canvas(Modifier.fillMaxSize()) { drawGlows(clock.value) }
        FogGrid(top = true, clock = clock)
        FogGrid(top = false, clock = clock)
    }
}

private fun DrawScope.glow(color: Color, sizeDp: Float, left: Float, top: Float, drift: Offset, scaleTo: Float, p: Float) {
    val d = sizeDp.dp.toPx()
    val s = 1f + (scaleTo - 1f) * p
    val c = Offset(left + d / 2 + drift.x.dp.toPx() * p, top + d / 2 + drift.y.dp.toPx() * p)
    scale(s, s, pivot = c) {
        drawCircle(
            Brush.radialGradient(0f to color.copy(alpha = .75f), .55f to color.copy(alpha = .38f), 1f to color.copy(alpha = 0f), center = c, radius = d / 2),
            radius = d / 2,
            center = c,
        )
    }
}

private fun DrawScope.drawGlows(t: Float) {
    val w = size.width
    val h = size.height
    glow(Sakura.Primary, 300f, w + 90.dp.toPx() - 300.dp.toPx(), -60.dp.toPx(), Offset(-70f, 80f), 1.12f, alternate(t, 18f))
    glow(Sakura.Aqua, 260f, -110.dp.toPx(), h - 120.dp.toPx() - 260.dp.toPx(), Offset(90f, -60f), .9f, alternate(t, 22f))
    glow(Sakura.MilkTea, 240f, w + 70.dp.toPx() - 240.dp.toPx(), h * .42f, Offset(-80f, -40f), 1.15f, alternate(t, 26f))
}

/** A perspective grid slab: 42% of the height, tilted 68 degrees, cells of 44 dp flowing one cell every 6 s, fading towards the horizon. */
@Composable
private fun FogGrid(top: Boolean, clock: State<Float>) {
    Box(Modifier.fillMaxSize(), contentAlignment = if (top) Alignment.TopCenter else Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(.42f)
                .layout { measurable, c ->
                    // Like the mockup's left/right -70%: the slab is 2.4x as wide as the screen so the tilted edges still reach past it.
                    val w = (c.maxWidth * 2.4f).toInt()
                    val p = measurable.measure(Constraints.fixed(w, c.maxHeight))
                    layout(c.maxWidth, p.height) { p.place(-(w - c.maxWidth) / 2, 0) }
                }
                .graphicsLayer {
                    rotationX = if (top) -68f else 68f
                    cameraDistance = 340.dp.toPx()
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, if (top) 0f else 1f)
                    alpha = .55f
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawGridLines(clock.value, reverse = top)
                    // Fade from fully visible near the screen edge (10%) to gone far away (85%).
                    val near = if (top) 0f else size.height
                    val far = if (top) size.height else 0f
                    drawRect(
                        Brush.linearGradient(.1f to Color.Black, .85f to Color.Transparent, start = Offset(0f, near), end = Offset(0f, far)),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}

private fun DrawScope.drawGridLines(t: Float, reverse: Boolean) {
    val cell = 44.dp.toPx()
    val line = 1.dp.toPx()
    val color = Color.White.copy(alpha = .95f)
    val flow = ((t / 6f) % 1f) * cell * (if (reverse) -1f else 1f)
    var x = 0f
    while (x <= size.width) {
        drawRect(color, Offset(x, 0f), androidx.compose.ui.geometry.Size(line, size.height))
        x += cell
    }
    var y = flow - cell
    while (y <= size.height + cell) {
        drawRect(color, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, line))
        y += cell
    }
}
