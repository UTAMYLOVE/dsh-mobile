package app.dshmobile.ui.fog

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor

/** The four steps of the startup animation, as callers and older tests know them. */
enum class IntroStep { PERMISSION, CHECKING, PASSED, WELCOME }

/** The seven beats of 「櫻紋陣」, in order. */
enum class IntroPhase { IGNITE, ACCESS, IDENTITY, CHARGE, VERIFIED, WELCOME, EXIT }

val IntroPhase.step: IntroStep
    get() = when (this) {
        IntroPhase.IGNITE, IntroPhase.ACCESS -> IntroStep.PERMISSION
        IntroPhase.IDENTITY, IntroPhase.CHARGE -> IntroStep.CHECKING
        IntroPhase.VERIFIED -> IntroStep.PASSED
        else -> IntroStep.WELCOME
    }

/**
 * The beat marks of one run, in ms. They are fixed "punch" times at three anchor lengths (1.5, 3 and 8 s) and interpolated
 * between, so a longer intro only gets longer holds: nothing slows down beyond 3 s ([pace] is 1 there), and below 3 s the punches
 * tighten a little. The old fixed fractions stretched every animation with the length (the critique's "slow motion at 8 s").
 */
class IntroMarks(val a: Float, val b: Float, val c: Float, val hit: Float, val d: Float, val e: Float, val total: Float) {
    val pace: Float = if (total < 3000f) .8f + .2f * (total - 1500f) / 1500f else 1f
    /** Below 2.2 s two beats share one hero line instead of rushing both. */
    val compact: Boolean = total < 2200f
    val scanStart: Float get() = b + 40f * pace
    val scanMs: Float get() = (c - b - 60f * pace).coerceIn(180f, 700f)
    /**
     * When the inner sigil starts to assemble; it always finishes (530 ms × pace) by [c]. From 5 s it starts right after ring A
     * closes, so the rosette assembles during 需要訪問許可: waiting for b left ring A and its ticks alone for over a second, and
     * that read as a wristwatch dial.
     */
    val build: Float get() = if (total >= 5000f) a + 560f * pace else maxOf(a, minOf(b - 100f * pace, c - 540f * pace))

    fun phase(ms: Float): IntroPhase = when {
        ms < a -> IntroPhase.IGNITE
        ms < b -> IntroPhase.ACCESS
        ms < c -> IntroPhase.IDENTITY
        ms < hit -> IntroPhase.CHARGE
        ms < d -> IntroPhase.VERIFIED
        ms < e -> IntroPhase.WELCOME
        else -> IntroPhase.EXIT
    }

    companion object {
        private val A15 = floatArrayOf(40f, 280f, 520f, 620f, 790f, 1220f, 1500f)
        private val A30 = floatArrayOf(100f, 600f, 1060f, 1200f, 1520f, 2620f, 3000f)
        private val A80 = floatArrayOf(260f, 1560f, 3000f, 3160f, 4040f, 7560f, 8000f)

        fun of(totalMs: Int): IntroMarks {
            val t = totalMs.coerceIn(1500, 8000).toFloat()
            val lo: FloatArray
            val hi: FloatArray
            val u: Float
            if (t <= 3000f) { lo = A15; hi = A30; u = (t - 1500f) / 1500f } else { lo = A30; hi = A80; u = (t - 3000f) / 5000f }
            val m = FloatArray(7) { lo[it] + (hi[it] - lo[it]) * u }
            return IntroMarks(m[0], m[1], m[2], m[3], m[4], m[5], m[6])
        }

        /** The reduced-motion frame: the 3 s run's welcome, fully settled, whatever the length. */
        val STILL: IntroMarks = of(3000)
        const val STILL_MS = 2500f
    }
}

/** 0..1 through [dur] ms from [start], eased. Everything in the intro is a pure function of the clock, so any frame can be drawn. */
fun seg(ms: Float, start: Float, dur: Float, e: Easing = LinearEasing): Float =
    if (dur <= 0f) (if (ms >= start) 1f else 0f) else e.transform(((ms - start) / dur).coerceIn(0f, 1f))

