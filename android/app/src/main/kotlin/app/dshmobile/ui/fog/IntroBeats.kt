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

/** The hero lines' tracking (em). */
private const val HERO_TRACK = .07f

/** A typed label's letters, this many ms apart at pace 1. */
internal const val TYPE_MS = 22f

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

    private val c11b = IntroFonts.latin(11f, FontWeight.Bold)
    private val c11 = IntroFonts.latin(11f, FontWeight.SemiBold)
    private val c12b = IntroFonts.latin(12f, FontWeight.Bold)
    private val set11b = scramble(c11b)
    private val plain = compact

    val brand = DecodeRun(mm.run(IntroLatin.BRAND, c11b, .28f), set11b, plain)
    val date = DecodeRun(mm.run(words.date, c11, .12f), scramble(c11), plain)
    val time = DecodeRun(mm.run(words.time, c11b, .12f), set11b, plain)
    // Quicksand's capitals run wider than Chakra Petch's: AUTHORIZATION at .17 em keeps the old width.
    val hud2 = mm.mixed("${IntroLatin.AUTHORIZATION} · ${words.hudSub}", IntroFonts.latin(10f, FontWeight.SemiBold), IntroFonts.hand(10f), .17f, handScale = 1.4f)
    val index = Array(3) { mm.run("0${it + 1}", c11b, .10f) }
    val captions = Array(3) { mm.run(IntroLatin.CAPTIONS[it], c11, .30f) }
    private val heroMax = lay.colWidth
    // 52 / 64 / 72 sp: the lines own the lower third, so they are set large enough to fill it. The pen is tracked a little wider
    // than a gothic would be: its thickening stroke takes some of the gap.
    val heroes: Array<GlyphRun> = arrayOf(
        mm.fit(words.permission, IntroFonts.hand(52f), HERO_TRACK, 52f, 32f, heroMax),
        mm.fit(words.checking, IntroFonts.hand(64f), HERO_TRACK, 64f, 32f, heroMax),
        mm.fit(words.passed, IntroFonts.hand(72f), HERO_TRACK, 72f, 32f, heroMax),
    )
    val subs = arrayOf(words.permissionSub, words.checkingSub, words.passedSub).map { mm.run(it, IntroFonts.hand(13f, small = true), .06f) }.toTypedArray()
    val authLabel = mm.run(IntroLatin.AUTH, c11b, .12f)
    /** The settled 100 %, in proportional figures: in a fixed cell the narrow 1 left a hole ("AUTH 1 00%"). */
    val authDone = mm.run("100%", c11b, .12f)
    val digits = Array(10) { mm.one(it.toString(), c11b) }
    val digitW = (0 until 10).maxOf { d -> digits[d].let { it.getLineRight(0) - it.getLineLeft(0) } } + .12f * 11f * s * mm.pxPerSp
    val percent = mm.one("%", c11b)
    // The number and level are the user's own words (may be Chinese): mixed, so a 見習生 is in the pen, not the system font.
    val hudId = DecodeRun(mm.mixed("${words.number} · ${words.level}", c12b, IntroFonts.hand(12f), .14f, handScale = 1.3f), scramble(c12b), plain)
    val skipCjk = mm.run(words.skip, IntroFonts.hand(12f, small = true), .04f)
    val skipLatin = mm.run(IntroLatin.SKIP, c11, .2f)

    /** The data ring's text, one copy; the ring repeats it to fill the circle. */
    val ring = mm.mixed(
        "${IntroLatin.BRAND} · ${IntroLatin.AUTHORIZATION} · ${words.number} · ${words.level} · ${words.date} · ${words.name.ifBlank { "GUEST" }} · ".uppercase(),
        IntroFonts.latin(8f, FontWeight.SemiBold), IntroFonts.hand(8f), 0f, handScale = 1.35f,
    )

    /** The monogram's first grapheme, or null for the sakura mon (no name, or an emoji first). */
    val monogram: GlyphRun? = graphemes(words.name.trim()).firstOrNull()?.takeIf { !isEmoji(it) }?.let {
        mm.run(if (isLatin(it)) it.uppercase() else it, IntroFonts.hand(52f), 0f)
    }

    // ---- the welcome block ----
    val hasName = words.name.isNotBlank()
    val verified = mm.run(IntroLatin.VERIFIED, IntroFonts.latin(10f, FontWeight.Bold), .30f)
    val welcomeBack: GlyphRun? = if (words.wordsAreDefault) mm.run(IntroLatin.WELCOME_BACK, IntroFonts.latin(20f, FontWeight.Medium), .04f) else null
    /** The words row: the comma only leads into the Latin line; on its own the row ends without one (no dangling 「，」). */
    val wordsRun: GlyphRun? = if (hasName) {
        val bare = words.words.trimEnd(' ', '，', ',', '、', '。', '.', '!', '！')
        mm.fit(if (welcomeBack != null) "$bare，" else bare, IntroFonts.hand(28f), .06f, 28f, 18f, lay.colWidth * .7f)
    } else null
    /** The name (or, with no name, the welcome words) as the sticker. */
    val name: IntroSticker = run {
        val cap = when { lay.split -> 56f; lay.small -> 72f; else -> 88f }
        IntroSticker(mm, if (hasName) words.name.trim() else words.words, lay.colWidth, if (hasName) cap else min(cap, 72f), 44f, dp)
    }
    val nameLines: List<GlyphRun> get() = name.lines
    val idNo = mm.mixed(words.number, c12b, IntroFonts.hand(12f), .16f, handScale = 1.3f)
    val idLv = mm.mixed(words.level, c12b, IntroFonts.hand(12f), .16f, handScale = 1.3f)

    val nameFontPx = name.fontPx
    val nameLh = name.lh
    val nameHeight = name.height
    val nameWidth = name.width
    val idTop: Float
    val dividerY: Float
    val nameTop: Float
    val wordsBaseline: Float
    val eyebrowBaseline: Float
    val eyebrowTop: Float
    // 16 sp, not 13: the pen's 核驗通過 shrunk to 13 sp read as a smudge.
    private val eyebrowPx = 16f * s * mm.pxPerSp

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
        // The crest floats at 0.30H; where the block reaches up to it, it rises and shrinks (or, with no room at all, is left out)
        // so its halo and orbit keep clear of the eyebrow.
        if (!lay.landscape) {
            // On a short screen the crest rises just enough to clear the block, never into the top HUD.
            val halo = IntroLayout.CREST_HALO * dp
            val lowest = eyebrowTop - 14f * dp - halo - lay.crestR
            val highest = lay.hud1Top + 30f * dp + halo + lay.crestR
            if (lowest < lay.crestC.y) {
                val y = max(lowest, highest)
                lay.crestR = min(lay.crestR, eyebrowTop - 14f * dp - halo - y)
                lay.crestC = Offset(lay.c.x, y)
            }
            if (lay.crestR < 36f * dp) lay.crest = false
        }
    }

    fun nameBaseline(line: Int): Float = name.baseline(nameTop, line)

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
    /** The pink bar under each hero line: 4 dp, from the column's left edge for .35 of the column (each beat's graphic anchor). */
    fun barTop(i: Int): Float = heroBottom(i) + 4f * dp
    val barWidth = max(60f * dp, .35f * lay.wc - lay.g)
    fun subTop(i: Int): Float = heroBottom(i) + 18f * dp

    /** 核驗通過's lighter sticker: a .08 em white outline over a pink drop at (0, .04 em). */
    val verifiedStroke = stickerStroke(heroes[2], .08f)
    /** The hit's offset flash: the white round the aqua copy, and the white inline between the copies and the ink. */
    val splitHalo = Stroke(heroes[2].thickPx + .012f * heroes[2].fontPx, join = StrokeJoin.Round)
    val splitInline = Stroke(heroes[2].thickPx + .024f * heroes[2].fontPx, join = StrokeJoin.Round)

    /**
     * The giant faded beat numerals 01 / 02 / 03 (Quicksand Bold up to 140 sp), right-aligned on the gutter with their
     * baseline 20 dp above the progress line, so the band between the beat block and the progress line is not empty halftone.
     * Sized to the room under the widest hero line; none where that room is too small (short, split and landscape screens).
     */
    val numerals: Array<GlyphRun>? = run {
        if (lay.landscape || lay.split) return@run null
        val baseline = lay.progressY - 20f * dp
        val top = (0 until 3).maxOf { barTop(it) + 4f * dp } + 14f * dp
        // Digits stand .70 em tall in Quicksand.
        val sp = min(140f, (baseline - top) / .70f / (s * mm.pxPerSp))
        if (sp < 72f) null else Array(3) { mm.run("0${it + 1}", IntroFonts.latin(140f, FontWeight.Bold), .02f, sp) }
    }
}

