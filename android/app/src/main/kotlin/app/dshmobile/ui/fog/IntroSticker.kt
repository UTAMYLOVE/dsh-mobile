package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.util.lerp
import app.dshmobile.ui.theme.Sakura
import kotlin.math.max
import kotlin.math.min

/**
 * The name as a sticker, shared by every look: the handwriting at up to [capSp] (88 sp on a phone), thickened, inside a white
 * outline with a thin blush inline and a pink hard drop shadow. It fits [maxW] on one line down to [floorSp], else breaks onto two
 * balanced lines (a near tie keeps the shorter line on top; a name with spaces breaks only at a space; what still does not fit
 * ends in an ellipsis). Measured once; [drawSticker] stamps it in glyph by glyph.
 */
internal class IntroSticker(private val mm: IntroMeasure, text: String, maxW: Float, capSp: Float, floorSp: Float = 44f, private val dp: Float) {
    val lines: List<GlyphRun>

    init {
        // +.02 em for Latin too: at -.01 em the sticker's white outlines of 'oo' and 'wo' fused into one blob.
        val track = .02f
        val style = IntroFonts.hand(capSp)
        val one = mm.fit(text, style, track, capSp, floorSp, maxW)
        lines = if (one.width <= maxW || graphemes(text).size < 2) listOf(one) else twoLines(text, style, track, maxW, floorSp)
    }

    private fun twoLines(text: String, style: TextStyle, track: Float, maxW: Float, floorSp: Float): List<GlyphRun> {
        val g = graphemes(text)
        val full = mm.run(text, style, track, floorSp)
        val n = g.size
        fun left(k: Int) = full.x[k - 1] + full.advance[k - 1]
        fun right(k: Int) = full.x[n - 1] + full.advance[n - 1] - full.x[k]
        val spaced = g.any { it == " " }
        val breaks = (1 until n).filter { !spaced || g[it - 1] == " " }
        val least = breaks.minOfOrNull { max(left(it), right(it)) } ?: Float.MAX_VALUE
        val best = breaks.firstOrNull { max(left(it), right(it)) <= least + .25f * full.fontPx } ?: -1
        if (best > 0 && least <= maxW) {
            return listOf(mm.run(g.take(best).joinToString("").trim(), style, track, floorSp), mm.run(g.drop(best).joinToString("").trim(), style, track, floorSp))
        }
        var k = 1
        while (k < n - 1 && left(k + 1) <= maxW) k++
        val first = mm.run(g.take(k).joinToString("").trim(), style, track, floorSp)
        var rest = g.drop(k)
        var second = mm.run(rest.joinToString("").trim(), style, track, floorSp)
        while (second.width > maxW && rest.size > 1) {
            rest = rest.dropLast(1)
            second = mm.run(rest.joinToString("").trim() + "…", style, track, floorSp)
        }
        return listOf(first, second)
    }

    val fontPx: Float = lines[0].fontPx
    /** Two lines get more air, so their white outlines do not touch. */
    val lh: Float = if (lines.size > 1) 1.18f else 1.08f
    val height: Float = ((lines.size - 1) * lh + 1.08f) * fontPx
    val width: Float = lines.maxOf { it.width }
    /** The white outline, round the thickened body. */
    val stroke: Stroke = stickerStroke(lines[0])
    /** A thin blush line between the ink and the white: ink anti-aliased straight onto white made a grey fringe (#E7E3E3). */
    val inline: Stroke = Stroke(1.6f * dp + lines[0].thickPx, join = StrokeJoin.Round)
    /** True when every line is Latin: such a name stamps in faster (more, narrower glyphs). */
    val latin: Boolean = lines.all { line -> line.text.all { isLatin(it) } }

    /** Line [line]'s baseline when the sticker's top is at [top]. */
    fun baseline(top: Float, line: Int): Float = top + line * lh * fontPx + .92f * fontPx
}

/** The name shadow's layer: drawn opaque inside, faded as one. */
private val ShadowPaint = Paint()
/** One name glyph while it fades in: outline and fill composed opaque, faded as one. */
private val GlyphPaint = Paint()
/** The paint for the glints' offscreen layer (SrcAtop needs the glyphs alone in a layer). */
private val LayerPaint = Paint()

/** A diagonal white glint at progress [k] across [bounds], over whatever [body] draws (only where it drew). */
internal fun DrawScope.glint(bounds: Rect, k: Float, body: DrawScope.() -> Unit) {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bounds.inflate(bounds.height * .5f), LayerPaint)
        body()
        val bw = bounds.width * .22f
        val x = lerp(bounds.left - bw * 2f, bounds.right + bw, k)
        withTransform({ rotate(-20f, Offset(x + bw / 2f, bounds.center.y)) }) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = .40f), Color.White.copy(alpha = 0f)), x, x + bw),
                Offset(x, bounds.top - bounds.height), Size(bw, bounds.height * 3f), blendMode = BlendMode.SrcAtop,
            )
        }
        canvas.restore()
    }
}

