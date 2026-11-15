package app.dshmobile.ui.fog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.util.lerp
import app.dshmobile.ui.theme.Sakura
import kotlin.math.max
import kotlin.math.min

/** The words a run shows, already resolved (defaults filled in), so the scene never reads resources. */
internal class IntroWords(
    val permission: String,
    val checking: String,
    val passed: String,
    val permissionSub: String,
    val checkingSub: String,
    val passedSub: String,
    /** The welcome words without punctuation, e.g. 歡迎回來. */
    val words: String,
    val wordsAreDefault: Boolean,
    val name: String,
    val number: String,
    val level: String,
    val skip: String,
    val hudSub: String,
    val date: String,
    val time: String,
)

/** Latin design text: part of the look, not translated. */
internal object IntroLatin {
    const val BRAND = "DSH MOBILE"
    const val AUTHORIZATION = "AUTHORIZATION"
    val CAPTIONS = arrayOf("ACCESS REQUEST", "IDENTITY CHECK", "VERIFIED")
    const val AUTH = "AUTH "
    const val SKIP = "SKIP"
    const val WELCOME_BACK = "Welcome back"
    const val VERIFIED = "VERIFIED"
}

private const val SCRAMBLE = "0123456789ABDEHKMNRSX"

/**
 * A HUD text that decodes itself: each letter or digit flickers through three others of its kind before it locks. A [plain] run
 * (the short, compact intro) only fades in with its final text, so the ID is never wrong on screen for a quarter of the run.
 */
internal class DecodeRun(val run: GlyphRun, private val set: Array<TextLayoutResult>, private val plain: Boolean = false) {
    private val kind = IntArray(run.size) { i ->
        val ch = run.text[i].singleOrNull()
        when { ch == null -> 0; ch.isDigit() -> 1; ch.isLetter() && ch.code < 128 -> 2; else -> 0 }
    }

    /** Draws the text as it decodes from [start] (25 ms per character, 3 × 40 ms of scramble each). */
    fun draw(scope: DrawScope, left: Float, top: Float, ms: Float, start: Float, color: Color, alpha: Float) = with(scope) {
        if (alpha <= 0f) return@with
        if (plain) {
            drawRun(run, left, top, color, alpha * seg(ms, start, 120f))
            return@with
        }
        for (i in 0 until run.size) {
            val t = ms - start - 25f * i
            if (t < 0f) continue
            val at = Offset(left + run.x[i], top)
            if (t >= 120f || kind[i] == 0) { drawGlyph(run, i, at, color, alpha * (if (kind[i] == 0) (t / 120f).coerceIn(0f, 1f) else 1f)); continue }
            val step = (t / 40f).toInt()
            val pick = (ih(i * 3 + step, 7) * (if (kind[i] == 1) 10 else SCRAMBLE.length - 10)).toInt() + (if (kind[i] == 1) 0 else 10)
            val g = set[pick.coerceIn(0, set.size - 1)]
            withTransform({ translate(at.x, at.y) }) { drawText(g, color = color, alpha = alpha * .85f) }
        }
    }
}

/**
 * Every text of the intro measured once for this size, plus where the welcome block sits (which also places the crest).
 */
internal class IntroTexts(private val mm: IntroMeasure, val lay: IntroLayout, val words: IntroWords, compact: Boolean = false) {
    private val dp = lay.dp
    private val s = lay.s

    private fun scramble(style: androidx.compose.ui.text.TextStyle): Array<TextLayoutResult> = Array(SCRAMBLE.length) { mm.one(SCRAMBLE[it].toString(), style) }

    private val c11b = IntroFonts.chakra(11f, true, tnum = true)
    private val c11 = IntroFonts.chakra(11f, false, tnum = true)
    private val c12b = IntroFonts.chakra(12f, true, tnum = true)
    private val set11b = scramble(c11b)
    private val plain = compact

