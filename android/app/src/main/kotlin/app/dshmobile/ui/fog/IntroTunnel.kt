package app.dshmobile.ui.fog

// Adapted from Rearpoem/dsh-link-start (c) 2026 FHun, MIT License: the perspective ray tunnel (a ray's head at focal / z, rays
// sent round again far away once their tail has passed the camera, a speed that grows with u² from a non-zero start), the shock
// rings' radius curve (.06 + 1.05 (1 − (1 − k)^2.2)) and the status discs flying past the camera while turning. The colours,
// the light rays' gradients, the core, the panels, the words and the timing are our own; nothing of its name, script, images
// or sounds.

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import app.dshmobile.BuildConfig
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 「櫻花隧道」 (Sakura tunnel), the startup look [IntroStyle.TUNNEL], the most "anime opening" of the five: a spark in a white
 * void, then a tunnel of sakura-coloured light rays fans out of a glowing core (with the user's picture growing inside it) and
 * rushes at the camera, depth rings racing outward; status discs pop out of the core, hover to show the user's own values
 * (編號, 等級, today's date, the app's 版本), flip to OK and fly past; a 登入 panel types the name in, a 確認 panel lights 出發;
 * the tunnel turns pink and dashes, the core opens onto the picture and bursts white; the name stamps in as a sticker
 * (歡迎回來) and the sakura iris opens into the app.
 *
 * Fixed moves and stretching holds ([TunnelTimeline]): 8 s cruises longer, lets the panels be read and holds the welcome, but
 * nothing moves slower. Everything is a pure function of the run's clock, read only in draw lambdas, so a run composes once.
 * The layout comes from the space available ([TunnelLayout]): the panels never cover the core, and in landscape they stand
 * beside it. With reduced motion the host holds one still: a frozen tunnel (the core, faint rays, one OK disc) behind the
 * settled welcome.
 */
@Composable
internal fun TunnelIntro(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean,
    fx: IntroFx,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val total = intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000f
    val tl = remember(total) { TunnelTimeline.of(total) }
    val timing = remember(tl) { IntroTiming.of(tl.plan, still = tl.still, hit = tl.hit, announce = tl.hit) }
    val line = introWelcomeLine(intro)
    val words = tunnelWords(intro)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val coverage = IntroFonts.coverage(LocalContext.current.resources)
    val skippable = intro.skippable
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = line) {
        val scope = this
        val scene = remember(scope, words, tl, skippable) {
            val d = density
            TunnelScene(
                scope, tl, words, IntroMeasure(measurer, d.density, d.fontScale, 1f, coverage),
                // The name is measured as if the handwriting had every character: a character it lacks (桜, ひなた) falls back
                // to the system font but is still thickened and outlined like the rest, instead of a hairline beside ink.
                IntroMeasure(measurer, d.density, d.fontScale, 1f, IntArray(0)), skippable,
            )
        }
        val run = run
        // The iris opens from the tunnel's core, where the burst came from.
        run.sceneOrigin = scene.vp
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawWorld(this, run.ms, run.amb) })
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawDiscs(this, run.ms); scene.drawPanels(this, run.ms) })
        Spacer(Modifier.fillMaxSize().drawBehind { scene.drawBurst(this, run.ms, run.amb) })
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(0f) }.drawBehind { scene.drawHud(this, run.ms) })
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(40f) }.drawBehind { scene.drawWelcome(this, run.ms) })
    }
}

/** A run's words, resolved once (defaults filled in), so drawing never reads resources. */
internal class TunnelWords(
    /** The welcome words without trailing punctuation (the name follows them), e.g. 歡迎回來. */
    val greet: String,
    val greetIsDefault: Boolean,
    val name: String,
    /** What each status disc shows: its label and the real value (編號 No.0001, 等級 Lv.1, 日期 2026.11.15, 版本 v0.8.2). */
    val labels: List<String>,
    val values: List<String>,
    val login: String,
    val loginName: String,
    val loginCode: String,
    /** What types into the 名字 field: the name, else the user's 編號. */
    val loginValue: String,
    val confirm: String,
    val confirmLine: String,
    val go: String,
    val time: String,
    /** The host's 跳過 label, measured here only to put an opaque face under it while the rays run. */
    val skip: String,
)

/** The welcome words as the eyebrow over the name: trailing punctuation dropped (the name follows), the default when blank. */
internal fun tunnelGreet(words: String, fallback: String): String =
    words.trim().trimEnd('，', ',', '!', '！', '。', '.', '、', '~', '～', ' ', '　').ifEmpty { fallback }

@Composable
private fun tunnelWords(intro: Intro): TunnelWords {
    val default = stringResource(R.string.intro_welcome_default)
    val number = intro.number.trim().ifEmpty { stringResource(R.string.intro_number_default) }
    val level = intro.level.trim().ifEmpty { stringResource(R.string.intro_level_default) }
    val labels = listOf(
        stringResource(R.string.intro_tunnel_number), stringResource(R.string.intro_tunnel_level),
        stringResource(R.string.intro_tunnel_date), stringResource(R.string.intro_tunnel_version),
    )
    val login = stringResource(R.string.intro_tunnel_login)
    val loginName = stringResource(R.string.intro_tunnel_login_name)
    val loginCode = stringResource(R.string.intro_tunnel_login_code)
    val confirm = stringResource(R.string.intro_tunnel_confirm)
    val confirmLine = stringResource(R.string.intro_tunnel_confirm_line)
    val go = stringResource(R.string.intro_tunnel_go)
    val skip = stringResource(R.string.intro_skip)
    val now = remember { Date() }
    return remember(intro, default, number, level) {
        val name = intro.name.trim()
        TunnelWords(
            greet = tunnelGreet(intro.welcome, default), greetIsDefault = intro.welcome.isBlank(), name = name, labels = labels,
            values = listOf(number, level, SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(now), "v" + BuildConfig.VERSION_NAME),
            login = login, loginName = loginName, loginCode = loginCode, loginValue = name.ifEmpty { number },
            confirm = confirm, confirmLine = confirmLine, go = go, time = SimpleDateFormat("HH:mm", Locale.ROOT).format(now), skip = skip,
        )
    }
}

/**
 * The beats of one run of [plan]'s total (1.5–8 s). The moves keep their length (they tighten together only when a short run
 * cannot fit them, [pace] < 1); the holds stretch: the tunnel cruises, the 登入 and 確認 panels wait to be read and the welcome
 * holds. Below [COMPACT_BELOW] ms the 確認 panel is left out and three discs fly instead of four.
 *
 * The tunnel's speed is a curve of the clock ([speed]), integrated once into a table ([distance]), so a ray's depth at any
 * moment is a closed form and any frame can be drawn on its own.
 */
internal class TunnelTimeline private constructor(val plan: IntroPlan, val compact: Boolean) {
    val total: Float get() = plan.total
    val pace: Float get() = plan.pace

    /** The spark: the white void lasts only [VOID_MS] (× pace), then the core lights and the rays fan out of it. */
    val tunnelAt: Float = plan.at(B_TUNNEL)
    /** The end of the cruise: the 登入 panel wipes in. */
    val loginAt: Float = plan.at(B_LOGIN)
    /** The 確認 panel wipes in (the dash's start when [compact]). */
    val confirmAt: Float = plan.at(B_CONFIRM)
    val dashAt: Float = plan.at(B_DASH)
    /** The white burst: the haptic tick, the announcement and the hit's nudge. */
    val hit: Float = plan.at(B_BURST)
    val exit: Float = plan.at(B_EXIT)
    /** The reduced-motion still: the welcome at the end of its hold, fully settled. */
    val still: Float = exit - 1f

    val discs: Int = if (compact) 3 else 4
    val discMs: Float = DISC_MS * plan.pace
    private val discFirst = tunnelAt + 120f * plan.pace
    /** The discs share the cruise: about 105 ms apart at 3 s (two or three hover at once), up to 420 ms apart on a long run. */
    val discStep: Float = ((plan.end(B_CRUISE) - discMs - discFirst) / (discs - 1)).coerceIn(50f * plan.pace, 420f)
    fun discAt(i: Int): Float = discFirst + i * discStep

    /** The name starts typing into the 名字 field, at most 220 ms for the whole name. */
    val typeAt: Float = loginAt + 110f * plan.pace
    fun typeStep(chars: Int): Float = min(70f, 220f / chars.coerceAtLeast(1)) * plan.pace
    /** 出發 lights up. */
    val pressAt: Float = confirmAt + 150f * plan.pace
    /** The 登入 panel leaves when the 確認 panel comes (when the dash starts on a compact run). */
    val loginOutAt: Float = if (compact) dashAt else confirmAt
    /**
     * The eyebrow (歡迎回來) rises once the flare's blades are gone (hit + 110 ms × pace), so type never lies under the spikes;
     * the name sticker stamps in right after.
     */
    val greetAt: Float = hit + 130f * plan.pace
    val nameAt: Float = hit + 190f * plan.pace

    /** The dash's progress, eased in: slow, then everything at once (the speed and the opening core). */
    fun dashK(ms: Float): Float = seg(ms, dashAt, hit - dashAt, IntroEase.CubicIn)

    /** The dash's plain progress: the rays turn pink and the flood comes in from its first frames. */
    fun dashLinear(ms: Float): Float = seg(ms, dashAt, hit - dashAt)

    /**
     * The white burst over everything: up in the dash's last 50 ms, a true white peak held [FLASH_HOLD] ms (two frames), then
     * gone [FLASH_FALL] ms later, fast, so the picture comes through crisp instead of out of a long haze (all × pace).
     */
    fun flash(ms: Float): Float {
        val d = ms - hit
        return when {
            d < -50f * pace -> 0f
            d < 0f -> FLASH_PEAK * (1f + d / (50f * pace))
            d < FLASH_HOLD * pace -> FLASH_PEAK
            else -> FLASH_PEAK * (1f - IntroEase.CubicOut.transform(((d - FLASH_HOLD * pace) / (FLASH_FALL * pace)).coerceIn(0f, 1f)))
        }
    }

    /**
     * The dash's flood (a pink rim round a white centre): from 35 % of the dash, by when the aqua rays have turned white (so no
     * pink lies over aqua), full at the burst, gone 140 ms after it.
     */
    fun flood(ms: Float): Float = if (ms < hit) seg(dashLinear(ms), .35f, .65f) else 1f - seg(ms, hit, 140f * pace)

    /** The user's picture growing in the core during the cruise (it is what the core opens onto at the burst). */
    fun portraitK(ms: Float): Float = seg(ms, tunnelAt + 250f * pace, 450f * pace, IntroEase.CubicOut)

    /**
     * The tunnel's core, a white light: it lights with the spark, swells to [TunnelLayout.CORE_DP] by the dash, then opens to
     * [cover] (the whole screen) at the burst.
     */
    fun coreRadius(ms: Float, dp: Float, cover: Float): Float {
        if (ms >= hit) return cover
        val appear = seg(ms, tunnelAt - 20f * pace, 260f * pace, IntroEase.BackOut) * (TunnelLayout.CORE_DP - CORE_SWELL) * dp
        val swell = CORE_SWELL * dp * seg(ms, tunnelAt, dashAt - tunnelAt)
        return lerp(appear + swell, cover, dashK(ms))
    }