/**
 * [st] centred on [centerX] with its top at [top]: every glyph rises .35 em, grows from 1.12 and turns opaque within 60 ms, one
 * after another from [start] (40 ms apart for Chinese, 20 for Latin, 24 when [compact]; all within 240 ms, all × [pace]). Then a
 * glint at start + 560 ms and every 2.4 s while there is time before [glintUntil]. [reduced] draws it settled, with no glint.
 */
internal fun DrawScope.drawSticker(
    st: IntroSticker, centerX: Float, top: Float, ms: Float, start: Float, pace: Float, compact: Boolean, reduced: Boolean, glintUntil: Float,
    alpha: Float = 1f,
) {
    if ((ms < start && !reduced) || alpha <= 0f) return
    val stagger = (if (compact) 24f else if (!st.latin) 40f else 20f) * pace
    val total = st.lines.sumOf { it.size }
    val step = if (total > 1) min(stagger, 240f * pace / (total - 1)) else 0f
    val stroke = st.stroke
    val pad = stroke.width + 4f * density
    val shadowDx = .03f * st.fontPx
    val shadowDy = .07f * st.fontPx
    val block = Rect(centerX - st.width / 2f, top, centerX + st.width / 2f, top + st.height)
    // Every glyph that has started: its top-left (rising .35 em), alpha (opaque within 60 ms) and scale (1.12 → 1).
    val glyphs: DrawScope.(each: DrawScope.(GlyphRun, Int, Offset, Float, Float) -> Unit) -> Unit = { each ->
        var n = 0
        for ((li, line) in st.lines.withIndex()) {
            val left = centerX - line.width / 2f
            val base = st.baseline(top, li)
            for (g in 0 until line.size) {
                val raw = if (reduced) 1f else ((ms - start - step * n) / (420f * pace)).coerceIn(0f, 1f)
                n++
                if (raw <= 0f) continue
                val e = IntroEase.ExpoOut.transform(raw)
                each(line, g, Offset(left + line.x[g], base - line.baseline + .35f * line.fontPx * (1f - e)), (raw / .14f).coerceAtMost(1f), lerp(1.12f, 1f, e))
            }
        }
    }
    val outline: DrawScope.(GlyphRun, Int, Offset, Float) -> Unit = { line, g, at, k -> drawGlyph(line, g, at, Sakura.White, 1f, k, stroke = stroke, sticker = true) }
    val fill: DrawScope.(GlyphRun, Int, Offset, Float) -> Unit = { line, g, at, k ->
        drawGlyph(line, g, at, Sakura.Background, 1f, k, stroke = st.inline)
        drawGlyph(line, g, at, Sakura.Text, 1f, k, sticker = true)
    }
    val body: DrawScope.() -> Unit = {
        // The pink hard shadow is the whole sticker's silhouette (outline and fill), offset down-right, in one layer so the
        // overlaps stay one even pink: offset by the fill alone, the white outline covered it completely.
        if (IntroConst.NAME_STICKER) drawIntoCanvas { canvas ->
            ShadowPaint.alpha = .85f * alpha
            canvas.saveLayer(block.inflate(stroke.width + shadowDy), ShadowPaint)
            glyphs { line, g, at, a, k ->
                val o = at + Offset(shadowDx, shadowDy)
                drawGlyph(line, g, o, Sakura.Primary, a, k, stroke = stroke, sticker = true)
                drawGlyph(line, g, o, Sakura.Primary, a, k, shadow = true, sticker = true)
            }
            canvas.restore()
        }
        // Settled glyphs: every outline, then every fill, so no outline ever cuts into a neighbour's ink.
        val settled: DrawScope.() -> Unit = {
            glyphs { line, g, at, a, k -> if (a >= 1f) outline(line, g, at, k) }
            glyphs { line, g, at, a, k -> if (a >= 1f) fill(line, g, at, k) }
        }
        if (alpha >= 1f) settled() else drawIntoCanvas { canvas ->
            GlyphPaint.alpha = alpha
            canvas.saveLayer(block.inflate(pad), GlyphPaint)
            settled()
            canvas.restore()
        }
        // A glyph still coming in is composed opaque (outline and fill) in its own layer and faded as one: ink half faded over an
        // already opaque white outline read as grey frosted plastic.
        glyphs { line, g, at, a, k ->
            if (a < 1f) drawIntoCanvas { canvas ->
                val size = line.glyphs[g].size
                GlyphPaint.alpha = a * alpha
                canvas.saveLayer(Rect(at.x - .1f * size.width - pad, at.y - .15f * size.height - pad, at.x + 1.1f * size.width + pad, at.y + size.height + pad), GlyphPaint)
                outline(line, g, at, k)
                fill(line, g, at, k)
                canvas.restore()
            }
        }
    }
    // Glints: first 560 ms after the name starts (when there is time for it), then every 2.4 s while the welcome holds.
    var gk = 0f
    if (!reduced) {
        var at = start + 560f * pace
        if (glintUntil - at >= 300f) {
            while (at + 2400f <= ms && glintUntil - (at + 2400f) > 700f) at += 2400f
            gk = seg(ms, at, 460f, IntroEase.CubicInOut)
        }
    }
    if (gk > 0f && gk < 1f) glint(block, gk, body) else body()
}
