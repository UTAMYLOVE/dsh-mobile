package app.dshmobile.ui.fog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.Sakura

private val CountEase = Easing { 1f - (1f - it) * (1f - it) * (1f - it) }

/**
 * A number that counts up from 0 to [value] (950 ms) and tweens from the last shown value on a change; with countUp off it is just the final
 * text. The animated value is read only while drawing, so counting never recomposes anything.
 */
@Composable
fun CountUpText(value: Double, format: (Double) -> String, style: TextStyle, modifier: Modifier = Modifier) {
    val countUp = LocalEffects.current.countUp
    val shown = remember { mutableDoubleStateOf(if (countUp) 0.0 else value) }
    LaunchedEffect(value, countUp) {
        if (!countUp) {
            shown.doubleValue = value
            return@LaunchedEffect
        }
        val from = shown.doubleValue
        if (from != value) animate(0f, 1f, animationSpec = tween(FogMotion.COUNT_MS, easing = CountEase)) { t, _ -> shown.doubleValue = from + (value - from) * t }
    }
    val measurer = rememberTextMeasurer()
    val finalText = format(value)
    val drawStyle = if (style.color == Color.Unspecified) style.copy(color = Sakura.Text) else style
    Spacer(
        modifier
            .semantics { text = AnnotatedString(finalText) }
            .layout { measurable, constraints ->
                // Sized for the final text, so the layout does not move while the digits run.
                val r = measurer.measure(finalText, drawStyle, softWrap = false, maxLines = 1)
                val p = measurable.measure(Constraints.fixed(r.size.width, r.size.height))
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .drawWithCache {
                onDrawBehind { drawText(measurer.measure(format(shown.doubleValue), drawStyle, softWrap = false, maxLines = 1)) }
            },
    )
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
        Box(
            Modifier.size(size).drawWithCache {
                FogDebug.count("ring.cache")
                val unit = this.size.minDimension / 240f
                val c = Offset(120f, 120f)
                val rose = Sakura.RoseBrown
                val tickStroke = Stroke(7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.2f, 10.74f)))
                val trackStroke = Stroke(4f)
                val arcStroke = Stroke(5f, cap = StrokeCap.Round)
                val iceStroke = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f)))
                val innerStroke = Stroke(1f)
                val trackColor = rose.copy(alpha = .18f)
                val iceColor = Sakura.Aqua.copy(alpha = .8f)
                val innerColor = rose.copy(alpha = .3f)
                // Drawn in 240-unit space under a scale, and mask filters follow the matrix, so the glow blur is given in units too.
                val glow = fogShadowPaint(Sakura.Primary, 7f).apply {
                    style = PaintingStyle.Stroke
                    strokeWidth = 5f
                    strokeCap = StrokeCap.Round
                }
                val arcRect = Rect(Offset(20f, 20f), Size(200f, 200f))
                val arcTopLeft = Offset(20f, 20f)
                val arcSize = Size(200f, 200f)
                onDrawBehind {
                    val t = clock.value
                    val p = drawn.value
                    scale(unit, unit, pivot = Offset.Zero) {
                        rotate((t / 40f % 1f) * 360f, pivot = c) { drawCircle(rose, 114f, c, style = tickStroke) }
                        drawCircle(trackColor, 100f, c, style = trackStroke)
                        if (p > 0f) {
                            drawIntoCanvas { it.drawArc(arcRect, -90f, 360f * p, false, glow) }
                            drawArc(Sakura.Primary, -90f, 360f * p, false, arcTopLeft, arcSize, style = arcStroke)
                        }
                        rotate(-(t / 26f % 1f) * 360f, pivot = c) { drawCircle(iceColor, 87f, c, style = iceStroke) }
                        drawCircle(innerColor, 77f, c, style = innerStroke)
                    }
                }
            },
        )
        center()
    }
}

/** A brush whose shader is built once: it ignores the size of the shape it paints, so drawing a growing rectangle never rebuilds it. */
internal class FixedShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
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
            .drawWithCache {
                FogDebug.count("meter.cache")
                val track = Sakura.RoseBrown.copy(alpha = .14f)
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                val glow = fogShadowPaint(color.copy(alpha = .8f), 10.dp.toPx())
                val fill = FixedShaderBrush(
                    LinearGradientShader(Offset.Zero, Offset(size.width, 0f), listOf(color.copy(alpha = .6f), color)),
                )
                onDrawBehind {
                    drawRoundRect(track, cornerRadius = r)
                    val w = size.width * shown.value
                    if (w > 0f) {
                        drawIntoCanvas { it.drawRoundRect(0f, 0f, w, size.height, r.x, r.y, glow) }
                        drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
                    }
                }
            },
    )
}
