package app.dshmobile.ui.fog

// Adapted from KylinQ01/dsh-startup-animation (c) 2026 KylinQ01, MIT License: the motion values of its welcome (the portrait's
// pop on cubic-bezier(.16, 1.06, .3, 1.22), the burst ring .88 → 2.1 in 1.25 s, sparks flung out over 1.15 s at ± 9°, two ping
// ripples .95 → 1.85 every 4.2 s, 2.1 s apart, orbit rings turning in 7 s and 11 s the other way, the greeting rising 14 px and
// untilting 4° in 620 ms, 80 ms apart, 22° streaks, the halo breathing .96 ↔ 1.04 in 5.2 s, and those cubic-bezier curves). The
// drawing, the colours, the run's timing and the layout are our own; none of its images, names or words.

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 * round portrait (the picture's face, or your monogram on a sakura) pops in with a flash ring, a burst ring and sparks, inside
 * two orbit rings turning opposite ways; 「歡迎回來」 floats up one character at a time, a pink-white-aqua line grows under it
 * and your name stamps in as a sticker; 「正在準備你的工作台」 waits in a pill with a bar that is full when the exit starts. Then
 * a soft white bloom rises from the portrait and the sakura iris opens from it onto the home screen, the veil and the camera
 * easing to the home screen's own picture inside it.
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
    val timing = remember(tl) { IntroTiming(tl.exit, tl.total, still = tl.still, hit = tl.hit, announce = tl.announce) }
    val line = introWelcomeLine(intro)
    val words = stardustWords(intro.welcome, stringResource(R.string.intro_welcome_default))
    val status = stringResource(R.string.intro_stardust_status)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val coverage = IntroFonts.coverage(LocalContext.current.resources)
    // The home screen shows the picture at the 透明度 setting over the blush; the backdrop ends exactly so (see drawBackdrop).
    val homeAlpha = (LocalLook.current.opacity / 100f).coerceIn(0f, 1f)
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = line) {
        val sc = this
        val scene = remember(sc, words, intro.name, intro.portrait, status) {
            StardustScene(sc, tl, measurer, coverage, words, intro.name.trim(), status, intro.portrait)
        }
        val run = sc.run
        // The iris opens from the portrait, where the eye already is.
        run.sceneOrigin = scene.lay.c
        // The host nudges the whole screen 2 dp at the hit (FULL only). The sky and its air are held still against it, so only the
        // portrait, its rings and the words shake, and no strip of what lies underneath opens at the top edge.
        val steady = sc.level == EffectLevel.FULL
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { translationY = if (steady) -nudge(run.ms - tl.hit) * scene.dp else 0f }.drawBehind {
                scene.drawBackdrop(this, run.ms, run.amb, run.exitK(), homeAlpha)
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
        // The words leave in the iris's first moments (not before it: the welcome stays whole until the hand-off starts).
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(0f, tl.textFadeMs) }.drawBehind {
                scene.drawWords(this, run.ms, run.exitFade(0f, .5f * tl.textFadeMs))
            },
        )
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
 * name's sheen and a pulse of shooting stars and sparks at 70 %.
 *
 * The exit starts at 77 % (the bloom 180 ms × [str] before it) and takes the rest; at 8 s the iris is capped at 1.6 × the 3 s
 * one (1.1 s), so it starts at 86 % there.
 */
internal class StardustTimeline private constructor(val total: Float) {
    /** How much longer each move is than at 3 s. */
    val str: Float = (total / 3000f).coerceIn(MIN_STRETCH, MAX_STRETCH)
    /** The impacts' (pop, flash, burst, sparks, streaks) stretch: they stay snappy. */
    val snap: Float = (total / 3000f).coerceIn(.7f, 1.15f)
    private val longer = ((total - 3000f) / 5000f).coerceIn(0f, 1f)
    private fun mark(at3: Float, at8: Float): Float = lerp(at3, at8, longer) * total

    val exitMs: Float = min(EXIT_SHARE * total, EXIT_SHARE * 3000f * MAX_STRETCH)
    /** The iris starts here (by itself) and runs to [total]. */
    val exit: Float = total - exitMs
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
    /** The words fade over this in the iris's first moments. */
    val textFadeMs: Float = 120f * str
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