    val brand = DecodeRun(mm.run(IntroLatin.BRAND, c11b, .28f), set11b, plain)
    val date = DecodeRun(mm.run(words.date, c11, .12f), scramble(c11), plain)
    val time = DecodeRun(mm.run(words.time, c11b, .12f), set11b, plain)
    val hud2 = mm.run("${IntroLatin.AUTHORIZATION} · ${words.hudSub}", IntroFonts.chakra(10f, false), .20f)
    val index = Array(3) { mm.run("0${it + 1}", c11b, .10f) }
    val captions = Array(3) { mm.run(IntroLatin.CAPTIONS[it], IntroFonts.chakra(11f, false), .32f) }
    private val heroMax = lay.colWidth
    // 52 / 64 / 72 sp: the lines own the lower third, so they are set large enough to fill it.
    val heroes: Array<GlyphRun> = arrayOf(
        mm.fit(words.permission, IntroFonts.sans(52f, FontWeight.Medium), .04f, 52f, 32f, heroMax),
        mm.fit(words.checking, IntroFonts.sans(64f, FontWeight.Medium), .04f, 64f, 32f, heroMax),
        mm.fit(words.passed, IntroFonts.sans(72f, FontWeight.Medium), .04f, 72f, 32f, heroMax),
    )
    val subs = arrayOf(words.permissionSub, words.checkingSub, words.passedSub).map { mm.run(it, IntroFonts.sans(13f, FontWeight.Normal), .08f) }.toTypedArray()
    val authLabel = mm.run(IntroLatin.AUTH, c11b, .12f)
    val digits = Array(10) { mm.one(it.toString(), c11b) }
    val digitW = digits[0].let { it.getLineRight(0) - it.getLineLeft(0) } + .12f * 11f * s * mm.pxPerSp
    val percent = mm.one("%", c11b)
    val hudId = DecodeRun(mm.run("${words.number} · ${words.level}", c12b, .14f), scramble(c12b), plain)
    val skipCjk = mm.run(words.skip, IntroFonts.sans(12f, FontWeight.Medium), 0f)
    val skipLatin = mm.run(IntroLatin.SKIP, IntroFonts.chakra(11f, false), .2f)

    /** The data ring's text, one copy; the ring repeats it to fill the circle. */
    val ring = mm.run(
        "${IntroLatin.BRAND} · ${IntroLatin.AUTHORIZATION} · ${words.number} · ${words.level} · ${words.date} · ${words.name.ifBlank { "GUEST" }} · ".uppercase(),
        IntroFonts.chakra(8f, false), 0f,
    )

    /** The monogram's first grapheme, or null for the sakura mon (no name, or an emoji first). */
    val monogram: GlyphRun? = graphemes(words.name.trim()).firstOrNull()?.takeIf { !isEmoji(it) }?.let {
        mm.run(if (isLatin(it)) it.uppercase() else it, IntroFonts.sans(52f, FontWeight.Medium), 0f)
    }

    // ---- the welcome block ----
    val hasName = words.name.isNotBlank()
    val verified = mm.run(IntroLatin.VERIFIED, IntroFonts.chakra(10f, true), .32f)
    val welcomeBack: GlyphRun? = if (words.wordsAreDefault) mm.run(IntroLatin.WELCOME_BACK, IntroFonts.serifItalic(20f), .06f) else null
    /** The words row: the comma only leads into the serif line; on its own the row ends without one (no dangling 「，」). */
    val wordsRun: GlyphRun? = if (hasName) {
        val bare = words.words.trimEnd(' ', '，', ',', '、', '。', '.', '!', '！')
        mm.fit(if (welcomeBack != null) "$bare，" else bare, IntroFonts.sans(28f, FontWeight.Medium), .04f, 28f, 18f, lay.colWidth * .7f)
    } else null
    val nameLines: List<GlyphRun>
    val idNo = mm.run(words.number, c12b, .16f)
    val idLv = mm.run(words.level, c12b, .16f)

    init {
        val text = if (hasName) words.name.trim() else words.words
        val latin = isLatin(text)
        val track = if (latin) -.01f else .02f
        val cap = when { lay.split -> 56f; lay.small -> 72f; else -> 88f }
        val start = if (hasName) cap else min(cap, 72f)
        val style = IntroFonts.sans(start, FontWeight.Bold)
        val maxW = lay.colWidth
        val one = mm.fit(text, style, track, start, 44f, maxW)
        nameLines = if (one.width <= maxW) listOf(one) else {
            // Two balanced lines at the floor size; an ellipsis when even that does not fit.
            val g = graphemes(text)
            val half = (g.size + 1) / 2
            val first = mm.run(g.take(half).joinToString(""), style, track, 44f)
            var rest = g.drop(half)
            var second = mm.run(rest.joinToString(""), style, track, 44f)
            while (second.width > maxW && rest.size > 1) {
                rest = rest.dropLast(1)
                second = mm.run(rest.joinToString("") + "…", style, track, 44f)
            }
            listOf(first, second)
        }
    }