    /**
     * The welcome's light rays: full at the burst, settling to [settle] behind the name: faint over the user's picture, stronger
     * over the plain sky.
     */
    fun afterglow(ms: Float, settle: Float): Float =
        if (ms < hit) 0f else seg(ms, hit, 160f * pace) * lerp(1f, settle, seg(ms, hit + 80f * pace, 560f * pace, IntroEase.SineInOut))

    /** A panel's words fade in the first 60 ms of its exit at [at]: no glyph is ever drawn squashed. */
    fun panelText(ms: Float, at: Float): Float = 1f - seg(ms, at, 60f * pace)

    /** Then the card is pulled into the core (scale about the vanishing point, fading), as if the camera flew past it. */
    fun panelPull(ms: Float, at: Float): Float = seg(ms, at + 40f * pace, 140f * pace, IntroEase.CubicIn)

    /** The tunnel's speed (depth units per second of the clock): a fast start, up to cruise, calmer behind the panels, the dash. */
    fun speed(ms: Float): Float {
        if (ms < tunnelAt) return 0f
        val v = when {
            ms >= dashAt -> lerp(SLOW, DASH, dashK(ms))
            ms >= loginAt -> lerp(ramp(loginAt), SLOW, seg(ms, loginAt, 260f * pace, IntroEase.SineInOut))
            else -> ramp(ms)
        }
        return v / pace
    }

    private fun ramp(ms: Float): Float {
        val u = (ms - tunnelAt) / pace / 1000f
        return min(CRUISE, START_SPEED + 13f * u * u)
    }

    // The distance flown, integrated once at 8 ms steps (moves are pure functions of it).
    private val table = FloatArray((plan.total / STEP).toInt() + 2).also { t ->
        for (i in 1 until t.size) t[i] = t[i - 1] + speed((i - .5f) * STEP) * STEP / 1000f
    }

    /** How far the camera has flown down the tunnel by [ms] (depth units). */
    fun distance(ms: Float): Float {
        val x = (ms / STEP).coerceIn(0f, (table.size - 1).toFloat())
        val i = x.toInt().coerceAtMost(table.size - 2)
        return lerp(table[i], table[i + 1], x - i)
    }

    companion object {
        const val COMPACT_BELOW = 2200f
        const val VOID_MS = 100f
        const val TUNNEL_MS = 960f
        const val LOGIN_MS = 400f
        const val CONFIRM_MS = 240f
        const val DASH_MS = 180f
        const val BURST_MS = 400f
        const val WELCOME_MIN = 340f
        const val EXIT_MS = 380f
        const val DISC_MS = 520f
        const val START_SPEED = 4f
        const val CRUISE = 14f
        const val SLOW = 5f
        const val DASH = 46f
        const val FLASH_PEAK = .985f
        const val FLASH_HOLD = 34f
        const val FLASH_FALL = 240f
        const val CORE_SWELL = 12f
        private const val STEP = 8f

        const val B_VOID = 0
        const val B_TUNNEL = 1
        const val B_CRUISE = 2
        const val B_LOGIN = 3
        const val B_READ = 4
        const val B_CONFIRM = 5
        const val B_CONFIRM_READ = 6
        const val B_DASH = 7
        const val B_BURST = 8
        const val B_WELCOME = 9
        const val B_EXIT = 10

        fun of(totalMs: Float): TunnelTimeline {
            val compact = totalMs < COMPACT_BELOW
            val plan = IntroPlan.of(
                totalMs,
                IntroBeat.move(VOID_MS),
                IntroBeat.move(if (compact) TUNNEL_MS - 80f else TUNNEL_MS),
                IntroBeat.hold(1f),
                IntroBeat.move(LOGIN_MS),
                IntroBeat.hold(.5f),
                IntroBeat.move(if (compact) 0f else CONFIRM_MS),
                IntroBeat.hold(if (compact) 0f else .4f),
                IntroBeat.move(DASH_MS),
                IntroBeat.move(BURST_MS),
                IntroBeat.hold(1.6f, WELCOME_MIN),
                IntroBeat.move(EXIT_MS),
            )
            return TunnelTimeline(plan, compact)
        }
    }
}

/** A status disc's depth at [k] (0..1 of its flight): out of the core, a hover at z ≈ 2 (scale ≈ 1) while it flips, then past. */
internal fun tunnelDiscDepth(k: Float): Float = when {
    k < DISC_HOVER_FROM -> lerp(7f, 2f, IntroEase.CubicOut.transform(k / DISC_HOVER_FROM))
    k < DISC_HOVER_TO -> lerp(2f, 1.86f, (k - DISC_HOVER_FROM) / (DISC_HOVER_TO - DISC_HOVER_FROM))
    else -> lerp(1.86f, .3f, IntroEase.CubicIn.transform(((k - DISC_HOVER_TO) / (1f - DISC_HOVER_TO)).coerceIn(0f, 1f)))
}

internal const val DISC_HOVER_FROM = .38f
internal const val DISC_HOVER_TO = .72f
/** A disc flips CHECK → OK at the middle of its hover (it squashes over .50–.60 and reopens). */
internal const val DISC_FLIP = .55f

/** How many things a run draws, by effect level ([introCount] for the user's 數量; the rays are the look itself, fixed). */
internal class TunnelCounts(val rays: Int, val petals: Int, val burst: Int, val drift: Int, val season: Int, val lines: Int) {
    companion object {
        fun of(level: EffectLevel, fx: IntroFx, reduced: Boolean): TunnelCounts = TunnelCounts(
            rays = if (reduced) 0 else when (level) { EffectLevel.FULL -> 110; EffectLevel.BALANCED -> 76; EffectLevel.SAVER -> 40 },
            petals = if (reduced) 0 else introCount(level, fx, 26, 16, 8),
            burst = if (reduced) 0 else introCount(level, fx, 24, 16, 8),
            drift = introCount(level, fx, 12, 8, 5, reduced),
            season = if (fx.season == IntroSeason.NONE) 0 else introCount(level, fx, 10, 8, 4, reduced),
            lines = if (reduced) 0 else when (level) { EffectLevel.FULL -> 36; EffectLevel.BALANCED -> 24; EffectLevel.SAVER -> 14 },
        )
    }
}

/**
 * Ray depth after the camera has flown [flown] since the ray switched on (pure: nothing is carried between frames). The ray
 * starts at depth [start], moves [v] times the camera and goes round from [far] to the camera (until its tail, [len] behind the
 * head, passes .12) and back to [far]: [tunnelRayDepth] is its depth now, [tunnelRayCycle] how many times it has gone round
 * (each round gets a new angle, width and colour).
 */
internal fun tunnelRayDepth(flown: Float, v: Float, len: Float, far: Float, start: Float): Float {
    val c = far - (.12f - len)
    val p = (far - start) + v * flown.coerceAtLeast(0f)
    return far - (p - floor(p / c) * c)
}

internal fun tunnelRayCycle(flown: Float, v: Float, len: Float, far: Float, start: Float): Int {
    val c = far - (.12f - len)
    return floor(((far - start) + v * flown.coerceAtLeast(0f)) / c).toInt()
}

/** A light colour every edge meets instead of pure white: ink or rose-brown anti-aliased onto pure white left grey pixels. */
internal val TunnelMist: Color = lerpColor(Sakura.White, Sakura.Background, .3f)

/** The ray colour groups, by index: aqua, blush, white, milk tea, pink, and rose-brown (thin accents only). */
private val GROUP_COLOR = arrayOf(Sakura.Aqua, Sakura.Background, Sakura.White, Sakura.MilkTea, Sakura.Primary, Sakura.RoseBrown)
/** The rays' colours, weighted: pink ×4, blush ×3, white ×2, aqua ×2, milk tea ×1 (sakura light, not café). */
private val RAY_GROUP = intArrayOf(4, 4, 4, 4, 1, 1, 1, 2, 2, 0, 0, 3)
/** The still's frozen rays (no rose-brown, aqua kept apart from it). */
private val STILL_GROUP = intArrayOf(4, 2, 0, 4, 1, 2)
/** The void's spark: three little arcs (aqua only ever over the white room). */
private val SPARK = arrayOf(Sakura.Primary, Sakura.MilkTea, Sakura.Aqua)
private const val DEG = 180f / PI.toFloat()

private fun clampF(v: Float, lo: Float, hi: Float): Float = if (lo > hi) (lo + hi) / 2f else v.coerceIn(lo, hi)
private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/**
 * Where everything stands, from the space available (pure, so a test can check every screen). The core (the vanishing point
 * [vp], [coreR] at its largest before the dash) is never covered: the panels' top sits [GAP_DP] under it, and when they do not
 * fit below it the core moves up (to .34 H, or higher if it must); in landscape the panels stand beside it instead. The discs
 * peak fully on screen, [EDGE_DP] inside the edges, below the top chrome band and clear of the core.
 */
