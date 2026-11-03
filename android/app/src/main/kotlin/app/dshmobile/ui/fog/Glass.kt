package app.dshmobile.ui.fog

import android.graphics.BlurMaskFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.Sakura

internal val GlassBlurRadius = 10.dp
internal const val GlassSaturation = 1.7f

/** A CSS `linear-gradient(<angle>deg, ...)` over a box of [size]: the gradient line runs through the centre at [angleDeg] clockwise from "up". */
internal fun cssLinear(angleDeg: Float, size: Size, vararg stops: Pair<Float, Color>): Brush {
    val a = Math.toRadians(angleDeg.toDouble())
    val dx = Math.sin(a).toFloat()
    val dy = (-Math.cos(a)).toFloat()
    val half = (Math.abs(size.width * dx) + Math.abs(size.height * dy)) / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(
        colorStops = stops,
        start = Offset(c.x - dx * half, c.y - dy * half),
        end = Offset(c.x + dx * half, c.y + dy * half),
    )
}

/** A soft shadow or glow of [color], blurred by [blur] and moved by ([dx],[dy]), drawn only outside [shape] when [outsideOnly]. */
internal fun DrawScope.softShadow(shape: Shape, color: Color, blur: Dp, dx: Dp = 0.dp, dy: Dp = 0.dp, outsideOnly: Boolean = true) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply {
        when (outline) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
    val blurPx = blur.toPx()
    val paint = Paint().apply {
        this.color = color
        if (blurPx > 0f) asFrameworkPaint().maskFilter = BlurMaskFilter(blurPx / 2f, BlurMaskFilter.Blur.NORMAL)
    }
    val draw = {
        translate(dx.toPx(), dy.toPx()) { drawIntoCanvas { it.drawPath(path, paint) } }
    }
    if (outsideOnly) clipPath(path, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) { draw() } else draw()
}

/** A static glow behind a shape (always on at every effect level). */
fun Modifier.glow(color: Color, radius: Dp, shape: Shape): Modifier = drawBehind { softShadow(shape, color, radius * 2, outsideOnly = false) }

/**
 * Liquid glass, as in the mockup: optional blur of the background layer behind, a white gradient, a gradient rim, a lit top-left corner
 * and two soft shadows. Without blur (balanced / saver, or no [LocalBackdrop]) the white stops get 0.16 more alpha.
 */
fun Modifier.glass(shape: Shape): Modifier = this then GlassElement(shape)

private data class GlassElement(val shape: Shape) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(shape)
    override fun update(node: GlassNode) {
        if (node.shape != shape) {
            node.shape = shape
            node.invalidateDraw()
        }
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "glass"
        properties["shape"] = shape
    }
}

private class GlassNode(var shape: Shape) : Modifier.Node(), DrawModifierNode, SemanticsModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null

    private fun blurs(): Boolean = currentValueOf(LocalEffects).blur && currentValueOf(LocalBackdrop) != null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        set(FogSemantics.Blur, blurs())
    }

    override fun ContentDrawScope.draw() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        val backdrop = currentValueOf(LocalBackdrop)
        val blur = currentValueOf(LocalEffects).blur

        softShadow(shape, Sakura.RoseBrown.copy(alpha = .16f), 30.dp, dy = 12.dp)
        softShadow(shape, Sakura.RoseBrown.copy(alpha = .08f), 6.dp, dy = 2.dp)

        clipPath(path) {
            if (blur && backdrop != null) drawBlurredBackdrop(backdrop)
            val lift = if (blur) 0f else .16f
            drawRect(
                cssLinear(
                    140f, size,
                    0f to Color.White.copy(alpha = .62f + lift),
                    .6f to Color.White.copy(alpha = .16f + lift),
                    1f to Color.White.copy(alpha = .28f + lift),
                ),
            )
            // Lit corner: radial, centred on the top-left corner, 120% x 70% of the box.
            val rx = size.width * 1.2f
            val ry = size.height * .7f
            if (rx > 0f && ry > 0f) {
                withTransform({ scale(1f, ry / rx, pivot = Offset.Zero) }) {
                    drawRect(
                        Brush.radialGradient(0f to Color.White.copy(alpha = .55f), .45f to Color.Transparent, center = Offset.Zero, radius = rx),
                        size = Size(size.width, size.height * rx / ry),
                    )
                }
            }
        }
        drawOutline(
            outline,
            cssLinear(
                140f, size,
                0f to Color.White.copy(alpha = .95f),
                .5f to Sakura.Primary.copy(alpha = .75f),
                1f to Sakura.Aqua.copy(alpha = .90f),
            ),
            style = Stroke(1.2.dp.toPx()),
        )
        drawContent()
    }

    private fun DrawScope.drawBlurredBackdrop(backdrop: Backdrop) {
        val l = layer ?: return
        val origin = backdrop.originInWindow() ?: return
        val mine = runCatching { requireLayoutCoordinates().positionInWindow() }.getOrNull() ?: return
        val radius = GlassBlurRadius.toPx()
        l.renderEffect = glassRenderEffect(radius)
        l.record(size = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) {
            translate(origin.x - mine.x, origin.y - mine.y) { drawLayer(backdrop.layer) }
        }
        drawLayer(l)
    }
}

/** Gaussian blur, then saturation. Clamped edges keep the rim from fading to transparent. */
private fun glassRenderEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? = runCatching {
    val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    val sat = android.graphics.ColorMatrix().apply { setSaturation(GlassSaturation) }
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sat), blur).asComposeRenderEffect()
}.getOrNull()