    val nameFontPx = nameLines[0].fontPx
    /** Two lines get more air, so their white outlines do not touch. */
    val nameLh = if (nameLines.size > 1) 1.18f else 1.08f
    val nameHeight = ((nameLines.size - 1) * nameLh + 1.08f) * nameFontPx
    val nameStroke = stickerStroke(nameLines[0])
    val nameInline = Stroke(1.6f * dp, join = StrokeJoin.Round)
    val nameWidth = nameLines.maxOf { it.width }
    val idTop: Float
    val dividerY: Float
    val nameTop: Float
    val wordsBaseline: Float
    val eyebrowBaseline: Float
    val eyebrowTop: Float
    private val eyebrowPx = 13f * s * mm.pxPerSp

    init {
        val bottom = lay.welcomeBottom
        idTop = bottom - idNo.height
        dividerY = idTop - 10f * dp
        val nameBottom = dividerY - 12f * dp
        nameTop = nameBottom - nameHeight
        val wordsPx = 28f * s * mm.pxPerSp
        val hasRow = wordsRun != null || welcomeBack != null
        val wordsTop = if (hasRow) nameTop - 4f * dp - 1.25f * wordsPx else nameTop
        wordsBaseline = wordsTop + .95f * wordsPx
        eyebrowTop = wordsTop - 12f * dp - 1.3f * eyebrowPx
        eyebrowBaseline = eyebrowTop + 1.0f * eyebrowPx
        // The crest stays where the sigil stood; it shrinks (or, with no room at all, is left out) so its halo and orbit keep
        // clear of the eyebrow.
        if (!lay.landscape) {
            // On a short screen the crest rises just enough to clear the block, never into the top HUD.
            val halo = 31f * dp
            val lowest = eyebrowTop - 14f * dp - halo - lay.crestR
            val highest = lay.hud1Top + 30f * dp + halo + lay.crestR
            if (lowest < lay.c.y) {
                val y = max(lowest, highest)
                lay.crestR = min(lay.crestR, eyebrowTop - 14f * dp - halo - y)
                lay.crestC = Offset(lay.c.x, y)
            }
            if (lay.crestR < 36f * dp) lay.crest = false
        }
    }

    fun nameBaseline(line: Int): Float = nameTop + line * nameLh * nameFontPx + .92f * nameFontPx

    /** The eyebrow group, centred: [✓ 12][6][核驗通過 shrunk][8][VERIFIED]. */
    val eyebrowScale = eyebrowPx / heroes[2].fontPx
    private val eyebrowWidth = 12f * dp + 6f * dp + heroes[2].width * eyebrowScale + 8f * dp + verified.width
    val eyebrowLeft = lay.welcomeCenterX - eyebrowWidth / 2f
    val eyebrowTextLeft = eyebrowLeft + 18f * dp
    val verifiedLeft = eyebrowTextLeft + heroes[2].width * eyebrowScale + 8f * dp