    /** The portrait's scale: from .6 on the overshoot curve, largest exactly at [hit], then resting at 1 (0 before it starts). */
    fun popScale(ms: Float): Float {
        if (ms < popStart) return 0f
        return lerp(POP_FROM, 1f, PopEase.transform(((ms - popStart) / popMs).coerceIn(0f, 1f)))
    }

    /** The portrait's opacity: whole by half way to the peak, so the flash rings round a face, never wash out a ghost. */
    fun popAlpha(ms: Float): Float = seg(ms, popStart, .5f * POP_PEAK * popMs)

    /** How far the hand-off is (0..1): the iris's own progress (scheduled or a tap's), eased as the iris opens. */
    fun handK(exitK: Float): Float = IntroEase.CubicInOut.transform(exitK)

    /**
     * The camera: from 1.02 it pushes in slowly (up to 1.07, less in a short run) until the exit, then eases back to 1 inside the
     * iris, which is exactly the home screen's crop. 1 for reduced motion.
     */
    fun zoom(ms: Float, hk: Float, reduced: Boolean): Float {
        if (reduced) return 1f
        val push = PUSH * (exit / 2300f).coerceIn(.4f, 1f)
        return lerp(1.02f + push * seg(ms, 0f, exit, IntroEase.SineInOut), 1f, hk)
    }

    /** When the bloom starts: [bloomStart], or at a tap that came before it ([exitAt] on the run's clock, -1 for none). */
    fun bloomFrom(exitAt: Float): Float = if (exitAt in 0f..bloomStart) exitAt else bloomStart

    /**
     * The exit's soft white bloom from the portrait, 0..1: up over [bloomMs] to the iris, then down over the iris's first half.
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
        /** The exit's share of the run: the iris opens at 77 %. */
        const val EXIT_SHARE = .23f
        const val POP_FROM = .6f
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
 * strong phone, fewer on BALANCED, few on SAVER and for the reduced-motion still (which has no sparks, they only fly). The
 * season's snow is twice the shared count (soft flakes in two depths; sparse ones read as bubbles).
 */
internal class StardustCounts(val stars: Int, val motes: Int, val sparks: Int, val petals: Int, val season: Int) {
    val total: Int get() = stars + motes + sparks + petals + season

