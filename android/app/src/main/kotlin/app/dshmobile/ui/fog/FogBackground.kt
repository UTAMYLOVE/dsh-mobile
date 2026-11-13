package app.dshmobile.ui.fog

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

object FogSemantics {
    /** True while a glass surface actually blurs what is behind it. Reported only while [exposeBlur]. */
    val Blur = SemanticsPropertyKey<Boolean>("FogBlur")
    /** True while the background (glows, grid) is being animated. */
    val Animating = SemanticsPropertyKey<Boolean>("FogAnimating")

    /**
     * Whether glass surfaces report [Blur]. Reporting it makes every glass surface a semantics node of its own, which nothing but
     * tests reads, so it is off in the app; Robolectric tests start with it on (and may set it), so a glass surface composed
     * after a change follows the new value.
     */
    @JvmField @Volatile var exposeBlur: Boolean = "robolectric" == android.os.Build.FINGERPRINT
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
 * Debug switches for bisecting renderer trouble on test devices (the CI emulator): set before the shell is shown. Never set in
 * the app itself.
 */
object FogSwitches {
    /** Only the plain pink ground: no glows, no grids, nothing animated behind the pages. */
    @JvmField @Volatile var plainBackground: Boolean = false
}

/**
 * Seconds of animation time, advancing only while [running]; slowed to [FogMotion.IDLE_SPEED] while [idle], when it also ticks
 * only every [FogMotion.IDLE_TICK_MS] instead of every frame (an idle screen still draws its slow drift, at a fraction of the
 * redraws). [idle] is read here, in the clock, so going idle never recomposes anything. Read the returned state in a draw lambda
 * so a frame redraws without recomposing.
 */
@Composable
internal fun rememberFogClock(running: Boolean, idle: State<Boolean>): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, idle) {
        if (!running) return@LaunchedEffect
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            if (idle.value) delay(FogMotion.IDLE_TICK_MS)
            val now = withInfiniteAnimationFrameNanos { it }
            time.floatValue += (now - last) / 1_000_000_000f * (if (idle.value) FogMotion.IDLE_SPEED else 1f)
            last = now
            FogDebug.count("clock.tick")
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

/**
 * The pink ground, three drifting glows and the two perspective grids, recorded into [backdrop]'s layer for the glass to blur.
 * The box has a layer of its own, and the glows and the grids one each, so a clock tick re-records only what moves, never the
 * shell above. With [FogSwitches.plainBackground] only the ground is drawn.
 */
@Composable
fun FogBackground(backdrop: Backdrop, modifier: Modifier = Modifier) {
    val effects = LocalEffects.current
    val plain = FogSwitches.plainBackground
    val animating = effects.ambient && LocalPageActive.current && !plain
    val clock = rememberFogClock(animating, LocalIdle.current)
    Box(
        modifier
            .semantics { set(FogSemantics.Animating, animating) }
            .onGloballyPositioned { backdrop.coordinates = it }
            .graphicsLayer { }
            .drawWithContent {
                backdrop.layer.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop.layer)
            },
    ) {
        Box(Modifier.fillMaxSize().background(Sakura.Background))
        if (!plain) {
            Box(Modifier.fillMaxSize().fogGlows(clock))
            // 0.8.0 (the owner: 「不要科技感了，來一個舒服的感覺」): the perspective grids are gone; the soft glows stay.
        }
    }
}

/** One drifting glow: a radial gradient built once at the glow's own centre, moved and scaled at draw time by the clock. */
private class GlowSpec(
    val brush: Brush,
    val radius: Float,
    val left: Float,
    val top: Float,
    val driftX: Float,
    val driftY: Float,
    val scaleTo: Float,
    val oneWay: Float,
)

private fun buildGlow(color: Color, sizeDp: Float, left: Float, top: Float, drift: Offset, scaleTo: Float, oneWay: Float, density: Float): GlowSpec {
    val r = sizeDp * density / 2f
    val c = Offset(r, r)
    return GlowSpec(
        Brush.radialGradient(
            0f to color.copy(alpha = .75f), .55f to color.copy(alpha = .38f), 1f to color.copy(alpha = 0f), center = c, radius = r,
        ),
        r, left, top, drift.x * density, drift.y * density, scaleTo, oneWay,
    )
}

