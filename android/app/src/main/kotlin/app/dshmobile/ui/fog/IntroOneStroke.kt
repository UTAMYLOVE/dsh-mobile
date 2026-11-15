package app.dshmobile.ui.fog

import android.graphics.Typeface
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Ideas, not code: the mark drawn in over its own faint ghost, the breathing glow once it is whole and the wide-tracked caption
// with a block cursor come from the simple boot of yannicksong0106/dsh-550c-boot (MIT, (c) 2026 Ziyang Song); the name written
// in along its strokes comes from the handwritten "hello" reveal of tianyuec11-design/dsh-apple-liquid-glass (MIT, (c) 2026 CTY).
// No code, numbers, images or names of either were copied: the crest, the timing and the stroke reveal below are our own.

/**
 * 「一筆描」 (One stroke), the startup look [IntroStyle.ONE_STROKE], the short and quiet one: a pen draws the 櫻紋 crest in one
 * unbroken brush line (an open ring that curls in and runs onto a five-petal sakura) over its own faint ghost; when the line
 * closes a soft white glow runs along it and the crest keeps breathing a pink glow, the flower opens onto your picture (or blooms
 * pale pink with its stamens) and your name is written in stroke by stroke in the intro's handwriting, with 歡迎回來 above it and a
 * small typed DSH MOBILE below. Over a picture the blush veil then thins a little, so the picture develops as the payoff.
 *
 * It is the short option: the run ends at min(setting, 2.5 s). All its moves take 1.9 s (everything has moved by 1.53 s, then
 * the finished name holds about 0.6 s); a 1.5 s setting shortens the pen most, so the name still holds at least 0.3 s.
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
        val driftCount = introCount(level, fx, 9, 6, 3, reduced)
        val drift = remember(this) { IntroDrift(widthPx, heightPx, dp, driftCount, fx, salt = 40) }
        val season = remember(this) { IntroSeasonFx(widthPx, heightPx, dp, fx.season, introCount(level, fx, 8, 6, 3, reduced), fx) }
        val particles = driftCount > 0 || season.active
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
                    // A blush veil over the picture, like paper: the line reads on it. The flower clears as it opens, and once the
                    // name is written the veil thins outside the words, so the picture develops; the band behind the words grows
                    // by as much, so they stay as easy to read.
                    val develop = times.develop(ms, reduced)
                    val veil = lerp(VEIL, VEIL_DEVELOPED, develop)
                    val open = if (window) times.open(ms, reduced) else 0f
                    if (open <= 0f) {
                        drawRect(backdrop.sky, alpha = veil)
                    } else {
                        drawPath(crest.veil, backdrop.sky, alpha = veil)
                        if (open < 1f) drawPath(crest.flower, backdrop.sky, alpha = veil * (1f - open))
                    }
                    drawRect(text.band, alpha = lerp(OneStrokeText.BAND_START, 1f, develop))
                }
            },
        )
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(60f, 160f) }.drawBehind {
                crest.drawUnder(this, run.ms, run.amb, times, level, reduced, window)
            },
        )
        // The ink and its white under-glow in their own layer, each piece drawn with BlendMode.Src: the brush pieces overlap at
        // their round ends, and half-transparent pieces drawn one over another left darker beads at every joint. The layer is
        // only as big as the crest.
        val box = crest.inkBox
        Spacer(
            Modifier
                .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                .graphicsLayer {
                    alpha = run.exitFade(60f, 160f)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawBehind {
                    translate(-box.left.roundToInt().toFloat(), -box.top.roundToInt().toFloat()) { crest.drawInk(this, run.ms, times, reduced) }
                },
        )
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(60f, 160f) }.drawBehind {
                crest.drawOver(this, run.ms, run.amb, times, level, reduced)
            },
        )
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(40f) }.drawBehind {
                text.draw(this, run.ms, times, reduced)
            },
        )
        // The petals (or sparkles) and the season drift in front of the crest but fade out over the band of the words: a petal
        // cut into the name, and behind it the ink's soft edge over a snowflake's aqua ring read as grey. Composed in a layer so
        // the band can wipe them softly.
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { if (particles) compositingStrategy = CompositingStrategy.Offscreen }.drawBehind {
                if (!particles) return@drawBehind
                val inK = if (reduced) 1f else seg(run.ms, 0f, 500f)
                drift.draw(this, run.amb, .8f * inK)
                season.draw(this, run.amb, inK)
                drawRect(text.particleMask, blendMode = BlendMode.DstOut)
            },
        )
    }
}

/** The blush veil over the picture, strong enough that the fine line reads on any picture... */
private const val VEIL = .70f

/** ...and thinner once the name is written (outside the words' band). */
private const val VEIL_DEVELOPED = .50f

/** The crest's ink: rose brown at .8 over the blush (one of the six colours, never a mixed solid)... */
private val Ink = Sakura.RoseBrown.copy(alpha = .8f)

/** ...on a white under-glow [UNDER] times as wide as the brush. */
private val UnderInk = Sakura.White.copy(alpha = .6f)
private const val UNDER = 2.3f

/** The pen's pink halo, radius 1 at the origin (scaled where it is drawn). */
private val PenHalo: Brush = Brush.radialGradient(
    0f to Sakura.Primary.copy(alpha = .55f), .45f to Sakura.Primary.copy(alpha = .22f), 1f to Sakura.Primary.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)