internal class TunnelLayout private constructor(
    val vp: Offset,
    val coreR: Float,
    val wide: Boolean,
    /** The panels' width and centre; [cardTop] is the cards' top edge (their tab sticks out [TAB_DP] above it). */
    val pw: Float,
    val panelCx: Float,
    val cardTop: Float,
    private val cardH: Float,
    /** Where each status disc's centre is when it hovers, at its largest readable size ([discR] × [DISC_PEAK] × [discScale]). */
    val discs: Array<Offset>,
    val discR: Float,
    /** 1, or less only on a screen too short for a 155 dp disc between the chrome bands (a small landscape with system bars). */
    val discScale: Float,
    private val dp: Float,
) {
    val coreBox: Rect get() = Rect(vp, coreR)
    /** The panels' whole box: the tab above the card, the card, and its hard shadow. */
    val panelBox: Rect get() = Rect(panelCx - pw / 2f, cardTop - TAB_DP * dp, panelCx + pw / 2f + SHADOW_DP * dp, cardTop + cardH + SHADOW_DP * dp)
    fun discBox(i: Int): Rect = Rect(discs[i], discR * DISC_PEAK * discScale)

    companion object {
        /** The core's radius at its largest before the dash (dp). */
        const val CORE_DP = 44f
        const val GAP_DP = 20f
        const val TAB_DP = 15f
        const val SHADOW_DP = 7f
        /** A disc's radius at scale 1: 144 dp across while it hovers. */
        const val DISC_DP = 72f
        /** The hover's largest scale (z 1.86). */
        const val DISC_PEAK = 2f / 1.86f
        /** The top chrome band (DSH MOBILE, the clock) and the bottom one (跳過 SKIP), below and above the insets. */
        const val TOP_DP = 64f
        const val BOTTOM_DP = 72f
        const val EDGE_DP = 16f
        // Each disc's hover, from the core (dp): the hero 編號 the most central, just above-right of the core; the others in
        // the corners. Clamped into the safe box on every screen.
        private val PORTRAIT_DISCS = arrayOf(Offset(42f, -178f), Offset(-112f, 200f), Offset(-110f, -240f), Offset(112f, 228f))
        private val WIDE_DISCS = arrayOf(Offset(150f, -24f), Offset(-205f, 24f), Offset(310f, 30f), Offset(470f, -24f))

        fun of(w: Float, h: Float, dp: Float, insetTop: Float, insetBottom: Float, cardH: Float): TunnelLayout {
            val wide = w > h
            val coreR = CORE_DP * dp
            val gap = GAP_DP * dp
            val topBand = insetTop + TOP_DP * dp
            val bottomLimit = h - insetBottom - BOTTOM_DP * dp
            val box = (TAB_DP + SHADOW_DP) * dp + cardH
            val vp: Offset
            val pw: Float
            val panelCx: Float
            val cardTop: Float
            if (!wide) {
                pw = min(w - 56f * dp, 330f * dp)
                panelCx = w / 2f
                fun fits(y: Float) = y + coreR + gap + box <= bottomLimit
                var vy = .45f * h
                if (!fits(vy)) vy = max(.34f * h, topBand + coreR + 24f * dp)
                if (!fits(vy)) vy = max(topBand + coreR, bottomLimit - box - gap - coreR)
                vp = Offset(w / 2f, vy)
                cardTop = vy + coreR + gap + TAB_DP * dp
            } else {
                vp = Offset(.35f * w, clampF(.47f * h, insetTop + coreR + 12f * dp, h - insetBottom - coreR - 12f * dp))
                val left0 = vp.x + coreR + gap
                pw = max(160f * dp, min(330f * dp, w - EDGE_DP * dp - SHADOW_DP * dp - left0))
                panelCx = clampF(.68f * w, left0 + pw / 2f, w - EDGE_DP * dp - SHADOW_DP * dp - pw / 2f)
                val boxTop = clampF(h / 2f - box / 2f, topBand, bottomLimit - box)
                cardTop = boxTop + TAB_DP * dp
            }
            val r = DISC_DP * dp
            val edge = EDGE_DP * dp
            val discScale = min(1f, min(bottomLimit - topBand - 2f * edge, w - 2f * edge) / (2f * r * DISC_PEAK)).coerceAtLeast(.5f)
            val rPk = r * DISC_PEAK * discScale
            val x0 = edge + rPk
            val x1 = w - edge - rPk
            val y0 = topBand + edge + rPk
            val y1 = bottomLimit - edge - rPk
            val minD = coreR + rPk + 12f * dp
            val discs = (if (wide) WIDE_DISCS else PORTRAIT_DISCS).map { o ->
                var p = Offset(clampF(vp.x + o.x * dp, x0, x1), clampF(vp.y + o.y * dp, y0, y1))
                val d = p - vp
                val len = d.getDistance()
                if (len in 1f..minD) {
                    p = vp + d / len * minD
                    p = Offset(clampF(p.x, x0, x1), clampF(p.y, y0, y1))
                }
                p
            }.toTypedArray()
            // On a cramped screen the clamping can bring another spot nearer the core: the hero takes the most central one.
            val nearest = discs.indices.minByOrNull { (discs[it] - vp).getDistance() } ?: 0
            if (nearest != 0) discs[0] = discs[nearest].also { discs[nearest] = discs[0] }
            return TunnelLayout(vp, coreR, wide, pw, panelCx, cardTop, cardH, discs, r, discScale, dp)
        }
    }
}

/**
 * The tunnel's rays (pure: a ray's whole state is a closed form of the clock, so any frame draws on its own). Each ray goes
 * round from far away to past the camera; a new ray shoots out of the core in [FAN_MS]. The first [FIRST] start close (long
 * already), so the tunnel grabs the eye within 300 ms; the rest start spread over their whole round (a full tunnel at once).
 * [pose] fills the public fields for one ray (one object reused: a frame allocates nothing).
 */
internal class TunnelRays(private val tl: TunnelTimeline, val count: Int, private val focal: Float, private val reach: Float, private val core: Float, private val dp: Float) {
    private val v = FloatArray(count) { .75f + .5f * ih(it, 301) }
    private val len = FloatArray(count) { 1.2f + 3.2f * ih(it, 302) }
    private val far = FloatArray(count) { 6f + 3f * ih(it, 303) }
    private val first = min(count, FIRST)
    private val on = FloatArray(count) {
        if (it < first) tl.tunnelAt + it * 2f * tl.pace
        else tl.tunnelAt + (40f + (it - first) * SPREAD_MS / max(1, count - first)) * tl.pace
    }
    private val flown0 = FloatArray(count) { tl.distance(on[it]) }
    /**
     * The first rays start just far enough down the tunnel to be long (head nearer than .45) at [LONG_BY] ms, at any run length
     * (a short run flies faster): they rush out of the core and fill the screen by then.
     */
    private val start = FloatArray(count) {
        if (it < first) .45f - .35f * len[it] * ih(it, 309) + v[it] * (tl.distance(LONG_BY) - flown0[it]).coerceAtLeast(0f)
        else far[it] - (far[it] - (.12f - len[it])) * .97f * ih(it, 304)
    }

    var angle = 0f
    var tail = 0f
    var head = 0f
    /** Half the ray's width at its head, and at its tail (px). */
    var half = 0f
    var tailHalf = 0f
    /** Its colour group ([GROUP_COLOR]). */
    var group = 0
    /** Far rays are lost in the core's light: 0 inside it, 1 once the head is well clear of it. */
    var alpha = 0f

    fun pose(i: Int, ms: Float, flown: Float, dk: Float): Boolean {
        if (ms < on[i]) return false
        val f = flown - flown0[i]
        val z = tunnelRayDepth(f, v[i], len[i], far[i], start[i])
        val salt = i + 977 * tunnelRayCycle(f, v[i], len[i], far[i], start[i])
        // The head is drawn no further out than just past the corners, so its full colour (60 % along) lands on screen.
        var hd = min(focal / max(z, .12f), reach * 1.15f)
        var tl0 = focal / (z + len[i] * (1f + 2.5f * dk))
        if (tl0 > reach * 1.1f) return false
        val grow = seg(ms, on[i], FAN_MS * tl.pace, IntroEase.CubicOut)
        if (grow < 1f) {
            hd = lerp(core * .7f, hd, grow)
            tl0 = lerp(core * .6f, tl0, grow)
        }
        if (hd - tl0 < 2f * dp) return false
        val u = ih(salt, 305)
        // Aqua and rose-brown never cross (their blend is grey): rose-brown only in the middle of odd twelfths of the circle
        // (.3–.7), aqua never within .15 of a twelfth of that band (more than the widest aqua ray). All rays turn together, so
        // they stay apart.
        val sector = u * 12f
        val sk = sector.toInt()
        val frac = sector - sk
        var g = RAY_GROUP[(ih(salt, 307) * 12f).toInt().coerceIn(0, 11)]
        val accent = ih(salt, 308) < ACCENT
        if (accent) g = if (sk % 2 == 1 && frac in .3f..0.7f) 5 else 4
        else if (g == 0 && sk % 2 == 1 && frac in .15f..0.85f) g = 2
        val wa = if (accent) .005f + .005f * ih(salt, 306) else .02f + .045f * ih(salt, 306)
        group = g
        angle = 2f * PI.toFloat() * u + .03f * flown
        head = hd
        tail = tl0
        // Narrower towards the edges: a beam of light, not a cut-paper wedge.
        half = max(.8f * dp, hd * sin(wa) * (1f - .45f * smooth(hd / reach)))
        tailHalf = min(half, max(.4f * dp, tl0 * sin(wa * .15f)))
        alpha = ((hd - core * 1.05f) / (.16f * reach)).coerceIn(0f, 1f)
        return alpha > .01f
    }

    companion object {
        const val FIRST = 16
        const val LONG_BY = 300f
        const val FAN_MS = 150f
        const val SPREAD_MS = 300f
        const val ACCENT = .07f
    }
}

/** A grapheme's script for a line break: 1 Han, 2 kana, 3 Latin and digits, 4 Hangul, 5 other; 0 joins the one before (emoji, marks). */
private fun tunnelScript(g: String): Int {
    if (g.isEmpty() || isEmoji(g)) return 0
    val cp = g.codePointAt(0)
    return when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN -> 1
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> 2
        Character.UnicodeScript.LATIN -> 3
        Character.UnicodeScript.HANGUL -> 4
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> if (Character.isLetterOrDigit(cp)) 3 else 0
        else -> 5
    }
}

/**
 * Where a name too long for one line ([g] laid out at [x] with [adv] advances) should break: at a change of script (桜庭 | ひなた,
 * 小雨 | Amy) whose longer side is the shortest, when both sides fit [maxW]; -1 when there is none (the sticker then balances
 * the lines by width). An emoji stays with the text before it.
 */
internal fun tunnelNameBreak(g: List<String>, x: FloatArray, adv: FloatArray, maxW: Float): Int {
    val n = g.size
    if (n < 2) return -1
    val script = IntArray(n)
    for (i in 0 until n) {
        val s = tunnelScript(g[i])
        script[i] = if (s == 0 && i > 0) script[i - 1] else s
    }
    val end = x[n - 1] + adv[n - 1]
    var best = -1
    var bestW = Float.MAX_VALUE
    for (k in 1 until n) {
        if (script[k] == script[k - 1] || script[k] == 0) continue
        val m = max(x[k - 1] + adv[k - 1], end - x[k])
        if (m <= maxW && m < bestW) { best = k; bestW = m }
    }
    return best
}

/** True when a picture cropped to fill a [w] × [h] screen shows less than 60 % of its height (a tall picture on a wide screen). */
internal fun tunnelCardMode(iw: Float, ih: Float, w: Float, h: Float): Boolean = w > h && h / max(w / iw, h / ih) < .6f * ih

/**
 * Everything one run draws, built once per size: the layout, paths, brushes and every text measured. The draw functions read
 * only this and the clock, so a frame lays out no text and allocates almost nothing (the ray paths are reset and refilled).
 */
