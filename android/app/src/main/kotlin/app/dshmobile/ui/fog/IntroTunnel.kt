package app.dshmobile.ui.fog

// Adapted from Rearpoem/dsh-link-start (c) 2026 FHun, MIT License: the perspective ray tunnel (a ray's head at focal / z, rays
// sent round again far away once their tail has passed the camera, a speed that grows with u² from a non-zero start), the shock
// rings' radius curve (.06 + 1.05 (1 − (1 − k)^2.2)) and the status discs flying past the camera while turning. The colours,
// the light rays' gradients, the core, the panels, the words and the timing are our own; nothing of its name, script, images
// or sounds.

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Paint
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import app.dshmobile.R
import app.dshmobile.config.AgentModels
import app.dshmobile.config.AgentSettingsStore
import app.dshmobile.config.displayName
import app.dshmobile.gateway.Provider
import app.dshmobile.security.AndroidKeyVault
import app.dshmobile.system.PhantomState
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
 * rushes at the camera, depth rings racing outward; status discs pop out of the core one after another, each on its own lane,
 * hover to show the user's own values (the 模型 dsh leads with, 編號, today's date, 等級), flip to OK and fly past; a 登入 panel
 * types the name in, a 確認 panel lights 出發 and flies at the camera; the pink dash (the rays turn pink from the rim inward,
 * speed lines, the core swells), the core opens onto the picture and bursts white; the name stamps in as a sticker (歡迎回來)
 * and the sakura iris opens into the app.
 *
 * Fixed moves and stretching holds ([TunnelTimeline]): 8 s cruises longer, lets the panels be read, rushes longer and holds the
 * welcome, but nothing moves slower. Everything is a pure function of the run's clock, read only in draw lambdas, so a run
 * composes once. The layout comes from the space available ([TunnelLayout]): the panels never cover the core, in landscape they
 * stand beside it, and no two discs overlap. With reduced motion the host holds one still: the settled end of the welcome.
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
    val words = tunnelWords(intro)
    val tl = remember(total, words.values.size) { TunnelTimeline.of(total, words.values.size) }
    val timing = remember(tl) { IntroTiming.of(tl.plan, still = tl.still, hit = tl.hit, announce = tl.hit) }
    val line = introWelcomeLine(intro)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val coverage = IntroFonts.coverage(LocalContext.current.resources)
    val skippable = intro.skippable
    // No line for the host: its 跳過 label is set in the handwriting at 15 sp with the display thickening, where 跳 blurs into
    // 兆. This look draws its own, larger and with a lighter pen, and adds the same two screen-reader nodes ([TunnelNodes]).
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = null) {
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
        TunnelNodes(scope, timing, skippable, words.skip, line, scene.skipRect)
    }
}

/**
 * The screen-reader nodes the host adds for a look that passes it a line, here for this look's own 跳過 pill: the skip button
 * (with [skippable]) over the pill, and [line], the welcome, as the run's one live announcement from [IntroTiming.announce] (at
 * once for reduced motion). Composed once more when the announcement starts, never per frame.
 */
