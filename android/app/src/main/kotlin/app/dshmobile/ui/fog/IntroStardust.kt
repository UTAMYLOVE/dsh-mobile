package app.dshmobile.ui.fog

// Adapted from KylinQ01/dsh-startup-animation (c) 2026 KylinQ01, MIT License: the motion values of its welcome (the portrait's
// pop on cubic-bezier(.16, 1.06, .3, 1.22), the burst ring .88 → 2.1 in 1.25 s, sparks flung out over 1.15 s at ± 9°, two ping
// ripples .95 → 1.85 every 4.2 s, 2.1 s apart, orbit rings turning in 7 s and 11 s the other way, the greeting rising 14 px and
// untilting 4° in 620 ms, 80 ms apart, 22° streaks, the halo breathing .96 ↔ 1.04 in 5.2 s, and those cubic-bezier curves). The
// drawing, the colours, the run's timing and the layout are our own; none of its images, names or words.

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「星屑迎接」 (Stardust welcome), the startup look [IntroStyle.STARDUST]. Your own picture (or, without one, the blush sky with a
 * slow pink and aqua aurora) fades in and slowly pushes in; stars twinkle, light motes rise and shooting stars cross it. Your
 * round portrait (the picture's face, or your monogram on a sakura) pops in with a flash ring, a burst ring and sparks, on a disc
 * of frosted light, inside two orbit rings turning opposite ways; 「歡迎回來」 floats up one character at a time, a
 * pink-white-aqua line grows under it and your name stamps in as a sticker; 「馬上就好」 waits in a pill with a bar that is full
 * when the exit starts. Then a soft white bloom rises from the portrait and opens as a round light iris with a rim of stars: inside
 * it the veil is gone and the picture is the home screen's own, and once it fills the screen the host's quick cut shows the app.
 *
 * Every beat sits at a share of the run ([StardustTimeline]), so 1.5 s and 8 s tell the same story; a long run adds middle
 * beats instead of holding one frame. Everything is a pure function of the run's clock, read only in draw lambdas and
 * graphicsLayer blocks, so a run composes once. Reduced motion draws one settled frame with nothing flying.
 */
@Composable
internal fun StardustIntro(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean,
    fx: IntroFx,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val tl = remember(intro.seconds) { StardustTimeline.of(intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000f) }
    // The look's own light iris needs the full layers; on SAVER the host's crossfade is the exit (from the look's exit mark).
    val ownIris = rememberIntroLevel(reducedMotion) != EffectLevel.SAVER && !reducedMotion
    val timing = remember(tl, ownIris) {
        IntroTiming(if (ownIris) tl.hand else tl.exit, tl.total, still = tl.still, hit = tl.hit, announce = tl.announce)
    }
    val line = introWelcomeLine(intro)
    val words = stardustWords(intro.welcome, stringResource(R.string.intro_welcome_default))
    val status = stringResource(R.string.intro_stardust_status)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val res = LocalContext.current.resources
    val coverage = IntroFonts.coverage(res)
    // The home screen shows the picture at the 透明度 setting over the blush; the light iris opens onto exactly that.
    val homeAlpha = (LocalLook.current.opacity / 100f).coerceIn(0f, 1f)
    val homeStyle = LocalLook.current.style
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = line) {
        val sc = this
        val scene = remember(sc, words, intro.name, intro.portrait, status, homeAlpha, homeStyle) {
            StardustScene(sc, tl, measurer, coverage, words, intro.name.trim(), status, intro.portrait, homeAlpha, res, homeStyle)
        }
        val run = sc.run
        // The host's last quick cut onto the app's buttons rises from under the bottom edge, away from the face and the words
        // (from the portrait its outline crossed the face, where the light iris had just shown it).
        run.sceneOrigin = scene.cutOrigin
        // The host nudges the whole screen 2 dp at the hit (FULL only). The sky and its air are held still against it, so only the
        // portrait, its rings and the words shake, and no strip of what lies underneath opens at the top edge.
        val steady = sc.level == EffectLevel.FULL
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { translationY = if (steady) -nudge(run.ms - tl.hit) * scene.dp else 0f }.drawBehind {
                scene.drawBackdrop(this, run.ms, run.amb, run.exitK())
                scene.drawAmbient(this, run.ms, run.amb, run.exitK())
            },
        )
        // SAVER has no iris (the host crossfades): there the portrait and its rings leave with the veil instead of lingering.
        val saver = sc.level == EffectLevel.SAVER && !sc.reduced
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = if (saver) 1f - tl.handK(run.exitK()) else 1f }
                .drawBehind { scene.drawStage(this, run.ms, run.amb, run.exitAt) },
        )
        if (scene.hasNear) Spacer(Modifier.fillMaxSize().drawBehind { scene.drawNear(this, run.ms, run.amb, run.exitK(), run.exitAt) })
        // The exit's bloom: over the portrait, under the words.
        if (!sc.reduced) Spacer(Modifier.fillMaxSize().drawBehind { scene.drawBloom(this, run.ms, run.exitAt) })
        // The words leave as the bloom opens: they turn pink, then white, and grow a little as they fade, so they dissolve into
        // the light (faded straight, the ink went a dusty grey over the pink).
        Spacer(
            Modifier.fillMaxSize().graphicsLayer {
                val f = scene.wordsFade(run.ms, run.exitAt)
                alpha = f
                val grow = 1f + .03f * (1f - f)
                scaleX = grow
                scaleY = grow
                transformOrigin = scene.wordsPivot
            }.drawBehind {
                val ms = run.ms
                scene.drawWords(this, ms, 1f - scene.wordsFade(ms, run.exitAt))
            },
        )
        // The look's own exit: the light iris, the home screen's picture inside it, a rim of stars.
        if (ownIris) Spacer(Modifier.fillMaxSize().drawBehind { scene.drawLift(this, run.ms, run.amb) })
    }
}

/** The greeting that floats up: the user's own words (or 歡迎回來) without a trailing comma or stop, since the name follows below. */
internal fun stardustWords(welcome: String, fallback: String): String =
    welcome.trim().ifEmpty { fallback }.trimEnd(' ', '，', ',', '、', '。', '.', '!', '！', '~', '～').ifEmpty { fallback }

/**
 * The marks of one run, each at a share of the run's length (the art director: a long run must tell its story across the whole
 * time, not play the 3 s story and then hold one frame). The shares are set at 3 s and at 8 s and blended between; at 3 s and
 * below they are the same, so 1.5 s is the 3 s run in half the time. Each move ([str]) stretches with the run, but at most 1.6×
 * (8 s does not play in slow motion) and at least .6×; the impacts ([snap]) stretch less. From 4 s a run adds middle beats: the
 * name's sheen, an echo of the pop at 50 %, a wave of twinkles across the stars at 62 % and a pulse of shooting stars and sparks
 * at 70 %.
 *
 * The exit starts at 77 % (the bloom 180 ms × [str] before it) and takes the rest; at 8 s it is capped at 1.6 × the 3 s one
 * (1.1 s), so it starts at 86 % there. It is the look's own light iris ([liftMs]) and then the host's quick cut onto the app
 * ([handMs], from [hand]), which by then only has the app's buttons left to show.
 */
internal class StardustTimeline private constructor(val total: Float) {
    /** How much longer each move is than at 3 s. */
    val str: Float = (total / 3000f).coerceIn(MIN_STRETCH, MAX_STRETCH)
    /** The impacts' (pop, flash, burst, sparks, streaks) stretch: they stay snappy. */
    val snap: Float = (total / 3000f).coerceIn(.7f, 1.15f)
    private val longer = ((total - 3000f) / 5000f).coerceIn(0f, 1f)
    private fun mark(at3: Float, at8: Float): Float = lerp(at3, at8, longer) * total

    val exitMs: Float = min(EXIT_SHARE * total, EXIT_SHARE * 3000f * MAX_STRETCH)
    /** The look's exit (the bloom's peak, then the light iris) starts here and runs to [total]. */
    val exit: Float = total - exitMs
    /** The host's cut onto the app: the last moments of the run, once the light iris covers the screen. */
    val handMs: Float = min(HAND_MS * snap, .4f * exitMs)
    val hand: Float = total - handMs
    /** The light iris opens from the bloom to past the far corner over this. */
    val liftMs: Float = exitMs - handMs
    /** The portrait's pop peaks here, on the frame the flash ring starts (the haptic tick). */
    val hit: Float = mark(.10f, .09f)
    val popMs: Float = 320f * snap
    val popStart: Float = hit - POP_PEAK * popMs
    /** The orbit rings come in with the flash, not before. */
    val ringsIn: Float = hit - 60f * snap
    val greetStart: Float = mark(.16f, .15f)
    val greetMs: Float = 620f * str
    val nameStart: Float = mark(.30f, .28f)
    /** The gradient line under the greeting grows from its middle. */
    val lineStart: Float = mark(.40f, .35f)
    val lineMs: Float = 520f * str
    val statusIn: Float = mark(.46f, .44f)
    val bloomMs: Float = 180f * str
    val bloomStart: Float = exit - bloomMs
    val sheenMs: Float = 460f * str
    /** A white sheen across the name, when it ends before the bloom (not at 1.5 s), else -1. */
    val sheen: Float = mark(.55f, .55f).takeIf { it + sheenMs <= bloomStart } ?: -1f
    /** From 4 s: a pair of shooting stars and a ring of sparks from the portrait, else -1. */
    val pulse: Float = if (total > 4000f) mark(.64f, .70f) else -1f
    /** From 4 s: a softer echo of the pop (a burst ring and sparks) between the pill and the sheen, else -1. */
    val echo: Float = if (total > 4000f) mark(.48f, .50f) else -1f
    /** From 4 s: a wave of twinkles running across the stars from the left, else -1. */
    val wave: Float = if (total > 4000f) mark(.58f, .62f) else -1f
    val waveMs: Float = 1100f * snap
    /** The words fade over this as the light iris opens. */
    val textFadeMs: Float = 150f * str
    /** Below 2.2 s the name stamps in faster (drawSticker's compact rhythm). */
    val compact: Boolean = total < 2200f
    /** The status line and its bar only when they can be read (for .7 s at least): not at 1.5 s. */
    val showsStatus: Boolean = exit - statusIn >= 700f
    /** The reduced-motion frame: everything in and settled, just before the bloom. */
    val still: Float = bloomStart - 1f
    /** The welcome line is announced as the name comes in. */
    val announce: Float = nameStart

    /** The shooting stars' starts: two across every run, a third from 4 s, and the pulse's pair. */
    val streaks: FloatArray = buildList {
        add(mark(.20f, .20f))
        if (total > 4000f) add(mark(.36f, .36f))
        add(mark(.48f, .46f))
        if (pulse >= 0f) { add(pulse); add(pulse + 90f * snap) }
    }.toFloatArray()
    val streakMs: Float = 700f * snap

    /** Greeting glyph [i]'s start of [n]: 80 ms × [str] apart, all within 360 ms × [str] (a long greeting would trickle). */
    fun greetAt(i: Int, n: Int): Float = greetStart + i * (if (n < 2) 0f else min(80f, 360f / (n - 1))) * str

    /**
     * The portrait's scale: from .6 on the overshoot curve, largest exactly at [hit] (1.08, the curve's own 1.034 plus a short
     * swell centred on the hit), then resting at 1 (0 before it starts).
     */
    fun popScale(ms: Float): Float {
        if (ms < popStart) return 0f
        val base = lerp(POP_FROM, 1f, PopEase.transform(((ms - popStart) / popMs).coerceIn(0f, 1f)))
        val half = .3f * popMs
        val v = (ms - hit + half) / (2f * half)
        return base + if (v in 0f..1f) SWELL * sin(PI.toFloat() * v) else 0f
    }

    /** The camera's settling on the home screen's crop before the light iris (0..1, done at [exit]). */
    fun settle(ms: Float): Float = seg(ms, exit - SETTLE_MS * str, SETTLE_MS * str, IntroEase.SineInOut)

    /** The light iris's opening, 0..1, from [exit] over [liftMs]. */
    fun lift(ms: Float): Float = seg(ms, exit, liftMs, IntroEase.CubicInOut)

    /**
     * The words' strength on exit (1 → 0): over [textFadeMs] from [exit], or from a tap before it ([exitAt] on the run's clock,
     * -1 for none).
     */
    fun wordsFade(ms: Float, exitAt: Float = -1f): Float {
        val from = if (exitAt in 0f..exit) exitAt else exit
        return 1f - seg(ms, from, textFadeMs)
    }

    /** The portrait's opacity: whole by half way to the peak, so the flash rings round a face, never wash out a ghost. */
    fun popAlpha(ms: Float): Float = seg(ms, popStart, .5f * POP_PEAK * popMs)

    /** How far a hand-off through the host's iris or crossfade is (0..1): a tap's, SAVER's, or the last quick cut. */
    fun handK(exitK: Float): Float = IntroEase.CubicInOut.transform(exitK)

    /**
     * The camera: from 1.02 it pushes in slowly (up to 1.07, less in a short run), then eases back to 1 as [hk] goes to 1 (the
     * settling before the light iris, or a hand-off), which is exactly the home screen's crop. 1 for reduced motion.
     */
    fun zoom(ms: Float, hk: Float, reduced: Boolean): Float {
        if (reduced) return 1f
        val push = PUSH * (exit / 2300f).coerceIn(.4f, 1f)
        return lerp(1.02f + push * seg(ms, 0f, exit, IntroEase.SineInOut), 1f, hk)
    }

    /** When the bloom starts: [bloomStart], or at a tap that came before it ([exitAt] on the run's clock, -1 for none). */
    fun bloomFrom(exitAt: Float): Float = if (exitAt in 0f..bloomStart) exitAt else bloomStart

