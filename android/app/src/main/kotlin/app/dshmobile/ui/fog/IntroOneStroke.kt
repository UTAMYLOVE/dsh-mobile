package app.dshmobile.ui.fog

import android.graphics.Typeface
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.util.lerp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// Ideas, not code: the mark drawn in over its own faint ghost, the breathing glow once it is whole and the wide-tracked caption
// with a block cursor come from the simple boot of yannicksong0106/dsh-550c-boot (MIT, (c) 2026 Ziyang Song); the name written
// in along its strokes comes from the handwritten "hello" reveal of tianyuec11-design/dsh-apple-liquid-glass (MIT, (c) 2026 CTY).
// No code, numbers, images or names of either were copied: the crest, the timing and the stroke reveal below are our own.

/**
 * 「一筆描」 (One stroke), the startup look [IntroStyle.ONE_STROKE], the short and quiet one: a pen draws the 櫻紋 crest in one
 * unbroken line (an open ring that curls in and becomes a five-petal sakura) over its own faint ghost; when the line closes the
 * crest flashes once and keeps breathing a soft pink glow, the flower opens onto your picture (or blooms pale pink) and your name
 * is written in stroke by stroke in the intro's handwriting, with 歡迎回來 above it and a small typed DSH MOBILE below.
 *
 * Its moves take 1.9 s in all (everything has moved by 1.53 s at the default 3 s). A longer setting only holds the finished crest
 * and name longer (the run still ends exactly at the setting, which every look shares); a 1.5 s setting tightens the moves.
 */
@Composable
internal fun OneStrokeIntro(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean,
    fx: IntroFx,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val seconds = intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS)
    val times = remember(seconds) { OneStrokeTimes.of(seconds * 1000f) }
    val timing = remember(times) { IntroTiming.of(times.plan, still = times.still, hit = times.hit, announce = times.writeAt) }
    val line = introWelcomeLine(intro)
    val words = intro.welcome.trim().ifEmpty { stringResource(R.string.intro_welcome_default) }
    val name = intro.name.trim()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val context = LocalContext.current
    val coverage = IntroFonts.coverage(context.resources)
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = line) {
        val run = this.run
        val reduced = this.reduced
        val level = this.level
        val dp = density.density
        val lay = remember(this) { OneStrokeLayout(widthPx, heightPx, dp, insetTop, insetBottom) }
        val crest = remember(this) {
            OneStrokeCrest(widthPx, heightPx, lay.c, lay.r, dp, if (fx.sparkles) introCount(level, fx, 9, 6, 0, reduced) else 0)
        }
        val backdrop = remember(this) { IntroBackdrop(this.picture, widthPx, heightPx, align) }
        val drift = remember(this) { IntroDrift(widthPx, heightPx, dp, introCount(level, fx, 9, 6, 3, reduced), fx, salt = 40) }
        val season = remember(this) { IntroSeasonFx(widthPx, heightPx, dp, fx.season, introCount(level, fx, 8, 6, 3, reduced), fx) }
        val text = remember(this, words, name) {
            val hand = runCatching { context.resources.getFont(R.font.intro_hand) }.getOrNull()
            OneStrokeText(IntroMeasure(measurer, dp, density.fontScale, lay.s, coverage), lay, words, name, hand, dp)
        }
        // With the picture (and 頭像 on) the finished flower is a window onto it; otherwise it blooms pale pink.
        val window = backdrop.hasPicture && intro.portrait
        // The exit's sakura iris opens from the crest's own flower.
        run.sceneOrigin = lay.c
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val ms = run.ms
                backdrop.draw(this, backdrop.zoom(ms, times.exit, 1.06f, reduced))
                if (backdrop.hasPicture) {
                    // A blush veil over the picture, like paper: the line reads on it. The flower clears as it opens.
                    val open = if (window) times.open(ms, reduced) else 0f
                    if (open <= 0f) {
                        drawRect(backdrop.sky, alpha = VEIL)
                    } else {
                        drawPath(crest.veil, backdrop.sky, alpha = VEIL)
                        if (open < 1f) drawPath(crest.flower, backdrop.sky, alpha = VEIL * (1f - open))
                    }
                    drawRect(text.band)
                }
            },
        )
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(60f, 160f) }.drawBehind {
                crest.draw(this, run.ms, run.amb, times, level, reduced, window)
            },
        )
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(40f) }.drawBehind {
                text.draw(this, run.ms, times, reduced)
            },
        )
        // The petals (or sparkles) and the season drift in front of everything: behind the name, the ink's soft edge over a
        // snowflake's aqua ring (or an aqua sparkle) read as grey.
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val inK = if (reduced) 1f else seg(run.ms, 0f, 500f)
                drift.draw(this, run.amb, .8f * inK)
                season.draw(this, run.amb, inK)
            },
        )
    }
}

