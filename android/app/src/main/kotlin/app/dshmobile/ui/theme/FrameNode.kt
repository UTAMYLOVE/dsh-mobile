package app.dshmobile.ui.theme

import androidx.compose.runtime.State
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.FogDebug
import app.dshmobile.ui.fog.Kawaii
import app.dshmobile.ui.fog.fogShadowPaint
import app.dshmobile.ui.fog.toPath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// FrameNode: the one generic painter of a theme Frame (§2.4.1's obligations). Everything that depends only on the frame, the
// size, the density and the layout direction is built once in a per-size cache (FrameArt, counted as "frame.cache"); a redraw
// only replays it. It never reads a clock: runners are their own node (RunnerNode), reading the ThemeClock in draw.

/** Which piece of a long message a frame is painting (MessageViews' pieces); WHOLE is an ordinary surface. */
internal enum class Piece { WHOLE, FIRST, MIDDLE, LAST }

/**
 * [frame] painted behind the content: shades (outside the shape), then the fills clipped to it, rims, ornaments, content. [lift]:
 * the surface carries text, so while [LocalPictureBehind] its light fills are floored at α .74 (D9). [piece]: segment mode.
 */
internal data class FrameElement(val frame: Frame, val lift: Boolean, val piece: Piece = Piece.WHOLE) : ModifierNodeElement<FrameNode>() {
    override fun create() = FrameNode(frame, lift, piece)
    override fun update(node: FrameNode) = node.update(frame, lift, piece)
    override fun InspectorInfo.inspectableProperties() {
        name = "frame"
        properties["frame"] = frame
        properties["lift"] = lift
        properties["piece"] = piece
    }
}

internal class FrameNode(private var frame: Frame, private var lift: Boolean, private var piece: Piece) :
    DelegatingNode(), CompositionLocalConsumerModifierNode {

    private val cache = delegate(
        CacheDrawModifierNode {
            val art = FrameArt(this, frame, lift, piece)
            onDrawWithContent {
                // Read in draw (eng E20): a flip redraws this frame; it recomposes nothing and rebuilds no cache.
                val behind = art.liftable && this@FrameNode.currentValueOf(LocalPictureBehind)
                art.draw(this, behind)
                drawContent()
            }
        },
    )

    fun update(frame: Frame, lift: Boolean, piece: Piece) {
        if (frame == this.frame && lift == this.lift && piece == this.piece) return
        this.frame = frame
        this.lift = lift
        this.piece = piece
        cache.invalidateDrawCache()
    }
}

private typealias Op = DrawScope.() -> Unit

/** Far enough to never clip sideways: a segment's clip limits only the top and bottom. */
private const val FAR = 1e5f

/** D9: a light fill stop of a text-bearing surface is at least this opaque while a dark picture is behind it. */
internal const val PictureFloor = .74f

/** The colour [c] floored for a dark picture behind (D9): light stops (luminance > .6) reach α [PictureFloor]. */
internal fun liftedForPicture(c: Color): Color = if (c.alpha < PictureFloor && c.luminance() > .6f) c.copy(alpha = PictureFloor) else c

/** Everything [f] draws at one size, built once (FogDebug "frame.cache"). */
private class FrameArt(scope: CacheDrawScope, private val f: Frame, lift: Boolean, private val piece: Piece) {
    private val shadeOps: Array<Op>
    private val fillOps: Array<Op>
    private val liftedFillOps: Array<Op>
    private val rimOps: Array<Op>
    private val ornamentOps: Array<Op>
    private val path: Path
    private val clipTop: Float
    private val clipBottom: Float
    private val segment = piece != Piece.WHOLE

    /** Whether a dark picture behind would change anything (so whether draw needs to read LocalPictureBehind at all). */
    val liftable: Boolean

