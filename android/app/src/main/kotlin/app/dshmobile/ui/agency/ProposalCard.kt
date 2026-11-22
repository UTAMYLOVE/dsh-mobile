package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.ProposalView
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton

object ProposalTags {
    const val STRIP = "ag-proposals"
    const val CARD = "ag-proposal-"
    const val YES = "ag-proposal-yes-"
    const val NO = "ag-proposal-no-"
}

/**
 * The proposals waiting for his yes (one table: the conversation's and the links' alike), above the composer. Nothing is
 * shown when none waits. Each is a [ProposalCard].
 */
@Composable
fun ProposalStrip(views: List<ProposalView>, busy: Boolean, onDecide: (String, Boolean) -> Unit) {
    if (views.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(ProposalTags.STRIP),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.ag_proposals_title), color = Sakura.TextSecondary, fontSize = 12.sp)
        views.forEach { ProposalCard(it, busy, onDecide) }
    }
}

/** 差異卡 (arch T12): what changes, before → after, why, and 好 / 不要. Everything on it is written by code. */
@Composable
fun ProposalCard(v: ProposalView, busy: Boolean, onDecide: (String, Boolean) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().background(Sakura.White, shape).border(1.dp, Sakura.Border, shape).padding(12.dp).testTag(ProposalTags.CARD + v.id),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(v.title, color = Sakura.Text, fontSize = 15.sp)
        v.lines.forEach { l ->
            Text(
                if (l.before != null) stringResource(R.string.ag_proposal_line, l.label, l.before!!, l.after) else stringResource(R.string.ag_proposal_line_new, l.label, l.after),
                color = Sakura.Text, fontSize = 14.sp,
            )
        }
        if (v.reason.isNotBlank()) Text(v.reason, color = Sakura.TextSecondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinkButton(stringResource(R.string.ag_proposal_yes), onClick = { onDecide(v.id, true) }, enabled = !busy, modifier = Modifier.testTag(ProposalTags.YES + v.id))
            SoftButton(stringResource(R.string.ag_proposal_no), onClick = { onDecide(v.id, false) }, enabled = !busy, modifier = Modifier.testTag(ProposalTags.NO + v.id))
        }
    }
}