/** The three glows live in their own layer, so a clock tick redraws only this layer, not the chat list or dock above it. */
private fun Modifier.fogGlows(clock: State<Float>): Modifier = this
    .graphicsLayer { }
    .drawWithCache {
        FogDebug.count("background.glows")
        val d = density
        val w = size.width
        val h = size.height
        val glows = arrayOf(
            buildGlow(Sakura.Primary, 300f, w + 90f * d - 300f * d, -60f * d, Offset(-70f, 80f), 1.12f, 18f, d),
            buildGlow(Sakura.Aqua, 260f, -110f * d, h - 120f * d - 260f * d, Offset(90f, -60f), .9f, 22f, d),
            buildGlow(Sakura.MilkTea, 240f, w + 70f * d - 240f * d, h * .42f, Offset(-80f, -40f), 1.15f, 26f, d),
        )
        onDrawBehind {
            FogDebug.count("background.glows.draw")
            val t = clock.value
            for (g in glows) {
                val p = alternate(t, g.oneWay)
                val s = 1f + (g.scaleTo - 1f) * p
                translate(g.left + g.driftX * p, g.top + g.driftY * p) {
                    scale(s, s, pivot = Offset(g.radius, g.radius)) { drawCircle(g.brush, g.radius, Offset(g.radius, g.radius)) }
                }
            }
        }
    }

/**
 * The mockup's grid slab (`.grid` + `.grid i`), floor or ceiling, on a [screenW] x [screenH] screen: a slab twice the screen's
 * width (left -50%) and 42% of its height, `rotateX(68deg)` about its bottom edge under `perspective(340px)` seen from the
 * slab's centre, the ceiling being the floor mirrored (`scaleY(-1)`). Cells of 44 dp, 1 dp lines of white α.95 in a layer of
 * α.55, masked from 10% (opaque, nearest the screen edge) to 85% (gone, far away).
 *
 * A plane under a perspective camera is a 2D projective map, so [matrix] (slab pixels to screen pixels) is that map exactly
 * and [project] the same map for one point. Unlike a graphicsLayer's rotationX (whose camera sits at the transform origin and
 * whose cameraDistance is in 72-dpi "inches"), this puts the camera where the mockup's perspective-origin is.
 */
internal class GridSlab(screenW: Float, screenH: Float, density: Float, val ceiling: Boolean) {
    val width = 2f * screenW
    val height = .42f * screenH
    val cell = 44f * density
    val line = 1f * density
    private val left = -.5f * screenW
    private val top = if (ceiling) 0f else screenH - height

    // Homogeneous rows (u, v, 1) -> (nx, ny, nw); see the class doc for the geometry.
    private val c = cos(Math.toRadians(68.0)).toFloat()
    private val sn = sin(Math.toRadians(68.0)).toFloat()
    private val q = sn / (340f * density)
    private val ax = 1f; private val bx = -(width / 2f) * q; private val cx = (width / 2f) * height * q
    private val by = c - (height / 2f) * q; private val cy = (height / 2f) * (1f + height * q) + height / 2f - height * c
    private val bw = -q; private val cw = 1f + height * q

    /** Slab point ([u] across, [v] down from the far edge's side as drawn flat) to screen pixels. */
    fun project(u: Float, v: Float): Offset {
        val nw = bw * v + cw
        val x = (ax * u + bx * v + cx) / nw
        val y = (by * v + cy) / nw
        return Offset(left + x, top + if (ceiling) height - y else y)
    }

    /** How much a length at depth [v] is shortened on screen (1 at the near edge). */
    fun shrink(v: Float): Float = 1f / (bw * v + cw)

    /** [project] as a matrix, to draw the slab's lines under (3x3 projective map in a 4x4). */
    val matrix: Matrix = Matrix().apply {
        // screen x = left + nx/nw; screen y = top + ny/nw (floor) or top + height - ny/nw (ceiling)
        val rowX = floatArrayOf(ax + left * 0f, bx + left * bw, cx + left * cw)
        val rowY = if (ceiling) floatArrayOf(0f, (top + height) * bw - by, (top + height) * cw - cy)
        else floatArrayOf(0f, by + top * bw, cy + top * cw)
        values[Matrix.ScaleX] = rowX[0]; values[Matrix.SkewX] = rowX[1]; values[Matrix.TranslateX] = rowX[2]
        values[Matrix.SkewY] = rowY[0]; values[Matrix.ScaleY] = rowY[1]; values[Matrix.TranslateY] = rowY[2]
        values[Matrix.Perspective0] = 0f; values[Matrix.Perspective1] = bw; values[Matrix.Perspective2] = cw
    }

