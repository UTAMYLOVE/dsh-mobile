package app.dshmobile.ui.fog

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One run's moving parts, shared by every look: the clock and the exit. Read [ms] and [amb] only in draw lambdas and
 * graphicsLayer blocks, never in composition, so a run does not recompose per frame.
 */
class IntroRun internal constructor() {
    internal val msState = mutableFloatStateOf(0f)
    internal val ambState = mutableFloatStateOf(0f)
    internal val fadeState = mutableFloatStateOf(1f)

    /** The run's clock in ms. It advances at most [IntroHostConst.MAX_STEP_MS] per frame, so a stall pauses the look. */
    val ms: Float get() = msState.floatValue
    /** Ambient seconds for loops and drift; frozen at 0 for reduced motion. */
    val amb: Float get() = ambState.floatValue
    /** The reduced-motion still's fade over its last 150 ms (1 otherwise). */
    val fade: Float get() = fadeState.floatValue

    /** When the exit started (ms on the run's clock), or -1 before it. */
    var exitAt: Float by mutableFloatStateOf(-1f)
        internal set
    /** The exit's length: to the end of the run, or 280 ms after a tap. */
    var exitDur: Float by mutableFloatStateOf(0f)
        internal set
    /** Where the user tapped to skip (the iris opens there); null when nothing was tapped. */
    var origin: Offset? = null
        internal set
    /** Where the iris opens when nothing was tapped; a look may set it (null: the middle of the upper third). */
    var sceneOrigin: Offset? = null

    /** The user's picture as decided when the clock started (never swapped in mid-beat), or null. */
    var picture: ImageBitmap? = null
        internal set

    /** The avatar square chosen in 調整頭像 (or the app's guess), decided with [picture]; null when only its shape is known. */
    var avatar: AvatarCrop? = null
        internal set

    internal var finished = false
    internal val decided = mutableStateOf(false)

    val exiting: Boolean get() = exitAt >= 0f