    companion object {
        fun of(level: EffectLevel, fx: IntroFx, reduced: Boolean): StardustCounts = StardustCounts(
            stars = if (fx.sparkles) introCount(level, fx, 28, 18, 9, reduced) else 0,
            motes = introCount(level, fx, 12, 8, 4, reduced),
            sparks = if (reduced) 0 else when (level) { EffectLevel.FULL -> 10; EffectLevel.BALANCED -> 8; EffectLevel.SAVER -> 6 },
            petals = introCount(level, fx, 7, 5, 3, reduced),
            season = when (fx.season) {
                IntroSeason.SNOW -> introCount(level, fx, 20, 14, 8, reduced)
                IntroSeason.SAKURA -> introCount(level, fx, 14, 10, 6, reduced)
                IntroSeason.NONE -> 0
            },
        )
    }
}

/** The words of a run, measured once at the layout's scale. */
internal class StardustTexts(
    measurer: TextMeasurer, density: Float, fontScale: Float, lay: StardustLayout, coverage: IntArray, words: String, name: String, status: String,
) {
    private val mm = IntroMeasure(measurer, density, fontScale, lay.s, coverage)
    val hasName: Boolean = name.isNotEmpty()
    /** 歡迎回來: at 34 sp over the name (46 when there is no name), smaller only when long. */
    val greet: GlyphRun = mm.fit(words, IntroFonts.hand(lay.greetSp), .08f, lay.greetSp, 22f, lay.textW).also { penInk(it) }
    /** What still does not fit at 22 sp is drawn smaller as a whole. */
    val greetScale: Float = if (greet.width > lay.textW) lay.textW / greet.width else 1f
    /** A white outline and a thin blush line inside it: ink anti-aliased straight onto white made a grey fringe. */
    val greetOutline: Stroke = stickerStroke(greet, .10f)
    val greetInline: Stroke = Stroke(1.4f * density + greet.thickPx, join = StrokeJoin.Round)
    val sticker: IntroSticker? =
        if (hasName) IntroSticker(mm, name, lay.textW, lay.nameSp, 32f, density).also { st -> st.lines.forEach { penInk(it) } } else null
    /**
     * 正在準備你的工作台 is the one helper line set in the body font, in ink and lightly spaced: in the handwriting even at 18 sp
     * 準 read as a scribble and 的 as ㄅㄋ. The greeting and the name keep the handwriting.
     */
    val status: GlyphRun = mm.run(status, IntroFonts.sans(STATUS_SP, FontWeight.Medium), .04f)
    /** The portrait without a picture: the name's first character, or the sakura alone (no name, or an emoji first). */
    val monogram: GlyphRun? = graphemes(name).firstOrNull()?.takeIf { !isEmoji(it) }?.let {
        mm.run(if (isLatin(it)) it.uppercase() else it, IntroFonts.hand(62f), 0f).also { m -> penInk(m) }
    }
    val monogramStroke: Stroke? = monogram?.let { stickerStroke(it) }
    val monogramInline: Stroke? = monogram?.let { Stroke(1.4f * density + it.thickPx, join = StrokeJoin.Round) }

    /**
     * A character the pen does not have (a name's 桜 or kana) falls back to the system font and was drawn without the pen's
     * thickening, so a long name's sticker looked pieced together from a heavy and a light face. It gets some of it here: the
     * system font is already a Regular, and the pen's whole thickening made it the heavier of the two.
     */
    private fun penInk(run: GlyphRun) {
        val want = FALLBACK_INK * IntroFonts.thickEm(run.fontPx / mm.pxPerSp) * run.fontPx
        for (i in 0 until run.size) if (run.thick[i] <= 0f && !run.emoji[i] && run.text[i].isNotBlank()) run.thick[i] = want
    }

    companion object {
        const val STATUS_SP = 15f
        /** How much of the pen's thickening a fallback character gets. */
        const val FALLBACK_INK = .45f
    }
}

/** The backdrop's focus (0..1 of the picture) and where on the screen it goes (0..1), [hk] into the hand-off. */
internal class StardustFocus(val fy: Float, val ty: Float)

/**
 * Where the picture's crop is held. Upright, on the face ([faceY]) while the camera pushes in, easing to the home screen's own
 * crop ([homeY], ContentScale.Crop with the 對齊 bias) inside the iris. On a wide screen the face is put at the screen's middle and
 * stays there: the home's centred crop of a tall picture is the subject's chest, which is no first screen.
 */
internal fun stardustFocus(wide: Boolean, faceY: Float, homeY: Float, hk: Float): StardustFocus =
    if (wide) StardustFocus(faceY, .5f) else lerp(faceY, homeY, hk).let { StardustFocus(it, it) }

/**
 * One run's drawing: built once per screen and words (a remember keyed on the IntroScope), drawn from draw lambdas. Every moving
 * thing is a pure function of the clock and a fixed hash ([ih]), and a frame allocates nothing.
 */
internal class StardustScene(
    sc: IntroScope, val tl: StardustTimeline, measurer: TextMeasurer, coverage: IntArray, words: String, name: String, status: String,
    portrait: Boolean,
) {
    val dp: Float = sc.density.density
    val lay = StardustLayout(sc.widthPx, sc.heightPx, dp, sc.insetTop, sc.insetBottom, name.isNotEmpty())
    val texts = StardustTexts(measurer, dp, sc.density.fontScale, lay, coverage, words, name, status)
    private val reduced = sc.reduced
    private val w = sc.widthPx
    private val h = sc.heightPx
    private val s = lay.s
    private val pic: ImageBitmap? = sc.picture
    private val sky = introSky(h)
    private val place = Placement()
    private val faceY = faceFocus(sc.align)
    private val homeY = (1f + sc.align.bias) / 2f
    /** The portrait: the picture's face cropped round (with 歡迎畫面用背景圖當頭像 on), else the monogram plate. */
    private val face: ImageBitmap? = pic?.takeIf { portrait }?.let { faceCrop(it, (2f * lay.r).roundToInt(), faceY) }
    /**
     * A small soft copy of the picture (about 7 dp a pixel on screen), drawn behind the portrait: the picture's own face sat
     * right behind the portrait, so the face showed twice. Not on SAVER (a layer every frame).
     */
    private val soft: ImageBitmap? = pic?.takeIf { sc.level != EffectLevel.SAVER }?.let { p ->
        val k = max(w / p.width, h / p.height)
        softCopy(p, (p.width * k / (14f * dp)).roundToInt().coerceAtLeast(8))
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
    private val barW = max(min(lay.textW * .6f, 176f * dp * s), statusW * .9f)
    private val barH = 4f * dp * s
    private val pillW = max(statusW, barW) + 40f * dp * s

    init {
        val g = texts.greet
        val st = texts.sticker
        val inkUnder = (g.baseline + StardustLayout.INK_BOTTOM * g.fontPx) * texts.greetScale
        // The pill: 10 dp over the words' box, the bar 9 dp under it, 12 dp under the bar.
        pillH = 10f * dp * s + texts.status.height + 9f * dp * s + barH + 12f * dp * s
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
        statusTop = pillTop + 10f * dp * s
        showsStatus = (tl.showsStatus || reduced) && pillTop + pillH <= lay.bottom - 56f * dp
    }

    private val barLeft = lay.textX - barW / 2f
    private val barTop = statusTop + texts.status.height + 9f * dp * s
    private val barFill = Brush.horizontalGradient(
        0f to Sakura.Primary, 1f to lerpColor(Sakura.Primary, Sakura.White, .45f), startX = barLeft, endX = barLeft + barW,
    )

    /** The widest line of the type (the greeting or the name). */
    private val textBlockW = max(texts.greet.width * texts.greetScale, texts.sticker?.width ?: 0f)

    /** The type's block, which the stars keep clear of. */
    private val textRect = Rect(lay.textX - lay.textW / 2f, greetTop - 8f * dp, lay.textX + lay.textW / 2f, textBottom + 10f * dp)

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

    // The picture's veil: strongest behind the type's column (55 %), 20 % at the top and the edges.
    private val veilC = Offset(lay.textX, if (lay.wide) h / 2f else (lay.c.y + textBottom) / 2f)
    private val veilRx = if (lay.wide) .42f * w else .78f * w
    private val veilRy = if (lay.wide) .80f * h else .40f * h

    private fun clear(x: Float, y: Float): Boolean {
        val dx = x - lay.c.x
        val dy = y - lay.c.y
        if (dx * dx + dy * dy < (lay.ring2 + 14f * dp) * (lay.ring2 + 14f * dp)) return false
        return !textRect.inflate(10f * dp).contains(Offset(x, y))
    }

    // ---- particles: counts by the effect level (and the user's 數量) ----
    private val counts = StardustCounts.of(sc.level, sc.fx, reduced)
    private val starN = counts.stars
    private val starX = FloatArray(starN)
    private val starY = FloatArray(starN)
    /** Mostly small, every fifth a big one. */
    private val starR = FloatArray(starN) { (if (it % 5 == 0) 5f + 2.5f * ih(it, 203) else 2f + 2.4f * ih(it, 203)) * dp * s }

    init {
        for (i in 0 until starN) {
            var x = 0f
            var y = 0f
            for (t in 0 until 6) {
                x = (.04f + .92f * ih(i, 201 + 17 * t)) * w
                y = (.04f + .90f * ih(i, 202 + 17 * t)) * h
                if (clear(x, y)) break
            }
            starX[i] = x
            starY[i] = y
        }
    }

    private val moteN = counts.motes
    private val sparkN = counts.sparks
    private val drift = IntroDrift(w, h, dp, counts.petals, sc.fx, salt = 40)
    private val season = StardustSeason(w, h, dp, sc.fx.season, counts.season, sc.fx)
    /** The season's near layer (bigger flakes or petals) is drawn in front of the portrait. */
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
    /** The portrait with its rim (and its 2 dp bob), which the season's near layer passes behind. */
    private val portraitHole = Path().apply { addOval(Rect(lay.c, lay.r + lay.rim + 4f * dp)) }
    private val hair = Stroke(1f * dp)
    private val arcBase = Stroke(5.5f * dp * s, cap = StrokeCap.Round)
    /** The white under the aqua arc: wide enough that the aqua's soft edge never reaches the pink. */
    private val arcWide = Stroke(6.5f * dp * s, cap = StrokeCap.Round)
    private val arcCore = Stroke(2.6f * dp * s, cap = StrokeCap.Round)
    private val arcThin = Stroke(2f * dp * s, cap = StrokeCap.Round)
    private val ringLine = Stroke(1.5f * dp)
    private val ringGlow = Stroke(4f * dp)
    private val burstLine = Stroke(2f * dp)
    private val plateLine = Stroke(1f * dp / (.78f * lay.r), join = StrokeJoin.Round)
    private val veinLine = Stroke(1.25f * dp / (.78f * lay.r), cap = StrokeCap.Round)
    /** The outer ring's dots, round the origin (drawn turned, at the portrait's centre). */
    private val ringDots: List<Offset> = List(72) { i ->
        val a = i * (2f * PI.toFloat() / 72f)
        Offset(cos(a) * lay.ring2, sin(a) * lay.ring2)
    }
    private val layerPaint = Paint()
    private val monoPaint = Paint()

    // ---- layer 1: the backdrop ----

    /**
     * The picture (pushing in on the face, then easing to the home crop at [homeAlpha], the home screen's own picture strength,
     * under the home screen's own scrim), softened behind the portrait, under a blush veil that is strongest behind the type and
     * white glows behind the portrait and the type. The veil, the softening and the glows lift inside the exit iris, as it opens.
     * Without a picture: the blush sky and its aurora, easing to the plain blush the home screen stands on.
     */
    fun drawBackdrop(scope: DrawScope, ms: Float, amb: Float, exitK: Float, homeAlpha: Float) = with(scope) {
        val hk = tl.handK(exitK)
        val inK = if (reduced) 1f else seg(ms, 0f, 360f * tl.str, IntroEase.SineInOut)
        val veil = inK * (1f - hk)
        // Opaque first, and a little larger than the screen, so nothing underneath ever shows at an edge.
        val pad = 4f * dp
        drawRect(Sakura.Background, Offset(-pad, -pad), Size(w + 2f * pad, h + 2f * pad))
        val p = pic
        if (p == null) {
            drawRect(sky, alpha = veil)
            aurora(amb, veil, aqua = true)
            glows(.6f * veil)
            return@with
        }
        val f = stardustFocus(lay.wide, faceY, homeY, hk)
        cover(p.width.toFloat(), p.height.toFloat(), w, h, tl.zoom(ms, hk, reduced), .5f, f.fy, .5f * w, f.ty * h, place)
        val a = homeAlpha * inK
        withTransform({ translate(place.ox, place.oy); scale(place.k, place.k, Offset.Zero) }) { drawImage(p, alpha = a) }
        if (veil > 0f) soften(p, a, veil)
        drawRect(HomeScrim, alpha = inK)
        if (veil <= 0f) return@with
        drawRect(Sakura.Background.copy(alpha = VEIL_EDGE * veil))
        withTransform({ translate(veilC.x, veilC.y); scale(veilRx, veilRy, Offset.Zero) }) { drawCircle(VeilCore, 1f, Offset.Zero, alpha = veil) }
        aurora(amb, .6f * veil, aqua = false)
        glows(.8f * veil)
    }

    /** The soft copy behind the portrait, faded out at its edge, over the same blush as the picture itself. */
    private fun DrawScope.soften(p: ImageBitmap, alpha: Float, veil: Float) {
        val sp = soft ?: return
        val c = lay.c
        val big = softR
        drawIntoCanvas { canvas ->
            layerPaint.alpha = veil
            canvas.saveLayer(Rect(c.x - big, c.y - big, c.x + big, c.y + big), layerPaint)
            drawRect(Sakura.Background, Offset(c.x - big, c.y - big), Size(2f * big, 2f * big))
            withTransform({
                translate(place.ox, place.oy)
                scale(place.k * p.width / sp.width, place.k * p.height / sp.height, Offset.Zero)
            }) { drawImage(sp, alpha = alpha) }
            // The mask over the whole layer: a circle drawn with DstIn left the square's corners untouched (a frosted square).
            withTransform({ translate(c.x, c.y); scale(big, big, Offset.Zero) }) {
                drawRect(SoftMask, Offset(-1f, -1f), Size(2f, 2f), blendMode = BlendMode.DstIn)
            }
            canvas.restore()
        }
    }

    /** Soft white light behind the portrait and behind the type, so both read over a busy picture. */
    private fun DrawScope.glows(alpha: Float) {
        withTransform({ translate(lay.c.x, lay.c.y); scale(lay.ring2 * 1.35f, lay.ring2 * 1.35f, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .5f * alpha)
        }
        val cy = (textRect.top + textRect.bottom) / 2f
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
        for (i in 0 until starN) {
            val period = 2.4f + 3.6f * ih(i, 204)
            val tw = sin(tau * (amb / period + ih(i, 205))).coerceAtLeast(0f)
            star(Offset(starX[i], starY[i]), starR[i] * (.75f + .25f * tw), a * (.22f + .78f * tw), 45f * ih(i, 206))
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
        drift.draw(this, amb + 1.4f * gust, .9f * a)
        season.draw(this, amb, gust, a, near = false, tint = 0f)
    }

    /**
     * The season's near layer: bigger flakes or petals in front of the rings, fading with the exit. They pass behind the portrait
     * itself (a flake's glow or a petal's white rim over the monogram's ink read grey); the flakes' aqua fades as the bloom rises.
     */
    fun drawNear(scope: DrawScope, ms: Float, amb: Float, exitK: Float, exitAt: Float) = with(scope) {
        val a = (if (reduced) 1f else seg(ms, 80f * tl.str, 500f * tl.str)) * (1f - tl.handK(exitK))
        val gust = if (reduced) 0f else tl.gust(ms)
        val tint = if (reduced) 1f else (1f - tl.bloomUp(ms, exitAt)).let { it * it * it }
        clipPath(portraitHole, ClipOp.Difference) { season.draw(this, amb, gust, a, near = true, tint = tint) }
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
        val up = if (reduced) 0f else tl.bloomUp(ms, exitAt)
        val c = lay.c
        val r = lay.r
        val tau = 2f * PI.toFloat()
        val inK = if (reduced) 1f else seg(ms, tl.popStart, 420f * tl.snap)
        if (inK <= 0f) return@with
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
                drawCircle(Sakura.Primary, rr + sw / 2f, c, alpha = .6f * (1f - fu), style = hair)
            }
        }
        rings(ms, amb, up)
        portrait(ms, amb, up)
    }

    /** A burst ring from the rim (.88 → 2.1 of the portrait in 1.25 s) from [at], at [k] strength. */
    private fun DrawScope.burst(ms: Float, at: Float, k: Float) {
        val bu = (ms - at) / (1250f * tl.snap)
        if (bu !in 0f..1f) return
        val a = k * if (bu < .14f) bu / .14f else 1f - (bu - .14f) / .86f
        val rr = (lay.r + lay.rim) * lerp(.88f, 2.1f, BurstEase.transform(bu))
        drawCircle(Sakura.Primary, rr, lay.c, alpha = .5f * a, style = ringGlow)
        drawCircle(Sakura.White, rr, lay.c, alpha = a, style = burstLine)
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
                drawArc(Sakura.White, 70f, 50f, false, tl1, sz1, alpha = (1.6f * a).coerceAtMost(1f), style = arcWide)
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
     * Sparks flung out from behind the portrait from [at], each its own way (± 9°), 34 to 80 dp out, gone in 1.15 s; white stars,
     * every third a pink one. [salt] picks another set; [k] is their strength.
     */
    private fun DrawScope.sparks(ms: Float, at: Float, salt: Int, k: Float) {
        val c = lay.c
        val base = lay.r + lay.rim
        for (i in 0 until sparkN) {
            val start = at + 180f * tl.snap * ih(i, salt + 1)
            val u = (ms - start) / (1150f * tl.snap)
            if (u <= 0f || u >= 1f) continue
            val e = SparkEase.transform(u)
            val ang = (-90f + (i + .5f * (salt % 2)) * 360f / sparkN + (ih(i, salt + 2) - .5f) * 18f) * (PI.toFloat() / 180f)
            val dist = base * .92f + lerp(6f, 34f + 46f * ih(i, salt + 3), e) * dp * s
            val p = Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist)
            val a = k * if (u < .16f) u / .16f else 1f - (u - .16f) / .84f
            val rr = lerp(.4f, 1f, e) * (5f + 3f * ih(i, salt + 4)) * dp * s
            if (i % 3 == 2) introSparkle(sparkle, p, rr, Sakura.Primary, a, 90f * u) else star(p, rr, a, 90f * u)
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
        withTransform({ translate(lay.c.x, lay.c.y); scale(rr, rr, Offset.Zero) }) {
            drawCircle(BloomBrush, 1f, Offset.Zero, alpha = b)
        }
    }

    // ---- layer 4: the words ----

    /** [ink] is the status line's own fade on exit: its ink goes before its pill (ink fading straight onto the aqua read grey). */
    fun drawWords(scope: DrawScope, ms: Float, ink: Float = 1f) = with(scope) {
        greeting(ms)
        underline(ms)
        texts.sticker?.let { st -> name(st, ms) }
        if (showsStatus) status(ms, ink)
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
                        drawGlyph(g, i, at, Sakura.Text, 1f)
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

    /** 正在準備你的工作台 with three dots blinking 200 ms apart, and a little bar that is full when the exit starts. */
    private fun DrawScope.status(ms: Float, ink: Float) {
        // The pill comes first and the words after it (and on exit they go first): ink fading straight over the light aqua of the
        // sky read grey.
        val pa = if (reduced) 1f else seg(ms, tl.statusIn, 200f * tl.str)
        val a = ink * if (reduced) 1f else seg(ms, tl.statusIn + 140f * tl.str, 300f * tl.str)
        if (pa <= 0f) return
        val run = texts.status
        val left = lay.textX - statusW / 2f
        val top = statusTop
        // A soft blush-white pill behind both, so they read over a busy picture (on pure white the ink's soft edge read grey).
        drawRoundRect(PillTint.copy(alpha = .9f * pa), Offset(lay.textX - pillW / 2f, pillTop), Size(pillW, pillH), CornerRadius(pillH / 2f))
        drawRun(run, left, top, Sakura.Text, a)
        val cy = top + run.baseline - 4f * dp * s
        for (j in 0 until 3) {
            val ph = if (reduced) .5f else (((ms - tl.statusIn - 200f * j) % 1400f) + 1400f) % 1400f / 1400f
            val blink = .25f + .75f * (1f - abs(ph - .5f) * 2f)
            drawCircle(Sakura.Primary, 2.2f * dp * s, Offset(left + run.width + 6f * dp * s + 3f * dp * s + j * dotGap, cy), alpha = a * blink)
        }
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

    companion object {
        private const val STREAK_DEG = 22f
        private val STREAK_RAD = STREAK_DEG * PI.toFloat() / 180f
        /** The veil's strength at the top and the edges (behind the type it adds up to about .55). */
        private const val VEIL_EDGE = .20f
    }
}

/**
 * The season for this look, bigger and softer than the shared layer (the art director: snow read as bubbles or targets, the
 * blossoms as flat stickers). Winter: round flakes, a white core with a soft aqua edge inside a white glow (aqua never straight on
 * the pink), in two depths: small slow ones behind the portrait, bigger quicker ones in front of it. Spring (with petals on): the
 * look's petals tumbling as they fall, in three sizes, the small behind and the big in front, swept sideways by the pop's gust.
 */
private class StardustSeason(private val w: Float, private val h: Float, private val dp: Float, season: IntroSeason, count: Int, fx: IntroFx) {
    private val kind = if (season == IntroSeason.SAKURA && !fx.petals) IntroSeason.NONE else season
    private val n = if (kind == IntroSeason.NONE) 0 else count
    private val petal = Kawaii.petalPath()
    val active: Boolean get() = n > 0

    /** Every third thing (and the big petals) is near. */
    private fun near(i: Int): Boolean = if (kind == IntroSeason.SNOW) i % 5 >= 3 else i % 3 == 2

    /** [tint] scales the flakes' aqua (0 for the far ones: the rings' pink over them read grey). */
    fun draw(scope: DrawScope, amb: Float, gust: Float, alpha: Float, near: Boolean, tint: Float) = with(scope) {
        if (n == 0 || alpha <= 0f) return@with
        val margin = 24f * dp
        val spanX = w + 2f * margin
        val spanY = h + 2f * margin
        for (i in 0 until n) {
            if (near(i) != near) continue
            val hh = ih(i, 160)
            if (kind == IntroSeason.SNOW) {
                val fall = (if (near) 44f else 24f) * dp * (.8f + .4f * hh)
                val y = wrap(ih(i, 161) * spanY + amb * fall, spanY) - margin
                val sway = (if (near) 14f else 8f) * dp * sin(amb * (.7f + .5f * hh) + 6.28f * hh)
                val x = wrap(ih(i, 162) * spanX + sway + gust * (if (near) 30f else 14f) * dp, spanX) - margin
                // The flake's whole glow; its solid white is .62 of it (4 to 10 dp across). The aqua tint lies only on that solid
                // white and fades much faster (a half-faded aqua over the pink read grey).
                val r = (if (near) 6f + 2.5f * ih(i, 163) else 3.5f + 1.5f * ih(i, 163)) * dp
                withTransform({ translate(x, y); scale(r, r, Offset.Zero) }) {
                    drawCircle(SnowFlake, 1f, Offset.Zero, alpha = alpha)
                    if (tint > 0f) drawCircle(SnowTint, 1f, Offset.Zero, alpha = alpha * alpha * alpha * tint)
                }
            } else {
                val size = when (i % 3) { 0 -> 6f; 1 -> 8.5f; else -> 11f } * dp
                val fall = (if (near) 40f else 26f) * dp * (.75f + .5f * hh)
                val y = wrap(ih(i, 164) * spanY + amb * fall, spanY) - margin
                val sway = (12f + 10f * ih(i, 165)) * dp * sin(amb * (.9f + .5f * hh) + 6.28f * hh)
                val x = wrap(ih(i, 166) * spanX + amb * 10f * dp + sway + gust * .22f * w * (if (near) 1f else .6f), spanX) - margin
                val spin = (if (ih(i, 167) < .5f) -1f else 1f) * (50f + 70f * ih(i, 168)) * amb + 360f * ih(i, 169) + 140f * gust
                // The tumble: the petal turns edge-on and back, so it flutters instead of sliding.
                val flip = cos(6.28f * ih(i, 170) + (1.6f + 1.4f * hh) * amb)
                val color = if (ih(i, 171) < .7f) lerpColor(Sakura.Primary, Sakura.White, .25f * ih(i, 172)) else Sakura.White
                withTransform({ translate(x, y); rotate(spin, Offset.Zero); scale(.72f * size * flip, size, Offset.Zero) }) {
                    // A pink petal gets a white rim (a sixth wider), so it reads on the pink sky as well as on the white.
                    if (color != Sakura.White) withTransform({ scale(1.16f, 1.12f, Offset.Zero) }) { drawPath(petal, Sakura.White.copy(alpha = .9f * alpha)) }
                    drawPath(petal, color.copy(alpha = (if (color == Sakura.White) .95f else .92f) * alpha))
                }
            }
        }
    }

    private fun wrap(v: Float, span: Float): Float = ((v % span) + span) % span
}

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

/** The picture halved (each step a 2 × 2 average) until it is about [px] wide: drawn large, it is a soft blur. */
private fun softCopy(picture: ImageBitmap, px: Int): ImageBitmap? = runCatching {
    var b = picture.asAndroidBitmap()
    while (b.width / 2 >= px && b.height >= 4) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    b.asImageBitmap()
}.getOrNull()

/** The home screen's own scrim over its picture (FogBackground's PictureScrim), so the backdrop ends exactly as the home starts. */
private val HomeScrim = Brush.verticalGradient(
    0f to Sakura.Background.copy(alpha = .55f), .14f to Sakura.Background.copy(alpha = 0f),
    .80f to Sakura.Background.copy(alpha = 0f), 1f to Sakura.Background.copy(alpha = .65f),
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
/** The picture's veil behind the type (over the even [StardustScene.VEIL_EDGE] one: together about .55 in the middle). */
private val VeilCore = Brush.radialGradient(
    0f to Sakura.Background.copy(alpha = .44f), .5f to Sakura.Background.copy(alpha = .30f), 1f to Sakura.Background.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
/** The soft copy's mask behind the portrait: whole to .6, gone at the edge. */
private val SoftMask = Brush.radialGradient(
    0f to Sakura.White, .6f to Sakura.White, 1f to Sakura.White.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
)
/** A snowflake: solid white to .62, then a soft white glow. */
private val SnowFlake = Brush.radialGradient(
    0f to Sakura.White, .62f to Sakura.White, .78f to Sakura.White.copy(alpha = .45f), 1f to Sakura.White.copy(alpha = 0f),
    center = Offset.Zero, radius = 1f,
)
/** The flake's soft aqua band, inside its solid white (aqua between whites, never on the pink). */
private val SnowTint = lerpColor(Sakura.White, Sakura.Aqua, .65f).let { t ->
    Brush.radialGradient(
        0f to t.copy(alpha = 0f), .22f to t.copy(alpha = 0f), .42f to t, .60f to t.copy(alpha = 0f), 1f to t.copy(alpha = 0f),
        center = Offset.Zero, radius = 1f,
    )
}
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
