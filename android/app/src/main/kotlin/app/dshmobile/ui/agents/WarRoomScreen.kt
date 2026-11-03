package app.dshmobile.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Mood
import app.dshmobile.agents.WarLogRow
import app.dshmobile.chat.shortDuration
import app.dshmobile.chat.shortTokens
import app.dshmobile.chat.shortUsd
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Cell = RoundedCornerShape(14.dp)
private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Taipei"))

/** The status badge text (§H.1): LIVE, WAIT, DONE, ERR, IDLE, plus the stage. */
internal fun badgeOf(a: AgentStatus): String = when (a.mood) {
    Mood.RUNNING -> "LIVE · ${a.stage.en}"
    Mood.WAITING -> "WAIT"
    Mood.DONE -> "DONE"
    Mood.ERROR -> "ERR"
    Mood.IDLE -> "IDLE"
}

/** The big top-right stat tiles of §H.1: 今日花費, 執行中, 完成, 錯誤. */
@Composable
fun WarTiles(todayUsd: Double?, agents: List<AgentStatus>, limit: Int?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag(Tags.WAR_TILES), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigTile("今日花費", todayUsd?.let { String.format(Locale.US, "%.2f", it) } ?: "—", "US$", Modifier.weight(1.2f))
        BigTile("執行中", agents.count { it.mood == Mood.RUNNING }.toString(), limit?.let { "/ $it" } ?: "", Modifier.weight(1f))
        BigTile("完成", agents.count { it.mood == Mood.DONE }.toString(), "", Modifier.weight(1f))
        BigTile("錯誤", agents.count { it.mood == Mood.ERROR }.toString(), "", Modifier.weight(1f))
    }
}