    init {
        FogDebug.count("frame.cache")
        val w = scope.size.width
        val h = scope.size.height
        val dp = scope.density
        val rtl = scope.layoutDirection == LayoutDirection.Rtl
        // Segment mode (slots §3.4): the outline is drawn as if the bubble went on 2·r + 4 px past each cut edge, clipped to the piece.
        val over = if (segment) 2f * Ribbon.radii(f.corners, w, 1e6f, dp).let { max(max(it.ts, it.te), max(it.be, it.bs)) } + 4f else 0f
        val top = if (piece == Piece.MIDDLE || piece == Piece.LAST) -over else 0f
        val bottom = if (piece == Piece.MIDDLE || piece == Piece.FIRST) h + over else h
        clipTop = if (top < 0f) 0f else -FAR
        clipBottom = if (bottom > h) h else FAR
        val box = Rect(0f, top, w, bottom)
        val geo = Geometry(scope, f, box, dp, rtl)
        path = geo.outline(0f, 0f, 0f)

        shadeOps = f.shades.mapNotNull { geo.shade(it, path) }.toTypedArray()
        fillOps = f.fills.mapNotNull { geo.fill(it, piece, lifted = false) }.toTypedArray()
        liftable = lift && f.fills.any { fill -> stopsOf(fill).any { liftedForPicture(it) != it } }
        liftedFillOps = if (liftable) f.fills.mapNotNull { geo.fill(it, piece, lifted = true) }.toTypedArray() else fillOps
        rimOps = f.rims.mapNotNull { geo.rim(it, path) }.toTypedArray()
        ornamentOps = f.ornaments.mapNotNull { geo.ornament(it, path, piece) }.toTypedArray()
    }

    fun draw(d: DrawScope, behind: Boolean) {
        if (segment) d.clipRect(-FAR, clipTop, FAR, clipBottom) { body(this, behind) } else body(d, behind)
    }

    private fun body(d: DrawScope, behind: Boolean) = with(d) {
        for (op in shadeOps) op()
        val fills = if (behind) liftedFillOps else fillOps
        if (fills.isNotEmpty()) clipPath(path) { for (op in fills) op() }
        for (op in rimOps) op()
        for (op in ornamentOps) op()
    }
}

/** The colours of a fill that the picture floor may lift (D9: Solid, Linear and Radial stops). */
private fun stopsOf(f: Fill): List<Color> = when (f) {
    is Fill.Solid -> listOf(f.color)
    is Fill.Linear -> f.stops.map { it.second }
    is Fill.Radial -> f.stops.map { it.second }
    is Fill.RadialDp -> f.stops.map { it.second }
    is Fill.Lines, is Fill.Halftone -> emptyList()
}

