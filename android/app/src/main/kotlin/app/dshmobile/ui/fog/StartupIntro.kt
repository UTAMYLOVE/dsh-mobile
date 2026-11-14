package app.dshmobile.ui.fog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The four steps of the startup animation, by how far through it is (0..1). */
enum class IntroStep { PERMISSION, CHECKING, PASSED, WELCOME }

object IntroTimeline {
    private val starts = floatArrayOf(0f, .22f, .5f, .68f, 1f)

    fun step(progress: Float): IntroStep = when {
        progress < starts[1] -> IntroStep.PERMISSION
        progress < starts[2] -> IntroStep.CHECKING
        progress < starts[3] -> IntroStep.PASSED
        else -> IntroStep.WELCOME
    }

    /** How far through its own step the animation is (0..1). */
    fun local(progress: Float): Float {
        val i = step(progress).ordinal
        return ((progress - starts[i]) / (starts[i + 1] - starts[i])).coerceIn(0f, 1f)
    }

    /** The welcome line: the user's own words (or the default) and the name when there is one. */
    fun welcome(intro: Intro, fallback: String): String {
        val words = intro.welcome.trim().ifEmpty { fallback }
        val name = intro.name.trim()
        return if (name.isEmpty()) words else "$words，$name"
    }
}

private fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) * (1f - t) }

/**
 * The optional startup animation in the 粉嫩二次元 style (the owner's idea after HALOFLUX's authorisation screen, and 「把開場做的更
 * 精緻」): a glass card over the user's picture (blurred, when there is one) and drifting petals, a badge with turning rings and its
 * own drawn icons (a lock, a scanning frame, a check that draws itself, then the portrait or a heart), typed titles, a 核驗通過
 * stamp with a burst of hearts, and four step dots. dsh keeps starting underneath. A tap (and back) ends it when
 * [Intro.skippable]; with [reducedMotion] only the welcome card shows, still. [onDone] is called once. Only the six colours.
 */
@Composable
fun StartupIntro(intro: Intro, picture: ImageBitmap?, reducedMotion: Boolean, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val finished = remember { mutableStateOf(false) }
    val finish = { if (!finished.value) { finished.value = true; done() } }
    val progress = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val totalMs = (intro.seconds.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS) * 1000).toInt()
    LaunchedEffect(Unit) {
        if (reducedMotion) kotlinx.coroutines.delay(totalMs.toLong()) else progress.animateTo(1f, tween(totalMs, easing = LinearEasing))
        finish()
    }
    BackHandler(enabled = intro.skippable) { finish() }
    val p = progress.value
    val step = IntroTimeline.step(p)
    val local = if (reducedMotion) 1f else IntroTimeline.local(p)
    // Each step's title types itself in over its first 0.45 s (or its first 40%, when the steps are short).
    val stepMs = totalMs * when (step) { IntroStep.PERMISSION -> .22f; IntroStep.CHECKING -> .28f; IntroStep.PASSED -> .18f; IntroStep.WELCOME -> .32f }
    val typed = if (reducedMotion) 1f else (local * stepMs / minOf(450f, stepMs * .4f)).coerceIn(0f, 1f)
    val fade = if (reducedMotion) 1f else minOf(1f, p / .05f, (1f - p) / .05f + .0001f).coerceIn(0f, 1f)
    val clock = rememberFogClock(running = !reducedMotion, idle = remember { mutableStateOf(false) })
    val particles = remember { Kawaii.particles(Look(style = BackdropStyle.KAWAII, amount = 45, clouds = false)) }

    Box(
        Modifier
            .fillMaxSize()
            .testTag(INTRO_TAG)
            .graphicsLayer { alpha = fade }
            .background(Brush.verticalGradient(listOf(Sakura.Background, Sakura.White, Sakura.Background)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = intro.skippable) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(
                picture, null, contentScale = ContentScale.Crop, alpha = .45f,
                modifier = Modifier.fillMaxSize().blur(18.dp),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Sakura.Background.copy(alpha = .55f), Sakura.White.copy(alpha = .35f), Sakura.Background.copy(alpha = .6f)))))
        }
        Glows(clock)
        Rings(clock)
        Box(Modifier.fillMaxSize().kawaii(clock, particles, Kawaii.pace(45)))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Card(intro, picture, step, local, typed, p, clock)
            Spacer(Modifier.height(22.dp))
            Text(
                "DSH  MOBILE", style = FogText.Label.copy(fontFamily = FogFonts.Orbitron, fontSize = 10.sp, letterSpacing = .4.em, color = Sakura.RoseBrown),
            )
        }
        if (intro.skippable) {
            Text(
                stringResource(R.string.intro_skip), color = Sakura.TextSecondary, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
            )
        }
    }
}

