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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** One run's moving parts: the clock (ms, read only in draw lambdas) and the exit, once it starts. */
private class IntroRun {
    val ms = mutableFloatStateOf(0f)
    /** Ambient seconds (loops, drift); frozen at 0 for reduced motion. */
    val amb = mutableFloatStateOf(0f)
    /** Fades the reduced-motion still out over its last 150 ms. */
    val fade = mutableFloatStateOf(1f)
    var exitAt by mutableFloatStateOf(-1f)
    var exitDur by mutableFloatStateOf(0f)
    var origin: Offset? = null
    var finished = false
    /** The picture is decided once, when the clock starts, and never swapped in mid-beat. */
    val decided = mutableStateOf(false)
    var picture: ImageBitmap? = null
    /** Where the exit iris opens when nothing was tapped (the crest, or the upper third over a backdrop). */
    var sceneOrigin: Offset? = null
}

/**
 * 「櫻紋陣」 (Sakura Sigil), the optional startup animation (the owner, 2026-11-15: the old card 「有點醜」, look at real 二次元
 * boot screens). Two halves: a pastel terminal wakes on a pink sky and a sakura magic circle is traced, assembled and scans *you*
 * (需要訪問許可 → 身份確認); at 核驗通過 one flash, a haptic tick, ribbons and petals transform it, the circle lies down as a
 * glowing floor, your picture opens to the full screen (or your monogram floats up as a crest) and your name stands on the circle
 * (歡迎回來); a sakura-shaped iris opens into the app.
 *
 * Fixed punches and stretching holds ([IntroMarks]); everything is a pure function of one clock read only in draw lambdas, so a
 * run recomposes once per beat. dsh keeps starting underneath. With [Intro.skippable] a tap opens the iris at once and back ends
 * it; with [reducedMotion] one still welcome frame shows. [onOpaqueChange] says when the screen underneath may be hidden (true while
 * the intro covers it); [onDone] is called exactly once. Only the six colours (and #5E4445 / #7D5F60 for text).
 */