    /** The check mark, drawn on: three points inside a 12 dp box at the eyebrow's left. */
    val check = Path().apply {
        val x = eyebrowLeft
        val y = eyebrowBaseline - 9f * dp
        moveTo(x + 1f * dp, y + 5f * dp); lineTo(x + 4.5f * dp, y + 8.5f * dp); lineTo(x + 11f * dp, y + 1.5f * dp)
    }
    val checkMeasure = PathMeasure().apply { setPath(check, false) }
    val checkStroke = Stroke(1.75f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val scratch = Path()

    /** The hero lines' baselines (1.12 em line box) and their slot clip. */
    fun heroBaseline(i: Int): Float = lay.heroTop + .94f * heroes[i].fontPx
    fun heroBottom(i: Int): Float = lay.heroTop + 1.12f * heroes[i].fontPx
    fun subTop(i: Int): Float = heroBottom(i) + 10f * dp
}

/** The paint for the glints' offscreen layer (SrcAtop needs the glyphs alone in a layer). */
private val LayerPaint = Paint()
/** The name shadow's layer: drawn opaque inside, faded as one. */
private val ShadowPaint = Paint()

private fun DrawScope.glint(bounds: Rect, k: Float, body: DrawScope.() -> Unit) {
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

/** L11 (system half and the morph): the HUD, the three beat blocks and the 核驗通過 line becoming the welcome's eyebrow. */
internal fun DrawScope.drawHudAndBeats(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    // HUD alpha: dims in the inhale; after the hit the brand and date stay at .75 and the rest goes.
    val inh = seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn)
    val dim = if (ms < m.hit) lerp(1f, .55f, inh) else lerp(.55f, .75f, seg(ms, m.hit + 40f * p, 220f))
    val gone = if (ms < m.hit) lerp(1f, .55f, inh) else .55f * (1f - seg(ms, m.hit + 40f * p, 220f))
    val ink = Sakura.Text
    t.brand.draw(this, L.m, L.hud1Top, ms, 120f, ink, dim)
    val timeLeft = L.w - L.m - t.time.run.width
    t.time.draw(this, timeLeft, L.hud1Top, ms, 120f + 25f * 10, ink, dim)
    t.date.draw(this, timeLeft - 10f * dp - t.date.run.width, L.hud1Top, ms, 120f, ink, dim)
    if (L.hud2 && !m.compact && gone > 0f) drawRun(t.hud2, L.m, L.hud2Top, ink, .80f * gone * seg(ms, 200f, 200f))
    if (gone > 0f) t.hudId.draw(this, L.m, L.hudBottomTop, ms, m.a + 200f * p, ink, gone)
    // AUTH 000 %, counting with the progress.
    if (gone > 0f && ms >= m.a) {
        val pct = (progress(sc, ms) * 100f).toInt().coerceIn(0, 100)
        val a = gone * seg(ms, m.a, 160f)
        val right = L.w - 20f * dp
        val numW = 3 * t.digitW
        val total = t.authLabel.width + .12f * t.authLabel.fontPx + numW + t.percent.size.width
        var x = right - total
        val top = L.progressY - 6f * dp - t.authLabel.height
        drawRun(t.authLabel, x, top, ink, a)
        x += t.authLabel.width + .12f * t.authLabel.fontPx
        val ds = intArrayOf(pct / 100, (pct / 10) % 10, pct % 10)
        for (d in ds) { withTransform({ translate(x, top) }) { drawText(t.digits[d], color = ink, alpha = a) }; x += t.digitW }
        withTransform({ translate(x, top) }) { drawText(t.percent, color = ink, alpha = a) }
    }
    // The skip label (when skippable): 跳過 SKIP and two drawn chevrons.
    if (sc.skippable) {
        val a = seg(ms, m.a + 200f * p, 200f * p).let { if (sc.reduced) 1f else it }
        val chevW = 2 * 5f * dp + 2f * dp
        val right = L.w - L.m
        var x = right - chevW - 6f * dp - t.skipLatin.width - 6f * dp - t.skipCjk.width
        val top = L.hudBottomTop
        drawRun(t.skipCjk, x, top, ink, a)
        x += t.skipCjk.width + 6f * dp
        val latinTop = top + (t.skipCjk.baseline - t.skipLatin.baseline)
        drawRun(t.skipLatin, x, latinTop, ink, a)
        x += t.skipLatin.width + 6f * dp
        val cy = top + t.skipCjk.baseline - 4.5f * dp
        for (j in 0 until 2) {
            val cx = x + j * 7f * dp
            drawLine(Sakura.Primary.copy(alpha = a), Offset(cx, cy - 4f * dp), Offset(cx + 5f * dp, cy), 1.5f * dp, StrokeCap.Round)
            drawLine(Sakura.Primary.copy(alpha = a), Offset(cx + 5f * dp, cy), Offset(cx, cy + 4f * dp), 1.5f * dp, StrokeCap.Round)
        }
    }
    if (sc.reduced) { drawEyebrow(sc, ms); return }
    // Beat 1 (and, when compact, the block that cross-fades into beat 2 and stays until c).
    val b1Exit = if (m.compact) m.c else m.b
    val b1Dur = if (m.compact) 160f * p else 180f * p
    drawBeat(sc, 0, ms, m.a, 0f, b1Exit, b1Dur, compactSwap = m.compact)
    if (!m.compact) drawBeat(sc, 1, ms, m.b, 60f * p, m.c, 160f * p, compactSwap = false)
    // Beat 3: the slam, then the morph into the eyebrow.
    if (ms >= m.hit) drawVerified(sc, ms)
}

/**
 * One beat block: caption (index, dash, tracked label), the hero rising out of its slot, and the sub line. With [compactSwap]
 * (the short intro) the whole block, hero included, cross-fades to beat 2 at b, so the caption never says 身份確認's
 * IDENTITY CHECK under 需要訪問許可.
 */
private fun DrawScope.drawBeat(sc: IntroScene, i: Int, ms: Float, start: Float, heroDelay: Float, exit: Float, exitDur: Float, compactSwap: Boolean) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    if (ms < start) return
    val out = seg(ms, exit, exitDur, IntroEase.EmphAccel)
    if (out >= 1f) return
    val hero = t.heroes[i]
    val outY = -.25f * hero.fontPx * out
    val outA = 1f - out
    // The caption (beat 1's swaps to beat 2's in compact mode).
    val swap = if (compactSwap) seg(ms, m.b, 120f * p) else 0f
    drawCaption(sc, if (swap >= .5f) 1 else i, ms, start + 40f * p, outA * (if (swap > 0f && swap < 1f) kotlin.math.abs(1f - 2f * swap) else 1f), outY)
    // The hero, glyph by glyph out of the slot.
    val base = t.heroBaseline(i)
    clipRect(top = L.heroTop - 8f * dp, bottom = t.heroBottom(if (compactSwap) 1 else i) + 4f * dp) {
        if (swap < 1f) for (g in 0 until hero.size) {
            val raw = ((ms - start - heroDelay - 28f * p * g) / (360f * p)).coerceIn(0f, 1f)
            if (raw <= 0f) continue
            val e = IntroEase.ExpoOut.transform(raw)
            val y = base - hero.baseline + .55f * hero.fontPx * (1f - e) + outY
            drawGlyph(hero, g, Offset(L.colLeft + hero.x[g], y), Sakura.Text, (raw / .4f).coerceAtMost(1f) * outA * (1f - swap))
        }
        if (swap > 0f) {
            val h2 = t.heroes[1]
            drawRun(h2, L.colLeft, t.heroBaseline(1) - h2.baseline + outY, Sakura.Text, swap * outA)
        }
    }
    val subIndex = if (swap >= .5f) 1 else i
    drawSub(sc, subIndex, ms, start + 200f * p, outA * (if (swap > 0f && swap < 1f) kotlin.math.abs(1f - 2f * swap) else 1f), outY, t.subTop(i))
}

