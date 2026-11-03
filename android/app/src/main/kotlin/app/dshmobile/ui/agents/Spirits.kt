package app.dshmobile.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Mood
import app.dshmobile.agents.Prop
import app.dshmobile.agents.Stage
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.LocalPageActive
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Spirit colour per state (§H.2): aqua running, pink waiting, milk tea done, rose-brown error, white-ish idle. */
internal fun moodColor(m: Mood): Color = when (m) {
    Mood.RUNNING -> Sakura.Aqua
    Mood.WAITING -> Sakura.Primary
    Mood.DONE -> Sakura.MilkTea
    Mood.ERROR -> Sakura.RoseBrown
    Mood.IDLE -> Color(0xFFF3E6E2)
}

/** At most this many spirits fly; the rest are only in the cards (performance cap, §E). */
const val MAX_SPIRITS = 12

/**
 * The LIVE FLOW (§H.1) with the agents as floating spirits (§H.2): six glowing stage nodes on a dotted path, each spirit hovering at
 * the stage of what it does, with its face for its state and a prop for its tool. Spirits glide between nodes, bob and pulse only
 * while effects allow ambient motion and the page shows (SAVER and reduced motion draw one still frame). Tap a spirit: [onTap].
 * Everything is drawn in one Canvas; the frame clock is read in the draw phase, so nothing recomposes per frame.
 */
@Composable
fun SpiritFlow(agents: List<AgentStatus>, onTap: (AgentStatus) -> Unit, modifier: Modifier = Modifier, height: Dp = 210.dp, compact: Boolean = false) {
    val animate = LocalEffects.current.ambient && LocalPageActive.current
    val shown = agents.take(MAX_SPIRITS)
    val time = remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = androidx.compose.animation.core.withInfiniteAnimationFrameMillis { it }
            while (true) androidx.compose.animation.core.withInfiniteAnimationFrameMillis { time.longValue = it - start }
        }
    }
    // Where each spirit is drawn now; it eases toward its stage node every frame (no state writes: plain map).
    val positions = remember { HashMap<String, Offset>() }
    val measurer = rememberTextMeasurer()
    val tap by rememberUpdatedState(onTap)
    val current by rememberUpdatedState(shown)
    val hits = remember { HashMap<String, Rect>() }
    val summary = shown.joinToString("；") { "${it.label}：${it.action ?: it.stage.label}" }
    Canvas(
        modifier.fillMaxWidth().height(height).testTag(Tags.WAR_FLOW)
            .semantics { contentDescription = summary.ifEmpty { "沒有代理人" } }
            .pointerInput(Unit) {
                detectTapGestures { p -> current.firstOrNull { a -> hits[a.id]?.contains(p) == true }?.let { tap(it) } }
            },
    ) {
        val t = time.longValue
        val nodes = stageNodes(size, compact)
        drawPipeline(nodes, t, animate, measurer, compact)
        val perStage = HashMap<Stage, Int>()
        val r = if (compact) 13.dp.toPx() else 17.dp.toPx()
        hits.clear()
        for ((i, a) in shown.withIndex()) {
            val slot = perStage.merge(a.stage, 1, Int::plus)!! - 1
            val node = nodes.getValue(a.stage)
            val ring = slot % 4
            val target = node + Offset((ring - 1.5f) * r * 1.6f * (if (slot == 0) 0f else 1f), -r * 2.1f - (slot / 4) * r * 2.2f)
            val prev = positions[a.id] ?: if (a.parentId != null) positions[a.parentId] ?: target else target
            val now = if (animate) prev + (target - prev) * 0.08f else target
            positions[a.id] = now
            val phase = i * 1.7f
            val bob = if (animate) sin(t / 520f + phase) * r * 0.18f else 0f
            val hop = if (animate && a.mood == Mood.DONE) (-kotlin.math.abs(sin(t / 300f + phase)) * r * 0.25f) else 0f
            val c = now + Offset(0f, bob + hop)
            drawSpirit(c, r, a, t, animate, phase)
            hits[a.id] = Rect(c - Offset(r * 1.6f, r * 1.6f), Size(r * 3.2f, r * 3.6f))
            if (!compact || shown.size <= 3) {
                val text = measurer.measure(
                    a.label.take(8), TextStyle(fontFamily = FogFonts.ChakraPetch, fontSize = 9.sp, color = Sakura.Text),
                    overflow = TextOverflow.Ellipsis, maxLines = 1,
                )
                drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y + r * 1.25f))
            }
        }
        positions.keys.retainAll(shown.mapTo(HashSet()) { it.id })
    }
}

private fun DrawScope.stageNodes(size: Size, compact: Boolean): Map<Stage, Offset> {
    val stages = Stage.entries
    val padX = size.width * 0.08f
    val baseY = size.height * (if (compact) 0.80f else 0.82f)
    return stages.withIndex().associate { (i, s) ->
        val x = padX + (size.width - 2 * padX) * i / (stages.size - 1)
        val wave = sin(i / (stages.size - 1f) * PI.toFloat()) * size.height * 0.10f
        s to Offset(x, baseY - wave)
    }
}