@Composable
fun StartupIntro(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean = false,
    fx: IntroFx = IntroFx(),
    onOpaqueChange: (Boolean) -> Unit = {},
    onDone: () -> Unit,
) {
    val done by rememberUpdatedState(onDone)
    val opaque by rememberUpdatedState(onOpaqueChange)
    val latestPicture by rememberUpdatedState(picture)
    val effects = LocalEffects.current
    val level = when { effects.blur -> EffectLevel.FULL; effects.ambient || reducedMotion -> EffectLevel.BALANCED; else -> EffectLevel.SAVER }
    val totalMs = (intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000).roundToInt()
    val marks = remember(totalMs, reducedMotion) { if (reducedMotion) IntroMarks.STILL else IntroMarks.of(totalMs) }
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
        run.decided.value = true
        if (reducedMotion) {
            run.ms.floatValue = IntroMarks.STILL_MS
            val show = minOf(totalMs, 2500)
            delay((show - 150).toLong())
            val t0 = withFrameNanos { it }
            while (true) {
                val t = (withFrameNanos { it } - t0) / 1_000_000f
                run.fade.floatValue = (1f - t / 150f).coerceIn(0f, 1f)
                if (t >= 150f) break
            }
            finish()
            return@LaunchedEffect
        }
        val t0 = withFrameNanos { it }
        var hit = false
        while (!run.finished) {
            val ms = (withFrameNanos { it } - t0) / 1_000_000f
            run.ms.floatValue = ms
            run.amb.floatValue = ms / 1000f
            if (!hit && ms >= marks.hit) {
                hit = true
                // From the frame loop, never from draw; the system's own haptic setting still applies.
                if (IntroConst.HAPTIC) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            if (run.exitAt < 0f && ms >= marks.e) {
                run.exitAt = marks.e
                run.exitDur = marks.total - marks.e
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
                run.exitAt = run.ms.floatValue
                run.exitDur = 280f
                opaque(false)
            }
        }
    }
    val skipLabel = stringResource(R.string.intro_skip)
    val phase by remember { derivedStateOf { marks.phase(run.ms.floatValue) } }
    val density = LocalDensity.current
    val insetTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val insetBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val words = introWords(intro)
    val measurer = rememberTextMeasurer(cacheSize = 0)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .testTag(INTRO_TAG)
            .pointerInput(intro.skippable) { detectTapGestures(onTap = { tap(it) }) }
            .semantics { if (intro.skippable) onClick(skipLabel) { tap(Offset(-1f, -1f)); true } }
            .graphicsLayer {
                val ms = run.ms.floatValue
                val exitK = if (run.exitAt >= 0f) ((ms - run.exitAt) / run.exitDur).coerceIn(0f, 1f) else 0f
                translationY = if (level == EffectLevel.FULL) nudge(ms - marks.hit) * density.density else 0f
                if (level == EffectLevel.SAVER && run.exitAt >= 0f) {
                    val k = IntroEase.CubicIn.transform(((ms - (run.exitAt + run.exitDur - 240f)) / 240f).coerceIn(0f, 1f))
                    scaleX = 1f + .03f * k
                    scaleY = scaleX
                    alpha = 1f - k
                } else {
                    alpha = run.fade.floatValue * (1f - ((exitK - .7f) / .3f).coerceIn(0f, 1f))
                }
                compositingStrategy = if (run.exitAt >= 0f && level != EffectLevel.SAVER) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithCache {
                val sakura = sakuraSilhouette()
                val scratch = Path()
                val matrix = Matrix()
                val sparkle = Kawaii.sparklePath()
                val dp = density.density
                onDrawWithContent {
                    drawContent()
                    val exitAt = run.exitAt
                    if (exitAt < 0f || level == EffectLevel.SAVER) return@onDrawWithContent
                    val ms = run.ms.floatValue
                    val k = ((ms - exitAt) / run.exitDur).coerceIn(0f, 1f)
                    val o = run.origin?.takeIf { it.x >= 0f } ?: run.sceneOrigin ?: Offset(size.width / 2f, .30f * size.height)
                    val far = maxOf(hypot(o.x, o.y), hypot(size.width - o.x, o.y), hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y))
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
                    drawPath(scratch, Sakura.White.copy(alpha = .22f), style = Stroke(10f * dp))
                    drawPath(scratch, Sakura.White.copy(alpha = .95f), style = Stroke(2f * dp))
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
        val look = LocalLook.current
        val pic = run.picture
        val scene = remember(w, h, density, insetTop, insetBottom, words, level, pic, fx, reducedMotion, intro.portrait, intro.skippable, look.align) {
            val lay = IntroLayout(w, h, density.density, insetTop, insetBottom)
            val mm = IntroMeasure(measurer, density.density, density.fontScale, lay.s)
            val texts = IntroTexts(mm, lay, words, compact = marks.compact)
            IntroScene(
                lay, marks, level, pic, intro.portrait, .5f, (1f + look.align.bias) / 2f, windowFocus(look.align), fx, reducedMotion, texts,
                intro.skippable,
            )
        }
        run.sceneOrigin = scene.exitOrigin()
        val ms = { run.ms.floatValue }
        val amb = { if (reducedMotion) 0f else run.amb.floatValue }
        val drift = TransformOrigin(scene.exitOrigin().x / w, scene.exitOrigin().y / h)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val t = run.ms.floatValue
                val k = if (reducedMotion) 0f else IntroEase.SineInOut.transform(((t - marks.d) / (marks.e - marks.d)).coerceIn(0f, 1f))
                scaleX = 1f + .02f * k
                scaleY = scaleX
                transformOrigin = drift
            },
        ) {
            val place = remember(scene) { Placement() }
            Spacer(Modifier.fillMaxSize().graphicsLayer { }.drawBehind { drawField(scene, ms(), amb(), place) })
            Spacer(Modifier.fillMaxSize().graphicsLayer { }.drawBehind { drawChrome(scene, ms(), amb()) })
            // L7–L8: the sigil and its beat effects. Over the user's picture it dims to .35 from the hit, so the face reads as
            // soon as the opening circle shows it.
            Spacer(
                Modifier.fillMaxSize().graphicsLayer { alpha = scene.sigilAlpha(run.ms.floatValue) }.drawWithCache {
                    val ring = recordRing(scene, obtainGraphicsLayer())
                    val pose = SigilPose()
                    onDrawBehind {
                        val t = ms()
                        scene.pose(t, amb(), pose)
                        drawSigil(scene, t, amb(), pose, ring)
                        if (!reducedMotion) drawSigilEffects(scene, t)
                    }
                },
            )
            // The hit's shock rings, speed lines and ribbons: over the sigil, under the crest (a ribbon across the crest read
            // as a 'no entry' slash). Over the user's picture they dim to .35 from the hit, so no ribbon whips across the face.
            if (!reducedMotion) {
                Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = scene.hitFxAlpha(run.ms.floatValue) }.drawBehind { drawHitFx(scene, ms()) })
            }
            Spacer(
                Modifier.fillMaxSize().graphicsLayer { }.drawBehind {
                    val t = ms()
                    scene.parts.drawAmbient(this, t, amb(), run.exitAt, run.exitDur)
                    scene.parts.drawBurst(this, t)
                },
            )
            // L10: the window, which becomes the crest, and the opening circle's rim.
            Spacer(Modifier.fillMaxSize().graphicsLayer { }.drawBehind { drawWindow(scene, ms(), amb()); drawIrisRim(scene, ms()) })
        }
        // L12: the one flash, over the sky, the sigil and the crest but under the type, so it bursts out of the sigil as white
        // light behind a sharp 核驗通過 (over the type it greyed the slam to about 2.8:1 for 150 ms).
        if (!reducedMotion) {
            val peak = if (level == EffectLevel.SAVER) .35f else .70f
            Spacer(Modifier.fillMaxSize().graphicsLayer { }.drawBehind {
                val f = flash(ms(), marks.hit, peak)
                if (f > 0f) drawRect(scene.flashBrush, alpha = f)
            })
        }
        // L11: the type. On exit the HUD (brand, date, skip) fades first and the welcome block right after, both within 120 ms,
        // so the iris wipes only the floor circle and petals: cut by its edge, the date sat on the app's own buttons.
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = typeAlpha(run, 0f) }.drawBehind { drawHud(scene, ms()) })
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = typeAlpha(run, 40f) }.drawBehind { drawBeats(scene, ms()); drawWelcome(scene, ms()) })
        // The name has no live blur: each glyph stamps in as one opaque sticker, see drawName.
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = typeAlpha(run, 40f) }.drawBehind { drawName(scene, ms()) })
        IntroSemantics(scene, intro, words, phase, reducedMotion, skipLabel)
    }
}
/** A type layer's alpha on exit: 1, then down to 0 over 80 ms from [delay] ms after the exit starts. */
private fun typeAlpha(run: IntroRun, delay: Float): Float =
    if (run.exitAt < 0f) 1f else 1f - ((run.ms.floatValue - run.exitAt - delay) / 80f).coerceIn(0f, 1f)