private fun DrawScope.drawCaption(sc: IntroScene, i: Int, ms: Float, start: Float, alpha: Float, dy: Float, trackFrom: Float = .80f) {
    val t = sc.texts
    val L = sc.lay
    val dp = L.dp
    if (ms < start || alpha <= 0f) return
    val top = L.captionTop + dy
    val idx = t.index[i]
    drawRun(idx, L.colLeft, top, Sakura.Text, alpha * seg(ms, start, 120f))
    val dashX = L.colLeft + idx.width + 8f * dp
    val cy = top + idx.baseline - .35f * idx.fontPx
    val dash = 14f * dp * IntroEase.ExpoOut.transform(seg(ms, start, 200f))
    if (dash > 0f) drawLine(Sakura.Primary.copy(alpha = alpha), Offset(dashX, cy), Offset(dashX + dash, cy), 1.5f * dp, StrokeCap.Round)
    val cap = t.captions[i]
    val k = seg(ms, start, 420f, IntroEase.QuintOut)
    val spread = (trackFrom - .32f) * cap.fontPx * (1f - k)
    drawRun(cap, dashX + 14f * dp + 8f * dp, top, Sakura.TextSecondary, alpha * seg(ms, start, 160f), spread)
}

private fun DrawScope.drawSub(sc: IntroScene, i: Int, ms: Float, start: Float, alpha: Float, dy: Float, top0: Float) {
    val t = sc.texts
    val L = sc.lay
    val dp = L.dp
    if (ms < start || alpha <= 0f) return
    val k = seg(ms, start, 300f, IntroEase.ExpoOut)
    val top = top0 + 6f * dp * (1f - k) + dy
    val a = alpha * k
    val sub = t.subs[i]
    sc.drawSparkle(this, Offset(L.colLeft + 3.5f * dp, top + sub.baseline - .35f * sub.fontPx), 3.5f * dp, Sakura.Primary, a)
    drawRun(sub, L.colLeft + 7f * dp + 6f * dp, top, Sakura.TextSecondary, a)
}