/** The blush veil over the picture: strong enough that the fine line and the ink read on any picture. */
private const val VEIL = .70f

/** The crest's line: a deep sakura, halfway between the pink and the rose brown. */
private val Ink = lerpColor(Sakura.Primary, Sakura.RoseBrown, .5f)

/** The pen's pink halo, radius 1 at the origin (scaled where it is drawn). */
private val PenHalo: Brush = Brush.radialGradient(
    0f to Sakura.Primary.copy(alpha = .55f), .45f to Sakura.Primary.copy(alpha = .22f), 1f to Sakura.Primary.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)

/**
 * One run's beats (ms on the run's clock), a pure function of the 1.5–8 s setting. The moves: the ghost inks in (110), the pen
 * draws the crest (820), the crest glows and the name is written (600), the exit iris (360); one hold between them takes the rest
 * (at least 180). A move never slows down: 8 s only holds longer, and 1.5 s tightens every move by the same [pace].
 */
internal class OneStrokeTimes private constructor(val plan: IntroPlan) {
    val pace: Float = plan.pace
    /** When the pen starts and how long it draws. */
    val drawAt: Float = plan.at(1)
    val drawMs: Float = plan.length(1)
    /** The line closes: the crest flashes (and the haptic tick). */
    val hit: Float = plan.at(2)
    /** The name is written from [writeAt] for [writeMs] (TalkBack hears the welcome then). */
    val writeAt: Float = hit + 90f * pace
    val writeMs: Float = plan.end(2) - writeAt
    val eyebrowAt: Float = hit + 30f * pace
    val captionAt: Float = plan.end(2) - 80f * pace
    /** Everything has moved (the name is whole) by here; the hold follows. */
    val settled: Float = plan.end(2)
    val exit: Float = plan.at(4)
    /** The reduced-motion frame: the finished crest and name, just before the exit. */
    val still: Float = exit - 1f

    /** The pen's progress along the line, 0..1: it lands slowly, glides, and eases into the last petal. */
    fun drawK(ms: Float): Float = seg(ms, drawAt, drawMs, PenEase)

    /** The ghost (the whole line, faint): it inks in first, then goes once the line is closed. */
    fun ghost(ms: Float): Float = seg(ms, 0f, plan.length(0) + 60f * pace) * (1f - seg(ms, hit, 240f * pace))

    /** The flower opening (onto the picture, or into its pale fill) after the line closes. */
    fun open(ms: Float, reduced: Boolean = false): Float = if (reduced) 1f else seg(ms, hit, 420f * pace, IntroEase.CubicOut)

    /** The crest's breathing glow coming in after the flash. */
    fun glowIn(ms: Float, reduced: Boolean = false): Float = if (reduced) 1f else seg(ms, hit, 360f * pace)

    companion object {
        const val INK_MS = 110f
        const val DRAW_MS = 820f
        const val WRITE_MS = 600f
        const val HOLD_MIN = 180f
        const val EXIT_MS = 360f
        /** All the moves together: the look's own length before any hold. */
        const val MOVES_MS = INK_MS + DRAW_MS + WRITE_MS + EXIT_MS

        /** The breathing glow's period (s). */
        const val BREATH_S = 2.6f

        val PenEase: Easing = CubicBezierEasing(.42f, 0f, .28f, 1f)

        fun of(totalMs: Float): OneStrokeTimes = OneStrokeTimes(
            IntroPlan.of(
                totalMs, IntroBeat.move(INK_MS), IntroBeat.move(DRAW_MS), IntroBeat.move(WRITE_MS), IntroBeat.hold(1f, HOLD_MIN),
                IntroBeat.move(EXIT_MS),
            ),
        )
    }
}

/**
 * Where things go: the crest above the words on a phone (or to their left on a landscape screen), [s] the screen factor for
 * type. The crest's radius [r] is its ring's.
 */
