package app.dshmobile.ui.fog

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.Sakura

private val CountEase = Easing { 1f - (1f - it) * (1f - it) * (1f - it) }

/** A number that counts up from 0 to [value] (950 ms) and tweens from the last shown value on a change; with countUp off it is just the final text. */
@Composable
fun CountUpText(value: Double, format: (Double) -> String, style: TextStyle, modifier: Modifier = Modifier) {
    val countUp = LocalEffects.current.countUp
    var shown by remember { mutableDoubleStateOf(if (countUp) 0.0 else value) }
    LaunchedEffect(value, countUp) {
        if (!countUp) {
            shown = value
            return@LaunchedEffect
        }
        val from = shown
        if (from != value) animate(0f, 1f, animationSpec = tween(FogMotion.COUNT_MS, easing = CountEase)) { t, _ -> shown = from + (value - from) * t }
    }
    Text(format(shown), style = style, modifier = modifier)
}

/** The 240-unit HUD ring of the mockup: ticks (40 s spin), track, value arc from 12 o'clock with a glow, dashed aqua ring (26 s, reverse). */
@Composable
fun HudRing(progress: Float, size: Dp, modifier: Modifier = Modifier, center: @Composable () -> Unit) {
    val effects = LocalEffects.current
    val target = progress.coerceIn(0f, 1f)
    val drawn = remember { Animatable(if (effects.countUp) 0f else target) }
    LaunchedEffect(target, effects.countUp) {
        if (!effects.countUp) drawn.snapTo(target)
        else drawn.animateTo(target, tween(FogMotion.RING_MS, if (drawn.value == 0f) FogMotion.RING_DELAY_MS else 0, FogMotion.Soft))
    }
    val spinning = effects.spin && LocalPageActive.current
    val clock = rememberFogClock(spinning, LocalIdle.current)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val unit = this.size.minDimension / 240f
            scale(unit, unit, pivot = Offset.Zero) { drawRing(drawn.value, clock.value) }
        }
        center()
    }
}

private fun DrawScope.drawRing(p: Float, t: Float) {
    val c = Offset(120f, 120f)
    val rose = Sakura.RoseBrown
    rotate((t / 40f % 1f) * 360f, pivot = c) {
        drawCircle(rose, 114f, c, style = Stroke(7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.2f, 10.74f))))
    }
    drawCircle(rose.copy(alpha = .18f), 100f, c, style = Stroke(4f))
    if (p > 0f) {
        val arc = Size(200f, 200f)
        val tl = Offset(20f, 20f)
        drawIntoCanvas { canvas ->
            val glow = Paint().apply {
                style = PaintingStyle.Stroke
                strokeWidth = 5f
                strokeCap = StrokeCap.Round
                color = Sakura.Primary
                asFrameworkPaint().maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawArc(androidx.compose.ui.geometry.Rect(tl, arc), -90f, 360f * p, false, glow)
        }
        drawArc(Sakura.Primary, -90f, 360f * p, false, tl, arc, style = Stroke(5f, cap = StrokeCap.Round))
    }
    rotate(-(t / 26f % 1f) * 360f, pivot = c) {
        drawCircle(Sakura.Aqua.copy(alpha = .8f), 87f, c, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f))))
    }
    drawCircle(rose.copy(alpha = .3f), 77f, c, style = Stroke(1f))
}

/** A 7 dp bar; the fill runs from pink (or aqua when [ice]) at 60% to full colour and grows over 1.1 s. */
@Composable
fun Meter(progress: Float, ice: Boolean = false, modifier: Modifier = Modifier) {
    val countUp = LocalEffects.current.countUp
    val target = progress.coerceIn(0f, 1f)
    val shown = remember { Animatable(if (countUp) 0f else target) }
    LaunchedEffect(target, countUp) {
        if (countUp) shown.animateTo(target, tween(FogMotion.METER_MS, easing = FogMotion.EaseOut)) else shown.snapTo(target)
    }
    val color = if (ice) Sakura.Aqua else Sakura.Primary
    Box(
        modifier
            .fillMaxWidth()
            .height(7.dp)
            .drawBehind {
                drawRoundRect(Sakura.RoseBrown.copy(alpha = .14f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                val w = size.width * shown.value
                if (w > 0f) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawIntoCanvas { canvas ->
                        val glowPaint = Paint().apply {
                            this.color = color.copy(alpha = .8f)
                            asFrameworkPaint().maskFilter = BlurMaskFilter(10.dp.toPx() / 2f, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.drawRoundRect(0f, 0f, w, size.height, r.x, r.y, glowPaint)
                    }
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(color.copy(alpha = .6f), color), startX = 0f, endX = w),
                        size = Size(w, size.height),
                        cornerRadius = r,
                    )
                }
            },
    )
}