/**
 * The part of the picture the identity window frames, from the picture's alignment: a typical full-body picture has the face in
 * its upper third, so the centred default looks at 35 % of its height, not the middle.
 */
private fun windowFocus(align: PictureAlign): Float = when (align) {
    PictureAlign.TOP -> .25f
    PictureAlign.CENTER -> .35f
    PictureAlign.BOTTOM -> .65f
}

/** The data ring's text written once round its circle into a layer, which the sigil then turns as a whole. */
private fun androidx.compose.ui.draw.CacheDrawScope.recordRing(sc: IntroScene, layer: GraphicsLayer): GraphicsLayer {
    val run = sc.texts.ring
    val R = sc.lay.r
    val rr = .955f * R
    val side = (2.1f * R).roundToInt()
    val circ = 2f * PI.toFloat() * rr
    val one = run.x.lastOrNull()?.let { it + run.advance.last() } ?: 0f
    val copies = max(1, (circ / (one * 1.15f).coerceAtLeast(1f)).toInt())
    val track = (circ - copies * one) / (copies * run.size).coerceAtLeast(1)
    layer.record(this, layoutDirection, IntSize(side, side)) {
        val c = Offset(side / 2f, side / 2f)
        var at = 0f
        for (j in 0 until copies) for (i in 0 until run.size) {
            val mid = at + run.advance[i] / 2f
            val deg = mid / rr * (180f / PI.toFloat())
            withTransform({ rotate(deg, c) }) {
                drawGlyph(run, i, Offset(c.x - run.advance[i] / 2f, c.y - rr - run.baseline), Sakura.TextSecondary, 1f)
            }
            at += run.advance[i] + track
        }
    }
    return layer
}