internal class OneStrokeLayout(val w: Float, val h: Float, val dp: Float, iT: Float, iB: Float) {
    val s: Float = min(w / dp / 412f, h / dp / 915f).coerceIn(.8f, 1.2f)
    val landscape: Boolean = w > h * 1.05f
    val c: Offset
    val r: Float
    val textX: Float
    val textMaxW: Float
    /** The text block's top on a phone; on a landscape screen the block is centred on the crest instead. */
    val textTop: Float

    init {
        if (landscape) {
            r = min((h - iT - iB) * .34f, w * .19f)
            c = Offset(w * .29f, (iT + h - iB) / 2f)
            textX = w * .67f
            textMaxW = w * .56f - 48f * dp
            textTop = -1f
        } else {
            r = min(w * .32f, h * .17f)
            c = Offset(w / 2f, max(iT + r * 1.1f + 28f * dp, h * .37f))
            textX = w / 2f
            textMaxW = w - 56f * dp
            textTop = c.y + r * 1.04f + 34f * dp * s
        }
    }
}

/** The flower's radius at [deg] (the shared sakura outline's, [sakuraRadius], for any angle): petal 0 up, tips .99, valleys .70. */
private fun flowerRadius(deg: Float): Float {
    val phi = (((deg + 126f) % 72f) + 72f) % 72f - 36f
    return .70f + .30f * cos(Math.toRadians(phi * 2.5).toFloat()) - .12f * max(0f, 1f - abs(phi) / 6f)
}

private fun smooth(u: Float): Float = u * u * (3f - 2f * u)

/**
 * The crest as one unbroken line, built once per size: an open ring (300° of a circle, from 12 o'clock clockwise) that curls in
 * over 123° and runs into the left shoulder of the upper-right petal, then the five-petal sakura outline once round (the shared
 * silhouette at .68 of the ring) and a little past where it began, as a brush lifts. One contour, so one [PathMeasure] draws it.
 *
 * The ink is cut into [PIECES] pieces of a brush-like width (it lands thin, swells round the ring, thins in the curl and the
 * flower, lifts at the end); a piece the pen has passed is a fixed path, so a frame only cuts the one under the pen.
 */
internal class OneStrokeCrest(w: Float, h: Float, val c: Offset, val r: Float, private val dp: Float, sparkles: Int) {
    val path = Path()
    /** The flower outline alone, closed (its fill and the picture's window). */
    val flower = Path()
    /** The screen with the flower cut out (even-odd): the veil round the open window. */
    val veil = Path()
    private val measure = PathMeasure()
    val length: Float
    /** Where the ring ends and the curl ends, as fractions of [length]. */
    val ringEnd: Float
    val curlEnd: Float

    private val pieceStart = FloatArray(PIECES + 1)
    private val pieces = Array(PIECES) { Path() }
    private val pieceStroke: Array<Stroke>