/**
 * One run's beats (ms on the run's clock), a pure function of the 1.5–8 s setting. The run is the short option: it ends at
 * min(setting, [MAX_MS]). The moves: the ghost inks in (110), the pen draws the crest (820), the crest glows and the name is
 * written (600), the exit iris (360); one hold between them takes the rest (at least [HOLD_MIN], so the finished name always
 * shows a moment). A move never slows down. When the moves do not fit (a 1.5 s setting) the name keeps at least [WRITE_FLOOR]
 * and the other moves tighten by [pace]: the pen gives up most.
 */
internal class OneStrokeTimes private constructor(val plan: IntroPlan, val pace: Float) {
    /** When the pen starts and how long it draws. */
    val drawAt: Float = plan.at(1)
    val drawMs: Float = plan.length(1)
    /** The line closes: the crest glows (and the haptic tick). */
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

    /** The crest's breathing glow coming in after the line closes. */
    fun glowIn(ms: Float, reduced: Boolean = false): Float = if (reduced) 1f else seg(ms, hit, 360f * pace)

    /** The picture developing once the name is whole: over [DEVELOP_MS], or what is left of the hold. */
    fun develop(ms: Float, reduced: Boolean = false): Float =
        if (reduced) 1f else seg(ms, settled, min(DEVELOP_MS, exit - settled).coerceAtLeast(1f), IntroEase.CubicInOut)

