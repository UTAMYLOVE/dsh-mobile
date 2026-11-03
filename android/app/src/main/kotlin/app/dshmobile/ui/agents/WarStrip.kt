package app.dshmobile.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.WarLogRow
import app.dshmobile.service.DshPhase
import app.dshmobile.service.DshStatus
import app.dshmobile.service.monotonicMs
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura

/** 「dsh 執行中 · 埠 41234 · 已運行 12 分」 and the last failure, for the overview (§G). */
fun dshLine(s: DshStatus?, nowMono: Long = monotonicMs()): String = when {
    s == null -> "dsh：未知"
    s.phase == DshPhase.RUNNING -> "dsh 執行中" + (s.port?.let { " · 埠 $it" } ?: "") + " · 已運行 ${((nowMono - s.sinceMs).coerceAtLeast(0) / 60_000)} 分"
    s.phase == DshPhase.STOPPED -> "dsh 已停止"
    else -> "dsh 啟動／重啟中"
} + (s?.failure?.let { " · 上次失敗：${it.step.name}" } ?: "")

/** The overview's war room (§H): dsh's state, the spirits on the LIVE FLOW (tap: the agents tab) and the newest log rows. */
@Composable
fun WarStrip(agents: List<AgentStatus>, log: List<WarLogRow>, dsh: DshStatus?, onOpen: () -> Unit) {
    FogPanel(title = "戰情室", en = "WAR ROOM", meta = dshLine(dsh), index = 1, modifier = Modifier.testTag(Tags.OVERVIEW_WAR)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (agents.isEmpty()) Text("目前沒有代理人在工作", color = Sakura.TextSecondary, fontSize = 12.sp)
            else SpiritFlow(agents, onTap = { onOpen() }, height = 150.dp, compact = true)
            log.take(4).forEach { LogLine(it) }
        }
    }
}
