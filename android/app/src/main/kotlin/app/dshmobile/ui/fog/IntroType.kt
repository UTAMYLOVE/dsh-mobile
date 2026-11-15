package app.dshmobile.ui.fog

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.dshmobile.ui.theme.Sakura

/** Splits [s] into user-perceived characters, so an emoji or a surrogate pair in a name is never cut in half. */
internal fun graphemes(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val it = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(s) }
    val out = ArrayList<String>()
    var a = it.first()
    var b = it.next()
    while (b != android.icu.text.BreakIterator.DONE) { out += s.substring(a, b); a = b; b = it.next() }
    return out
}

/** True for a picture character (an emoji): its colours come from the font, so it is drawn as an ink silhouette instead. */
internal fun isEmoji(g: String): Boolean {
    var i = 0
    while (i < g.length) {
        val cp = g.codePointAt(i)
        if (cp == 0xFE0F || cp == 0x200D || UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)) return true
        i += Character.charCount(cp)
    }
    return false
}

/** Only Latin letters, digits and spaces: such names get slightly tighter tracking than CJK ones. */
internal fun isLatin(s: String): Boolean = s.all { it == ' ' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }

/**
 * A line of text measured once, glyph by glyph, so the intro can move, scale and fade each glyph in the draw phase without ever
 * laying text out again (the old intro built a new AnnotatedString, and so a new layout, every frame). [x] are the glyph lefts
 * from the line's left at rest tracking; [baseline] is from the line's top.
 */
internal class GlyphRun(
    val text: List<String>,
    val glyphs: Array<TextLayoutResult>,
    val x: FloatArray,
    val advance: FloatArray,
    val width: Float,
    val height: Float,
    val baseline: Float,
    val fontPx: Float,
    val emoji: BooleanArray,
) {
    val size: Int get() = glyphs.size
}

/** The shared text styles of the intro; sizes are multiplied by the screen factor `s` when measured. */
internal object IntroFonts {
    fun chakra(sp: Float, semibold: Boolean, tnum: Boolean = false) = TextStyle(
        fontFamily = FogFonts.ChakraPetch, fontWeight = if (semibold) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = sp.sp, fontFeatureSettings = if (tnum) "tnum" else null,
    )

    fun sans(sp: Float, weight: FontWeight) = TextStyle(fontWeight = weight, fontSize = sp.sp)

    fun serifItalic(sp: Float) = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = sp.sp)
}

/**
 * Measures text for the intro at a capped font scale (at most 1.3, so a very large system font cannot push the composition off
 * screen; the auto-fit below keeps it in bounds anyway).
 */
internal class IntroMeasure(private val measurer: TextMeasurer, density: Float, fontScale: Float, private val s: Float) {
    private val density = Density(density, fontScale.coerceAtMost(1.3f))
    val pxPerSp: Float = density * fontScale.coerceAtMost(1.3f)

    private fun scaled(style: TextStyle, sizeSp: Float): TextStyle = style.copy(fontSize = TextUnit(sizeSp * s, style.fontSize.type))

    /** The style's own size (× s) unless [sizeSp] is given. */
    fun one(text: String, style: TextStyle, sizeSp: Float = style.fontSize.value): TextLayoutResult =
        measurer.measure(text, scaled(style, sizeSp), softWrap = false, maxLines = 1, density = density)

    /** [text] glyph by glyph with [trackEm] of tracking between glyphs. */
    fun run(text: String, style: TextStyle, trackEm: Float, sizeSp: Float = style.fontSize.value): GlyphRun {
        val parts = graphemes(text)
        val st = scaled(style, sizeSp)
        val fontPx = sizeSp * s * pxPerSp
        val track = trackEm * fontPx
        val layouts = Array(parts.size) { measurer.measure(parts[it], st, softWrap = false, maxLines = 1, density = density) }
        val adv = FloatArray(parts.size) { i -> layouts[i].let { if (it.lineCount > 0) it.getLineRight(0) - it.getLineLeft(0) else 0f } }
        val x = FloatArray(parts.size)
        var at = 0f
        for (i in parts.indices) { x[i] = at; at += adv[i] + track }
        val width = if (parts.isEmpty()) 0f else at - track
        val ref = layouts.firstOrNull() ?: measurer.measure(" ", st, density = density)
        return GlyphRun(
            parts, layouts, x, adv, width, ref.size.height.toFloat(), ref.firstBaseline, fontPx,
            BooleanArray(parts.size) { isEmoji(parts[it]) },
        )
    }