internal class TunnelScene(
    sc: IntroScope, val tl: TunnelTimeline, private val words: TunnelWords, private val mm: IntroMeasure, private val mmName: IntroMeasure,
    private val skippable: Boolean,
) {
    private val w = sc.widthPx
    private val h = sc.heightPx
    private val dp = sc.density.density
    private val reduced = sc.reduced
    private val insetTop = sc.insetTop
    private val insetBottom = sc.insetBottom
    private val pace = tl.pace
    private val picture = sc.picture
    private val petals = sc.fx.petals
    private val counts = TunnelCounts.of(sc.level, sc.fx, reduced)

    // ---- the panels' contents first: the layout needs their height ----

    private val pad = 20f * dp
    private val buttonH = 44f * dp

    /** One width's panel contents: the 登入 rows and the 確認 line and button. */
    private inner class Cards(val pw: Float) {
        val labelName = mm.run(words.loginName, IntroFonts.hand(18f), .04f)
        val labelCode = mm.run(words.loginCode, IntroFonts.hand(18f), .04f)
        val valueX = 18f * dp + max(labelName.width, labelCode.width) + 14f * dp
        val nameRun = if (words.name.isEmpty()) {
            mm.fit(words.loginValue, IntroFonts.latin(18f, FontWeight.Bold), .06f, 18f, 12f, pw - valueX - 18f * dp)
        } else {
            mmName.fit(words.loginValue, IntroFonts.hand(28f), .04f, 28f, 18f, pw - valueX - 18f * dp)
        }
        val row1Base = pad + 8f * dp + max(nameRun.baseline, labelName.baseline)
        val row2Base = row1Base + 16f * dp + labelCode.height
        val loginH = row2Base + pad
        val confirmRun = mm.fit(words.confirmLine, IntroFonts.hand(20f), .06f, 20f, 18f, pw - 2f * pad)
        val goRun = mm.run(words.go, IntroFonts.hand(22f), .1f)
        val buttonW = goRun.width + 64f * dp
        val confirmTop = pad + 8f * dp
        val buttonTop = confirmTop + confirmRun.height + 12f * dp
        val confirmH = buttonTop + buttonH + pad
    }

    private val guess = Cards(min(w - 56f * dp, 330f * dp))
    val lay = TunnelLayout.of(w, h, dp, insetTop, insetBottom, max(guess.loginH, guess.confirmH))
    private val cards = if (lay.pw == guess.pw) guess else Cards(lay.pw)

    /** The vanishing point: the core of the tunnel. */
    val vp = lay.vp
    private val coreMax = lay.coreR
    private val reach = hypot(w, h) / 2f
    private val focal = reach * .2f
    /** A circle round [vp] this large covers the whole screen. */
    private val cover = maxOf(hypot(vp.x, vp.y), hypot(w - vp.x, vp.y), hypot(vp.x, h - vp.y), hypot(w - vp.x, h - vp.y)) + 2f * dp

    private val rays = TunnelRays(tl, counts.rays, focal, reach, coreMax, dp)
    private val drift = IntroDrift(w, h, dp, counts.drift, sc.fx, salt = 40)
    private val season = IntroSeasonFx(w, h, dp, sc.fx.season, counts.season, sc.fx)
    private val petal = Kawaii.petalPath()
    private val sparkle = Kawaii.sparklePath()
    private val crest = sakuraSilhouette()

    // ---- the picture: in the core, then the stage ----

    private val iw = picture?.width?.toFloat()?.coerceAtLeast(1f) ?: 1f
    private val ih = picture?.height?.toFloat()?.coerceAtLeast(1f) ?: 1f
    /** Where the home screen's crop holds the picture (the 對齊 setting): at the exit the stage matches it exactly. */
    private val fy = (1f + sc.align.bias) / 2f
    /** Where a face usually is in a full-body picture (the core shows that, not the middle of the body). */
    private val faceFy = when (sc.align) { PictureAlign.TOP -> .22f; PictureAlign.CENTER -> .30f; PictureAlign.BOTTOM -> .62f }
    /**
     * A tall picture on a wide screen, cropped to fill it, shows only a band across the chest (the face cut off, the name
     * stamped on the body): there it stands whole as a card where the core opened, over a blurred fill of itself.
     */
    private val card = picture != null && tunnelCardMode(iw, ih, w, h)
    private val cardRect: Rect = if (!card) Rect.Zero else {
        var ch = .80f * (h - insetTop - insetBottom)
        var cw = ch * iw / ih
        if (cw > .44f * w) { cw = .44f * w; ch = cw * ih / iw }
        val cx = clampF(vp.x, 12f * dp + cw / 2f, w - 12f * dp - cw / 2f)
        val cy = clampF(vp.y, insetTop + 12f * dp + ch / 2f, h - insetBottom - 12f * dp - ch / 2f)
        Rect(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
    }
    private val cardPlace = Placement(cardRect.left, cardRect.top, cardRect.width / iw)
    private val cardClip = Path().apply { if (card) addRoundRect(RoundRect(cardRect, CornerRadius(18f * dp))) }
    /** The blurred fill: the picture shrunk to a few dozen pixels once and drawn back up smooth (no live blur per frame). */
    private val blurred: ImageBitmap? = if (!card) null else runCatching {
        val src = picture!!.asAndroidBitmap()
        var b: Bitmap = src
        while (min(b.width, b.height) > 24) {
            val next = Bitmap.createScaledBitmap(b, max(1, b.width / 2), max(1, b.height / 2), true)
            if (b !== src) b.recycle()
            b = next
        }
        if (b === src) null else b.asImageBitmap()
    }.getOrNull()
    private val blurSrc: Pair<IntOffset, IntSize> = blurred?.let { b ->
        val k = max(w / b.width, h / b.height)
        val sw = (w / k).roundToInt().coerceIn(1, b.width)
        val sh = (h / k).roundToInt().coerceIn(1, b.height)
        IntOffset((b.width - sw) / 2, ((b.height - sh) * faceFy).roundToInt().coerceIn(0, b.height - sh)) to IntSize(sw, sh)
    } ?: (IntOffset.Zero to IntSize(1, 1))
    /** The portrait in the core: the face, half the picture's short side across the core's [portraitR]. */
    private val portraitR = .92f * coreMax
    private val facePlace = Placement().also { p ->
        val side = 2f * portraitR
        cover(iw, ih, side, side, 2f, .5f, faceFy, side / 2f, side / 2f, p)
        p.ox += vp.x - portraitR
        p.oy += vp.y - portraitR
    }
    private val place = Placement()
    private val toPlace = Placement()
    private val sky = introSky(h)

    // ---- the room, the core and the after-glow ----

    private val room = Brush.radialGradient(
        0f to Sakura.White, .4f to lerpColor(Sakura.White, Sakura.Background, .12f), 1f to lerpColor(Sakura.White, Sakura.Background, .75f),
        center = vp, radius = reach * 1.05f,
    )
    /** The core: a white light whose bloom runs white to blush and out by 1.6 times the core's radius (radius 1, scaled). */
    private val coreBloom = Brush.radialGradient(
        0f to Sakura.White, .62f to Sakura.White, .80f to Sakura.Background.copy(alpha = .85f), 1f to Sakura.Background.copy(alpha = 0f),
        center = Offset.Zero, radius = 1f,
    )
    /** The portrait's soft edge, melting into the core's white (radius 1, scaled). */
    private val feather = Brush.radialGradient(
        0f to Sakura.White.copy(alpha = 0f), .72f to Sakura.White.copy(alpha = 0f), 1f to Sakura.White, center = Offset.Zero, radius = 1f,
    )
    /** A soft mist disc of radius 1 at the origin (the discs' halo). */
    private val mistBloom = Brush.radialGradient(
        0f to TunnelMist, .45f to TunnelMist.copy(alpha = .6f), 1f to TunnelMist.copy(alpha = 0f), center = Offset.Zero, radius = 1f,
    )
    private val corePath = Path()
    private val glow: Path = Path().apply {
        val r0 = 4f * dp
        val r1 = reach * 1.4f
        for (j in 0 until 14) {
            val a = 2f * PI.toFloat() * j / 14f + .2f * (ih(j, 501) - .5f)
            val ww = .025f + .04f * ih(j, 502)
            moveTo(cos(a - ww * .1f) * r0, sin(a - ww * .1f) * r0)
            lineTo(cos(a - ww) * r1, sin(a - ww) * r1)
            lineTo(cos(a + ww) * r1, sin(a + ww) * r1)
            lineTo(cos(a + ww * .1f) * r0, sin(a + ww * .1f) * r0)
            close()
        }
    }
    private val glowBrush = Brush.radialGradient(
        0f to TunnelMist.copy(alpha = 0f), .10f to TunnelMist.copy(alpha = 0f), .30f to TunnelMist.copy(alpha = .75f),
        1f to TunnelMist.copy(alpha = 0f), center = Offset.Zero, radius = reach * 1.3f,
    )
    private val glowPink = Brush.radialGradient(
        0f to Sakura.Primary.copy(alpha = 0f), .12f to Sakura.Primary.copy(alpha = 0f), .35f to Sakura.Primary.copy(alpha = .40f),
        1f to Sakura.Primary.copy(alpha = 0f), center = Offset.Zero, radius = reach * 1.3f,
    )
    /** The dash's flood: a white centre running out to a strong pink rim (pink already at the screen's sides). */
    private val floodBrush = Brush.radialGradient(
        0f to Sakura.White.copy(alpha = .9f), .18f to Sakura.White.copy(alpha = .55f), .40f to Sakura.Primary.copy(alpha = .5f),
        .62f to Sakura.Primary.copy(alpha = .85f), 1f to Sakura.Primary, center = vp, radius = cover,
    )

    // ---- the rays: one gradient per colour along a unit ray (x 0..1, drawn scaled to each ray's length) ----

    /** Clear at the tail (the core), full colour 60 % along, soft at the head: light, not paper. */
    private val rayBrush = Array(6) { g ->
        val c = GROUP_COLOR[g]
        Brush.horizontalGradient(
            0f to c.copy(alpha = 0f), .22f to c.copy(alpha = .3f), .6f to c, .86f to c.copy(alpha = .92f), 1f to c.copy(alpha = .3f),
            startX = 0f, endX = 1f,
        )
    }
    /** The faint white line down a ray's middle, added on (it only ever lightens). */
    private val lineBrush = Brush.horizontalGradient(
        0f to Sakura.White.copy(alpha = 0f), .35f to Sakura.White.copy(alpha = 0f), .78f to Sakura.White.copy(alpha = .6f),
        1f to Sakura.White.copy(alpha = 0f), startX = 0f, endX = 1f,
    )
    private val rayPath = Path()
    private val linePath = Path()

    // ---- the discs ----

    private val discR = lay.discR
    private val discLabels = Array(tl.discs) { mm.run(words.labels[it], IntroFonts.hand(20f), .08f) }
    private val discValues = Array(tl.discs) { mm.fit(words.values[it], IntroFonts.latin(20f, FontWeight.Bold), .04f, 20f, 12f, 1.5f * discR) }
    private val chipCheck = mm.run("CHECK", IntroFonts.latin(10.5f, FontWeight.Bold), .14f)
    private val chipOk = mm.run("OK", IntroFonts.latin(12f, FontWeight.Bold), .12f)
    private val ringA = Array(tl.discs) { i ->
        Stroke(3.5f * dp, pathEffect = PathEffect.dashPathEffect(FloatArray(6) { k -> (if (k % 2 == 0) 5f + 26f * ih(i * 6 + k, 511) else 6f + 7f * ih(i * 6 + k, 512)) * dp }, 0f))
    }
    private val ringB = Array(tl.discs) { i ->
        Stroke(1.8f * dp, pathEffect = PathEffect.dashPathEffect(FloatArray(4) { k -> (if (k % 2 == 0) 2f + 12f * ih(i * 4 + k, 513) else 4f + 5f * ih(i * 4 + k, 514)) * dp }, 0f))
    }
    private val okTick = Path()
    /**
     * The still's one disc: the hero's hover spot; over a picture the upper-left one (background, not where a face usually
     * is); in landscape beside the core (or the card), clear of the welcome.
     */
    private val stillDisc: Offset = if (!lay.wide) lay.discs[if (picture != null) 2 else 0] else {
        val s = STILL_DISC * discR
        val side = if (card) cardRect.width / 2f else 1.6f * coreMax
        Offset(max(16f * dp + s, vp.x - side - 24f * dp - s), if (card) h - insetBottom - 24f * dp - s else insetTop + 80f * dp + s)
    }

    // ---- the panels ----

    private val tabLogin = mm.run(words.login, IntroFonts.hand(18f), .06f)
    private val tabLoginLatin = mm.run("SIGN IN", IntroFonts.latin(10f, FontWeight.Bold), .22f)
    private val tabConfirm = mm.run(words.confirm, IntroFonts.hand(18f), .06f)
    private val tabConfirmLatin = mm.run("CONFIRM", IntroFonts.latin(10f, FontWeight.Bold), .22f)

    // ---- the HUD and the welcome ----

    private val brand = mm.run(IntroLatin.BRAND, IntroFonts.latin(11f, FontWeight.Bold), .28f)
    private val clock = mm.run(words.time, IntroFonts.latin(11f, FontWeight.Bold), .12f)
    /** The host's 跳過 SKIP pill, measured as the host does: an opaque face goes under its half-transparent white. */
    private val skipRect: Rect = run {
        val cjk = mm.run(words.skip, IntroFonts.hand(12f, small = true), .04f)
        val latin = mm.run(IntroLatin.SKIP, IntroFonts.latin(11f, FontWeight.SemiBold), .2f)
        val right = w - 20f * dp
        val top = h - insetBottom - 42f * dp
        val x = right - (2 * 5f * dp + 2f * dp) - 6f * dp - latin.width - 6f * dp - cjk.width
        Rect(x - 10f * dp, top - 4f * dp, right + 10f * dp, top - 4f * dp + cjk.height + 8f * dp)
    }
    /** The welcome's column: the middle in portrait; in landscape right of the core (or of the card). */
    private val welcomeX: Float
    private val welcomeW: Float

    init {
        if (!lay.wide) {
            welcomeX = w / 2f
            welcomeW = w - 48f * dp
        } else if (card) {
            welcomeX = (cardRect.right + w) / 2f
            welcomeW = w - cardRect.right - 48f * dp
        } else {
            welcomeX = lay.panelCx
            welcomeW = 2f * min(w - welcomeX, welcomeX - (vp.x + 1.6f * coreMax)) - 32f * dp
        }
    }

    private val greet: GlyphRun? = if (words.name.isEmpty()) null else mm.fit(words.greet, IntroFonts.hand(30f), .08f, 30f, 18f, welcomeW - 16f * dp)
    private val sticker = IntroSticker(mmName, stickerText(), welcomeW, if (lay.wide) 72f else 80f, STICKER_FLOOR, dp)
    private val latin: GlyphRun? = if (words.greetIsDefault) mm.run(IntroLatin.WELCOME_BACK, IntroFonts.latin(13f, FontWeight.SemiBold), .16f) else null
    private val blockH = (greet?.let { it.height + 6f * dp } ?: 0f) + sticker.height + (latin?.let { 10f * dp + it.height } ?: 0f)
    private val blockTop = if (!lay.wide) min(h * .58f, h - insetBottom - 64f * dp - blockH)
        else clampF((h - blockH) / 2f + 6f * dp, insetTop + 48f * dp, h - insetBottom - 40f * dp - blockH)
    private val greetTop = blockTop
    private val stickerTop = blockTop + (greet?.let { it.height + 6f * dp } ?: 0f)
    private val latinTop = stickerTop + sticker.height + 10f * dp
    /**
     * The blush veil behind the words. In portrait: clear above them, .80 from the greeting's top to just under the welcome, a
     * little lighter further down. In landscape: clear over the core's side, .80 over the welcome's column.
     */
    private val scrim: Brush = if (!lay.wide) {
        val from = blockTop - 150f * dp
        val to = blockTop + blockH + 200f * dp
        val span = to - from
        Brush.verticalGradient(
            0f to Sakura.Background.copy(alpha = 0f), 150f * dp / span to Sakura.Background.copy(alpha = .80f),
            (150f * dp + blockH + 30f * dp) / span to Sakura.Background.copy(alpha = .80f), 1f to Sakura.Background.copy(alpha = .55f),
            startY = from, endY = to,
        )
    } else {
        val colLeft = welcomeX - welcomeW / 2f
        Brush.horizontalGradient(0f to Sakura.Background.copy(alpha = 0f), 1f to Sakura.Background.copy(alpha = if (card) .55f else .80f), startX = colLeft - 70f * dp, endX = colLeft + 10f * dp)
    }

    /**
     * The sticker's text. A name too long for one line breaks where its script changes (星野夢美子桜庭 | ひなた🌸, never inside
     * 桜庭): a space there makes the sticker break only at it.
     */
    private fun stickerText(): String {
        val name = words.name
        if (name.isEmpty()) return words.greet
        if (name.contains(' ')) return name
        val run = mmName.run(name, IntroFonts.hand(STICKER_FLOOR), .02f)
        if (run.width <= welcomeW || run.size < 2) return name
        val b = tunnelNameBreak(run.text, run.x, run.advance, welcomeW)
        return if (b > 0) run.text.take(b).joinToString("") + " " + run.text.drop(b).joinToString("") else name
    }

    // ---- the burst ----

    private val flareMist = Path()
    private val linesPink = Path()
    private val linesMist = Path()
    private val flarePink = Path()
    // Strokes made once (a frame should not allocate them): fixed widths, and widths in half-dp steps for the rings.
    private val discEdge = Stroke(2.6f * dp)
    private val discAqua = Stroke(1.5f * dp)
    private val chipPink = Stroke(1.2f * dp)
    private val chipAqua = Stroke(1.8f * dp)
    private val tickStroke = Stroke(2f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val sparkStroke = Stroke(2f * dp, cap = StrokeCap.Round)
    private val cardEdge = Stroke(2f * dp)
    private val glowRing = Stroke(3f * dp)
    private val pinkRing = Stroke(1.5f * dp)
    private val strokes = arrayOfNulls<Stroke>(64)
    private fun strokeOf(px: Float): Stroke {
        val i = (px / (.5f * dp)).roundToInt().coerceIn(1, 63)
        return strokes[i] ?: Stroke(i * .5f * dp).also { strokes[i] = it }
    }

    // ======== drawing ========

    /** The room, the depth rings, the rays, the tunnel's petals and the core; after the burst (and for the still) the stage. */
    fun drawWorld(ds: DrawScope, ms: Float, amb: Float) = with(ds) {
        val r = if (reduced) cover else tl.coreRadius(ms, dp, cover)
        if (r >= cover) {
            drawStage(ms, amb)
            return@with
        }
        drawRect(room)
        drawDepthRings(ms)
        drawRays(ms)
        drawTunnelPetals(ms, amb)
        drawCore(ms, amb, r)
        drawSpark(ms)
    }

    private fun zoomAt(ms: Float): Float = if (reduced) 1f else lerp(1.12f, 1f, seg(ms, 0f, tl.exit, IntroEase.SineInOut))

    private fun DrawScope.drawPicture(p: Placement, alpha: Float = 1f) {
        val pic = picture ?: return
        withTransform({ translate(p.ox, p.oy); scale(p.k, p.k, Offset.Zero) }) { drawImage(pic, alpha = alpha) }
    }

    private fun DrawScope.drawBlurFill(alpha: Float) {
        val b = blurred
        if (b == null) drawRect(sky, alpha = alpha)
        else drawImage(b, blurSrc.first, blurSrc.second, IntOffset.Zero, IntSize(w.roundToInt() + 1, h.roundToInt() + 1), alpha = alpha, filterQuality = FilterQuality.Low)
        drawRect(Sakura.Background, alpha = .42f * alpha)
    }

    /** The welcome's ground: the picture (whole, or as a card), or the blush sky; its light; petals; the veil for the words. */
    private fun DrawScope.drawStage(ms: Float, amb: Float) {
        when {
            picture == null -> drawRect(sky)
            card -> drawBlurFill(1f)
            else -> {
                cover(iw, ih, w, h, zoomAt(ms), .5f, fy, .5f * w, fy * h, place)
                drawPicture(place)
            }
        }
        if (reduced) {
            drawFrozenTunnel()
        } else {
            val g = tl.afterglow(ms, if (picture != null && !card) .25f else .6f)
            if (g > 0f) {
                withTransform({ translate(vp.x, vp.y); rotate(6f * amb, Offset.Zero) }) {
                    // Over the plain sky the light is a sunburst: mist rays with pink ones between them, round a soft white core.
                    if (picture == null) withTransform({ rotate(360f / 28f, Offset.Zero) }) { drawPath(glow, glowPink, alpha = g) }
                    drawPath(glow, glowBrush, alpha = g)
                }
                if (picture == null) withTransform({ translate(vp.x, vp.y); scale(110f * dp, 110f * dp, Offset.Zero) }) { drawCircle(mistBloom, 1f, Offset.Zero, alpha = .9f * g) }
            }
            if (card) drawCard(ms)
        }
        // Petals and snow under the veil: behind the words they are muted, so an aqua snowflake never sits under the ink.
        drift.draw(this, amb, if (reduced) 1f else seg(ms, tl.hit + 100f * pace, 400f * pace))
        season.draw(this, amb, if (reduced) 1f else seg(ms, tl.hit, 400f * pace))
        drawRect(scrim, alpha = if (reduced) 1f else seg(ms, tl.hit, 300f * pace))
    }

    /** The picture standing whole as a card (white border, pink hard shadow), settling from a little larger after the burst. */
    private fun DrawScope.drawCard(ms: Float) {
        val k = if (reduced) 1f else lerp(1.05f, 1f, seg(ms, tl.hit, 500f * pace, IntroEase.CubicOut))
        val corner = CornerRadius(18f * dp)
        withTransform({ scale(k, k, cardRect.center) }) {
            drawRoundRect(Sakura.Primary.copy(alpha = .45f), cardRect.topLeft + Offset(6f * dp, 8f * dp), cardRect.size, corner)
            val b = 4f * dp
            drawRoundRect(Sakura.White, cardRect.topLeft - Offset(b, b), Size(cardRect.width + 2f * b, cardRect.height + 2f * b), CornerRadius(18f * dp + b))
            clipPath(cardClip) { drawPicture(cardPlace) }
        }
    }

    /**
     * The reduced-motion still's tunnel, frozen: twelve soft rays (40 %), the light core (over a picture only its glow; in a
     * wide card layout the card) and one OK disc.
     */
    private fun DrawScope.drawFrozenTunnel() {
        for (j in 0 until 12) {
            val a = 2f * PI.toFloat() * j / 12f + .2f * (ih(j, 611) - .5f)
            val tail = coreMax * 1.4f
            val head = reach * (.85f + .25f * ih(j, 612))
            val half = (16f + 14f * ih(j, 613)) * dp
            ray(a, tail, head, half, 1.5f * dp, rayBrush[STILL_GROUP[j % STILL_GROUP.size]], .4f, null, 0f, 0f)
        }
        when {
            card -> drawCard(tl.still)
            picture == null -> drawLight(coreMax, .5f, 0f, .9f)
            // Over the picture the core is light only: no white disc or crest on the person in it.
            else -> withTransform({ translate(vp.x, vp.y); scale(2.4f * coreMax, 2.4f * coreMax, Offset.Zero) }) { drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .45f) }
        }
        // At .8 its title is still 16 sp (two characters of handwriting read from 16).
        withTransform({ translate(stillDisc.x, stillDisc.y); rotate(-6f, Offset.Zero); scale(STILL_DISC, STILL_DISC, Offset.Zero) }) {
            drawDisc(0, .9f, 0f, 1f, 1f)
        }
    }

    /** The core's light: a wide soft glow and the bloom; with [crestA] the slowly turning sakura crest inside it. */
    private fun DrawScope.drawLight(r: Float, crestA: Float, amb: Float, inner: Float) {
        withTransform({ translate(vp.x, vp.y); scale(2.8f * r, 2.8f * r, Offset.Zero) }) { drawCircle(IntroUnitBloom, 1f, Offset.Zero, alpha = .55f) }
        withTransform({ translate(vp.x, vp.y); scale(1.6f * r, 1.6f * r, Offset.Zero) }) { drawCircle(coreBloom, 1f, Offset.Zero) }
        if (crestA > 0f) drawCrest(r, crestA, amb, inner)
    }

    private fun DrawScope.drawCrest(r: Float, a: Float, amb: Float, inner: Float) {
        withTransform({ translate(vp.x, vp.y); rotate(18f * amb, Offset.Zero); scale(.66f * r, .66f * r, Offset.Zero) }) {
            drawPath(crest, Sakura.Primary, alpha = a)
            if (inner > 0f) withTransform({ rotate(36f, Offset.Zero); scale(.42f, .42f, Offset.Zero) }) { drawPath(crest, Sakura.White, alpha = inner) }
            drawCircle(Sakura.White, .14f, Offset.Zero, alpha = (a * 1.6f).coerceAtMost(1f))
        }
    }

    /**
     * The core: a white light with a blush bloom and the sakura crest turning in it; with a picture, the user's portrait grows
     * in it during the cruise (the face, feathered into the white). In the dash it opens to the screen onto the picture (or the
     * sky), the portrait travelling to its place on the stage.
     */
    private fun DrawScope.drawCore(ms: Float, amb: Float, r: Float) {
        if (r <= 0f) return
        val kd = tl.dashK(ms)
        val pk = if (picture == null) 0f else tl.portraitK(ms)
        drawLight(r, 0f, amb, 0f)
        if (ms >= tl.dashAt) {
            val clip = r * lerp(.92f, 1f, kd)
            corePath.reset()
            corePath.addOval(Rect(vp, clip))
            val fill = seg(ms, tl.dashAt, .6f * (tl.hit - tl.dashAt))
            clipPath(corePath) {
                if (picture == null) drawRect(sky, alpha = fill)
                else {
                    if (card) drawBlurFill(fill)
                    dashPlace(ms, kd, clip)
                    drawPicture(place)
                    if (kd < 1f) withTransform({ translate(vp.x, vp.y); scale(clip, clip, Offset.Zero) }) { drawCircle(feather, 1f, Offset.Zero, alpha = 1f - kd) }
                }
            }
        } else if (pk > 0f) {
            val rp = .92f * r * pk
            val s = rp / portraitR
            place.k = facePlace.k * s
            place.ox = vp.x + (facePlace.ox - vp.x) * s
            place.oy = vp.y + (facePlace.oy - vp.y) * s
            corePath.reset()
            corePath.addOval(Rect(vp, rp))
            clipPath(corePath) {
                drawPicture(place, pk)
                withTransform({ translate(vp.x, vp.y); scale(rp, rp, Offset.Zero) }) { drawCircle(feather, 1f, Offset.Zero) }
            }
        }
        // The crest: in the void's light, turning slowly; it gives way to the portrait and to the opening core.
        val ca = .5f * (1f - pk) * (1f - 2.5f * kd).coerceAtLeast(0f) * seg(ms, tl.tunnelAt + 40f * pace, 200f * pace)
        if (ca > 0f) drawCrest(r, ca, amb, .9f * ca / .5f)
    }

    /**
     * The picture's placement while the core opens (progress [kd], clip radius [r]): the face travels on a straight line from the
     * core to its place on the stage while the scale grows geometrically, so it stays steady; over the full-screen picture it
     * never shows an edge inside the circle. At the burst it is exactly the stage's (the home screen's crop, or the card).
     */
    private fun dashPlace(ms: Float, kd: Float, r: Float) {
        if (card) toPlace.set(cardPlace) else cover(iw, ih, w, h, zoomAt(ms), .5f, fy, .5f * w, fy * h, toPlace)
        val px = .5f * iw
        val py = faceFy * ih
        val ax = lerp(facePlace.ox + px * facePlace.k, toPlace.ox + px * toPlace.k, kd)
        val ay = lerp(facePlace.oy + py * facePlace.k, toPlace.oy + py * toPlace.k, kd)
        var k = facePlace.k * (toPlace.k / facePlace.k).pow(kd)
        if (card) {
            place.k = k
            place.ox = ax - px * k
            place.oy = ay - py * k
            return
        }
        val left = max(0f, vp.x - r)
        val top = max(0f, vp.y - r)
        val right = min(w, vp.x + r)
        val bottom = min(h, vp.y + r)
        k = max(k, max((right - left) / iw, (bottom - top) / ih))
        place.k = k
        place.ox = clampF(ax - px * k, right - iw * k, left)
        place.oy = clampF(ay - py * k, bottom - ih * k, top)
    }

    /**
     * The spark: three little arcs spinning in the white void, then at the tunnel's start a pink star flashes open at the core
     * and a thin pink ring rushes out with the first rays.
     */
    private fun DrawScope.drawSpark(ms: Float) {
        val a = 1f - seg(ms, tl.tunnelAt, 120f * pace)
        if (a > 0f) {
            val t = ms / pace / 1000f
            val rr = (6f + 30f * t) * dp
            for (k in 0 until 3) {
                val start = (t * 9f + k * 2.1f) * DEG
                drawArc(SPARK[k], start, 75f, false, Offset(vp.x - rr, vp.y - rr), Size(2f * rr, 2f * rr), alpha = a, style = sparkStroke)
            }
        }
        val k = seg(ms, tl.tunnelAt - 20f * pace, 280f * pace)
        if (k > 0f && k < 1f) {
            introSparkle(sparkle, vp, 30f * dp * sin(PI.toFloat() * k), Sakura.Primary, 1f, 90f * k)
            val e = IntroEase.CubicOut.transform(k)
            drawCircle(Sakura.Primary, coreMax * (1f + 3.5f * e), vp, alpha = .8f * (1f - k), style = strokeOf((3f - 2f * k) * dp))
        }
    }

    /** Two or three faint white rings racing outward down the tunnel, so it reads as depth, not a flat sunburst. */
    private fun DrawScope.drawDepthRings(ms: Float) {
        if (counts.rays == 0 || ms < tl.tunnelAt) return
        val flown = tl.distance(ms)
        val fade = 1f - tl.dashLinear(ms)
        if (fade <= 0f) return
        val span = 7.2f
        for (j in 0 until 3) {
            val p = (flown * .9f + j * span / 3f) % span
            val rr = focal / (7.35f - p)
            val inner = coreMax * 1.6f
            if (rr < inner || rr > reach * 1.2f) continue
            val a = .15f * fade * ((rr - inner) / inner).coerceIn(0f, 1f) * (1f - ((rr - .85f * reach) / (.35f * reach)).coerceIn(0f, 1f))
            if (a > .005f) drawCircle(Sakura.White, rr, vp, alpha = a, style = strokeOf((1f + 3f * rr / reach) * dp))
        }
    }

    private fun DrawScope.drawRays(ms: Float) {
        if (counts.rays == 0 || ms < tl.tunnelAt) return
        val flown = tl.distance(ms)
        val dk = tl.dashK(ms)
        val p = tl.dashLinear(ms)
        // In the dash every ray turns pink; aqua turns white first (by a third of it), so no pink ever lies over aqua.
        val white = min(1f, 3.2f * p)
        val pink = .8f * p
        val line = .55f * (1f - p)
        for (i in 0 until rays.count) {
            if (!rays.pose(i, ms, flown, dk)) continue
            val g = rays.group
            val a = rays.alpha
            if (g == 0) ray(rays.angle, rays.tail, rays.head, rays.half, rays.tailHalf, rayBrush[0], a * (1f - white), rayBrush[2], a * white, line * a)
            else ray(rays.angle, rays.tail, rays.head, rays.half, rays.tailHalf, rayBrush[g], a * (1f - pink), if (g == 4) null else rayBrush[4], a * pink, line * a)
        }
    }

    /**
     * One ray of light along angle [a] from the core: [tailHalf] wide at radius [tail], [half] at radius [head], then a rounded
     * tip. Drawn in its own frame (x along the ray, scaled so the ray's length is 1), so one gradient brush per colour serves
     * every ray: clear at the tail, full 60 % along, soft at the tip. [b2] at [a2] is laid over [b1] (the dash's colour turn);
     * [line] the white line's strength.
     */
    private fun DrawScope.ray(a: Float, tail: Float, head: Float, half: Float, tailHalf: Float, b1: Brush, a1: Float, b2: Brush?, a2: Float, line: Float) {
        val tip = half * 1.15f
        val len = head + tip - tail
        if (len <= 1f) return
        withTransform({ translate(vp.x, vp.y); rotate(a * DEG, Offset.Zero); translate(tail, 0f); scale(len, 1f, Offset.Zero) }) {
            val hx = (head - tail) / len
            val cx = hx + (1f - hx) * .9f
            rayPath.reset()
            rayPath.moveTo(0f, -tailHalf)
            rayPath.lineTo(hx, -half)
            rayPath.quadraticTo(cx, -half, 1f, 0f)
            rayPath.quadraticTo(cx, half, hx, half)
            rayPath.lineTo(0f, tailHalf)
            rayPath.close()
            if (a1 > .005f) drawPath(rayPath, b1, alpha = a1)
            if (b2 != null && a2 > .005f) drawPath(rayPath, b2, alpha = a2)
            if (line > .02f && half > 2.5f * dp) {
                val lh = min(1.3f * dp, .3f * half)
                linePath.reset()
                linePath.moveTo(.35f, 0f)
                linePath.quadraticTo(.75f, -2f * lh, .97f, 0f)
                linePath.quadraticTo(.75f, 2f * lh, .35f, 0f)
                linePath.close()
                drawPath(linePath, lineBrush, alpha = line, blendMode = BlendMode.Plus)
            }
        }
    }

    /** Sakura petals (or mist sparkles with petals off) flying out of the core with the tunnel. */
    private fun DrawScope.drawTunnelPetals(ms: Float, amb: Float) {
        if (counts.petals == 0) return
        val a0 = seg(ms, tl.tunnelAt + 150f * pace, 300f * pace)
        if (a0 <= 0f) return
        val flown = tl.distance(ms)
        val spin = .03f * flown
        val far = 8f
        val near = .3f
        val span = far - near
        for (j in 0 until counts.petals) {
            val p = ih(j, 401) * span + .8f * flown
            val n = floor(p / span).toInt()
            val z = far - (p - n * span)
            val salt = j + 31 * n
            val rr = focal * (.9f + 1.8f * ih(salt, 402)) / z
            if (rr > reach * 1.15f) continue
            val b = 2f * PI.toFloat() * ih(salt, 403) + spin
            val at = Offset(vp.x + cos(b) * rr, vp.y + sin(b) * rr)
            val size = 5f * dp * (2f / z).pow(.85f)
            // Out of the core's glow: they fade in as they leave it.
            val a = a0 * ((far - z) / 2.5f).coerceIn(0f, 1f)
            val rot = 360f * ih(salt, 404) + (90f + 120f * ih(salt, 405)) * amb
            if (!petals) {
                introSparkle(sparkle, at, size * .8f, TunnelMist, a, rot * .3f)
                continue
            }
            val color = if (ih(salt, 406) < .7f) Sakura.Primary.copy(alpha = .9f) else TunnelMist
            withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(.72f * size, size, Offset.Zero) }) {
                drawPath(petal, color, alpha = a)
            }
        }
    }

    /**
     * The status discs: each pops out of the core, hovers fully on screen at its readable size ([tunnelDiscDepth]) while its
     * chip flips CHECK → OK, then flies past the camera, its words fading first (ink never fades over the rays).
     */
    fun drawDiscs(ds: DrawScope, ms: Float) = with(ds) {
        if (reduced || ms >= tl.hit) return@with
        for (i in 0 until tl.discs) {
            val k = (ms - tl.discAt(i)) / tl.discMs
            if (k < 0f || k >= 1f) continue
            val z = tunnelDiscDepth(k)
            val f = 2f / z
            val s = f * lay.discScale * IntroEase.CubicOut.transform((k / .08f).coerceAtMost(1f))
            val p = lay.discs[i]
            val at = Offset(vp.x + (p.x - vp.x) * f, vp.y + (p.y - vp.y) * f)
            val out = seg(k, DISC_HOVER_TO, 1f - DISC_HOVER_TO)
            val textA = 1f - seg(out, 0f, .25f)
            val faceA = 1f - seg(out, .2f, .3f)
            if (faceA <= 0f) continue
            val rot = (lerp(-10f, 4f, seg(k, 0f, DISC_HOVER_TO, IntroEase.SineInOut)) + 16f * out) * (if (i % 2 == 0) 1f else -1f)
            withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(s, s, Offset.Zero) }) { drawDisc(i, k, ms, textA, faceA) }
        }
    }

    private fun DrawScope.drawDisc(i: Int, k: Float, ms: Float, textA: Float, faceA: Float) {
        val r = discR
        withTransform({ scale(r * 1.3f, r * 1.3f, Offset.Zero) }) { drawCircle(mistBloom, 1f, Offset.Zero, alpha = .9f * faceA) }
        drawCircle(TunnelMist, r, Offset.Zero, alpha = faceA)
        drawCircle(Sakura.Primary, r - 1.3f * dp, Offset.Zero, alpha = faceA, style = discEdge)
        // Aqua only between mist, never under the ink, and not while the disc fades over the rays.
        if (faceA >= 1f) drawCircle(Sakura.Aqua, .93f * r, Offset.Zero, style = discAqua)
        withTransform({ rotate(ms * .08f, Offset.Zero) }) { drawCircle(Sakura.MilkTea, .82f * r, Offset.Zero, alpha = faceA, style = ringA[i]) }
        withTransform({ rotate(-ms * .12f, Offset.Zero) }) { drawCircle(Sakura.Primary, .72f * r, Offset.Zero, alpha = .8f * faceA, style = ringB[i]) }
        if (textA > 0f) {
            val label = discLabels[i]
            drawRun(label, -label.width / 2f, -.58f * r, Sakura.TextSecondary, textA)
            val value = discValues[i]
            drawRun(value, -value.width / 2f, -.17f * r, Sakura.Text, textA)
        }
        // The chip flips (squashes and reopens) from CHECK to OK at the middle of the hover.
        val ok = k >= DISC_FLIP
        val flip = if (k in DISC_FLIP - .05f..<DISC_FLIP + .05f) abs(cos(PI.toFloat() * (k - DISC_FLIP + .05f) / .1f)) else 1f
        val run = if (ok) chipOk else chipCheck
        val cw = max(chipOk.width + 22f * dp, chipCheck.width) + 18f * dp
        val ch = 20f * dp
        val cy = .55f * r
        withTransform({ scale(1f, flip.coerceAtLeast(.02f), Offset(0f, cy)) }) {
            drawRoundRect(TunnelMist, Offset(-cw / 2f, cy - ch / 2f), Size(cw, ch), CornerRadius(ch / 2f), alpha = faceA)
            if (faceA >= 1f || !ok) drawRoundRect(if (ok) Sakura.Aqua else Sakura.Primary, Offset(-cw / 2f, cy - ch / 2f), Size(cw, ch), CornerRadius(ch / 2f), alpha = faceA, style = if (ok) chipAqua else chipPink)
            val tw = run.width + (if (ok) 14f * dp else 0f)
            val left = -tw / 2f
            // Squashed flat, the words would lie on the aqua edge (ink on aqua is grey): only the pill turns over.
            if (flip >= .5f && textA > 0f) {
                drawRun(run, left, cy - run.baseline + run.fontPx * .36f, if (ok) Sakura.Text else Sakura.TextSecondary, textA)
                if (ok) {
                    val x = left + run.width + 4f * dp
                    okTick.reset()
                    okTick.moveTo(x, cy)
                    okTick.lineTo(x + 3.5f * dp, cy + 3.5f * dp)
                    okTick.lineTo(x + 10f * dp, cy - 4f * dp)
                    drawPath(okTick, Sakura.Primary, alpha = textA, style = tickStroke)
                }
            }
        }
    }

    /** The 登入 panel (the name types in, the code shows as dots), then the 確認 panel with 出發 lighting up. */
    fun drawPanels(ds: DrawScope, ms: Float) = with(ds) {
        if (reduced || ms < tl.loginAt || ms >= tl.hit) return@with
        val cx = lay.panelCx
        val top = lay.cardTop
        val c = cards
        val loginOut = tl.loginOutAt
        if (ms < loginOut + 200f * pace) {
            val textA = tl.panelText(ms, loginOut)
            panel(cx, top, c.loginH, seg(ms, tl.loginAt, 140f * pace, IntroEase.CubicOut), tl.panelPull(ms, loginOut), textA, tabLogin, tabLoginLatin) {
                if (textA <= 0f) return@panel
                val left = cx - c.pw / 2f
                drawRun(c.labelName, left + 18f * dp, top + c.row1Base - c.labelName.baseline, Sakura.TextSecondary, textA)
                drawRun(c.labelCode, left + 18f * dp, top + c.row2Base - c.labelCode.baseline, Sakura.TextSecondary, textA)
                val vx = left + c.valueX
                drawLine(Sakura.MilkTea, Offset(vx, top + c.row1Base + 6f * dp), Offset(left + c.pw - 18f * dp, top + c.row1Base + 6f * dp), 1.2f * dp)
                drawLine(Sakura.MilkTea, Offset(vx, top + c.row2Base + 6f * dp), Offset(left + c.pw - 18f * dp, top + c.row2Base + 6f * dp), 1.2f * dp)
                val nameRun = c.nameRun
                val step = tl.typeStep(nameRun.size)
                // Like a real field: a name too long for it (even at the smallest size) scrolls left as it types, the cursor in view.
                val fieldW = left + c.pw - 18f * dp - vx
                val typed = ((ms - tl.typeAt) / step).toInt().coerceIn(0, (nameRun.size - 1).coerceAtLeast(0))
                val scroll = if (nameRun.size == 0) 0f else (nameRun.x[typed] + nameRun.advance[typed] + .3f * nameRun.fontPx - fieldW).coerceAtLeast(0f)
                clipRect(vx - 2f * dp, top + 4f * dp, vx + fieldW, top + c.row1Base + 5f * dp) {
                    drawTyped(nameRun, vx - scroll, top + c.row1Base - nameRun.baseline, Sakura.Text, textA, ms, tl.typeAt, step, hold = 0f)
                }
                // Six dots for the code, popping in one by one behind the name (nothing is read: it is only a look).
                val dotsAt = tl.typeAt + step * nameRun.size + 50f * pace
                var shown = 0
                for (j in 0 until 6) {
                    val dk = seg(ms, dotsAt + j * 30f * pace, 60f * pace)
                    if (dk <= 0f) break
                    shown = j + 1
                    drawCircle(Sakura.Primary, 4.5f * dp * lerp(1.5f, 1f, dk), Offset(vx + 6f * dp + j * 16f * dp, top + c.row2Base - 7f * dp), alpha = textA)
                }
                val done = dotsAt + 6 * 30f * pace
                if (ms >= dotsAt && (ms < done || ((ms - done) % 320f) < 160f)) {
                    drawRect(Sakura.Primary, Offset(vx + shown * 16f * dp, top + c.row2Base - 16f * dp), Size(2.4f * dp, 17f * dp), alpha = textA)
                }
            }
        }
        if (!tl.compact && ms >= tl.confirmAt + 60f * pace) {
            val textA = tl.panelText(ms, tl.dashAt)
            panel(cx, top, c.confirmH, seg(ms, tl.confirmAt + 60f * pace, 140f * pace, IntroEase.CubicOut), tl.panelPull(ms, tl.dashAt), textA, tabConfirm, tabConfirmLatin) {
                val pk = seg(ms, tl.pressAt, 90f * pace)
                val dip = 1f - .05f * sin(PI.toFloat() * seg(ms, tl.pressAt, 140f * pace))
                val bl = cx - c.buttonW / 2f
                val bt = top + c.buttonTop
                val ring = seg(ms, tl.pressAt, 320f * pace)
                if (ring > 0f && ring < 1f) {
                    val g = 14f * dp * IntroEase.CubicOut.transform(ring)
                    drawRoundRect(TunnelMist, Offset(bl - g, bt - g), Size(c.buttonW + 2f * g, buttonH + 2f * g), CornerRadius(buttonH / 2f + g), alpha = 1f - ring, style = glowRing)
                    drawRoundRect(Sakura.Primary, Offset(bl - g * .6f, bt - g * .6f), Size(c.buttonW + 1.2f * g, buttonH + 1.2f * g), CornerRadius(buttonH / 2f + g * .6f), alpha = .7f * (1f - ring), style = pinkRing)
                }
                withTransform({ scale(dip, dip, Offset(cx, bt + buttonH / 2f)) }) {
                    drawRoundRect(lerpColor(TunnelMist, Sakura.Primary, pk), Offset(bl, bt), Size(c.buttonW, buttonH), CornerRadius(buttonH / 2f))
                    drawRoundRect(Sakura.Primary, Offset(bl, bt), Size(c.buttonW, buttonH), CornerRadius(buttonH / 2f), style = cardEdge)
                    if (textA > 0f) drawRun(c.goRun, cx - c.goRun.width / 2f, bt + (buttonH - c.goRun.height) / 2f, Sakura.Text, textA)
                }
                if (textA > 0f) drawRun(c.confirmRun, cx - c.confirmRun.width / 2f, top + c.confirmTop, Sakura.Text, textA)
            }
        }
    }

    /**
     * A card [TunnelLayout.pw] wide with its top at [top]: a pink hard shadow, an opaque mist face with a pink border and a pink
     * tab with its title on the top edge; [open] wipes it open from the middle. [pull] takes it away: it shrinks into the core
     * and fades, a faint copy of its edge ahead of it (as if the camera flew past); its words ([textA]) are gone first.
     */
    private inline fun DrawScope.panel(cx: Float, top: Float, height: Float, open: Float, pull: Float, textA: Float, tab: GlyphRun, tabLatin: GlyphRun, body: DrawScope.() -> Unit) {
        if (open <= 0f || pull >= 1f) return
        val pw = lay.pw
        val left = cx - pw / 2f
        val half = (pw / 2f + 12f * dp) * open
        val cy = top + height / 2f
        val s = lerp(1f, .2f, pull)
        val a = 1f - pull
        val corner = CornerRadius(20f * dp)
        withTransform({ scale(s, s, vp) }) {
            if (pull > 0f) withTransform({ scale(1.08f, 1.08f, Offset(cx, cy)) }) {
                drawRoundRect(Sakura.Primary, Offset(left, top), Size(pw, height), corner, alpha = .35f * a, style = cardEdge)
            }
            withTransform({
                scale(lerp(.96f, 1f, open), lerp(.96f, 1f, open), Offset(cx, cy))
                clipRect(cx - half, top - 30f * dp, cx + half, top + height + 20f * dp)
            }) {
                drawRoundRect(Sakura.Primary.copy(alpha = .35f), Offset(left + 5f * dp, top + 7f * dp), Size(pw, height), corner, alpha = a)
                drawRoundRect(TunnelMist, Offset(left, top), Size(pw, height), corner, alpha = a)
                drawRoundRect(Sakura.Primary, Offset(left, top), Size(pw, height), corner, alpha = a, style = cardEdge)
                val th = 30f * dp
                val tw = tab.width + tabLatin.width + 34f * dp
                val tl0 = left + 18f * dp
                val tt = top - th / 2f
                drawRoundRect(Sakura.Primary, Offset(tl0, tt), Size(tw, th), CornerRadius(th / 2f), alpha = a)
                if (textA > 0f) {
                    drawRun(tab, tl0 + 14f * dp, tt + (th - tab.height) / 2f, Sakura.Text, textA)
                    drawRun(tabLatin, tl0 + 14f * dp + tab.width + 6f * dp, tt + (th - tab.height) / 2f + tab.baseline - tabLatin.baseline, Sakura.Text, textA)
                }
                body()
            }
        }
    }

    /** The dash's flood, its speed lines, the white burst with its true-white peak, the shock rings, the flare and the petals. */
    fun drawBurst(ds: DrawScope, ms: Float, amb: Float) = with(ds) {
        if (reduced) return@with
        val fl = tl.flood(ms)
        if (fl > 0f) drawRect(floodBrush, alpha = fl)
        drawSpeedLines(ms)
        val f = tl.flash(ms)
        if (f > 0f) drawRect(Sakura.White, alpha = f)
        if (ms < tl.hit - 70f * pace) return@with
        // Two shock rings, quick and thin, fading fast (they are past the words before the words come).
        for (j in 0 until 2) {
            val k = seg(ms, tl.hit + j * 60f * pace, 400f * pace)
            if (k <= 0f || k >= 1f) continue
            val rr = reach * (.06f + 1.05f * (1f - (1f - k).pow(2.2f)))
            val a = (1f - k) * (1f - k)
            drawCircle(TunnelMist, rr, vp, alpha = .9f * a, style = strokeOf((5f + 12f * k) * dp))
            drawCircle(Sakura.Primary, rr, vp, alpha = .8f * a, style = strokeOf((1.5f + 2.5f * (1f - k)) * dp))
        }
        // The star flare: twelve blades at full strength, long pink and short mist, turning a little; gone by hit + 110 ms.
        val fa = seg(ms, tl.hit - 70f * pace, 40f * pace) * (1f - seg(ms, tl.hit + 60f * pace, 50f * pace))
        if (fa > 0f) {
            val k = seg(ms, tl.hit - 70f * pace, 180f * pace)
            flareMist.reset()
            flarePink.reset()
            for (i in 0 until 12) {
                val a = 2f * PI.toFloat() * i / 12f + .5f * k + (i % 2) * .13f
                val len = (if (i % 2 == 0) 90f + 260f * k else 50f + 120f * k) * dp
                (if (i % 2 == 0) flarePink else flareMist).blade(vp.x, vp.y, a, 8f * dp, len, (5f + 6f * k) * dp)
            }
            drawPath(flareMist, TunnelMist, alpha = fa)
            drawPath(flarePink, Sakura.Primary, alpha = fa)
        }
        // Petals flung out of the core.
        val n = counts.burst
        val k = (ms - tl.hit) / (820f * pace)
        if (n > 0 && k > 0f && k < 1f) {
            val e = IntroEase.CubicOut.transform(k)
            val a = 1f - seg(k, .55f, .45f)
            for (j in 0 until n) {
                val b = 2f * PI.toFloat() * (j + .5f * ih(j, 421)) / n
                val rr = (30f + (380f + 220f * ih(j, 422)) * e) * dp
                val at = Offset(vp.x + cos(b) * rr, vp.y + sin(b) * rr)
                val size = (6f + 6f * k) * dp * (.8f + .4f * ih(j, 423))
                val rot = 360f * ih(j, 424) + 300f * k + 40f * amb
                if (!petals) { introSparkle(sparkle, at, size * .8f, TunnelMist, a, rot * .3f); continue }
                val c = if (ih(j, 425) < .65f) Sakura.Primary.copy(alpha = .92f) else TunnelMist
                withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(.72f * size, size, Offset.Zero) }) { drawPath(petal, c, alpha = a) }
            }
        }
    }

    /**
     * The dash's speed lines: pink and mist streaks shooting out of the opening core, accelerating, over everything until the
     * white burst swallows them (the original's outward dashes, re-drawn as straight blades).
     */
    private fun DrawScope.drawSpeedLines(ms: Float) {
        if (counts.lines == 0 || ms < tl.dashAt || ms > tl.hit + 200f * pace) return
        linesPink.reset()
        linesMist.reset()
        val span = tl.hit - tl.dashAt
        for (j in 0 until counts.lines) {
            val t0 = tl.dashAt + ih(j, 432) * .55f * span
            val age = (ms - t0) / (240f * pace)
            if (age <= 0f) continue
            val head = (16f + 620f * age.pow(1.6f)) * dp
            if (head - 40f * dp > cover) continue
            val len = (30f + 170f * age) * dp
            val b = 2f * PI.toFloat() * ih(j, 431)
            (if (ih(j, 433) < .62f) linesPink else linesMist).blade(vp.x, vp.y, b, max(0f, head - len), head, (1.2f + 2.6f * age.coerceAtMost(1.5f)) * dp)
        }
        drawPath(linesMist, TunnelMist)
        drawPath(linesPink, Sakura.Primary)
    }

    /**
     * DSH MOBILE at the top left and the time at the top right, each on an opaque mist pill, and an opaque mist face under the
     * host's half-transparent 跳過 SKIP pill: over a rose-brown or aqua ray its ink turned grey. The faces are there from the
     * first frame; only the words fade in (ink fading over a ray is grey too).
     */
    fun drawHud(ds: DrawScope, ms: Float) = with(ds) {
        val a = if (reduced) 1f else seg(ms, 120f * pace, 200f * pace)
        val top = insetTop + 12f * dp
        val ph = brand.height + 8f * dp
        val dot = 6f * dp
        val bw = brand.width + dot + 26f * dp
        drawRoundRect(TunnelMist, Offset(14f * dp, top), Size(bw, ph), CornerRadius(ph / 2f))
        val cw = clock.width + 20f * dp
        drawRoundRect(TunnelMist, Offset(w - 14f * dp - cw, top), Size(cw, ph), CornerRadius(ph / 2f))
        if (skippable) drawRoundRect(TunnelMist, skipRect.topLeft, skipRect.size, CornerRadius(14f * dp))
        if (a <= 0f) return@with
        drawCircle(Sakura.Primary, dot / 2f, Offset(14f * dp + 10f * dp + dot / 2f, top + ph / 2f), alpha = a)
        drawRun(brand, 14f * dp + 10f * dp + dot + 6f * dp, top + 4f * dp, Sakura.TextSecondary, a)
        drawRun(clock, w - 14f * dp - cw + 10f * dp, top + 4f * dp + brand.baseline - clock.baseline, Sakura.TextSecondary, a)
    }

    /** 歡迎回來 rising once the flare is gone, the name stamped in as a sticker, and "Welcome back" typed under it. */
    fun drawWelcome(ds: DrawScope, ms: Float) = with(ds) {
        if (!reduced && ms < tl.greetAt) return@with
        greet?.let { g ->
            val a = if (reduced) 1f else seg(ms, tl.greetAt, 220f * pace)
            val e = IntroEase.CubicOut.transform(a)
            drawRun(g, welcomeX - g.width / 2f, greetTop + 10f * dp * (1f - e), Sakura.Text, a)
        }
        drawSticker(sticker, welcomeX, stickerTop, ms, tl.nameAt, pace, pace < 1f, reduced, tl.exit)
        latin?.let { drawTyped(it, welcomeX - it.width / 2f, latinTop, Sakura.TextSecondary, 1f, ms, tl.nameAt + 160f * pace, 22f * pace, reduced = reduced) }
    }

    private companion object {
        /** The sticker's smallest size before it breaks onto two lines. */
        const val STICKER_FLOOR = 32f
        /** The still's one OK disc, a little smaller than a hovering one. */
        const val STILL_DISC = .8f
    }
}

/** A diamond blade from radius [r0] to [len] along [a], [half] wide at a quarter of its length (the flare's points). */
private fun Path.blade(cx: Float, cy: Float, a: Float, r0: Float, len: Float, half: Float) {
    val dx = cos(a)
    val dy = sin(a)
    val rm = r0 + (len - r0) * .25f
    moveTo(cx + dx * r0, cy + dy * r0)
    lineTo(cx + dx * rm - dy * half, cy + dy * rm + dx * half)
    lineTo(cx + dx * len, cy + dy * len)
    lineTo(cx + dx * rm + dy * half, cy + dy * rm - dx * half)
    close()
}