/** Two soft glows (pink above, aqua below) that breathe slowly. */
@Composable
private fun Glows(clock: State<Float>) {
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        val b = .5f + .5f * sin(t * .9f)
        val r1 = size.minDimension * (.62f + .06f * b)
        drawCircle(Brush.radialGradient(listOf(Sakura.Primary.copy(alpha = .38f), Color.Transparent), Offset(size.width * .78f, size.height * .2f), r1), r1, Offset(size.width * .78f, size.height * .2f))
        val r2 = size.minDimension * (.58f + .06f * (1f - b))
        drawCircle(Brush.radialGradient(listOf(Sakura.Aqua.copy(alpha = .4f), Color.Transparent), Offset(size.width * .2f, size.height * .82f), r2), r2, Offset(size.width * .2f, size.height * .82f))
    }
}

/** Faint rings around the card's badge, turning slowly: depth behind the glass. */
@Composable
private fun Rings(clock: State<Float>) {
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        val c = Offset(size.width / 2f, size.height / 2f - 118.dp.toPx())
        drawCircle(Sakura.White.copy(alpha = .7f), 128.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        rotate(t * 6f, c) {
            drawCircle(Sakura.Primary.copy(alpha = .4f), 176.dp.toPx(), c, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 9.dp.toPx())), cap = StrokeCap.Round))
        }
        rotate(-t * 4f, c) {
            drawCircle(Sakura.Aqua.copy(alpha = .45f), 232.dp.toPx(), c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(28.dp.toPx(), 14.dp.toPx()))))
            for (i in 0 until 3) {
                val a = i * 2f * PI.toFloat() / 3f
                drawCircle(Sakura.White, 3.dp.toPx(), Offset(c.x + cos(a) * 232.dp.toPx(), c.y + sin(a) * 232.dp.toPx()))
            }
        }
        drawCircle(Sakura.White.copy(alpha = .5f), 300.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun Card(intro: Intro, picture: ImageBitmap?, step: IntroStep, local: Float, typed: Float, p: Float, clock: State<Float>) {
    val shape = RoundedCornerShape(32.dp)
    Box {
        Column(
            Modifier
                .width(304.dp)
                .shadow(28.dp, shape, ambientColor = Sakura.Primary, spotColor = Sakura.Primary)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Sakura.White.copy(alpha = .94f), Sakura.Background.copy(alpha = .9f))))
                .border(1.5.dp, Brush.linearGradient(listOf(Sakura.Primary, Sakura.White, Sakura.Aqua)), shape)
                .drawWithContent {
                    drawContent()
                    // A sweep of light across the glass as each step begins.
                    val k = (local / .45f).coerceIn(0f, 1f)
                    if (k in 0.001f..0.999f) {
                        val x = -size.width * .5f + size.width * 2f * k
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, Sakura.White.copy(alpha = .55f), Color.Transparent), Offset(x, 0f), Offset(x + size.width * .35f, size.height)))
                    }
                }
                .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A small ribbon: ✦ AUTHORIZATION ✦
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sparkle(Sakura.Aqua, 9.dp)
                Text("AUTHORIZATION", style = FogText.Label.copy(fontFamily = FogFonts.Orbitron, fontSize = 10.sp, letterSpacing = .3.em, color = Sakura.TextSecondary))
                Sparkle(Sakura.Primary, 9.dp)
            }
            Spacer(Modifier.height(16.dp))
            Badge(step, local, picture.takeIf { intro.portrait }, clock)
            Spacer(Modifier.height(18.dp))
            val welcome = IntroTimeline.welcome(intro, stringResource(R.string.intro_welcome_default))
            val number = intro.number.trim().ifEmpty { stringResource(R.string.intro_number_default) }
            val level = intro.level.trim().ifEmpty { stringResource(R.string.intro_level_default) }
            val title = when (step) {
                IntroStep.PERMISSION -> stringResource(R.string.intro_permission)
                IntroStep.CHECKING -> stringResource(R.string.intro_checking)
                IntroStep.PASSED -> stringResource(R.string.intro_passed)
                IntroStep.WELCOME -> welcome
            }
            Typed(title, typed, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(6.dp))
            Text(
                when (step) {
                    IntroStep.PERMISSION -> stringResource(R.string.intro_permission_sub)
                    IntroStep.CHECKING -> stringResource(R.string.intro_checking_sub)
                    IntroStep.PASSED -> stringResource(R.string.intro_passed_sub)
                    IntroStep.WELCOME -> "♡"
                },
                color = Sakura.TextSecondary, fontSize = 12.sp, letterSpacing = .08.em,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.graphicsLayer { alpha = if (step == IntroStep.PERMISSION) 0f else 1f }) {
                Chip(number, Sakura.Aqua)
                Chip(level, Sakura.Primary)
            }
            Spacer(Modifier.height(18.dp))
            Steps(step, local)
        }
        val twinkle = { phase: Float -> .35f + .65f * (.5f + .5f * sin(clock.value * 2.2f + phase)) }
        Sparkle(Sakura.Primary, 12.dp, Modifier.align(Alignment.TopStart).padding(14.dp).graphicsLayer { alpha = twinkle(0f) })
        Sparkle(Sakura.Aqua, 10.dp, Modifier.align(Alignment.BottomStart).padding(16.dp).graphicsLayer { alpha = twinkle(2f) })
        Sparkle(Sakura.Primary, 10.dp, Modifier.align(Alignment.BottomEnd).padding(16.dp).graphicsLayer { alpha = twinkle(4f) })
        if (step == IntroStep.PASSED || step == IntroStep.WELCOME) Stamp(if (step == IntroStep.PASSED) local else 1f, Modifier.align(Alignment.TopEnd))
    }
}