    /** The mask: 1 up to 10% from the near (bottom) edge of the slab, 0 from 85%, linear between. */
    fun fade(v: Float): Float = ((v / height - .15f) / .75f).coerceIn(0f, 1f)
}

/** White α.95 in a layer of α.55, folded into the colour (no offscreen layer for the opacity). */
private val GridLine = Color.White.copy(alpha = .95f * .55f)

/**
 * Both grids in one layer of their own. [perspective] (FULL, BALANCED): the slab's lines drawn under the projective [GridSlab.matrix],
 * the cells flowing one every 6 s while the clock runs. Otherwise (SAVER): the same lines' ends projected once and drawn flat, no
 * 3D transform. Neither needs an offscreen layer: the mask and the opacity are folded into the line colours.
 */
private fun Modifier.fogGrids(clock: State<Float>, perspective: Boolean): Modifier = this
    .graphicsLayer { }
    .drawWithCache {
        FogDebug.count("background.grid")
        val slabs = listOf(GridSlab(size.width, size.height, density, ceiling = true), GridSlab(size.width, size.height, density, ceiling = false))
        if (perspective) {
            val vertical = slabs.map { s -> Brush.verticalGradient(0f to Color.Transparent, .15f to Color.Transparent, .9f to GridLine, 1f to GridLine, startY = 0f, endY = s.height) }
            onDrawBehind {
                FogDebug.count("background.grid.depth")
                val t = clock.value
                slabs.forEachIndexed { i, s -> withTransform({ transform(s.matrix) }) { drawSlab(s, vertical[i], t) } }
            }
        } else {
            val flat = slabs.map { FlatGrid(it) }
            onDrawBehind {
                FogDebug.count("background.grid.flat")
                flat.forEach { it.draw(this) }
            }
        }
    }

/** One slab's lines in slab pixels (drawn under its matrix): verticals with the fade as a brush, horizontals flowing with the clock. */
private fun DrawScope.drawSlab(s: GridSlab, vertical: Brush, t: Float) {
    var u = 0f
    while (u <= s.width) {
        drawRect(vertical, Offset(u, 0f), Size(s.line, s.height))
        u += s.cell
    }
    val flow = ((t / 6f) % 1f) * s.cell
    var v = flow - s.cell
    while (v <= s.height) {
        val a = s.fade(v)
        if (a > 0f) drawRect(GridLine.copy(alpha = GridLine.alpha * a), Offset(0f, v), Size(s.width, s.line))
        v += s.cell
    }
}

/** A slab's lines with their ends projected once: flat lines on screen, the fade as one vertical brush and per-line alphas. */
private class FlatGrid(s: GridSlab) {
    private class Seg(val from: Offset, val to: Offset, val width: Float, val color: Color)
    private val segs = ArrayList<Seg>()
    private val verticalBrush: Brush
    private val verticals = ArrayList<Pair<Offset, Offset>>()
    private val line = s.line

    init {
        var u = 0f
        while (u <= s.width) {
            verticals += s.project(u, 0f) to s.project(u, s.height)
            u += s.cell
        }
        var v = 0f
        while (v <= s.height) {
            val a = s.fade(v)
            if (a > 0f) segs += Seg(s.project(0f, v), s.project(s.width, v), (s.line * s.shrink(v)).coerceAtLeast(.5f), GridLine.copy(alpha = GridLine.alpha * a))
            v += s.cell
        }
        val near = s.project(0f, s.height * .9f).y
        val far = s.project(0f, s.height * .15f).y
        verticalBrush = Brush.verticalGradient(0f to GridLine, 1f to Color.Transparent, startY = near, endY = far)
    }

    fun draw(scope: DrawScope) = with(scope) {
        for ((a, b) in verticals) drawLine(verticalBrush, a, b, line)
        for (g in segs) drawLine(g.color, g.from, g.to, g.width)
    }
}