/** 核驗通過's pink drop, in its own layer: drawn opaque inside, faded as one. */
private val HeroShadowPaint = Paint()

/**
 * When 03's caption, sub, bar and giant numeral start their 120 ms fade: by hit + 480 ms, and always early enough that they are
 * gone before the floor circle sweeps down through the beat block (it is held clear of the caption until d, then passes it at
 * once).
 */
internal fun IntroMarks.verifiedOut(): Float = min(hit + 480f * pace, d - 130f * pace)

/** 03's caption and sub show only when they would stay up for 100 ms; at 3 s and below the slam has the beat to itself. */
internal fun IntroMarks.verifiedCaption(): Boolean = verifiedOut() - (hit + 160f * pace) >= 100f * pace

/** L11, the HUD: brand, date and time, the second row, the ID, AUTH % and the skip label (its own layer, first out on exit). */
internal fun DrawScope.drawHud(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    // HUD alpha: dims in the inhale; after the hit the brand and date stay at .75 (full over the user's picture, where .75 fell
    // under 3:1 against busy art) and the rest goes.
    val inh = seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn)
    val dim = if (ms < m.hit) lerp(1f, .55f, inh) else lerp(.55f, if (sc.backdrop) 1f else .75f, seg(ms, m.hit + 40f * p, 220f))
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
        if (pct >= 100) drawRun(t.authDone, right - t.authDone.width, top, ink, a) else {
            val ds = intArrayOf(pct / 100, (pct / 10) % 10, pct % 10)
            for (d in ds) { withTransform({ translate(x, top) }) { drawText(t.digits[d], color = ink, alpha = a) }; x += t.digitW }
            withTransform({ translate(x, top) }) { drawText(t.percent, color = ink, alpha = a) }
        }
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
}