    companion object {
        /** The look's own length at most: it is the short option. */
        const val MAX_MS = 2500f
        const val INK_MS = 110f
        const val DRAW_MS = 820f
        const val WRITE_MS = 600f
        /** The name's writing never gets shorter than this (a 1.5 s run takes it from the pen instead). */
        const val WRITE_FLOOR = 480f
        const val HOLD_MIN = 300f
        const val EXIT_MS = 360f
        const val DEVELOP_MS = 400f
        /** All the moves together: the look's own length before any hold. */
        const val MOVES_MS = INK_MS + DRAW_MS + WRITE_MS + EXIT_MS

        /** The breathing glow's period (s). */
        const val BREATH_S = 2.6f

        val PenEase: Easing = CubicBezierEasing(.42f, 0f, .28f, 1f)

        /** The beats for a [settingMs] setting; the run itself lasts min(setting, [MAX_MS]). */
        fun of(settingMs: Float): OneStrokeTimes {
            val total = min(settingMs, MAX_MS)
            val room = total - HOLD_MIN
            if (room >= MOVES_MS) {
                return OneStrokeTimes(
                    IntroPlan.of(
                        total, IntroBeat.move(INK_MS), IntroBeat.move(DRAW_MS), IntroBeat.move(WRITE_MS), IntroBeat.hold(1f, HOLD_MIN),
                        IntroBeat.move(EXIT_MS),
                    ),
                    1f,
                )
            }
            // Too short for every move: the writing keeps its floor (never more than 60 % of the room), the rest share what is left.
            val even = (room / MOVES_MS).coerceIn(0f, 1f)
            val write = min(WRITE_MS, max(WRITE_MS * even, min(WRITE_FLOOR, room * .6f)))
            val pace = ((room - write) / (MOVES_MS - WRITE_MS)).coerceIn(0f, 1f)
            return OneStrokeTimes(
                IntroPlan.of(
                    total, IntroBeat.move(INK_MS * pace), IntroBeat.move(DRAW_MS * pace), IntroBeat.move(write), IntroBeat.hold(1f, HOLD_MIN),
                    IntroBeat.move(EXIT_MS * pace),
                ),
                pace,
            )
        }
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
 * The crest as one unbroken brush line, built once per size: an open ring ([RING_SWEEP]° of a circle clockwise from [RING_START],
 * one o'clock), then a curl that dives in over the top and lands on the upper-right petal's shoulder ([JOIN]) along the
 * outline's own direction (a cubic matched to the outline's slope there, so the two lines meet without a corner or a spur), then
 * the five-petal sakura outline once round (the shared silhouette at [FLOWER] of the ring) and a little past where it began, as a
 * brush lifts. One contour, so one [PathMeasure] draws it.
 *
 * The ink is cut into pieces of a brush-like width (it lands at a fifth of its width and fills out, swells round the ring, thins
 * in the curl and the flower, lifts to a hairline), finer where the width changes fast; a piece the pen has passed is a fixed
 * path, so a frame only cuts the one under the pen.
 */
internal class OneStrokeCrest(w: Float, h: Float, val c: Offset, val r: Float, private val dp: Float, sparkles: Int) {
    val path = Path()
    /** The flower outline alone, closed (its fill and the picture's window). */
    val flower = Path()
    /** The screen with the flower cut out (even-odd): the veil round the open window. */
    val veil = Path()
    /** Where the ink layer sits: the crest and its widest stroke. */
    val inkBox: Rect = Rect(c.x - r - 12f * dp, c.y - r - 12f * dp, c.x + r + 12f * dp, c.y + r + 12f * dp)
    private val measure = PathMeasure()
    val length: Float
    /** Where the ring ends and the curl ends, as fractions of [length]. */
    val ringEnd: Float
    val curlEnd: Float

    private val pieceStart = FloatArray(PIECES + 1)
    private val pieces = Array(PIECES) { Path() }
    private val pieceStroke: Array<Stroke>
    private val underStroke: Array<Stroke>

    private val ghostStroke = Stroke(1.3f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    /** The breathing glow: three widening strokes, ever fainter, read as one soft halo (no blur needed). */
    private val halo = arrayOf(
        Stroke(22f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(14f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(8f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    /** The glow when the line closes: the same soft falloff, in white, under the ink. */
    private val glowStrokes = floatArrayOf(18f, 11f, 6f, 3f).map { Stroke(it * dp, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    private val glowAlpha = floatArrayOf(.12f, .18f, .25f, .5f)
    private val scratch = Path()
    private val sparkle = Kawaii.sparklePath()

    /** The pale fill of the flower without a picture: white at the heart, blush, pink at the petals' edges. */
    private val fill: Brush
    /** The stamens: fine curved filaments of varied length, each with a small anther. */
    private val filaments = Path()
    private val anthers = Path()
    private val filamentStroke = Stroke(1.1f * dp, cap = StrokeCap.Round)
    private val sparkAt: Array<Offset>
    private val sparkF: FloatArray

    init {
        val toRad = (PI / 180.0).toFloat()
        fun at(rho: Float, deg: Float) = Offset(c.x + r * rho * cos(deg * toRad), c.y + r * rho * sin(deg * toRad))
        var started = false
        fun to(p: Offset) { if (started) path.lineTo(p.x, p.y) else { path.moveTo(p.x, p.y); started = true } }
        val ringFrom = RING_START
        val ringTo = RING_START + RING_SWEEP
        var join = JOIN
        while (join <= ringTo) join += 360f
        val curl = join - ringTo
        val steps = (RING_SWEEP * 2).toInt()
        for (i in 0..steps) to(at(1f, ringFrom + RING_SWEEP * i / steps))
        // The curl: a cubic in radius from the ring (radius 1, level) to the outline at the join, arriving with the outline's own
        // slope there (a brush joining the petal along its edge, not across it).
        val rj = FLOWER * flowerRadius(join)
        val slope = FLOWER * (flowerRadius(join + .05f) - flowerRadius(join - .05f)) / .1f * curl
        val curlSteps = (curl * 2).toInt()
        for (i in 1..curlSteps) {
            val u = i / curlSteps.toFloat()
            val u2 = u * u
            val u3 = u2 * u
            val rho = (2f * u3 - 3f * u2 + 1f) + (-2f * u3 + 3f * u2) * rj + (u3 - u2) * slope
            to(at(rho, ringTo + curl * u))
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
        // The ring is r·sweep; the curl ends where the flower begins, the flower's own length (once round and the overshoot)
        // before the end.
        val ringLen = r * RING_SWEEP * toRad
        val flowerLen = PathMeasure().apply { setPath(flower, true) }.length * (360f + OVERSHOOT) / 360f
        ringEnd = (ringLen / length).coerceIn(0f, 1f)
        curlEnd = ((length - flowerLen) / length).coerceIn(ringEnd, 1f)

        for (j in 0..PIECES) pieceStart[j] = length * pieceU(j)
        pieceStroke = Array(PIECES) { j ->
            val u = (pieceU(j) + pieceU(j + 1)) / 2f
            Stroke(INK_DP * dp * pressure(u), cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
        underStroke = Array(PIECES) { j -> Stroke(pieceStroke[j].width * UNDER, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        for (j in 0 until PIECES) measure.getSegment(pieceStart[j], pieceStart[j + 1], pieces[j], true)

        fill = Brush.radialGradient(
            0f to Sakura.White.copy(alpha = .95f), .45f to lerpColor(Sakura.White, Sakura.Background, .6f).copy(alpha = .9f),
            1f to lerpColor(Sakura.Background, Sakura.Primary, .55f).copy(alpha = .85f), center = c, radius = r * FLOWER,
        )
        for (i in 0 until STAMENS) {
            val deg = 270f + (i + .7f * (ih(i, 310) - .5f)) * 360f / STAMENS
            val len = r * FLOWER * (.21f + .15f * ih(i, 311))
            val a = deg * toRad
            val dir = Offset(cos(a), sin(a))
            val from = c + dir * (.035f * r)
            val end = c + dir * len
            val bend = (if (ih(i, 312) < .5f) -1f else 1f) * (.10f + .10f * ih(i, 313)) * len
            val mid = (from + end) / 2f + Offset(-dir.y, dir.x) * bend
            filaments.moveTo(from.x, from.y)
            filaments.quadraticTo(mid.x, mid.y, end.x, end.y)
            val ar = (1.3f + .6f * ih(i, 314)) * dp
            anthers.addOval(Rect(end.x - ar, end.y - ar * .8f, end.x + ar, end.y + ar * .8f))
        }
        sparkF = FloatArray(sparkles) { i -> .05f + .9f * (i + .5f * ih(i, 301)) / sparkles.coerceAtLeast(1) }
        sparkAt = Array(sparkles) { i ->
            val d = sparkF[i] * length
            val p = measure.getPosition(d)
            val t = measure.getTangent(d)
            val side = (if (ih(i, 302) < .5f) -1f else 1f) * (7f + 7f * ih(i, 303)) * dp
            Offset(p.x - t.y * side, p.y + t.x * side)
        }
    }

    /** Where piece [j] starts along the line (0..1): finer over the landing and the lift, where the width changes fastest. */
    private fun pieceU(j: Int): Float = when {
        j <= LAND_PIECES -> LAND * j / LAND_PIECES
        j >= PIECES - LIFT_PIECES -> 1f - LIFT * (PIECES - j) / LIFT_PIECES
        else -> LAND + (1f - LAND - LIFT) * (j - LAND_PIECES) / (PIECES - LAND_PIECES - LIFT_PIECES)
    }

    /** The brush's width at [u] (0..1 along the line), as a factor of [INK_DP]. */
    fun pressure(u: Float): Float {
        val land = .18f + .82f * smooth((u / LAND).coerceIn(0f, 1f))
        val body = when {
            u < ringEnd -> 1f + .32f * sin(PI.toFloat() * u / ringEnd)
            u < curlEnd -> lerp(1f, .8f, (u - ringEnd) / (curlEnd - ringEnd).coerceAtLeast(1e-4f))
            else -> .8f
        }
        val lift = 1f - .88f * smooth(((u - (1f - LIFT)) / LIFT).coerceIn(0f, 1f))
        return land * body * lift
    }

    /** The pen's position at [k] (0..1 along the line). */
    fun penAt(k: Float): Offset = measure.getPosition(k.coerceIn(0f, 1f) * length)

    /** The line's direction at [k] (0..1 along the line). */
    fun tangentAt(k: Float): Offset = measure.getTangent(k.coerceIn(0f, 1f) * length)

    /** Below the ink: the ghost, the breathing glow, the flower's fill and stamens, and the soft glow when the line closes. */
    fun drawUnder(scope: DrawScope, ms: Float, amb: Float, t: OneStrokeTimes, level: EffectLevel, reduced: Boolean, window: Boolean) = with(scope) {
        val k = if (reduced) 1f else t.drawK(ms)
        val d = k * length
        val ghost = if (reduced) 0f else t.ghost(ms)
        if (ghost > 0f) drawPath(path, Sakura.RoseBrown.copy(alpha = .16f * ghost), style = ghostStroke)
        val glowIn = t.glowIn(ms, reduced)
        if (glowIn > 0f) {
            // The breathing glow: from nothing as the line closes, then on a slow 2.6 s breath (still for reduced motion and SAVER).
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
                // The stamens grow out from the heart as the flower opens.
                val g = lerp(.55f, 1f, open)
                withTransform({ scale(g, g, c) }) {
                    drawPath(filaments, Sakura.Primary.copy(alpha = .9f * open), style = filamentStroke)
                    drawPath(anthers, Sakura.Primary.copy(alpha = open))
                }
                drawCircle(Sakura.White.copy(alpha = .9f * open), 3.2f * dp, c)
            }
        }
        if (k < 1f || reduced) return@with
        // The line closes: a soft white glow runs along all of it, under the ink (which stays readable), and a bloom from the heart.
        val f = flash(ms, t.hit, 1f)
        if (f <= 0f) return@with
        for (i in glowStrokes.indices) drawPath(path, Sakura.White.copy(alpha = glowAlpha[i] * f), style = glowStrokes[i])
        if (level != EffectLevel.SAVER) {
            val g = 1f - f
            withTransform({ translate(c.x, c.y); scale(r * (.55f + .6f * g), r * (.55f + .6f * g), Offset.Zero) }) {
                drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .5f * f)
            }
        }
    }

    /**
     * The ink, rose brown at .8, on a white under-glow as wide as the brush allows (so the line reads on the pink and on
     * any picture, and the hairline start stays a hairline). Drawn into its own layer with [BlendMode.Src], so a piece replaces
     * what is under it instead of adding to it: the overlapping round ends leave no beads, and the ink is never darkened twice.
     */
    fun drawInk(scope: DrawScope, ms: Float, t: OneStrokeTimes, reduced: Boolean) = with(scope) {
        val d = (if (reduced) 1f else t.drawK(ms)) * length
        if (d <= 0f) return@with
        pieceRun(d, UnderInk, underStroke)
        pieceRun(d, Ink, pieceStroke)
    }

    private fun DrawScope.pieceRun(d: Float, color: Color, strokes: Array<Stroke>) {
        for (j in 0 until PIECES) {
            val a = pieceStart[j]
            if (a >= d) break
            if (pieceStart[j + 1] <= d) drawPath(pieces[j], color, style = strokes[j], blendMode = BlendMode.Src)
            else {
                scratch.reset()
                measure.getSegment(a, d, scratch, true)
                drawPath(scratch, color, style = strokes[j], blendMode = BlendMode.Src)
            }
        }
    }

    /** Over the ink: the pen while it draws and the sparkles it sheds, which twinkle now and then once the crest breathes. */
    fun drawOver(scope: DrawScope, ms: Float, amb: Float, t: OneStrokeTimes, level: EffectLevel, reduced: Boolean) = with(scope) {
        val k = if (reduced) 1f else t.drawK(ms)
        val d = k * length
        if (d <= 0f) return@with
        val closed = k >= 1f
        val glowIn = t.glowIn(ms, reduced)
        for (i in sparkAt.indices) {
            val behind = (d - sparkF[i] * length) / length
            var a = if (!closed && behind > 0f && behind < .1f) sin(PI.toFloat() * behind / .1f) else 0f
            if (closed && !reduced && level != EffectLevel.SAVER) {
                val tw = sin(amb * 2f * PI.toFloat() / OneStrokeTimes.BREATH_S * .7f + 6.28f * ih(i, 304))
                a = max(a, glowIn * tw.coerceAtLeast(0f).pow(8))
            }
            if (a > 0f) introSparkle(sparkle, sparkAt[i], (3f + 2f * ih(i, 305)) * dp, if (ih(i, 306) < .6f) Sakura.White else Sakura.Aqua, a, 45f * ih(i, 307))
        }
        if (closed) return@with
        // The pen: a white nib in a pink halo, a small star turning on it.
        val p = measure.getPosition(d)
        withTransform({ translate(p.x, p.y); scale(13f * dp, 13f * dp, Offset.Zero) }) { drawCircle(PenHalo, 1f, Offset.Zero) }
        drawCircle(Sakura.White, 2.6f * dp, p)
        if (level != EffectLevel.SAVER) introSparkle(sparkle, p, 6.5f * dp, Sakura.White, .95f, d / dp * .9f)
    }

    companion object {
        /** The flower's size against the ring. */
        const val FLOWER = .68f
        /** The ring: from one o'clock, clockwise. */
        const val RING_START = -60f
        const val RING_SWEEP = 320f
        /** Where the curl lands: the upper-right petal's shoulder, just before its notch (its tip is at 342°). */
        const val JOIN = 330f
        /** How far past its start the line runs round the flower (degrees), as a brush lifts. */
        const val OVERSHOOT = 10f
        /** The brush lands over this much of the line and lifts over this much (fractions of its length). */
        const val LAND = .07f
        const val LIFT = .035f
        const val LAND_PIECES = 14
        const val LIFT_PIECES = 10
        const val PIECES = 68
        /** The ink's width at full pressure. */
        const val INK_DP = 2.4f
        const val STAMENS = 13
    }
}

/**
 * A handwriting glyph's strokes, worked out from its outline (the font has outlines only, and strokes that cross are merged
 * into one contour, so revealing contour by contour made shapes that are never written, such as 雨 passing through 工). The
 * glyph is drawn into a [GRID]-per-em grid, thinned to its centre line (Zhang–Suen) and traced into pieces between ends and
 * crossings. The pieces are put in a writing order: top first, then left (y + x/2 at a piece's start, and each piece starts at
 * that end); a piece that carries straight on through a crossing goes next; a glyph split into left and right parts by an empty
 * column writes its left part first (林, 明), except that a tall middle of three goes first (小).
 *
 * Done once per glyph of the name when the look is built (a 96-cell grid: well under a millisecond).
 */
internal object OneStrokeSkeleton {
    const val GRID = 96
    private const val PAD = 3

    class Strokes(
        /** Each stroke as x, y pairs in the outline's own px, from where the pen starts, in writing order. */
        val lines: List<FloatArray>,
        /** The pen's width (px): the ink's area over the strokes' length. */
        val pen: Float,
    )

    private val DX = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val DY = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    fun of(outline: android.graphics.Path, emPx: Float): Strokes? {
        if (emPx <= 0f) return null
        val b = android.graphics.RectF()
        outline.computeBounds(b, true)
        if (b.width() <= 0f && b.height() <= 0f) return null
        val s = GRID / emPx
        val w = ceil(b.width() * s).toInt() + 2 * PAD
        val h = ceil(b.height() * s).toInt() + 2 * PAD
        val m = android.graphics.Matrix().apply { setTranslate(-b.left, -b.top); postScale(s, s); postTranslate(PAD.toFloat(), PAD.toFloat()) }
        val p = android.graphics.Path(outline).apply { transform(m) }
        val region = android.graphics.Region()
        if (!region.setPath(p, android.graphics.Region(0, 0, w, h))) return null
        val ink = BooleanArray(w * h)
        val it = android.graphics.RegionIterator(region)
        val rc = android.graphics.Rect()
        var area = 0
        while (it.next(rc)) {
            for (y in max(rc.top, 0) until min(rc.bottom, h)) for (x in max(rc.left, 0) until min(rc.right, w)) {
                if (!ink[y * w + x]) { ink[y * w + x] = true; area++ }
            }
        }
        if (area == 0) return null
        // One cell wider first: a thin diagonal hairline otherwise breaks into dots on the grid.
        val sk = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (ink[y * w + x]) for (dy in -1..1) for (dx in -1..1) sk[(y + dy) * w + x + dx] = true
        }
        thin(sk, w, h)
        prune(sk, w, h)
        val deg = IntArray(w * h)
        for (i in sk.indices) if (sk[i]) deg[i] = neighbours(sk, w, h, i, null)
        val chains = trace(sk, deg, w, h)
        // Short spurs off a crossing are left over from thinning, not strokes.
        val kept = chains.filter { ch ->
            if (ch.size < 2) return@filter true
            val e0 = deg[ch[0]] == 1
            val e1 = deg[ch[ch.size - 1]] == 1
            !(e0 != e1 && chainLength(ch, w) < .07f * GRID)
        }.map { ch -> if (ch[0] != ch[ch.size - 1] && key(ch[ch.size - 1], w) < key(ch[0], w)) ch.reversedArray() else ch }
        if (kept.isEmpty()) return null
        val ordered = order(kept, ink, w, h)
        var skLen = 0f
        for (ch in ordered) skLen += max(chainLength(ch, w), 1f)
        val lines = ordered.map { ch -> toPx(ch, w, s, b.left, b.top) }
        return Strokes(lines, area / skLen.coerceAtLeast(1f) / s)
    }

    private fun key(i: Int, w: Int): Float = (i / w) + .5f * (i % w)

    private fun chainLength(ch: IntArray, w: Int): Float {
        var l = 0f
        for (k in 1 until ch.size) l += hypot(((ch[k] % w) - (ch[k - 1] % w)).toFloat(), ((ch[k] / w) - (ch[k - 1] / w)).toFloat())
        return l
    }

    /** The skeleton neighbours of cell [i] (8-connected), into [out] when given; returns how many. */
    private fun neighbours(g: BooleanArray, w: Int, h: Int, i: Int, out: IntArray?): Int {
        val x = i % w
        val y = i / w
        var n = 0
        for (k in 0 until 8) {
            val nx = x + DX[k]
            val ny = y + DY[k]
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            val j = ny * w + nx
            if (g[j]) { out?.set(n, j); n++ }
        }
        return n
    }

    /** Zhang–Suen thinning, in place. */
    private fun thin(g: BooleanArray, w: Int, h: Int) {
        val del = IntArray(w * h)
        val nb = BooleanArray(8)
        var changed = true
        while (changed) {
            changed = false
            for (step in 0..1) {
                var n = 0
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (!g[i]) continue
                    var count = 0
                    for (k in 0 until 8) { nb[k] = g[(y + DY[k]) * w + x + DX[k]]; if (nb[k]) count++ }
                    if (count < 2 || count > 6) continue
                    var a = 0
                    for (k in 0 until 8) if (!nb[k] && nb[(k + 1) % 8]) a++
                    if (a != 1) continue
                    // nb: 0 N, 1 NE, 2 E, 3 SE, 4 S, 5 SW, 6 W, 7 NW
                    if (step == 0 && ((nb[0] && nb[2] && nb[4]) || (nb[2] && nb[4] && nb[6]))) continue
                    if (step == 1 && ((nb[0] && nb[2] && nb[6]) || (nb[0] && nb[4] && nb[6]))) continue
                    del[n++] = i
                }
                for (k in 0 until n) g[del[k]] = false
                if (n > 0) changed = true
            }
        }
    }

    /** Drops cells whose neighbours stay connected without them (the thinning's staircases), so a line is one cell wide. */
    private fun prune(g: BooleanArray, w: Int, h: Int) {
        val nx = IntArray(8)
        val ny = IntArray(8)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!g[i]) continue
            var n = 0
            for (k in 0 until 8) {
                val ax = x + DX[k]
                val ay = y + DY[k]
                if (ax < 0 || ay < 0 || ax >= w || ay >= h || !g[ay * w + ax]) continue
                nx[n] = DX[k]; ny[n] = DY[k]; n++
            }
            if (n < 2) continue
            var seen = 1
            var grew = true
            while (grew) {
                grew = false
                for (a in 0 until n) {
                    if (seen and (1 shl a) == 0) continue
                    for (c in 0 until n) {
                        if (seen and (1 shl c) != 0) continue
                        if (abs(nx[a] - nx[c]) <= 1 && abs(ny[a] - ny[c]) <= 1) { seen = seen or (1 shl c); grew = true }
                    }
                }
            }
            if (seen == (1 shl n) - 1) g[i] = false
        }
    }

    /** The skeleton as chains of cells: between ends and crossings, then any closed loops, then lone dots. */
    private fun trace(g: BooleanArray, deg: IntArray, w: Int, h: Int): List<IntArray> {
        val node = BooleanArray(w * h) { g[it] && deg[it] != 2 }
        val visited = BooleanArray(w * h)
        val out = ArrayList<IntArray>()
        val nb = IntArray(8)
        val nb2 = IntArray(8)
        for (i in g.indices) {
            if (!node[i]) continue
            if (deg[i] == 0) { out += intArrayOf(i); continue }
            val n = neighbours(g, w, h, i, nb)
            for (a in 0 until n) {
                val q = nb[a]
                if (node[q] || visited[q]) continue
                val chain = ArrayList<Int>()
                chain += i; chain += q
                visited[q] = true
                var prev = i
                var cur = q
                while (true) {
                    val m = neighbours(g, w, h, cur, nb2)
                    var next = -1
                    for (c in 0 until m) {
                        val o = nb2[c]
                        if (o == prev || (!node[o] && visited[o])) continue
                        if (chain.size >= 3 && o == chain[chain.size - 3]) continue
                        next = o
                        break
                    }
                    if (next < 0) break
                    chain += next
                    if (node[next]) break
                    visited[next] = true
                    prev = cur
                    cur = next
                }
                out += chain.toIntArray()
            }
        }
        // Closed loops with no end or crossing (口 written in one go): start top-left, go clockwise.
        while (true) {
            var start = -1
            for (i in g.indices) if (g[i] && !node[i] && !visited[i] && (start < 0 || key(i, w) < key(start, w))) start = i
            if (start < 0) break
            val chain = ArrayList<Int>()
            chain += start
            visited[start] = true
            var cur = start
            var first = true
            while (true) {
                val m = neighbours(g, w, h, cur, nb)
                var next = -1
                var best = Float.NEGATIVE_INFINITY
                for (c in 0 until m) {
                    val o = nb[c]
                    if (visited[o] || node[o]) continue
                    val score = if (first) ((o % w) - (cur % w)) - ((o / w) - (cur / w)).toFloat() else 0f
                    if (next < 0 || score > best) { next = o; best = score }
                    if (!first) break
                }
                if (next < 0) break
                chain += next
                visited[next] = true
                cur = next
                first = false
            }
            chain += start
            out += chain.toIntArray()
        }
        return out
    }

    /** Left-right parts: empty columns with between 15 % and 85 % of the ink on their left. */
    private fun cuts(ink: BooleanArray, w: Int, h: Int): FloatArray {
        val col = IntArray(w)
        var total = 0
        for (i in ink.indices) if (ink[i]) { col[i % w]++; total++ }
        val out = ArrayList<Float>()
        var acc = 0
        var x = 0
        while (x < w) {
            if (col[x] == 0 && acc > .15f * total && acc < .85f * total) {
                val x0 = x
                while (x < w && col[x] == 0) x++
                out += (x0 + x) / 2f
                continue
            }
            acc += col[x]
            x++
        }
        return out.toFloatArray()
    }

    private fun direction(ch: IntArray, w: Int, atEnd: Boolean): Offset? {
        if (ch.size < 2) return null
        val n = min(4, ch.size - 1)
        val a = if (atEnd) ch[ch.size - 1 - n] else ch[0]
        val b = if (atEnd) ch[ch.size - 1] else ch[n]
        val dx = ((b % w) - (a % w)).toFloat()
        val dy = ((b / w) - (a / w)).toFloat()
        val l = hypot(dx, dy)
        return if (l > 0f) Offset(dx / l, dy / l) else null
    }

    private fun order(chains: List<IntArray>, ink: BooleanArray, w: Int, h: Int): List<IntArray> {
        val cut = cuts(ink, w, h)
        fun part(ch: IntArray): Int {
            var sx = 0f
            for (c in ch) sx += c % w
            val mx = sx / ch.size
            return cut.count { mx > it }
        }
        val parts = cut.size + 1
        val rank = IntArray(parts) { it }
        if (parts >= 3) {
            var top = -1
            var topY = Int.MAX_VALUE
            for (ch in chains) for (c in ch) if (c / w < topY) { topY = c / w; top = part(ch) }
            if (top in 1 until parts - 1) for (p in 0 until parts) rank[p] = if (p == top) 0 else p + 1
        }
        val rest = chains.toMutableList()
        val out = ArrayList<IntArray>()
        var cur: IntArray? = null
        while (rest.isNotEmpty()) {
            var pick = -1
            var pickChain: IntArray? = null
            val c = cur
            val d0 = if (c != null) direction(c, w, true) else null
            if (c != null && d0 != null) {
                val end = c[c.size - 1]
                var best = .64f
                for ((i, e) in rest.withIndex()) {
                    if (part(e) != part(c)) continue
                    for (rev in 0..1) {
                        val ee = if (rev == 1) e.reversedArray() else e
                        if (max(abs((ee[0] % w) - (end % w)), abs((ee[0] / w) - (end / w))) > 2) continue
                        val d1 = direction(ee, w, false) ?: continue
                        val dot = d0.x * d1.x + d0.y * d1.y
                        if (dot > best) { best = dot; pick = i; pickChain = ee }
                    }
                }
            }
            if (pick < 0) {
                pick = rest.indices.minWith(compareBy<Int>({ rank[part(rest[it])] }, { key(rest[it][0], w) }))
                pickChain = rest[pick]
            }
            rest.removeAt(pick)
            out += pickChain!!
            cur = pickChain
        }
        return out
    }

    /** A chain as px: every other cell, lightly smoothed, back in the outline's coordinates (a lone dot gets a tiny length). */
    private fun toPx(ch: IntArray, w: Int, s: Float, left: Float, top: Float): FloatArray {
        fun px(i: Int) = ((i % w) + .5f - PAD) / s + left
        fun py(i: Int) = ((i / w) + .5f - PAD) / s + top
        if (ch.size == 1) return floatArrayOf(px(ch[0]), py(ch[0]), px(ch[0]) + .5f / s, py(ch[0]) + .5f / s)
        val idx = ArrayList<Int>()
        for (k in ch.indices step 2) idx += k
        if (idx.last() != ch.size - 1) idx += ch.size - 1
        val out = FloatArray(idx.size * 2)
        for ((n, k) in idx.withIndex()) {
            if (n == 0 || n == idx.size - 1) { out[2 * n] = px(ch[k]); out[2 * n + 1] = py(ch[k]); continue }
            val a = ch[idx[n - 1]]
            val c = ch[idx[n + 1]]
            out[2 * n] = (px(a) + 2f * px(ch[k]) + px(c)) / 4f
            out[2 * n + 1] = (py(a) + 2f * py(ch[k]) + py(c)) / 4f
        }
        return out
    }
}

/**
 * One glyph of the written name and how it is written: its outline from the handwriting font and its strokes from
 * [OneStrokeSkeleton]. The glyph is drawn whole and shown through a brush that runs along the strokes in order (the brush a
 * little wider than the pen, so each stroke's own edges show as the font draws them); at the end the brush spreads, so whatever
 * it did not reach fills in. The finished glyph is exactly the text the other looks draw.
 */
private class InkGlyph(val run: GlyphRun, val i: Int, val left: Float, val top: Float, val ink: InkPaths?)

private class InkPaths(
    val full: Path,
    val bounds: Rect,
    val strokes: Array<Path>,
    val measures: Array<PathMeasure>,
    val lengths: FloatArray,
    /** Each stroke's share of the writing: start and length, 0..1. */
    val start: FloatArray,
    val dur: FloatArray,
    val body: Stroke,
    val brushW: Float,
    val brush: Stroke,
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
    /** True when every glyph of the name has strokes to write along (false: it fades and wipes in instead). */
    val written: Boolean
    /** The band behind the words: from 36 dp above them to 40 dp below the caption. */
    val bandTop: Float = top - 36f * dp
    val bandBottom: Float = captionTop + caption.height + 40f * dp
    /** A soft blush band behind the words over a picture (drawn at [BAND_START] of this at first, all of it once developed). */
    val band: Brush = Brush.verticalGradient(
        0f to Sakura.Background.copy(alpha = 0f), .22f to Sakura.Background.copy(alpha = BAND_PEAK),
        .86f to Sakura.Background.copy(alpha = BAND_PEAK), 1f to Sakura.Background.copy(alpha = 0f), startY = bandTop, endY = bandBottom,
    )
    /** Where petals and snow fade out (drawn with DstOut over them): the words' band, soft at every edge. */
    val particleMask: Brush
    private val scratch = Path()
    private val layer = Paint()
    private val maskLayer = Paint().apply { blendMode = BlendMode.DstIn }

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

        val blockW = max(max(eyebrow?.width ?: 0f, hero.width), caption.width)
        val l = lay.textX - blockW / 2f - 28f * dp
        val r = lay.textX + blockW / 2f + 28f * dp
        val feather = 26f * dp
        val t0 = bandTop
        val t1 = bandBottom
        particleMask = object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val black = Color.Black.toArgb()
                val clear = Color.Transparent.toArgb()
                val vf = (feather / (t1 - t0)).coerceIn(0f, .5f)
                val v = android.graphics.LinearGradient(
                    0f, t0, 0f, t1, intArrayOf(clear, black, black, clear), floatArrayOf(0f, vf, 1f - vf, 1f), android.graphics.Shader.TileMode.CLAMP,
                )
                val hf = (feather / (r - l)).coerceIn(0f, .5f)
                val hz = android.graphics.LinearGradient(
                    l - feather, 0f, r + feather, 0f, intArrayOf(clear, black, black, clear), floatArrayOf(0f, hf, 1f - hf, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                return android.graphics.ComposeShader(v, hz, android.graphics.PorterDuff.Mode.DST_IN)
            }
        }
    }

    private fun inkPaths(run: GlyphRun, g: Int, hand: Typeface, x: Float, baseline: Float): InkPaths? {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = hand; textSize = run.fontPx }
        val text = run.text[g]
        val outline = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, x, baseline, outline)
        val sk = OneStrokeSkeleton.of(outline, run.fontPx) ?: return null
        val n = sk.lines.size
        val strokes = Array(n) { j ->
            val pts = sk.lines[j]
            Path().apply { moveTo(pts[0], pts[1]); for (k in 2 until pts.size step 2) lineTo(pts[k], pts[k + 1]) }
        }
        val measures = Array(n) { j -> PathMeasure().apply { setPath(strokes[j], false) } }
        val lengths = FloatArray(n) { measures[it].length }
        // Each stroke takes time for its length (a dot still a visible moment), with a short lift of the pen between strokes.
        val em = run.fontPx
        val weight = FloatArray(n) { max(lengths[it], .1f * em) }
        val gap = .03f * em
        val sum = weight.sum() + gap * (n - 1)
        val start = FloatArray(n)
        val dur = FloatArray(n)
        var acc = 0f
        for (j in 0 until n) {
            start[j] = acc / sum
            dur[j] = weight[j] / sum
            acc += weight[j] + gap
        }
        val b = android.graphics.RectF()
        outline.computeBounds(b, true)
        val thick = run.thick[g]
        val brushW = 2.2f * sk.pen + 1.2f * thick
        val pad = brushW * 2f + thick
        return InkPaths(
            outline.asComposePath(), Rect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad), strokes, measures, lengths, start, dur,
            run.body(g) ?: Stroke(1f, cap = StrokeCap.Round, join = StrokeJoin.Round), brushW,
            Stroke(brushW, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
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
                    drawPath(ink.full, Sakura.Text, style = ink.body)
                    continue
                }
                writeGlyph(ink, q)
            }
        }
        drawTyped(
            caption, lay.textX - caption.width / 2f, captionTop, Sakura.TextSecondary, 1f, ms, t.captionAt, 24f * t.pace,
            hold = 800f, reduced = reduced,
        )
    }

    /** Glyph [ink] [q] of the way written: whole in a layer, kept only where the brush has been (a DstIn layer of its strokes). */
    private fun DrawScope.writeGlyph(ink: InkPaths, q: Float) {
        val k = q / WRITE_PART
        scratch.reset()
        for (s in ink.strokes.indices) {
            val p = ((k - ink.start[s]) / ink.dur[s]).coerceIn(0f, 1f)
            if (p <= 0f) break
            if (p >= 1f) scratch.addPath(ink.strokes[s])
            else ink.measures[s].getSegment(0f, IntroEase.SineInOut.transform(p) * ink.lengths[s], scratch, true)
        }
        // Past the strokes the brush spreads, so the bits it did not reach (a hook's tip, a serif) fill in before the glyph is whole.
        val brush = if (q <= WRITE_PART) ink.brush
        else Stroke(ink.brushW * (1f + 2.5f * (q - WRITE_PART) / (1f - WRITE_PART)), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val canvas = drawContext.canvas
        canvas.saveLayer(ink.bounds, layer)
        drawPath(ink.full, Sakura.Text)
        drawPath(ink.full, Sakura.Text, style = ink.body)
        canvas.saveLayer(ink.bounds, maskLayer)
        drawPath(scratch, Color.Black, style = brush)
        canvas.restore()
        canvas.restore()
    }

    companion object {
        /** The band's strength over the veil at its fullest, and the share of it drawn before the picture develops (.38). */
        const val BAND_PEAK = .62f
        const val BAND_START = .38f / BAND_PEAK
        /** The share of a glyph's window spent on its strokes; the rest the brush spreads. */
        const val WRITE_PART = .86f
    }
}
