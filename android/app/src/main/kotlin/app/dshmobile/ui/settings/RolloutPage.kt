package app.dshmobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.schedule.RolloutSpec
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RolloutTags {
    const val NOW = "ag-rollout-now"
    const val WEEK = "ag-rollout-week-"
    const val EARLIER = "ag-rollout-earlier"
    const val LATER = "ag-rollout-later"
    const val CONFIRM = "ag-rollout-confirm"
}

/**
 * 設定 › 上線週數 (分週上線, owner 2026-11-27): the week he is in, what each week opens, and his own change of the week (one
 * step at a time, confirmed once). A week on target also brings its card in the conversation.
 */
@Composable
fun RolloutPage(ready: StartState.Ready) {
    var saved by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val spec by produceState<RolloutSpec?>(null, saved) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.rollout() }.getOrNull() } }
    }
    val r = spec ?: return
    Section(stringResource(R.string.ag_set_rollout)) {
        Text(stringResource(R.string.ag_rollout_now, r.week), color = Sakura.Text, fontSize = 16.sp, modifier = Modifier.testTag(RolloutTags.NOW))
        Note(stringResource(R.string.ag_rollout_note))
        (1..RolloutSpec.LAST).forEach { w ->
            val state = stringResource(if (w <= r.week) R.string.ag_rollout_open else R.string.ag_rollout_closed)
            Text(
                stringResource(R.string.ag_rollout_week, w, r.opensIn(w).joinToString("、") { it.zh }, state),
                color = if (w <= r.week) Sakura.Text else Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.testTag(RolloutTags.WEEK + w),
            )
        }
        val target = asking
        if (target == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.week > 1) SoftButton(stringResource(R.string.ag_rollout_earlier), onClick = { asking = r.week - 1 }, modifier = Modifier.testTag(RolloutTags.EARLIER))
                if (r.week < RolloutSpec.LAST) SoftButton(stringResource(R.string.ag_rollout_later), onClick = { asking = r.week + 1 }, modifier = Modifier.testTag(RolloutTags.LATER))
            }
        } else {
            Text(stringResource(R.string.ag_rollout_ask, target), color = Sakura.Text, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PinkButton(stringResource(R.string.ag_rollout_confirm), onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { ready.brain.setRolloutWeek(target) } }
                        asking = null
                        saved++
                    }
                }, modifier = Modifier.testTag(RolloutTags.CONFIRM))
                SoftButton(stringResource(R.string.ag_back), onClick = { asking = null })
            }
        }
    }
}