/** The title with its not-yet-typed part transparent: the line keeps its width and screen readers get it whole. */
@Composable
private fun Typed(text: String, typed: Float, modifier: Modifier) {
    val shown = (text.length * typed + .999f).toInt().coerceIn(0, text.length)
    Text(
        buildAnnotatedString {
            append(text.substring(0, shown))
            withStyle(SpanStyle(color = Color.Transparent)) { append(text.substring(shown)) }
        },
        color = Sakura.Text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, letterSpacing = .04.em,
        modifier = modifier,
    )
}

/** The round badge: a halo, two turning rings, and the step's own icon (or the portrait to welcome). */
@Composable
private fun Badge(step: IntroStep, local: Float, picture: ImageBitmap?, clock: State<Float>) {
    val pop = when (step) {
        IntroStep.PASSED -> 1f + .22f * (1f - ease(local / .35f))
        else -> 1f
    }
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val c = center
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(Sakura.Primary.copy(alpha = .45f), Color.Transparent), c, r), r, c)
            // Outer dashed ring (aqua) turning one way, inner dotted ring (pink) the other.
            rotate(t * 24f, c) {
                drawCircle(Sakura.Aqua, r * .86f, c, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())), cap = StrokeCap.Round))
            }
            rotate(-t * 40f, c) {
                drawCircle(Sakura.Primary, r * .76f, c, style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(.1f, 9.dp.toPx())), cap = StrokeCap.Round))
                // Two little orbiting dots.
                drawCircle(Sakura.White, 3.5.dp.toPx(), Offset(c.x + r * .76f, c.y))
                drawCircle(Sakura.Aqua, 3.dp.toPx(), Offset(c.x - r * .76f, c.y))
            }
            // The burst of hearts and sparkles when 核驗通過 lands.
            if (step == IntroStep.PASSED) burst(local, c, r)
        }
        Box(
            Modifier
                .size(94.dp)
                .graphicsLayer { scaleX = pop; scaleY = pop }
                .shadow(10.dp, CircleShape, ambientColor = Sakura.Primary, spotColor = Sakura.Primary)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Sakura.Background, Sakura.Primary)))
                .border(3.dp, Sakura.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (step == IntroStep.WELCOME && picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    when (step) {
                        IntroStep.PERMISSION -> lock(local)
                        IntroStep.CHECKING -> scan(local, clock.value)
                        IntroStep.PASSED -> check(ease(local / .5f))
                        IntroStep.WELCOME -> heart(clock.value)
                    }
                }
            }
        }
    }
}