    private val ghostStroke = Stroke(1.3f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val underStroke = Stroke(5.5f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    /** The breathing glow: three widening strokes, ever fainter, read as one soft halo (no blur needed). */
    private val halo = arrayOf(
        Stroke(22f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(14f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(8f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    private val flashWide = Stroke(12f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val flashCore = Stroke(3f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val scratch = Path()
    private val sparkle = Kawaii.sparklePath()

    /** The pale fill of the flower without a picture: white at the heart, blush, pink at the petals' edges. */
    private val fill: Brush
    private val stamenEnds: Array<Offset>
    private val sparkAt: Array<Offset>
    private val sparkF: FloatArray

    init {
        val toRad = (PI / 180.0).toFloat()
        fun at(rho: Float, deg: Float) = Offset(c.x + r * rho * cos(deg * toRad), c.y + r * rho * sin(deg * toRad))
        var started = false
        fun to(p: Offset) { if (started) path.lineTo(p.x, p.y) else { path.moveTo(p.x, p.y); started = true } }
        val join = 333f // the upper-right petal (tip at 342°) just before its notch
        val curl = 123f
        val ringSweep = 300f
        val ringStart = join - curl - ringSweep
        val steps = (ringSweep * 2).toInt()
        for (i in 0..steps) to(at(1f, ringStart + ringSweep * i / steps))
        val rj = FLOWER * flowerRadius(join)
        val curlSteps = (curl * 2).toInt()
        for (i in 1..curlSteps) {
            val u = i / curlSteps.toFloat()
            to(at(1f + (rj - 1f) * smooth(u), join - curl + curl * u))
        }
        val flowerSteps = (360f + OVERSHOOT) * 2f
        for (i in 1..flowerSteps.toInt()) {
            val deg = join + i / 2f
            to(at(FLOWER * flowerRadius(deg), deg))
        }
        for (i in 0 until 720) {
            val p = at(FLOWER * flowerRadius(i / 2f), i / 2f)
            if (i == 0) flower.moveTo(p.x, p.y) else flower.lineTo(p.x, p.y)
        }
        flower.close()
        veil.fillType = PathFillType.EvenOdd
        veil.addRect(Rect(0f, 0f, w, h))
        veil.addPath(flower)

        measure.setPath(path, false)
        length = measure.length
        // The ring is r·300°; the curl ends where the flower begins, the flower's own length (once round and the overshoot)
        // before the end.
        val ringLen = r * ringSweep * toRad
        val flowerLen = PathMeasure().apply { setPath(flower, true) }.length * (360f + OVERSHOOT) / 360f
        ringEnd = (ringLen / length).coerceIn(0f, 1f)
        curlEnd = ((length - flowerLen) / length).coerceIn(ringEnd, 1f)

        pieceStroke = Array(PIECES) { j ->
            val u = (j + .5f) / PIECES
            Stroke(INK_DP * dp * pressure(u), cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
        for (j in 0..PIECES) pieceStart[j] = length * j / PIECES
        for (j in 0 until PIECES) measure.getSegment(pieceStart[j], pieceStart[j + 1], pieces[j], true)

        fill = Brush.radialGradient(
            0f to Sakura.White.copy(alpha = .95f), .45f to lerpColor(Sakura.White, Sakura.Background, .6f).copy(alpha = .9f),
            1f to lerpColor(Sakura.Background, Sakura.Primary, .55f).copy(alpha = .85f), center = c, radius = r * FLOWER,
        )
        stamenEnds = Array(5) { i -> at(.22f, 270f + 72f * i) }
        sparkF = FloatArray(sparkles) { i -> .05f + .9f * (i + .5f * ih(i, 301)) / sparkles.coerceAtLeast(1) }
        sparkAt = Array(sparkles) { i ->
            val d = sparkF[i] * length
            val p = measure.getPosition(d)
            val t = measure.getTangent(d)
            val side = (if (ih(i, 302) < .5f) -1f else 1f) * (7f + 7f * ih(i, 303)) * dp
            Offset(p.x - t.y * side, p.y + t.x * side)
        }
    }

    /** The brush's width at [u] (0..1 along the line), as a factor of [INK_DP]. */
    fun pressure(u: Float): Float {
        val land = .6f + .4f * smooth((u / .035f).coerceIn(0f, 1f))
        val body = when {
            u < ringEnd -> 1f + .32f * sin(PI.toFloat() * u / ringEnd)
            u < curlEnd -> lerp(1f, .8f, (u - ringEnd) / (curlEnd - ringEnd).coerceAtLeast(1e-4f))
            else -> .8f
        }
        val lift = 1f - .62f * smooth(((u - .955f) / .045f).coerceIn(0f, 1f))
        return land * body * lift
    }

    /** The pen's position at [k] (0..1 along the line). */
    fun penAt(k: Float): Offset = measure.getPosition(k.coerceIn(0f, 1f) * length)

    fun draw(scope: DrawScope, ms: Float, amb: Float, t: OneStrokeTimes, level: EffectLevel, reduced: Boolean, window: Boolean) = with(scope) {
        val k = if (reduced) 1f else t.drawK(ms)
        val d = k * length
        val ghost = if (reduced) 0f else t.ghost(ms)
        if (ghost > 0f) drawPath(path, Ink.copy(alpha = .16f * ghost), style = ghostStroke)
        val glowIn = t.glowIn(ms, reduced)
        if (glowIn > 0f) {
            // The breathing glow: from nothing at the flash, then on a slow 2.6 s breath (still for reduced motion and SAVER).
            val breath = if (reduced || level == EffectLevel.SAVER) .5f
            else .5f - .5f * cos(2f * PI.toFloat() * (amb - t.hit / 1000f).coerceAtLeast(0f) / OneStrokeTimes.BREATH_S)
            val a = (.55f + .45f * breath) * glowIn
            drawPath(path, Sakura.Primary.copy(alpha = .07f * a), style = halo[0])
            drawPath(path, Sakura.Primary.copy(alpha = .10f * a), style = halo[1])
            drawPath(path, Sakura.Primary.copy(alpha = .13f * a), style = halo[2])
        }
        if (!window) {
            val open = t.open(ms, reduced)
            if (open > 0f) {
                drawPath(flower, fill, alpha = open)
                for (e in stamenEnds) {
                    drawLine(Sakura.Primary.copy(alpha = .9f * open), c, e, 1.4f * dp, StrokeCap.Round)
                    drawCircle(Sakura.Primary.copy(alpha = open), 2.4f * dp, e)
                }
                drawCircle(Sakura.White.copy(alpha = open), 3.6f * dp, c)
            }
        }
        if (d <= 0f) return@with
        val closed = k >= 1f
        // A white under-glow along the drawn line, so it reads on the pink and on any picture.
        if (closed) drawPath(path, Sakura.White.copy(alpha = .6f), style = underStroke)
        else {
            scratch.reset()
            measure.getSegment(0f, d, scratch, true)
            drawPath(scratch, Sakura.White.copy(alpha = .6f), style = underStroke)
        }
        for (j in 0 until PIECES) {
            val a = pieceStart[j]
            if (a >= d) break
            if (pieceStart[j + 1] <= d) drawPath(pieces[j], Ink, style = pieceStroke[j])
            else {
                scratch.reset()
                measure.getSegment(a, d, scratch, true)
                drawPath(scratch, Ink, style = pieceStroke[j])
            }
        }
        // Sparkles shed beside the line just behind the pen; once the crest breathes, they twinkle now and then.
        for (i in sparkAt.indices) {
            val behind = (d - sparkF[i] * length) / length
            var a = if (!closed && behind > 0f && behind < .1f) sin(PI.toFloat() * behind / .1f) else 0f
            if (closed && !reduced && level != EffectLevel.SAVER) {
                val tw = sin(amb * 2f * PI.toFloat() / OneStrokeTimes.BREATH_S * .7f + 6.28f * ih(i, 304))
                a = max(a, glowIn * tw.coerceAtLeast(0f).pow(8))
            }
            if (a > 0f) introSparkle(sparkle, sparkAt[i], (3f + 2f * ih(i, 305)) * dp, if (ih(i, 306) < .6f) Sakura.White else Sakura.Aqua, a, 45f * ih(i, 307))
        }
        if (!closed) {
            // The pen: a white nib in a pink halo, a small star turning on it.
            val p = measure.getPosition(d)
            withTransform({ translate(p.x, p.y); scale(13f * dp, 13f * dp, Offset.Zero) }) { drawCircle(PenHalo, 1f, Offset.Zero) }
            drawCircle(Sakura.White, 2.6f * dp, p)
            if (level != EffectLevel.SAVER) introSparkle(sparkle, p, 6.5f * dp, Sakura.White, .95f, d / dp * .9f)
            return@with
        }
        // The line closes: one flash along all of it and a soft bloom from the heart (never for reduced motion).
        val f = if (reduced) 0f else flash(ms, t.hit, 1f)
        if (f > 0f) {
            drawPath(path, Sakura.White.copy(alpha = .45f * f), style = flashWide)
            drawPath(path, Sakura.White.copy(alpha = .85f * f), style = flashCore)
            if (level != EffectLevel.SAVER) {
                val g = 1f - f
                withTransform({ translate(c.x, c.y); scale(r * (.55f + .6f * g), r * (.55f + .6f * g), Offset.Zero) }) {
                    drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .5f * f)
                }
            }
        }
    }

    companion object {
        /** The flower's size against the ring. */
        const val FLOWER = .68f
        /** How far past its start the line runs round the flower (degrees), as a brush lifts. */
        const val OVERSHOOT = 10f
        const val PIECES = 48
        /** The ink's width at full pressure. */
        const val INK_DP = 2.4f
    }
}

/**
 * One glyph of the written name: its outline from the handwriting font, cut into contours, each revealed from its top-left point
 * outwards both ways at once. The handwriting is a thin pen whose strokes are narrower than the thickening stroke ([GlyphRun.body]),
 * so a contour's two sides drawn with that stroke cover the stroke between them: the reveal reads as the stroke being written
 * along its middle, and the finished glyph (outline filled and stroked) is exactly the text the other looks draw.
 */
private class InkGlyph(val run: GlyphRun, val i: Int, val left: Float, val top: Float, val ink: InkPaths?)

private class InkPaths(
    val full: Path,
    val contours: Array<Path>,
    val measures: Array<PathMeasure>,
    val lengths: FloatArray,
    /** Where each contour's reveal starts (distance along it). */
    val origin: FloatArray,
    /** Each contour's share of the glyph's time: start and length, 0..1. */
    val start: FloatArray,
    val dur: FloatArray,
    val stroke: Stroke,
)

/**
 * The words of 一筆描: 歡迎回來 (the user's own words) small above, the name written in large (just the words when there is no
 * name), the caption DSH MOBILE typed below. Laid out once; [draw] reads the clock.
 */
internal class OneStrokeText(mm: IntroMeasure, private val lay: OneStrokeLayout, words: String, name: String, hand: Typeface?, private val dp: Float) {
    private val eyebrow: GlyphRun? = if (name.isEmpty()) null else mm.fit(words, IntroFonts.hand(20f), .14f, 20f, 16f, lay.textMaxW)
    /** The name's lines (the shared sticker's fitting and line breaking, drawn here as ink instead). */
    private val hero = IntroSticker(mm, name.ifEmpty { words }, lay.textMaxW, 72f, 40f, dp)
    private val caption: GlyphRun = mm.run(IntroLatin.BRAND, IntroFonts.latin(11f, FontWeight.SemiBold), .42f)
    private val gapEyebrow = 6f * dp * lay.s
    private val gapCaption = 16f * dp * lay.s
    private val blockH = (eyebrow?.let { it.height + gapEyebrow } ?: 0f) + hero.height + gapCaption + caption.height
    private val top: Float = if (lay.landscape) lay.c.y - blockH / 2f else lay.textTop
    private val eyebrowTop = top
    val nameTop: Float = top + (eyebrow?.let { it.height + gapEyebrow } ?: 0f)
    val captionTop: Float = nameTop + hero.height + gapCaption
    private val glyphs: List<InkGlyph>
    /** True when every glyph of the name has outlines to write along (false: it fades and wipes in instead). */
    val written: Boolean
    /** A soft blush band behind the words over a picture. */
    val band: Brush = Brush.verticalGradient(
        0f to Sakura.Background.copy(alpha = 0f), .3f to Sakura.Background.copy(alpha = .38f), .75f to Sakura.Background.copy(alpha = .38f),
        1f to Sakura.Background.copy(alpha = 0f), startY = top - 36f * dp, endY = captionTop + caption.height + 40f * dp,
    )
    private val scratch = Path()

    init {
        val out = ArrayList<InkGlyph>()
        for ((li, line) in hero.lines.withIndex()) {
            val left = lay.textX - line.width / 2f
            val base = hero.baseline(nameTop, li)
            for (g in 0 until line.size) {
                if (line.text[g].isBlank()) continue
                val x = left + line.x[g]
                val ink = if (hand != null && line.thick[g] > 0f) runCatching { inkPaths(line, g, hand, x, base) }.getOrNull() else null
                out += InkGlyph(line, g, x, base - line.baseline, ink)
            }
        }
        glyphs = out
        written = out.isNotEmpty() && out.all { it.ink != null }
    }

    private fun inkPaths(run: GlyphRun, g: Int, hand: Typeface, x: Float, baseline: Float): InkPaths? {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = hand; textSize = run.fontPx }
        val text = run.text[g]
        val outline = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, x, baseline, outline)
        val pm = android.graphics.PathMeasure(outline, false)
        class C(val path: Path, val len: Float, val origin: Float, val score: Float)
        val found = ArrayList<C>()
        val pos = FloatArray(2)
        do {
            val len = pm.length
            if (len > 1f) {
                val piece = android.graphics.Path()
                pm.getSegment(0f, len, piece, true)
                // The reveal starts at the contour's most top-left point (a stroke is written from its top or its left).
                var best = 0f
                var bestScore = Float.MAX_VALUE
                val n = 48
                for (j in 0 until n) {
                    val dd = len * j / n
                    pm.getPosTan(dd, pos, null)
                    val sc = pos[1] + .6f * (pos[0] - x)
                    if (sc < bestScore) { bestScore = sc; best = dd }
                }
                found += C(piece.asComposePath(), len, best, bestScore)
            }
        } while (pm.nextContour())
        if (found.isEmpty()) return null
        found.sortBy { it.score }
        val n = found.size
        // Longer contours get more time, short dots still a visible moment; each starts a little before the last one ends.
        val weight = FloatArray(n) { found[it].len.pow(.7f) }
        val sum = weight.sum()
        val f = FloatArray(n) { weight[it] / sum }
        val stretch = 1.3f
        var before = 0f
        for (j in 0 until n - 1) before += f[j]
        val unit = 1f / (before + stretch * f[n - 1])
        val start = FloatArray(n)
        val dur = FloatArray(n)
        var acc = 0f
        for (j in 0 until n) {
            start[j] = acc * unit
            dur[j] = min(stretch * f[j] * unit, 1f - start[j])
            acc += f[j]
        }
        return InkPaths(
            outline.asComposePath(),
            Array(n) { found[it].path },
            Array(n) { j -> PathMeasure().apply { setPath(found[j].path, false) } },
            FloatArray(n) { found[it].len },
            FloatArray(n) { found[it].origin },
            start, dur,
            run.body(g) ?: Stroke(1f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** This contour (of [len]) revealed [half] each way round [center], wrapping past its start, into [out]. */
    private fun PathMeasure.around(len: Float, center: Float, half: Float, out: Path) {
        out.reset()
        if (half * 2f >= len) { getSegment(0f, len, out, true); return }
        val a = center - half
        val b = center + half
        when {
            a < 0f -> { getSegment(a + len, len, out, true); getSegment(0f, b, out, true) }
            b > len -> { getSegment(a, len, out, true); getSegment(0f, b - len, out, true) }
            else -> getSegment(a, b, out, true)
        }
    }

    fun draw(scope: DrawScope, ms: Float, t: OneStrokeTimes, reduced: Boolean) = with(scope) {
        eyebrow?.let { e ->
            val a = if (reduced) 1f else seg(ms, t.eyebrowAt, 320f * t.pace, IntroEase.CubicOut)
            if (a > 0f) drawRun(e, lay.textX - e.width / 2f, eyebrowTop + .3f * e.fontPx * (1f - a), Sakura.TextSecondary, a)
        }
        val n = glyphs.size
        if (n > 0) {
            // Each glyph writes in its own window; the next starts as the last one's final stroke is still going.
            val each = t.writeMs / (n * .8f + .2f)
            val step = .8f * each
            for ((j, g) in glyphs.withIndex()) {
                val q = if (reduced) 1f else ((ms - t.writeAt - j * step) / each).coerceIn(0f, 1f)
                if (q <= 0f) continue
                val ink = g.ink
                if (ink == null) {
                    // A character the handwriting lacks (or an emoji): it wipes in from the left as it fades in. An emoji is the
                    // sticker's pink silhouette with a fine rose-brown edge (a dark ink one read as a blot beside the name).
                    val adv = g.run.advance[g.i]
                    clipRect(g.left - adv * .2f, g.top - g.run.fontPx, g.left + adv * 1.2f * q, g.top + 2f * g.run.fontPx) {
                        drawGlyph(g.run, g.i, Offset(g.left, g.top), Sakura.Text, q, sticker = g.run.emoji[g.i])
                    }
                    continue
                }
                if (q >= 1f) {
                    drawPath(ink.full, Sakura.Text)
                    drawPath(ink.full, Sakura.Text, style = ink.stroke)
                    continue
                }
                for (k in ink.contours.indices) {
                    val p = ((q - ink.start[k]) / ink.dur[k]).coerceIn(0f, 1f)
                    if (p <= 0f) continue
                    if (p >= 1f) { drawPath(ink.contours[k], Sakura.Text, style = ink.stroke); continue }
                    val e = IntroEase.SineInOut.transform(p)
                    ink.measures[k].around(ink.lengths[k], ink.origin[k], e * ink.lengths[k] / 2f, scratch)
                    drawPath(scratch, Sakura.Text, style = ink.stroke)
                }
            }
        }
        drawTyped(
            caption, lay.textX - caption.width / 2f, captionTop, Sakura.TextSecondary, .85f, ms, t.captionAt, 24f * t.pace,
            hold = 800f, reduced = reduced,
        )
    }
}