/** L11 (system half and the morph): the three beat blocks and the 核驗通過 line becoming the welcome's eyebrow. */
internal fun DrawScope.drawBeats(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    if (sc.reduced) { drawEyebrow(sc, ms); return }
    t.numerals?.let { drawNumerals(sc, it, ms) }
    // Beat 1 (and, when compact, the block that cross-fades into beat 2 and stays until c).
    val b1Exit = if (m.compact) m.c else m.b
    val b1Dur = if (m.compact) 160f * p else 180f * p
    drawBeat(sc, 0, ms, m.a, 0f, b1Exit, b1Dur, compactSwap = m.compact)
    if (!m.compact) drawBeat(sc, 1, ms, m.b, 60f * p, m.c, 160f * p, compactSwap = false)
    // Beat 3: the slam, then the morph into the eyebrow.
    if (ms >= m.hit) drawVerified(sc, ms)
}

/**
 * The giant faded numeral of the current beat. At each beat mark the old one fades out over 80 ms and only then the new one rises
 * 24 dp out of a slot at its own baseline (just above AUTH): cross-faded in place, 01 and 02 overlapped and read as '001'.
 */
private fun DrawScope.drawNumerals(sc: IntroScene, nums: Array<GlyphRun>, ms: Float) {
    val m = sc.marks
    val L = sc.lay
    val dp = L.dp
    val right = L.colLeft + L.colWidth
    val baseline = L.progressY - 20f * dp
    for (i in 0 until 3) {
        val enter = when (i) { 0 -> m.a; 1 -> m.b + 80f; else -> m.hit + 80f }
        val out = when (i) { 0 -> seg(ms, m.b, 80f); 1 -> seg(ms, m.hit, 80f); else -> seg(ms, m.verifiedOut(), 120f * m.pace) }
        val a = seg(ms, enter, 160f) * (1f - out)
        if (a <= 0f) continue
        val rise = 24f * dp * (1f - IntroEase.ExpoOut.transform(seg(ms, enter, 320f)))
        val run = nums[i]
        clipRect(bottom = baseline + 2f * dp) {
            drawRun(run, right - run.width, baseline - run.baseline + rise, Sakura.RoseBrown, .10f * a)
        }
    }
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
    // In compact mode beat 2's label types afresh from b, where it takes over.
    val capStart = start + 40f * p
    drawCaption(
        sc, if (swap >= .5f) 1 else i, ms, capStart, outA * (if (swap > 0f && swap < 1f) kotlin.math.abs(1f - 2f * swap) else 1f), outY,
        typeFrom = if (swap >= .5f) m.b + 60f * p else capStart + 60f * p,
    )
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
    // The pink bar under the hero, drawing on with it.
    val bk = IntroEase.ExpoOut.transform(seg(ms, start + heroDelay, 300f * p))
    if (bk > 0f) drawRect(Sakura.Primary.copy(alpha = outA), Offset(L.colLeft, lerp(t.barTop(i), t.barTop(1), swap) + outY), Size(t.barWidth * bk, 4f * dp))
    val subIndex = if (swap >= .5f) 1 else i
    drawSub(sc, subIndex, ms, start + 200f * p, outA * (if (swap > 0f && swap < 1f) kotlin.math.abs(1f - 2f * swap) else 1f), outY, lerp(t.subTop(i), t.subTop(1), swap))
}