/** 核驗通過: the slam, its caption and sub, the glint, then the line shrinking into the welcome's eyebrow. */
private fun DrawScope.drawVerified(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val capOut = 1f - seg(ms, m.d, 160f)
    drawCaption(sc, 2, ms, m.hit + 40f * p, capOut, 0f, trackFrom = 1f)
    drawSub(sc, 2, ms, m.hit + 160f * p, capOut, 0f, t.subTop(2))
    drawEyebrow(sc, ms)
}

/** The 核驗通過 line itself: slammed in at the hit, and from [IntroMarks.d] the same layout scaled into the eyebrow. */
private fun DrawScope.drawEyebrow(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    val hero = t.heroes[2]
    val u = seg(ms, m.hit, 280f * p)
    val a = if (sc.reduced) 1f else ((ms - m.hit) / 70f).coerceIn(0f, 1f)
    val k = if (sc.reduced) 1f else seg(ms, m.d, 420f * p, IntroEase.EmphDecel)
    // The slam starts large but never past the screen's right edge.
    val fit = max(1f, (L.w - 10f * dp - L.colLeft) / hero.width)
    val scale = lerp(min(slam(u), fit), t.eyebrowScale, k)
    val bx = lerp(L.colLeft, t.eyebrowTextLeft, k)
    val by = lerp(t.heroBaseline(2), t.eyebrowBaseline, k)
    val color = lerpColor(Sakura.Text, if (sc.backdrop) Sakura.Text else Sakura.TextSecondary, k)
    val glintK = if (!m.compact && !sc.reduced && m.d - m.hit - 300f * p >= 300f) seg(ms, m.hit + 300f * p, 420f * p, IntroEase.CubicInOut) else 0f
    val body: DrawScope.() -> Unit = {
        withTransform({ translate(bx, by); scale(scale, scale, Offset.Zero) }) {
            for (g in 0 until hero.size) drawGlyph(hero, g, Offset(hero.x[g], -hero.baseline), color, a)
        }
    }
    if (glintK > 0f && glintK < 1f) {
        val r = Rect(bx, by - hero.baseline * scale, bx + hero.width * scale, by + (hero.height - hero.baseline) * scale)
        glint(r, glintK, body)
    } else body()
    // The check and VERIFIED join the eyebrow once the line has shrunk.
    val ck = if (sc.reduced) 1f else seg(ms, m.d + 420f * p, 200f * p, IntroEase.CubicOut)
    if (ck > 0f) {
        t.scratch.reset()
        t.checkMeasure.getSegment(0f, t.checkMeasure.length * ck, t.scratch, true)
        drawPath(t.scratch, Sakura.Primary, style = t.checkStroke)
    }
    val vk = if (sc.reduced) 1f else seg(ms, m.d + 420f * p, 220f * p, IntroEase.QuintOut)
    if (vk > 0f) {
        val v = t.verified
        val top = t.eyebrowBaseline - v.baseline
        drawRun(v, t.verifiedLeft, top, if (sc.backdrop) Sakura.Text else Sakura.TextSecondary, vk, (.8f - .32f) * v.fontPx * (1f - vk))
    }
}

