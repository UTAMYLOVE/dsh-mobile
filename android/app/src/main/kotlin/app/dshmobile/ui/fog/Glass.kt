package app.dshmobile.ui.fog

import android.graphics.BlurMaskFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.boundsInWindow
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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.theme.Sakura
import kotlin.math.ceil
import kotlin.math.floor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val GlassBlurRadius = 10.dp
internal const val GlassSaturation = 1.7f

/**
 * Counters the tests read to prove that draw caches are reused across frames. Off unless a test sets [enabled]: the calls sit in
 * production draw paths, where a map lookup per draw is not wanted.
 */
internal object FogDebug {
    @JvmField @Volatile var enabled = false
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun count(key: String) {
        if (!enabled) return
        counts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    }

    fun get(key: String): Int = counts[key]?.get() ?: 0

    fun reset() = counts.clear()

    /** The offset the glass last used to line the blurred backdrop up with the background (recorded only while [enabled]). */
    @Volatile var lastBackdropShift: Offset = Offset.Zero

    /** The size of the blur layer a glass surface last recorded (recorded only while [enabled]). */
    @Volatile var lastLayerSize: IntSize = IntSize.Zero
}

/**
 * The glass surfaces that hold a blur layer right now: one exists only while its surface blurs and is on screen, and never more
 * than [MAX] at once (each one re-blurs the background every frame it moves). A surface turned away at the cap draws the no-blur
 * fill and is told to try again as soon as a layer is given back. Main thread only (drawing), apart from reading [active].
 */
internal object BlurLayers {
    const val MAX = 4
    private val live = AtomicInteger()
    private val waiting = LinkedHashMap<Any, () -> Unit>()
    val active: Int get() = live.get()

    /** Takes a layer slot, or false at the cap (then [retry] runs when a slot frees up). */
    fun open(owner: Any, retry: () -> Unit): Boolean {
        if (live.get() >= MAX) {
            waiting[owner] = retry
            return false
        }
        waiting.remove(owner)
        live.incrementAndGet()
        return true
    }

    fun closed() {
        live.decrementAndGet()
        // Everyone turned away may try again: the first to draw gets the slot, the others are turned away anew.
        val retry = waiting.values.toList()
        waiting.clear()
        retry.forEach { it() }
    }

    fun forget(owner: Any) {
        waiting.remove(owner)
    }
}

/**
 * Where, in the window, the floating dock begins: a page's glass is hidden below it (the page fades out there), so its blur is
 * recorded only above. [FogShell][app.dshmobile.ui.shell.FogShell] provides it to the pages; null everywhere else.
 */
@Stable
class BlurFloor {
    var y by mutableFloatStateOf(Float.POSITIVE_INFINITY)
}

val LocalBlurFloor: ProvidableCompositionLocal<BlurFloor?> = staticCompositionLocalOf { null }

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
fun Modifier.glass(shape: Shape): Modifier = glass(shape, settled = true)

/**
 * [glass] for a surface that a graphicsLayer moves or scales without relaying it out (a panel rising in): that never reaches
 * onGloballyPositioned, so the blurred backdrop is drawn for wherever the surface was when it last drew. While [settled] is
 * false the blur is only approximately placed (the surface is also mostly transparent then); the flip to true redraws it at its
 * final position, which is what keeps it locked to the background afterwards.
 */
fun Modifier.glass(shape: Shape, settled: Boolean): Modifier =
    this then if (FogSemantics.exposeBlur) GlassSemanticsElement(shape, settled) else GlassElement(shape, settled)

private data class GlassElement(val shape: Shape, val settled: Boolean) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(shape, settled)
    override fun update(node: GlassNode) = node.update(shape, settled)
    override fun InspectorInfo.inspectableProperties() {
        name = "glass"
        properties["shape"] = shape
        properties["settled"] = settled
    }
}

