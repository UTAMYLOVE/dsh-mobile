package app.dshmobile.ui.warroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.engine.Links
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.NerveEffect
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LinkTags {
    const val CARD = "ag-links"
    const val ROW = "ag-link-"
    const val CANCEL = "ag-link-cancel-"
    const val YES = "ag-link-yes-"
    const val NO = "ag-link-no-"
    const val EMPTY = "ag-links-empty"
}

/**
 * 「今天的連動」 (agency-links §1.5): what changed today because of what, each with its 「因為…所以…」 line. A 今天微調 can be
 * cancelled for the day (the cancel is kept); a proposal waits for 好 / 不要. Reminders and notes are not listed here.
 */
@Composable
fun TodayLinksCard(ready: StartState.Ready) {
    val revision by ready.brain.revision.collectOnMain()
    val scope = rememberCoroutineScope()
    val rows by produceState<List<NerveEffect>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.links() }.getOrDefault(emptyList()) } }
    }
    val shown = rows.filter { it.kind == EffectKind.ADJUST || it.kind == EffectKind.PROPOSAL || it.kind == EffectKind.SAY }
    val names = Links.all().associate { it.id to it.zh }
    Panel(title = stringResource(R.string.ag_links_title), modifier = Modifier.testTag(LinkTags.CARD)) {
        if (shown.isEmpty()) {
            Text(stringResource(R.string.ag_links_empty), color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.testTag(LinkTags.EMPTY))
        }
        shown.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = Sakura.MilkTea)
            Column(Modifier.fillMaxWidth().testTag(LinkTags.ROW + e.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((names[e.link] ?: e.link) + " ・ " + statusZh(e), color = Sakura.TextSecondary, fontSize = 12.sp)
                Text(e.because, color = Sakura.Text, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.kind == EffectKind.ADJUST && e.status == EffectStatus.ACTIVE) {
                        SoftButton(stringResource(R.string.ag_links_cancel), onClick = {
                            scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.cancelLink(e.id) } } }
                        }, modifier = Modifier.testTag(LinkTags.CANCEL + e.id))
                    }
                    if (e.kind == EffectKind.PROPOSAL && e.status == EffectStatus.PROPOSED) {
                        PinkButton(stringResource(R.string.ag_links_yes), onClick = {
                            scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.decideLink(e.id, true) } } }
                        }, modifier = Modifier.testTag(LinkTags.YES + e.id))
                        SoftButton(stringResource(R.string.ag_links_no), onClick = {
                            scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.decideLink(e.id, false) } } }
                        }, modifier = Modifier.testTag(LinkTags.NO + e.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusZh(e: NerveEffect): String = stringResource(
    when (e.status) {
        EffectStatus.ACTIVE -> R.string.ag_links_active
        EffectStatus.CANCELLED -> R.string.ag_links_cancelled
        EffectStatus.PROPOSED -> R.string.ag_links_proposed
        EffectStatus.ACCEPTED -> R.string.ag_links_accepted
        EffectStatus.REJECTED -> R.string.ag_links_rejected
        EffectStatus.EXPIRED -> R.string.ag_links_expired
        else -> R.string.ag_links_said
    },
)