/** The words of a run, resolved from the user's settings and the app's defaults. */
@Composable
private fun introWords(intro: Intro): IntroWords {
    val default = stringResource(R.string.intro_welcome_default)
    val number = intro.number.trim().ifEmpty { stringResource(R.string.intro_number_default) }
    val level = intro.level.trim().ifEmpty { stringResource(R.string.intro_level_default) }
    val permission = stringResource(R.string.intro_permission)
    val checking = stringResource(R.string.intro_checking)
    val passed = stringResource(R.string.intro_passed)
    val subs = listOf(stringResource(R.string.intro_permission_sub), stringResource(R.string.intro_checking_sub), stringResource(R.string.intro_passed_sub))
    val skip = stringResource(R.string.intro_skip)
    val hudSub = stringResource(R.string.intro_hud_sub)
    val now = remember { Date() }
    return remember(intro, default, number, level) {
        IntroWords(
            permission, checking, passed, subs[0], subs[1], subs[2],
            words = intro.welcome.trim().ifEmpty { default }, wordsAreDefault = intro.welcome.isBlank(),
            name = intro.name.trim(), number = number, level = level, skip = skip, hudSub = hudSub,
            date = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(now), time = SimpleDateFormat("HH:mm", Locale.ROOT).format(now),
        )
    }
}

/**
 * What a screen reader (and the tests) read: the current beat's line, the ID and level as two plain nodes, the skip button, and
 * the welcome line as the run's only live announcement.
 */
@Composable
private fun IntroSemantics(sc: IntroScene, intro: Intro, words: IntroWords, phase: IntroPhase, reducedMotion: Boolean, skipLabel: String) {
    val L = sc.lay
    val beat = when (phase.step) {
        IntroStep.PERMISSION -> words.permission
        IntroStep.CHECKING -> words.checking
        IntroStep.PASSED -> words.passed
        IntroStep.WELCOME -> null
    }
    val d = LocalDensity.current
    fun Modifier.at(x: Float, y: Float, w: Float, h: Float) = this
        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        .size(with(d) { w.coerceAtLeast(1f).toDp() }, with(d) { h.coerceAtLeast(1f).toDp() })
    if (beat != null && !reducedMotion) {
        Box(Modifier.at(L.colLeft, L.heroTop, L.colWidth, sc.texts.heroes[0].height).semantics { text = AnnotatedString(beat) })
    }
    Box(Modifier.at(L.m, L.hudBottomTop, 60f * L.dp, 16f * L.dp).semantics { text = AnnotatedString(words.number) })
    Box(Modifier.at(L.m + 60f * L.dp, L.hudBottomTop, 40f * L.dp, 16f * L.dp).semantics { text = AnnotatedString(words.level) })
    if (intro.skippable) {
        Box(Modifier.at(L.w - L.m - 90f * L.dp, L.hudBottomTop, 90f * L.dp, 20f * L.dp).semantics { text = AnnotatedString(skipLabel); role = Role.Button })
    }
    if (phase >= IntroPhase.WELCOME || reducedMotion) {
        val line = IntroTimeline.welcome(intro, words.words)
        Box(
            Modifier.at(L.welcomeCenterX - L.colWidth / 2f, sc.texts.nameTop, L.colWidth, sc.texts.nameHeight)
                .semantics { text = AnnotatedString(line); liveRegion = LiveRegionMode.Polite },
        )
    }
}

const val INTRO_TAG = "startup-intro"