/** [GlassElement] that also tells tests whether it blurs ([FogSemantics.Blur]); only while [FogSemantics.exposeBlur]. */
private data class GlassSemanticsElement(val shape: Shape, val settled: Boolean) : ModifierNodeElement<GlassSemanticsNode>() {
    override fun create() = GlassSemanticsNode(shape, settled)
    override fun update(node: GlassSemanticsNode) = node.update(shape, settled)
    override fun InspectorInfo.inspectableProperties() {
        name = "glass"
        properties["shape"] = shape
        properties["settled"] = settled
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
}

private open class GlassNode(var shape: Shape, var settled: Boolean) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode {
    private var layer: GraphicsLayer? = null
    private var cache: GlassCache? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null
    private var effectRadius = -1f
    private var recordedShift: Offset? = null
    private var recordedRect: IntRect? = null
    private var lastWindowPos: Offset? = null
    /** What of the surface its parents let the window show (a panel scrolled away shows nothing), in window pixels. */
    private var windowBounds: Rect? = null
    /** Turned away by [BlurLayers.MAX]: drawn without blur until a layer is given back. */
    private var denied = false
    /** What the last draw did (null before the first): a change is re-reported. */
    private var drewBlur: Boolean? = null

    fun update(shape: Shape, settled: Boolean) {
        if (this.shape != shape || this.settled != settled) {
            this.shape = shape
            this.settled = settled
            relock()
        }
    }

    /** The part of the surface the window shows above the floor (dock), in the surface's own pixels; null when nothing shows. */
    private fun visibleRect(size: Size, floor: Float): Rect? {
        val wb = windowBounds ?: return null
        val pos = lastWindowPos ?: return null
        val top = (wb.top - pos.y).coerceAtLeast(0f)
        val bottom = (minOf(wb.bottom, floor) - pos.y).coerceAtMost(size.height)
        val left = (wb.left - pos.x).coerceAtLeast(0f)
        val right = (wb.right - pos.x).coerceAtMost(size.width)
        return if (bottom > top && right > left) Rect(left, top, right, bottom) else null
    }

    /** Whether the surface wants to blur (the effects allow it, there is a backdrop, some of it shows); the cap is checked when drawing. */
    private fun wantsBlur(size: Size?): Boolean {
        // Every local is always read (before the visibility check), so observeReads keeps watching them while the surface is off screen.
        val effectsBlur = currentValueOf(LocalEffects).blur
        val hasBackdrop = currentValueOf(LocalBackdrop) != null
        val floor = currentValueOf(LocalBlurFloor)
        val shows = windowBounds != null && (size == null || visibleRect(size, floor?.y ?: Float.POSITIVE_INFINITY) != null)
        return effectsBlur && hasBackdrop && shows
    }

    protected fun blurs(): Boolean = wantsBlur(cache?.size) && !denied

    /** The blur state changed: [GlassSemanticsNode] re-reports it. */
    protected open fun blurChanged() = Unit

    /** Draws the blurred backdrop afresh from the surface's current position. */
    fun relock() {
        recordedShift = null
        invalidateDraw()
    }

    private val retry: () -> Unit = { if (isAttached) invalidateDraw() }

    private fun releaseLayer() {
        layer?.let {
            requireGraphicsContext().releaseGraphicsLayer(it)
            layer = null
            BlurLayers.closed()
        }
        effect = null
        effectRadius = -1f
        recordedShift = null
        recordedRect = null
    }

    override fun onAttach() {
        observeReads { wantsBlur(null) }
    }

    override fun onDetach() {
        releaseLayer()
        BlurLayers.forget(this)
        denied = false
        drewBlur = null
        cache = null
    }

    override fun onObservedReadsChanged() {
        blurChanged()
        invalidateDraw()
        observeReads { wantsBlur(null) }
    }

