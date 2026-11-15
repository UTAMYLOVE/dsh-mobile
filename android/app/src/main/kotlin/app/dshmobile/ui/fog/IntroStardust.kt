package app.dshmobile.ui.fog

// Adapted from KylinQ01/dsh-startup-animation (c) 2026 KylinQ01, MIT License: the motion values of its welcome (the burst ring
// .88 → 2.1 in 1.25 s, sparks flung out over 1.15 s at ± 9°, two ping ripples .95 → 1.85 every 4.2 s, 2.1 s apart, orbit rings turning in 7 s
// and 11 s the other way, the greeting rising 14 px and untilting 4° in 620 ms, 80 ms apart, 22° streaks, the halo breathing
// .96 ↔ 1.04 in 5.2 s, the exit bloom peaking at 22 % of 380 ms and the stage leaving at 1.05 and 10 px up, and those
// cubic-bezier curves). The drawing, the colours and the layout are our own; none of its images, names or words.

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「星屑迎接」 (Stardust welcome), the startup look [IntroStyle.STARDUST]. Your own picture (or, without one, the blush sky with a
 * slow pink and aqua aurora) fades in and slowly pushes in; stars twinkle, light motes rise and a shooting star crosses it. In
 * the middle your round portrait (the picture's face, or your monogram on a sakura) pops in with a flash ring, a burst ring and
 * sparks, inside two orbit rings turning opposite ways; 「歡迎回來」 floats up one character at a time and your name stamps in as
 * a sticker under it; 「正在準備你的工作台」 waits with blinking dots and a little bar. Then it settles: the stage lifts away in a
 * soft bloom, the camera eases back to exactly the home screen's crop and veil, and the iris opens onto the same picture.
 *
 * Fixed moves and stretching holds ([StardustTimeline]); everything is a pure function of the run's clock, read only in draw
 * lambdas and graphicsLayer blocks, so a run composes once. Reduced motion draws one settled frame with nothing flying.
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
    val timing = remember(tl) { IntroTiming.of(tl.plan, still = tl.still, hit = tl.hit, announce = tl.announce) }
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
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawBackdrop(this, run.ms, run.amb, run.exitK(), homeAlpha) })
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawAmbient(this, run.ms, run.amb, run.exitK()) })
        Spacer(
            Modifier.fillMaxSize().graphicsLayer {
                val o = scene.tl.stageOut(run.ms)
                alpha = 1f - o
                scaleX = 1f + .05f * o
                scaleY = scaleX
                translationY = -10f * scene.dp * o
                transformOrigin = scene.origin
            }.drawBehind { scene.drawStage(this, run.ms, run.amb) },
        )
        if (scene.snow) Spacer(Modifier.fillMaxSize().drawBehind { scene.drawSnow(this, run.amb) })
        // The settle's soft bloom: over the stage, under the type.
        if (!sc.reduced) Spacer(Modifier.fillMaxSize().drawBehind { scene.drawBloom(this, run.ms) })
        Spacer(
            Modifier.fillMaxSize().graphicsLayer {
                val o = scene.tl.stageOut(run.ms)
                alpha = run.exitFade(40f) * (1f - scene.tl.textOut(run.ms))
                scaleX = 1f + .05f * o
                scaleY = scaleX
                translationY = -10f * scene.dp * o
                transformOrigin = scene.origin
            }.drawBehind { scene.drawWords(this, run.ms) },
        )
    }
}

/** The greeting that floats up: the user's own words (or 歡迎回來) without a trailing comma or stop, since the name follows below. */
internal fun stardustWords(welcome: String, fallback: String): String =
    welcome.trim().ifEmpty { fallback }.trimEnd(' ', '，', ',', '、', '。', '.', '!', '！', '~', '～').ifEmpty { fallback }

/**
 * The beats of one run of [IntroPlan]: ARRIVE (the portrait pops and lands: the hit), GREET (歡迎回來 starts rising), a short
 * BREATH that grows a little with long runs, NAME (the sticker stamps in), the HOLD that takes the rest of the time, SETTLE (the
 * stage lifts away, the camera eases to the home crop) and the EXIT iris. Moves keep their length from 1.8 s up (8 s only
 * holds longer); at 1.5 s they all tighten to .80.
 */