@Composable
private fun BigTile(label: String, value: String, unit: String, modifier: Modifier) {
    Column(
        modifier.background(Sakura.White.copy(alpha = .45f), Cell).border(1.dp, Sakura.Primary.copy(alpha = .5f), Cell)
            .padding(horizontal = 10.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = FogText.Label.copy(fontSize = 10.sp), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            if (unit == "US$") Text("US$", style = FogText.Label.copy(fontSize = 9.sp), modifier = Modifier.padding(bottom = 4.dp, end = 2.dp))
            Text(value, style = FogText.Number.copy(fontSize = 22.sp), maxLines = 1)
            if (unit.isNotEmpty() && unit != "US$") Text(" $unit", style = FogText.Label.copy(fontSize = 10.sp), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/**
 * The 代理人 war room (§E/G/H): stat tiles, the LIVE FLOW with spirits, one card per agent with its detailed status (tap to open
 * everything), and the live log. [listMode] shows the plain list instead ([list]), for TalkBack and small screens.
 */
@Composable
fun WarRoom(
    agents: List<AgentStatus>,
    log: List<WarLogRow>,
    todayUsd: Double?,
    limits: String,
    expanded: String?,
    onExpand: (String?) -> Unit,
    costOf: (AgentStatus) -> Double?,
    onStop: (AgentStatus) -> Unit,
    stopping: Set<String>,
    onOpen: (AgentStatus) -> Unit,
    limit: Int?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WarTiles(todayUsd, agents, limit)
        FogPanel(title = "即時流程", en = "LIVE FLOW", meta = limits, index = 1) {
            if (agents.isEmpty()) {
                Text("還沒有代理人在工作。開一個對話叫它做事，小精靈就會出來。", color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.testTag(Tags.AGENTS_EMPTY))
            }
            SpiritFlow(agents, onTap = { onExpand(it.id) })
        }
        FogPanel(title = "代理人", en = "AGENTS", meta = "${agents.size} 位", index = 2) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                agents.forEach { a ->
                    AgentCard(a, a.id == expanded, costOf(a), a.id in stopping, { onExpand(if (a.id == expanded) null else a.id) }, { onStop(a) }, { onOpen(a) })
                }
            }
        }
        FogPanel(title = "即時紀錄", en = "LOG · TAIL -F", ice = true, index = 3) {
            if (log.isEmpty()) Text("等第一個動作…", color = Sakura.TextSecondary, fontSize = 12.sp)
            Column(Modifier.testTag(Tags.WAR_LOG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                log.take(30).forEach { LogLine(it) }
            }
        }
    }
}

@Composable
internal fun LogLine(r: WarLogRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(TIME.format(Instant.ofEpochMilli(r.atMs)), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Sakura.TextSecondary)
        val c = when (r.badge) { "錯誤" -> Sakura.RoseBrown; "待批" -> Sakura.Primary; "完成" -> Sakura.MilkTea; else -> Sakura.Aqua }
        Text(r.badge, fontSize = 9.sp, color = Sakura.Text, modifier = Modifier.background(c.copy(alpha = .6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        Text(r.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Sakura.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One agent's card: badge, name, role, current action in mono, last line; opened, every detail of §G and its actions. */
@Composable
internal fun AgentCard(
    a: AgentStatus, open: Boolean, cost: Double?, stopping: Boolean, onToggle: () -> Unit, onStop: () -> Unit, onOpen: () -> Unit,
) {
    val color = moodColor(a.mood)
    val border = if (a.mood == Mood.RUNNING) Modifier.glow(Sakura.Aqua.copy(alpha = .5f), 10.dp, Cell) else Modifier
    Column(
        Modifier.fillMaxWidth().then(border).background(Sakura.White.copy(alpha = .5f), Cell)
            .border(1.dp, if (a.mood == Mood.RUNNING) Sakura.Aqua else Sakura.Primary.copy(alpha = .4f), Cell)
            .clickable(role = Role.Button, onClick = onToggle).padding(12.dp).testTag(Tags.WAR_CARD + a.id),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
            Text(a.label, color = Sakura.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                badgeOf(a), fontSize = 9.sp, color = Sakura.Text,
                modifier = Modifier.background(color.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            if (a.isLead) "主代理人" else "子代理人 · 第 ${a.depth} 層", color = Sakura.TextSecondary, fontSize = 11.sp,
        )
        a.action?.let { Text("› $it", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Sakura.RoseBrown, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        a.lastLine?.let { Text(it, color = Sakura.Text, fontSize = 12.sp, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis) }
        TokenBar(a)
        if (open) {
            Column(Modifier.testTag(Tags.WAR_DETAIL + a.id), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Detail("狀態", listOfNotNull(stateWord(a.mood), a.waiting).joinToString(" · "))
                Detail("模型", listOfNotNull(a.model, a.effort?.let { "思考 $it" }).joinToString(" · ").ifEmpty { "—" })
                Detail("步驟", "${a.usage.turns} 輪 · ${a.usage.steps} 步")
                Detail("時間", a.activeMs?.let { shortDuration(it) } ?: "—")
                Detail(
                    "Token", "入 ${shortTokens(a.usage.inputTokens + a.usage.cacheReadTokens)}（快取 ${shortTokens(a.usage.cacheReadTokens)}）· 出 ${shortTokens(a.usage.outputTokens)}",
                )
                Detail("速度", a.usage.tokensPerSecond?.let { String.format(Locale.US, "%.0f tok/s", it) } ?: "—")
                Detail("首字", a.usage.averageTtftMs?.let { shortDuration(it) } ?: "—")
                Detail("花費", cost?.let { "≈ " + shortUsd(it) } ?: "—")
                Detail("上下文", a.contextFill?.let { "${(it * 100).toInt()}%" } ?: "—")
                Detail("排隊", if (a.queued > 0) "${a.queued} 則" else "沒有")
                a.error?.let { Detail("錯誤", it) }
                if (a.recent.isNotEmpty()) {
                    Text("最近動作", style = FogText.Label.copy(fontSize = 10.sp), modifier = Modifier.padding(top = 4.dp))
                    a.recent.forEach { Text("· $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Sakura.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    FogButton(if (a.isLead) "打開對話" else "打開來源對話", FogButtonKind.GHOST, onOpen, Modifier.testTag(Tags.WAR_OPEN + a.id))
                    if (!a.isLead && a.mood == Mood.RUNNING) {
                        FogButton(if (stopping) "停止中…" else "叫它停", FogButtonKind.AQUA, onStop, Modifier.testTag(Tags.AGENT_STOP + a.id), enabled = !stopping)
                    }
                }
            }
        }
    }
}

private fun stateWord(m: Mood) = when (m) {
    Mood.RUNNING -> "工作中"
    Mood.WAITING -> "等待中"
    Mood.DONE -> "完成"
    Mood.ERROR -> "出錯"
    Mood.IDLE -> "閒置"
}

@Composable
private fun Detail(k: String, v: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, style = FogText.Label.copy(fontSize = 10.sp), modifier = Modifier.padding(top = 1.dp).size(width = 44.dp, height = 16.dp))
        Text(v, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Sakura.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Stacked bar: cache read, uncached input, output (FUI bar meter). */
@Composable
private fun TokenBar(a: AgentStatus) {
    val u = a.usage
    val total = (u.inputTokens + u.cacheReadTokens + u.outputTokens).toFloat()
    if (total <= 0f) return
    Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Sakura.White.copy(alpha = .6f))) {
        listOf(u.cacheReadTokens to Sakura.MilkTea, u.inputTokens to Sakura.Primary, u.outputTokens to Sakura.Aqua).forEach { (n, c) ->
            if (n > 0) Box(Modifier.weight(n / total).height(4.dp).background(c))
        }
    }
    Text("${shortTokens(u.totalTokens)} tok", style = FogText.Label.copy(fontSize = 9.sp), color = Color.Unspecified)
}