/** L11 (welcome): the glow behind the name, the words row, the divider and the ID row. The name has its own layer. */
internal fun DrawScope.drawWelcome(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    val d = m.d
    if (ms < d && !sc.reduced) return
    val cx = L.welcomeCenterX
    val label = if (sc.backdrop) Sakura.Text else Sakura.TextSecondary
    // The name glow (fainter over the user's picture, which it would otherwise bleach).
    val ga = (if (sc.backdrop) .20f else .35f) * (if (sc.reduced) 1f else seg(ms, d + 40f * p, 400f))
    if (ga > 0f) {
        val r = .7f * t.nameWidth.coerceAtLeast(120f * dp)
        val cy = t.nameTop + t.nameHeight / 2f
        withTransform({ translate(cx, cy); scale(r, r * .6f, Offset.Zero) }) { drawCircle(sc.unitBloom, 1f, Offset.Zero, alpha = ga) }
    }
    // Words row: 歡迎回來， (rising glyph by glyph) and the serif Welcome back.
    val wr = t.wordsRun
    val wb = t.welcomeBack
    val gap = if (wr != null && wb != null) 10f * dp else 0f
    val rowW = (wr?.width ?: 0f) + gap + (wb?.width ?: 0f)
    var x = cx - rowW / 2f
    if (wr != null) {
        for (g in 0 until wr.size) {
            val raw = if (sc.reduced) 1f else ((ms - d - 100f * p - 25f * p * g) / (340f * p)).coerceIn(0f, 1f)
            if (raw <= 0f) continue
            val e = IntroEase.ExpoOut.transform(raw)
            drawGlyph(wr, g, Offset(x + wr.x[g], t.wordsBaseline - wr.baseline + .5f * wr.fontPx * (1f - e)), Sakura.Text, (raw / .4f).coerceAtMost(1f))
        }
        x += wr.width + gap
    }
    if (wb != null) {
        val k = if (sc.reduced) 1f else seg(ms, d + 180f * p, 460f * p, IntroEase.QuintOut)
        val a = if (sc.reduced) 1f else seg(ms, d + 180f * p, 180f)
        // Tracks in from .25 em, anchored at its left edge, and never spreads past the gutter (at .6 em it ran to the edge).
        val room = ((L.w - L.g - (x + wb.width)) / (wb.size - 1).coerceAtLeast(1)).coerceAtLeast(0f)
        val spread = min((.25f - .06f) * wb.fontPx * (1f - k), room)
        if (a > 0f) drawRun(wb, x, t.wordsBaseline - wb.baseline, label, a, spread)
    }
    // Divider: two pink hairlines out from a diamond.
    val dk = if (sc.reduced) 1f else seg(ms, d + 300f * p, 360f * p, IntroEase.ExpoOut)
    if (dk > 0f) {
        val reach = (t.nameWidth / 2f + 24f * dp).coerceIn(80f * dp, max(80f * dp, L.wc / 2f - L.g))
        val y = t.dividerY
        val from = 10f * dp
        val to = from + (reach - from) * dk
        drawLine(Sakura.Primary.copy(alpha = .9f), Offset(cx - from, y), Offset(cx - to, y), 1f * dp)
        drawLine(Sakura.Primary.copy(alpha = .9f), Offset(cx + from, y), Offset(cx + to, y), 1f * dp)
        val pop = if (sc.reduced) 1f else IntroEase.BackOut.transform(seg(ms, d + 300f * p, 240f * p))
        if (pop > 0f) {
            withTransform({ translate(cx, y); rotate(45f, Offset.Zero); scale(pop, pop, Offset.Zero) }) {
                val half = 3.5f * dp / 1.414f
                drawRect(Sakura.Primary, Offset(-half, -half), Size(2 * half, 2 * half))
            }
            drawCircle(Sakura.White, .75f * dp * pop, Offset(cx, y))
        }
    }
    // ID row: No.0001 ◆ Lv.1.
    val ik = if (sc.reduced) 1f else seg(ms, d + 380f * p, 300f * p, IntroEase.ExpoOut)
    if (ik > 0f) {
        val no = t.idNo
        val lv = t.idLv
        val dia = 5f * dp
        val sp = 8f * dp
        val w = no.width + sp + dia + sp + lv.width
        val left = cx - w / 2f
        val top = t.idTop + 6f * dp * (1f - ik)
        drawRun(no, left, top, Sakura.Text, ik)
        val dy = top + no.baseline - .35f * no.fontPx
        withTransform({ translate(left + no.width + sp + dia / 2f, dy); rotate(45f, Offset.Zero) }) {
            val half = dia / 2f / 1.414f
            drawRect(Sakura.Primary.copy(alpha = ik), Offset(-half, -half), Size(2 * half, 2 * half))
        }
        drawRun(lv, left + no.width + sp + dia + sp, top, Sakura.Text, ik)
    }
}

