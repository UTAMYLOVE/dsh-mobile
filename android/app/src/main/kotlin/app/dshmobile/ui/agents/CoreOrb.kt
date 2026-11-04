package app.dshmobile.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.shell.Tags
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** One data strip of the core: its name, a note, the latest value as text and the recent values (oldest first). */
data class CoreStrip(val name: String, val note: String, val value: String, val series: List<Float>, val fill: Color)

private const val W = 372f
private const val H = 330f
private const val CX = 262f
private const val CY = 165f
private const val R = 70f

/**
 * THE CORE (owner-approved A+B+C mockup, from reference 「THE ORB」): six data strips on the left, each pouring a stream of
 * particles into a rotating sphere of points (the lead), with up to four spirits drifting around it. Drawn in one Canvas in a
 * 372×330 design space scaled to the width; the time is read only while drawing, and stands still at SAVER / reduced motion.
 */
@Composable
fun CoreOrb(strips: List<CoreStrip>, spirits: List<AgentStatus>, topNote: String, bottomNote: String, modifier: Modifier = Modifier) {
    val time = rememberWarTime()
    val measurer = rememberTextMeasurer()
    val sphere = remember { fibonacci(340) }
    val dust = remember { List(6) { i -> streamDust(i) } }
    val halo = remember { haloDust() }
    val description = "核心：" + strips.joinToString("，") { "${it.name} ${it.value}" }
    Canvas(modifier.fillMaxWidth().aspectRatio(W / H).testTag(Tags.WAR_CORE).semantics { contentDescription = description }) {
        val k = size.width / W
        val t = time.longValue
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            // Halo behind the sphere.
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White, .35f to Color.White.copy(alpha = .75f), .7f to War.aqua.copy(alpha = .35f), 1f to Color.Transparent,
                    center = Offset(CX, CY), radius = 100f,
                ),
                100f, Offset(CX, CY), alpha = .75f + .25f * sin(t / 900f),
            )
            // Strips and their streams.
            strips.take(6).forEachIndexed { i, s ->
                val top = 8f + i * 52f
                drawRoundRect(Color.White.copy(alpha = .7f), Offset(2f, top), Size(104f, 44f), CornerRadius(6f))
                val title = measurer.measure(s.name, TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = War.ink))
                drawText(title, topLeft = Offset(8f, top + 4f))
                val value = measurer.measure(s.value, TextStyle(fontFamily = FogFonts.Orbitron, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = War.ink))
                drawText(value, topLeft = Offset(100f - value.size.width, top + 3f))
                val note = measurer.measure(s.note, TextStyle(fontFamily = FontFamily.Monospace, fontSize = 5.6.sp, color = War.ink2))
                drawText(note, topLeft = Offset(8f, top + 13f))
                val base = top + 40f
                val values = s.series.ifEmpty { listOf(0f, 0f) }.let { if (it.size == 1) it + it else it }
                val max = values.max().takeIf { it > 0f } ?: 1f
                val line = Path()
                values.forEachIndexed { j, v ->
                    val x = 8f + 92f * j / (values.size - 1)
                    val y = base - 3f - (v / max) * 14f
                    if (j == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                val area = Path().apply { addPath(line); lineTo(100f, base); lineTo(8f, base); close() }
                drawPath(area, s.fill.copy(alpha = .35f))
                drawPath(line, War.rose, style = Stroke(1.3f))
                val sy = top + 22f
                val curve = Path().apply { moveTo(106f, sy); cubicTo(170f, sy, 205f, CY + (sy - CY) * .25f, CX, CY) }
                drawPath(curve, s.fill.copy(alpha = .7f), style = Stroke(1f))
                drawPath(
                    curve, War.rose,
                    style = Stroke(2f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 9f), -(t / 40f) % 10.5f)),
                )
                drawPoints(dust[i], PointMode.Points, War.rose.copy(alpha = .8f), strokeWidth = 1.6f, cap = StrokeCap.Round)
            }
            // The sphere: rotate about the vertical axis; the far side paler.
            val a = (t / 4_000.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val front = ArrayList<Offset>(200)
            val back = ArrayList<Offset>(200)
            for (v in sphere) {
                val x = v[0] * ca - v[2] * sa
                val z = v[0] * sa + v[2] * ca
                val p = Offset(CX + x * R, CY + v[1] * R)
                if (z > 0) front += p else back += p
            }
            drawPoints(halo, PointMode.Points, War.pink.copy(alpha = .55f), strokeWidth = 1.4f, cap = StrokeCap.Round)
            drawPoints(back, PointMode.Points, War.pink.copy(alpha = .6f), strokeWidth = 1.4f, cap = StrokeCap.Round)
            drawPoints(front, PointMode.Points, War.rose.copy(alpha = .85f), strokeWidth = 1.9f, cap = StrokeCap.Round)
            drawCircle(War.aqua.copy(alpha = .8f), 74f, Offset(CX, CY), style = Stroke(1f))
            drawCircle(
                War.pink, 88f, Offset(CX, CY),
                style = Stroke(.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f), (t / 60f) % 8f)),
            )
            drawCircle(Color.White, 17f, Offset(CX, CY))
            drawCircle(War.aqua, 17f, Offset(CX, CY), style = Stroke(2f))
            drawRoundRect(War.ink, Offset(CX - 6.5f, CY - 7f), Size(4f, 11f), CornerRadius(2f))
            drawRoundRect(War.ink, Offset(CX + 2.5f, CY - 7f), Size(4f, 11f), CornerRadius(2f))
            val lead = measurer.measure("主代理人", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 6.5.sp, fontWeight = FontWeight.Bold, color = War.ink))
            drawText(lead, topLeft = Offset(CX - lead.size.width / 2f, CY + 24f))
            val tn = measurer.measure(topNote, TextStyle(fontFamily = FontFamily.Monospace, fontSize = 6.5.sp, color = War.ink2))
            drawText(tn, topLeft = Offset(W - tn.size.width - 6f, 8f))
            val bn = measurer.measure(bottomNote, TextStyle(fontFamily = FontFamily.Monospace, fontSize = 6.5.sp, color = War.ink2))
            drawText(bn, topLeft = Offset(W - bn.size.width - 6f, H - 14f))
            // Spirits drifting around the core, each at its own spot.
            val spots = listOf(Offset(150f, 40f), Offset(160f, 290f), Offset(345f, 60f), Offset(350f, 275f))
            spirits.take(4).forEachIndexed { i, s ->
                val ph = i * 1.9f
                val drift = Offset(sin(t / 900f + ph) * 5f, cos(t / 1100f + ph) * 6f)
                drawSpirit(spots[i] + drift, 7f, s, t, t != 0L, ph)
            }
        }
    }
}

private fun fibonacci(n: Int): List<FloatArray> = List(n) { i ->
    val y = 1f - (2f * i + 1f) / n
    val r = sqrt(1f - y * y)
    val th = i * 2.399963f
    floatArrayOf(cos(th) * r, y, sin(th) * r)
}

private fun streamDust(i: Int): List<Offset> {
    val rnd = Random(31 + i)
    val sy = 8f + i * 52f + 22f
    val c1 = Offset(170f, sy)
    val c2 = Offset(205f, CY + (sy - CY) * .25f)
    return List(60) {
        val t = rnd.nextFloat()
        val u = 1 - t
        val x = u * u * u * 106f + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * CX
        val y = u * u * u * sy + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * CY
        val spread = sin(t * PI.toFloat()) * 14f
        Offset(x + (rnd.nextFloat() - .5f) * spread, y + (rnd.nextFloat() - .5f) * spread)
    }
}

private fun haloDust(): List<Offset> {
    val rnd = Random(5)
    return List(80) {
        val a = rnd.nextFloat() * 2 * PI.toFloat()
        val r = R + 6f + rnd.nextFloat() * 22f
        Offset(CX + cos(a) * r, CY + sin(a) * r)
    }
}