private fun DrawScope.drawPipeline(
    nodes: Map<Stage, Offset>, t: Long, animate: Boolean, measurer: androidx.compose.ui.text.TextMeasurer, compact: Boolean,
) {
    val pts = Stage.entries.map { nodes.getValue(it) }
    val path = Path().apply {
        moveTo(pts.first().x, pts.first().y)
        for (k in 1 until pts.size) {
            val a = pts[k - 1]; val b = pts[k]
            quadraticTo((a.x + b.x) / 2, (a.y + b.y) / 2 - 6.dp.toPx(), b.x, b.y)
        }
    }
    val dash = 4.dp.toPx()
    drawPath(
        path, Sakura.Primary.copy(alpha = .7f),
        style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), if (animate) -(t / 40f) % (2 * dash) else 0f)),
    )
    for ((s, p) in nodes) {
        val glow = if (animate) 0.5f + 0.5f * sin(t / 700f + s.ordinal) else 0.6f
        drawCircle(Brush.radialGradient(listOf(Sakura.Aqua.copy(alpha = .45f * glow), Color.Transparent), p, 14.dp.toPx()), 14.dp.toPx(), p)
        drawCircle(Sakura.White, 4.dp.toPx(), p)
        drawCircle(Sakura.Primary, 4.dp.toPx(), p, style = Stroke(1.dp.toPx()))
        val label = measurer.measure(
            if (compact) s.label else "${s.label} ${s.en}",
            TextStyle(fontFamily = FogFonts.ChakraPetch, fontSize = if (compact) 8.sp else 9.sp, color = Sakura.TextSecondary),
        )
        drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y + 7.dp.toPx()))
    }
}

/** One spirit: a soft round body with a glowing tail, a face for its mood and a prop for its tool. */
private fun DrawScope.drawSpirit(c: Offset, r: Float, a: AgentStatus, t: Long, animate: Boolean, phase: Float) {
    val color = moodColor(a.mood)
    val blink = a.mood == Mood.ERROR && animate && (t / 400) % 2 == 0L
    val body = if (blink) Sakura.RoseBrown.copy(alpha = .55f) else color
    // Halo, then a tail of fading puffs that sways.
    drawCircle(Brush.radialGradient(listOf(body.copy(alpha = .45f), Color.Transparent), c, r * 2.0f), r * 2.0f, c)
    for (k in 1..4) {
        val sway = if (animate) sin(t / 260f + phase + k) * r * 0.22f * k / 4f else r * 0.1f * k / 4f
        drawCircle(body.copy(alpha = .55f - k * .1f), r * (0.62f - k * 0.1f), c + Offset(sway, r * (0.55f + k * 0.38f)))
    }
    drawCircle(Brush.radialGradient(listOf(Sakura.White, body), c - Offset(r * .3f, r * .35f), r * 1.3f), r, c)
    drawCircle(Sakura.White.copy(alpha = .8f), r, c, style = Stroke(1.dp.toPx()))
    // Cheeks.
    drawCircle(Sakura.Primary.copy(alpha = .45f), r * .14f, c + Offset(-r * .5f, r * .22f))
    drawCircle(Sakura.Primary.copy(alpha = .45f), r * .14f, c + Offset(r * .5f, r * .22f))
    val ink = Sakura.Text
    val eyeL = c + Offset(-r * .32f, -r * .06f)
    val eyeR = c + Offset(r * .32f, -r * .06f)
    val w = 1.6.dp.toPx()
    when (a.mood) {
        Mood.RUNNING -> { // focused: flat determined eyes
            drawLine(ink, eyeL - Offset(r * .14f, 0f), eyeL + Offset(r * .14f, 0f), w * 1.3f, StrokeCap.Round)
            drawLine(ink, eyeR - Offset(r * .14f, 0f), eyeR + Offset(r * .14f, 0f), w * 1.3f, StrokeCap.Round)
            drawLine(ink, c + Offset(-r * .1f, r * .32f), c + Offset(r * .1f, r * .32f), w, StrokeCap.Round)
        }
        Mood.WAITING -> { // looking up at you
            drawCircle(ink, r * .13f, eyeL); drawCircle(ink, r * .13f, eyeR)
            drawCircle(Sakura.White, r * .05f, eyeL - Offset(0f, r * .06f)); drawCircle(Sakura.White, r * .05f, eyeR - Offset(0f, r * .06f))
            drawCircle(ink, r * .07f, c + Offset(0f, r * .34f), style = Stroke(w))
        }
        Mood.ERROR -> { // x eyes and a sweat drop
            for (e in listOf(eyeL, eyeR)) {
                drawLine(ink, e - Offset(r * .1f, r * .1f), e + Offset(r * .1f, r * .1f), w, StrokeCap.Round)
                drawLine(ink, e + Offset(-r * .1f, r * .1f), e + Offset(r * .1f, -r * .1f), w, StrokeCap.Round)
            }
            drawArc(ink, 200f, 140f, false, topLeft = c + Offset(-r * .16f, r * .26f), size = Size(r * .32f, r * .24f), style = Stroke(w))
            drawCircle(Sakura.Aqua, r * .13f, c + Offset(r * .78f, -r * .5f))
        }
        Mood.DONE -> { // happy ^^
            for (e in listOf(eyeL, eyeR)) drawArc(ink, 200f, 140f, false, topLeft = e - Offset(r * .14f, r * .04f), size = Size(r * .28f, r * .22f), style = Stroke(w, cap = StrokeCap.Round))
            drawArc(ink, 20f, 140f, false, topLeft = c + Offset(-r * .18f, r * .14f), size = Size(r * .36f, r * .26f), style = Stroke(w, cap = StrokeCap.Round))
        }
        Mood.IDLE -> { // sleepy
            drawArc(ink, 20f, 140f, false, topLeft = eyeL - Offset(r * .13f, r * .1f), size = Size(r * .26f, r * .16f), style = Stroke(w, cap = StrokeCap.Round))
            drawArc(ink, 20f, 140f, false, topLeft = eyeR - Offset(r * .13f, r * .1f), size = Size(r * .26f, r * .16f), style = Stroke(w, cap = StrokeCap.Round))
            drawCircle(Sakura.TextSecondary, r * .08f, c + Offset(r * .9f, -r * .9f), style = Stroke(w * .7f))
        }
    }
    drawProp(c + Offset(r * 1.05f, r * .25f), r * .5f, a.prop, ink, w, t, animate)
}