/** The name: the payoff, 88 sp, a sticker (pink hard shadow, white outline, ink fill), rising glyph by glyph, with its glint. */
internal fun DrawScope.drawName(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val d = m.d
    if (ms < d + 40f * p && !sc.reduced) return
    val cjk = t.nameLines.none { line -> line.text.all { isLatin(it) } }
    val stagger = (if (m.compact) 24f else if (cjk) 40f else 20f) * p
    val total = t.nameLines.sumOf { it.size }
    val step = if (total > 1) min(stagger, 240f * p / (total - 1)) else 0f
    val stroke = t.nameStroke
    val shadowDx = .03f * t.nameFontPx
    val shadowDy = .07f * t.nameFontPx
    val block = Rect(L.welcomeCenterX - t.nameWidth / 2f, t.nameTop, L.welcomeCenterX + t.nameWidth / 2f, t.nameTop + t.nameHeight)
    val glyphs: DrawScope.(pass: Int, dx: Float, dy: Float) -> Unit = { pass, dx, dy ->
        var n = 0
        for ((li, line) in t.nameLines.withIndex()) {
            val left = L.welcomeCenterX - line.width / 2f
            val base = t.nameBaseline(li)
            for (g in 0 until line.size) {
                val raw = if (sc.reduced) 1f else ((ms - d - 40f * p - step * n) / (420f * p)).coerceIn(0f, 1f)
                n++
                if (raw <= 0f) continue
                val e = IntroEase.ExpoOut.transform(raw)
                val a = (raw / .45f).coerceAtMost(1f)
                val at = Offset(left + line.x[g] + dx, base - line.baseline + .35f * line.fontPx * (1f - e) + dy)
                val sc2 = lerp(1.06f, 1f, e)
                when (pass) {
                    0 -> {
                        drawGlyph(line, g, at, Sakura.Primary, a, sc2, stroke = stroke)
                        drawGlyph(line, g, at, Sakura.Primary, a, sc2, shadow = true)
                    }
                    1 -> drawGlyph(line, g, at, Sakura.White, a, sc2, stroke = stroke)
                    else -> {
                        // A thin blush inline between the ink and the white: ink anti-aliased straight onto white made a grey
                        // fringe (#E7E3E3) round every stroke.
                        drawGlyph(line, g, at, Sakura.Background, a, sc2, stroke = t.nameInline)
                        drawGlyph(line, g, at, Sakura.Text, a, sc2)
                    }
                }
            }
        }
    }
    val body: DrawScope.() -> Unit = {
        // The pink hard shadow is the whole sticker's silhouette (outline and fill), offset down-right, in one layer so the
        // overlaps stay one even pink: offset by the fill alone, the white outline covered it completely.
        if (IntroConst.NAME_STICKER) drawIntoCanvas { canvas ->
            ShadowPaint.alpha = .85f
            canvas.saveLayer(block.inflate(stroke.width + shadowDy), ShadowPaint)
            glyphs(0, shadowDx, shadowDy)
            canvas.restore()
        }
        glyphs(1, 0f, 0f)
        glyphs(2, 0f, 0f)
    }
    // Glints: first 600 ms in (when there is time for it), then every 2.4 s while the welcome holds.
    var gk = 0f
    if (!sc.reduced) {
        var start = d + 600f * p
        if (m.e - start >= 300f) {
            while (start + 2400f <= ms && m.e - (start + 2400f) > 700f) start += 2400f
            gk = seg(ms, start, 460f, IntroEase.CubicInOut)
        }
    }
    if (gk > 0f && gk < 1f) glint(block, gk, body) else body()
}