internal class StardustTimeline private constructor(val plan: IntroPlan) {
    val pace: Float = plan.pace
    val total: Float = plan.total
    /** Below 2.2 s the name stamps in faster (drawSticker's compact rhythm). */
    val compact: Boolean = total < 2200f

    /** The portrait's pop; it lands (at its largest, the hit) exactly at the end of ARRIVE. */
    val popMs: Float = 560f * pace
    val hit: Float = plan.end(ARRIVE)
    val popStart: Float = hit - POP_LAND * popMs
    val ringsIn: Float = popStart + 120f * pace
    val greetStart: Float = plan.at(GREET)
    val greetMs: Float = 620f * pace
    val nameStart: Float = plan.at(NAME)
    val holdStart: Float = plan.at(HOLD)
    val statusIn: Float = holdStart + 120f * pace
    val settle: Float = plan.at(SETTLE)
    val settleMs: Float = plan.length(SETTLE)
    val exit: Float = plan.at(EXIT)
    /** The status line and its bar only when they can be read (over half a second). */
    val showsStatus: Boolean = settle - statusIn >= 700f
    /** The reduced-motion frame: the end of the hold, everything settled. */
    val still: Float = settle - 1f
    /** The welcome line is announced as the name comes in. */
    val announce: Float = nameStart

    /** Greeting glyph [i]'s start of [n]: 80 ms apart, all within 360 ms (a long greeting would otherwise trickle). */
    fun greetAt(i: Int, n: Int): Float = greetStart + i * (if (n < 2) 0f else min(80f, 360f / (n - 1))) * pace

    /** The portrait's scale: from .25 it grows past full size to 1.10 when it lands, dips to .97 and rests (0 before it starts). */
    fun popScale(ms: Float): Float {
        if (ms < popStart) return 0f
        val u = ((ms - popStart) / popMs).coerceIn(0f, 1f)
        return when {
            u < POP_LAND -> lerp(.25f, 1.10f, IntroEase.CubicOut.transform(u / POP_LAND))
            u < .72f -> lerp(1.10f, .97f, IntroEase.SineInOut.transform((u - POP_LAND) / (.72f - POP_LAND)))
            else -> lerp(.97f, 1f, IntroEase.SineInOut.transform((u - .72f) / .28f))
        }
    }

    /** How far the hand-off is (0..1): the settle beat, or a tap's exit iris when that comes first. */
    fun handK(ms: Float, exitK: Float): Float = max(seg(ms, settle, settleMs, IntroEase.CubicInOut), exitK)

    /** The stage (portrait, rings, type) leaving: up 10 dp, to 1.05 and out within 300 ms of the settle. */
    fun stageOut(ms: Float): Float = seg(ms, settle, min(300f * pace, settleMs), IntroEase.CubicIn)

    /** The type fades a little later than the portrait, so the name is the last thing to go. */
    fun textOut(ms: Float): Float = seg(ms, settle + .25f * settleMs, .6f * settleMs)

    /**
     * The camera: from 1.02 it pushes in slowly (up to 1.07 at 3 s and longer, less in a short run) until the settle, then eases
     * back to 1, which is exactly the home screen's crop. 1 for reduced motion.
     */
    fun zoom(ms: Float, hk: Float, reduced: Boolean): Float {
        if (reduced) return 1f
        val push = PUSH * (settle / 2600f).coerceIn(.4f, 1f)
        return lerp(1.02f + push * seg(ms, 0f, settle, IntroEase.SineInOut), 1f, hk)
    }

    /** The settle's bloom (white, 0..[BLOOM]): up to its peak at 22 % of 380 ms, then down. */
    fun bloom(ms: Float): Float {
        val u = (ms - settle) / (380f * pace)
        return when {
            u <= 0f || u >= 1f -> 0f
            u < .22f -> BLOOM * u / .22f
            else -> BLOOM * (1f - IntroEase.CubicOut.transform((u - .22f) / .78f))
        }
    }

    companion object {
        const val ARRIVE = 0
        const val GREET = 1
        const val BREATH = 2
        const val NAME = 3
        const val HOLD = 4
        const val SETTLE = 5
        const val EXIT = 6
        /** Where in the pop the portrait lands. */
        const val POP_LAND = .45f
        const val PUSH = .05f
        const val BLOOM = .30f

        fun of(totalMs: Float): StardustTimeline = StardustTimeline(
            IntroPlan.of(
                totalMs,
                IntroBeat.move(280f), IntroBeat.move(340f), IntroBeat.hold(.15f), IntroBeat.move(340f),
                IntroBeat.hold(1f, 200f), IntroBeat.move(360f), IntroBeat.move(300f),
            ),
        )
    }
}