private fun DrawScope.lock(local: Float) {
    val s = size.minDimension
    val w = s * .4f
    val h = s * .3f
    val left = (size.width - w) / 2f
    val top = size.height * .47f
    // The shackle lifts a little near the end: the lock is about to open.
    val lift = s * .05f * ease((local - .6f) / .4f)
    drawArc(Sakura.White, 180f, 180f, false, Offset(left + w * .17f, top - h * .72f - lift), Size(w * .66f, h * 1.2f), style = Stroke(s * .055f, cap = StrokeCap.Round))
    drawRoundRect(Sakura.White, Offset(left, top), Size(w, h), androidx.compose.ui.geometry.CornerRadius(s * .06f))
    drawCircle(Sakura.Primary, s * .035f, Offset(size.width / 2f, top + h * .42f))
    drawLine(Sakura.Primary, Offset(size.width / 2f, top + h * .42f), Offset(size.width / 2f, top + h * .7f), s * .03f, StrokeCap.Round)
}

private fun DrawScope.scan(local: Float, t: Float) {
    val s = size.minDimension
    val a = s * .24f
    val k = s * .1f
    val stroke = Stroke(s * .045f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val cx = size.width / 2f
    val cy = size.height / 2f
    // Four corner brackets.
    for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val x = cx + sx * a
        val y = cy + sy * a
        drawPath(Path().apply { moveTo(x, y - sy * k); lineTo(x, y); lineTo(x - sx * k, y) }, Sakura.White, style = stroke)
    }
    // A little face-like mark in the middle, and the scanning light sweeping up and down.
    drawCircle(Sakura.White.copy(alpha = .9f), s * .07f, Offset(cx, cy - s * .04f))
    drawArc(Sakura.White.copy(alpha = .9f), 200f, 140f, false, Offset(cx - s * .12f, cy + s * .03f), Size(s * .24f, s * .2f), style = Stroke(s * .04f, cap = StrokeCap.Round))
    val y = cy + a * sin((t * 3.2f + local * 2f).toDouble()).toFloat()
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Sakura.Aqua.copy(alpha = .85f), Color.Transparent), y - s * .08f, y + s * .08f),
        Offset(cx - a - k * .3f, y - s * .08f), Size(2 * a + k * .6f, s * .16f),
    )
    drawLine(Sakura.White, Offset(cx - a, y), Offset(cx + a, y), s * .015f, StrokeCap.Round)
}