private fun DrawScope.drawProp(p: Offset, s: Float, prop: Prop, ink: Color, w: Float, t: Long, animate: Boolean) {
    when (prop) {
        Prop.NONE -> Unit
        Prop.LENS -> {
            drawCircle(Sakura.White.copy(alpha = .7f), s * .55f, p)
            drawCircle(ink, s * .55f, p, style = Stroke(w))
            drawLine(ink, p + Offset(s * .4f, s * .4f), p + Offset(s, s), w * 1.4f, StrokeCap.Round)
        }
        Prop.PAGE -> {
            drawRect(Sakura.White, p - Offset(s * .5f, s * .65f), Size(s, s * 1.3f))
            drawRect(ink, p - Offset(s * .5f, s * .65f), Size(s, s * 1.3f), style = Stroke(w * .8f))
            for (k in 0..2) drawLine(ink.copy(alpha = .6f), p + Offset(-s * .3f, -s * .3f + k * s * .3f), p + Offset(s * .3f, -s * .3f + k * s * .3f), w * .6f)
        }
        Prop.PEN -> rotate(if (animate) 25f + sin(t / 150f) * 12f else 25f, p) {
            drawLine(Sakura.RoseBrown, p - Offset(0f, s * .8f), p + Offset(0f, s * .6f), w * 2.2f, StrokeCap.Round)
            drawLine(ink, p + Offset(0f, s * .6f), p + Offset(0f, s * .85f), w, StrokeCap.Round)
        }
        Prop.SPLIT -> {
            val k = if (animate) (t % 1600) / 1600f else .6f
            val q = p + Offset(s * k, -s * k)
            drawCircle(Sakura.Aqua.copy(alpha = 1f - k * .5f), s * .45f, q)
            drawCircle(Sakura.White, s * .45f, q, style = Stroke(w * .7f))
        }
    }
}

/** A small ring gauge (FUI): [fraction] of a circle in aqua with an orbiting dot, the rest a faint track. */
@Composable
fun RingGauge(fraction: Float?, modifier: Modifier = Modifier) {
    val animate = LocalEffects.current.ambient && LocalPageActive.current
    val time = remember { mutableLongStateOf(0L) }
    if (animate) LaunchedEffect(Unit) { while (true) androidx.compose.animation.core.withInfiniteAnimationFrameMillis { time.longValue = it } }
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = stroke
        val sz = Size(size.minDimension - 2 * inset, size.minDimension - 2 * inset)
        val tl = Offset(inset, inset)
        drawArc(Sakura.Primary.copy(alpha = .3f), 0f, 360f, false, tl, sz, style = Stroke(stroke))
        val f = (fraction ?: 0f).coerceIn(0f, 1f)
        drawArc(if (f >= .8f) Sakura.RoseBrown else Sakura.Aqua, -90f, 360f * f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        val a = (time.longValue / 1200.0) % (2 * PI)
        val rr = sz.width / 2
        val center = Offset(size.minDimension / 2, size.minDimension / 2)
        drawCircle(Sakura.Primary, 2.dp.toPx(), center + Offset((cos(a) * rr).toFloat(), (sin(a) * rr).toFloat()))
    }
}
