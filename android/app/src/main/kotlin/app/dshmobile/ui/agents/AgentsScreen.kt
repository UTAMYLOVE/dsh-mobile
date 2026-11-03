package app.dshmobile.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.agents.AgentRow
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogPanel
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura

/**
 * The simple agents page (the monitor wall replaces it in stage 3): a panel with the running count against the limit, then one
 * row per subagent. A row is a dot (aqua glow while running, milk tea once finished), the label, 「來自：父對話標題」 (tap opens
 * that conversation) and, while running, 「叫它停」. Laid out for 412 dp portrait: label and origin take what the button leaves
 * and ellipsize after two lines, so the button never overlaps them. [failed] children show a short error under their row.
 */
@Composable
fun AgentsScreen(
    rows: List<AgentRow>,
    parentTitle: (String) -> String?,
    limit: Int,
    stopping: Set<String>,
    onStop: (AgentRow) -> Unit,
    onOpenParent: (String) -> Unit,
    failed: Set<String> = emptySet(),
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current)) {
        FogPanel(
            title = stringResource(R.string.agents_title),
            en = "AGENTS",
            meta = stringResource(R.string.agents_meta, rows.count { it.running }, limit),
        ) {
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.agents_empty), color = Sakura.TextSecondary, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp).testTag(Tags.AGENTS_EMPTY),
                )
            }
            rows.forEach { row ->
                AgentItem(
                    row, parentTitle(row.parentId) ?: stringResource(R.string.agents_parent_untitled),
                    stopping = row.childId in stopping, failed = row.childId in failed,
                    onStop = { onStop(row) }, onOpenParent = { onOpenParent(row.parentId) },
                )
            }
        }
    }
}

@Composable
private fun AgentItem(row: AgentRow, parent: String, stopping: Boolean, failed: Boolean, onStop: () -> Unit, onOpenParent: () -> Unit) {
    val status = stringResource(if (row.running) R.string.agents_running else R.string.agents_finished)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val dot = if (row.running) Sakura.Aqua else Sakura.MilkTea
            val round = RoundedCornerShape(50)
            Box(
                Modifier.size(8.dp).then(if (row.running) Modifier.glow(dot, 8.dp, round) else Modifier).background(dot, round)
                    .semantics { contentDescription = status },
            )
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpenParent)
                    .padding(vertical = 10.dp).testTag(Tags.AGENT_ROW + row.childId),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(row.label, color = Sakura.Text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.agents_from, parent), color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (row.running) {
                FogButton(
                    stringResource(if (stopping) R.string.agents_stopping else R.string.agents_stop), FogButtonKind.AQUA, onStop,
                    Modifier.testTag(Tags.AGENT_STOP + row.childId), enabled = !stopping,
                )
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.agents_stop_failed), color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
        }
    }
}