/** Builds a frame's draw operations inside [box] (the layout box, or a segment's extended box) at one size. */
private class Geometry(
    private val scope: CacheDrawScope,
    private val f: Frame,
    private val box: Rect,
    private val dp: Float,
    private val rtl: Boolean,
) {
    private val w = box.width
    private val h = box.height
    private val radii = Ribbon.radii(f.corners, w, h, dp)
    private val dir = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun Dp.px(): Float = value * dp

    /** The frame's outline for the box grown by [grow] on every side (negative: shrunk) and shifted by ([dx], [dy]). */
    fun outline(grow: Float, dx: Float, dy: Float): Path {
        val gw = max(0f, w + 2f * grow)
        val gh = max(0f, h + 2f * grow)
        val p = f.shape.createOutline(Size(gw, gh), dir, scope).toPath()
        p.translate(Offset(box.left - grow + dx, box.top - grow + dy))
        return p
    }

    // ───── shades: before the fills, clipped out of the shape ─────

    fun shade(d: Drop, shape: Path): Op? {
        val (paint, grow, dx, dy) = when (d) {
            is Drop.Soft -> ShadeSpec(fogShadowPaint(d.color, d.blur.px()), d.spread.px(), d.dx.px(), d.dy.px())
            is Drop.Hard -> ShadeSpec(Paint().also { it.color = d.color }, d.grow.px(), d.dx.px(), d.dy.px())
        }
        if (w + 2f * grow <= 0f || h + 2f * grow <= 0f) return null
        val shadow = if (grow == 0f) shape else outline(grow, 0f, 0f)
        return {
            clipPath(shape, ClipOp.Difference) {
                drawIntoCanvas { c ->
                    c.save()
                    c.translate(dx, dy)
                    c.drawPath(shadow, paint)
                    c.restore()
                }
            }
        }
    }

    private data class ShadeSpec(val paint: Paint, val grow: Float, val dx: Float, val dy: Float)

    // ───── fills: clipped to the shape, in order ─────

    fun fill(fill: Fill, piece: Piece, lifted: Boolean): Op? {
        // Past the box on every side: a Flick's tip lies outside it, and the clip keeps the paint inside the shape.
        val cover = Rect(box.left - 16f * dp, box.top - 16f * dp, box.right + 16f * dp, box.bottom + 16f * dp)
        fun c(color: Color) = if (lifted) liftedForPicture(color) else color
        fun stops(s: List<Pair<Float, Color>>) = s.map { (p, color) -> p to c(color) }
        return when (fill) {
            is Fill.Solid -> {
                val color = c(fill.color)
                ({ drawRect(color, cover.topLeft, cover.size) })
            }
            is Fill.Linear -> {
                val brush = if (piece == Piece.WHOLE) linearIn(fill.angleDeg, box, stops(fill.stops)) else segmentLinear(fill.angleDeg, stops(fill.stops))
                brush?.let { b -> { drawRect(b, cover.topLeft, cover.size) } }
            }
            is Fill.Radial, is Fill.RadialDp -> {
                if (!radialShows(fill, piece)) return null
                radialBrush(fill, piece, ::stops)?.let { b -> { drawRect(b, cover.topLeft, cover.size) } }
            }
            is Fill.Lines -> linesFill(fill)
            is Fill.Halftone -> halftoneFill(fill)
        }
    }

    /**
     * A long message's piece (slots §3.4): the pieces cannot know the whole bubble's height, so a Linear keeps only its first and
     * last stops, laid across the width the way the angle runs, and every piece shows the same colours at the same x (no seam).
     * A vertical gradient has no width to run across: its middle colour.
     */
    private fun segmentLinear(angleDeg: Float, stops: List<Pair<Float, Color>>): Brush? {
        if (stops.isEmpty()) return null
        val first = stops.first().second
        val last = stops.last().second
        val across = sin(Math.toRadians(angleDeg.toDouble())).toFloat()
        if (abs(across) < .05f || first == last) return SolidColor(lerp(first, last, .5f))
        val (from, to) = if (across > 0f) first to last else last to first
        return Brush.horizontalGradient(0f to from, 1f to to, startX = 0f, endX = w)
    }

    /** A radial anchored at the top (centre at or above the middle) shows only on the first piece, a bottom one only on the last (web D4). */
    private fun radialShows(fill: Fill, piece: Piece): Boolean {
        val fy = when (fill) {
            is Fill.Radial -> fill.fy
            is Fill.RadialDp -> fill.fy
            else -> return true
        }
        return when (piece) {
            Piece.WHOLE -> true
            Piece.FIRST -> fy <= .5f
            Piece.LAST -> fy > .5f
            Piece.MIDDLE -> false
        }
    }

    /** A radial's brush; in segment mode it is laid in the piece itself (the first piece's top is the bubble's top, the last's bottom its bottom). */
    private fun radialBrush(fill: Fill, piece: Piece, stops: (List<Pair<Float, Color>>) -> List<Pair<Float, Color>>): Brush? {
        val r = if (piece == Piece.WHOLE) box else Rect(0f, 0f, w, scope.size.height)
        return when (fill) {
            is Fill.Radial -> ellipseBrush(r.left + fill.fx * r.width, r.top + fill.fy * r.height, fill.rx * r.width, fill.ry * r.height, stops(fill.stops))
            is Fill.RadialDp -> ellipseBrush(r.left + fill.fx * r.width, r.top + fill.fy * r.height, fill.rx.px(), fill.ry.px(), stops(fill.stops))
            else -> null
        }
    }

    /** CRT lines: a 1 × pitch tile (the line [Fill.Lines.width] px at its top), repeated over the band from top to bottom. */
    private fun linesFill(fill: Fill.Lines): Op? {
        val pitch = fill.pitch.px().roundToInt().coerceAtLeast(1)
        val line = fill.width.px().roundToInt().coerceIn(1, pitch)
        val tile = ImageBitmap(1, pitch)
        androidx.compose.ui.graphics.Canvas(tile).drawRect(0f, 0f, 1f, line.toFloat(), Paint().also { it.color = fill.color })
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        val y0 = box.top + fill.top * h
        val y1 = box.top + fill.bottom * h
        if (y1 <= y0) return null
        return { drawRect(brush, Offset(box.left, y0), Size(w, y1 - y0)) }
    }

    /** Kawaii.halftone's dots in one corner: its region is [Fill.Halftone.extent] on each side of the corner. */
    private fun halftoneFill(fill: Fill.Halftone): Op? {
        val e = fill.extent.px()
        if (e <= 0f) return null
        val hw = e / .62f
        val hh = e / .30f
        // Built for the bottom-left corner of an hw × hh box (x from 0, y up from hh), then mirrored onto the corner.
        val source = Kawaii.halftone(hw, hh, fill.pitch.px(), fill.maxR.px(), fill.minR.px())
        val left = physicalLeft(fill.corner)
        val topCorner = isTop(fill.corner)
        // x' = sx·x + tx, y' = sy·y + ty: the source's left edge lands on the corner's side and its bottom (y = hh) on its edge.
        val m = android.graphics.Matrix().apply {
            setScale(if (left) 1f else -1f, if (topCorner) -1f else 1f)
            postTranslate(if (left) box.left else box.right, if (topCorner) box.top + hh else box.bottom - hh)
        }
        source.asAndroidPath().transform(m)
        val color = fill.color
        return { drawPath(source, color) }
    }

    // ───── rims: after the fills ─────

    fun rim(r: Rim, shape: Path): Op? = when (r) {
        is Rim.Line -> lineRim(r)
        is Rim.Dots -> dotsRim(r, shape)
        is Rim.Brush -> brushRim(r)
    }

    private fun lineRim(r: Rim.Line): Op? {
        val width = r.width.px()
        if (width <= 0f) return null
        val inset = r.inset.px()
        val dx = r.dx.px()
        val dy = r.dy.px()
        val brush = strokeBrush(r.brush) ?: return null
        val stroke = Stroke(width, pathEffect = r.dash?.let { (on, off) -> PathEffect.dashPathEffect(floatArrayOf(on.px(), off.px()), 0f) })
        val p = when (r.sides) {
            Sides.All -> {
                if (w - 2f * inset <= 0f || h - 2f * inset <= 0f) return null
                outline(-inset, dx, dy)
            }
            else -> Path().apply {
                if (r.sides == Sides.Top || r.sides == Sides.TopBottom) {
                    moveTo(box.left + dx, box.top + inset + dy)
                    lineTo(box.right + dx, box.top + inset + dy)
                }
                if (r.sides == Sides.Bottom || r.sides == Sides.TopBottom) {
                    moveTo(box.left + dx, box.bottom - inset + dy)
                    lineTo(box.right + dx, box.bottom - inset + dy)
                }
            }
        }
        return { drawPath(p, brush, style = stroke) }
    }

    /** A rim's paint over the box. */
    private fun strokeBrush(fill: Fill): Brush? = fillBrush(fill, box, dp)

    /** drawn §2.6: the glow line, then the halo points, then the dots: three draw calls. */
    private fun dotsRim(r: Rim.Dots, shape: Path): Op? {
        val points = dotsAlong(shape, r.pitch.px())
        if (points.isEmpty()) return null
        val glow = r.glow
        val glowStroke = Stroke(r.glowWidth.px())
        val halo = r.halo.px()
        val dot = r.dot.px()
        return {
            if (glow != null) drawPath(shape, glow, style = glowStroke)
            if (halo > 0f) drawPoints(points, PointMode.Points, r.haloColor, strokeWidth = halo, cap = StrokeCap.Round)
            if (dot > 0f) drawPoints(points, PointMode.Points, r.color, strokeWidth = dot, cap = StrokeCap.Round)
        }
    }

    /** drawn §2.6 steps 1-5: the under-ink ribbon (2.3 ×) and the ink ribbon, two filled paths. */
    private fun brushRim(r: Rim.Brush): Op? {
        val width = r.width.px()
        if (width <= 0f || w <= 0f || h <= 0f) return null
        val line = Ribbon.loop(f.corners, r.start, w, h, dp, rtl, r.overshoot).let { l ->
            if (box.top == 0f) l else Polyline(l.x, FloatArray(l.size) { l.y[it] + box.top })
        }
        val ink = ringPath(Ribbon.outline(Ribbon.sides(line, width, dp, RibbonProfile.Frame)))
        val under = r.under?.let { u -> u to ringPath(Ribbon.outline(Ribbon.sides(line, width * Ribbon.UNDER_SCALE, dp, RibbonProfile.Frame))) }
        val inkColor = r.ink
        return {
            under?.let { (color, p) -> drawPath(p, color) }
            drawPath(ink, inkColor)
        }
    }

    // ───── ornaments: after the rims, unclipped (Glow excepted) ─────

    fun ornament(o: Ornament, shape: Path, piece: Piece): Op? = when (o) {
        is Ornament.CornerMarks -> cornerMarks(o)
        is Ornament.TailMark -> tailMark(o)
        is Ornament.Stub -> stub(o)
        is Ornament.Glow -> glow(o, shape, piece)
        is Ornament.StartBar -> startBar(o)
        is Ornament.Edge -> edge(o)
    }

    private fun physicalLeft(c: Corner): Boolean = (c == Corner.TopStart || c == Corner.BottomStart) != rtl
    private fun isTop(c: Corner): Boolean = c == Corner.TopStart || c == Corner.TopEnd

    /** A point given as if [c] were the top-left corner (x right, y down into the box), placed at the real corner. */
    private fun at(c: Corner, x: Float, y: Float): Offset = Offset(
        if (physicalLeft(c)) box.left + x else box.right - x,
        if (isTop(c)) box.top + y else box.bottom - y,
    )

    /**
     * web D9: with outset > 0, square L marks [outset] outside the corner; with outset 0, the frame's own corner arc plus straight
     * arms, [Ornament.CornerMarks.arm] long in total along each edge. [Ornament.CornerMarks.inner] repeats the first
     * [Ornament.CornerMarks.innerArm] of each arm in its colour.
     */
    private fun cornerMarks(o: Ornament.CornerMarks): Op? {
        val width = o.width.px()
        if (width <= 0f || o.corners.isEmpty()) return null
        fun marks(arm: Float): Path = Path().apply { for (c in o.corners) addCornerMark(this, c, arm, o.outset.px(), width) }
        val main = marks(o.arm.px())
        val stroke = Stroke(width, join = StrokeJoin.Miter, cap = StrokeCap.Butt)
        val inner = o.inner?.takeIf { o.innerArm > 0.dp }?.let { it to marks(o.innerArm.px()) }
        return {
            drawPath(main, o.color, style = stroke)
            inner?.let { (color, p) -> drawPath(p, color, style = stroke) }
        }
    }

    private fun addCornerMark(p: Path, c: Corner, arm: Float, outset: Float, width: Float) {
        if (outset > 0f) {
            val v = -outset
            p.moveToAt(c, v, v + arm)
            p.lineToAt(c, v, v)
            p.lineToAt(c, v + arm, v)
            return
        }
        val i = width / 2f
        val r = radii.of(c)
        val reach = max(arm, r)
        p.moveToAt(c, i, reach)
        if (r <= i) {
            p.lineToAt(c, i, i)
        } else if (radii.cut) {
            p.lineToAt(c, i, r)
            p.lineToAt(c, r, i)
        } else {
            p.lineToAt(c, i, r)
            val k = 12
            for (j in 1..k) {
                val a = Math.toRadians(180.0 + 90.0 * j / k)
                p.lineToAt(c, r + (r - i) * cos(a).toFloat(), r + (r - i) * sin(a).toFloat())
            }
        }
        p.lineToAt(c, reach, i)
    }

    private fun Path.moveToAt(c: Corner, x: Float, y: Float) = at(c, x, y).let { moveTo(it.x, it.y) }
    private fun Path.lineToAt(c: Corner, x: Float, y: Float) = at(c, x, y).let { lineTo(it.x, it.y) }

    /** The tail corner: [Ornament.TailMark.corner], a Flick's corner, or the smallest one (ties: bottom-end). */
    private fun tailCorner(o: Ornament.TailMark): Corner {
        o.corner?.let { return it }
        (f.corners as? Corners.Flick)?.let { return it.corner }
        return listOf(Corner.BottomEnd, Corner.BottomStart, Corner.TopEnd, Corner.TopStart).minBy { radii.of(it) }
    }

    /** drawn D13: a diamond or a petal on the tail corner's outline (the arc's 45° point), pointing out of the box. */
    private fun tailMark(o: Ornament.TailMark): Op? {
        val s = o.size.px()
        if (s <= 0f) return null
        val c = tailCorner(o)
        val r = radii.of(c)
        val k = if (radii.cut) r / 2f else r * (1f - 1f / sqrt(2f))
        val centre = at(c, k, k)
        val out = Offset(if (physicalLeft(c)) -1f else 1f, if (isTop(c)) -1f else 1f)
        val shape = when (o.kind) {
            TailKind.Diamond -> Path().apply {
                val half = s / 2f
                moveTo(centre.x, centre.y - half)
                lineTo(centre.x + half, centre.y)
                lineTo(centre.x, centre.y + half)
                lineTo(centre.x - half, centre.y)
                close()
            }
            TailKind.Petal -> petalAt(centre, s / 2f, Math.toDegrees(atan2(out.y.toDouble(), out.x.toDouble())).toFloat() + 90f)
        }
        val rimStroke = Stroke(o.rimWidth.px())
        val dotR = s * .12f
        return {
            drawPath(shape, o.color)
            o.rim?.let { if (o.rimWidth > 0.dp) drawPath(shape, it, style = rimStroke) }
            o.dot?.let { drawCircle(it, dotR, centre) }
        }
    }

    /** Kawaii.petalPath (2 × 2 round the origin, its notched tip up) scaled by [scale], turned by [deg] and moved to [centre]. */
    private fun petalAt(centre: Offset, scale: Float, deg: Float): Path {
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        fun px(x: Float, y: Float) = centre.x + (x * ca - y * sa) * scale
        fun py(x: Float, y: Float) = centre.y + (x * sa + y * ca) * scale
        return Path().apply {
            moveTo(px(0f, 1f), py(0f, 1f))
            cubicTo(px(-1.1f, .45f), py(-1.1f, .45f), px(-.9f, -.75f), py(-.9f, -.75f), px(-.18f, -1f), py(-.18f, -1f))
            lineTo(px(0f, -.72f), py(0f, -.72f))
            lineTo(px(.18f, -1f), py(.18f, -1f))
            cubicTo(px(.9f, -.75f), py(.9f, -.75f), px(1.1f, .45f), py(1.1f, .45f), px(0f, 1f), py(0f, 1f))
            close()
        }
    }

    /** web §6.4: a short bar along the corner's horizontal edge, [Ornament.Stub.length] from the corner, inside or just outside it. */
    private fun stub(o: Ornament.Stub): Op? {
        val len = o.length.px()
        val t = o.thickness.px()
        if (len <= 0f || t <= 0f) return null
        val a = at(o.corner, 0f, if (o.outside) -t else 0f)
        val b = at(o.corner, len, if (o.outside) 0f else t)
        val rect = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        return { drawRect(o.color, rect.topLeft, rect.size) }
    }

    /** A glow clipped inside the shape (a Radial or RadialDp); in segment mode only on the first or last piece (web D4). */
    private fun glow(o: Ornament.Glow, shape: Path, piece: Piece): Op? {
        if (!radialShows(o.fill, piece)) return null
        val brush = when (o.fill) {
            is Fill.Radial, is Fill.RadialDp -> radialBrush(o.fill, piece) { it }
            is Fill.Solid -> SolidColor(o.fill.color)
            is Fill.Linear -> linearIn(o.fill.angleDeg, box, o.fill.stops)
            else -> null
        } ?: return null
        val cover = Rect(box.left - 16f * dp, box.top - 16f * dp, box.right + 16f * dp, box.bottom + 16f * dp)
        return { clipPath(shape) { drawRect(brush, cover.topLeft, cover.size) } }
    }

    /** drawn D3: a vertical bar on the start edge from top + inset to bottom − inset, with ruler ticks or as a brush stroke. */
    private fun startBar(o: Ornament.StartBar): Op? {
        val bw = o.width.px()
        val inset = o.inset.px()
        val y0 = box.top + inset
        val y1 = box.bottom - inset
        if (bw <= 0f || y1 <= y0) return null
        val x0 = if (rtl) box.right - bw else box.left
        val rect = Rect(x0, y0, x0 + bw, y1)
        val ticks = o.ticks?.px()?.takeIf { it > 0f }?.let { pitch ->
            val len = TickDp * dp
            Path().apply {
                var y = y0
                while (y <= y1 + .01f) {
                    val inner = if (rtl) rect.left else rect.right
                    moveTo(inner, y)
                    lineTo(if (rtl) inner - len else inner + len, y)
                    y += pitch
                }
            }
        }
        val tickStroke = Stroke(max(bw, .75f * dp))
        if (o.brush) {
            val colour = (o.fill as? Fill.Solid)?.color ?: stopsOf(o.fill).firstOrNull() ?: return null
            val n = max(2, ((y1 - y0) / (Ribbon.STEP_DP * dp)).roundToInt())
            val line = Polyline(FloatArray(n + 1) { rect.center.x }, FloatArray(n + 1) { y0 + (y1 - y0) * it / n })
            val ribbon = ringPath(Ribbon.outline(Ribbon.sides(line, bw, dp, RibbonProfile.Rule)))
            return {
                drawPath(ribbon, colour)
                ticks?.let { drawPath(it, colour, style = tickStroke) }
            }
        }
        val brush = when (val fill = o.fill) {
            is Fill.Linear -> linearIn(fill.angleDeg, rect, fill.stops)
            else -> strokeBrush(fill)
        } ?: return null
        val tickColour = stopsOf(o.fill).firstOrNull() ?: (o.fill as? Fill.Lines)?.color
        return {
            drawRect(brush, rect.topLeft, rect.size)
            if (ticks != null && tickColour != null) drawPath(ticks, tickColour, style = tickStroke)
        }
    }

    /** web D3: an EdgeDrawing along one side, [Ornament.Edge.gap] beyond it, [Ornament.Edge.length] long (null: the whole side). */
    private fun edge(o: Ornament.Edge): Op? {
        val gap = o.gap.px()
        val horizontal = o.side == Side.Top || o.side == Side.Bottom
        val span = if (horizontal) w else h
        val len = (o.length?.px() ?: span).coerceIn(0f, span)
        if (len <= 0f) return null
        val startAt = if (o.align == EdgeAlign.Start) 0f else (span - len) / 2f
        val (from, to) = if (horizontal) {
            val y = if (o.side == Side.Top) box.top - gap else box.bottom + gap
            // Along a horizontal side the line runs from its start end (the left in LTR).
            if (!rtl) Offset(box.left + startAt, y) to Offset(box.left + startAt + len, y)
            else Offset(box.right - startAt, y) to Offset(box.right - startAt - len, y)
        } else {
            val startSide = o.side == Side.Start
            val x = if (startSide != rtl) box.left - gap else box.right + gap
            Offset(x, box.top + startAt) to Offset(x, box.top + startAt + len)
        }
        val draw = o.drawing.build(scope, from, to)
        return { draw(0f) }
    }
}

