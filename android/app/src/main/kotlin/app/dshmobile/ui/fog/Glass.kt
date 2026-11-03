package app.dshmobile.ui.fog

import android.graphics.BlurMaskFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.Sakura
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val GlassBlurRadius = 10.dp
internal const val GlassSaturation = 1.7f

/** Counters the tests read to prove that draw caches are reused across frames. Increments are cheap and allocation free. */
internal object FogDebug {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun count(key: String) {
        counts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    }

    fun get(key: String): Int = counts[key]?.get() ?: 0

    fun reset() = counts.clear()

    /** The offset the glass last used to line the blurred backdrop up with the background. */
    @Volatile var lastBackdropShift: Offset = Offset.Zero
}

/** A CSS blur of [blurPx] has a Gaussian sigma of blurPx / 2; BlurMaskFilter wants a radius, which Skia turns into 0.57735 * radius + 0.5. */
internal fun cssBlurFilter(blurPx: Float): BlurMaskFilter {
    val sigma = blurPx / 2f
    return BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
}

internal fun fogShadowPaint(color: Color, blurPx: Float): Paint = Paint().apply {
    this.color = color
    if (blurPx > 0f) asFrameworkPaint().maskFilter = cssBlurFilter(blurPx)
}

internal fun Outline.toPath(): Path = Path().also {
    when (this) {
        is Outline.Rectangle -> it.addRect(rect)
        is Outline.Rounded -> it.addRoundRect(roundRect)
        is Outline.Generic -> it.addPath(path)
    }
}

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

/** A static glow behind a shape, like CSS `box-shadow: dx dy blur color` (always on at every effect level). The paint and path are built once per size. */
fun Modifier.glow(color: Color, blur: Dp, shape: Shape, dy: Dp = 0.dp, dx: Dp = 0.dp): Modifier = drawWithCache {
    FogDebug.count("glow.cache")
    val path = shape.createOutline(size, layoutDirection, this).toPath()
    val paint = fogShadowPaint(color, blur.toPx())
    val ox = dx.toPx()
    val oy = dy.toPx()
    onDrawBehind {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(ox, oy)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }
}

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

/** Everything a glass surface draws that depends only on its size, density and whether it is lifted (no blur): rebuilt only when one changes. */
private class GlassCache(
    val size: Size,
    val density: Float,
    val direction: LayoutDirection,
    val lifted: Boolean,
    val shape: Shape,
    density2: Density,
) {
    val outline: Outline = shape.createOutline(size, direction, density2)
    val path: Path = outline.toPath()
    val lift = if (lifted) .16f else 0f
    val fill: Brush = cssLinear(
        140f, size,
        0f to Color.White.copy(alpha = .62f + lift),
        .6f to Color.White.copy(alpha = .16f + lift),
        1f to Color.White.copy(alpha = .28f + lift),
    )
    val rim: Brush = cssLinear(
        140f, size,
        0f to Color.White.copy(alpha = .95f),
        .5f to Sakura.Primary.copy(alpha = .75f),
        1f to Sakura.Aqua.copy(alpha = .90f),
    )
    val rimStroke = Stroke(1.2f * density)
    // Lit corner: radial, centred on the top-left corner, 120% x 70% of the box (a circle drawn through a vertical squash).
    val highlightRx = size.width * 1.2f
    val highlightRy = size.height * .7f
    val highlight: Brush? = if (highlightRx > 0f && highlightRy > 0f) {
        Brush.radialGradient(
            0f to Color.White.copy(alpha = .55f),
            .45f to Color.White.copy(alpha = 0f),
            center = Offset.Zero,
            radius = highlightRx,
        )
    } else null
    val highlightSquash = if (highlightRx > 0f) highlightRy / highlightRx else 1f
    val highlightSize = Size(size.width, if (highlightRy > 0f) size.height * highlightRx / highlightRy else 0f)
    val wideShadow = fogShadowPaint(Sakura.RoseBrown.copy(alpha = .16f), 30f * density)
    val tightShadow = fogShadowPaint(Sakura.RoseBrown.copy(alpha = .08f), 6f * density)
    val wideDy = 12f * density
    val tightDy = 2f * density
    val intSize = IntSize(size.width.toInt(), size.height.toInt())
}

