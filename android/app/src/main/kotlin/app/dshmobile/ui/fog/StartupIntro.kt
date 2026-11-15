package app.dshmobile.ui.fog

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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The optional startup animation, in the look the user picked ([Intro.style]); 「櫻紋陣」 is the default. Every look plays in
 * [IntroHost], which gives it the same clock, skip, exit iris, reduced-motion still and screen-reader behaviour. [onOpaqueChange]
 * says when the screen underneath may be hidden (true while the intro covers it); [onDone] is called exactly once. Only the six
 * colours (and #5E4445 / #7D5F60 for text).
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
    when (intro.style) {
        IntroStyle.SIGIL -> SigilIntro(intro, picture, reducedMotion, pictureExpected, fx, onOpaqueChange, onDone)
        IntroStyle.STARDUST -> StardustIntro(intro, picture, reducedMotion, pictureExpected, fx, onOpaqueChange, onDone)
        IntroStyle.PETAL_STORM -> PetalStormIntro(intro, picture, reducedMotion, pictureExpected, fx, onOpaqueChange, onDone)
        IntroStyle.TUNNEL -> TunnelIntro(intro, picture, reducedMotion, pictureExpected, fx, onOpaqueChange, onDone)
        IntroStyle.ONE_STROKE -> OneStrokeIntro(intro, picture, reducedMotion, pictureExpected, fx, onOpaqueChange, onDone)
    }
}

/**
 * 「櫻紋陣」 (Sakura Sigil), the default look (the owner, 2026-11-15: the old card 「有點醜」, look at real 二次元 boot screens).
 * Two halves: a pastel terminal wakes on a pink sky and a sakura magic circle is traced, assembled and scans *you*
 * (需要訪問許可 → 身份確認); at 核驗通過 the rosette snaps home, one flash, a haptic tick, ribbons and petals transform it, the
 * circle lies down as a glowing floor, your picture opens to the full screen (or your monogram floats up as a crest) and your
 * name stands on the circle (歡迎回來); a sakura-shaped iris opens into the app.
 *
 * Fixed punches and stretching holds ([IntroMarks]); everything is a pure function of one clock read only in draw lambdas, so a
 * run recomposes once per beat. With reduced motion one still welcome frame shows.
 */
@Composable
private fun SigilIntro(
    intro: Intro,
    picture: ImageBitmap?,
    reducedMotion: Boolean,
    pictureExpected: Boolean,
    fx: IntroFx,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val totalMs = (intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000).roundToInt()
    val marks = remember(totalMs, reducedMotion) { if (reducedMotion) IntroMarks.STILL else IntroMarks.of(totalMs) }
    val timing = remember(marks) { IntroTiming(exit = marks.e, total = marks.total, still = IntroMarks.STILL_MS, hit = marks.hit, announce = marks.d) }
    val words = introWords(intro)
    val skipLabel = stringResource(R.string.intro_skip)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val coverage = IntroFonts.coverage(LocalContext.current.resources)
    IntroHost(intro, picture, reducedMotion, pictureExpected, timing, fx, onOpaqueChange, onDone, announce = null) {
        val run = this.run
        val w = widthPx
        val h = heightPx
        val pic = this.picture
        val level = this.level
        val density = this.density
        val phase by remember(marks) { derivedStateOf { marks.phase(run.ms) } }
        val scene = remember(this, words, marks, intro.portrait, intro.skippable) {
            val lay = IntroLayout(w, h, density.density, insetTop, insetBottom)
            val mm = IntroMeasure(measurer, density.density, density.fontScale, lay.s, coverage)
            val texts = IntroTexts(mm, lay, words, compact = marks.compact)
            IntroScene(
                lay, marks, level, pic, intro.portrait, .5f, (1f + align.bias) / 2f, windowFocus(align), fx, reducedMotion, texts,
                intro.skippable,
            )
        }
        run.sceneOrigin = scene.exitOrigin()
        val ms = { run.ms }
        val amb = { if (reducedMotion) 0f else run.amb }
        val drift = TransformOrigin(scene.exitOrigin().x / w, scene.exitOrigin().y / h)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val t = run.ms
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
                Modifier.fillMaxSize().graphicsLayer { alpha = scene.sigilAlpha(run.ms) }.drawWithCache {
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
                Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = scene.hitFxAlpha(run.ms) }.drawBehind { drawHitFx(scene, ms()) })
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
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(0f) }.drawBehind { drawHud(scene, ms()) })
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(40f) }.drawBehind { drawBeats(scene, ms()); drawWelcome(scene, ms()) })
        // The name has no live blur: each glyph stamps in as one opaque sticker, see drawName.
        Spacer(Modifier.fillMaxSize().graphicsLayer { alpha = run.exitFade(40f) }.drawBehind { drawName(scene, ms()) })
        IntroSemantics(scene, intro, words, phase, reducedMotion, skipLabel)
    }
}

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