    /**
     * The exit's soft white bloom from the portrait, 0..1: up over [bloomMs] to the exit, then down as the light iris opens.
     * A tap before it gets a quick one (60 ms up, 200 down) from the tap.
     */
    fun bloom(ms: Float, exitAt: Float = -1f): Float {
        if (exitAt in 0f..bloomStart) return seg(ms, exitAt, 60f) * (1f - seg(ms, exitAt + 60f, 200f, IntroEase.CubicOut))
        return seg(ms, bloomStart, bloomMs, IntroEase.SineInOut) * (1f - seg(ms, exit, .5f * exitMs, IntroEase.CubicOut))
    }

    /** The bloom's rise alone (0..1, staying at 1): the aqua and the monogram's ink fade under it (either washed by it read grey). */
    fun bloomUp(ms: Float, exitAt: Float = -1f): Float =
        if (exitAt in 0f..bloomStart) seg(ms, exitAt, 60f) else seg(ms, bloomStart, bloomMs)

    /** The status bar's fill: from the pill's arrival to full exactly when the exit starts (.92 for the still). */
    fun barFill(ms: Float): Float = seg(ms, statusIn, exit - statusIn, IntroEase.SineInOut)

    /** The pop's gust through the petals (0..1), so they swirl when the portrait lands. */
    fun gust(ms: Float): Float = seg(ms, hit, 1100f * snap, IntroEase.CubicOut)

    companion object {
        const val MIN_STRETCH = .6f
        const val MAX_STRETCH = 1.6f
        /** The exit's share of the run: the light iris opens at 77 %. */
        const val EXIT_SHARE = .23f
        /** The host's cut at 3 s (× [snap]); at most .4 of the exit. */
        const val HAND_MS = 180f
        /** The camera settles on the home crop over this (× [str]) before the light iris. */
        const val SETTLE_MS = 520f
        const val POP_FROM = .6f
        /** What the swell adds at the hit: the portrait peaks at 1.08. */
        const val SWELL = .046f
        const val PUSH = .05f
        /** The portrait's pop curve, cubic-bezier(.16, 1.06, .3, 1.22): a quick overshoot (to 1.034 of full size), then back. */
        val PopEase: Easing = CubicBezierEasing(.16f, 1.06f, .3f, 1.22f)
        /** Where in the pop [PopEase] peaks (about .57). */
        val POP_PEAK: Float = run {
            var best = 0f
            var at = 0f
            for (i in 0..1000) {
                val u = i / 1000f
                val v = PopEase.transform(u)
                if (v > best) { best = v; at = u }
            }
            at
        }

        fun of(totalMs: Float): StardustTimeline = StardustTimeline(totalMs.coerceIn(Intro.MIN_SECONDS * 1000f, Intro.MAX_SECONDS * 1000f))
    }
}

/**
 * Where everything sits, from the screen alone. Upright, the name's baseline is at 60 % of the height and the rest stacks up from
 * it: the greeting's ink [gap2] above the name's, the outer ring [gap1] above the greeting (the greeting groups with the name,
 * not with the portrait), the status pill centred at 75 %. On a wide screen the portrait is on the left and the type's column on
 * the right. [s] scales every dp (and sp) to the screen.
 */
internal class StardustLayout(val w: Float, val h: Float, val dp: Float, insetTop: Float, insetBottom: Float, hasName: Boolean = true) {
    val wide: Boolean = w > h * 1.15f
    val top: Float = insetTop
    val bottom: Float = h - insetBottom
    val usable: Float = (bottom - top).coerceAtLeast(1f)
    val s: Float = if (wide) (usable / dp / 420f).coerceIn(.6f, 1.2f) else min(w / dp / 412f, usable / dp / 820f).coerceIn(.6f, 1.25f)
    val r: Float = 62f * dp * s
    /** The white ring round the portrait. */
    val rim: Float = 5f * dp * s
    val ring1: Float = r + 22f * dp * s
    val ring2: Float = r + 44f * dp * s
    /** From the outer ring to the greeting's ink, and (.6 of it) from the greeting's ink to the name's. */
    val gap1: Float = 40f * dp * s
    val gap2: Float = .6f * gap1
    val greetSp: Float = if (hasName) 34f else 46f
    val nameSp: Float = if (wide) 56f else 72f
    /** Upright: the name's (first) baseline. */
    val nameBase: Float = top + usable * NAME_AT
    /** Upright: the bottom of the greeting's ink, [gap2] over the name's ink (no name: the greeting alone at 56 %). */
    val greetInkBottom: Float =
        if (hasName) nameBase - NAME_INK * nameSp * s * dp - gap2 else top + usable * GREET_ALONE_AT
    val c: Offset = if (wide) {
        Offset(w * .29f, top + usable * .5f)
    } else {
        val y = greetInkBottom - (INK_TOP + INK_BOTTOM) * greetSp * s * dp - gap1 - ring2
        Offset(w / 2f, max(y, top + ring2 + 12f * dp))
    }
    val textX: Float = if (wide) w * .67f else w / 2f
    val textW: Float = if (wide) w * .44f else min(w - 48f * dp, 400f * dp * s)
    /** Upright: the status pill's middle. */
    val pillMid: Float = top + usable * PILL_AT

    companion object {
        const val NAME_AT = .60f
        const val GREET_ALONE_AT = .56f
        const val PILL_AT = .75f
        /** The pen's ink over its baseline, in em: 歡 rises .80 and drops .094; a name's ink rises about .70. */
        const val INK_TOP = .80f
        const val INK_BOTTOM = .094f
        const val NAME_INK = .70f
    }
}

/**
 * How many of each moving thing a run draws, by the effect level (and the user's 數量 and 星光 for the ambient ones): FULL on a
 * strong phone, fewer on BALANCED, few on SAVER and for the reduced-motion still (which has no sparks, they only fly). [top] are
 * extra small stars in the upper third, [twinkles] the fine ones drifting along the outer orbit ring. The season is plenty, so it
 * reads in one frame: snow in two depths, or tumbling petals, plus [bigs] large soft-focus ones in front (none on SAVER).
 */
internal class StardustCounts(
    val stars: Int, val motes: Int, val sparks: Int, val petals: Int, val season: Int, val top: Int = 0, val twinkles: Int = 0, val bigs: Int = 0,
) {
    val total: Int get() = stars + top + twinkles + motes + sparks + petals + season + bigs

    companion object {
        fun of(level: EffectLevel, fx: IntroFx, reduced: Boolean): StardustCounts = StardustCounts(
            stars = if (fx.sparkles) introCount(level, fx, 28, 18, 9, reduced) else 0,
            motes = introCount(level, fx, 12, 8, 4, reduced),
            sparks = if (reduced) 0 else when (level) { EffectLevel.FULL -> 10; EffectLevel.BALANCED -> 8; EffectLevel.SAVER -> 6 },
            petals = introCount(level, fx, 7, 5, 3, reduced),
            season = when (fx.season) {
                IntroSeason.SNOW -> introCount(level, fx, 48, 32, 16, reduced)
                IntroSeason.SAKURA -> if (fx.petals) introCount(level, fx, 36, 24, 12, reduced) else 0
                IntroSeason.NONE -> 0
            },
            top = if (fx.sparkles) introCount(level, fx, 14, 9, 4, reduced) else 0,
            twinkles = if (fx.sparkles) introCount(level, fx, 16, 10, 6, reduced) else 0,
            bigs = when {
                fx.season == IntroSeason.NONE || (fx.season == IntroSeason.SAKURA && !fx.petals) || fx.scale <= 0f -> 0
                reduced -> 2
                else -> when (level) { EffectLevel.FULL -> 3; EffectLevel.BALANCED -> 2; EffectLevel.SAVER -> 0 }
            },
        )
    }
}

/** The words of a run, measured once at the layout's scale. */
internal class StardustTexts(
    measurer: TextMeasurer, density: Float, fontScale: Float, private val lay: StardustLayout, private val coverage: IntArray, words: String,
    name: String, status: String, res: Resources? = null,
) {
    private val mm = IntroMeasure(measurer, density, fontScale, lay.s, coverage)
    /** How much smaller a fallback character is drawn, so its ink stands as tall as the pen's (1 when it cannot be measured). */
    private val fallbackScale: Float = res?.let { fallbackInkRatio(it) } ?: 1f
    val hasName: Boolean = name.isNotEmpty()
    /** 歡迎回來: at 34 sp over the name (46 when there is no name), smaller only when long. */
    val greet: GlyphRun = mm.fit(words, IntroFonts.hand(lay.greetSp), .08f, lay.greetSp, 22f, lay.textW).also { matchPen(it) }
    /** What still does not fit at 22 sp is drawn smaller as a whole. */
    val greetScale: Float = if (greet.width > lay.textW) lay.textW / greet.width else 1f
    /** A white outline and a thin blush line inside it: ink anti-aliased straight onto white made a grey fringe. */
    val greetOutline: Stroke = stickerStroke(greet, .10f)
    val greetInline: Stroke = Stroke(1.4f * density + greet.thickPx, join = StrokeJoin.Round)
    val sticker: IntroSticker? =
        if (hasName) IntroSticker(mm, name, lay.textW, lay.nameSp, 32f, density).also { st -> st.lines.forEach { matchPen(it) } } else null
    /**
     * 馬上就好, in the same thickened handwriting as everything else on screen (one Chinese typeface), in the lighter brown. The
     * words are chosen for the pen: in it 正在準備你的工作台 broke down (準 a scribble, 的 a ㄅㄋ) at this size.
     */
    val status: GlyphRun = mm.run(status, IntroFonts.hand(STATUS_SP, small = true), .06f)
    /** The portrait without a picture: the name's first character, or the sakura alone (no name, or an emoji first). */
    val monogram: GlyphRun? = graphemes(name).firstOrNull()?.takeIf { !isEmoji(it) }?.let {
        mm.run(if (isLatin(it)) it.uppercase() else it, IntroFonts.hand(62f), 0f).also { m -> matchPen(m) }
    }
    val monogramStroke: Stroke? = monogram?.let { stickerStroke(it) }
    val monogramInline: Stroke? = monogram?.let { Stroke(1.4f * density + it.thickPx, join = StrokeJoin.Round) }

    /**
     * A character the pen does not have (a name's 桜 or kana) falls back to the system's gothic, which stood taller and heavier
     * than the handwriting beside it: the sticker looked set in two fonts. Each one is measured again smaller, so its ink is as
     * tall as the pen's ([fallbackScale]), centred in its place and on the same baseline, with a little of the pen's thickening.
     */
    private fun matchPen(run: GlyphRun) {
        if (fallbackScale >= .995f) return
        val sp = run.fontPx / (mm.pxPerSp * lay.s)
        val style = IntroFonts.hand(sp)
        for (i in 0 until run.size) {
            val g = run.text[i]
            if (run.emoji[i] || g.isBlank() || IntroFonts.covers(coverage, g)) continue
            val small = mm.one(g, style, sp * fallbackScale)
            val adv = if (small.lineCount > 0) small.getLineRight(0) - small.getLineLeft(0) else 0f
            run.x[i] += (run.advance[i] - adv) / 2f
            run.glyphs[i] = small
            run.dy[i] = run.baseline - small.firstBaseline
            run.thick[i] = FALLBACK_INK * IntroFonts.thickEm(sp * lay.s) * run.fontPx * fallbackScale
        }
    }

    companion object {
        const val STATUS_SP = 20f
        /** A fallback character gets this share of the pen's thickening: none read lighter than the pen, all of it heavier. */
        const val FALLBACK_INK = .2f

        /** The pen's ink height over the system font's, for a few common ideographs both have (the pen's are a little smaller). */
        fun fallbackInkRatio(res: Resources): Float? = runCatching {
            val pen = res.getFont(R.font.intro_hand)
            val probe = "國永東"
            fun ink(tf: Typeface): Float {
                val p = android.graphics.Paint().apply { typeface = tf; textSize = 200f }
                val r = android.graphics.Rect()
                p.getTextBounds(probe, 0, probe.length, r)
                return r.height().toFloat()
            }
            val sys = ink(Typeface.DEFAULT)
            if (sys <= 0f) null else (ink(pen) / sys).coerceIn(.72f, 1f)
        }.getOrNull()
    }
}

/** The backdrop's focus (0..1 of the picture) and where on the screen it goes (0..1), [hk] into the hand-off. */
internal class StardustFocus(val fx: Float, val fy: Float, val tx: Float, val ty: Float)

/**
 * Where the picture's crop is held, [hk] into a hand-off through the host's iris (a tap, or SAVER's crossfade; the look's own
 * light iris draws the home crop itself). Upright, on the face ([faceY]) while the camera pushes in, easing to the home screen's
 * own crop ([homeY], ContentScale.Crop with the 對齊 bias). On a wide screen the face is put right behind the portrait ([portX],
 * [portY] of the screen), where the frosted disc covers it: in the middle it showed a second, giant face beside the words.
 */
internal fun stardustFocus(wide: Boolean, faceY: Float, homeY: Float, hk: Float, portX: Float = .29f, portY: Float = .5f): StardustFocus =
    if (wide) StardustFocus(.5f, lerp(faceY, homeY, hk), lerp(portX, .5f, hk), lerp(portY, homeY, hk))
    else lerp(faceY, homeY, hk).let { StardustFocus(.5f, it, .5f, it) }

/**
 * How much more a wide screen zooms in so the picture's face ([fx], [fy]) can sit at ([tx], [ty]) of the screen with the screen
 * still covered (1 when it can already; at most 1.8). A tall picture across a wide screen has no room to move sideways otherwise.
 */
internal fun stardustWideZoom(iw: Float, ih: Float, w: Float, h: Float, fx: Float, fy: Float, tx: Float, ty: Float): Float {
    val k0 = max(w / iw, h / ih)
    val need = maxOf(
        (w - tx * w) / ((1f - fx) * iw), tx * w / (fx * iw), (h - ty * h) / ((1f - fy) * ih), ty * h / (fy * ih),
    )
    return (need / k0).coerceIn(1f, 1.8f)
}