private class GlassNode(var shape: Shape) :
    Modifier.Node(), DrawModifierNode, SemanticsModifierNode, CompositionLocalConsumerModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode {
    private var layer: GraphicsLayer? = null
    private var cache: GlassCache? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null
    private var effectRadius = -1f
    private var recordedShift: Offset? = null
    private var recordedSize: IntSize? = null
    private var lastWindowPos: Offset? = null

    private fun blurs(): Boolean = currentValueOf(LocalEffects).blur && currentValueOf(LocalBackdrop) != null

    override fun onAttach() {
        observeReads { blurs() }
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        cache = null
        recordedShift = null
        recordedSize = null
    }

    override fun onObservedReadsChanged() {
        invalidateSemantics()
        invalidateDraw()
        observeReads { blurs() }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        set(FogSemantics.Blur, blurs())
    }

    /** The blurred backdrop has to stay locked to the background while the panel moves, so a new window position redraws it. */
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val pos = coordinates.positionInWindow()
        if (pos != lastWindowPos) {
            lastWindowPos = pos
            if (blurs()) {
                FogDebug.count("glass.moved")
                invalidateDraw()
            }
        }
    }

    private fun cacheFor(scope: ContentDrawScope, lifted: Boolean): GlassCache {
        val c = cache
        if (c != null && c.size == scope.size && c.density == scope.density && c.direction == scope.layoutDirection && c.lifted == lifted && c.shape == shape) return c
        FogDebug.count("glass.cache")
        return GlassCache(scope.size, scope.density, scope.layoutDirection, lifted, shape, scope).also { cache = it }
    }

    override fun ContentDrawScope.draw() {
        FogDebug.count("glass.draw")
        val backdrop = currentValueOf(LocalBackdrop)
        val blur = currentValueOf(LocalEffects).blur && backdrop != null
        val c = cacheFor(this, lifted = !blur)

        drawShadow(c.path, c.wideShadow, c.wideDy)
        drawShadow(c.path, c.tightShadow, c.tightDy)

        clipPath(c.path) {
            backdrop?.takeIf { blur }?.let { drawBlurredBackdrop(it, c) }
            drawRect(c.fill)
            c.highlight?.let { h ->
                withTransform({ scale(1f, c.highlightSquash, pivot = Offset.Zero) }) { drawRect(h, size = c.highlightSize) }
            }
        }
        drawOutline(c.outline, c.rim, style = c.rimStroke)
        drawContent()
    }

    private fun ContentDrawScope.drawShadow(path: Path, paint: Paint, dy: Float) {
        clipPath(path, clipOp = ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(0f, dy)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }
    }

    private fun DrawScope.drawBlurredBackdrop(backdrop: Backdrop, c: GlassCache) {
        val origin = backdrop.originInWindow() ?: return
        val mine = requireLayoutCoordinates().takeIf { it.isAttached }?.positionInWindow() ?: return
        val shift = origin - mine
        FogDebug.lastBackdropShift = shift
        // Created only when blur is first needed.
        val l = layer ?: requireGraphicsContext().createGraphicsLayer().also { layer = it }
        val radius = GlassBlurRadius.toPx()
        if (effectRadius != radius) {
            effect = glassRenderEffect(radius)
            effectRadius = radius
            l.renderEffect = effect
        }
        // The recorded list refers to the live backdrop layer, so it only has to be re-recorded when the offset or size changes.
        if (recordedShift != shift || recordedSize != c.intSize) {
            l.record(size = c.intSize) { translate(shift.x, shift.y) { drawLayer(backdrop.layer) } }
            recordedShift = shift
            recordedSize = c.intSize
        }
        drawLayer(l)
    }
}

/** Gaussian blur, then saturation. Clamped edges keep the rim from fading to transparent. */
private fun glassRenderEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? = runCatching {
    FogDebug.count("glass.effect")
    val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    val sat = ColorMatrix().apply { setSaturation(GlassSaturation) }
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sat), blur).asComposeRenderEffect()
}.getOrNull()