/** A CSS linear gradient over [r] (Glass.kt's cssLinear, placed in the rect). */
internal fun linearIn(angleDeg: Float, r: Rect, stops: List<Pair<Float, Color>>): Brush? {
    if (stops.isEmpty()) return null
    if (stops.size == 1) return SolidColor(stops[0].second)
    val a = Math.toRadians(angleDeg.toDouble())
    val dx = sin(a).toFloat()
    val dy = (-cos(a)).toFloat()
    val half = (abs(r.width * dx) + abs(r.height * dy)) / 2f
    val c = r.center
    return Brush.linearGradient(
        colorStops = stops.toTypedArray(),
        start = Offset(c.x - dx * half, c.y - dy * half),
        end = Offset(c.x + dx * half, c.y + dy * half),
    )
}

/** [fill] as one brush over [r] at [dp] px per dp; Lines and Halftone have no gradient of their own, so their colour. */
internal fun fillBrush(fill: Fill, r: Rect, dp: Float): Brush? = when (fill) {
    is Fill.Solid -> SolidColor(fill.color)
    is Fill.Linear -> linearIn(fill.angleDeg, r, fill.stops)
    is Fill.Radial -> ellipseBrush(r.left + fill.fx * r.width, r.top + fill.fy * r.height, fill.rx * r.width, fill.ry * r.height, fill.stops)
    is Fill.RadialDp -> ellipseBrush(r.left + fill.fx * r.width, r.top + fill.fy * r.height, fill.rx.value * dp, fill.ry.value * dp, fill.stops)
    is Fill.Lines -> SolidColor(fill.color)
    is Fill.Halftone -> SolidColor(fill.color)
}