/**
 * Where everything sits, from the screen alone: the portrait's centre [c] and radius [r] with its two orbit rings, and the
 * type's column ([textX] its middle, [textW] its width). Upright, the portrait stands in the upper third and the type under its
 * rings; on a wide screen the portrait is on the left and the type on the right. [s] scales every dp (and sp) to the screen.
 */
internal class StardustLayout(val w: Float, val h: Float, val dp: Float, insetTop: Float, insetBottom: Float) {
    val wide: Boolean = w > h * 1.15f
    val top: Float = insetTop
    val bottom: Float = h - insetBottom
    private val usable = (bottom - top).coerceAtLeast(1f)
    val s: Float = if (wide) (usable / dp / 420f).coerceIn(.6f, 1.2f) else min(w / dp / 412f, usable / dp / 820f).coerceIn(.6f, 1.25f)
    val r: Float = 62f * dp * s
    /** The white ring round the portrait. */
    val rim: Float = 5f * dp * s
    val ring1: Float = r + 22f * dp * s
    val ring2: Float = r + 44f * dp * s
    val c: Offset = if (wide) Offset(w * .29f, top + usable * .5f) else Offset(w / 2f, top + usable * .335f)
    val textX: Float = if (wide) w * .67f else w / 2f
    val textW: Float = if (wide) w * .44f else min(w - 48f * dp, 400f * dp * s)

    /** The type's top: under the outer ring upright, or the [blockH] tall block centred on a wide screen. */
    fun textTop(blockH: Float): Float = if (wide) top + (usable - blockH) / 2f else c.y + ring2 + 24f * dp * s
}

/**
 * How many of each moving thing a run draws, by the effect level (and the user's 數量 and 星光 for the ambient ones): FULL on a
 * strong phone, fewer on BALANCED, few on SAVER and for the reduced-motion still (which has no sparks, they only fly).
 */
internal class StardustCounts(val stars: Int, val motes: Int, val sparks: Int, val petals: Int, val season: Int) {
    val total: Int get() = stars + motes + sparks + petals + season

    companion object {
        fun of(level: EffectLevel, fx: IntroFx, reduced: Boolean): StardustCounts = StardustCounts(
            stars = if (fx.sparkles) introCount(level, fx, 28, 18, 9, reduced) else 0,
            motes = introCount(level, fx, 12, 8, 4, reduced),
            sparks = if (reduced) 0 else when (level) { EffectLevel.FULL -> 10; EffectLevel.BALANCED -> 8; EffectLevel.SAVER -> 6 },
            petals = introCount(level, fx, 7, 5, 3, reduced),
            season = introCount(level, fx, 10, 7, 4, reduced),
        )
    }
}

/** The words of a run, measured once at the layout's scale. */
internal class StardustTexts(
    measurer: TextMeasurer, density: Float, fontScale: Float, lay: StardustLayout, coverage: IntArray, words: String, name: String, status: String,
) {
    private val mm = IntroMeasure(measurer, density, fontScale, lay.s, coverage)
    val hasName: Boolean = name.isNotEmpty()
    private val greetSp = if (hasName) 34f else 46f
    /** 歡迎回來: at 34 sp over the name (46 when there is no name), smaller only when long. */
    val greet: GlyphRun = mm.fit(words, IntroFonts.hand(greetSp), .08f, greetSp, 22f, lay.textW)
    /** What still does not fit at 22 sp is drawn smaller as a whole. */
    val greetScale: Float = if (greet.width > lay.textW) lay.textW / greet.width else 1f
    /** A white outline and a thin blush line inside it: ink anti-aliased straight onto white made a grey fringe. */
    val greetOutline: Stroke = stickerStroke(greet, .10f)
    val greetInline: Stroke = Stroke(1.4f * density + greet.thickPx, join = StrokeJoin.Round)
    val sticker: IntroSticker? = if (hasName) IntroSticker(mm, name, lay.textW, if (lay.wide) 56f else 72f, 32f, density) else null
    val status: GlyphRun = mm.run(status, IntroFonts.hand(15f, small = true), .06f)
    /** The portrait without a picture: the name's first character, or the sakura alone (no name, or an emoji first). */
    val monogram: GlyphRun? = graphemes(name).firstOrNull()?.takeIf { !isEmoji(it) }?.let {
        mm.run(if (isLatin(it)) it.uppercase() else it, IntroFonts.hand(62f), 0f)
    }
    val monogramStroke: Stroke? = monogram?.let { stickerStroke(it) }
    val monogramInline: Stroke? = monogram?.let { Stroke(1.4f * density + it.thickPx, join = StrokeJoin.Round) }
}

