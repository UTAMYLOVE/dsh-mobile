package app.dshmobile.ui.fog

// Adapted from Rearpoem/dsh-link-start (c) 2026 FHun, MIT License: the perspective ray tunnel (head = focal / z, a ray's speed
// 1.6 + 12u², rays switched on at about 110 a second, widths .03–.095 rad, lengths 1.2–4.4), the shock rings' radius curve
// (.06 + 1.05 (1 − (1 − k)^2.2)) and the status discs' flight (growing past the camera while turning −8° → 22°). The colours,
// the petal-headed rays, the panels, the words and the timing are our own; nothing of its name, script, images or sounds.

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.util.lerp
import app.dshmobile.BuildConfig
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 「櫻花隧道」 (Sakura tunnel), the startup look [IntroStyle.TUNNEL], the most "anime opening" of the five: out of a white void
 * a tunnel of petal-headed light rays in the six colours spins up and rushes at the camera, with sakura petals in it; small
 * status discs fly past showing the user's own values (編號, 等級, today's date and the app's 版本) and flip to OK; a 登入 panel
 * types the name in, a 確認 panel lights 出發; the tunnel turns pink and dashes, the white core opens onto the user's picture
 * and bursts white; the name stamps in as a sticker over the picture (歡迎回來) and the sakura iris opens into the app.
 *
 * Fixed moves and stretching holds ([TunnelTimeline]): 8 s cruises longer in the tunnel, lets the panels be read and holds the
 * welcome, but nothing moves slower. Everything is a pure function of the run's clock, read only in draw lambdas, so a run
 * composes once. With reduced motion the host holds one still: the settled welcome over the picture with its faint light rays.
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
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = line) {
        val scope = this
        val scene = remember(scope, words, tl) {
            TunnelScene(scope, tl, words, IntroMeasure(measurer, density.density, density.fontScale, 1f, coverage))
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
    val now = remember { Date() }
    return remember(intro, default, number, level) {
        val name = intro.name.trim()
        TunnelWords(
            greet = tunnelGreet(intro.welcome, default), greetIsDefault = intro.welcome.isBlank(), name = name, labels = labels,
            values = listOf(number, level, SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(now), "v" + BuildConfig.VERSION_NAME),
            login = login, loginName = loginName, loginCode = loginCode, loginValue = name.ifEmpty { number },
            confirm = confirm, confirmLine = confirmLine, go = go, time = SimpleDateFormat("HH:mm", Locale.ROOT).format(now),
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

    /** The tunnel starts (the void's spark is before it). */
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
    private val discFirst = tunnelAt + 200f * plan.pace
    /** The discs share the tunnel and its cruise: 80 ms apart at 3 s, up to 420 ms apart on a long run (one at a time). */
    val discStep: Float = ((plan.end(B_CRUISE) - discMs - discFirst) / (discs - 1)).coerceIn(80f * plan.pace, 420f)
    fun discAt(i: Int): Float = discFirst + i * discStep

    /** The name starts typing into the 名字 field, at most 220 ms for the whole name. */
    val typeAt: Float = loginAt + 110f * plan.pace
    fun typeStep(chars: Int): Float = min(70f, 220f / chars.coerceAtLeast(1)) * plan.pace
    /** 出發 lights up. */
    val pressAt: Float = confirmAt + 150f * plan.pace
    /** The eyebrow (歡迎回來) rises; the name sticker stamps in. */
    val greetAt: Float = hit + 40f * plan.pace
    val nameAt: Float = hit + 120f * plan.pace

    /** The dash's progress, eased in: slow, then everything at once (the speed and the opening core). */
    fun dashK(ms: Float): Float = seg(ms, dashAt, hit - dashAt, IntroEase.CubicIn)

    /** The dash's plain progress: the rays turn pink and the edges close in from its first frame. */
    fun dashLinear(ms: Float): Float = seg(ms, dashAt, hit - dashAt)

    /** The white burst over everything: up in the dash's last 50 ms, held 40 ms, gone 380 ms later (all × pace). */
    fun flash(ms: Float): Float {
        val d = ms - hit
        return when {
            d < -50f * pace -> 0f
            d < 0f -> FLASH_PEAK * (1f + d / (50f * pace))
            d < 40f * pace -> FLASH_PEAK
            else -> FLASH_PEAK * (1f - IntroEase.QuintOut.transform(((d - 40f * pace) / (380f * pace)).coerceIn(0f, 1f)))
        }
    }

    /**
     * The pink dash closing in from the edges: from 40 % of the dash (the aqua rays are white by then, so the pink never lies
     * over aqua), then out within 120 ms of the burst.
     */
    fun vignette(ms: Float): Float = if (ms < hit) .72f * seg(dashLinear(ms), .4f, .6f) else .72f * (1f - seg(ms, hit, 120f * pace))

    /** How much of the user's picture shows in the opening core: it fades in over the dash's first 60 %. */
    fun pictureK(ms: Float): Float = if (ms >= hit) 1f else seg(ms, dashAt, .6f * (hit - dashAt))

    /** The tunnel's core (the white void the rays come out of): it appears, swells a little, then opens to [cover] in the dash. */
    fun coreRadius(ms: Float, dp: Float, cover: Float): Float {
        if (ms >= hit) return cover
        val appear = seg(ms, tunnelAt - 60f * pace, 420f * pace, IntroEase.BackOut) * 20f * dp
        val swell = 10f * dp * seg(ms, tunnelAt, dashAt - tunnelAt)
        return lerp(appear + swell, cover, dashK(ms))
    }

    /**
     * The welcome's light rays: full at the burst, settling to [settle] behind the name (and in the still): faint over the
     * user's picture, stronger over the plain sky.
     */
    fun afterglow(ms: Float, settle: Float): Float =
        if (ms < hit) 0f else seg(ms, hit, 160f * pace) * lerp(1f, settle, seg(ms, hit + 80f * pace, 560f * pace, IntroEase.SineInOut))

    /** The tunnel's speed (depth units per second of the clock): up to cruise, calmer behind the panels, then the dash. */
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
        return min(CRUISE, 5f + 14f * u * u)
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
        const val DISC_MS = 580f
        const val CRUISE = 14f
        const val SLOW = 5f
        const val DASH = 46f
        const val FLASH_PEAK = .92f
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

/** How many things a run draws, by effect level ([introCount] for the user's 數量; the rays are the look itself, fixed). */
internal class TunnelCounts(val rays: Int, val petals: Int, val burst: Int, val drift: Int, val season: Int, val lines: Int) {
    companion object {
        fun of(level: EffectLevel, fx: IntroFx, reduced: Boolean): TunnelCounts = TunnelCounts(
            rays = if (reduced) 0 else when (level) { EffectLevel.FULL -> 130; EffectLevel.BALANCED -> 90; EffectLevel.SAVER -> 48 },
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

/**
 * The colours of the rays, weighted (pink ×3, blush ×2, aqua ×2, milk tea ×2, rose-brown ×2, white), and the group each is drawn
 * in. The groups draw aqua first, rose-brown last, each ray inside a mist rim, so aqua never touches milk tea, rose-brown or
 * ink (their blend is grey).
 */
private val RAY_GROUP = intArrayOf(4, 4, 4, 1, 1, 0, 0, 3, 3, 5, 5, 2)
/** The void's spark: three little arcs (aqua only ever over the white room or the mist core). */
private val SPARK = arrayOf(Sakura.Primary, Sakura.MilkTea, Sakura.Aqua)
private val GROUP_COLOR = arrayOf(Sakura.Aqua, Sakura.Background, Sakura.White, Sakura.MilkTea, Sakura.Primary, Sakura.RoseBrown)

/** Where each disc heads (dp from the core at scale 1): up-right, down-left, up-left, down-right, off the corners. */
private val DISC_DIR = arrayOf(Offset(118f, -160f), Offset(-122f, 150f), Offset(-118f, -170f), Offset(124f, 160f))

/**
 * Everything one run draws, built once per size: the paths, brushes and every text measured. The draw functions read only this
 * and the clock, so a frame lays out no text and allocates almost nothing (the ray paths are reset and refilled).
 */
internal class TunnelScene(sc: IntroScope, val tl: TunnelTimeline, private val words: TunnelWords, mm: IntroMeasure) {
    private val w = sc.widthPx
    private val h = sc.heightPx
    private val dp = sc.density.density
    private val reduced = sc.reduced
    private val insetTop = sc.insetTop
    private val pace = tl.pace
    private val wide = w > h

    /** The vanishing point: the core of the tunnel, a little above the middle. */
    val vp = Offset(w / 2f, h * (if (wide) .42f else .45f))
    private val reach = hypot(w, h) / 2f
    private val focal = reach * .2f
    /** A circle round [vp] this large covers the whole screen. */
    private val cover = maxOf(hypot(vp.x, vp.y), hypot(w - vp.x, vp.y), hypot(vp.x, h - vp.y), hypot(w - vp.x, h - vp.y)) + 2f * dp

    private val counts = TunnelCounts.of(sc.level, sc.fx, reduced)
    private val backdrop = IntroBackdrop(sc.picture, w, h, sc.align)
    private val drift = IntroDrift(w, h, dp, counts.drift, sc.fx, salt = 40)
    private val season = IntroSeasonFx(w, h, dp, sc.fx.season, counts.season, sc.fx)
    private val petal = Kawaii.petalPath()
    private val sparkle = Kawaii.sparklePath()
    private val petals = sc.fx.petals

    // ---- the room, the core and the after-glow ----

    private val room = Brush.radialGradient(
        0f to Sakura.White, .35f to lerpColor(Sakura.White, Sakura.Background, .25f), 1f to Sakura.Background, center = vp, radius = reach * 1.05f,
    )
    /** A soft mist disc of radius 1 at the origin (the core's glow, the discs' halo). */
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
    private val vignetteBrush = Brush.radialGradient(
        0f to Sakura.Primary.copy(alpha = 0f), .5f to Sakura.Primary.copy(alpha = 0f), .82f to Sakura.Primary.copy(alpha = .55f),
        1f to Sakura.Primary, center = vp, radius = cover,
    )

    // ---- the rays ----

    private val rayV = FloatArray(counts.rays) { .75f + .5f * ih(it, 301) }
    private val rayLen = FloatArray(counts.rays) { 1.2f + 3.2f * ih(it, 302) }
    private val rayFar = FloatArray(counts.rays) { 6f + 3f * ih(it, 303) }
    private val rayStart = FloatArray(counts.rays) { min(rayFar[it], 3f + 6f * ih(it, 304)) }
    /** About 150 rays a second switch on (× pace), each somewhere down the tunnel: it fills from the core in under a second. */
    private val rayOn = FloatArray(counts.rays) { tl.tunnelAt + it * 6.5f * pace }
    private val rayFlown = FloatArray(counts.rays) { tl.distance(rayOn[it]) }
    private val groups = Array(6) { Path() }
    /** Each group's rays grown by [rimGrow] all round: their mist rims, filled (cheaper on the GPU than stroking them). */
    private val rims = Array(6) { Path() }
    private val rimGrow = 1.1f * dp

    // ---- the discs ----

    private val discR = 58f * dp
    private val discLabels = Array(tl.discs) { mm.run(words.labels[it], IntroFonts.hand(17f), .08f) }
    private val discValues = Array(tl.discs) { mm.fit(words.values[it], IntroFonts.latin(16f, FontWeight.Bold), .04f, 16f, 10f, 1.5f * discR) }
    private val chipCheck = mm.run("CHECK", IntroFonts.latin(9.5f, FontWeight.Bold), .14f)
    private val chipOk = mm.run("OK", IntroFonts.latin(11f, FontWeight.Bold), .12f)
    private val ringA = Array(tl.discs) { i ->
        Stroke(3f * dp, pathEffect = PathEffect.dashPathEffect(FloatArray(6) { k -> (if (k % 2 == 0) 4f + 22f * ih(i * 6 + k, 511) else 5f + 6f * ih(i * 6 + k, 512)) * dp }, 0f))
    }
    private val ringB = Array(tl.discs) { i ->
        Stroke(1.6f * dp, pathEffect = PathEffect.dashPathEffect(FloatArray(4) { k -> (if (k % 2 == 0) 2f + 10f * ih(i * 4 + k, 513) else 4f + 4f * ih(i * 4 + k, 514)) * dp }, 0f))
    }
    private val okTick = Path()

    // ---- the panels ----

    private val pw = min(w - 56f * dp, 330f * dp)
    private val cardCy = h * (if (wide) .56f else .57f)
    private val pad = 20f * dp
    private val tabLogin = mm.run(words.login, IntroFonts.hand(18f), .06f)
    private val tabLoginLatin = mm.run("SIGN IN", IntroFonts.latin(10f, FontWeight.Bold), .22f)
    private val tabConfirm = mm.run(words.confirm, IntroFonts.hand(18f), .06f)
    private val tabConfirmLatin = mm.run("CONFIRM", IntroFonts.latin(10f, FontWeight.Bold), .22f)
    private val labelName = mm.run(words.loginName, IntroFonts.hand(16f, small = true), .06f)
    private val labelCode = mm.run(words.loginCode, IntroFonts.hand(16f, small = true), .06f)
    private val labelCol = max(labelName.width, labelCode.width)
    private val valueX = 18f * dp + labelCol + 14f * dp
    private val nameRun = if (words.name.isEmpty()) {
        mm.fit(words.loginValue, IntroFonts.latin(18f, FontWeight.Bold), .06f, 18f, 12f, pw - valueX - 18f * dp)
    } else {
        mm.fit(words.loginValue, IntroFonts.hand(28f), .04f, 28f, 15f, pw - valueX - 18f * dp)
    }
    private val row1Base = pad + 8f * dp + max(nameRun.baseline, labelName.baseline)
    private val row2Base = row1Base + 16f * dp + labelCode.height
    private val loginH = row2Base + pad
    private val confirmRun = mm.fit(words.confirmLine, IntroFonts.hand(20f), .06f, 20f, 15f, pw - 2f * pad)
    private val goRun = mm.run(words.go, IntroFonts.hand(22f), .1f)
    private val buttonH = 44f * dp
    private val buttonW = goRun.width + 64f * dp
    private val confirmTop = pad + 8f * dp
    private val buttonTop = confirmTop + confirmRun.height + 12f * dp
    private val confirmH = buttonTop + buttonH + pad

    // ---- the HUD and the welcome ----

    private val brand = mm.run(IntroLatin.BRAND, IntroFonts.latin(11f, FontWeight.Bold), .28f)
    private val clock = mm.run(words.time, IntroFonts.latin(11f, FontWeight.Bold), .12f)
    private val greet: GlyphRun? = if (words.name.isEmpty()) null else mm.fit(words.greet, IntroFonts.hand(30f), .08f, 30f, 18f, w - 64f * dp)
    private val sticker = IntroSticker(mm, words.name.ifEmpty { words.greet }, w - 48f * dp, 80f, 32f, dp)
    private val latin: GlyphRun? = if (words.greetIsDefault) mm.run(IntroLatin.WELCOME_BACK, IntroFonts.latin(13f, FontWeight.SemiBold), .16f) else null
    private val blockH = (greet?.let { it.height + 6f * dp } ?: 0f) + sticker.height + (latin?.let { 10f * dp + it.height } ?: 0f)
    private val blockTop = min(h * (if (wide) .50f else .58f), h - sc.insetBottom - 64f * dp - blockH)
    private val greetTop = blockTop
    private val stickerTop = blockTop + (greet?.let { it.height + 6f * dp } ?: 0f)
    private val latinTop = stickerTop + sticker.height + 10f * dp
    /**
     * The blush veil behind the words: clear above them, .80 from the greeting's top to just under the welcome, a little lighter
     * further down (the picture's foot shows). Ink never lies on more than a fifth of whatever is under the veil.
     */
    private val scrim: Brush = run {
        val from = blockTop - 150f * dp
        val to = blockTop + blockH + 200f * dp
        val span = to - from
        Brush.verticalGradient(
            0f to Sakura.Background.copy(alpha = 0f), 150f * dp / span to Sakura.Background.copy(alpha = .80f),
            (150f * dp + blockH + 30f * dp) / span to Sakura.Background.copy(alpha = .80f), 1f to Sakura.Background.copy(alpha = .55f),
            startY = from, endY = to,
        )
    }

    // ---- the burst ----

    private val flareMist = Path()
    private val linesPink = Path()
    private val linesMist = Path()
    private val flarePink = Path()
    // Fixed-width strokes, made once (a frame should not allocate them).
    private val coreRing = Stroke(1.4f * dp)
    private val discEdge = Stroke(2.4f * dp)
    private val discAqua = Stroke(1.4f * dp)
    private val chipPink = Stroke(1.2f * dp)
    private val chipAqua = Stroke(1.8f * dp)
    private val tickStroke = Stroke(2f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val sparkStroke = Stroke(2f * dp, cap = StrokeCap.Round)
    private val cardEdge = Stroke(2f * dp)
    private val glowRing = Stroke(3f * dp)
    private val pinkRing = Stroke(1.5f * dp)

    // ======== drawing ========

    /** The room, the rays, the tunnel's petals, the core (the picture as it opens), and after the burst the welcome's ground. */
    fun drawWorld(ds: DrawScope, ms: Float, amb: Float) = with(ds) {
        val r = tl.coreRadius(ms, dp, cover)
        val zoom = backdrop.zoom(ms, tl.exit, from = 1.12f, reduced = reduced)
        if (r >= cover) {
            backdrop.draw(this, zoom)
            val g = tl.afterglow(ms, if (backdrop.hasPicture) .25f else .75f)
            if (g > 0f) {
                withTransform({ translate(vp.x, vp.y); rotate(6f * amb, Offset.Zero) }) {
                    // Over the plain sky the light is a sunburst: mist rays with pink ones between them, round a soft white core.
                    if (!backdrop.hasPicture) withTransform({ rotate(360f / 28f, Offset.Zero) }) { drawPath(glow, glowPink, alpha = g) }
                    drawPath(glow, glowBrush, alpha = g)
                }
                if (!backdrop.hasPicture) withTransform({ translate(vp.x, vp.y); scale(110f * dp, 110f * dp, Offset.Zero) }) { drawCircle(mistBloom, 1f, Offset.Zero, alpha = .9f * g) }
            }
            // Petals and snow under the veil: behind the words they are muted, so an aqua snowflake never sits under the ink.
            drift.draw(this, amb, if (reduced) 1f else seg(ms, tl.hit + 100f * pace, 400f * pace))
            season.draw(this, amb, if (reduced) 1f else seg(ms, tl.hit, 400f * pace))
            drawRect(scrim, alpha = if (reduced) 1f else seg(ms, tl.hit, 300f * pace))
            return@with
        }
        drawRect(room)
        drawRays(ms)
        drawTunnelPetals(ms, amb)
        if (r > 0f) {
            val dk = tl.dashK(ms)
            withTransform({ translate(vp.x, vp.y); scale(r * 2.3f + 18f * dp, r * 2.3f + 18f * dp, Offset.Zero) }) { drawCircle(mistBloom, 1f, Offset.Zero) }
            drawCircle(TunnelMist, r, vp)
            val pk = tl.pictureK(ms)
            if (pk > 0f) {
                corePath.reset()
                corePath.addOval(Rect(vp, r))
                clipPath(corePath) { backdrop.draw(this, zoom, pk) }
            }
            drawCircle(Sakura.Primary, r, vp, alpha = .6f * (1f - dk), style = coreRing)
        }
        drawSpark(ms)
    }

    /** The three little arcs spinning in the void before the tunnel (the "loading spark"). */
    private fun DrawScope.drawSpark(ms: Float) {
        val a = 1f - seg(ms, tl.tunnelAt + 100f * pace, 200f * pace)
        if (a <= 0f) return
        val t = ms / pace / 1000f
        val rr = (5f + 10f * t) * dp
        for (k in 0 until 3) {
            val start = (t * 9f + k * 2.1f) * (180f / PI.toFloat())
            drawArc(
                SPARK[k], start, 75f, false, Offset(vp.x - rr, vp.y - rr), Size(2f * rr, 2f * rr), alpha = a,
                style = sparkStroke,
            )
        }
    }

    private fun DrawScope.drawRays(ms: Float) {
        if (counts.rays == 0 || ms < tl.tunnelAt) return
        val flown = tl.distance(ms)
        val dk = tl.dashK(ms)
        val pink = tl.dashLinear(ms)
        val spin = .03f * flown
        for (p in groups) p.reset()
        for (p in rims) p.reset()
        for (i in 0 until counts.rays) {
            if (ms < rayOn[i]) break
            val f = flown - rayFlown[i]
            val z = tunnelRayDepth(f, rayV[i], rayLen[i], rayFar[i], rayStart[i])
            val salt = i + 977 * tunnelRayCycle(f, rayV[i], rayLen[i], rayFar[i], rayStart[i])
            val head = focal / max(z, .12f)
            val tail = focal / (z + rayLen[i] * (1f + 2.5f * dk))
            if (tail > reach * 1.2f) continue
            val a = 2f * PI.toFloat() * ih(salt, 305) + spin
            val wd = .03f + .065f * ih(salt, 306)
            val g = RAY_GROUP[(ih(salt, 307) * 12f).toInt().coerceIn(0, 11)]
            groups[g].petalRay(vp.x, vp.y, a, wd, head, tail)
            rims[g].petalRay(vp.x, vp.y, a, wd, head, tail, rimGrow)
        }
        for (g in 0 until 6) {
            val p = groups[g]
            if (p.isEmpty) continue
            drawPath(rims[g], TunnelMist)
            // In the dash every ray turns pink; aqua turns white first (by 40 % of it), so no pink ever lies over aqua.
            val c = if (g == 0) lerpColor(GROUP_COLOR[0], Sakura.White, 2.5f * pink) else lerpColor(GROUP_COLOR[g], Sakura.Primary, .75f * pink)
            drawPath(p, c)
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

    /** The status discs flying past, each flipping CHECK → OK halfway. */
    fun drawDiscs(ds: DrawScope, ms: Float) = with(ds) {
        if (reduced || ms >= tl.hit) return@with
        for (i in 0 until tl.discs) {
            val k = (ms - tl.discAt(i)) / tl.discMs
            if (k < 0f || k >= 1f) continue
            // Constant speed towards the camera: perspective does the rest (small for long, then past in a moment).
            val z = lerp(6f, .45f, k)
            val s = 2f / z * IntroEase.CubicOut.transform((k / .1f).coerceAtMost(1f))
            val o = DISC_DIR[i]
            val at = Offset(vp.x + o.x * dp * (2f / z), vp.y + o.y * dp * (2f / z))
            val rot = lerp(-8f, 22f, k) * (if (i % 2 == 0) 1f else -1f)
            withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(s, s, Offset.Zero) }) { drawDisc(i, k, ms) }
        }
    }

    private fun DrawScope.drawDisc(i: Int, k: Float, ms: Float) {
        val r = discR
        withTransform({ scale(r * 1.35f, r * 1.35f, Offset.Zero) }) { drawCircle(mistBloom, 1f, Offset.Zero, alpha = .9f) }
        drawCircle(TunnelMist, r, Offset.Zero)
        drawCircle(Sakura.Primary, r - 1.2f * dp, Offset.Zero, style = discEdge)
        // Aqua only between mist: never under the ink.
        drawCircle(Sakura.Aqua, .93f * r, Offset.Zero, style = discAqua)
        withTransform({ rotate(ms * .08f, Offset.Zero) }) { drawCircle(Sakura.MilkTea, .82f * r, Offset.Zero, style = ringA[i]) }
        withTransform({ rotate(-ms * .12f, Offset.Zero) }) { drawCircle(Sakura.Primary, .72f * r, Offset.Zero, alpha = .8f, style = ringB[i]) }
        val label = discLabels[i]
        drawRun(label, -label.width / 2f, -.50f * r - 2f * dp, Sakura.TextSecondary, 1f)
        val value = discValues[i]
        drawRun(value, -value.width / 2f, -5f * dp, Sakura.Text, 1f)
        // The chip flips (squashes and reopens) from CHECK to OK at half the flight.
        val ok = k >= .5f
        val flip = if (k in .42f..<.58f) kotlin.math.abs(cos(PI.toFloat() * (k - .42f) / .16f)) else 1f
        val run = if (ok) chipOk else chipCheck
        val cw = max(chipOk.width + 22f * dp, chipCheck.width) + 16f * dp
        val ch = 18f * dp
        val cy = .50f * r
        withTransform({ scale(1f, flip.coerceAtLeast(.02f), Offset(0f, cy)) }) {
            drawRoundRect(TunnelMist, Offset(-cw / 2f, cy - ch / 2f), Size(cw, ch), CornerRadius(ch / 2f))
            drawRoundRect(if (ok) Sakura.Aqua else Sakura.Primary, Offset(-cw / 2f, cy - ch / 2f), Size(cw, ch), CornerRadius(ch / 2f), style = if (ok) chipAqua else chipPink)
            val tw = run.width + (if (ok) 14f * dp else 0f)
            val left = -tw / 2f
            // Squashed flat, the words would lie on the aqua edge (ink on aqua is grey): only the pill turns over.
            if (flip >= .5f) drawRun(run, left, cy - run.baseline + run.fontPx * .36f, if (ok) Sakura.Text else Sakura.TextSecondary, 1f)
            if (ok && flip >= .5f) {
                val x = left + run.width + 4f * dp
                okTick.reset()
                okTick.moveTo(x, cy)
                okTick.lineTo(x + 3.5f * dp, cy + 3.5f * dp)
                okTick.lineTo(x + 10f * dp, cy - 4f * dp)
                drawPath(okTick, Sakura.Primary, style = tickStroke)
            }
        }
    }

    /** The 登入 panel (the name types in, the code shows as dots), then the 確認 panel with 出發 lighting up. */
    fun drawPanels(ds: DrawScope, ms: Float) = with(ds) {
        if (reduced || ms < tl.loginAt || ms >= tl.hit) return@with
        val cx = w / 2f
        // 登入: wipes open from the middle; it folds away when 確認 arrives (or, short, when the dash starts).
        val loginOut = if (tl.compact) tl.dashAt else tl.confirmAt
        if (ms < loginOut + 130f * pace) {
            val top = cardCy - loginH / 2f
            panel(cx, top, loginH, seg(ms, tl.loginAt, 140f * pace, IntroEase.CubicOut), fold(ms, loginOut), tabLogin, tabLoginLatin) {
                val left = cx - pw / 2f
                drawRun(labelName, left + 18f * dp, top + row1Base - labelName.baseline, Sakura.TextSecondary, 1f)
                drawRun(labelCode, left + 18f * dp, top + row2Base - labelCode.baseline, Sakura.TextSecondary, 1f)
                val vx = left + valueX
                drawLine(Sakura.MilkTea, Offset(vx, top + row1Base + 6f * dp), Offset(left + pw - 18f * dp, top + row1Base + 6f * dp), 1.2f * dp)
                drawLine(Sakura.MilkTea, Offset(vx, top + row2Base + 6f * dp), Offset(left + pw - 18f * dp, top + row2Base + 6f * dp), 1.2f * dp)
                val step = tl.typeStep(nameRun.size)
                // Like a real field: a name too long for it (even at the smallest size) scrolls left as it types, the cursor in view.
                val fieldW = left + pw - 18f * dp - vx
                val typed = ((ms - tl.typeAt) / step).toInt().coerceIn(0, (nameRun.size - 1).coerceAtLeast(0))
                val scroll = if (nameRun.size == 0) 0f else (nameRun.x[typed] + nameRun.advance[typed] + .3f * nameRun.fontPx - fieldW).coerceAtLeast(0f)
                clipRect(vx - 2f * dp, top + 4f * dp, vx + fieldW, top + row1Base + 5f * dp) {
                    drawTyped(nameRun, vx - scroll, top + row1Base - nameRun.baseline, Sakura.Text, 1f, ms, tl.typeAt, step, hold = 0f)
                }
                // Six dots for the code, popping in one by one behind the name (nothing is read: it is only a look).
                val dotsAt = tl.typeAt + step * nameRun.size + 50f * pace
                var shown = 0
                for (j in 0 until 6) {
                    val dk = seg(ms, dotsAt + j * 30f * pace, 60f * pace)
                    if (dk <= 0f) break
                    shown = j + 1
                    drawCircle(Sakura.Primary, 4.5f * dp * lerp(1.5f, 1f, dk), Offset(vx + 6f * dp + j * 16f * dp, top + row2Base - 7f * dp))
                }
                val done = dotsAt + 6 * 30f * pace
                if (ms >= dotsAt && (ms < done || ((ms - done) % 320f) < 160f)) {
                    drawRect(Sakura.Primary, Offset(vx + shown * 16f * dp, top + row2Base - 16f * dp), Size(2.4f * dp, 17f * dp))
                }
            }
        }
        if (!tl.compact && ms >= tl.confirmAt + 60f * pace) {
            val top = cardCy - confirmH / 2f
            panel(cx, top, confirmH, seg(ms, tl.confirmAt + 60f * pace, 140f * pace, IntroEase.CubicOut), fold(ms, tl.dashAt), tabConfirm, tabConfirmLatin) {
                drawRun(confirmRun, cx - confirmRun.width / 2f, top + confirmTop, Sakura.Text, 1f)
                val pk = seg(ms, tl.pressAt, 90f * pace)
                val dip = 1f - .05f * sin(PI.toFloat() * seg(ms, tl.pressAt, 140f * pace))
                val bl = cx - buttonW / 2f
                val bt = top + buttonTop
                val ring = seg(ms, tl.pressAt, 320f * pace)
                if (ring > 0f && ring < 1f) {
                    val g = 14f * dp * IntroEase.CubicOut.transform(ring)
                    drawRoundRect(TunnelMist, Offset(bl - g, bt - g), Size(buttonW + 2f * g, buttonH + 2f * g), CornerRadius(buttonH / 2f + g), alpha = 1f - ring, style = glowRing)
                    drawRoundRect(Sakura.Primary, Offset(bl - g * .6f, bt - g * .6f), Size(buttonW + 1.2f * g, buttonH + 1.2f * g), CornerRadius(buttonH / 2f + g * .6f), alpha = .7f * (1f - ring), style = pinkRing)
                }
                withTransform({ scale(dip, dip, Offset(cx, bt + buttonH / 2f)) }) {
                    drawRoundRect(lerpColor(TunnelMist, Sakura.Primary, pk), Offset(bl, bt), Size(buttonW, buttonH), CornerRadius(buttonH / 2f))
                    drawRoundRect(Sakura.Primary, Offset(bl, bt), Size(buttonW, buttonH), CornerRadius(buttonH / 2f), style = cardEdge)
                    drawRun(goRun, cx - goRun.width / 2f, bt + (buttonH - goRun.height) / 2f, Sakura.Text, 1f)
                }
            }
        }
    }

    /** A panel's fold-away from [at]: flat into a line, then the line closes (an old screen turning off); no fade over the rays. */
    private fun fold(ms: Float, at: Float): Float = seg(ms, at, 130f * pace)

    /**
     * A card [pw] wide with its top at [top]: a pink hard shadow, an opaque mist face with a pink border and a pink tab with its
     * title on the top edge; [open] wipes it open from the middle, [folded] folds it away; [body] draws its content.
     */
    private inline fun DrawScope.panel(cx: Float, top: Float, height: Float, open: Float, folded: Float, tab: GlyphRun, tabLatin: GlyphRun, body: DrawScope.() -> Unit) {
        if (open <= 0f || folded >= 1f) return
        val sy = lerp(1f, .03f, seg(folded, 0f, .6f, IntroEase.CubicIn))
        val sx = 1f - seg(folded, .55f, .45f, IntroEase.CubicIn)
        if (sx <= 0f) return
        val left = cx - pw / 2f
        val half = (pw / 2f + 12f * dp) * open
        val cy = top + height / 2f
        withTransform({
            scale(sx * lerp(.96f, 1f, open), sy * lerp(.96f, 1f, open), Offset(cx, cy))
            clipRect(cx - half, top - 30f * dp, cx + half, top + height + 20f * dp)
        }) {
            val corner = CornerRadius(20f * dp)
            drawRoundRect(Sakura.Primary.copy(alpha = .35f), Offset(left + 5f * dp, top + 7f * dp), Size(pw, height), corner)
            drawRoundRect(TunnelMist, Offset(left, top), Size(pw, height), corner)
            drawRoundRect(Sakura.Primary, Offset(left, top), Size(pw, height), corner, style = cardEdge)
            val th = 30f * dp
            val tw = tab.width + tabLatin.width + 34f * dp
            val tl0 = left + 18f * dp
            val tt = top - th / 2f
            drawRoundRect(Sakura.Primary, Offset(tl0, tt), Size(tw, th), CornerRadius(th / 2f))
            drawRun(tab, tl0 + 14f * dp, tt + (th - tab.height) / 2f, Sakura.Text, 1f)
            drawRun(tabLatin, tl0 + 14f * dp + tab.width + 6f * dp, tt + (th - tab.height) / 2f + tab.baseline - tabLatin.baseline, Sakura.Text, 1f)
            body()
        }
    }

    /** The pink dash closing in, the white burst, its shock rings and star flare, and petals flung out of the core. */
    fun drawBurst(ds: DrawScope, ms: Float, amb: Float) = with(ds) {
        if (reduced) return@with
        val v = tl.vignette(ms)
        if (v > 0f) drawRect(vignetteBrush, alpha = v)
        drawSpeedLines(ms)
        val f = tl.flash(ms)
        if (f > 0f) drawRect(TunnelMist, alpha = f)
        if (ms < tl.hit - 40f * pace) return@with
        // Three shock rings, 110 ms apart.
        for (j in 0 until 3) {
            val k = seg(ms, tl.hit + j * 110f * pace, 560f * pace)
            if (k <= 0f || k >= 1f) continue
            val rr = reach * (.06f + 1.05f * (1f - (1f - k).pow(2.2f)))
            drawCircle(TunnelMist, rr, vp, alpha = .9f * (1f - k), style = Stroke((5f + 14f * k) * dp))
            drawCircle(Sakura.Primary, rr, vp, alpha = .75f * (1f - k), style = Stroke((1.5f + 2.5f * (1f - k)) * dp))
        }
        // The star flare: twelve blades, long mist and short pink, turning a little.
        val fa = seg(ms, tl.hit - 40f * pace, 60f * pace) * (1f - seg(ms, tl.hit + 120f * pace, 200f * pace))
        if (fa > 0f) {
            val k = seg(ms, tl.hit - 40f * pace, 360f * pace)
            flareMist.reset()
            flarePink.reset()
            for (i in 0 until 12) {
                val a = 2f * PI.toFloat() * i / 12f + .5f * k + (i % 2) * .13f
                val len = (if (i % 2 == 0) 60f + 200f * k else 30f + 90f * k) * dp
                (if (i % 2 == 0) flareMist else flarePink).blade(vp.x, vp.y, a, 6f * dp, len, (3.5f + 5f * k) * dp)
            }
            drawPath(flarePink, Sakura.Primary, alpha = fa)
            drawPath(flareMist, TunnelMist, alpha = fa)
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

    /** DSH MOBILE at the top left and the time at the top right, each on an opaque mist pill. */
    fun drawHud(ds: DrawScope, ms: Float) = with(ds) {
        val a = if (reduced) 1f else seg(ms, 120f * pace, 200f * pace)
        if (a <= 0f) return@with
        val top = insetTop + 12f * dp
        val ph = brand.height + 8f * dp
        val dot = 6f * dp
        val bw = brand.width + dot + 26f * dp
        drawRoundRect(TunnelMist, Offset(14f * dp, top), Size(bw, ph), CornerRadius(ph / 2f), alpha = a)
        drawCircle(Sakura.Primary, dot / 2f, Offset(14f * dp + 10f * dp + dot / 2f, top + ph / 2f), alpha = a)
        drawRun(brand, 14f * dp + 10f * dp + dot + 6f * dp, top + 4f * dp, Sakura.TextSecondary, a)
        val cw = clock.width + 20f * dp
        drawRoundRect(TunnelMist, Offset(w - 14f * dp - cw, top), Size(cw, ph), CornerRadius(ph / 2f), alpha = a)
        drawRun(clock, w - 14f * dp - cw + 10f * dp, top + 4f * dp + brand.baseline - clock.baseline, Sakura.TextSecondary, a)
    }

    /** 歡迎回來 rising over the picture, the name stamped in as a sticker, and "Welcome back" typed under it. */
    fun drawWelcome(ds: DrawScope, ms: Float) = with(ds) {
        if (!reduced && ms < tl.greetAt) return@with
        greet?.let { g ->
            val a = if (reduced) 1f else seg(ms, tl.greetAt, 260f * pace)
            val e = IntroEase.CubicOut.transform(a)
            drawRun(g, w / 2f - g.width / 2f, greetTop + 10f * dp * (1f - e), Sakura.Text, a)
        }
        drawSticker(sticker, w / 2f, stickerTop, ms, tl.nameAt, pace, pace < 1f, reduced, tl.exit)
        latin?.let { drawTyped(it, w / 2f - it.width / 2f, latinTop, Sakura.TextSecondary, 1f, ms, tl.nameAt + 160f * pace, 22f * pace, reduced = reduced) }
    }
}

/**
 * A light ray from the tunnel's centre ([cx], [cy]) along angle [a]: narrow at its [tail] radius, [w] radians wide at its [head]
 * radius, with a sakura petal's notched tip instead of the round head of a plain wedge. [grow] widens it by that much all round
 * (its rim).
 */
private fun Path.petalRay(cx: Float, cy: Float, a: Float, w: Float, head: Float, tail0: Float, grow: Float = 0f) {
    val dx = cos(a)
    val dy = sin(a)
    val nx = -dy
    val ny = dx
    val tail = (tail0 - grow).coerceAtLeast(0f)
    val cap = head * sin(w) + grow
    val narrow = tail0 * sin(w * .12f) + grow
    moveTo(cx + dx * tail - nx * narrow, cy + dy * tail - ny * narrow)
    lineTo(cx + dx * head - nx * cap, cy + dy * head - ny * cap)
    var r = head + .95f * cap
    var s = -.95f * cap
    val r2 = head + 1.05f * cap
    val s2 = -.30f * cap
    quadraticTo(cx + dx * r + nx * s, cy + dy * r + ny * s, cx + dx * r2 + nx * s2, cy + dy * r2 + ny * s2)
    r = head + .78f * cap
    lineTo(cx + dx * r, cy + dy * r)
    lineTo(cx + dx * r2 - nx * s2, cy + dy * r2 - ny * s2)
    r = head + .95f * cap
    s = .95f * cap
    quadraticTo(cx + dx * r + nx * s, cy + dy * r + ny * s, cx + dx * head + nx * cap, cy + dy * head + ny * cap)
    lineTo(cx + dx * tail + nx * narrow, cy + dy * tail + ny * narrow)
    close()
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