/** A code block's ruler ticks are this long. */
private const val TickDp = 3f

/** An elliptical radial gradient: centre ([cx], [cy]), radii [rx] × [ry] (a circle squashed by the shader's local matrix). */
internal fun ellipseBrush(cx: Float, cy: Float, rx: Float, ry: Float, stops: List<Pair<Float, Color>>): Brush? {
    if (stops.isEmpty() || rx <= 0f || ry <= 0f) return null
    if (stops.size == 1) return SolidColor(stops[0].second)
    val shader = RadialGradientShader(Offset(cx, cy), rx, stops.map { it.second }, stops.map { it.first }, TileMode.Clamp)
    if (ry != rx) shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, ry / rx, cx, cy) })
    return ShaderBrush(shader)
}

// ───── runners: a highlight moving along the outline (HEADER/DOCK), reading the ThemeClock in draw ─────

/**
 * [drawing] along [shape]'s outline at the phase of [time] (seconds) over [periodMs]; [stillPhase] when [time] is null (motion off)
 * or while [idle]. Drawn over the content, as flowLight is.
 */
internal data class RunnerElement(
    val drawing: OutlineDrawing,
    val shape: Shape,
    val time: State<Float>?,
    val idle: State<Boolean>,
    val periodMs: Int,
    val stillPhase: Float,
) : ModifierNodeElement<RunnerNode>() {
    override fun create() = RunnerNode(this)
    override fun update(node: RunnerNode) = node.update(this)
    override fun InspectorInfo.inspectableProperties() {
        name = "runner"
        properties["periodMs"] = periodMs
        properties["moving"] = time != null
    }
}

internal class RunnerNode(private var spec: RunnerElement) : DelegatingNode() {
    private val cache = delegate(
        CacheDrawModifierNode {
            val outline = spec.shape.createOutline(size, layoutDirection, this).toPath()
            val draw = spec.drawing.build(this, outline)
            onDrawWithContent {
                drawContent()
                draw(phase())
            }
        },
    )

    /** The phase now: still unless the clock is held for this runner and the screen is not idle (both read in draw only). */
    private fun phase(): Float {
        val s = spec
        val t = s.time ?: return s.stillPhase
        if (s.idle.value || s.periodMs <= 0) return s.stillPhase
        return ((t.value * 1000f / s.periodMs) % 1f + 1f) % 1f
    }

    fun update(next: RunnerElement) {
        val old = spec
        spec = next
        if (old.drawing !== next.drawing || old.shape != next.shape) cache.invalidateDrawCache() else cache.invalidateDraw()
    }
}