/**
 * One run's drawing: built once per screen and words (a remember keyed on the IntroScope), drawn from draw lambdas. Every moving
 * thing is a pure function of the clock and a fixed hash ([ih]), and a frame allocates nothing.
 */
internal class StardustScene(
    sc: IntroScope, val tl: StardustTimeline, measurer: TextMeasurer, coverage: IntArray, words: String, name: String, status: String,
    portrait: Boolean,
) {
    val dp: Float = sc.density.density
    val lay = StardustLayout(sc.widthPx, sc.heightPx, dp, sc.insetTop, sc.insetBottom)
    val texts = StardustTexts(measurer, dp, sc.density.fontScale, lay, coverage, words, name, status)
    private val reduced = sc.reduced
    private val w = sc.widthPx
    private val h = sc.heightPx
    private val s = lay.s
    private val backdrop = IntroBackdrop(sc.picture, w, h, sc.align)
    /** The portrait: the picture's face cropped round (with 歡迎畫面用背景圖當頭像 on), else the monogram plate. */
    private val face: ImageBitmap? = sc.picture?.takeIf { portrait }?.let { faceCrop(it, (2f * lay.r).roundToInt(), faceFocus(sc.align)) }
    val origin = TransformOrigin(lay.c.x / w, lay.c.y / h)

    // ---- the type's places ----
    private val greetH = texts.greet.height * texts.greetScale
    private val gap = 22f * dp * s
    private val stickerH = texts.sticker?.height ?: 0f
    private val statusGap = 34f * dp * s
    private val statusH = texts.status.height + 22f * dp * s
    private val greetTop: Float
    private val stickerTop: Float
    private val statusTop: Float
    val showsStatus: Boolean

    init {
        val block = greetH + (if (texts.sticker != null) gap + stickerH else 0f)
        greetTop = lay.textTop(block + if (lay.wide) statusGap + statusH else 0f)
        stickerTop = greetTop + greetH + gap
        val under = greetTop + block
        // Upright the status waits near the bottom (above 跳過); on a wide screen under the name.
        statusTop = if (lay.wide) under + statusGap else max(under + statusGap, lay.bottom - 150f * dp * s)
        showsStatus = tl.showsStatus && statusTop + statusH <= lay.bottom - 52f * dp
    }

    /** The widest line of the type (the greeting or the name). */
    private val textBlockW = max(texts.greet.width * texts.greetScale, texts.sticker?.width ?: 0f)

    /** The type's block, which the stars keep clear of. */
    private val textRect = Rect(lay.textX - lay.textW / 2f, greetTop - 8f * dp, lay.textX + lay.textW / 2f, greetTop + greetH + gap + stickerH + 10f * dp)

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
    private val season = IntroSeasonFx(w, h, dp, sc.fx.season, counts.season, sc.fx)
    /** Winter: the snow has its own layer over the stage ([drawSnow]). */
    val snow: Boolean = sc.fx.season == IntroSeason.SNOW && season.active

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

    // ---- the status bar ----
    private val barW = min(lay.textW * .6f, 176f * dp * s)
    private val barH = 4f * dp * s
    private val barLeft = lay.textX - barW / 2f
    private val barTop = statusTop + texts.status.height + 12f * dp * s
    private val barFill = Brush.horizontalGradient(
        0f to Sakura.Primary, 1f to lerpColor(Sakura.Primary, Sakura.White, .45f), startX = barLeft, endX = barLeft + barW,
    )

    // ---- layer 1: the backdrop ----

    /**
     * The picture (pushing in, then settling to the home crop at [homeAlpha], the home screen's own picture strength, under the
     * home screen's own scrim), with a light blush veil and white glows behind the portrait and the type while the welcome
     * shows. Without a picture: the blush sky and its aurora, fading to the plain blush the home screen stands on.
     */
    fun drawBackdrop(scope: DrawScope, ms: Float, amb: Float, exitK: Float, homeAlpha: Float) = with(scope) {
        val hk = tl.handK(ms, exitK)
        val inK = if (reduced) 1f else seg(ms, 0f, 360f * tl.pace, IntroEase.SineInOut)
        val veil = inK * (1f - hk)
        if (backdrop.hasPicture) {
            backdrop.draw(this, tl.zoom(ms, hk, reduced), homeAlpha * inK)
            drawRect(HomeScrim, alpha = inK)
            if (veil > 0f) {
                drawRect(Sakura.Background.copy(alpha = .10f * veil))
                aurora(amb, .7f * veil, aqua = false)
                glows(veil)
            }
        } else {
            drawRect(backdrop.sky, alpha = veil)
            aurora(amb, veil, aqua = true)
            glows(.6f * veil)
        }
    }

    /** Soft white light behind the portrait and behind the type, so both read over a busy picture. */
    private fun DrawScope.glows(alpha: Float) {
        withTransform({ translate(lay.c.x, lay.c.y); scale(lay.ring2 * 1.35f, lay.ring2 * 1.35f, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .5f * alpha)
        }
        val cy = (textRect.top + textRect.bottom) / 2f
        withTransform({ translate(lay.textX, cy); scale(textBlockW * .62f + 24f * dp * s, textRect.height * .72f, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .55f * alpha)
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
        val a2 = 2f * PI.toFloat() * amb / 38f + 2f
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
        val d = kotlin.math.hypot(x - aquaX, y - aquaY) / aquaR
        return ((1.2f - d) / .6f).coerceIn(0f, 1f)
    }

    // ---- layer 2: the air (stars, motes, streaks, petals, the season) ----

    fun drawAmbient(scope: DrawScope, ms: Float, amb: Float, exitK: Float) = with(scope) {
        val a = (if (reduced) 1f else seg(ms, 80f * tl.pace, 500f * tl.pace)) * (1f - tl.handK(ms, exitK))
        if (a <= 0f) return@with
        if (!reduced) sheen(ms)
        aquaX = auroraX(amb)
        aquaY = auroraY(amb)
        aquaR = if (backdrop.hasPicture) 0f else .26f * max(w, h)
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
        drift.draw(this, amb, .9f * a)
        if (!snow) season.draw(this, amb, a)
    }

    /**
     * Winter's snow falls in front of the portrait and its rings (but behind the type): a pink ring or spark over a flake's aqua
     * read grey. It never fades either (a half-faded aqua ring over the pink reads grey too): it is there from the start and the
     * iris takes it away with the rest.
     */
    fun drawSnow(scope: DrawScope, amb: Float) {
        if (snow) season.draw(scope, amb, 1f)
    }

    /** One diagonal band of white light sweeping across once as the picture arrives. */
    private fun DrawScope.sheen(ms: Float) {
        val u = seg(ms, 120f * tl.pace, 1300f * tl.pace, IntroEase.SineInOut)
        if (u <= 0f || u >= 1f) return
        val x = lerp(-.35f * w, 1.35f * w, u)
        withTransform({ translate(x, h / 2f); rotate(15f, Offset.Zero); scale(.30f * w, 1.6f * h, Offset.Zero) }) {
            drawRect(SheenBand, Offset(-.5f, -.5f), Size(1f, 1f), alpha = .45f * sin(PI.toFloat() * u))
        }
    }

    /**
     * Shooting stars: two series (every 2.6 s from 650 ms, every 3.4 s from 1.65 s), each a 700 ms streak at 22° below the
     * horizontal, right to left across the upper part of the screen; none once the settle starts.
     */
    private fun DrawScope.streaks(ms: Float, alpha: Float) {
        val life = 700f
        for (series in 0 until 2) {
            val first = if (series == 0) 650f else 1650f
            val every = if (series == 0) 2600f else 3400f
            if (ms < first) continue
            val k = ((ms - first) / every).toInt()
            val t = first + k * every
            if (ms - t >= life || t + life > tl.settle) continue
            val u = (ms - t) / life
            val n = k * 2 + series
            val dir = Offset(-cos(STREAK_RAD), sin(STREAK_RAD))
            val start = Offset((.70f + .45f * ih(n, 221)) * w, (.02f + .12f * ih(n, 222)) * h)
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
            if (t > 0f) drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .8f * alpha * (1f - kotlin.math.abs(2f * t - 1f)))
            if (t > .5f) drawCircle(AquaHalo, 1f, Offset.Zero, alpha = alpha * (2f * t - 1f))
        }
    }

    // ---- layer 3: the stage (halo, ripples, rings, the portrait, sparks) ----

    fun drawStage(scope: DrawScope, ms: Float, amb: Float) = with(scope) {
        val c = lay.c
        val r = lay.r
        val p = tl.pace
        val tau = 2f * PI.toFloat()
        val inK = if (reduced) 1f else seg(ms, tl.popStart, 420f * p)
        if (inK <= 0f) return@with
        // The halo, breathing .96 ↔ 1.04 every 5.2 s, and a pink glow under the portrait.
        val breath = 1f + .04f * sin(tau * amb / 5.2f)
        withTransform({ translate(c.x, c.y); scale(r * 1.75f * breath, r * 1.75f * breath, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = inK)
        }
        // Two ripples from the rim (4.2 s each, 2.1 s apart), from just after the hit until the settle.
        if (!reduced) {
            var t = tl.hit + 240f * p
            while (t <= ms && t < tl.settle) {
                val u = (ms - t) / 4200f
                if (u < 1f) {
                    val a = if (u < .16f) .7f * u / .16f else .7f * (1f - (u - .16f) / .84f)
                    val rr = (r + lay.rim) * lerp(.95f, 1.85f, PingEase.transform(u))
                    drawCircle(Sakura.Primary, rr, c, alpha = .55f * a, style = ringGlow)
                    drawCircle(Sakura.White, rr, c, alpha = a, style = ringLine)
                }
                t += 2100f
            }
        }
        // The hit's burst of light behind the portrait.
        if (!reduced) {
            val hb = flash(ms, tl.hit, 1f) + .6f * (1f - seg(ms, tl.hit, 520f * p, IntroEase.CubicOut)) * seg(ms, tl.hit - 60f * p, 60f * p)
            if (hb > 0f) withTransform({ translate(c.x, c.y); scale(lay.ring2 * 1.9f, lay.ring2 * 1.9f, Offset.Zero) }) {
                drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = hb.coerceAtMost(1f))
            }
        }
        // The sparks, the burst ring and the flash ring all pass under the orbit rings: their pink over the aqua arc read grey.
        if (!reduced) sparks(ms)
        // The burst ring (.88 → 2.1 of the portrait in 1.25 s) and the flash ring (out to 1.4 in 420 ms) from the hit.
        if (!reduced) {
            val bu = (ms - tl.hit + 40f * p) / (1250f * p)
            if (bu in 0f..1f) {
                val a = if (bu < .14f) bu / .14f else 1f - (bu - .14f) / .86f
                val rr = (r + lay.rim) * lerp(.88f, 2.1f, BurstEase.transform(bu))
                drawCircle(Sakura.Primary, rr, c, alpha = .5f * a, style = ringGlow)
                drawCircle(Sakura.White, rr, c, alpha = a, style = burstLine)
            }
            val fu = (ms - tl.hit) / (420f * p)
            if (fu in 0f..1f) {
                val e = IntroEase.ExpoOut.transform(fu)
                val sw = lerp(14f, 1f, e) * dp * s
                val rr = (r + lay.rim) * lerp(1f, 1.4f, e) + sw / 2f
                drawCircle(Sakura.White, rr, c, alpha = .95f * (1f - fu), style = Stroke(sw))
                drawCircle(Sakura.Primary, rr + sw / 2f, c, alpha = .6f * (1f - fu), style = hair)
            }
        }
        rings(ms, amb)
        portrait(ms, amb)
    }

    /**
     * The two orbit rings, coming in at [StardustTimeline.ringsIn]: the inner one (7 s a turn) a pink hairline with a white arc
     * and an aqua arc on white, the outer one (11 s, the other way) a ring of dots with a pink arc and a short white one.
     */
    private fun DrawScope.rings(ms: Float, amb: Float) {
        val k = if (reduced) 1f else seg(ms, tl.ringsIn, 520f * tl.pace)
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
                // The white under the aqua comes in first and the aqua as the square, and as the stage leaves (its layer fading
                // as a whole) the aqua is gone before the white: aqua never sits half-faded on the pink.
                val out = 1f - tl.stageOut(ms)
                drawArc(Sakura.White, 70f, 50f, false, tl1, sz1, alpha = (1.6f * a).coerceAtMost(1f), style = arcWide)
                drawArc(Sakura.Aqua, 70f, 50f, false, tl1, sz1, alpha = a * a * out * out * out, style = arcThin)
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
     * The portrait: a white rim with a fine pink line round it and a soft pink shadow under it, popping in ([popScale]) from
     * 16 dp lower, then bobbing ±2 dp (5.4 s); a camera flash at the hit, and a glass shine every 4.2 s while the welcome holds.
     */
    private fun DrawScope.portrait(ms: Float, amb: Float) {
        val pk = if (reduced) 1f else tl.popScale(ms)
        if (pk <= 0f) return
        val c = lay.c
        val r = lay.r
        val a = if (reduced) 1f else seg(ms, tl.popStart, .18f * tl.popMs)
        val land = if (reduced) 1f else IntroEase.CubicOut.transform(((ms - tl.popStart) / (StardustTimeline.POP_LAND * tl.popMs)).coerceIn(0f, 1f))
        val dy = 16f * dp * s * (1f - land) + 2f * dp * s * sin(2f * PI.toFloat() * amb / 5.4f)
        val body: DrawScope.() -> Unit = {
            withTransform({ translate(c.x, c.y + .30f * r); scale(1.25f * r, 1.0f * r, Offset.Zero) }) { drawCircle(PinkShadow, 1f, Offset.Zero) }
            drawCircle(Sakura.Primary, r + lay.rim + 1.2f * dp, c)
            drawCircle(Sakura.White, r + lay.rim, c)
            clipPath(faceClip) {
                val f = face
                if (f != null) drawImage(f, dstOffset = IntOffset((c.x - r).roundToInt(), (c.y - r).roundToInt()), dstSize = IntSize((2f * r).roundToInt(), (2f * r).roundToInt()))
                else plate()
                shine(ms)
            }
            if (!reduced) {
                // Blush white: ink washed with pure white turned grey for a moment.
                val fl = flash(ms, tl.hit, .85f)
                if (fl > 0f) drawCircle(FlashTint, r + lay.rim, c, alpha = fl)
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

    /** No picture (or 歡迎畫面用背景圖當頭像 off): a pink sakura on a white-to-blush plate, the monogram on it as a sticker. */
    private fun DrawScope.plate() {
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
        val at = Offset(c.x - mono.advance[0] / 2f, c.y + .36f * mono.fontPx - mono.baseline)
        val drop = at + Offset(0f, .05f * mono.fontPx)
        drawGlyph(mono, 0, drop, Sakura.Primary, .55f, stroke = texts.monogramStroke)
        drawGlyph(mono, 0, at, Sakura.White, 1f, stroke = texts.monogramStroke)
        drawGlyph(mono, 0, at, Sakura.Background, 1f, stroke = texts.monogramInline)
        drawGlyph(mono, 0, at, Sakura.Text, 1f)
    }

    /** The glass shine across the portrait: at hit + 700 ms, then every 4.2 s while there is time before the settle. */
    private fun DrawScope.shine(ms: Float) {
        if (reduced) return
        var t = tl.hit + 700f * tl.pace
        if (tl.settle - t < 900f) return
        while (t + 4200f <= ms && tl.settle - (t + 4200f) > 900f) t += 4200f
        val u = seg(ms, t, 760f, IntroEase.CubicInOut)
        if (u <= 0f || u >= 1f) return
        val c = lay.c
        val r = lay.r
        withTransform({ translate(lerp(c.x - 1.7f * r, c.x + 1.7f * r, u), c.y); rotate(20f, Offset.Zero); scale(.6f * r, 2.6f * r, Offset.Zero) }) {
            drawRect(SheenBand, Offset(-.5f, -.5f), Size(1f, 1f), alpha = .7f)
        }
    }

    /**
     * The sparks: flung out from behind the portrait at the hit, each its own way (± 9°), 34 to 80 dp out, gone in 1.15 s;
     * white stars, every third a pink one.
     */
    private fun DrawScope.sparks(ms: Float) {
        val c = lay.c
        val base = lay.r + lay.rim
        for (i in 0 until sparkN) {
            val start = tl.hit - 40f * tl.pace + 180f * tl.pace * ih(i, 231)
            val u = (ms - start) / (1150f * tl.pace)
            if (u <= 0f || u >= 1f) continue
            val e = SparkEase.transform(u)
            val ang = (-90f + i * 360f / sparkN + (ih(i, 232) - .5f) * 18f) * (PI.toFloat() / 180f)
            val dist = base * .92f + lerp(6f, 34f + 46f * ih(i, 233), e) * dp * s
            val at = Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist)
            val a = if (u < .16f) u / .16f else 1f - (u - .16f) / .84f
            val rr = lerp(.4f, 1f, e) * (5f + 3f * ih(i, 234)) * dp * s
            if (i % 3 == 2) introSparkle(sparkle, at, rr, Sakura.Primary, a, 90f * u) else star(at, rr, a, 90f * u)
        }
    }

    // ---- the settle's bloom ----

    fun drawBloom(scope: DrawScope, ms: Float) = with(scope) {
        val b = tl.bloom(ms)
        if (b <= 0f) return@with
        withTransform({ translate(lay.c.x, lay.c.y); scale(max(w, h) * .75f, max(w, h) * .75f, Offset.Zero) }) {
            drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = b)
        }
    }

    // ---- layer 4: the words ----

    fun drawWords(scope: DrawScope, ms: Float) = with(scope) {
        greeting(ms)
        texts.sticker?.let { st -> drawSticker(st, lay.textX, stickerTop, ms, tl.nameStart, tl.pace, tl.compact, reduced, tl.settle) }
        if (showsStatus) status(ms)
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

    /** 正在準備你的工作台 with three dots blinking 200 ms apart, and a little bar that is full when the settle starts. */
    private fun DrawScope.status(ms: Float) {
        // The pill comes first and the words after it: ink fading in straight over the light aqua of the sky read grey.
        val pa = if (reduced) 1f else seg(ms, tl.statusIn, 200f * tl.pace)
        val a = if (reduced) 1f else seg(ms, tl.statusIn + 140f * tl.pace, 300f * tl.pace)
        if (pa <= 0f) return
        val run = texts.status
        val dotGap = 7f * dp * s
        val dotsW = 6f * dp * s + 2f * dotGap
        val total = run.width + dotsW
        val left = lay.textX - total / 2f
        val top = statusTop
        // A soft blush-white pill behind both, so they read over a busy picture (on pure white the ink's soft edge read grey).
        val pillW = max(total, barW) + 36f * dp * s
        drawRoundRect(
            PillTint.copy(alpha = .85f * pa), Offset(lay.textX - pillW / 2f, top - 8f * dp * s),
            Size(pillW, barTop + barH + 10f * dp * s - (top - 8f * dp * s)), CornerRadius(16f * dp * s),
        )
        drawRun(run, left, top, Sakura.TextSecondary, a)
        val cy = top + run.baseline - 3f * dp * s
        for (j in 0 until 3) {
            val ph = if (reduced) .5f else (((ms - tl.statusIn - 200f * j) % 1400f) + 1400f) % 1400f / 1400f
            val blink = .25f + .75f * (1f - kotlin.math.abs(ph - .5f) * 2f)
            drawCircle(Sakura.Primary, 2f * dp * s, Offset(left + run.width + 5f * dp * s + j * dotGap, cy), alpha = a * blink)
        }
        val corner = CornerRadius(barH / 2f)
        drawRoundRect(Sakura.White, Offset(barLeft, barTop), Size(barW, barH), corner, alpha = a)
        drawRoundRect(Sakura.Primary, Offset(barLeft, barTop), Size(barW, barH), corner, alpha = .45f * a, style = hair)
        val f = if (reduced) .92f else seg(ms, tl.statusIn, tl.settle - tl.statusIn, IntroEase.CubicOut)
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
    }
}

/** [this] turned by [deg] degrees about the origin. */
private fun Offset.rotated(deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
}

/** Where a picture's face usually is, by the 對齊 setting (a full-body picture has the face in its upper third). */
private fun faceFocus(align: PictureAlign): Float = when (align) {
    PictureAlign.TOP -> .25f
    PictureAlign.CENTER -> .35f
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
/** The camera flash over the portrait: white with a touch of blush. */
private val FlashTint = lerpColor(Sakura.White, Sakura.Background, .45f)
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