private fun DrawScope.check(drawn: Float) {
    val s = size.minDimension
    val path = Path().apply {
        moveTo(size.width * .3f, size.height * .52f)
        lineTo(size.width * .44f, size.height * .66f)
        lineTo(size.width * .71f, size.height * .38f)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * drawn.coerceIn(0f, 1f), part, true)
    drawPath(part, Sakura.White, style = Stroke(s * .08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.heart(t: Float) {
    val s = size.minDimension
    val beat = 1f + .06f * sin(t * 5f).coerceAtLeast(0f)
    withTransform({
        translate(size.width / 2f, size.height / 2f + s * .02f)
        scale(s * .27f * beat, s * .27f * beat, Offset.Zero)
    }) { drawPath(Kawaii.heartPath(), Sakura.White) }
}

/** Hearts and sparkles flying out from the badge and fading. */
private fun DrawScope.burst(local: Float, c: Offset, r: Float) {
    val k = ease(local / .7f)
    val alpha = (1f - local).coerceIn(0f, 1f)
    if (alpha <= 0f) return
    val heart = Kawaii.heartPath()
    val sparkle = Kawaii.sparklePath()
    for (i in 0 until 12) {
        val angle = (i / 12f) * 2f * PI.toFloat() + .26f
        val d = r * (.45f + .6f * k) * (if (i % 2 == 0) 1f else .82f)
        val at = Offset(c.x + cos(angle) * d, c.y + sin(angle) * d)
        val size = (if (i % 2 == 0) 7f else 5f) * density
        withTransform({ translate(at.x, at.y); scale(size, size, Offset.Zero) }) {
            drawPath(if (i % 2 == 0) heart else sparkle, (if (i % 3 == 0) Sakura.Aqua else if (i % 3 == 1) Sakura.Primary else Sakura.White).copy(alpha = alpha))
        }
    }
}

/** The 核驗通過 stamp at the card's corner: pops in big and settles, slightly turned. */
@Composable
private fun Stamp(local: Float, modifier: Modifier) {
    val k = ease(local / .3f)
    val scale = 1.8f - .8f * k
    Box(
        modifier
            .padding(top = 14.dp, end = 14.dp)
            .size(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = -14f; alpha = k }
            .border(2.dp, Sakura.Primary, CircleShape)
            .padding(4.dp)
            .border(1.dp, Sakura.Primary.copy(alpha = .6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.intro_stamp), color = Sakura.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp, textAlign = TextAlign.Center)
    }
}

/** Four dots joined by a line: done ones filled, the current one ringed, the rest waiting. */
@Composable
private fun Steps(step: IntroStep, local: Float) {
    val labels = listOf(
        stringResource(R.string.intro_step_permission), stringResource(R.string.intro_step_checking),
        stringResource(R.string.intro_step_passed), stringResource(R.string.intro_step_welcome),
    )
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val gap = size.width / 4f
            val y = size.height / 2f
            val now = step.ordinal + local
            drawLine(Sakura.MilkTea, Offset(gap / 2f, y), Offset(size.width - gap / 2f, y), 2.dp.toPx(), StrokeCap.Round)
            drawLine(
                Brush.horizontalGradient(listOf(Sakura.Aqua, Sakura.Primary)), Offset(gap / 2f, y),
                Offset(gap / 2f + gap * now.coerceAtMost(3f), y), 2.5.dp.toPx(), StrokeCap.Round,
            )
            for (i in 0..3) {
                val x = gap / 2f + gap * i
                when {
                    i < step.ordinal || (i == 3 && step == IntroStep.WELCOME && local >= 1f) -> drawCircle(Sakura.Primary, 5.dp.toPx(), Offset(x, y))
                    i == step.ordinal -> {
                        drawCircle(Sakura.White, 6.dp.toPx(), Offset(x, y))
                        drawCircle(Sakura.Primary, 6.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
                        drawCircle(Sakura.Primary, 2.5.dp.toPx(), Offset(x, y))
                    }
                    else -> drawCircle(Sakura.MilkTea, 4.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEachIndexed { i, label ->
                Text(
                    label, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp,
                    color = if (i <= step.ordinal) Sakura.Text else Sakura.TextSecondary.copy(alpha = .6f),
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, accent: Color) {
    Row(
        Modifier.clip(CircleShape).background(Sakura.White).border(1.dp, accent, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Text(text, style = FogText.Number.copy(fontSize = 13.sp, color = Sakura.TextSecondary))
    }
}

@Composable
private fun Sparkle(color: Color, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        withTransform({ translate(this.size.width / 2f, this.size.height / 2f); scale(this.size.width / 2f, this.size.height / 2f, Offset.Zero) }) {
            drawPath(Kawaii.sparklePath(), color)
        }
    }
}

const val INTRO_TAG = "startup-intro"