/**
 * Before the status pill arrives (and for a whole 1.5 s run, which has none) the group stands [rest] px lower, on the screen's
 * optical centre instead of in its top 60 %; it glides up as the pill comes in. 0 for the reduced-motion still (the pill shows).
 */
internal fun stardustShift(ms: Float, tl: StardustTimeline, showsStatus: Boolean, rest: Float, reduced: Boolean): Float = when {
    !showsStatus -> rest
    reduced -> 0f
    else -> rest * (1f - seg(ms, tl.statusIn - 100f * tl.str, 560f * tl.str, IntroEase.SineInOut))
}

/**
 * One run's drawing: built once per screen and words (a remember keyed on the IntroScope), drawn from draw lambdas. Every moving
 * thing is a pure function of the clock and a fixed hash ([ih]), and a frame allocates nothing.
 */
internal class StardustScene(
    sc: IntroScope, val tl: StardustTimeline, measurer: TextMeasurer, coverage: IntArray, words: String, name: String, status: String,
    portrait: Boolean, private val homeAlpha: Float = .8f, res: Resources? = null, homeStyle: BackdropStyle = BackdropStyle.COSY,
) {
    val dp: Float = sc.density.density
    val lay = StardustLayout(sc.widthPx, sc.heightPx, dp, sc.insetTop, sc.insetBottom, name.isNotEmpty())
    val texts = StardustTexts(measurer, dp, sc.density.fontScale, lay, coverage, words, name, status, res)
    private val reduced = sc.reduced
    /** The look's own light iris plays the exit (not on SAVER, where the host crossfades, nor for the still). */
    private val ownIris = sc.level != EffectLevel.SAVER && !reduced
    private val w = sc.widthPx
    private val h = sc.heightPx
    private val s = lay.s
    private val pic: ImageBitmap? = sc.picture
    private val sky = introSky(h)
    private val place = Placement()
    private val homePlace = Placement()
    private val faceY = faceFocus(sc.align)
    private val homeY = (1f + sc.align.bias) / 2f
    /** A wide screen zooms in further, so the face can sit behind the portrait (see [stardustFocus]). */
    private val wideZoom = pic?.takeIf { lay.wide }?.let {
        stardustWideZoom(it.width.toFloat(), it.height.toFloat(), w, h, .5f, faceY, lay.c.x / w, lay.c.y / h)
    } ?: 1f
    /** The portrait: the picture's face cropped round (with 歡迎畫面用背景圖當頭像 on), else the monogram plate. */
    private val face: ImageBitmap? = pic?.takeIf { portrait }?.let { faceCrop(it, (2f * lay.r).roundToInt(), faceY) }
    /**
     * A blurred copy of the picture (about 3.5 dp a pixel on screen, then a soft box blur), drawn behind the portrait as frosted
     * glass: the picture's own face sat right behind the portrait, so the face showed twice. Not on SAVER (a layer every frame).
     */
    private val soft: ImageBitmap? = pic?.takeIf { sc.level != EffectLevel.SAVER }?.let { p ->
        val k = max(w / p.width, h / p.height) * (if (lay.wide) wideZoom else 1f)
        frostCopy(p, (p.width * k / (FROST_DP * dp)).roundToInt().coerceAtLeast(8), FROST_BLUR)
    }
    private val softR = lay.ring2 * 1.55f

    // ---- the type's places ----
    private val greetK = texts.greet.fontPx * texts.greetScale
    private val greetTop: Float
    private val stickerTop: Float
    private val lineY: Float
    private val textBottom: Float
    private val pillTop: Float
    private val pillH: Float
    private val statusTop: Float
    val showsStatus: Boolean

    // The status pill: the words and three dots, the bar under them.
    private val dotGap = 7f * dp * s
    private val dotsW = 6f * dp * s + 2f * dotGap
    private val statusW = texts.status.width + 6f * dp * s + dotsW
    private val barW = max(min(lay.textW * .6f, 176f * dp * s), statusW * 1.1f)
    private val barH = 4f * dp * s
    private val pillW = max(statusW, barW) + 44f * dp * s

    init {
        val g = texts.greet
        val st = texts.sticker
        val inkUnder = (g.baseline + StardustLayout.INK_BOTTOM * g.fontPx) * texts.greetScale
        // The pill: 9 dp over the words' box, the bar 8 dp under it, 12 dp under the bar.
        pillH = 9f * dp * s + texts.status.height + 8f * dp * s + barH + 12f * dp * s
        val nameDrop = if (st != null) lay.gap2 + StardustLayout.NAME_INK * st.fontPx else 0f
        val nameUnder = if (st != null) st.height - .92f * st.fontPx else 0f
        val greetInkBottom = if (lay.wide) {
            // The column (the greeting's ink, the name, the pill) centred on the screen's height.
            val block = (StardustLayout.INK_TOP + StardustLayout.INK_BOTTOM) * greetK + nameDrop + nameUnder + 26f * dp * s + pillH
            lay.top + (lay.usable - block) / 2f + (StardustLayout.INK_TOP + StardustLayout.INK_BOTTOM) * greetK
        } else {
            lay.greetInkBottom
        }
        greetTop = greetInkBottom - inkUnder
        lineY = greetInkBottom + lay.gap2 / 2f
        val nameBase = greetInkBottom + nameDrop
        stickerTop = nameBase - (if (st != null) .92f * st.fontPx else 0f)
        textBottom = if (st != null) stickerTop + st.height else greetInkBottom
        val after = textBottom + 26f * dp * s
        pillTop = if (lay.wide) after else max(lay.pillMid - pillH / 2f, after)
        statusTop = pillTop + 9f * dp * s
        showsStatus = (tl.showsStatus || reduced) && pillTop + pillH <= lay.bottom - 56f * dp
    }

    // ---- the group's glide (see stardustShift): upright everything moves; wide, only the type's column ----
    private val restShift = if (lay.wide) (26f * dp * s + pillH) / 2f else SHIFT_DP * dp * s
    private fun shift(ms: Float): Float = stardustShift(ms, tl, showsStatus, restShift, reduced)
    /** The portrait and its rings' drop at [ms]. */
    fun stageDy(ms: Float): Float = if (lay.wide) 0f else shift(ms)
    /** The type's drop at [ms]. */
    fun wordsDy(ms: Float): Float = shift(ms)
    /** Where the portrait stands once the group has settled: the light iris opens there. */
    val irisOrigin: Offset = lay.c + Offset(0f, stageDy(tl.hand))
    /** Under the bottom edge's middle: the host's quick cut onto the app rises from there. */
    val cutOrigin: Offset = Offset(w / 2f, h + 24f * dp)

    private val barLeft = lay.textX - barW / 2f
    private val barTop = statusTop + texts.status.height + 8f * dp * s
    private val barFill = Brush.horizontalGradient(
        0f to Sakura.Primary, 1f to lerpColor(Sakura.Primary, Sakura.White, .45f), startX = barLeft, endX = barLeft + barW,
    )

    /** The widest line of the type (the greeting or the name). */
    private val textBlockW = max(texts.greet.width * texts.greetScale, texts.sticker?.width ?: 0f)

    /** The type's block, which the stars keep clear of. */
    private val textRect = Rect(lay.textX - lay.textW / 2f, greetTop - 8f * dp, lay.textX + lay.textW / 2f, textBottom + 10f * dp)

    /** The words' pivot as they grow on exit: the middle of the type's block. */
    val wordsPivot: TransformOrigin = TransformOrigin(lay.textX / w, ((greetTop + textBottom) / 2f + wordsDy(tl.exit)) / h)

    // The gradient line under the greeting: pink at the left, white in the middle, aqua at the right (pink to aqua only through
    // white, never grey), on a wider white line.
    private val lineW = max(texts.greet.width * texts.greetScale * .92f, 96f * dp * s).coerceAtMost(lay.textW)
    private val lineBrush = Brush.horizontalGradient(
        0f to Sakura.Primary, .30f to lerpColor(Sakura.Primary, Sakura.White, .45f), .5f to Sakura.White,
        .70f to lerpColor(Sakura.Aqua, Sakura.White, .45f), 1f to Sakura.Aqua,
        startX = lay.textX - lineW / 2f, endX = lay.textX + lineW / 2f,
    )
    private val lineUnder = 5.5f * dp * s
    private val lineCore = 2.4f * dp * s

    // The picture's veil: graded, light over the top third (the picture's own scene shows there), strongest behind the type's
    // column; the white glows behind the portrait and the type carry the reading locally.
    private val veilGrade: Brush = if (lay.wide) {
        Brush.horizontalGradient(
            0f to Sakura.Background.copy(alpha = .08f), .42f to Sakura.Background.copy(alpha = .08f),
            .58f to Sakura.Background.copy(alpha = .22f), 1f to Sakura.Background.copy(alpha = .24f), startX = 0f, endX = w,
        )
    } else {
        Brush.verticalGradient(
            0f to Sakura.Background.copy(alpha = .03f), .33f to Sakura.Background.copy(alpha = .03f),
            .48f to Sakura.Background.copy(alpha = .20f), .80f to Sakura.Background.copy(alpha = .24f),
            1f to Sakura.Background.copy(alpha = .30f), startY = 0f, endY = h,
        )
    }
    private val veilC = if (lay.wide) Offset(lay.textX, h / 2f) else Offset(lay.textX, (greetTop + (pillTop + pillH)) / 2f + wordsDy(tl.exit))
    private val veilRx = if (lay.wide) .40f * w else .74f * w
    private val veilRy = if (lay.wide) .80f * h else .26f * h

    private fun clear(x: Float, y: Float): Boolean {
        val dx = x - lay.c.x
        val dy = y - (lay.c.y + .5f * stageDy(0f))
        val keep = lay.ring2 + 14f * dp + .5f * stageDy(0f)
        if (dx * dx + dy * dy < keep * keep) return false
        return !Rect(textRect.left, textRect.top, textRect.right, textRect.bottom + wordsDy(0f)).inflate(10f * dp).contains(Offset(x, y))
    }

    // ---- particles: counts by the effect level (and the user's 數量) ----
    private val counts = StardustCounts.of(sc.level, sc.fx, reduced)
    /** The stars: [StardustCounts.stars] over the whole screen, then [StardustCounts.top] more in the upper third. */
    private val starN = counts.stars + counts.top
    private val starX = FloatArray(starN)
    private val starY = FloatArray(starN)
    /** Mostly small, every fifth a big one; the upper third's extra ones small. */
    private val starR = FloatArray(starN) {
        val big = it < counts.stars && it % 5 == 0
        (if (big) 5f + 2.5f * ih(it, 203) else if (it < counts.stars) 2f + 2.4f * ih(it, 203) else 1.6f + 1.8f * ih(it, 203)) * dp * s
    }

    init {
        for (i in 0 until starN) {
            var x = 0f
            var y = 0f
            val upper = i >= counts.stars
            for (t in 0 until 6) {
                x = (.04f + .92f * ih(i, 201 + 17 * t)) * w
                y = if (upper) (.03f + .30f * ih(i, 202 + 17 * t)) * h else (.04f + .90f * ih(i, 202 + 17 * t)) * h
                if (clear(x, y)) break
            }
            starX[i] = x
            starY[i] = y
        }
    }

    private val moteN = counts.motes
    private val sparkN = counts.sparks
    private val twinkleN = counts.twinkles
    private val driftN = counts.petals
    private val petals = sc.fx.petals
    private val kawaiiPetal = Kawaii.petalPath()
    private val season = StardustSeason(w, h, dp, sc.fx.season, counts.season, counts.bigs, sc.fx) { x, y -> aquaT(x, y) }
    /** The season's near layer (bigger flakes or petals, the big soft ones) is drawn in front of the portrait. */
    val hasNear: Boolean = season.active

    // ---- shapes and strokes, built once ----
    private val sparkle = Kawaii.sparklePath()
    private val sakura = sakuraSilhouette()
    private val veins = Path().apply {
        for (i in 0 until 5) {
            val a = Math.toRadians(-90.0 + 72.0 * i)
            moveTo((cos(a) * .16).toFloat(), (sin(a) * .16).toFloat())
            lineTo((cos(a) * .52).toFloat(), (sin(a) * .52).toFloat())
        }
    }
    private val faceClip = Path().apply { addOval(Rect(lay.c, lay.r)) }
    /** The portrait with its rim (and its 2 dp bob and 8 % swell), which the season's near layer passes behind. */
    private val portraitHole = Path().apply { addOval(Rect(lay.c, (lay.r + lay.rim) * 1.08f + 4f * dp)) }
    private val hair = Stroke(1f * dp)
    private val arcBase = Stroke(5.5f * dp * s, cap = StrokeCap.Round)
    /** The white under the aqua arc: wide enough that the aqua's soft edge never reaches the pink. */
    private val arcWide = Stroke(6.5f * dp * s, cap = StrokeCap.Round)
    private val arcCore = Stroke(2.6f * dp * s, cap = StrokeCap.Round)
    private val arcThin = Stroke(2f * dp * s, cap = StrokeCap.Round)
    private val ringLine = Stroke(1.5f * dp)
    private val ringGlow = Stroke(4f * dp)
    /** The burst ring: a white core with a pink edge outside it, so it reads on the light sky. */
    private val burstCore = Stroke(3.5f * dp)
    private val burstEdge = Stroke(2f * dp)
    private val plateLine = Stroke(1f * dp / (.78f * lay.r), join = StrokeJoin.Round)
    private val veinLine = Stroke(1.25f * dp / (.78f * lay.r), cap = StrokeCap.Round)
    /** The outer ring's dots, round the origin (drawn turned, at the portrait's centre). */
    private val ringDots: List<Offset> = List(72) { i ->
        val a = i * (2f * PI.toFloat() / 72f)
        Offset(cos(a) * lay.ring2, sin(a) * lay.ring2)
    }
    // The light iris's rim: soft white bands in and out of the edge (a pink line on it read grey over the sky's aqua).
    private val rimHaze = Stroke(44f * dp)
    private val rimGlow = Stroke(18f * dp)
    private val rimCore = Stroke(6f * dp)
    private val rimEdge = Stroke(1.6f * dp)
    private val liftClip = Path()
    /** Past the farthest corner from the portrait, with the rim's haze: when the light iris is this big it covers the screen. */
    private val liftFar: Float = run {
        val o = irisOrigin
        maxOf(hypot(o.x, o.y), hypot(w - o.x, o.y), hypot(o.x, h - o.y), hypot(w - o.x, h - o.y)) + 40f * dp
    }
    private val rimStarN = if (ownIris) (if (sc.level == EffectLevel.FULL) 22 else 14) else 0
    private val homeGlows = if (ownIris) StardustHomeGlows(w, h, dp, homeStyle) else null
    private val layerPaint = Paint()
    private val washPaint = Paint()
    private val monoPaint = Paint()

    // ---- layer 1: the backdrop ----

    /**
     * The picture (pushing in on the face) under a light welcome scrim and a blush veil graded from light at the top to strongest
     * behind the type, with frosted glass behind the portrait and white glows behind the portrait and the type. A hand-off through
     * the host's iris ([exitK]: a tap, or SAVER's crossfade) eases the camera, the veil and the scrim to the home screen's own.
     * Without a picture: the blush sky and its aurora.
     */
    fun drawBackdrop(scope: DrawScope, ms: Float, amb: Float, exitK: Float) = with(scope) {
        val hk = tl.handK(exitK)
        // Before the light iris opens the camera settles on the home screen's own crop, so the iris only lifts the veil: the
        // picture inside it and outside it is the same, with no jump in crop or scale at its edge.
        val cam = if (ownIris) max(hk, tl.settle(ms)) else hk
        val inK = if (reduced) 1f else seg(ms, 0f, 360f * tl.str, IntroEase.SineInOut)
        val veil = inK * (1f - hk)
        // The frosted disc comes in with the portrait's halo and its burst, never before them.
        val frost = (if (reduced) 1f else seg(ms, tl.popStart, 420f * tl.snap)) * (1f - hk)
        val sdy = stageDy(ms)
        // Opaque first, and a little larger than the screen, so nothing underneath ever shows at an edge.
        val pad = 4f * dp
        drawRect(Sakura.Background, Offset(-pad, -pad), Size(w + 2f * pad, h + 2f * pad))
        val p = pic
        if (p == null) {
            drawRect(sky, alpha = veil)
            aurora(amb, veil, aqua = true)
            glows(.6f * veil, ms)
            return@with
        }
        val f = stardustFocus(lay.wide, faceY, homeY, cam, lay.c.x / w, (lay.c.y + sdy) / h)
        val z = tl.zoom(ms, cam, reduced) * lerp(wideZoom, 1f, cam)
        cover(p.width.toFloat(), p.height.toFloat(), w, h, z, f.fx, f.fy, f.tx * w, f.ty * h, place)
        val a = homeAlpha * inK
        withTransform({ translate(place.ox, place.oy); scale(place.k, place.k, Offset.Zero) }) { drawImage(p, alpha = a) }
        if (frost > 0f) frost(p, a, frost, sdy)
        drawRect(WelcomeScrim, alpha = inK * (1f - cam))
        if (cam > 0f) drawRect(HomeScrim, alpha = inK * cam)
        if (veil <= 0f) return@with
        drawRect(veilGrade, alpha = veil)
        withTransform({ translate(veilC.x, veilC.y); scale(veilRx, veilRy, Offset.Zero) }) { drawCircle(VeilCore, 1f, Offset.Zero, alpha = veil) }
        aurora(amb, .25f * veil, aqua = false)
        glows(.8f * veil, ms)
    }

    /**
     * The frosted disc behind the portrait: the blurred copy of the picture (in place over it, on the same blush), washed half
     * white and faded out round its edge by a clean radial mask, so at every moment it reads as soft white glass, never as a
     * smudge. [k] is its strength.
     */
    private fun DrawScope.frost(p: ImageBitmap, alpha: Float, k: Float, dy: Float) {
        val sp = soft ?: return
        val c = lay.c + Offset(0f, dy)
        val big = softR
        drawIntoCanvas { canvas ->
            layerPaint.alpha = k
            canvas.saveLayer(Rect(c.x - big, c.y - big, c.x + big, c.y + big), layerPaint)
            drawRect(Sakura.Background, Offset(c.x - big, c.y - big), Size(2f * big, 2f * big))
            drawImage(
                sp, IntOffset.Zero, IntSize(sp.width, sp.height),
                IntOffset(place.ox.roundToInt(), place.oy.roundToInt()),
                IntSize((p.width * place.k).roundToInt(), (p.height * place.k).roundToInt()),
                alpha = alpha, filterQuality = FilterQuality.High,
            )
            drawRect(Sakura.White, Offset(c.x - big, c.y - big), Size(2f * big, 2f * big), alpha = FROST_WHITE)
            // The mask over the whole layer: a circle drawn with DstIn left the square's corners untouched (a frosted square).
            withTransform({ translate(c.x, c.y); scale(big, big, Offset.Zero) }) {
                drawRect(SoftMask, Offset(-1f, -1f), Size(2f, 2f), blendMode = BlendMode.DstIn)
            }
            canvas.restore()
        }
    }

    /** Soft white light behind the portrait and behind the type, so both read over a busy picture. */
    private fun DrawScope.glows(alpha: Float, ms: Float) {
        val sdy = stageDy(ms)
        withTransform({ translate(lay.c.x, lay.c.y + sdy); scale(lay.ring2 * 1.35f, lay.ring2 * 1.35f, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .5f * alpha)
        }
        val cy = (textRect.top + textRect.bottom) / 2f + wordsDy(ms)
        withTransform({ translate(lay.textX, cy); scale(textBlockW * .62f + 24f * dp * s, textRect.height * .72f, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .5f * alpha)
        }
    }

    /**
     * Two big soft lights drifting over 30 and 38 s: pink at the top left, white at the bottom right with (on the sky) an aqua
     * heart. The aqua sits inside the white, so the blend from pink to aqua passes through white, never grey.
     */
    private fun DrawScope.aurora(amb: Float, alpha: Float, aqua: Boolean) {
        if (alpha <= 0f) return
        val vmax = max(w, h)
        val a1 = 2f * PI.toFloat() * amb / 30f
        withTransform({ translate(.14f * w + .05f * vmax * sin(a1), .12f * h + .04f * vmax * cos(a1)); scale(.50f * vmax, .50f * vmax, Offset.Zero) }) {
            drawCircle(AuroraPink, 1f, Offset.Zero, alpha = alpha)
        }
        val bx = auroraX(amb)
        val by = auroraY(amb)
        withTransform({ translate(bx, by); scale(.46f * vmax, .46f * vmax, Offset.Zero) }) {
            drawCircle(AuroraWhite, 1f, Offset.Zero, alpha = alpha)
        }
        // The aqua fades much faster than the white under it: aqua over a half-faded white over pink read grey.
        if (aqua) withTransform({ translate(bx, by); scale(.26f * vmax, .26f * vmax, Offset.Zero) }) {
            drawCircle(AuroraAqua, 1f, Offset.Zero, alpha = alpha * alpha * sqrt(alpha))
        }
    }

    private fun auroraX(amb: Float): Float = .88f * w + .05f * max(w, h) * sin(2f * PI.toFloat() * amb / 38f + 2f)
    private fun auroraY(amb: Float): Float = .82f * h + .04f * max(w, h) * cos(2f * PI.toFloat() * amb / 38f + 2f)

    // The aqua heart's centre and radius this frame (set by drawAmbient; radius 0 over a picture, which has no aqua).
    private var aquaX = 0f
    private var aquaY = 0f
    private var aquaR = 0f

    /**
     * How far into the aqua heart [x], [y] is: 0 outside it, 1 well inside. A star's or mote's halo goes from pink (0) through
     * white (.5) to aqua (1), so pink never lies over the aqua (a pink halo over the light aqua measured grey, #E6E6EB).
     */
    private fun aquaT(x: Float, y: Float): Float {
        if (aquaR <= 0f) return 0f
        val d = hypot(x - aquaX, y - aquaY) / aquaR
        return ((1.2f - d) / .6f).coerceIn(0f, 1f)
    }

    // ---- layer 2: the air (stars, motes, streaks, petals, the season's far layer) ----

    fun drawAmbient(scope: DrawScope, ms: Float, amb: Float, exitK: Float) = with(scope) {
        val a = (if (reduced) 1f else seg(ms, 80f * tl.str, 500f * tl.str)) * (1f - tl.handK(exitK))
        if (a <= 0f) return@with
        if (!reduced) sheen(ms)
        aquaX = auroraX(amb)
        aquaY = auroraY(amb)
        aquaR = if (pic != null) 0f else .26f * max(w, h)
        val tau = 2f * PI.toFloat()
        // A long run's middle beat: a wave of light running across the stars from the left.
        val wu = if (reduced || tl.wave < 0f) -1f else (ms - tl.wave) / tl.waveMs
        for (i in 0 until starN) {
            val period = 2.4f + 3.6f * ih(i, 204)
            var tw = sin(tau * (amb / period + ih(i, 205))).coerceAtLeast(0f)
            if (wu in 0f..1f) tw = max(tw, (1f - abs(starX[i] / w - lerp(-.2f, 1.2f, wu)) / .14f).coerceIn(0f, 1f))
            star(Offset(starX[i], starY[i]), starR[i] * (.75f + .45f * tw), a * (.22f + .78f * tw), 45f * ih(i, 206))
        }
        for (i in 0 until moteN) {
            val dur = 9f + 9f * ih(i, 211)
            val p = ((amb / dur + ih(i, 212)) % 1f + 1f) % 1f
            val y = h * 1.04f - p * h * 1.10f
            val x = ih(i, 213) * w + 10f * dp * s * sin(tau * (amb / (4f + 3f * ih(i, 214)) + ih(i, 215)))
            val k = a * .9f * (p / .12f).coerceAtMost(1f) * (1f - ((p - .7f) / .3f).coerceIn(0f, 1f))
            val r = (1.6f + 2.2f * ih(i, 216)) * dp * s
            halo(Offset(x, y), r * 3.2f, .8f * k)
            drawCircle(Sakura.White, r, Offset(x, y), alpha = k)
        }
        if (!reduced) streaks(ms, a)
        val gust = if (reduced) 0f else tl.gust(ms)
        // The pop's gust: the drift runs 1.4 s ahead along its own path, quickly, then calms.
        drift(amb + 1.4f * gust, .9f * a)
        season.draw(this, amb, gust, a, near = false)
    }

    /**
     * The season's near layer: bigger flakes or petals (and the big soft-focus ones) in front of the rings, fading with the exit.
     * They pass behind the portrait itself (a flake's glow over the monogram's ink read grey).
     */
    fun drawNear(scope: DrawScope, ms: Float, amb: Float, exitK: Float, @Suppress("UNUSED_PARAMETER") exitAt: Float) = with(scope) {
        val a = (if (reduced) 1f else seg(ms, 80f * tl.str, 500f * tl.str)) * (1f - tl.handK(exitK))
        if (a <= 0f) return@with
        val gust = if (reduced) 0f else tl.gust(ms)
        val dy = stageDy(ms)
        withTransform({ translate(0f, dy) }) {
            clipPath(portraitHole, ClipOp.Difference) { withTransform({ translate(0f, -dy) }) { season.draw(this, amb, gust, a, near = true) } }
        }
    }

    /**
     * The look's own few petals drifting on the wind (or, with petals off, white and aqua sparkles), as the shared drift does it;
     * a pink petal crossing the sky's aqua heart gets a white rim (its soft edge straight on the aqua read grey).
     */
    private fun DrawScope.drift(amb: Float, alpha: Float) {
        if (alpha <= 0f) return
        val margin = 24f * dp
        val spanX = w + 2 * margin
        val spanY = h + 2 * margin
        val tr = 30f + amb
        for (i in 0 until driftN) {
            val h0 = ih(i, 50)
            val r = (4.5f + 3f * ih(i, 51)) * dp
            val speed = .7f + .6f * h0
            var x = wrapTo(ih(i, 52) * spanX + 14f * dp * tr * speed, spanX) - margin
            val y = wrapTo(ih(i, 53) * spanY + 26f * dp * tr * speed, spanY) - margin
            x += (10f + 10f * ih(i, 55)) * dp * sin(amb * 2f * PI.toFloat() / (3f + 2f * ih(i, 54)) + 6.28f * h0)
            val spin = (if (ih(i, 56) < .5f) -1f else 1f) * (40f + 60f * ih(i, 57)) * amb + 360f * ih(i, 58)
            if (!petals) {
                introSparkle(sparkle, Offset(x, y), r * .8f, if (h0 < .6f) Sakura.White else Sakura.Aqua, .9f * alpha, spin * .3f)
                continue
            }
            val flip = cos(ih(i, 59) * 6.28f + (1.4f + 1.2f * ih(i, 60)) * amb)
            val pink = ih(i, 61) < .6f
            val rim = if (pink) aquaT(x, y) else 0f
            withTransform({ translate(x, y); rotate(spin, Offset.Zero) }) {
                // The rim keeps some width even when the petal turns edge-on (a thin pink line on the aqua read grey too).
                if (rim > 0f) withTransform({ scale(.72f * r * max(abs(flip), .4f) * 1.3f, r * 1.16f, Offset.Zero) }) {
                    drawPath(kawaiiPetal, Sakura.White.copy(alpha = .95f * alpha * min(1f, 3f * rim)))
                }
                withTransform({ scale(.72f * r * flip, r, Offset.Zero) }) {
                    drawPath(kawaiiPetal, (if (pink) Sakura.Primary.copy(alpha = .88f) else Sakura.White.copy(alpha = .95f)).let { it.copy(alpha = it.alpha * alpha) })
                }
            }
        }
    }

    /** One diagonal band of white light sweeping across once as the picture arrives. */
    private fun DrawScope.sheen(ms: Float) {
        val u = seg(ms, 120f * tl.str, 1300f * tl.str, IntroEase.SineInOut)
        if (u <= 0f || u >= 1f) return
        val x = lerp(-.35f * w, 1.35f * w, u)
        withTransform({ translate(x, h / 2f); rotate(15f, Offset.Zero); scale(.30f * w, 1.6f * h, Offset.Zero) }) {
            drawRect(SheenBand, Offset(-.5f, -.5f), Size(1f, 1f), alpha = .45f * sin(PI.toFloat() * u))
        }
    }

    /**
     * Shooting stars ([StardustTimeline.streaks]): each a streak at 22° below the horizontal, right to left across the upper part
     * of the screen; none that would still fly when the exit starts. The pulse's pair flies side by side.
     */
    private fun DrawScope.streaks(ms: Float, alpha: Float) {
        val life = tl.streakMs
        val dir = Offset(-cos(STREAK_RAD), sin(STREAK_RAD))
        for ((n, t) in tl.streaks.withIndex()) {
            if (ms < t || ms - t >= life || t + life > tl.exit) continue
            val u = (ms - t) / life
            val pair = tl.pulse >= 0f && t >= tl.pulse
            val start = if (pair) {
                Offset((.78f + (if (t > tl.pulse) .10f else 0f)) * w, (.05f + (if (t > tl.pulse) .05f else 0f)) * h)
            } else {
                Offset((.70f + .45f * ih(n, 221)) * w, (.02f + .12f * ih(n, 222)) * h)
            }
            val head = start + dir * (.50f * max(w, h) * IntroEase.CubicOut.transform(u))
            val a = alpha * if (u < .12f) u / .12f else 1f - IntroEase.CubicIn.transform((u - .12f) / .88f)
            val len = 110f * dp * s * (u * 4f).coerceAtMost(1f)
            withTransform({ translate(head.x, head.y); rotate(180f - STREAK_DEG, Offset.Zero) }) {
                withTransform({ scale(len, 8f * dp * s, Offset.Zero) }) { drawRect(StreakGlow, Offset(-1f, -.5f), Size(1f, 1f), alpha = a) }
                withTransform({ scale(len, 2.2f * dp * s, Offset.Zero) }) { drawRect(StreakCore, Offset(-1f, -.5f), Size(1f, 1f), alpha = a) }
            }
            star(head, 5f * dp * s, a, 360f * u)
        }
    }

    /** A star: a white four-point core inside a pink halo, so it reads on the white glows and on the pink alike. */
    private fun DrawScope.star(at: Offset, r: Float, alpha: Float, rot: Float) {
        if (alpha <= 0f) return
        halo(at, r * 2.6f, alpha)
        introSparkle(sparkle, at, r, Sakura.White, alpha, rot)
    }

    /** A soft halo of radius [r]: pink, or (in the aqua heart, see [aquaT]) white turning aqua. */
    private fun DrawScope.halo(at: Offset, r: Float, alpha: Float) {
        val t = aquaT(at.x, at.y)
        withTransform({ translate(at.x, at.y); scale(r, r, Offset.Zero) }) {
            if (t < .5f) drawCircle(PinkHalo, 1f, Offset.Zero, alpha = alpha * (1f - 2f * t))
            if (t > 0f) drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .8f * alpha * (1f - abs(2f * t - 1f)))
            if (t > .5f) drawCircle(AquaHalo, 1f, Offset.Zero, alpha = alpha * (2f * t - 1f))
        }
    }

    // ---- layer 3: the stage (halo, ripples, flash, rings, the portrait, sparks) ----

    fun drawStage(scope: DrawScope, ms: Float, amb: Float, exitAt: Float = -1f) = with(scope) {
        val inK = if (reduced) 1f else seg(ms, tl.popStart, 420f * tl.snap)
        if (inK <= 0f) return@with
        withTransform({ translate(0f, stageDy(ms)) }) { stage(ms, amb, exitAt, inK) }
    }

    private fun DrawScope.stage(ms: Float, amb: Float, exitAt: Float, inK: Float) {
        val up = if (reduced) 0f else tl.bloomUp(ms, exitAt)
        val c = lay.c
        val r = lay.r
        val tau = 2f * PI.toFloat()
        // The halo, breathing .96 ↔ 1.04 every 5.2 s.
        val breath = 1f + .04f * sin(tau * amb / 5.2f)
        withTransform({ translate(c.x, c.y); scale(r * 1.75f * breath, r * 1.75f * breath, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = inK)
        }
        if (!reduced) {
            // Two ripples from the rim (4.2 s each, 2.1 s apart), from just after the hit until the bloom.
            var t = tl.hit + 240f * tl.snap
            while (t <= ms && t < tl.bloomStart) {
                val u = (ms - t) / 4200f
                if (u < 1f) {
                    val a = if (u < .16f) .7f * u / .16f else .7f * (1f - (u - .16f) / .84f)
                    val rr = (r + lay.rim) * lerp(.95f, 1.85f, PingEase.transform(u))
                    drawCircle(Sakura.Primary, rr, c, alpha = .55f * a, style = ringGlow)
                    drawCircle(Sakura.White, rr, c, alpha = a, style = ringLine)
                }
                t += 2100f
            }
            // The hit's burst of light behind the portrait, at its brightest on the hit's own frame.
            val hb = flash(ms, tl.hit - 60f, .9f) + .5f * (1f - seg(ms, tl.hit, 520f * tl.snap, IntroEase.CubicOut)) * seg(ms, tl.hit - 90f, 60f)
            if (hb > 0f) withTransform({ translate(c.x, c.y); scale(lay.ring2 * 1.9f, lay.ring2 * 1.9f, Offset.Zero) }) {
                drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = hb.coerceAtMost(1f))
            }
            // The sparks, the burst rings and the flash ring all pass under the orbit rings: their pink over the aqua arc read grey.
            sparks(ms, tl.hit - 40f * tl.snap, 230, 1f)
            burst(ms, tl.hit - 40f * tl.snap, 1f)
            if (tl.echo >= 0f) {
                // A long run's quiet middle beat: an echo of the pop, a soft burst ring and a few sparks.
                sparks(ms, tl.echo, 271, .7f)
                burst(ms, tl.echo, .6f)
            }
            if (tl.pulse >= 0f) {
                // The long run's pulse: a softer second burst and a ring of sparks.
                sparks(ms, tl.pulse, 251, .8f)
                burst(ms, tl.pulse, .7f)
            }
            // The flash ring: a thick white ring from the portrait's edge out to 1.4, from the hit (outside the face, never on it).
            val fu = (ms - tl.hit + 20f) / (420f * tl.snap)
            if (fu in 0f..1f) {
                val e = IntroEase.ExpoOut.transform(fu)
                val sw = lerp(14f, 1f, e) * dp * s
                val rr = (r + lay.rim) * lerp(1f, 1.4f, e) + sw / 2f
                drawCircle(Sakura.White, rr, c, alpha = .95f * (1f - fu), style = Stroke(sw))
                // A pink edge outside it, so the white ring reads on the light sky too.
                drawCircle(Sakura.Primary, rr + sw / 2f + 1f * dp, c, alpha = .85f * (1f - fu), style = burstEdge)
            }
        }
        rings(ms, amb, up)
        portrait(ms, amb, up)
    }

    /**
     * A burst ring from the rim (.88 → 2.1 of the portrait in 1.25 s) from [at], at [k] strength: a 3.5 dp white core with a pink
     * edge just outside it (white alone vanished on the light sky).
     */
    private fun DrawScope.burst(ms: Float, at: Float, k: Float) {
        val bu = (ms - at) / (1250f * tl.snap)
        if (bu !in 0f..1f) return
        val a = k * if (bu < .14f) bu / .14f else 1f - (bu - .14f) / .86f
        val rr = (lay.r + lay.rim) * lerp(.88f, 2.1f, BurstEase.transform(bu))
        drawCircle(Sakura.Primary, rr + 2.6f * dp, lay.c, alpha = .85f * a, style = burstEdge)
        drawCircle(Sakura.White, rr, lay.c, alpha = a, style = burstCore)
    }

    /**
     * The two orbit rings, coming in with the flash: the inner one (7 s a turn) a pink hairline with a white arc and an aqua arc
     * on white, the outer one (11 s, the other way) a ring of dots with a pink arc and a short white one.
     */
    private fun DrawScope.rings(ms: Float, amb: Float, up: Float) {
        val k = if (reduced) 1f else seg(ms, tl.ringsIn, 520f * tl.snap)
        if (k <= 0f) return
        val a = IntroEase.CubicOut.transform(k)
        val sc = lerp(.86f, 1f, IntroEase.ExpoOut.transform(k))
        val c = lay.c
        val r1 = lay.ring1
        val r2 = lay.ring2
        withTransform({ translate(c.x, c.y); scale(sc, sc, Offset.Zero) }) {
            drawCircle(Sakura.Primary, r1, Offset.Zero, alpha = .45f * a, style = hair)
            withTransform({ rotate(-90f + 360f * amb / 7f, Offset.Zero) }) {
                val tl1 = Offset(-r1, -r1)
                val sz1 = Size(2f * r1, 2f * r1)
                drawArc(Sakura.Primary, -40f, 80f, false, tl1, sz1, alpha = .55f * a, style = arcBase)
                drawArc(Sakura.White, -40f, 80f, false, tl1, sz1, alpha = a, style = arcCore)
                // The white under the aqua comes in first and the aqua as the square: aqua never sits half-faded on the pink.
                drawArc(Sakura.White, 70f, 50f, false, tl1, sz1, alpha = (3f * a).coerceAtMost(1f), style = arcWide)
                // ...and it leaves as the bloom rises (the blush bloom over the aqua read grey).
                val keep = (1f - up) * (1f - up) * (1f - up)
                drawArc(Sakura.Aqua, 70f, 50f, false, tl1, sz1, alpha = a * a * keep, style = arcThin)
                bead(Offset(r1, 0f).rotated(40f), a)
                bead(Offset(r1, 0f).rotated(-40f), a)
            }
            withTransform({ rotate(90f - 360f * amb / 11f, Offset.Zero) }) {
                drawPoints(ringDots, PointMode.Points, Sakura.Primary, 2f * dp * s, StrokeCap.Round, alpha = .55f * a)
                val tl2 = Offset(-r2, -r2)
                val sz2 = Size(2f * r2, 2f * r2)
                drawArc(Sakura.White, -50f, 100f, false, tl2, sz2, alpha = .9f * a, style = arcBase)
                drawArc(Sakura.Primary, -50f, 100f, false, tl2, sz2, alpha = a, style = arcCore)
                drawArc(Sakura.Primary, 150f, 26f, false, tl2, sz2, alpha = .6f * a, style = arcBase)
                drawArc(Sakura.White, 150f, 26f, false, tl2, sz2, alpha = a, style = arcThin)
                bead(Offset(r2, 0f).rotated(50f), a)
            }
            // Fine twinkles drifting just outside the outer ring, half its speed (outside it: their pink never meets the aqua arc).
            val tau = 2f * PI.toFloat()
            for (i in 0 until twinkleN) {
                val ang = (i + .7f * ih(i, 301)) * tau / twinkleN - tau * amb / 22f
                val rr = r2 + (5f + 12f * ih(i, 302)) * dp * s
                val tw = if (reduced) .6f else sin(tau * (amb / (1.4f + 1.6f * ih(i, 303)) + ih(i, 304))).coerceAtLeast(0f)
                val at = Offset(cos(ang) * rr, sin(ang) * rr)
                val size = (1.6f + 1.8f * ih(i, 305)) * dp * s * (.7f + .5f * tw)
                withTransform({ translate(at.x, at.y); scale(size * 1.9f, size * 1.9f, Offset.Zero) }) {
                    drawCircle(PinkHalo, 1f, Offset.Zero, alpha = a * (.25f + .6f * tw))
                }
                introSparkle(sparkle, at, size, Sakura.White, a * (.35f + .65f * tw), 45f * ih(i, 306))
            }
        }
    }

    private fun DrawScope.bead(at: Offset, alpha: Float) {
        drawCircle(Sakura.Primary, 3.6f * dp * s, at, alpha = alpha)
        drawCircle(Sakura.White, 2.4f * dp * s, at, alpha = alpha)
    }

    /**
     * The portrait: a white rim with a fine pink line round it and a soft pink shadow under it, popping in from .6 ([popScale])
     * and 10 dp lower, largest on the hit's frame, then bobbing ±2 dp (5.4 s); a glass shine every 4.2 s while the welcome holds.
     */
    private fun DrawScope.portrait(ms: Float, amb: Float, up: Float) {
        val pk = if (reduced) 1f else tl.popScale(ms)
        if (pk <= 0f) return
        val c = lay.c
        val r = lay.r
        val a = if (reduced) 1f else tl.popAlpha(ms)
        val land = if (reduced) 1f else seg(ms, tl.popStart, StardustTimeline.POP_PEAK * tl.popMs, IntroEase.CubicOut)
        val dy = 10f * dp * s * (1f - land) + 2f * dp * s * sin(2f * PI.toFloat() * amb / 5.4f)
        val body: DrawScope.() -> Unit = {
            withTransform({ translate(c.x, c.y + .30f * r); scale(1.25f * r, 1.0f * r, Offset.Zero) }) { drawCircle(PinkShadow, 1f, Offset.Zero) }
            drawCircle(Sakura.Primary, r + lay.rim + 1.2f * dp, c)
            drawCircle(Sakura.White, r + lay.rim, c)
            clipPath(faceClip) {
                val f = face
                if (f != null) drawImage(f, dstOffset = IntOffset((c.x - r).roundToInt(), (c.y - r).roundToInt()), dstSize = IntSize((2f * r).roundToInt(), (2f * r).roundToInt()))
                else plate(1f - up)
                shine(ms)
            }
        }
        withTransform({ translate(0f, dy); scale(pk, pk, c) }) {
            if (a >= 1f) body() else drawIntoCanvas { canvas ->
                // Composed opaque and faded as one, so the rim never shows through the face while it pops in.
                layerPaint.alpha = a
                val big = r + lay.rim + 4f * dp
                canvas.saveLayer(Rect(c.x - 1.4f * r, c.y - big, c.x + 1.4f * r, c.y + 1.4f * r), layerPaint)
                body()
                canvas.restore()
            }
        }
    }

    /**
     * No picture (or 歡迎畫面用背景圖當頭像 off): a pink sakura on a white-to-blush plate, the monogram on it as a sticker, at
     * [monoK] (it melts away as the exit's bloom rises).
     */
    private fun DrawScope.plate(monoK: Float) {
        val c = lay.c
        val r = lay.r
        withTransform({ translate(c.x, c.y); scale(r, r, Offset.Zero) }) { drawCircle(PlateBrush, 1f, Offset.Zero) }
        val sr = .78f * r
        withTransform({ translate(c.x, c.y); scale(sr, sr, Offset.Zero) }) {
            drawPath(sakura, Sakura.Primary.copy(alpha = .92f))
            drawPath(sakura, Sakura.RoseBrown, style = plateLine)
            if (texts.monogram == null) drawPath(veins, Sakura.White.copy(alpha = .6f), style = veinLine)
        }
        val mono = texts.monogram
        if (mono == null) {
            drawCircle(Sakura.White, 3.5f * dp * s, c)
            return
        }
        if (monoK <= 0f) return
        val at = Offset(c.x - mono.advance[0] / 2f, c.y + .36f * mono.fontPx - mono.baseline)
        val drop = at + Offset(0f, .05f * mono.fontPx)
        val body: DrawScope.() -> Unit = {
            drawGlyph(mono, 0, drop, Sakura.Primary, .55f, stroke = texts.monogramStroke)
            drawGlyph(mono, 0, at, Sakura.White, 1f, stroke = texts.monogramStroke)
            drawGlyph(mono, 0, at, Sakura.Background, 1f, stroke = texts.monogramInline)
            drawGlyph(mono, 0, at, Sakura.Text, 1f)
        }
        if (monoK >= 1f) { body(); return }
        // The monogram melts into the bloom as one (its ink half faded on the white outline read grey).
        drawIntoCanvas { canvas ->
            monoPaint.alpha = monoK
            canvas.saveLayer(Rect(c, r), monoPaint)
            body()
            canvas.restore()
        }
    }

    /** The glass shine across the portrait: at hit + 700 ms, then every 4.2 s while there is time before the bloom. */
    private fun DrawScope.shine(ms: Float) {
        if (reduced) return
        var t = tl.hit + 700f * tl.snap
        if (tl.bloomStart - t < 900f) return
        while (t + 4200f <= ms && tl.bloomStart - (t + 4200f) > 900f) t += 4200f
        val u = seg(ms, t, 760f, IntroEase.CubicInOut)
        if (u <= 0f || u >= 1f) return
        val c = lay.c
        val r = lay.r
        withTransform({ translate(lerp(c.x - 1.7f * r, c.x + 1.7f * r, u), c.y); rotate(20f, Offset.Zero); scale(.6f * r, 2.6f * r, Offset.Zero) }) {
            drawRect(SheenBand, Offset(-.5f, -.5f), Size(1f, 1f), alpha = .7f)
        }
    }

    /**
     * Sparks flung out from behind the portrait from [at], each its own way (± 9°), 34 to 80 dp out, gone in 1.15 s: a white
     * four-point star of 9 to 14 dp radius in a pink halo, with a short tail (pink glow, white core) that shrinks as it slows.
     * [salt] picks another set; [k] is their strength.
     */
    private fun DrawScope.sparks(ms: Float, at: Float, salt: Int, k: Float) {
        val c = lay.c
        val base = lay.r + lay.rim
        for (i in 0 until sparkN) {
            val start = at + 70f * tl.snap * ih(i, salt + 1)
            val u = (ms - start) / (1150f * tl.snap)
            if (u <= 0f || u >= 1f) continue
            val e = SparkEase.transform(u)
            val deg = -90f + (i + .5f * (salt % 2)) * 360f / sparkN + (ih(i, salt + 2) - .5f) * 18f
            val ang = deg * (PI.toFloat() / 180f)
            val dist = base * .92f + lerp(6f, 34f + 46f * ih(i, salt + 3), e) * dp * s
            val p = Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist)
            val a = k * if (u < .05f) u / .05f else 1f - IntroEase.CubicIn.transform((u - .05f) / .95f)
            val rr = lerp(.6f, 1f, e) * (9f + 5f * ih(i, salt + 4)) * dp * s
            // The tail points back at the portrait, longest while the spark is fast.
            val len = (8f + 30f * (1f - e)) * dp * s
            withTransform({ translate(p.x, p.y); rotate(deg, Offset.Zero) }) {
                withTransform({ scale(len, 6f * dp * s, Offset.Zero) }) { drawRect(StreakGlow, Offset(-1f, -.5f), Size(1f, 1f), alpha = .9f * a) }
                withTransform({ scale(len, 2f * dp * s, Offset.Zero) }) { drawRect(StreakCore, Offset(-1f, -.5f), Size(1f, 1f), alpha = a) }
            }
            withTransform({ translate(p.x, p.y); scale(1.7f * rr, 1.7f * rr, Offset.Zero) }) { drawCircle(PinkHalo, 1f, Offset.Zero, alpha = a) }
            introSparkle(sparkle, p, rr, Sakura.White, a, 90f * u)
        }
    }

    // ---- the exit's bloom ----

    /** A soft white bloom from the portrait, growing as it rises ([StardustTimeline.bloom]); the iris then opens from it. */
    fun drawBloom(scope: DrawScope, ms: Float, exitAt: Float) = with(scope) {
        val b = tl.bloom(ms, exitAt)
        if (b <= 0f) return@with
        val from = tl.bloomFrom(exitAt)
        val grow = seg(ms, from, tl.bloomMs + .5f * tl.exitMs, IntroEase.CubicOut)
        val rr = lay.ring2 * lerp(1.1f, 2.8f, grow)
        withTransform({ translate(lay.c.x, lay.c.y + stageDy(ms)); scale(rr, rr, Offset.Zero) }) {
            drawCircle(BloomBrush, 1f, Offset.Zero, alpha = b)
        }
    }

    // ---- the exit: the light iris ----

    /**
     * The look's own exit: from the bloom over the portrait a round light iris opens past the farthest corner over
     * [StardustTimeline.liftMs]. Inside it the veil, the frost and the welcome are gone and the picture is exactly the home
     * screen's (its crop, its 透明度 over the blush, its scrim; the plain blush without a picture); its rim is soft white light
     * and stars riding it. When it covers the screen the host's quick cut shows the app's own buttons.
     */
    fun drawLift(scope: DrawScope, ms: Float, amb: Float) = with(scope) {
        if (!ownIris || ms < tl.exit) return@with
        val u = tl.lift(ms)
        val o = irisOrigin
        val r0 = .9f * lay.ring2
        val rr = lerp(r0, liftFar, u)
        liftClip.reset()
        liftClip.addOval(Rect(o, rr))
        clipPath(liftClip) { home() }
        if (u >= 1f) return@with
        // The rim fades in over the iris's first moments (it grows out of the bloom) and is past the corners at the end.
        val ra = seg(ms, tl.exit, 80f * tl.str)
        drawCircle(Sakura.White, rr - 6f * dp, o, alpha = .16f * ra, style = rimHaze)
        drawCircle(Sakura.White, rr - 2f * dp, o, alpha = .34f * ra, style = rimGlow)
        drawCircle(Sakura.White, rr, o, alpha = .75f * ra, style = rimCore)
        drawCircle(Sakura.White, rr, o, alpha = .95f * ra, style = rimEdge)
        val tau = 2f * PI.toFloat()
        for (i in 0 until rimStarN) {
            val ang = (i + .8f * ih(i, 321)) * tau / rimStarN + .5f * u
            val d = rr + (ih(i, 322) - .4f) * 14f * dp
            val p = Offset(o.x + cos(ang) * d, o.y + sin(ang) * d)
            if (p.x < -20f * dp || p.x > w + 20f * dp || p.y < -20f * dp || p.y > h + 20f * dp) continue
            val tw = .55f + .45f * sin(tau * (amb / (.5f + .5f * ih(i, 323)) + ih(i, 324)))
            star(p, (3.5f + 4.5f * ih(i, 325)) * dp * s * (.7f + .4f * tw), ra * (.5f + .5f * tw), 360f * u + 90f * ih(i, 326))
        }
    }

    /**
     * The home screen's own background: the blush and its soft glows at rest (the home's clock waits while the intro covers it),
     * the picture at its crop and 透明度, under its scrim.
     */
    private fun DrawScope.home() {
        drawRect(Sakura.Background)
        homeGlows?.draw(this)
        val p = pic ?: return
        cover(p.width.toFloat(), p.height.toFloat(), w, h, 1f, .5f, homeY, .5f * w, homeY * h, homePlace)
        withTransform({ translate(homePlace.ox, homePlace.oy); scale(homePlace.k, homePlace.k, Offset.Zero) }) { drawImage(p, alpha = homeAlpha) }
        drawRect(HomeScrim)
    }

    // ---- layer 4: the words ----

    /**
     * The type at [ms], standing lower until the pill comes ([wordsDy]). [light] (0 → 1 as the words fade on exit) washes it
     * towards light: the ink first through the pink, then white (washed straight to white it passed a grey); the underline and
     * the pill straight to white (pink over their aqua, or over the aqua sky, read grey).
     */
    fun drawWords(scope: DrawScope, ms: Float, light: Float = 0f) = with(scope) {
        val dy = wordsDy(ms)
        washed(light, pink = true) {
            withTransform({ translate(0f, dy) }) {
                greeting(ms)
                texts.sticker?.let { st -> name(st, ms) }
            }
        }
        washed(light, pink = false) { withTransform({ translate(0f, dy) }) { underline(ms) } }
        if (!showsStatus) return@with
        washed(light, pink = false) { statusPill(ms) }
        washed(light, pink = true) { statusText(ms) }
    }

    /** The words' strength on exit (see [StardustTimeline.wordsFade]); 1 for the still. */
    fun wordsFade(ms: Float, exitAt: Float): Float = if (reduced) 1f else tl.wordsFade(ms, exitAt)

    /** [body] in its own layer, washed over (SrcAtop: only where it drew) by [t]: through the pink first when [pink], then white. */
    private inline fun DrawScope.washed(t: Float, pink: Boolean, body: DrawScope.() -> Unit) {
        if (t <= 0f) { body(); return }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, w, h), washPaint)
            body()
            if (pink) drawRect(Sakura.Primary, alpha = (2.2f * t).coerceAtMost(1f), blendMode = BlendMode.SrcAtop)
            val white = if (pink) (2.2f * (t - .3f)).coerceIn(0f, 1f) else (1.6f * t).coerceAtMost(1f)
            if (white > 0f) drawRect(Sakura.White, alpha = white, blendMode = BlendMode.SrcAtop)
            canvas.restore()
        }
    }

    /** The name stamped in as a sticker (its own glints off), with one white sheen across it at [StardustTimeline.sheen]. */
    private fun DrawScope.name(st: IntroSticker, ms: Float) {
        val draw: DrawScope.() -> Unit = { drawSticker(st, lay.textX, stickerTop, ms, tl.nameStart, tl.str, tl.compact, reduced, glintUntil = 0f) }
        val k = if (reduced || tl.sheen < 0f) 0f else seg(ms, tl.sheen, tl.sheenMs, IntroEase.CubicInOut)
        if (k <= 0f || k >= 1f) { draw(); return }
        glint(Rect(lay.textX - st.width / 2f, stickerTop, lay.textX + st.width / 2f, stickerTop + st.height), k, draw)
    }

    /**
     * 歡迎回來, one character at a time (80 ms apart): each rises 14 dp and untilts from 4° over 620 ms, in ink inside a white
     * outline. Settled characters draw every outline first and every fill after, so no outline cuts a neighbour's ink; one still
     * moving is composed in its own layer and faded as one.
     */
    private fun DrawScope.greeting(ms: Float) {
        val g = texts.greet
        val n = g.size
        val k = texts.greetScale
        val rise = 14f * dp * s
        fun raw(i: Int) = if (reduced) 1f else ((ms - tl.greetAt(i, n)) / tl.greetMs).coerceIn(0f, 1f)
        withTransform({ scale(k, k, Offset(lay.textX, greetTop)) }) {
            val x0 = lay.textX - g.width / 2f
            for (pass in 0 until 3) for (i in 0 until n) {
                if (raw(i) < 1f) continue
                val at = Offset(x0 + g.x[i], greetTop)
                when (pass) {
                    0 -> drawGlyph(g, i, at, Sakura.White, 1f, stroke = texts.greetOutline)
                    1 -> drawGlyph(g, i, at, Sakura.Background, 1f, stroke = texts.greetInline)
                    else -> drawGlyph(g, i, at, Sakura.Text, 1f)
                }
            }
            for (i in 0 until n) {
                val u = raw(i)
                if (u <= 0f || u >= 1f) continue
                val e = IntroEase.ExpoOut.transform(u)
                val a = (u / .3f).coerceAtMost(1f)
                val at = Offset(x0 + g.x[i], greetTop + rise / k * (1f - e))
                val pivot = at + Offset(g.advance[i] / 2f, g.baseline)
                drawIntoCanvas { canvas ->
                    layerPaint.alpha = a
                    val pad = texts.greetOutline.width + rise
                    canvas.saveLayer(Rect(at.x - pad, at.y - pad, at.x + g.advance[i] + pad, at.y + g.height + pad), layerPaint)
                    withTransform({ rotate(4f * (1f - e), pivot) }) {
                        drawGlyph(g, i, at, Sakura.White, 1f, stroke = texts.greetOutline)
                        drawGlyph(g, i, at, Sakura.Background, 1f, stroke = texts.greetInline)
                        // The ink arrives pink and deepens as it settles: faint ink on the light sky read a dusty grey.
                        drawGlyph(g, i, at, lerpColor(Sakura.Primary, Sakura.Text, (u / .45f).coerceAtMost(1f)), 1f)
                    }
                    canvas.restore()
                }
            }
        }
    }

    /**
     * The line under the greeting, grown from its middle: pink at the left through white to aqua at the right, on a wider white
     * line, with a little star riding each growing end.
     */
    private fun DrawScope.underline(ms: Float) {
        val k = if (reduced) 1f else seg(ms, tl.lineStart, tl.lineMs, IntroEase.ExpoOut)
        if (k <= 0f) return
        val half = lineW / 2f * k
        val a = Offset(lay.textX - half, lineY)
        val b = Offset(lay.textX + half, lineY)
        drawLine(Sakura.White, a, b, lineUnder, StrokeCap.Round, alpha = .95f)
        drawLine(lineBrush, a, b, lineCore, StrokeCap.Round)
        if (reduced || k >= 1f) return
        val sa = 1f - seg(ms, tl.lineStart + .5f * tl.lineMs, .5f * tl.lineMs)
        star(a, 4.5f * dp * s, sa, 90f * k)
        introSparkle(sparkle, b, 4.5f * dp * s, Sakura.Aqua, sa, -90f * k)
    }

    /** The status pill's strength: it comes first and its words after it (ink fading straight over the light aqua read grey). */
    private fun pillIn(ms: Float): Float = if (reduced) 1f else seg(ms, tl.statusIn, 200f * tl.str)
    private fun statusInk(ms: Float): Float = if (reduced) 1f else seg(ms, tl.statusIn + 140f * tl.str, 300f * tl.str)

    /** The pill behind 馬上就好, and the little bar under it that is full when the exit starts. */
    private fun DrawScope.statusPill(ms: Float) {
        val pa = pillIn(ms)
        if (pa <= 0f) return
        val a = statusInk(ms)
        // A soft blush-white pill, so the line reads over a busy picture (on pure white the ink's soft edge read grey).
        drawRoundRect(PillTint.copy(alpha = .9f * pa), Offset(lay.textX - pillW / 2f, pillTop), Size(pillW, pillH), CornerRadius(pillH / 2f))
        val corner = CornerRadius(barH / 2f)
        drawRoundRect(Sakura.White, Offset(barLeft, barTop), Size(barW, barH), corner, alpha = a)
        drawRoundRect(Sakura.Primary, Offset(barLeft, barTop), Size(barW, barH), corner, alpha = .45f * a, style = hair)
        val f = if (reduced) .92f else tl.barFill(ms)
        if (f <= 0f) return
        val fw = max(barH, barW * f)
        drawRoundRect(barFill, Offset(barLeft, barTop), Size(fw, barH), corner, alpha = a)
        if (!reduced) clipRect(barLeft, barTop, barLeft + fw, barTop + barH) {
            // A white gleam running along the filled part every 2.4 s.
            val g = ((ms - tl.statusIn) % 2400f) / 2400f
            val x = lerp(barLeft - 30f * dp, barLeft + fw + 30f * dp, g)
            withTransform({ translate(x, barTop + barH / 2f); scale(26f * dp * s, barH * 2f, Offset.Zero) }) {
                drawRect(SheenBand, Offset(-.5f, -.5f), Size(1f, 1f), alpha = .8f * a)
            }
        }
    }

    /** 馬上就好 in the lighter brown, with three pink dots after it blinking 200 ms apart. */
    private fun DrawScope.statusText(ms: Float) {
        val a = statusInk(ms)
        if (a <= 0f) return
        val run = texts.status
        val left = lay.textX - statusW / 2f
        drawRun(run, left, statusTop, Sakura.TextSecondary, a)
        val cy = statusTop + run.baseline - 5f * dp * s
        for (j in 0 until 3) {
            val ph = if (reduced) .5f else (((ms - tl.statusIn - 200f * j) % 1400f) + 1400f) % 1400f / 1400f
            val blink = .25f + .75f * (1f - abs(ph - .5f) * 2f)
            drawCircle(Sakura.Primary, 2.2f * dp * s, Offset(left + run.width + 6f * dp * s + 3f * dp * s + j * dotGap, cy), alpha = a * blink)
        }
    }

    companion object {
        private const val STREAK_DEG = 22f
        private val STREAK_RAD = STREAK_DEG * PI.toFloat() / 180f
        /** How much lower the group stands before the status pill comes (upright), in dp. */
        const val SHIFT_DP = 44f
        /** The frosted copy: dp of the screen per pixel, and its box blur's radius in those pixels (three passes). */
        const val FROST_DP = 3.5f
        const val FROST_BLUR = 4
        /** The frosted disc's white wash over the blurred picture: it always reads as soft white glass. */
        const val FROST_WHITE = .5f
    }
}