object IntroEase {
    val ExpoOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val QuintOut: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val CubicOut: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
    val CubicIn: Easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)
    val CubicInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    val BackOut: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val SineInOut: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
    val EmphDecel: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphAccel: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** 核驗通過's slam over its 280 ms: falls in big, squashes past, rebounds, rests. */
fun slam(u: Float): Float = when {
    u < .55f -> lerp(1.32f, .965f, IntroEase.CubicIn.transform((u / .55f).coerceIn(0f, 1f)))
    u < .78f -> lerp(.965f, 1.012f, IntroEase.CubicOut.transform((u - .55f) / .23f))
    else -> lerp(1.012f, 1f, IntroEase.SineInOut.transform(((u - .78f) / .22f).coerceIn(0f, 1f)))
}

/**
 * [slam] with its overshoot capped at [cap] (at least 1): the line still falls in big, squashes and rebounds, but at its largest
 * it is no wider than [cap] times its rest width, so a long 核驗通過 never runs into the right gutter.
 */
fun slamCapped(u: Float, cap: Float): Float {
    val s = slam(u)
    if (s <= 1f) return s
    val room = (cap.coerceAtLeast(1f) - 1f) / (slam(0f) - 1f)
    return 1f + (s - 1f) * room.coerceAtMost(1f)
}

/** The largest scale [sigilScale] reaches (the pulse right after the hit). */
const val SIGIL_PULSE_MAX = 1.07f

/** The sigil breathes in before the hit (to .95), pulses to 1.07 right after it and settles by +440 ms. */
fun sigilScale(ms: Float, m: IntroMarks): Float = when {
    ms < m.c -> 1f
    ms < m.hit -> lerp(1f, .95f, seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn))
    ms < m.hit + 120f * m.pace -> lerp(.95f, 1.07f, seg(ms, m.hit, 120f * m.pace, IntroEase.CubicOut))
    else -> lerp(1.07f, 1f, seg(ms, m.hit + 120f * m.pace, 320f * m.pace, IntroEase.SineInOut))
}

/**
 * The rosette's twist round 核驗通過 (degrees): it turns back up to 15° while the sigil breathes in (c → hit), then snaps home
 * with a damped swing (+6°, −2°, settled by hit + 480 ms × pace), so the hit reads as a mechanism clicking into place.
 * The idea of a wind-up and damped release comes from linshufan21/dsh-boot-compass (MIT); these numbers are our own.
 */
fun sigilTwist(ms: Float, m: IntroMarks): Float = when {
    ms < m.c -> 0f
    ms < m.hit -> -15f * seg(ms, m.c, m.hit - m.c, IntroEase.CubicIn)
    else -> {
        val u = ((ms - m.hit) / (480f * m.pace)).coerceIn(0f, 1f)
        -15f * exp(-2.6f * u) * cos(3f * PI.toFloat() * u) * (1f - u * u * u * u)
    }
}

/**
 * The hit's one pink/aqua offset flash, 0..0.6, [d] ms after the hit: up in 60 ms, gone by 250 (never paced, once per run).
 * After the RGB-split accent in 6shenhonghong9/dsh-startup-screen and linshufan21/dsh-boot-compass (both MIT; idea only).
 */
fun splitFlash(d: Float): Float = when {
    d < 0f || d >= 250f -> 0f
    d < 60f -> .6f * d / 60f
    else -> .6f * (1f - IntroEase.CubicOut.transform((d - 60f) / 190f))
}

/** The one flash of a run: 60 ms up, 320 ms down. Never paced, so it is the same at every length (and only one per run). */
fun flash(ms: Float, hit: Float, peak: Float): Float {
    val d = ms - hit
    return when {
        d < 0f -> 0f
        d < 60f -> peak * d / 60f
        d < 380f -> peak * (1f - IntroEase.QuintOut.transform((d - 60f) / 320f))
        else -> 0f
    }
}

/**
 * An uneven clock: a [step]° jump in [move] s every [every] s, still in between (a mechanism, not a screensaver spin). 36° is half
 * a petal: the rosette rests on the five heavy ticks, then on the five between them, never on a stray angle.
 */