@Composable
private fun TunnelNodes(sc: IntroScope, timing: IntroTiming, skippable: Boolean, skip: String, line: String, pill: Rect) {
    val run = sc.run
    val shown by remember(timing) { derivedStateOf { sc.reduced || run.ms >= timing.announce } }
    val d = sc.density
    fun Modifier.at(r: Rect) = this
        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
        .size(with(d) { r.width.coerceAtLeast(1f).toDp() }, with(d) { r.height.coerceAtLeast(1f).toDp() })
    if (skippable) Box(Modifier.at(pill).semantics { text = AnnotatedString(skip); role = Role.Button })
    if (shown) {
        val dp = d.density
        Box(
            Modifier.at(Rect(24f * dp, sc.heightPx * .40f, sc.widthPx - 24f * dp, sc.heightPx * .40f + 80f * dp))
                .semantics { text = AnnotatedString(line); liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A run's words, resolved once (defaults filled in), so drawing never reads resources. */
internal class TunnelWords(
    /** The welcome words without trailing punctuation (the name follows them), e.g. 歡迎回來. */
    val greet: String,
    val greetIsDefault: Boolean,
    val name: String,
    /**
     * What each status disc shows, in flight order: its label and the real value. With the model readable: 模型 (the hero),
     * 編號, 日期, 等級; without it: 編號, 等級, 日期. The timeline flies as many as the run has room for.
     */
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
    /** 跳過, on this look's own pill. */
    val skip: String,
)

/** The welcome words as the eyebrow over the name: trailing punctuation dropped (the name follows), the default when blank. */
internal fun tunnelGreet(words: String, fallback: String): String =
    words.trim().trimEnd('，', ',', '!', '！', '。', '.', '、', '~', '～', ' ', '　').ifEmpty { fallback }

/** The longest model label a disc shows (graphemes); a longer one ends in …. */
internal const val MODEL_MAX = 16

/** [s] trimmed and cut to [max] graphemes with a trailing … when longer; null when blank. */
internal fun tunnelCap(s: String?, max: Int = MODEL_MAX): String? {
    val t = s?.trim().orEmpty()
    if (t.isEmpty()) return null
    val g = graphemes(t)
    return if (g.size <= max) t else g.take(max - 1).joinToString("").trimEnd() + "…"
}

/**
 * The model dsh leads with, as the chat shows it (resolved the way MainActivity does: a stored lead whose provider has no key
 * runs the default), or null when it cannot be read. Only app-private preferences are read: no key is decrypted.
 */
internal fun tunnelModelLabel(context: Context): String? = runCatching {
    val app = context.applicationContext ?: context
    val hasAnthropicKey = AndroidKeyVault(app).has(Provider.ANTHROPIC)
    // The lead model does not depend on the phantom-process state (only the concurrency defaults do).
    val stored = AgentSettingsStore(app.getSharedPreferences(AgentSettingsStore.PREFS, Context.MODE_PRIVATE)).load(PhantomState.UNKNOWN, hasAnthropicKey)
    tunnelCap(AgentModels.displayName(AgentModels.resolve(stored.leadModel, hasAnthropicKey).id))
}.getOrNull()

@Composable
private fun tunnelWords(intro: Intro): TunnelWords {
    val default = stringResource(R.string.intro_welcome_default)
    val number = intro.number.trim().ifEmpty { stringResource(R.string.intro_number_default) }
    val level = intro.level.trim().ifEmpty { stringResource(R.string.intro_level_default) }
    val lModel = stringResource(R.string.intro_tunnel_model)
    val lNumber = stringResource(R.string.intro_tunnel_number)
    val lLevel = stringResource(R.string.intro_tunnel_level)
    val lDate = stringResource(R.string.intro_tunnel_date)
    val login = stringResource(R.string.intro_tunnel_login)
    val loginName = stringResource(R.string.intro_tunnel_login_name)
    val loginCode = stringResource(R.string.intro_tunnel_login_code)
    val confirm = stringResource(R.string.intro_tunnel_confirm)
    val confirmLine = stringResource(R.string.intro_tunnel_confirm_line)
    val go = stringResource(R.string.intro_tunnel_go)
    val skip = stringResource(R.string.intro_skip)
    val context = LocalContext.current
    // Read once per run, at the start (small app-private preferences, already loaded by the time the app shows anything).
    val model = remember { tunnelModelLabel(context) }
    val now = remember { Date() }
    return remember(intro, default, number, level, model) {
        val name = intro.name.trim()
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(now)
        val discs = if (model != null) listOf(lModel to model, lNumber to number, lDate to date, lLevel to level)
            else listOf(lNumber to number, lLevel to level, lDate to date)
        TunnelWords(
            greet = tunnelGreet(intro.welcome, default), greetIsDefault = intro.welcome.isBlank(), name = name,
            labels = discs.map { it.first }, values = discs.map { it.second },
            login = login, loginName = loginName, loginCode = loginCode, loginValue = name.ifEmpty { number },
            confirm = confirm, confirmLine = confirmLine, go = go, time = SimpleDateFormat("HH:mm", Locale.ROOT).format(now), skip = skip,
        )
    }
}

/** [this] run with its handwriting glyphs thickened at most [em] (small labels: the display thickening ran strokes together). */
internal fun GlyphRun.pen(em: Float): GlyphRun =
    GlyphRun(text, glyphs, x, advance, width, height, baseline, fontPx, emoji, dy, FloatArray(size) { if (thick[it] > 0f) min(thick[it], em * fontPx) else 0f })

/** The pen for small handwriting in this look (20–24 sp): it reads at 1×, where the display thickening blurred 名字 to ゑ字. */
internal const val PEN_SMALL = .035f

/**
 * The beats of one run of [plan]'s total (1.5–8 s). The moves keep their length (they tighten together only when a short run
 * cannot fit them, [pace] < 1); the holds stretch: the tunnel cruises, the 登入 and 確認 panels wait to be read, the dash rushes
 * longer and the welcome holds. Below [COMPACT_BELOW] ms the 確認 panel is left out.
 *
 * The dash is the look's climax and has its own time: [dashAt] the 確認 panel flies at the camera and the rays start turning
 * pink from the rim inward ([DASH_MS] plus a stretching hold, the rush), [openAt] the core opens to the whole screen
 * ([OPEN_MS]), [hit] the white burst: over 300 ms at 3 s, at least 150 ms at 1.5 s.
 *
 * The status discs fly one after another out of the core ([discs] of them, as many as fit before the 登入 panel with the next
 * disc's words shown only once the one before last has gone, [DISC_APART]).
 *
 * The tunnel's speed is a curve of the clock ([speed]), integrated once into a table ([distance]), so a ray's depth at any
 * moment is a closed form and any frame can be drawn on its own.
 */
internal class TunnelTimeline private constructor(val plan: IntroPlan, val compact: Boolean, wanted: Int) {
    val total: Float get() = plan.total
    val pace: Float get() = plan.pace

    /** The spark: the white void lasts only [VOID_MS] (× pace), then the core lights and the rays fan out of it. */
    val tunnelAt: Float = plan.at(B_TUNNEL)
    /** The end of the cruise: the 登入 panel wipes in. */
    val loginAt: Float = plan.at(B_LOGIN)
    /** The 確認 panel wipes in (the dash's start when [compact]). */
    val confirmAt: Float = plan.at(B_CONFIRM)
    /** The dash: 出發 has lit, the panel flies at the camera, the rays turn pink and the speed lines come. */
    val dashAt: Float = plan.at(B_DASH)
    /** The end of the rush: the core opens to the whole screen. */
    val openAt: Float = plan.at(B_OPEN)
    /** The white burst: the haptic tick, the announcement and the hit's nudge. */
    val hit: Float = plan.at(B_BURST)
    val exit: Float = plan.at(B_EXIT)
    /** The reduced-motion still: the welcome at the end of its hold, fully settled. */
    val still: Float = exit - 1f

    val discMs: Float = DISC_MS * plan.pace
    private val discFirst = tunnelAt + 110f * plan.pace
    /** How many discs fly: as many as are wanted (the words have) and fit. */
    val discs: Int
    /** The time between two discs (0 with one). */
    val discStep: Float

    init {
        var n = wanted.coerceIn(1, 4)
        var step = 0f
        while (n > 1) {
            val s = (loginAt - discFirst - DISC_GONE * discMs) / (n - 1)
            if (s >= DISC_APART * discMs) {
                step = min(s, DISC_STEP_MAX)
                break
            }
            n--
        }
        discs = n
        discStep = step
    }

    fun discAt(i: Int): Float = discFirst + i * discStep

    /** The name starts typing into the 名字 field, at most 180 ms for the whole name. */
    val typeAt: Float = loginAt + 90f * plan.pace
    fun typeStep(chars: Int): Float = min(60f, 180f / chars.coerceAtLeast(1)) * plan.pace
    /** The code's six dots start right after the name. */
    fun dotsAt(chars: Int): Float = typeAt + typeStep(chars) * chars.coerceAtLeast(1) + 20f * plan.pace
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

    /** The dash's progress, eased in: slow, then everything at once (the speed, the tails). */
    fun dashK(ms: Float): Float = seg(ms, dashAt, hit - dashAt, IntroEase.CubicIn)

    /** The dash's plain progress: the rays turn pink from the rim inward and the flood comes in. */
    fun dashLinear(ms: Float): Float = seg(ms, dashAt, hit - dashAt)

    /** A panel leaving at [at] as one piece: 0..1 over [PANEL_OUT] ms × pace (it fades and flies at the camera). */
    fun panelOut(ms: Float, at: Float): Float = seg(ms, at, PANEL_OUT * plan.pace)

    /** The core's swell in the rush, 0..1: only once the 確認 panel has gone, so it never grows over it. */
    fun rushK(ms: Float): Float {
        val from = dashAt + PANEL_OUT * plan.pace
        return seg(ms, from, max(1f, openAt - from), IntroEase.SineInOut)
    }

    /** The core opening, 0..1: it covers the screen 30 % of the open before the burst (no edge of it shows at the flash). */
    fun openK(ms: Float): Float = seg(ms, openAt, .7f * (hit - openAt), IntroEase.CubicIn)

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
     * The tunnel's core, a white light: it lights with the spark, swells to [TunnelLayout.CORE_DP] by the dash, swells by
     * [RUSH_ZOOM] in the rush (once the panel is gone), then opens to [cover] (the whole screen) before the burst.
     */
    fun coreRadius(ms: Float, dp: Float, cover: Float): Float {
        if (ms >= hit) return cover
        val appear = seg(ms, tunnelAt - 20f * pace, 260f * pace, IntroEase.BackOut) * (TunnelLayout.CORE_DP - CORE_SWELL) * dp
        val swell = CORE_SWELL * dp * seg(ms, tunnelAt, dashAt - tunnelAt)
        val r = (appear + swell) * (1f + RUSH_ZOOM * rushK(ms))
        return lerp(r, cover, openK(ms))
    }

    /**
     * The welcome's light rays: full at the burst, settling to [settle] behind the name: faint over the user's picture, stronger
     * over the plain sky.
     */
    fun afterglow(ms: Float, settle: Float): Float =
        if (ms < hit) 0f else seg(ms, hit, 160f * pace) * lerp(1f, settle, seg(ms, hit + 80f * pace, 560f * pace, IntroEase.SineInOut))

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
        return min(CRUISE, START_SPEED + 16f * u * u)
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
        const val TUNNEL_MS = 880f
        const val LOGIN_MS = 360f
        const val CONFIRM_MS = 200f
        const val DASH_MS = 200f
        const val OPEN_MS = 110f
        const val BURST_MS = 400f
        const val WELCOME_MIN = 340f
        const val EXIT_MS = 380f
        const val DISC_MS = 480f
        /** A disc's face is gone by this much of its flight (its words by .776). */
        const val DISC_GONE = .84f
        /**
         * Discs at least this much of a flight apart: the one before last has gone before a disc's value shows (from .12, at
         * 80 dp across), so at most two discs ever show words, and their hovers ([DISC_HOVER_FROM]..[DISC_HOVER_TO]) never meet.
         */
        const val DISC_APART = .36f
        const val DISC_STEP_MAX = 480f
        const val PANEL_OUT = 120f
        const val RUSH_ZOOM = .3f
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
        const val B_RUSH = 8
        const val B_OPEN = 9
        const val B_BURST = 10
        const val B_WELCOME = 11
        const val B_EXIT = 12

        /** A run of [totalMs] flying up to [discs] status discs. */
        fun of(totalMs: Float, discs: Int = 4): TunnelTimeline {
            val compact = totalMs < COMPACT_BELOW
            val plan = IntroPlan.of(
                totalMs,
                IntroBeat.move(VOID_MS),
                IntroBeat.move(if (compact) TUNNEL_MS - 80f else TUNNEL_MS),
                IntroBeat.hold(1f),
                IntroBeat.move(LOGIN_MS),
                IntroBeat.hold(.5f),
                IntroBeat.move(if (compact) 0f else CONFIRM_MS),
                IntroBeat.hold(if (compact) 0f else .35f),
                IntroBeat.move(DASH_MS),
                IntroBeat.hold(.2f),
                IntroBeat.move(OPEN_MS),
                IntroBeat.move(BURST_MS),
                IntroBeat.hold(1.6f, WELCOME_MIN),
                IntroBeat.move(EXIT_MS),
            )
            return TunnelTimeline(plan, compact, discs)
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

/** One disc at one moment ([tunnelDiscPose] fills it; one object reused, so a frame allocates nothing). */
internal class DiscPose {
    var x = 0f
    var y = 0f
    /** The scale of the unit disc ([TunnelLayout.discR] across its radius) on screen. */
    var scale = 0f
    /** Its radius on screen (px). */
    var radius = 0f
    var rot = 0f
    var k = 0f
    var faceA = 0f
    /** The words' strength: gone first as it flies off. */
    var textA = 0f
    /** The handwritten header: only once the disc is about 120 dp across (smaller, the pen blurred). */
    var labelA = 0f
    /** The value and the chip: from about 90 dp across. */
    var valueA = 0f
    val hovering: Boolean get() = k in DISC_HOVER_FROM..DISC_HOVER_TO
}

/**
 * Disc [i] at [ms] (false when it is not on screen): it pops out of the core, flies out along its own lane to its hover spot
 * ([TunnelLayout.discs]), hovers at its readable size ([tunnelDiscDepth]) and flies on past the camera, its words fading first.
 */
internal fun tunnelDiscPose(lay: TunnelLayout, tl: TunnelTimeline, i: Int, ms: Float, dp: Float, out: DiscPose): Boolean {
    if (i >= tl.discs || i >= lay.discs.size) return false
    val k = (ms - tl.discAt(i)) / tl.discMs
    if (k < 0f || k >= 1f) return false
    val o = seg(k, DISC_HOVER_TO, 1f - DISC_HOVER_TO)
    val face = 1f - seg(o, .15f, .25f)
    if (face <= 0f) return false
    val f = 2f / tunnelDiscDepth(k)
    val s = f * lay.discScale * TunnelLayout.discSize(i) * IntroEase.CubicOut.transform((k / .08f).coerceAtMost(1f))
    val p = lay.discs[i]
    out.x = lay.vp.x + (p.x - lay.vp.x) * f
    out.y = lay.vp.y + (p.y - lay.vp.y) * f
    out.scale = s
    out.radius = lay.discR * s
    out.k = k
    out.faceA = face
    out.textA = 1f - seg(o, 0f, .2f)
    val across = 2f * out.radius / dp
    out.labelA = out.textA * smoothstep((across - 112f) / 16f)
    out.valueA = out.textA * smoothstep((across - 80f) / 16f)
    out.rot = (lerp(-10f, 4f, seg(k, 0f, DISC_HOVER_TO, IntroEase.SineInOut)) + 16f * o) * (if (i % 2 == 0) 1f else -1f)
    return true
}

/** How many things a run draws, by effect level ([introCount] for the user's 數量; the rays are the look itself, fixed). */
internal class TunnelCounts(val rays: Int, val petals: Int, val burst: Int, val drift: Int, val season: Int, val lines: Int) {
    companion object {
        fun of(level: EffectLevel, fx: IntroFx, reduced: Boolean): TunnelCounts = TunnelCounts(
            rays = if (reduced) 0 else when (level) { EffectLevel.FULL -> 110; EffectLevel.BALANCED -> 76; EffectLevel.SAVER -> 40 },
            petals = if (reduced) 0 else introCount(level, fx, 26, 16, 8),
            burst = if (reduced) 0 else introCount(level, fx, 24, 16, 8),
            drift = introCount(level, fx, 12, 8, 5, reduced),
            season = if (tunnelSeason(fx) == IntroSeason.NONE) 0 else introCount(level, fx, 16, 12, 6, reduced),
            lines = if (reduced) 0 else when (level) { EffectLevel.FULL -> 36; EffectLevel.BALANCED -> 24; EffectLevel.SAVER -> 14 },
        )
    }
}

/** The season this run shows: sakura only with petals on (as the shared season effect does). */
internal fun tunnelSeason(fx: IntroFx): IntroSeason = if (fx.season == IntroSeason.SAKURA && !fx.petals) IntroSeason.NONE else fx.season

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
/** The void's spark: three little arcs (aqua only ever over the white room). */
private val SPARK = arrayOf(Sakura.Primary, Sakura.MilkTea, Sakura.Aqua)
private const val DEG = 180f / PI.toFloat()

private fun clampF(v: Float, lo: Float, hi: Float): Float = if (lo > hi) (lo + hi) / 2f else v.coerceIn(lo, hi)
private fun smoothstep(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
/** The angle between two directions (degrees), 0..180. */
private fun angleBetween(a: Float, b: Float): Float = abs((((a - b) % 360f) + 540f) % 360f - 180f)

/**
 * Where everything stands, from the space available (pure, so a test can check every screen). The core (the vanishing point
 * [vp], [coreR] at its largest before the dash) is never covered: the panels' top sits [GAP_DP] under it, and when they do not
 * fit below it the core moves up (to .34 H, or higher if it must); in landscape the panels stand beside it instead.
 *
 * Each status disc gets its own lane ([discs], its hover spot): spread round the core (±35° and ±145° in portrait, near the
 * horizontal in landscape), fully on screen [EDGE_DP] inside the edges and between the chrome bands, clear of the core, a disc
 * at least 90° round from the one before it, and never over another disc on screen at the same time (with [DISC_GAP_DP] between
 * two hovering at once). The first disc (the 模型) is the largest ([HERO]). Where they cannot all fit, all shrink together.
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
    /** Where each status disc's centre is when it hovers. */
    val discs: Array<Offset>,
    /** The unit disc's radius (scale 1). */
    val discR: Float,
    /** 1, or less only where the discs cannot all fit apart (a small or short screen). */
    val discScale: Float,
    private val dp: Float,
) {
    val coreBox: Rect get() = Rect(vp, coreR)
    /** The panels' whole box: the tab above the card, the card, and its hard shadow. */
    val panelBox: Rect get() = Rect(panelCx - pw / 2f, cardTop - TAB_DP * dp, panelCx + pw / 2f + SHADOW_DP * dp, cardTop + cardH + SHADOW_DP * dp)
    /** Disc [i]'s radius at the largest of its hover. */
    fun discRadius(i: Int): Float = discR * DISC_PEAK * discScale * discSize(i)
    fun discBox(i: Int): Rect = Rect(discs[i], discRadius(i))

    companion object {
        /** The core's radius at its largest before the dash (dp). */
        const val CORE_DP = 44f
        const val GAP_DP = 20f
        const val TAB_DP = 15f
        const val SHADOW_DP = 7f
        /** A disc's radius at scale 1: 144 dp across. */
        const val DISC_DP = 72f
        /** The hover's largest scale (z 1.86). */
        const val DISC_PEAK = 2f / 1.86f
        /** The first disc (the 模型) is this much larger than the others. */
        const val HERO = 1.15f
        /** The top chrome band (DSH MOBILE, the clock) and the bottom one (跳過 SKIP), below and above the insets. */
        const val TOP_DP = 64f
        const val BOTTOM_DP = 72f
        const val EDGE_DP = 16f
        /** The least gap between two discs hovering at once. */
        const val DISC_GAP_DP = 12f
        // Each lane's preferred direction (degrees, counter-clockwise from the right, y up), in flight order: the next disc
        // always on the other side. In landscape the screen is too short for steep lanes: they lie near the horizontal.
        private val PORTRAIT_ANGLES = floatArrayOf(35f, -145f, 145f, -35f)
        private val WIDE_ANGLES = floatArrayOf(12f, -168f, -12f, 168f)

        fun discSize(i: Int): Float = if (i == 0) HERO else 1f

        /** The layout for a [w] × [h] px screen; [tl] tells which discs are on screen together (null: assume all of them). */
        fun of(w: Float, h: Float, dp: Float, insetTop: Float, insetBottom: Float, cardH: Float, tl: TunnelTimeline? = null): TunnelLayout {
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
            val n = tl?.discs ?: 4
            fun span(i: Int, from: Float, to: Float) = tl?.let { it.discAt(i) + from * it.discMs to it.discAt(i) + to * it.discMs } ?: (0f to 1f)
            fun meet(a: Pair<Float, Float>, b: Pair<Float, Float>) = a.first < b.second && b.first < a.second
            val together = Array(n) { i -> BooleanArray(n) { j -> meet(span(i, 0f, TunnelTimeline.DISC_GONE), span(j, 0f, TunnelTimeline.DISC_GONE)) } }
            val hovering = Array(n) { i -> BooleanArray(n) { j -> meet(span(i, DISC_HOVER_FROM, DISC_HOVER_TO), span(j, DISC_HOVER_FROM, DISC_HOVER_TO)) } }
            // The hero must fit between the chrome bands at all.
            var scale = min(1f, min(bottomLimit - topBand - 2f * edge, w - 2f * edge) / (2f * r * DISC_PEAK * HERO)).coerceAtLeast(.5f)
            var spots: Array<Offset>? = null
            while (true) {
                spots = placeDiscs(n, vp, coreR, wide, dp, r * DISC_PEAK * scale, w, topBand, bottomLimit, together, hovering)
                if (spots != null || scale <= .5f) break
                scale = max(.5f, scale * .93f)
            }
            val discs = spots ?: Array(n) { i ->
                // Nothing fits even at half size (no real phone): the lanes clamped into the screen.
                val rr = r * DISC_PEAK * scale * discSize(i)
                val a = (if (wide) WIDE_ANGLES else PORTRAIT_ANGLES)[i] / DEG
                val d = coreR + rr + DISC_GAP_DP * dp
                Offset(clampF(vp.x + cos(a) * d, edge + rr, w - edge - rr), clampF(vp.y - sin(a) * d, topBand + edge + rr, bottomLimit - edge - rr))
            }
            return TunnelLayout(vp, coreR, wide, pw, panelCx, cardTop, cardH, discs, r, scale, dp)
        }

        /**
         * The discs' hover spots, in flight order, for discs [rPk] in radius at their largest (× [discSize]), or null when one
         * cannot be placed: for each lane the spot nearest its preferred direction and a distance just clear of the core that is
         * on screen, 90° or more round from the previous disc and clear of every disc on screen with it.
         */
        private fun placeDiscs(
            n: Int, vp: Offset, coreR: Float, wide: Boolean, dp: Float, rPk: Float, w: Float, topBand: Float, bottomLimit: Float,
            together: Array<BooleanArray>, hovering: Array<BooleanArray>,
        ): Array<Offset>? {
            val pref = if (wide) WIDE_ANGLES else PORTRAIT_ANGLES
            val edge = EDGE_DP * dp
            val out = Array(n) { Offset.Zero }
            val ang = FloatArray(n)
            val rad = FloatArray(n)
            for (i in 0 until n) {
                val r = rPk * discSize(i)
                rad[i] = r
                val x0 = edge + r
                val x1 = w - edge - r
                val y0 = topBand + edge + r
                val y1 = bottomLimit - edge - r
                if (x0 > x1 || y0 > y1) return null
                val minD = coreR + r + DISC_GAP_DP * dp
                val prefD = minD + 16f * dp
                var best: Offset? = null
                var bestA = 0f
                var bestScore = Float.MAX_VALUE
                for (step in 0..90) {
                    val dev = step * 2f
                    if (dev > bestScore) break
                    for (sg in 0..1) {
                        if (step == 0 && sg == 1) continue
                        val a = pref[i % pref.size] + (if (sg == 0) dev else -dev)
                        if (i > 0 && angleBetween(a, ang[i - 1]) < 90f) continue
                        val c = cos(a / DEG)
                        val s = -sin(a / DEG)
                        for (q in 0..40) {
                            val d = minD + q * 8f * dp
                            val p = Offset(vp.x + c * d, vp.y + s * d)
                            if (p.x < x0 || p.x > x1 || p.y < y0 || p.y > y1) continue
                            var clear = true
                            for (m in 0 until i) {
                                if (!together[i][m]) continue
                                val need = r + rad[m] + if (hovering[i][m]) DISC_GAP_DP * dp else 0f
                                if ((p - out[m]).getDistance() < need) { clear = false; break }
                            }
                            if (!clear) continue
                            val score = dev + .25f * abs(d - prefD) / dp
                            if (score < bestScore) { bestScore = score; best = p; bestA = a }
                        }
                    }
                }
                out[i] = best ?: return null
                ang[i] = bestA
            }
            return out
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
        half = max(.8f * dp, hd * sin(wa) * (1f - .45f * smoothstep(hd / reach)))
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
 * What types into the 名字 field: [run] laid out at [x] (glyph lefts from the field's left), with a character the handwriting
 * lacks (桜, ひなた) set at [scale] .92 so it does not stand taller and heavier than the pen beside it. [width] is the whole
 * line, never wider than the field: a name too long even at the floor size ends in … ([tunnelField]).
 */
internal class TunnelField(val run: GlyphRun, val x: FloatArray, val scale: FloatArray, val width: Float)

/** The fallback characters' size next to the handwriting (the system font stands taller than the pen). */
internal const val FALLBACK_SCALE = .92f
/** The field's name: from 28 sp down to this, then ellipsized (below 15 sp the handwriting blurs). */
internal const val FIELD_FLOOR = 15f

private fun fieldOf(run: GlyphRun, hand: Boolean): TunnelField {
    val n = run.size
    val track = if (n > 1) run.x[1] - run.x[0] - run.advance[0] else 0f
    val scale = FloatArray(n) { if (hand && run.thick[it] <= 0f && !run.emoji[it] && run.text[it].isNotBlank()) FALLBACK_SCALE else 1f }
    val x = FloatArray(n)
    var at = 0f
    for (i in 0 until n) { x[i] = at; at += run.advance[i] * scale[i] + track }
    return TunnelField(run, x, scale, if (n == 0) 0f else at - track)
}

/**
 * [text] fitted to a field [maxW] wide: the name in the handwriting from 28 sp down to [FIELD_FLOOR], then cut with … (never
 * scrolled: its first character always shows); a 編號 (no name) in Quicksand. The pen is lighter than the display's.
 */
internal fun tunnelField(mm: IntroMeasure, text: String, latin: Boolean, maxW: Float): TunnelField {
    if (latin) return fieldOf(mm.fit(text, IntroFonts.latin(18f, FontWeight.Bold), .06f, 18f, 12f, maxW), false)
    var f = fieldOf(mm.fit(text, IntroFonts.hand(28f), .04f, 28f, FIELD_FLOOR, maxW).pen(.045f), true)
    if (f.width <= maxW) return f
    val g = graphemes(text)
    for (n in g.size - 1 downTo 1) {
        f = fieldOf(mm.run(g.take(n).joinToString("").trimEnd() + "…", IntroFonts.hand(FIELD_FLOOR), .04f).pen(.045f), true)
        if (f.width <= maxW) return f
    }
    return f
}

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
    private val seasonKind = tunnelSeason(sc.fx)
    /**
     * Full-screen layers reach this far past every edge: the host nudges the whole screen 2 dp at the burst, and a layer drawn to
     * the edge left a seam of what is underneath along the top.
     */
    private val ov = 4f * dp
    private val fullAt = Offset(-ov, -ov)
    private val fullSize = Size(w + 2f * ov, h + 2f * ov)

    // ---- the panels' contents first: the layout needs their height ----

    private val pad = 20f * dp
    private val buttonH = 44f * dp

    /** One width's panel contents: the 登入 rows and the 確認 line and button. */
    private inner class Cards(val pw: Float) {
        val labelName = mm.run(words.loginName, IntroFonts.hand(21f), .04f).pen(PEN_SMALL)
        val labelCode = mm.run(words.loginCode, IntroFonts.hand(21f), .04f).pen(PEN_SMALL)
        val valueX = 18f * dp + max(labelName.width, labelCode.width) + 14f * dp
        val fieldW = pw - valueX - 18f * dp
        val field = tunnelField(mm, words.loginValue, words.name.isEmpty(), fieldW)
        val row1Base = pad + 8f * dp + max(field.run.baseline, labelName.baseline)
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
    val lay = TunnelLayout.of(w, h, dp, insetTop, insetBottom, max(guess.loginH, guess.confirmH), tl)
    private val cards = if (lay.pw == guess.pw) guess else Cards(lay.pw)

    /** The 名字 field's value and the field's left and width (px), for tests. */
    internal val nameField: TunnelField get() = cards.field
    internal val fieldLeft: Float get() = lay.panelCx - cards.pw / 2f + cards.valueX
    internal val fieldWidth: Float get() = cards.fieldW

    /** The vanishing point: the core of the tunnel. */
    val vp = lay.vp
    private val coreMax = lay.coreR
    private val reach = hypot(w, h) / 2f
    private val focal = reach * .2f
    /** A circle round [vp] this large covers the whole screen. */
    private val cover = maxOf(hypot(vp.x, vp.y), hypot(w - vp.x, vp.y), hypot(vp.x, h - vp.y), hypot(w - vp.x, h - vp.y)) + 2f * ov

    private val rays = TunnelRays(tl, counts.rays, focal, reach, coreMax, dp)
    private val drift = IntroDrift(w, h, dp, counts.drift, sc.fx, salt = 40)
    private val petal = Kawaii.petalPath()
    private val sparkle = Kawaii.sparklePath()
    private val crest = sakuraSilhouette()

    // ---- the picture: in the core, then the stage ----

    private val iw = picture?.width?.toFloat()?.coerceAtLeast(1f) ?: 1f
    private val ih = picture?.height?.toFloat()?.coerceAtLeast(1f) ?: 1f
    /** Where the home screen's crop holds the picture (the 對齊 setting): at the exit the stage matches it exactly. */
    private val fy = (1f + sc.align.bias) / 2f
    /** The avatar square (調整頭像, or the app's guess): what the core shows, not the middle of a full-body picture. */
    private val faceSquare = avatarSquare(iw.roundToInt(), ih.roundToInt(), sc.avatar)
    private val faceFx = faceSquare.centerX(iw.roundToInt())
    private val faceFy = faceSquare.centerY(ih.roundToInt())
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
    private val cardClip = Path().apply { if (card) addRoundRect(RoundRect(cardRect, CornerRadius(CARD_CORNER * dp))) }
    private val openClip = Path()
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
    /** The portrait in the core: the avatar square across the core's [portraitR]. */
    private val portraitR = .92f * coreMax
    private val facePlace = Placement().also { p ->
        p.k = 2f * portraitR / faceSquare.side
        p.ox = vp.x - portraitR - faceSquare.left * p.k
        p.oy = vp.y - portraitR - faceSquare.top * p.k
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
    /**
     * An aqua ray's white sheath, opaque from a quarter of the way out to its tip: the aqua inside it (narrower, shorter, and
     * clear for its first quarter) only ever lies on white. Aqua blended straight over the pink field came out grey-lavender.
     */
    private val sheathBrush = Brush.horizontalGradient(
        0f to Sakura.White.copy(alpha = 0f), .14f to Sakura.White.copy(alpha = .6f), .24f to Sakura.White, 1f to Sakura.White,
        startX = 0f, endX = 1f,
    )
    private val aquaBrush = Brush.horizontalGradient(
        0f to Sakura.Aqua.copy(alpha = 0f), .26f to Sakura.Aqua.copy(alpha = 0f), .6f to Sakura.Aqua, .86f to Sakura.Aqua.copy(alpha = .92f),
        1f to Sakura.Aqua.copy(alpha = .2f), startX = 0f, endX = 1f,
    )
    /** The faint white line down a ray's middle, added on (it only ever lightens). */
    private val lineBrush = Brush.horizontalGradient(
        0f to Sakura.White.copy(alpha = 0f), .35f to Sakura.White.copy(alpha = 0f), .78f to Sakura.White.copy(alpha = .6f),
        1f to Sakura.White.copy(alpha = 0f), startX = 0f, endX = 1f,
    )
    private val rayPath = Path()
    private val linePath = Path()
    /** A snowflake's body (radius 1): white at the heart, #A5DEE4 at the rim. */
    private val flakeBrush = Brush.radialGradient(0f to Sakura.White, .35f to Sakura.White, 1f to Sakura.Aqua, center = Offset.Zero, radius = 1f)

    // ---- the discs (drawn as a unit disc of radius [discR], scaled per disc) ----

    private val discR = lay.discR
    private val discLabels = Array(tl.discs) { mm.run(words.labels[it], IntroFonts.hand(20f), .08f).pen(PEN_SMALL) }
    /** Each disc's value: one line, or two (a model name such as two words) where one would be smaller than 15 sp. */
    private val discValues: Array<Array<GlyphRun>> = Array(tl.discs) { discValue(words.values[it]) }
    private val chipCheck = mm.run("CHECK", IntroFonts.latin(10.5f, FontWeight.Bold), .14f)
    private val chipOk = mm.run("OK", IntroFonts.latin(12f, FontWeight.Bold), .12f)
    private val ringA = Array(tl.discs) { i ->
        Stroke(3.5f * dp, pathEffect = PathEffect.dashPathEffect(FloatArray(6) { k -> (if (k % 2 == 0) 5f + 26f * ih(i * 6 + k, 511) else 6f + 7f * ih(i * 6 + k, 512)) * dp }, 0f))
    }
    private val ringB = Array(tl.discs) { i ->
        Stroke(1.8f * dp, pathEffect = PathEffect.dashPathEffect(FloatArray(4) { k -> (if (k % 2 == 0) 2f + 12f * ih(i * 4 + k, 513) else 4f + 5f * ih(i * 4 + k, 514)) * dp }, 0f))
    }
    private val okTick = Path()
    private val pose = DiscPose()

    private fun discValue(text: String): Array<GlyphRun> {
        val style = IntroFonts.latin(20f, FontWeight.Bold)
        val maxW = 1.5f * discR
        val one = mm.fit(text, style, .04f, 20f, 15f, maxW)
        val cut = text.indices.filter { text[it] == ' ' }.minByOrNull { abs(it - text.length / 2) }
        if (one.width <= maxW || cut == null) return arrayOf(if (one.width <= maxW) one else mm.fit(text, style, .04f, 15f, 10f, maxW))
        val a = text.substring(0, cut).trim()
        val b = text.substring(cut + 1).trim()
        val la = mm.fit(a, style, .04f, 19f, 11f, 1.32f * discR)
        val lb = mm.fit(b, style, .04f, 19f, 11f, 1.32f * discR)
        val sp = min(la.fontPx, lb.fontPx) / mm.pxPerSp
        return arrayOf(mm.run(a, style, .04f, sp), mm.run(b, style, .04f, sp))
    }

    // ---- the panels ----

    private val tabLogin = mm.run(words.login, IntroFonts.hand(19f), .06f).pen(.045f)
    private val tabLoginLatin = mm.run("SIGN IN", IntroFonts.latin(10f, FontWeight.Bold), .22f)
    private val tabConfirm = mm.run(words.confirm, IntroFonts.hand(19f), .06f).pen(.045f)
    private val tabConfirmLatin = mm.run("CONFIRM", IntroFonts.latin(10f, FontWeight.Bold), .22f)
    private val panelShadow = lerpColor(TunnelMist, Sakura.Primary, .35f)
    /** A leaving panel is composed in a layer with this paint's alpha, so it fades as one piece (no empty shell left). */
    private val layerPaint = Paint()

    // ---- the HUD and the welcome ----

    private val brand = mm.run(IntroLatin.BRAND, IntroFonts.latin(11f, FontWeight.Bold), .28f)
    private val clock = mm.run(words.time, IntroFonts.latin(11f, FontWeight.Bold), .12f)
    private val skipCjk = mm.run(words.skip, IntroFonts.hand(20f), .04f).pen(PEN_SMALL)
    private val skipLatin = mm.run(IntroLatin.SKIP, IntroFonts.latin(11f, FontWeight.SemiBold), .2f)
    /** This look's 跳過 SKIP ›› pill at the bottom right (where the host draws its own), opaque mist under handwriting. */
    val skipRect: Rect = run {
        val pillW = 12f * dp + skipCjk.width + 6f * dp + skipLatin.width + 6f * dp + 12f * dp + 12f * dp
        val pillH = max(skipCjk.height, 26f * dp) + 4f * dp
        val right = w - 10f * dp
        val bottom = h - insetBottom - 14f * dp
        Rect(right - pillW, bottom - pillH, right, bottom)
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
    /** The welcome's words with their outline and a margin: the season over the veil keeps out of it. */
    private val wordsBox: Rect = run {
        val tw = maxOf(greet?.width ?: 0f, sticker.lines.maxOfOrNull { it.width } ?: 0f, latin?.width ?: 0f) + 24f * dp
        Rect(welcomeX - tw / 2f - 12f * dp, blockTop - 12f * dp, welcomeX + tw / 2f + 12f * dp, blockTop + blockH + 12f * dp)
    }
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
            drawStage(ms)
            return@with
        }
        drawRect(room, fullAt, fullSize)
        drawDepthRings(ms)
        drawRays(ms, aqua = false)
        drawTunnelPetals(ms, amb)
        drawSeasonRide(ms, amb)
        // Aqua last, each in its white sheath: nothing translucent ever lies over it, and it never lies on pink.
        drawRays(ms, aqua = true)
        drawCore(ms, amb, r)
        drawSpark(ms)
    }

    private fun zoomAt(ms: Float): Float = if (reduced) 1f else lerp(1.12f, 1f, seg(ms, 0f, tl.exit, IntroEase.SineInOut))

    private fun DrawScope.drawPicture(p: Placement, alpha: Float = 1f) {
        val pic = picture ?: return
        withTransform({ translate(p.ox, p.oy); scale(p.k, p.k, Offset.Zero) }) { drawImage(pic, alpha = alpha) }
    }

    /**
     * The picture filling the screen at [p], its top and bottom rows stretched [ov] past the edges where the crop ends there:
     * the burst's nudge moves the whole screen by 2 dp, and an edge of the picture left a seam.
     */
    private fun DrawScope.drawPictureFull(p: Placement) {
        val pic = picture ?: return
        drawPicture(p)
        val left = max(p.ox, -ov)
        val right = min(p.ox + iw * p.k, w + ov)
        if (right - left < 1f) return
        val sx = ((left - p.ox) / p.k).toInt().coerceIn(0, pic.width - 1)
        val sw = ((right - left) / p.k).roundToInt().coerceIn(1, pic.width - sx)
        val top = p.oy
        if (top > -ov) drawImage(pic, IntOffset(sx, 0), IntSize(sw, 1), IntOffset(left.roundToInt(), (-ov).roundToInt()), IntSize((right - left).roundToInt(), (top + ov).roundToInt() + 1))
        val bottom = p.oy + ih * p.k
        if (bottom < h + ov) drawImage(pic, IntOffset(sx, pic.height - 1), IntSize(sw, 1), IntOffset(left.roundToInt(), bottom.roundToInt() - 1), IntSize((right - left).roundToInt(), (h + ov - bottom).roundToInt() + 2))
    }

    private fun DrawScope.drawBlurFill(alpha: Float) {
        val b = blurred
        if (b == null) drawRect(sky, fullAt, fullSize, alpha = alpha)
        else drawImage(b, blurSrc.first, blurSrc.second, IntOffset((-ov).roundToInt(), (-ov).roundToInt()), IntSize(fullSize.width.roundToInt() + 1, fullSize.height.roundToInt() + 1), alpha = alpha, filterQuality = FilterQuality.Low)
        drawRect(Sakura.Background, fullAt, fullSize, alpha = .42f * alpha)
    }

    /**
     * The welcome's ground: the picture (whole, or as a card), or the blush sky; its light; petals and the season; the veil for
     * the words; with no picture, the sakura crest settled into the core above them. The reduced-motion still is exactly this at
     * the end of the welcome's hold.
     */
    private fun DrawScope.drawStage(ms: Float) {
        val t = if (reduced) tl.still else ms
        // The stage's own slow turns run on the run's clock, not the ambient one (frozen for reduced motion): so the still is
        // exactly the motion's last frame.
        val turn = t / 1000f
        when {
            picture == null -> drawRect(sky, fullAt, fullSize)
            card -> drawBlurFill(1f)
            else -> {
                cover(iw, ih, w, h, zoomAt(t), .5f, fy, .5f * w, fy * h, place)
                drawPictureFull(place)
            }
        }
        val g = tl.afterglow(t, if (picture != null && !card) .25f else .6f)
        if (g > 0f) {
            withTransform({ translate(vp.x, vp.y); rotate(6f * turn, Offset.Zero) }) {
                // Over the plain sky the light is a sunburst: mist rays with pink ones between them, round a soft white core.
                if (picture == null) withTransform({ rotate(360f / 28f, Offset.Zero) }) { drawPath(glow, glowPink, alpha = g) }
                drawPath(glow, glowBrush, alpha = g)
            }
            if (picture == null) withTransform({ translate(vp.x, vp.y); scale(110f * dp, 110f * dp, Offset.Zero) }) { drawCircle(mistBloom, 1f, Offset.Zero, alpha = .9f * g) }
        }
        if (card) drawCard(t)
        // Petals under the veil: behind the words they are muted.
        drift.draw(this, turn, if (reduced) 1f else seg(t, tl.hit + 100f * pace, 400f * pace))
        drawRect(scrim, fullAt, fullSize, alpha = if (reduced) 1f else seg(t, tl.hit, 300f * pace))
        // The season over the veil (under it a snowflake's white turned blush and its aqua rim a grey ring, a bubble), fading
        // out round the words so it never lies under the ink.
        drawSeasonFall(turn, if (reduced) 1f else seg(t, tl.hit, 400f * pace))
        if (picture == null) drawSettledCrest(t, turn)
    }

    /**
     * With no picture, the core's light and the sakura crest settle back where the vanishing point was (a hero above the
     * eyebrow, not an empty glow): from 140 ms after the burst, with a little overshoot.
     */
    private fun DrawScope.drawSettledCrest(t: Float, amb: Float) {
        val c = seg(t, tl.hit + 140f * pace, 320f * pace)
        if (c <= 0f) return
        val r = coreMax * lerp(.5f, 1f, IntroEase.BackOut.transform(c))
        drawLight(r, .85f * min(1f, 2f * c), amb, .9f * min(1f, 2f * c))
    }

    /** The picture standing whole as a card (white border, pink hard shadow), settling from a little larger after the burst. */
    private fun DrawScope.drawCard(ms: Float) {
        val k = if (reduced) 1f else lerp(1.05f, 1f, seg(ms, tl.hit, 500f * pace, IntroEase.CubicOut))
        val corner = CornerRadius(CARD_CORNER * dp)
        withTransform({ scale(k, k, cardRect.center) }) {
            drawRoundRect(Sakura.Primary.copy(alpha = .45f), cardRect.topLeft + Offset(6f * dp, 8f * dp), cardRect.size, corner)
            val b = 4f * dp
            drawRoundRect(Sakura.White, cardRect.topLeft - Offset(b, b), Size(cardRect.width + 2f * b, cardRect.height + 2f * b), CornerRadius(CARD_CORNER * dp + b))
            clipPath(cardClip) { drawPicture(cardPlace) }
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
     * in it during the cruise (the face, feathered into the white). It swells in the rush, then opens to the screen onto the
     * picture (or the sky), the portrait travelling to its place on the stage; on a wide screen with a tall picture the picture
     * is the card already, rounded and white-edged, inside the opening circle.
     */
    private fun DrawScope.drawCore(ms: Float, amb: Float, r: Float) {
        if (r <= 0f) return
        val kd = tl.openK(ms)
        val pk = if (picture == null) 0f else tl.portraitK(ms)
        drawLight(r, 0f, amb, 0f)
        if (ms >= tl.openAt) {
            val clip = r * lerp(.92f, 1f, kd)
            corePath.reset()
            corePath.addOval(Rect(vp, clip))
            val fill = seg(ms, tl.openAt, .6f * (tl.hit - tl.openAt))
            clipPath(corePath) {
                if (picture == null) drawRect(sky, alpha = fill)
                else {
                    if (card) drawBlurFill(fill)
                    dashPlace(ms, kd, clip)
                    if (card) drawOpeningCard() else drawPicture(place)
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

    /** The picture at [place] as the card it becomes: rounded, with its white edge and pink shadow, all growing with it. */
    private fun DrawScope.drawOpeningCard() {
        val s = place.k / cardPlace.k
        val pic = Rect(place.ox, place.oy, place.ox + iw * place.k, place.oy + ih * place.k)
        val corner = CARD_CORNER * dp * s
        val b = 4f * dp * s
        drawRoundRect(Sakura.Primary.copy(alpha = .45f), pic.topLeft + Offset(6f * dp * s, 8f * dp * s), pic.size, CornerRadius(corner))
        drawRoundRect(Sakura.White, pic.topLeft - Offset(b, b), Size(pic.width + 2f * b, pic.height + 2f * b), CornerRadius(corner + b))
        openClip.reset()
        openClip.addRoundRect(RoundRect(pic, CornerRadius(corner)))
        clipPath(openClip) { drawPicture(place) }
    }

    /**
     * The picture's placement while the core opens (progress [kd], clip radius [r]): the face travels on a straight line from the
     * core to its place on the stage while the scale grows geometrically, so it stays steady; over the full-screen picture it
     * never shows an edge inside the circle. At the end it is exactly the stage's (the home screen's crop, or the card).
     */
    private fun dashPlace(ms: Float, kd: Float, r: Float) {
        if (card) toPlace.set(cardPlace) else cover(iw, ih, w, h, zoomAt(ms), .5f, fy, .5f * w, fy * h, toPlace)
        val px = faceFx * iw
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

    /**
     * The rays, the aqua ones or all the others. In the dash every ray turns #DC9FB4 from the rim inward (a ray reaching the
     * screen's edge turns first, one near the core last); aqua turns white first (within the dash's first seventh), so pink never
     * lies over aqua.
     */
    private fun DrawScope.drawRays(ms: Float, aqua: Boolean) {
        if (counts.rays == 0 || ms < tl.tunnelAt) return
        val flown = tl.distance(ms)
        val dk = tl.dashK(ms)
        val p = tl.dashLinear(ms)
        val white = min(1f, 7f * p)
        val line = .55f * (1f - p)
        for (i in 0 until rays.count) {
            if (!rays.pose(i, ms, flown, dk)) continue
            val g = rays.group
            if ((g == 0) != aqua) continue
            val a = rays.alpha
            val pink = if (p <= 0f) 0f else .85f * ((1.6f * p - .9f * (1f - rays.head / reach)) / .35f).coerceIn(0f, 1f)
            if (aqua) aquaRay(rays.angle, rays.tail, rays.head, rays.half, rays.tailHalf, a, 1f - white, if (white >= 1f) pink else 0f, line * a)
            else ray(rays.angle, rays.tail, rays.head, rays.half, rays.tailHalf, rayBrush[g], if (g == 4) a else a * (1f - pink), if (g == 4) null else rayBrush[4], a * pink, line * a)
        }
    }

    /** Fills [rayPath] with a ray's outline in its own unit frame (x 0..1 along it): [tailHalf] wide at the tail, [half] at the head. */
    private fun rayOutline(hx: Float, half: Float, tailHalf: Float) {
        val cx = hx + (1f - hx) * .9f
        rayPath.reset()
        rayPath.moveTo(0f, -tailHalf)
        rayPath.lineTo(hx, -half)
        rayPath.quadraticTo(cx, -half, 1f, 0f)
        rayPath.quadraticTo(cx, half, hx, half)
        rayPath.lineTo(0f, tailHalf)
        rayPath.close()
    }

    private fun DrawScope.rayLine(half: Float, line: Float) {
        if (line <= .02f || half <= 2.5f * dp) return
        val lh = min(1.3f * dp, .3f * half)
        linePath.reset()
        linePath.moveTo(.35f, 0f)
        linePath.quadraticTo(.75f, -2f * lh, .97f, 0f)
        linePath.quadraticTo(.75f, 2f * lh, .35f, 0f)
        linePath.close()
        drawPath(linePath, lineBrush, alpha = line, blendMode = BlendMode.Plus)
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
            rayOutline((head - tail) / len, half, tailHalf)
            if (a1 > .005f) drawPath(rayPath, b1, alpha = a1)
            if (b2 != null && a2 > .005f) drawPath(rayPath, b2, alpha = a2)
            rayLine(half, line)
        }
    }

    /**
     * An aqua ray: its white sheath (opaque from a quarter out), the aqua inside it a little narrower and shorter at [aquaA]
     * (squared: it comes after the sheath as the ray leaves the core's light), then in the dash, once white, [pink] over it.
     */
    private fun DrawScope.aquaRay(a: Float, tail: Float, head: Float, half: Float, tailHalf: Float, alpha: Float, aquaA: Float, pink: Float, line: Float) {
        val tip = half * 1.15f
        val len = head + tip - tail
        if (len <= 1f) return
        withTransform({ translate(vp.x, vp.y); rotate(a * DEG, Offset.Zero); translate(tail, 0f); scale(len, 1f, Offset.Zero) }) {
            rayOutline((head - tail) / len, half, tailHalf)
            drawPath(rayPath, sheathBrush, alpha = alpha)
            val aq = alpha * alpha * aquaA
            if (aq > .005f) withTransform({ scale(.95f, .68f, Offset.Zero) }) { drawPath(rayPath, aquaBrush, alpha = aq) }
            if (pink > .005f) drawPath(rayPath, rayBrush[4], alpha = alpha * pink)
            rayLine(half, line)
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
     * The season riding the tunnel: in winter snowflakes, in spring sakura blossoms flying out of the core along the rays, a
     * third of them two and a half times larger near the camera; a flake streaks behind it as it rushes past.
     */
    private fun DrawScope.drawSeasonRide(ms: Float, amb: Float) {
        if (counts.season == 0 || reduced) return
        val a0 = seg(ms, tl.tunnelAt + 220f * pace, 300f * pace)
        if (a0 <= 0f) return
        val flown = tl.distance(ms)
        val spin = .03f * flown
        val far = 8f
        val span = far - .35f
        val n = counts.season * 2
        for (j in 0 until n) {
            val p = ih(j, 451) * span + .85f * flown
            val c = floor(p / span).toInt()
            val z = far - (p - c * span)
            val salt = j + 37 * c
            val rr = focal * (1f + 1.7f * ih(salt, 452)) / z
            if (rr > reach * 1.15f) continue
            val b = 2f * PI.toFloat() * ih(salt, 453) + spin
            val dx = cos(b)
            val dy = sin(b)
            val at = Offset(vp.x + dx * rr, vp.y + dy * rr)
            val big = if (ih(salt, 454) < .33f) 2.5f else 1f
            val size = 3.2f * dp * (2f / z).pow(.85f) * big
            val a = a0 * ((far - z) / 2.5f).coerceIn(0f, 1f)
            if (seasonKind == IntroSeason.SNOW) {
                val trail = size * (2f + 3f * tl.dashLinear(ms))
                drawLine(Sakura.White, Offset(at.x - dx * trail, at.y - dy * trail), at, size * 1.1f, StrokeCap.Round, alpha = .45f * a)
                flake(at, size, a)
            } else {
                blossom(at, size * 1.2f, 360f * ih(salt, 455) + (60f + 80f * ih(salt, 456)) * amb, a)
            }
        }
    }

    /** The season in the welcome: snow or blossoms drifting down under the veil, a quarter of them twice as large. */
    private fun DrawScope.drawSeasonFall(amb: Float, alpha: Float) {
        if (counts.season == 0 || alpha <= 0f) return
        val margin = 30f * dp
        val spanY = h + 2 * margin
        for (i in 0 until counts.season) {
            val hh = ih(i, 140)
            val big = if (ih(i, 147) < .25f) 2f else 1f
            val fall = (if (seasonKind == IntroSeason.SNOW) 34f else 28f) * dp * (.7f + .6f * hh)
            val y = ((ih(i, 141) * spanY + amb * fall) % spanY) - margin
            val sway = (8f + 10f * ih(i, 142)) * dp * sin(amb * (.8f + .6f * hh) + 6.28f * hh)
            val x = ih(i, 143) * w + sway
            val r = (if (seasonKind == IntroSeason.SNOW) 3f + 2.5f * ih(i, 144) else 6f + 3f * ih(i, 145)) * dp * big
            val ox = max(0f, max(wordsBox.left - x, x - wordsBox.right))
            val oy = max(0f, max(wordsBox.top - y, y - wordsBox.bottom))
            val a = alpha * ((hypot(ox, oy) - 1.7f * r) / (14f * dp)).coerceIn(0f, 1f)
            if (a <= 0f) continue
            if (seasonKind == IntroSeason.SNOW) flake(Offset(x, y), r, a)
            else blossom(Offset(x, y), r, 360f * ih(i, 146) + (30f + 40f * hh) * amb, a)
        }
    }

    /**
     * A snowflake [r] in radius, filled: a soft glow, a white rim, and inside it a ball running from a white heart to #A5DEE4 at
     * its edge (a white disc with a thin aqua ring read as a hollow bubble, and over the white tunnel only the ring showed).
     */
    private fun DrawScope.flake(at: Offset, r: Float, a: Float) {
        drawCircle(Sakura.White, 1.7f * r, at, alpha = .35f * a)
        drawCircle(Sakura.White, 1.12f * r, at, alpha = a)
        // Squared: while the flake fades in, its white comes before its aqua (aqua on a half-clear white was grey).
        withTransform({ translate(at.x, at.y); scale(r, r, Offset.Zero) }) { drawCircle(flakeBrush, 1f, Offset.Zero, alpha = a * a) }
    }

    /** A sakura blossom [r] in radius: pink with a white heart. */
    private fun DrawScope.blossom(at: Offset, r: Float, rot: Float, a: Float) {
        withTransform({ translate(at.x, at.y); rotate(rot, Offset.Zero); scale(r, r, Offset.Zero) }) { drawPath(crest, Sakura.Primary.copy(alpha = .9f), alpha = a) }
        drawCircle(Sakura.White, .28f * r, at, alpha = .9f * a)
    }

    /**
     * The status discs: each pops out of the core along its own lane, hovers fully on screen at its readable size while its chip
     * flips CHECK → OK, then flies past the camera, its words fading first (ink never fades over the rays).
     */
    fun drawDiscs(ds: DrawScope, ms: Float) = with(ds) {
        if (reduced || ms >= tl.hit) return@with
        for (i in 0 until tl.discs) {
            if (!tunnelDiscPose(lay, tl, i, ms, dp, pose)) continue
            val s = pose.scale
            withTransform({ translate(pose.x, pose.y); rotate(pose.rot, Offset.Zero); scale(s, s, Offset.Zero) }) {
                drawDisc(i, pose.k, ms, pose.labelA, pose.valueA, pose.faceA)
            }
        }
    }

    private fun DrawScope.drawDisc(i: Int, k: Float, ms: Float, labelA: Float, valueA: Float, faceA: Float) {
        val r = discR
        withTransform({ scale(r * 1.3f, r * 1.3f, Offset.Zero) }) { drawCircle(mistBloom, 1f, Offset.Zero, alpha = .9f * faceA) }
        // The face reaches 1.5 dp past the pink edge, so the edge never anti-aliases straight onto a ray.
        drawCircle(TunnelMist, r + 1.5f * dp, Offset.Zero, alpha = faceA)
        drawCircle(Sakura.Primary, r - 1.3f * dp, Offset.Zero, alpha = faceA, style = discEdge)
        // Aqua only between mist, never under the ink, and only on a disc large enough for its fine lines to stay apart (on a
        // small one the aqua ring ran into the pink edge) and not while it fades over the rays.
        val fine = faceA >= 1f && valueA >= 1f
        if (fine) drawCircle(Sakura.Aqua, .93f * r, Offset.Zero, style = discAqua)
        withTransform({ rotate(ms * .08f, Offset.Zero) }) { drawCircle(Sakura.MilkTea, .82f * r, Offset.Zero, alpha = faceA, style = ringA[i]) }
        withTransform({ rotate(-ms * .12f, Offset.Zero) }) { drawCircle(Sakura.Primary, .72f * r, Offset.Zero, alpha = .8f * faceA, style = ringB[i]) }
        val cy = .55f * r
        val ch = 20f * dp
        val label = discLabels[i]
        val labelTop = -.58f * r
        if (labelA > 0f) drawRun(label, -label.width / 2f, labelTop, Sakura.TextSecondary, labelA)
        if (valueA > 0f) {
            // The value centred between the header and the chip: one line on its cap height, or two lines.
            val lines = discValues[i]
            val mid = (labelTop + label.height + cy - ch / 2f) / 2f
            if (lines.size == 1) {
                val v = lines[0]
                drawRun(v, -v.width / 2f, mid + .35f * v.fontPx - v.baseline, Sakura.Text, valueA)
            } else {
                val f = lines[0].fontPx
                val b1 = mid - .2f * f
                for ((j, v) in lines.withIndex()) drawRun(v, -v.width / 2f, b1 + j * 1.0f * f - v.baseline, Sakura.Text, valueA)
            }
        }
        // The chip flips (squashes and reopens) from CHECK to OK at the middle of the hover.
        val ok = k >= DISC_FLIP
        val flip = if (k in DISC_FLIP - .05f..<DISC_FLIP + .05f) abs(cos(PI.toFloat() * (k - DISC_FLIP + .05f) / .1f)) else 1f
        val run = if (ok) chipOk else chipCheck
        val cw = max(chipOk.width + 22f * dp, chipCheck.width) + 18f * dp
        withTransform({ scale(1f, flip.coerceAtLeast(.02f), Offset(0f, cy)) }) {
            // The mist reaches past the outline, so the aqua edge lies on mist, not on the pink dashes under the chip.
            val m = 1.5f * dp
            drawRoundRect(TunnelMist, Offset(-cw / 2f - m, cy - ch / 2f - m), Size(cw + 2f * m, ch + 2f * m), CornerRadius(ch / 2f + m), alpha = faceA)
            if (fine || !ok) drawRoundRect(if (ok) Sakura.Aqua else Sakura.Primary, Offset(-cw / 2f, cy - ch / 2f), Size(cw, ch), CornerRadius(ch / 2f), alpha = faceA, style = if (ok) chipAqua else chipPink)
            val tw = run.width + (if (ok) 14f * dp else 0f)
            val left = -tw / 2f
            // Squashed flat, the words would lie on the aqua edge (ink on aqua is grey): only the pill turns over.
            if (flip >= .5f && valueA > 0f) {
                drawRun(run, left, cy - run.baseline + run.fontPx * .36f, if (ok) Sakura.Text else Sakura.TextSecondary, valueA)
                if (ok) {
                    val x = left + run.width + 4f * dp
                    okTick.reset()
                    okTick.moveTo(x, cy)
                    okTick.lineTo(x + 3.5f * dp, cy + 3.5f * dp)
                    okTick.lineTo(x + 10f * dp, cy - 4f * dp)
                    drawPath(okTick, Sakura.Primary, alpha = valueA, style = tickStroke)
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
        val loginOut = tl.panelOut(ms, tl.loginOutAt)
        if (loginOut < 1f) leaving(loginOut) {
            panel(cx, top, c.loginH, seg(ms, tl.loginAt, 140f * pace, IntroEase.CubicOut), tabLogin, tabLoginLatin) {
                val left = cx - c.pw / 2f
                drawRun(c.labelName, left + 18f * dp, top + c.row1Base - c.labelName.baseline, Sakura.TextSecondary, 1f)
                drawRun(c.labelCode, left + 18f * dp, top + c.row2Base - c.labelCode.baseline, Sakura.TextSecondary, 1f)
                val vx = left + c.valueX
                drawLine(Sakura.MilkTea, Offset(vx, top + c.row1Base + 6f * dp), Offset(left + c.pw - 18f * dp, top + c.row1Base + 6f * dp), 1.2f * dp)
                drawLine(Sakura.MilkTea, Offset(vx, top + c.row2Base + 6f * dp), Offset(left + c.pw - 18f * dp, top + c.row2Base + 6f * dp), 1.2f * dp)
                val n = c.field.run.size
                drawField(c.field, vx, top + c.row1Base, ms, tl.typeAt, tl.typeStep(n))
                // Six dots for the code, popping in one by one behind the name (nothing is read: it is only a look).
                val dotsAt = tl.dotsAt(n)
                var shown = 0
                for (j in 0 until 6) {
                    val dk = seg(ms, dotsAt + j * DOT_MS * pace, 50f * pace)
                    if (dk <= 0f) break
                    shown = j + 1
                    drawCircle(Sakura.Primary, 4.5f * dp * lerp(1.5f, 1f, dk), Offset(vx + 6f * dp + j * 16f * dp, top + c.row2Base - 7f * dp))
                }
                val done = dotsAt + 6 * DOT_MS * pace
                if (ms >= dotsAt && (ms < done || ((ms - done) % 320f) < 160f)) {
                    drawRect(Sakura.Primary, Offset(vx + shown * 16f * dp, top + c.row2Base - 16f * dp), Size(2.4f * dp, 17f * dp))
                }
            }
        }
        // The 確認 panel wipes in once the 登入 panel has mostly gone (they share the place; overlapped, they read as a muddle).
        if (!tl.compact && ms >= tl.confirmAt + 90f * pace) {
            val out = tl.panelOut(ms, tl.dashAt)
            if (out < 1f) leaving(out) {
                panel(cx, top, c.confirmH, seg(ms, tl.confirmAt + 90f * pace, 110f * pace, IntroEase.CubicOut), tabConfirm, tabConfirmLatin) {
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
                        drawRun(c.goRun, cx - c.goRun.width / 2f, bt + (buttonH - c.goRun.height) / 2f, Sakura.Text, 1f)
                    }
                    drawRun(c.confirmRun, cx - c.confirmRun.width / 2f, top + c.confirmTop, Sakura.Text, 1f)
                }
            }
        }
    }

    /**
     * A panel leaving as one piece ([out] 0..1): composed in a layer that fades as a whole while it flies at the camera (grows
     * about the vanishing point, so it moves away from the core): its words and frame go together, no empty shell.
     */
    private inline fun DrawScope.leaving(out: Float, body: DrawScope.() -> Unit) {
        if (out <= 0f) { body(); return }
        val s = 1f + .35f * IntroEase.CubicIn.transform(out)
        layerPaint.alpha = 1f - out
        drawIntoCanvas { it.saveLayer(Rect(0f, 0f, w, h), layerPaint) }
        withTransform({ scale(s, s, vp) }) { body() }
        drawIntoCanvas { it.restore() }
    }

    /**
     * A card [TunnelLayout.pw] wide with its top at [top]: a pink hard shadow, an opaque mist face with a pink border and a pink
     * tab with its title on the top edge; [open] wipes it open from the middle. A mist margin runs round the card and the tab,
     * so their pink edges never anti-alias straight onto a ray.
     */
    private inline fun DrawScope.panel(cx: Float, top: Float, height: Float, open: Float, tab: GlyphRun, tabLatin: GlyphRun, body: DrawScope.() -> Unit) {
        if (open <= 0f) return
        val pw = lay.pw
        val left = cx - pw / 2f
        val half = (pw / 2f + 12f * dp) * open
        val cy = top + height / 2f
        val corner = CornerRadius(20f * dp)
        val m = 1.5f * dp
        withTransform({
            scale(lerp(.96f, 1f, open), lerp(.96f, 1f, open), Offset(cx, cy))
            clipRect(cx - half, top - 30f * dp, cx + half, top + height + 20f * dp)
        }) {
            // The hard shadow is solid (pink over mist), not translucent pink: over an aqua ray that was a grey-blue.
            drawRoundRect(panelShadow, Offset(left + 5f * dp, top + 7f * dp), Size(pw, height), corner)
            drawRoundRect(TunnelMist, Offset(left - m, top - m), Size(pw + 2f * m, height + 2f * m), CornerRadius(20f * dp + m))
            val th = 30f * dp
            val tw = tab.width + tabLatin.width + 34f * dp
            val tl0 = left + 18f * dp
            val tt = top - th / 2f
            drawRoundRect(TunnelMist, Offset(tl0 - m, tt - m), Size(tw + 2f * m, th + 2f * m), CornerRadius(th / 2f + m))
            drawRoundRect(Sakura.Primary, Offset(left, top), Size(pw, height), corner, style = cardEdge)
            drawRoundRect(Sakura.Primary, Offset(tl0, tt), Size(tw, th), CornerRadius(th / 2f))
            drawRun(tab, tl0 + 14f * dp, tt + (th - tab.height) / 2f, Sakura.Text, 1f)
            drawRun(tabLatin, tl0 + 14f * dp + tab.width + 6f * dp, tt + (th - tab.height) / 2f + tab.baseline - tabLatin.baseline, Sakura.Text, 1f)
            body()
        }
    }

    /**
     * The name typing into the 名字 field (its baseline at [baseline]): never scrolled, fitted or cut with … ([tunnelField]); an
     * emoji is pink with a rose-brown edge as in the welcome's sticker; a pink caret while it types.
     */
    private fun DrawScope.drawField(f: TunnelField, left: Float, baseline: Float, ms: Float, start: Float, step: Float) {
        val run = f.run
        val t = ms - start
        if (t < 0f || run.size == 0) return
        val top = baseline - run.baseline
        var shown = 0
        for (i in 0 until run.size) {
            val a = ((t - step * i) / 40f).coerceIn(0f, 1f)
            if (a <= 0f) break
            shown = i + 1
            val s = f.scale[i]
            val at = Offset(left + f.x[i] - run.advance[i] * (1f - s) / 2f, top)
            if (run.emoji[i]) drawGlyph(run, i, at, Sakura.Text, a, sticker = true)
            else drawGlyph(run, i, at, Sakura.Text, a, scale = s)
        }
        if (t < step * run.size + 40f) {
            val x = if (shown == 0) left else left + f.x[shown - 1] + run.advance[shown - 1] * f.scale[shown - 1] + 4f * dp
            caret(x, baseline, run.fontPx)
        }
    }

    /**
     * A typing caret everyone knows: #DC9FB4, 2 dp wide, 60 % of a capital's height, standing on the baseline (a text-coloured bar
     * at full height read as one more letter, "backl").
     */
    private fun DrawScope.caret(x: Float, baseline: Float, fontPx: Float) {
        val ch = CARET_CAP * fontPx
        drawRect(Sakura.Primary, Offset(x, baseline - ch), Size(2f * dp, ch))
    }

    /** [run] typed in letter by letter from [start], [perChar] apart, a caret 4 dp after it while it types, gone when done. */
    private fun DrawScope.typeLine(run: GlyphRun, left: Float, top: Float, color: Color, ms: Float, start: Float, perChar: Float) {
        if (run.size == 0) return
        if (reduced) { drawRun(run, left, top, color, 1f); return }
        val t = ms - start
        if (t < 0f) return
        var shown = 0
        for (i in 0 until run.size) {
            val a = ((t - perChar * i) / 40f).coerceIn(0f, 1f)
            if (a <= 0f) break
            shown = i + 1
            drawGlyph(run, i, Offset(left + run.x[i], top), color, a)
        }
        if (t < perChar * run.size + 40f) {
            val x = if (shown == 0) left else left + run.x[shown - 1] + run.advance[shown - 1] + 4f * dp
            caret(x, top + run.baseline, run.fontPx)
        }
    }

    /** The dash's flood, its speed lines, the white burst with its true-white peak, the shock rings, the flare and the petals. */
    fun drawBurst(ds: DrawScope, ms: Float, amb: Float) = with(ds) {
        if (reduced) return@with
        val fl = tl.flood(ms)
        if (fl > 0f) drawRect(floodBrush, fullAt, fullSize, alpha = fl)
        drawSpeedLines(ms)
        val f = tl.flash(ms)
        if (f > 0f) drawRect(Sakura.White, fullAt, fullSize, alpha = f)
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
     * The dash's speed lines: pink and mist streaks shooting out of the core, accelerating, longer and thicker as they go, over
     * everything until the white burst swallows them (the original's outward dashes, re-drawn as straight blades). They start
     * once the aqua rays have turned white, spread over the whole dash.
     */
    private fun DrawScope.drawSpeedLines(ms: Float) {
        if (counts.lines == 0 || ms < tl.dashAt || ms > tl.hit + 200f * pace) return
        linesPink.reset()
        linesMist.reset()
        val span = tl.hit - tl.dashAt
        for (j in 0 until counts.lines) {
            val t0 = tl.dashAt + (.15f + .65f * ih(j, 432)) * span
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
     * DSH MOBILE at the top left and the time at the top right, each on an opaque mist pill, and this look's 跳過 SKIP ›› pill at
     * the bottom right. The faces are there from the first frame; only the words fade in (ink fading over a ray is grey).
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
        if (skippable) drawRoundRect(TunnelMist, skipRect.topLeft, skipRect.size, CornerRadius(skipRect.height / 2f))
        if (a <= 0f) return@with
        drawCircle(Sakura.Primary, dot / 2f, Offset(14f * dp + 10f * dp + dot / 2f, top + ph / 2f), alpha = a)
        drawRun(brand, 14f * dp + 10f * dp + dot + 6f * dp, top + 4f * dp, Sakura.TextSecondary, a)
        drawRun(clock, w - 14f * dp - cw + 10f * dp, top + 4f * dp + brand.baseline - clock.baseline, Sakura.TextSecondary, a)
        if (skippable) {
            var x = skipRect.left + 12f * dp
            val st = skipRect.top + (skipRect.height - skipCjk.height) / 2f
            drawRun(skipCjk, x, st, Sakura.Text, a)
            x += skipCjk.width + 6f * dp
            drawRun(skipLatin, x, st + skipCjk.baseline - skipLatin.baseline, Sakura.Text, a)
            x += skipLatin.width + 6f * dp
            val cy = st + skipCjk.baseline - 4.5f * dp
            for (j in 0 until 2) {
                val cx = x + j * 7f * dp
                drawLine(Sakura.Primary, Offset(cx, cy - 4f * dp), Offset(cx + 5f * dp, cy), 1.5f * dp, StrokeCap.Round, alpha = a)
                drawLine(Sakura.Primary, Offset(cx + 5f * dp, cy), Offset(cx, cy + 4f * dp), 1.5f * dp, StrokeCap.Round, alpha = a)
            }
        }
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
        latin?.let { typeLine(it, welcomeX - it.width / 2f, latinTop, Sakura.TextSecondary, ms, tl.nameAt + 160f * pace, 22f * pace) }
    }

    private companion object {
        /** The sticker's smallest size before it breaks onto two lines. */
        const val STICKER_FLOOR = 32f
        const val CARD_CORNER = 18f
        /** One code dot every this many ms (× pace). */
        const val DOT_MS = 20f
        /** The caret: 60 % of a capital's height (about .7 em). */
        const val CARET_CAP = .6f * .7f
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