/**
 * The season for this look, plenty of it so it reads in one frame (the art director: a few petals looked like the look's own,
 * sparse snow like bokeh or bubbles). Winter: many soft white flakes in two depths on one shared, gently gusting wind (small and
 * slow behind the portrait, bigger and quicker in front, every third of those a little six-armed crystal), and a few big blurred
 * flakes close to the eye. Spring (with petals on): notched sakura petals, pink and white, 8 to 15 dp, tumbling on the same kind
 * of wind, and a few large soft-focus petals in front. The big ones keep to the screen's sides, clear of the words.
 */
private class StardustSeason(
    private val w: Float, private val h: Float, private val dp: Float, season: IntroSeason, count: Int, bigs: Int, fx: IntroFx,
    /** How far into the sky's aqua heart a point is (0 outside it): a pink petal there gets a white rim. */
    private val aquaAt: (Float, Float) -> Float = { _, _ -> 0f },
) {
    private val kind = if (season == IntroSeason.SAKURA && !fx.petals) IntroSeason.NONE else season
    private val n = if (kind == IntroSeason.NONE) 0 else count
    private val bigN = if (kind == IntroSeason.NONE) 0 else bigs
    private val petal = notchedPetal()
    private val crystal = crystalPath()
    private val crystalSmall = Stroke(1.3f / 5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val crystalLarge = Stroke(1.3f / 7.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    /** The big soft-focus petal, blurred once (only its alpha, so its edge stays pink and never greys). */
    private val softPetal: ImageBitmap? = if (kind == IntroSeason.SAKURA && bigN > 0) softShape(96, 7, Sakura.Primary) { size ->
        withTransform({ translate(size / 2f, size / 2f); scale(.36f * size, .36f * size, Offset.Zero) }) { drawPath(petal, Sakura.Primary) }
    } else null
    /** The same, white and a little wider: under a big petal crossing the sky's aqua (its soft pink edge on the aqua read grey). */
    private val softHalo: ImageBitmap? = if (softPetal != null) softShape(96, 9, Sakura.White) { size ->
        withTransform({ translate(size / 2f, size / 2f); scale(.40f * size, .40f * size, Offset.Zero) }) { drawPath(petal, Sakura.White) }
    } else null
    val active: Boolean get() = n > 0 || bigN > 0

    /** Snow: two in five are near; petals: one in three. */
    private fun near(i: Int): Boolean = if (kind == IntroSeason.SNOW) i % 5 >= 3 else i % 3 == 2

    /** The wind every flake and petal shares, in dp from where it would be: a steady drift with a slow gust. */
    private fun wind(amb: Float): Float = 16f * amb + 18f * sin(.5f * amb)

    fun draw(scope: DrawScope, amb: Float, gust: Float, alpha: Float, near: Boolean) = with(scope) {
        if (alpha <= 0f) return@with
        val margin = 30f * dp
        val spanX = w + 2f * margin
        val spanY = h + 2f * margin
        val wind = wind(amb) * dp
        for (i in 0 until n) {
            if (near(i) != near) continue
            val hh = ih(i, 160)
            val depth = if (near) 1f else .55f
            if (kind == IntroSeason.SNOW) {
                val fall = (if (near) 40f + 16f * hh else 20f + 10f * hh) * dp
                val y = wrap(ih(i, 161) * spanY + amb * fall, spanY) - margin
                val sway = (if (near) 6f else 4f) * dp * sin(amb * (.8f + .6f * hh) + 6.28f * hh)
                val x = wrap(ih(i, 162) * spanX + depth * wind + sway + gust * 24f * depth * dp, spanX) - margin
                if (near && i % 2 == 0) {
                    // A little crystal: six arms with a pair of twigs each, turning slowly.
                    val large = ih(i, 163) < .5f
                    val r = (if (large) 7.5f else 5.5f) * dp
                    withTransform({ translate(x, y); rotate(360f * ih(i, 164) + 20f * amb, Offset.Zero); scale(r, r, Offset.Zero) }) {
                        drawCircle(SnowFlake, 1.25f, Offset.Zero, alpha = .45f * alpha)
                        drawPath(crystal, Sakura.White.copy(alpha = alpha), style = if (large) crystalLarge else crystalSmall)
                    }
                } else {
                    // A soft white flake: solid to .62 of its glow, 3 to 9 dp across.
                    val r = (if (near) 2.8f + 1.8f * ih(i, 163) else 1.5f + 1.2f * ih(i, 163)) * dp * 1.6f
                    withTransform({ translate(x, y); scale(r, r, Offset.Zero) }) { drawCircle(SnowFlake, 1f, Offset.Zero, alpha = (if (near) 1f else .9f) * alpha) }
                }
            } else {
                // Petals 8 to 11 dp long behind, 12 to 15 in front (the path is 2 units long).
                val half = (if (near) 6f + 1.5f * ih(i, 163) else 4f + 1.5f * ih(i, 163)) * dp
                val fall = (if (near) 44f else 28f) * dp * (.75f + .5f * hh)
                val y = wrap(ih(i, 164) * spanY + amb * fall, spanY) - margin
                val sway = (10f + 10f * ih(i, 165)) * dp * sin(amb * (.9f + .5f * hh) + 6.28f * hh)
                val x = wrap(ih(i, 166) * spanX + depth * wind + sway + gust * .22f * w * depth, spanX) - margin
                val spin = (if (ih(i, 167) < .5f) -1f else 1f) * (60f + 80f * ih(i, 168)) * amb + 360f * ih(i, 169) + 140f * gust
                // The tumble: the petal turns edge-on and back, and nods, so it flutters instead of sliding.
                val flip = cos(6.28f * ih(i, 170) + (1.8f + 1.6f * hh) * amb)
                val nod = .75f + .25f * cos(6.28f * ih(i, 172) + (1.1f + .9f * hh) * amb)
                val pink = ih(i, 171) < .62f
                val rim = if (pink) aquaAt(x, y) else 0f
                withTransform({ translate(x, y); rotate(spin, Offset.Zero) }) {
                    // Over the sky's aqua a pink petal's soft edge read grey: there it sits on a white rim, which keeps some
                    // width even when the petal turns edge-on.
                    if (rim > 0f) withTransform({ scale(.8f * half * max(abs(flip), .4f) * 1.3f, half * nod * 1.18f, Offset.Zero) }) {
                        drawPath(petal, Sakura.White.copy(alpha = alpha * min(1f, 3f * rim)))
                    }
                    withTransform({ scale(.8f * half * flip, half * nod, Offset.Zero) }) {
                        drawPath(petal, (if (pink) Sakura.Primary else Sakura.White).copy(alpha = .95f * alpha))
                    }
                }
            }
        }
        if (!near) return@with
        for (i in 0 until bigN) {
            // Close to the eye: big, soft, slow to cross, on the screen's left or right side.
            val left = i % 2 == 0
            val fall = (34f + 14f * ih(i, 180)) * dp
            val y = wrap(ih(i, 181) * spanY + amb * fall, spanY + 120f * dp) - margin - 60f * dp
            val x = (if (left) .02f + .14f * ih(i, 182) else .84f + .14f * ih(i, 182)) * w + 22f * dp * sin(.6f * amb + 6.28f * ih(i, 183))
            if (kind == IntroSeason.SNOW) {
                val r = (16f + 10f * ih(i, 184)) * dp
                withTransform({ translate(x, y); scale(r, r, Offset.Zero) }) { drawCircle(SnowBokeh, 1f, Offset.Zero, alpha = alpha) }
            } else {
                val img = softPetal ?: continue
                val size = (72f + 26f * ih(i, 184)) * dp
                val spin = 360f * ih(i, 185) + (if (left) 22f else -22f) * amb
                val flip = .55f + .45f * cos(6.28f * ih(i, 186) + .9f * amb)
                val rim = aquaAt(x, y)
                withTransform({ translate(x, y); rotate(spin, Offset.Zero); scale(flip, 1f, Offset.Zero) }) {
                    val halo = softHalo
                    if (rim > 0f && halo != null) drawImage(
                        halo, IntOffset.Zero, IntSize(halo.width, halo.height),
                        IntOffset((-.6f * size).roundToInt(), (-.6f * size).roundToInt()), IntSize((1.2f * size).roundToInt(), (1.2f * size).roundToInt()),
                        alpha = alpha * min(1f, 2f * rim), filterQuality = FilterQuality.High,
                    )
                    drawImage(
                        img, IntOffset.Zero, IntSize(img.width, img.height),
                        IntOffset((-size / 2f).roundToInt(), (-size / 2f).roundToInt()), IntSize(size.roundToInt(), size.roundToInt()),
                        alpha = .7f * alpha, filterQuality = FilterQuality.High,
                    )
                }
            }
        }
    }

    private fun wrap(v: Float, span: Float): Float = ((v % span) + span) % span
}

/**
 * The home screen's soft glows as they stand when it first shows (FogBackground's three glows at the start of its clock, which
 * waits while the intro covers the screen; in the 粉嫩 style fainter, with its halftone corner and polka dots): the light iris
 * opens onto them, so the host's last cut onto the app shows no change in the background.
 */
private class StardustHomeGlows(w: Float, h: Float, private val d: Float, style: BackdropStyle) {
    private class Glow(val brush: Brush, val r: Float, val left: Float, val top: Float)

    private fun glow(color: androidx.compose.ui.graphics.Color, sizeDp: Float, left: Float, top: Float, peak: Float = .75f): Glow {
        val r = sizeDp * d / 2f
        return Glow(
            Brush.radialGradient(0f to color.copy(alpha = peak), .55f to color.copy(alpha = peak * .507f), 1f to color.copy(alpha = 0f), center = Offset(r, r), radius = r),
            r, left, top,
        )
    }

    private val kawaii = style == BackdropStyle.KAWAII
    private val glows = if (kawaii) {
        listOf(
            glow(Sakura.Primary, 420f, w - 270f * d, -120f * d, .15f),
            glow(Sakura.White, 260f, -110f * d, h - 120f * d - 260f * d),
            glow(Sakura.Primary, 336f, w - 218f * d, h * .42f - 48f * d, .15f),
        )
    } else {
        listOf(
            glow(Sakura.Primary, 300f, w + 90f * d - 300f * d, -60f * d),
            glow(Sakura.Aqua, 260f, -110f * d, h - 120f * d - 260f * d),
            glow(Sakura.MilkTea, 240f, w + 70f * d - 240f * d, h * .42f),
        )
    }
    private val halftone: Path? = if (kawaii) Kawaii.halftone(w, h, 9f * d, 3f * d, .4f * d) else null
    private val dots: Brush? = if (!kawaii) null else runCatching {
        val pitch = (16f * d).roundToInt().coerceAtLeast(4)
        val tile = ImageBitmap(pitch, pitch)
        val paint = Paint().apply { color = Sakura.Primary; isAntiAlias = true }
        androidx.compose.ui.graphics.Canvas(tile).apply {
            drawCircle(Offset(pitch / 4f, pitch / 4f), 1.5f * d, paint)
            drawCircle(Offset(3f * pitch / 4f, 3f * pitch / 4f), 1.5f * d, paint)
        }
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }.getOrNull()

    fun draw(scope: DrawScope) = with(scope) {
        dots?.let { drawRect(it, alpha = .16f) }
        halftone?.let { drawPath(it, Sakura.Primary.copy(alpha = .22f)) }
        for (g in glows) withTransform({ translate(g.left, g.top) }) { drawCircle(g.brush, g.r, Offset(g.r, g.r)) }
    }
}

/** A sakura petal 2 units long (base at +1, tip at -1) with the notch at its tip that makes it a sakura's, not an oval. */
private fun notchedPetal(): Path = Path().apply {
    moveTo(0f, 1f)
    cubicTo(-.62f, .62f, -.95f, -.35f, -.42f, -.98f)
    quadraticTo(-.22f, -1.02f, 0f, -.66f)
    quadraticTo(.22f, -1.02f, .42f, -.98f)
    cubicTo(.95f, -.35f, .62f, .62f, 0f, 1f)
    close()
}

/** A snow crystal of radius 1: six arms, each with a pair of twigs. */
private fun crystalPath(): Path = Path().apply {
    for (i in 0 until 6) {
        val a = i * PI.toFloat() / 3f
        val ca = cos(a)
        val sa = sin(a)
        fun pt(along: Float, side: Float) = Offset(ca * along - sa * side, sa * along + ca * side)
        val tip = pt(1f, 0f)
        moveTo(0f, 0f)
        lineTo(tip.x, tip.y)
        for ((at, len) in listOf(.45f to .28f, .72f to .2f)) {
            val base = pt(at, 0f)
            val l = pt(at + len * .7f, len * .7f)
            val r = pt(at + len * .7f, -len * .7f)
            moveTo(base.x, base.y); lineTo(l.x, l.y)
            moveTo(base.x, base.y); lineTo(r.x, r.y)
        }
    }
}

/**
 * A [px] square shape drawn by [draw] (given the square's size) in [color], with its alpha box-blurred ([blur] px, three passes):
 * a soft-focus sprite built once. Only the alpha is blurred, the colour stays [color], so its soft edge never darkens.
 */
private fun softShape(px: Int, blur: Int, color: androidx.compose.ui.graphics.Color, draw: DrawScope.(Float) -> Unit): ImageBitmap? = runCatching {
    val img = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(px.toFloat(), px.toFloat())) { draw(px.toFloat()) }
    val b = img.asAndroidBitmap()
    val pix = IntArray(px * px)
    b.getPixels(pix, 0, px, 0, 0, px, px)
    val alpha = IntArray(px * px) { (pix[it] ushr 24) and 255 }
    repeat(3) { boxBlur(alpha, px, px, blur) }
    val rgb = color.toArgb() and 0xFFFFFF
    for (i in pix.indices) pix[i] = (alpha[i].coerceIn(0, 255) shl 24) or rgb
    b.setPixels(pix, 0, px, 0, 0, px, px)
    img
}.getOrNull()

/** One pass of a box blur of radius [r] over the channel values [v] ([w] × [h]), across and then down, edges clamped. */
private fun boxBlur(v: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(v.size)
    val n = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var sum = 0
        for (k in -r..r) sum += v[row + k.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[row + x] = sum / n
            sum += v[row + (x + r + 1).coerceAtMost(w - 1)] - v[row + (x - r).coerceAtLeast(0)]
        }
    }
    for (x in 0 until w) {
        var sum = 0
        for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            v[y * w + x] = sum / n
            sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
        }
    }
}

