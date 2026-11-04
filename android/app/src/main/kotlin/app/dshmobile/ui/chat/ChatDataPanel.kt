package app.dshmobile.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Decision
import app.dshmobile.agents.WarDerive
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.WorkspaceFile
import app.dshmobile.chat.shortTokens
import app.dshmobile.chat.shortUsd
import app.dshmobile.ui.agents.Chip
import app.dshmobile.ui.agents.MiniTile
import app.dshmobile.ui.agents.SpiritFlow
import app.dshmobile.ui.agents.War
import app.dshmobile.ui.agents.WarHeader
import app.dshmobile.ui.agents.WarPanel
import app.dshmobile.ui.shell.Tags
import java.util.Locale

/**
 * The chat's pull-out data panel, in the pink war-room style (owner, 2026-11-04: the old one piled spirits up and showed raw
 * JSON): four tiles, this conversation's spirits on the LIVE FLOW (working ones and the three latest finished), the speed of
 * each reply as a curve, every tool call as a decision (放行 / 擋下 / 等你 / 錯誤) and the workspace files to share.
 */
@Composable
fun ChatDataPanel(
    team: List<AgentStatus>, speeds: List<Double>, costUsd: Double?, contextFill: Float?, cacheHit: Double?, tools: List<ChatItem.Tool>,
    files: List<WorkspaceFile> = emptyList(), onShare: (String) -> Unit = {},
) {
    val decisions = remember(tools) { WarDerive.decisions(tools, max = 8) }
    WarPanel(modifier = Modifier.testTag(Tags.CHAT_DATA_PANEL)) {
        WarHeader("◆ 對話數據 DATA", "● LIVE", War.rose)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniTile("◇ 上下文", contextFill?.let { "${(it * 100).toInt()}%" } ?: "—", "模型記憶", War.aqua, Modifier.weight(1f))
            MiniTile("◆ 快取", cacheHit?.let { "${(it * 100).toInt()}%" } ?: "—", "省錢比例", War.milk, Modifier.weight(1f))
            MiniTile("● 速度", speeds.lastOrNull()?.let { String.format(Locale.US, "%.0f", it) } ?: "—", "tok/s 最新", War.pink, Modifier.weight(1f))
            MiniTile("▲ 花費", costUsd?.let { shortUsd(it) } ?: "—", "已載入回覆", War.milk, Modifier.weight(1f))
        }
        if (team.isNotEmpty()) SpiritFlow(team, onTap = {}, height = 150.dp, compact = true)
        Text("每則回覆速度 tok/s", style = War.lab)
        SpeedCurve(speeds)
        Text("工具決策（新的在上）", style = War.lab, modifier = Modifier.padding(top = 2.dp))
        if (decisions.isEmpty()) Text("這個對話還沒用過工具", style = War.mono.copy(color = War.ink2))
        decisions.forEach { d ->
            val col = when (d.kind) { Decision.Kind.ALLOWED -> War.aqua; Decision.Kind.WAITING, Decision.Kind.BLOCKED -> War.pink; Decision.Kind.FAILED -> War.rose }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(d.kind.label, col)
                Column(Modifier.weight(1f)) {
                    Text(d.text, style = War.mono.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.why, style = War.mono.copy(fontSize = 7.5.sp, color = War.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (files.isNotEmpty()) {
            Text("工作區檔案（點一下分享）", style = War.lab, modifier = Modifier.padding(top = 2.dp))
            files.take(12).forEach { f ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { onShare(f.path) }.testTag(Tags.CHAT_FILE + f.path),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(f.path, style = War.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(shortTokens(f.bytes) + "B", style = War.lab)
                }
            }
        }
    }
}

@Composable
private fun SpeedCurve(values: List<Double>) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .7f))
            .border(1.dp, War.line, RoundedCornerShape(8.dp)).padding(6.dp),
    ) {
        if (values.size < 2) {
            Text("回覆兩則以上才有曲線", style = War.mono.copy(color = War.ink2))
            return@Box
        }
        val v = values.takeLast(30)
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val max = v.max().coerceAtLeast(1.0)
            val pts = v.mapIndexed { i, x -> Offset(size.width * i / (v.size - 1), size.height - (x / max).toFloat() * size.height * .9f) }
            val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, War.aqua.copy(alpha = .3f))
            drawPath(line, War.rose, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, 3.5.dp.toPx(), pts.last())
            drawCircle(War.pink, 3.5.dp.toPx(), pts.last(), style = Stroke(2.dp.toPx()))
        }
    }
}
