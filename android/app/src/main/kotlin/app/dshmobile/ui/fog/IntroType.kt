package app.dshmobile.ui.fog

import android.content.res.Resources
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.dshmobile.R
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
 * from the line's left at rest tracking; [baseline] is from the line's top, and [dy] moves each glyph so its own baseline sits
 * on it (a mixed run sets Latin and Chinese in two fonts with different ascents, and a character missing from the handwriting
 * falls back to the system font). [thick] is the same-colour stroke (px) that thickens a handwriting glyph, 0 for the others.
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
    val dy: FloatArray = FloatArray(glyphs.size),
    val thick: FloatArray = FloatArray(glyphs.size),
) {
    val size: Int get() = glyphs.size

    /** The thickening of this run's handwriting glyphs (px); an outline round the run adds it, so its white rim stays as wide. */
    val thickPx: Float = thick.maxOrNull() ?: 0f

    // One stroke per width, built on first use: a frame never allocates one.
    private val bodies = HashMap<Float, Stroke>()
    private val thinner = HashMap<Stroke, Stroke>()

    /** The stroke that thickens glyph [i] (round, so the pen's ends stay soft), or null for a glyph drawn as it is. */
    internal fun body(i: Int): Stroke? {
        val w = thick[i]
        if (w <= 0f) return null
        return bodies.getOrPut(w) { Stroke(w, join = StrokeJoin.Round, cap = StrokeCap.Round) }
    }

    /** [outline] for glyph [i]: one drawn without thickening (a fallback character, an emoji) gets it [thickPx] narrower. */
    internal fun outlineFor(i: Int, outline: Stroke): Stroke {
        val less = thickPx - thick[i]
        if (less <= 0f) return outline
        return thinner.getOrPut(outline) { Stroke((outline.width - less).coerceAtLeast(0f), join = outline.join, cap = outline.cap) }
    }
}

/**
 * The intro's type, shared by every look. Chinese is the owner's choice, 辰宇落雁體 (bundled as the renamed subset
 * "DSHM Luoyan Hand", see tools/fonts/subset_intro_font.py); small Latin labels are Quicksand. The handwriting is Thin only,
 * so each of its glyphs is drawn with a fill plus a stroke of its own colour ([thickEm]): at large sizes it reads as pen ink,
 * not as faint scribbles. Small Chinese never goes below [MIN_HAND_SP]. Sizes are multiplied by the screen factor `s` when
 * measured.
 */
