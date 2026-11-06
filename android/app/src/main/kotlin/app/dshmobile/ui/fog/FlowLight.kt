package app.dshmobile.ui.fog

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.Sakura

/**
 * The 「彩虹流水燈」 (the owner, 2026-11-06: 「要符合我們的主題」): a light strip of the palette only — sakura pink, aqua, milk tea,
 * rose brown — that flows around a surface's outline. Faster while [fast] (the AI is working). It moves only with ambient
 * effects on the page that is showing and the screen not idle; otherwise it is the same strip, standing still. The phase is
 * read while drawing, so the flow redraws the outline only and never recomposes anything.
 */
val FlowColors: List<Color> = listOf(Sakura.Primary, Sakura.Aqua, Sakura.MilkTea, Sakura.RoseBrown.copy(alpha = .8f), Sakura.Primary)

@Composable
fun Modifier.flowLight(shape: Shape, width: Dp = 1.6.dp, fast: Boolean = false, alpha: Float = .95f): Modifier {
    val animate = LocalEffects.current.ambient && LocalPageActive.current
    val idle = LocalIdle.current
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "flow-light").animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (fast) FAST_MS else SLOW_MS, easing = LinearEasing)), label = "flow-phase",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(width.toPx())
        val span = (size.width + size.height).coerceAtLeast(1f)
        onDrawWithContent {
            drawContent()
            val p = if (idle.value) 0f else phase.value
            val shift = p * span
            val brush = Brush.linearGradient(FlowColors, start = Offset(shift - span, 0f), end = Offset(shift, size.height), tileMode = TileMode.Repeated)
            drawOutline(outline, brush, alpha = alpha, style = stroke)
        }
    }
}

const val SLOW_MS = 4_200
const val FAST_MS = 1_500