    /** The blurred backdrop has to stay locked to the background while the panel moves, so a new window position redraws it. */
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bounds = coordinates.takeIf { it.isAttached }?.boundsInWindow()?.takeIf { it.width > 0f && it.height > 0f }
        val pos = coordinates.positionInWindow()
        if (bounds != windowBounds || pos != lastWindowPos) {
            val wasShown = windowBounds != null
            windowBounds = bounds
            lastWindowPos = pos
            if (wasShown != (bounds != null)) blurChanged()
            if (wantsBlur(cache?.size) || layer != null) {
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
        val floor = currentValueOf(LocalBlurFloor)?.y ?: Float.POSITIVE_INFINITY // a snapshot read: a moving dock redraws
        val visible = visibleRect(size, floor)
        val wants = currentValueOf(LocalEffects).blur && backdrop != null && visible != null
        val blur = when {
            !wants -> {
                // No blur (switched off, or scrolled off screen): give the layer back, so only what is seen holds one.
                if (layer != null) releaseLayer()
                BlurLayers.forget(this@GlassNode)
                denied = false
                false
            }
            layer != null -> true
            BlurLayers.open(this@GlassNode, retry) -> { denied = false; true }
            else -> { denied = true; false }
        }
        if (blur != drewBlur) {
            drewBlur = blur
            blurChanged() // turned away or let in at the cap, or the dock moved over it
        }
        val c = cacheFor(this, lifted = !blur)

        drawShadow(c.path, c.wideShadow, c.wideDy)
        drawShadow(c.path, c.tightShadow, c.tightDy)

        clipPath(c.path) {
            if (blur && backdrop != null && visible != null) drawBlurredBackdrop(backdrop, visible)
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

    /**
     * Records only the part of the background behind what shows of the surface ([visible], snapped outwards to [LayerSnapPx] so
     * a slow scroll does not resize the layer every frame), never more than the window: a tall panel would otherwise ask for an
     * offscreen layer bigger than the GPU allows.
     */
    private fun DrawScope.drawBlurredBackdrop(backdrop: Backdrop, visible: Rect) {
        val origin = backdrop.originInWindow() ?: return
        val mine = requireLayoutCoordinates().takeIf { it.isAttached }?.positionInWindow() ?: return
        val shift = origin - mine
        if (FogDebug.enabled) FogDebug.lastBackdropShift = shift
        val l = layer ?: requireGraphicsContext().createGraphicsLayer().also { layer = it }
        val radius = GlassBlurRadius.toPx()
        if (effectRadius != radius) {
            effect = glassRenderEffect(radius)
            effectRadius = radius
            l.renderEffect = effect
        }
        val rect = snapOut(visible, size)
        if (rect.width <= 0 || rect.height <= 0) return
        // The recorded list refers to the live backdrop layer, so it only has to be re-recorded when the offset or the shown part changes.
        if (recordedShift != shift || recordedRect != rect) {
            val layerSize = IntSize(rect.width, rect.height)
            l.record(size = layerSize) { translate(shift.x - rect.left, shift.y - rect.top) { drawLayer(backdrop.layer) } }
            recordedShift = shift
            recordedRect = rect
            if (FogDebug.enabled) FogDebug.lastLayerSize = layerSize
        }
        translate(rect.left.toFloat(), rect.top.toFloat()) { drawLayer(l) }
    }
}

/** How far the recorded part of a blur is rounded out, so scrolling resizes the layer once per this many pixels, not every frame. */
internal const val LayerSnapPx = 64

private fun snapOut(r: Rect, size: Size): IntRect {
    val left = (floor(r.left / LayerSnapPx) * LayerSnapPx).toInt().coerceAtLeast(0)
    val top = (floor(r.top / LayerSnapPx) * LayerSnapPx).toInt().coerceAtLeast(0)
    val right = (ceil(r.right / LayerSnapPx) * LayerSnapPx).toInt().coerceAtMost(ceil(size.width).toInt())
    val bottom = (ceil(r.bottom / LayerSnapPx) * LayerSnapPx).toInt().coerceAtMost(ceil(size.height).toInt())
    return IntRect(left, top, right, bottom)
}

/** [GlassNode] that reports [FogSemantics.Blur]: a semantics node per glass surface, so only while tests ask for it. */
private class GlassSemanticsNode(shape: Shape, settled: Boolean) : GlassNode(shape, settled), SemanticsModifierNode {
    override fun blurChanged() = invalidateSemantics()

    override fun SemanticsPropertyReceiver.applySemantics() {
        set(FogSemantics.Blur, blurs())
    }
}

/** Gaussian blur, then saturation. Clamped edges keep the rim from fading to transparent. */
private fun glassRenderEffect(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? = runCatching {
    FogDebug.count("glass.effect")
    val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    val sat = ColorMatrix().apply { setSaturation(GlassSaturation) }
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sat), blur).asComposeRenderEffect()
}.getOrNull()