fun stutter(sec: Float, step: Float = 36f, every: Float = 1.6f, move: Float = .18f): Float {
    val s = sec.coerceAtLeast(0f)
    return step * (floor(s / every) + IntroEase.CubicInOut.transform(((s % every) / move).coerceAtMost(1f)))
}

/** The hit's screen nudge in dp: down 2, up 1.5, back, in 140 ms. */
fun nudge(d: Float): Float = when {
    d < 0f || d >= 140f -> 0f
    d < 40f -> 2f * d / 40f
    d < 90f -> lerp(2f, -1.5f, (d - 40f) / 50f)
    else -> lerp(-1.5f, 0f, (d - 90f) / 50f)
}

object IntroTimeline {
    /** The welcome line: the user's own words (or the default) and the name when there is one. */
    fun welcome(intro: Intro, fallback: String): String {
        val words = intro.welcome.trim().ifEmpty { fallback }
        val name = intro.name.trim()
        return if (name.isEmpty()) words else "$words，$name"
    }
}

/**
 * One beat of a look's [IntroPlan]: a [Move] keeps its own length, so motion is never slowed down; a [Hold] waits, and the holds
 * share whatever time the run has left, by [Hold.weight].
 */
sealed class IntroBeat {
    class Move(val ms: Float) : IntroBeat()
    class Hold(val weight: Float, val minMs: Float) : IntroBeat()

    companion object {
        fun move(ms: Float): IntroBeat = Move(ms)
        fun hold(weight: Float = 1f, minMs: Float = 0f): IntroBeat = Hold(weight, minMs)
    }
}

/**
 * A look's beat marks for one run of [total] ms (the 1.5–8 s setting), the way 櫻紋陣's [IntroMarks] works: the [IntroBeat.Move]s
 * play at their own speed and the [IntroBeat.Hold]s stretch, so 8 s only waits longer between moves. When the moves do not fit
 * (a short run), they all tighten by the same [pace] (< 1) and each hold keeps only its minimum.
 *
 * [at] is when beat i starts, [end] when it ends ([total] after the last one); [k] is a beat's own progress.
 */
class IntroPlan private constructor(private val starts: FloatArray, val total: Float, val pace: Float) {
    val size: Int get() = starts.size
    fun at(i: Int): Float = starts[i]
    fun end(i: Int): Float = if (i + 1 < starts.size) starts[i + 1] else total
    fun length(i: Int): Float = end(i) - at(i)
    /** Beat [i]'s progress at [ms], 0..1, eased by [e]. */
    fun k(ms: Float, i: Int, e: Easing = LinearEasing): Float = seg(ms, at(i), length(i), e)
    /** The beat [ms] falls in (the last one from its start on). */
    fun beat(ms: Float): Int {
        for (i in starts.indices.reversed()) if (ms >= starts[i]) return i
        return 0
    }

    companion object {
        fun of(totalMs: Float, vararg beats: IntroBeat): IntroPlan {
            val total = totalMs.coerceAtLeast(1f)
            val moves = beats.sumOf { (it as? IntroBeat.Move)?.ms?.toDouble() ?: 0.0 }.toFloat()
            val mins = beats.sumOf { (it as? IntroBeat.Hold)?.minMs?.toDouble() ?: 0.0 }.toFloat()
            val weights = beats.sumOf { (it as? IntroBeat.Hold)?.weight?.toDouble() ?: 0.0 }.toFloat()
            val pace = if (moves <= 0f) 1f else ((total - mins) / moves).coerceIn(0f, 1f)
            val spare = (total - mins - moves * pace).coerceAtLeast(0f)
            val starts = FloatArray(beats.size)
            var t = 0f
            for ((i, b) in beats.withIndex()) {
                starts[i] = t
                t += when (b) {
                    is IntroBeat.Move -> b.ms * pace
                    is IntroBeat.Hold -> b.minMs + if (weights > 0f) spare * b.weight / weights else 0f
                }
            }
            // With no hold to take the spare time, the run still ends on time: the last beat absorbs it.
            return IntroPlan(starts, total, pace)
        }
    }
}
