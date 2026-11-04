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

@Composable
internal fun LogLine(r: WarLogRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(TIME.format(Instant.ofEpochMilli(r.atMs)), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Sakura.TextSecondary)
        val c = when (r.badge) { "錯誤" -> Sakura.RoseBrown; "待批" -> Sakura.Primary; "完成" -> Sakura.MilkTea; else -> Sakura.Aqua }
        Text(r.badge, fontSize = 9.sp, color = Sakura.Text, modifier = Modifier.background(c.copy(alpha = .6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        Text(r.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Sakura.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