private fun DrawScope.drawCaption(sc: IntroScene, i: Int, ms: Float, start: Float, alpha: Float, dy: Float, typeFrom: Float = start + 60f) {
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
    // The English label types itself in behind a pink cursor (it used to track in, which read as a slide, not a terminal).
    drawTyped(t.captions[i], dashX + 14f * dp + 8f * dp, top, Sakura.TextSecondary, alpha, ms, typeFrom, TYPE_MS * sc.marks.pace, reduced = sc.reduced)
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

/**
 * 核驗通過: the slam, its bar (and, when there is time, its caption and sub, which come in after the squash so the falling line
 * never covers them), the glint, then the line shrinking into the welcome's eyebrow.
 */
private fun DrawScope.drawVerified(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val capOut = 1f - seg(ms, m.verifiedOut(), 120f * p)
    if (capOut > 0f) {
        if (m.verifiedCaption()) {
            drawCaption(sc, 2, ms, m.hit + 160f * p, capOut, 0f)
            drawSub(sc, 2, ms, m.hit + 160f * p, capOut, 0f, t.subTop(2))
        }
        val bk = IntroEase.ExpoOut.transform(seg(ms, m.hit + 60f * p, 300f * p))
        if (bk > 0f) drawRect(Sakura.Primary.copy(alpha = capOut), Offset(L.colLeft, t.barTop(2)), Size(t.barWidth * bk, 4f * L.dp))
    }
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
    // The slam starts large but never wider than the column (at 1.32 a long line ran into the right gutter).
    val scale = lerp(slamCapped(u, L.colWidth / hero.width), t.eyebrowScale, k)
    val bx = lerp(L.colLeft, t.eyebrowTextLeft, k)
    val by = lerp(t.heroBaseline(2), t.eyebrowBaseline, k)
    val color = lerpColor(Sakura.Text, if (sc.backdrop) Sakura.Text else Sakura.TextSecondary, k)
    val glintK = if (!m.compact && !sc.reduced && m.d - m.hit - 300f * p >= 300f) seg(ms, m.hit + 300f * p, 420f * p, IntroEase.CubicInOut) else 0f
    // A lighter sticker than the name's (so the climax word has a voice of its own), fading out as it becomes the eyebrow.
    val st = a * (1f - k)
    val body: DrawScope.() -> Unit = {
        withTransform({ translate(bx, by); scale(scale, scale, Offset.Zero) }) {
            if (st > 0f) {
                val dy = .04f * hero.fontPx
                drawIntoCanvas { canvas ->
                    HeroShadowPaint.alpha = .5f * st
                    canvas.saveLayer(Rect(-t.verifiedStroke.width, -hero.baseline - t.verifiedStroke.width, hero.width + t.verifiedStroke.width, hero.height - hero.baseline + dy + t.verifiedStroke.width), HeroShadowPaint)
                    for (g in 0 until hero.size) {
                        drawGlyph(hero, g, Offset(hero.x[g], -hero.baseline + dy), Sakura.Primary, 1f, stroke = t.verifiedStroke)
                        drawGlyph(hero, g, Offset(hero.x[g], -hero.baseline + dy), Sakura.Primary, 1f)
                    }
                    canvas.restore()
                }
                for (g in 0 until hero.size) drawGlyph(hero, g, Offset(hero.x[g], -hero.baseline), Sakura.White, st, stroke = t.verifiedStroke)
            }
            // The slam's one pink/aqua offset flash (once per run): a pink copy .028 em to the left and an aqua one to the right,
            // inside the white outline. Aqua must never touch pink or ink (both mix to grey), so the aqua copy stands on its own
            // white silhouette and the ink on a white inline: pink → white → aqua → white → ink.
            val sp = if (sc.reduced) 0f else splitFlash(ms - m.hit)
            if (sp > 0f && st > 0f) {
                val off = .028f * hero.fontPx
                val y0 = -hero.baseline
                for (g in 0 until hero.size) drawGlyph(hero, g, Offset(hero.x[g] - off, y0), Sakura.Primary, sp)
                for (g in 0 until hero.size) {
                    drawGlyph(hero, g, Offset(hero.x[g] + off, y0), Sakura.White, a)
                    drawGlyph(hero, g, Offset(hero.x[g] + off, y0), Sakura.White, a, stroke = t.splitHalo)
                }
                for (g in 0 until hero.size) drawGlyph(hero, g, Offset(hero.x[g] + off, y0), Sakura.Aqua, sp)
                // The inline is a whole white silhouette, not only a rim: a pinhole the pen's thickening left inside a closed
                // counter showed the aqua copy through the ink, as a grey dot.
                for (g in 0 until hero.size) {
                    drawGlyph(hero, g, Offset(hero.x[g], y0), Sakura.White, a)
                    drawGlyph(hero, g, Offset(hero.x[g], y0), Sakura.White, a, stroke = t.splitInline)
                }
            }
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
        // Ink over the user's picture: a pink check vanished into the pink scrim.
        drawPath(t.scratch, if (sc.backdrop) Sakura.Text else Sakura.Primary, style = t.checkStroke)
    }
    val v = t.verified
    drawTyped(v, t.verifiedLeft, t.eyebrowBaseline - v.baseline, if (sc.backdrop) Sakura.Text else Sakura.TextSecondary, 1f, ms, m.d + 420f * p, TYPE_MS * p, reduced = sc.reduced)
}

/** L11 (welcome): the glow behind the name, the words row, the divider and the ID row. The name has its own layer. */
internal fun DrawScope.drawWelcome(sc: IntroScene, ms: Float) {
    val t = sc.texts
    val m = sc.marks
    val p = m.pace
    val L = sc.lay
    val dp = L.dp
    if (ms < m.d && !sc.reduced) return
    // The block follows the name, which waits (a little) for the floor circle to sink clear of it.
    val d = m.d + sc.welcomeDelay
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
    if (wb != null) drawTyped(wb, x, t.wordsBaseline - wb.baseline, label, 1f, ms, d + 180f * p, TYPE_MS * p, reduced = sc.reduced)
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
    val m = sc.marks
    drawSticker(sc.texts.name, sc.lay.welcomeCenterX, sc.texts.nameTop, ms, sc.nameStart, m.pace, m.compact, sc.reduced, m.e)
}