private fun wrapTo(v: Float, span: Float): Float = ((v % span) + span) % span

/** [this] turned by [deg] degrees about the origin. */
private fun Offset.rotated(deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
}

/**
 * Where a picture's face usually is, by the 對齊 setting (a full-body picture has the face in its upper third: in the owner's
 * own picture at 27 % of the height).
 */
private fun faceFocus(align: PictureAlign): Float = when (align) {
    PictureAlign.TOP -> .25f
    PictureAlign.CENTER -> .29f
    PictureAlign.BOTTOM -> .65f
}

/**
 * The portrait's picture: a square round the face ([fy] of the height, centred across), .6 of the picture's short side, scaled
 * to [px] once by halving (a single big downscale shimmers). Null when the picture cannot be read (the plate shows instead).
 */
internal fun faceCrop(picture: ImageBitmap, px: Int, fy: Float): ImageBitmap? = runCatching {
    val src = picture.asAndroidBitmap()
    val side = (min(src.width, src.height) * .60f).roundToInt().coerceAtLeast(1)
    val left = (src.width / 2 - side / 2).coerceIn(0, src.width - side)
    val top = (src.height * fy - side / 2f).roundToInt().coerceIn(0, src.height - side)
    var b = Bitmap.createBitmap(src, left, top, side, side)
    while (b.width / 2 >= px * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    Bitmap.createScaledBitmap(b, px.coerceAtLeast(1), px.coerceAtLeast(1), true).asImageBitmap()
}.getOrNull()

/**
 * The frosted copy of the picture: halved (each step a 2 × 2 average) to about [px] wide, then box-blurred three times ([blur]
 * px, about a Gaussian): drawn large it is a smooth blur, where the old 14 dp-a-pixel copy showed its blocks. Null when the
 * picture cannot be read (the disc is then only light).
 */
private fun frostCopy(picture: ImageBitmap, px: Int, blur: Int): ImageBitmap? = runCatching {
    var b = picture.asAndroidBitmap()
    while (b.width / 2 >= px * 2 && b.height >= 8) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    val ph = (b.height.toFloat() * px / b.width).roundToInt().coerceAtLeast(4)
    b = Bitmap.createScaledBitmap(b, px, ph, true)
    if (b.config != Bitmap.Config.ARGB_8888 || !b.isMutable) b = b.copy(Bitmap.Config.ARGB_8888, true)
    val n = px * ph
    val pix = IntArray(n)
    b.getPixels(pix, 0, px, 0, 0, px, ph)
    val ch = Array(3) { c -> IntArray(n) { (pix[it] shr (16 - 8 * c)) and 255 } }
    for (c in ch) repeat(3) { boxBlur(c, px, ph, blur) }
    for (i in 0 until n) pix[i] = (0xFF shl 24) or (ch[0][i] shl 16) or (ch[1][i] shl 8) or ch[2][i]
    b.setPixels(pix, 0, px, 0, 0, px, ph)
    b.asImageBitmap()
}.getOrNull()

/** The home screen's own scrim over its picture (FogBackground's PictureScrim), so the light iris opens onto the home exactly. */
private val HomeScrim = Brush.verticalGradient(
    0f to Sakura.Background.copy(alpha = .55f), .14f to Sakura.Background.copy(alpha = 0f),
    .80f to Sakura.Background.copy(alpha = 0f), 1f to Sakura.Background.copy(alpha = .65f),
)
/** The welcome's own scrim: only a touch behind the status bar and the 跳過 label, so the picture's top keeps its colour. */
private val WelcomeScrim = Brush.verticalGradient(
    0f to Sakura.Background.copy(alpha = .24f), .07f to Sakura.Background.copy(alpha = 0f),
    .84f to Sakura.Background.copy(alpha = 0f), 1f to Sakura.Background.copy(alpha = .45f),
)

// Unit brushes (radius 1 at the origin, scaled where drawn): a frame builds no brush. Every stop that fades keeps its colour at
// alpha 0 (fading to Color.Transparent, which is black, greys the middle).
private val PinkHalo = Brush.radialGradient(
    0f to Sakura.Primary.copy(alpha = .55f), .45f to Sakura.Primary.copy(alpha = .20f), 1f to Sakura.Primary.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
private val AquaHalo = Brush.radialGradient(
    0f to Sakura.Aqua.copy(alpha = .55f), .45f to Sakura.Aqua.copy(alpha = .20f), 1f to Sakura.Aqua.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
private val PinkShadow = Brush.radialGradient(
    0f to Sakura.Primary.copy(alpha = .45f), .6f to Sakura.Primary.copy(alpha = .16f), 1f to Sakura.Primary.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
/** The picture's veil behind the type (over the graded one: together about .6 in the middle of the type's column). */
private val VeilCore = Brush.radialGradient(
    0f to Sakura.Background.copy(alpha = .44f), .5f to Sakura.Background.copy(alpha = .30f), 1f to Sakura.Background.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
/** The frosted disc's mask: whole to .5, then a long soft fall to nothing at its edge (a clean circle, no corners). */
private val SoftMask = Brush.radialGradient(
    0f to Sakura.White, .5f to Sakura.White, .75f to Sakura.White.copy(alpha = .55f), .9f to Sakura.White.copy(alpha = .15f),
    1f to Sakura.White.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
)
/** A snowflake: solid white to .62, then a soft white glow. */
private val SnowFlake = Brush.radialGradient(
    0f to Sakura.White, .62f to Sakura.White, .78f to Sakura.White.copy(alpha = .45f), 1f to Sakura.White.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
/** A big blurred flake close to the eye: a soft white disc. */
private val SnowBokeh = Brush.radialGradient(
    0f to Sakura.White.copy(alpha = .62f), .5f to Sakura.White.copy(alpha = .42f), .8f to Sakura.White.copy(alpha = .14f),
    1f to Sakura.White.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
)
/** The exit's bloom: blush white (pure white washed over the monogram's ink turned it grey). */
private val BloomBrush = Brush.radialGradient(
    0f to lerpColor(Sakura.White, Sakura.Background, .7f), .4f to lerpColor(Sakura.White, Sakura.Background, .7f).copy(alpha = .6f),
    1f to Sakura.White.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
)
/** The status pill: white with blush. */
private val PillTint = lerpColor(Sakura.White, Sakura.Background, .55f)
private val PlateBrush = Brush.radialGradient(0f to Sakura.White, 1f to Sakura.Background, center = Offset.Zero, radius = 1f)
private val AuroraPink = Brush.radialGradient(
    0f to Sakura.Primary.copy(alpha = .42f), .5f to Sakura.Primary.copy(alpha = .16f), 1f to Sakura.Primary.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
private val AuroraWhite = Brush.radialGradient(
    0f to Sakura.White, .45f to Sakura.White.copy(alpha = .85f), 1f to Sakura.White.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
)
private val AuroraAqua = Brush.radialGradient(
    0f to Sakura.Aqua.copy(alpha = .30f), .6f to Sakura.Aqua.copy(alpha = .11f), 1f to Sakura.Aqua.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
/** A soft white band across x −.5..+.5 (the sheen, the glass shine, the bar's gleam). */
private val SheenBand = Brush.horizontalGradient(
    0f to Sakura.White.copy(alpha = 0f), .5f to Sakura.White, 1f to Sakura.White.copy(alpha = 0f), startX = -.5f, endX = .5f,
)
/** A streak's tail over x −1..0, the head at 0. */
private val StreakCore = Brush.horizontalGradient(0f to Sakura.White.copy(alpha = 0f), 1f to Sakura.White, startX = -1f, endX = 0f)
private val StreakGlow = Brush.horizontalGradient(
    0f to Sakura.Primary.copy(alpha = 0f), 1f to Sakura.Primary.copy(alpha = .55f), startX = -1f, endX = 0f,
)

private val BurstEase: Easing = CubicBezierEasing(.16f, .8f, .3f, 1f)
private val SparkEase: Easing = CubicBezierEasing(.2f, .7f, .3f, 1f)
private val PingEase: Easing = CubicBezierEasing(.2f, .6f, .35f, 1f)
