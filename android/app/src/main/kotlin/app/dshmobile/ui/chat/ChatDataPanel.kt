package app.dshmobile.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.stageOf
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.shortUsd
import app.dshmobile.ui.agents.RingGauge
import app.dshmobile.ui.agents.SpiritFlow
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import java.util.Locale

/**
 * The chat's pull-out data panel (§H 對話): this conversation's spirits on the LIVE FLOW, the speed of each reply as a curve,
 * context fill and cache hit as ring gauges, the estimated cost of the loaded replies and the tool-call log.
 */
@Composable
fun ChatDataPanel(team: List<AgentStatus>, speeds: List<Double>, costUsd: Double?, contextFill: Float?, cacheHit: Double?, tools: List<ChatItem.Tool>) {
    FogPanel(title = "對話數據", en = "DATA", index = 0, modifier = Modifier.testTag(Tags.CHAT_DATA_PANEL)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (team.isNotEmpty()) SpiritFlow(team, onTap = {}, height = 130.dp, compact = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Gauge("上下文", contextFill)
                Gauge("快取", cacheHit?.toFloat())
                Column(Modifier.weight(1f)) {
                    Text("每則速度 tok/s", style = FogText.Label.copy(fontSize = 10.sp))
                    Sparkline(speeds, Modifier.fillMaxWidth().height(36.dp))
                    Text(
                        listOfNotNull(
                            speeds.lastOrNull()?.let { String.format(Locale.US, "最新 %.0f", it) },
                            costUsd?.let { "已載入回覆 ≈ ${shortUsd(it)}" },
                        ).joinToString(" · "),
                        style = FogText.Label.copy(fontSize = 10.sp),
                    )
                }
            }
            if (tools.isNotEmpty()) {
                Text("工具紀錄", style = FogText.Label.copy(fontSize = 10.sp))
                tools.takeLast(8).asReversed().forEach { t ->
                    val mark = when (t.status) { ToolStatus.DONE -> "✓"; ToolStatus.FAILED -> "✗"; ToolStatus.WAITING_APPROVAL -> "⏸"; ToolStatus.RUNNING -> "…" }
                    Text("$mark ${stageOf(t.name, t.summary).third}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Sakura.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Gauge(label: String, f: Float?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            RingGauge(f, Modifier.size(48.dp))
            BasicText(f?.let { "${(it * 100).toInt()}%" } ?: "—", style = FogText.Number.copy(fontSize = 11.sp))
        }
        Text(label, style = FogText.Label.copy(fontSize = 9.sp), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) {
            drawLine(Sakura.Primary.copy(alpha = .4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            return@Canvas
        }
        val v = values.takeLast(24)
        val max = v.max().coerceAtLeast(1.0)
        val path = Path()
        v.forEachIndexed { i, x ->
            val p = Offset(size.width * i / (v.size - 1), size.height - (x / max).toFloat() * size.height * .9f)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, Sakura.Aqua, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