    /** The exit's progress, 0..1 (0 before it starts). */
    fun exitK(): Float = if (exitAt < 0f) 0f else ((ms - exitAt) / exitDur.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /**
     * A layer's alpha on exit: 1, then down to 0 over [dur] ms from [delay] ms after the exit starts. Type uses it so it is gone
     * before the iris reaches it (cut by the iris's edge, text sat on the app's own buttons).
     */
    fun exitFade(delay: Float = 0f, dur: Float = 80f): Float = if (exitAt < 0f) 1f else 1f - ((ms - exitAt - delay) / dur).coerceIn(0f, 1f)
}

object IntroHostConst {
    /**
     * The most the clock advances in one frame. dsh starts underneath the intro, and a cold start can freeze the main thread for
     * half a second: with the plain `now − start` clock that jumped beats (even past 核驗通過). Capped, the look pauses instead.
     * (The capped clock is an idea from Rearpoem/dsh-link-start, MIT.)
     */
    const val MAX_STEP_MS = 100f
    /** A tap's exit iris. */
    const val TAP_EXIT_MS = 280f
    /** The reduced-motion still frame shows this long at most, then fades over [STILL_FADE_MS]. */
    const val STILL_SHOW_MS = 2500
    const val STILL_FADE_MS = 150f
}

/** How long a run waits, after the picture, for the avatar guess of a picture never guessed before. */
private const val AVATAR_WAIT_MS = 300L

/**
 * What the host needs to know of one run of a look, in ms on the run's clock.
 *
 * [exit] is when the exit iris starts by itself; it runs to [total]. [hit] is the look's impact moment (one CONFIRM haptic tick
 * and, on FULL, a 2 dp nudge of the whole screen), negative for none. [announce] is when the host's own screen-reader node
 * announces the welcome line (once). [still] is the clock value the reduced-motion frame is drawn at.
 */
class IntroTiming(val exit: Float, val total: Float, val still: Float, val hit: Float = -1f, val announce: Float = exit) {
    companion object {
        /** A look built on an [IntroPlan] whose last beat is the exit. */
        fun of(plan: IntroPlan, still: Float, hit: Float = -1f, announce: Float = plan.at(plan.size - 1)): IntroTiming =
            IntroTiming(plan.at(plan.size - 1), plan.total, still, hit, announce)
    }
}

/** What a look's content gets from the host: the run, the screen, the user's picture and how much may move. */
class IntroScope internal constructor(
    val run: IntroRun,
    /** The user's (recoloured) picture, decided once when the clock started; null when there is none. */
    val picture: ImageBitmap?,
    val widthPx: Float,
    val heightPx: Float,
    val density: Density,
    /** The system bars, so nothing important sits under them. */
    val insetTop: Float,
    val insetBottom: Float,
    /** How rich the look may be: cap particle counts and skip costly layers by it ([introCount]). */
    val level: EffectLevel,
    /** Reduced motion: draw one still frame (the host holds the clock at [IntroTiming.still]). */
    val reduced: Boolean,
    /** The user's 粉嫩 choices (petals, sparkles, amount) and the season. */
    val fx: IntroFx,
    /** Where the picture's crop is held (the 對齊 setting), the same as the home screen's. */
    val align: PictureAlign,
    avatar: AvatarCrop? = null,
) {
    /**
     * The part of [picture] every portrait, avatar and identity window shows (cut with [avatarSquare]): the square chosen in
     * 調整頭像, else the app's guess, else (before the guess is known) the rule for the picture's shape. Full-screen backdrops
     * keep [align].
     */
    val avatar: AvatarCrop = avatar ?: picture?.let { AvatarGuess.heuristic(it.width, it.height) } ?: AvatarCrop.WHOLE
}

/** How rich a look may be: FULL with blur on, BALANCED with ambient motion (or reduced motion), SAVER otherwise. */
@Composable
internal fun introLevel(reducedMotion: Boolean): EffectLevel {
    val effects = LocalEffects.current
    return when { effects.blur -> EffectLevel.FULL; effects.ambient || reducedMotion -> EffectLevel.BALANCED; else -> EffectLevel.SAVER }
}

/**
 * [introLevel] as it was when the run started, kept for the whole run. The jank governor can step the app's level down while
 * the intro plays (a cold start, with dsh starting underneath, is when frames drop): followed live, every look rebuilt its scene
 * mid-run (re-measuring the type, re-placing every grain, re-blurring the picture) on the very frames the phone was struggling
 * with, its particles jumped to the new count and 星屑迎接 swapped its light iris for the crossfade half way through it. The app
 * behind the intro still follows the governor; the intro is over in at most 8 s.
 */
@Composable
internal fun rememberIntroLevel(reducedMotion: Boolean): EffectLevel {
    val live = introLevel(reducedMotion)
    return remember { live }
}

/**
 * The frame every look plays in, so each one gets the same behaviour for free:
 * - a picture on its way gets up to 400 ms, then the run's [IntroRun.picture] is decided once;
 * - the capped clock ([IntroHostConst.MAX_STEP_MS]), the haptic tick and nudge at [IntroTiming.hit], the exit at
 *   [IntroTiming.exit] and [onDone] exactly once at [IntroTiming.total];
 * - with [Intro.skippable] a tap opens a 280 ms iris at the tap and back ends it;
 * - the sakura-shaped exit iris (a soft fade on SAVER), opened at the tap or at [IntroRun.sceneOrigin];
 * - reduced motion: the clock held at [IntroTiming.still] for up to 2.5 s (never longer than the run), then a 150 ms fade; a
 *   tap ends it at once;
 * - [onOpaqueChange] true while the intro covers the screen underneath.
 *
 * With [announce] (the welcome line) the host also draws a 跳過 SKIP label at the bottom right when skippable and adds the
 * screen-reader nodes: the skip button and [announce] as the run's one live announcement from [IntroTiming.announce]. A look
 * with its own words and semantics (櫻紋陣) passes null.
 */
@Composable
internal fun IntroHost(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean,
    timing: IntroTiming,
    fx: IntroFx,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    announce: String?,
    content: @Composable IntroScope.() -> Unit,
) {
    val done by rememberUpdatedState(onDone)
    val opaque by rememberUpdatedState(onOpaqueChange)
    val latestPicture by rememberUpdatedState(picture)
    val latestTiming by rememberUpdatedState(timing)
    // The avatar square: the one chosen in 調整頭像, else the guess for the picture, which may still be on its way.
    val look = LocalLook.current
    val avatarExpected = look.hasPicture && look.avatar == null && LocalLookPicture.current != null
    val latestAvatar by rememberUpdatedState(rememberAvatarCrop(look))
    val level = rememberIntroLevel(reducedMotion)
    val totalMs = (intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000).roundToInt()
    val run = remember { IntroRun() }
    val view = LocalView.current
    val finish = {
        if (!run.finished) {
            run.finished = true
            opaque(false)
            done()
        }
    }
    DisposableEffect(Unit) { onDispose { opaque(false) } }

    LaunchedEffect(Unit) {
        opaque(true)
        // A picture that is on its way gets up to 400 ms (the screen stays the plain blush of the window meanwhile).
        if (pictureExpected && latestPicture == null) withTimeoutOrNull(400) { snapshotFlow { latestPicture }.first { it != null } }
        run.picture = latestPicture
        // The guess is kept next to the picture, so it is normally known by now; only a picture never guessed before (picked
        // with an older app) gets a moment for it, then the rule for its shape stands in (IntroScope.avatar).
        if (run.picture != null && avatarExpected && latestAvatar == null) {
            withTimeoutOrNull(AVATAR_WAIT_MS) { snapshotFlow { latestAvatar }.first { it != null } }
        }
        run.avatar = latestAvatar.takeIf { run.picture != null }
        run.decided.value = true
        if (reducedMotion) {
            run.msState.floatValue = latestTiming.still
            // Never longer than the look itself runs: 一筆描 is about 2 s at any setting, so its still is too.
            val show = minOf(totalMs, latestTiming.total.roundToInt(), IntroHostConst.STILL_SHOW_MS)
            delay((show - IntroHostConst.STILL_FADE_MS).toLong())
            val t0 = withFrameNanos { it }
            while (true) {
                val t = (withFrameNanos { it } - t0) / 1_000_000f
                run.fadeState.floatValue = (1f - t / IntroHostConst.STILL_FADE_MS).coerceIn(0f, 1f)
                if (t >= IntroHostConst.STILL_FADE_MS) break
            }
            finish()
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        var ms = 0f
        var hit = false
        while (!run.finished) {
            val now = withFrameNanos { it }
            ms += ((now - last) / 1_000_000f).coerceIn(0f, IntroHostConst.MAX_STEP_MS)
            last = now
            run.msState.floatValue = ms
            run.ambState.floatValue = ms / 1000f
            val tm = latestTiming
            if (!hit && tm.hit >= 0f && ms >= tm.hit) {
                hit = true
                // From the frame loop, never from draw; the system's own haptic setting still applies.
                if (IntroConst.HAPTIC) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            if (run.exitAt < 0f && ms >= tm.exit) {
                run.exitAt = tm.exit
                run.exitDur = tm.total - tm.exit
                opaque(false)
            }
            if (run.exitAt >= 0f && ms >= run.exitAt + run.exitDur) finish()
        }
    }
    BackHandler(enabled = intro.skippable) { finish() }

    val tap: (Offset) -> Unit = { at ->
        if (intro.skippable && !run.finished) {
            if (reducedMotion) finish()
            else if (run.exitAt < 0f) {
                run.origin = at
                run.exitAt = run.ms
                run.exitDur = IntroHostConst.TAP_EXIT_MS
                opaque(false)
            }
        }
    }
    val skipLabel = stringResource(R.string.intro_skip)
    val density = LocalDensity.current
    val insetTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val insetBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .testTag(INTRO_TAG)
            .pointerInput(intro.skippable) { detectTapGestures(onTap = { tap(it) }) }
            .semantics { if (intro.skippable) onClick(skipLabel) { tap(Offset(-1f, -1f)); true } }
            .graphicsLayer {
                val ms = run.ms
                val exitK = run.exitK()
                val hitAt = timing.hit
                translationY = if (level == EffectLevel.FULL && hitAt >= 0f) nudge(ms - hitAt) * density.density else 0f
                if (level == EffectLevel.SAVER && run.exitAt >= 0f) {
                    val k = IntroEase.CubicIn.transform(((ms - (run.exitAt + run.exitDur - 240f)) / 240f).coerceIn(0f, 1f))
                    scaleX = 1f + .03f * k
                    scaleY = scaleX
                    alpha = 1f - k
                } else {
                    alpha = run.fade * (1f - ((exitK - .7f) / .3f).coerceIn(0f, 1f))
                }
                compositingStrategy = if (run.exitAt >= 0f && level != EffectLevel.SAVER) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithCache {
                val sakura = sakuraSilhouette()
                val scratch = Path()
                val matrix = Matrix()
                val sparkle = Kawaii.sparklePath()
                val dp = density.density
                // Built once with the cache, not on each frame of the iris.
                val rimGlow = Stroke(10f * dp)
                val rimLine = Stroke(2f * dp)
                onDrawWithContent {
                    drawContent()
                    val exitAt = run.exitAt
                    if (exitAt < 0f || level == EffectLevel.SAVER) return@onDrawWithContent
                    val ms = run.ms
                    val k = run.exitK()
                    val o = run.origin?.takeIf { it.x >= 0f } ?: run.sceneOrigin ?: Offset(size.width / 2f, .30f * size.height)
                    val far = maxOf(hypot(o.x, o.y), hypot(size.width - o.x, o.y), hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y))
                    // The silhouette's valleys sit at radius .70, so at r = far / .70 the whole screen is open.
                    val r = (far + 16f * dp) / .70f * IntroEase.CubicInOut.transform(k)
                    if (r <= 0f) return@onDrawWithContent
                    matrix.reset()
                    matrix.translate(o.x, o.y)
                    matrix.rotateZ(30f * k)
                    matrix.scale(r, r)
                    scratch.reset()
                    scratch.addPath(sakura)
                    scratch.transform(matrix)
                    drawPath(scratch, Color.Black, blendMode = BlendMode.DstOut)
                    drawPath(scratch, Sakura.White.copy(alpha = .22f), style = rimGlow)
                    drawPath(scratch, Sakura.White.copy(alpha = .95f), style = rimLine)
                    for (i in 0 until 5) {
                        val a = (270f + 72f * i + 30f * k) * (PI.toFloat() / 180f)
                        val tip = Offset(o.x + cos(a) * .99f * r, o.y + sin(a) * .99f * r)
                        withTransform({ translate(tip.x, tip.y); rotate(40f * ms / 1000f, Offset.Zero); scale(3f * dp, 3f * dp, Offset.Zero) }) {
                            drawPath(sparkle, Sakura.White)
                        }
                    }
                }
            }
            .background(Sakura.Background),
    ) {
        if (!run.decided.value) return@BoxWithConstraints
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val scope = remember(w, h, density, insetTop, insetBottom, level, reducedMotion, run.picture, fx, look.align, run.avatar) {
            IntroScope(run, run.picture, w, h, density, insetTop, insetBottom, level, reducedMotion, fx, look.align, run.avatar)
        }
        scope.content()
        if (announce != null) {
            IntroSkipLabel(scope, intro.skippable)
            IntroDefaultSemantics(scope, timing, intro.skippable, skipLabel, announce)
        }
    }
}

/** The host's 跳過 SKIP ›› at the bottom right (only with [skippable]), fading in at 200 ms and out first on exit. */
@Composable
private fun IntroSkipLabel(sc: IntroScope, skippable: Boolean) {
    if (!skippable) return
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val coverage = IntroFonts.coverage(LocalContext.current.resources)
    val skip = stringResource(R.string.intro_skip)
    val runs = remember(sc, skip) {
        val mm = IntroMeasure(measurer, sc.density.density, sc.density.fontScale, 1f, coverage)
        mm.run(skip, IntroFonts.hand(12f, small = true), .04f) to mm.run(IntroLatin.SKIP, IntroFonts.latin(11f, FontWeight.SemiBold), .2f)
    }
    val run = sc.run
    Spacer(
        Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade() }.drawBehind {
            val (cjk, latin) = runs
            val dp = sc.density.density
            val a = if (sc.reduced) 1f else seg(run.ms, 200f, 200f)
            if (a <= 0f) return@drawBehind
            val chevW = 2 * 5f * dp + 2f * dp
            val right = size.width - 20f * dp
            val top = size.height - sc.insetBottom - 42f * dp
            var x = right - chevW - 6f * dp - latin.width - 6f * dp - cjk.width
            // A soft white pill behind it, so it reads over a busy picture.
            drawRoundRect(
                Sakura.White.copy(alpha = .55f * a), Offset(x - 10f * dp, top - 4f * dp),
                androidx.compose.ui.geometry.Size(right - x + 20f * dp, cjk.height + 8f * dp),
                androidx.compose.ui.geometry.CornerRadius(14f * dp),
            )
            drawRun(cjk, x, top, Sakura.Text, a)
            x += cjk.width + 6f * dp
            drawRun(latin, x, top + (cjk.baseline - latin.baseline), Sakura.Text, a)
            x += latin.width + 6f * dp
            val cy = top + cjk.baseline - 4.5f * dp
            for (j in 0 until 2) {
                val cx = x + j * 7f * dp
                drawLine(Sakura.Primary.copy(alpha = a), Offset(cx, cy - 4f * dp), Offset(cx + 5f * dp, cy), 1.5f * dp, StrokeCap.Round)
                drawLine(Sakura.Primary.copy(alpha = a), Offset(cx + 5f * dp, cy), Offset(cx, cy + 4f * dp), 1.5f * dp, StrokeCap.Round)
            }
        },
    )
}

/**
 * The default screen-reader nodes: the skip button (with [skippable]) and [line], the welcome, as the run's one live announcement
 * from [IntroTiming.announce] (at once for reduced motion). Composed once more when the announcement starts, never per frame.
 */
@Composable
private fun IntroDefaultSemantics(sc: IntroScope, timing: IntroTiming, skippable: Boolean, skipLabel: String, line: String) {
    val run = sc.run
    val shown by remember(timing) { derivedStateOf { sc.reduced || run.ms >= timing.announce } }
    val d = sc.density
    fun Modifier.at(x: Float, y: Float, w: Float, h: Float) = this
        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        .size(with(d) { w.coerceAtLeast(1f).toDp() }, with(d) { h.coerceAtLeast(1f).toDp() })
    val dp = d.density
    if (skippable) {
        Box(Modifier.at(sc.widthPx - 130f * dp, sc.heightPx - sc.insetBottom - 46f * dp, 116f * dp, 30f * dp).semantics { text = AnnotatedString(skipLabel); role = Role.Button })
    }
    if (shown) {
        Box(
            Modifier.at(24f * dp, sc.heightPx * .40f, sc.widthPx - 48f * dp, 80f * dp)
                .semantics { text = AnnotatedString(line); liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A particle count for [level], scaled by the user's 數量 ([IntroFx.scale]); reduced motion gets the SAVER count. */
internal fun introCount(level: EffectLevel, fx: IntroFx, full: Int, balanced: Int, saver: Int, reduced: Boolean = false): Int {
    val base = if (reduced) saver else when (level) { EffectLevel.FULL -> full; EffectLevel.BALANCED -> balanced; EffectLevel.SAVER -> saver }
    return (base * fx.scale).roundToInt().coerceIn(0, base * 2)
}

/** The welcome line every look announces: the user's words (or 歡迎回來) and the name, e.g. 歡迎回來，小雨. */
@Composable
internal fun introWelcomeLine(intro: Intro): String = IntroTimeline.welcome(intro, stringResource(R.string.intro_welcome_default))