    /** [run] at the largest size from [startSp] down to [floorSp] that is at most [maxW] wide (the floor when nothing fits). */
    fun fit(text: String, style: TextStyle, trackEm: Float, startSp: Float, floorSp: Float, maxW: Float): GlyphRun {
        var sp = startSp
        var r = run(text, style, trackEm, sp)
        while (r.width > maxW && sp > floorSp) {
            sp = maxOf(floorSp, minOf(sp - 2f, sp * maxW / r.width))
            r = run(text, style, trackEm, sp)
        }
        return r
    }
}

/** One paint for every emoji silhouette: an emoji drawn into a layer with this paint comes out in the hero text colour. */
private val EmojiInk = Paint().apply { colorFilter = ColorFilter.tint(Sakura.Text, BlendMode.SrcIn) }
private val EmojiShadow = Paint().apply { colorFilter = ColorFilter.tint(Sakura.Primary, BlendMode.SrcIn) }

/**
 * Draws glyph [i] of [run] with its top-left at [at], [scale] about its own bottom centre and [alpha]. Emoji become ink
 * silhouettes, a little smaller and lighter than the letters (a full-size solid one outweighed the name); [stroke] (a sticker
 * outline) skips them, since a colour bitmap has no outline to stroke.
 */
internal fun DrawScope.drawGlyph(
    run: GlyphRun, i: Int, at: Offset, color: Color, alpha: Float, scale: Float = 1f, stroke: Stroke? = null, shadow: Boolean = false,
) {
    if (alpha <= 0.004f) return
    val layout = run.glyphs[i]
    withTransform({
        translate(at.x, at.y)
        if (scale != 1f) scale(scale, scale, Offset(run.advance[i] / 2f, run.baseline))
    }) {
        if (run.emoji[i]) {
            if (stroke == null) withTransform({ scale(.80f, .80f, Offset(run.advance[i] / 2f, run.baseline * .62f)) }) {
                drawIntoCanvas { canvas ->
                    val paint = if (shadow) EmojiShadow else EmojiInk
                    paint.alpha = alpha * (if (shadow) 1f else .70f)
                    canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, layout.size.width.toFloat(), layout.size.height.toFloat()), paint)
                    drawText(layout, topLeft = Offset.Zero, drawStyle = Fill)
                    canvas.restore()
                }
            }
        } else {
            // Always pass the style: a text paint keeps the last one it was given, so a fill after a stroke would stay stroked.
            drawText(layout, color = color, topLeft = Offset.Zero, alpha = alpha, drawStyle = stroke ?: Fill)
        }
    }
}

/**
 * The whole [run] at [left], [top], with [spread] px of extra tracking per glyph (tracking-in without relayout); [centered]
 * spreads it about the run's middle instead of its left edge, for centred lines.
 */
internal fun DrawScope.drawRun(run: GlyphRun, left: Float, top: Float, color: Color, alpha: Float, spread: Float = 0f, centered: Boolean = false) {
    val mid = if (centered) (run.size - 1) / 2f else 0f
    for (i in 0 until run.size) drawGlyph(run, i, Offset(left + run.x[i] + (i - mid) * spread, top), color, alpha)
}

/** A sticker outline stroke for [run]: white, round joins, [em] of the font size wide. */
internal fun stickerStroke(run: GlyphRun, em: Float = .11f) = Stroke(width = run.fontPx * em, join = StrokeJoin.Round)