internal object IntroFonts {
    /** The handwriting: the 4,808 common characters, the intro's own strings, ASCII and CJK punctuation. */
    val Hand: FontFamily = FontFamily(Font(R.font.intro_hand, FontWeight.Normal))

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val Latin: FontFamily = FontFamily(
        Font(R.font.intro_latin, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.intro_latin, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.intro_latin, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

    /** Small Chinese (subtitles, 跳過) is set at least this large: the thin pen at 13 sp read as a scratch. */
    const val MIN_HAND_SP = 15f

    /** Chinese text in the handwriting at [sp] (at least [MIN_HAND_SP] when [small]). */
    fun hand(sp: Float, small: Boolean = false) = TextStyle(fontFamily = Hand, fontSize = (if (small) maxOf(sp, MIN_HAND_SP) else sp).sp)

    /** Latin labels, numbers and the ID in Quicksand ([FontWeight.Medium], [FontWeight.SemiBold] or [FontWeight.Bold]). */
    fun latin(sp: Float, weight: FontWeight = FontWeight.SemiBold) = TextStyle(fontFamily = Latin, fontWeight = weight, fontSize = sp.sp)

    /** The system font (only where text is not part of the look, e.g. an emoji's own font). */
    fun sans(sp: Float, weight: FontWeight) = TextStyle(fontWeight = weight, fontSize = sp.sp)

    /**
     * How much a handwriting glyph [sizeSp] large (after the screen factor) is thickened, in em: .045 from 40 sp up, rising to
     * .08 at 15 sp and below. At .07 em the hero lines read as a felt marker and the strokes of 驗 or 歡 ran into one blob; at
     * .045 the pen still writes like a pen. Small text needs more, or 15 sp stays a hairline.
     */
    fun thickEm(sizeSp: Float): Float = .045f + .035f * ((40f - sizeSp) / 25f).coerceIn(0f, 1f)

    @Volatile private var covered: IntArray? = null

    /**
     * Every code point the handwriting has (res/raw/intro_hand_chars.txt, written by the subset script), sorted. A character
     * outside it (a rare name character) falls back to the system font and is drawn without the thickening stroke.
     */
    fun coverage(res: Resources): IntArray = covered ?: synchronized(this) {
        covered ?: runCatching {
            res.openRawResource(R.raw.intro_hand_chars).use { String(it.readBytes(), Charsets.UTF_8) }.codePoints().toArray().apply { sort() }
        }.getOrDefault(IntArray(0)).also { covered = it }
    }

    /** True when every code point of [g] is in [coverage] (empty: assume yes). */
    fun covers(coverage: IntArray, g: String): Boolean {
        if (coverage.isEmpty()) return true
        var i = 0
        while (i < g.length) {
            val cp = g.codePointAt(i)
            if (cp > 0x20 && java.util.Arrays.binarySearch(coverage, cp) < 0) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** Han, kana, CJK punctuation and full-width forms: what a mixed run sets in the handwriting. */
    fun isCjk(g: String): Boolean {
        if (g.isEmpty()) return false
        val cp = g.codePointAt(0)
        return cp in 0x2E80..0x9FFF || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFFEF || cp in 0x20000..0x3FFFF
    }
}

/**
 * Measures text for the intro at a capped font scale (at most 1.3, so a very large system font cannot push the composition off
 * screen; the auto-fit below keeps it in bounds anyway). [coverage] is the handwriting's character set ([IntroFonts.coverage]);
 * empty means "every character is there".
 */
internal class IntroMeasure(
    private val measurer: TextMeasurer, density: Float, fontScale: Float, private val s: Float, private val coverage: IntArray = IntArray(0),
) {
    private val density = Density(density, fontScale.coerceAtMost(1.3f))
    val pxPerSp: Float = density * fontScale.coerceAtMost(1.3f)

    private fun scaled(style: TextStyle, sizeSp: Float): TextStyle = style.copy(fontSize = TextUnit(sizeSp * s, style.fontSize.type))

    /** The style's own size (× s) unless [sizeSp] is given. */
    fun one(text: String, style: TextStyle, sizeSp: Float = style.fontSize.value): TextLayoutResult =
        measurer.measure(text, scaled(style, sizeSp), softWrap = false, maxLines = 1, density = density)

    /** [text] glyph by glyph with [trackEm] of tracking between glyphs. */
    fun run(text: String, style: TextStyle, trackEm: Float, sizeSp: Float = style.fontSize.value): GlyphRun =
        build(graphemes(text), { style }, { sizeSp }, trackEm, sizeSp, style)

    /**
     * A run that mixes Latin and Chinese ("AUTHORIZATION · 認證程序", the ring's inscription): Chinese graphemes in [hand] at
     * [handScale] times the size (the pen's characters look small next to Latin capitals), everything else in [latin], all on
     * the Latin baseline.
     */
    fun mixed(text: String, latin: TextStyle, hand: TextStyle, trackEm: Float, sizeSp: Float = latin.fontSize.value, handScale: Float = 1f): GlyphRun =
        build(graphemes(text), { if (IntroFonts.isCjk(it)) hand else latin }, { if (IntroFonts.isCjk(it)) sizeSp * handScale else sizeSp }, trackEm, sizeSp, latin)

    private fun build(parts: List<String>, pick: (String) -> TextStyle, sizeOf: (String) -> Float, trackEm: Float, sizeSp: Float, ref: TextStyle): GlyphRun {
        val fontPx = sizeSp * s * pxPerSp
        val track = trackEm * fontPx
        val styles = Array(parts.size) { pick(parts[it]) }
        val sizes = FloatArray(parts.size) { sizeOf(parts[it]) }
        val layouts = Array(parts.size) { measurer.measure(parts[it], scaled(styles[it], sizes[it]), softWrap = false, maxLines = 1, density = density) }
        val adv = FloatArray(parts.size) { i -> layouts[i].let { if (it.lineCount > 0) it.getLineRight(0) - it.getLineLeft(0) else 0f } }
        val x = FloatArray(parts.size)
        var at = 0f
        for (i in parts.indices) { x[i] = at; at += adv[i] + track }
        val width = if (parts.isEmpty()) 0f else at - track
        // Every glyph's baseline on the run's own font's: a fallback or Latin glyph has other ascents than the handwriting.
        val refLayout = measurer.measure(if (ref.fontFamily == IntroFonts.Hand) "口" else "H", scaled(ref, sizeSp), softWrap = false, maxLines = 1, density = density)
        val baseline = refLayout.firstBaseline
        val dy = FloatArray(parts.size) { baseline - layouts[it].firstBaseline }
        val thick = FloatArray(parts.size) { i ->
            val pen = styles[i].fontFamily == IntroFonts.Hand && !isEmoji(parts[i]) && IntroFonts.covers(coverage, parts[i])
            if (pen) IntroFonts.thickEm(sizes[i] * s) * sizes[i] * s * pxPerSp else 0f
        }
        return GlyphRun(parts, layouts, x, adv, width, refLayout.size.height.toFloat(), baseline, fontPx, BooleanArray(parts.size) { isEmoji(parts[it]) }, dy, thick)
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
/** A thickened glyph while it fades: fill and stroke composed opaque in a layer with this paint's alpha. */
private val FadePaint = Paint()
/** Tint paints for sticker emoji, one per colour (only the few sticker colours are ever asked for). */
private val EmojiTints = HashMap<Color, Paint>()

/** The emoji glyph [layout] as a flat [color] silhouette, grown by [grow] px (drawn at 12 offsets round it) for an outline. */
private fun DrawScope.emojiSilhouette(layout: TextLayoutResult, color: Color, alpha: Float, grow: Float) {
    val paint = EmojiTints.getOrPut(color) { Paint().apply { colorFilter = ColorFilter.tint(color, BlendMode.SrcIn) } }
    paint.alpha = alpha
    drawIntoCanvas { canvas ->
        canvas.saveLayer(androidx.compose.ui.geometry.Rect(-grow, -grow, layout.size.width + grow, layout.size.height + grow), paint)
        if (grow > 0f) for (k in 0 until 12) {
            val a = k * (Math.PI / 6.0)
            drawText(layout, topLeft = Offset((kotlin.math.cos(a) * grow).toFloat(), (kotlin.math.sin(a) * grow).toFloat()), drawStyle = Fill)
        }
        drawText(layout, topLeft = Offset.Zero, drawStyle = Fill)
        canvas.restore()
    }
}

/**
 * Draws glyph [i] of [run] with its top-left at [at], [scale] about its own bottom centre and [alpha]. A handwriting glyph is
 * filled and then stroked in the same colour ([GlyphRun.body]), which turns the thin pen into ink; [stroke] (a sticker outline)
 * is drawn round that thickened body. Emoji become ink silhouettes, a little smaller and lighter than the letters (a full-size
 * solid one outweighed the name); [stroke] skips them, since a colour bitmap has no outline to stroke. With [sticker] (the name)
 * an emoji joins the sticker instead: [stroke] grows its silhouette by half the stroke in [color], and the fill is pink with a
 * fine rose-brown edge (a dark silhouette with no outline read as a blot next to the sticker letters).
 */
internal fun DrawScope.drawGlyph(
    run: GlyphRun, i: Int, at: Offset, color: Color, alpha: Float, scale: Float = 1f, stroke: Stroke? = null, shadow: Boolean = false,
    sticker: Boolean = false,
) {
    if (alpha <= 0.004f) return
    val layout = run.glyphs[i]
    withTransform({
        translate(at.x, at.y)
        if (scale != 1f) scale(scale, scale, Offset(run.advance[i] / 2f, run.baseline))
        if (run.dy[i] != 0f) translate(0f, run.dy[i])
    }) {
        if (run.emoji[i] && sticker) {
            withTransform({ scale(.80f, .80f, Offset(run.advance[i] / 2f, run.baseline * .62f)) }) {
                when {
                    stroke != null -> emojiSilhouette(layout, color, alpha, run.outlineFor(i, stroke).width / 2f / .80f)
                    shadow -> emojiSilhouette(layout, color, alpha, 0f)
                    else -> {
                        emojiSilhouette(layout, Sakura.RoseBrown, alpha, 1f * density / .80f)
                        emojiSilhouette(layout, Sakura.Primary, alpha, 0f)
                    }
                }
            }
        } else if (run.emoji[i]) {
            if (stroke == null) withTransform({ scale(.80f, .80f, Offset(run.advance[i] / 2f, run.baseline * .62f)) }) {
                drawIntoCanvas { canvas ->
                    val paint = if (shadow) EmojiShadow else EmojiInk
                    paint.alpha = alpha * (if (shadow) 1f else .70f)
                    canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, layout.size.width.toFloat(), layout.size.height.toFloat()), paint)
                    drawText(layout, topLeft = Offset.Zero, drawStyle = Fill)
                    canvas.restore()
                }
            }
        } else if (stroke != null) {
            drawText(layout, color = color, topLeft = Offset.Zero, alpha = alpha, drawStyle = run.outlineFor(i, stroke))
        } else {
            val body = run.body(i)
            val faded = alpha * color.alpha
            if (body != null && faded < .995f && run.fontPx >= 24f * density) {
                // Fill and thickening stroke overlap: drawn half transparent one over the other, a large glyph came out two-tone
                // (a dark core in a pale rim) while it faded. Composed opaque in a layer, it fades as one. Small text (the HUD,
                // dimmed for whole beats) skips the layer: there the two tones do not show and the layers would cost every frame.
                drawIntoCanvas { canvas ->
                    FadePaint.alpha = faded
                    val pad = body.width + .3f * run.fontPx
                    canvas.saveLayer(androidx.compose.ui.geometry.Rect(-pad, -pad, layout.size.width + pad, layout.size.height + pad), FadePaint)
                    val solid = color.copy(alpha = 1f)
                    drawText(layout, color = solid, topLeft = Offset.Zero, drawStyle = Fill)
                    drawText(layout, color = solid, topLeft = Offset.Zero, drawStyle = body)
                    canvas.restore()
                }
            } else {
                // Always pass the style: a text paint keeps the last one it was given, so a fill after a stroke would stay stroked.
                drawText(layout, color = color, topLeft = Offset.Zero, alpha = alpha, drawStyle = Fill)
                if (body != null) drawText(layout, color = color, topLeft = Offset.Zero, alpha = alpha, drawStyle = body)
            }
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

/**
 * [run] typed in letter by letter from [start], [perChar] ms apart (each letter fades in over 40 ms), with a pink block cursor
 * after the last letter: solid while it types, then blinking for [hold] ms and gone. [reduced] shows the whole text at once.
 */
internal fun DrawScope.drawTyped(
    run: GlyphRun, left: Float, top: Float, color: Color, alpha: Float, ms: Float, start: Float, perChar: Float,
    hold: Float = 360f, reduced: Boolean = false, cursor: Color = Sakura.Primary,
) {
    if (alpha <= 0f || run.size == 0) return
    if (reduced) { drawRun(run, left, top, color, alpha); return }
    val t = ms - start
    if (t < 0f) return
    var shown = 0
    for (i in 0 until run.size) {
        val a = ((t - perChar * i) / 40f).coerceIn(0f, 1f)
        if (a <= 0f) break
        shown = i + 1
        drawGlyph(run, i, Offset(left + run.x[i], top), color, alpha * a)
    }
    val typed = perChar * run.size
    if (t > typed + hold) return
    // Solid while typing; after it, on for 160 ms of every 320.
    if (t > typed && ((t - typed) % 320f) > 160f) return
    val x = if (shown == 0) left else left + run.x[shown - 1] + run.advance[shown - 1] + .12f * run.fontPx
    val h = .78f * run.fontPx
    drawRect(cursor.copy(alpha = cursor.alpha * alpha), Offset(x, top + run.baseline - h), Size(.12f * run.fontPx, h))
}

/** A sticker outline stroke for [run]: white, round joins, [em] of the font size wide beyond the (thickened) glyph body. */
internal fun stickerStroke(run: GlyphRun, em: Float = .11f) = Stroke(width = run.fontPx * em + run.thickPx, join = StrokeJoin.Round)
