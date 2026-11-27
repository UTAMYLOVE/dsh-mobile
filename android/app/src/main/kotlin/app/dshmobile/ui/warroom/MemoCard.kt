package app.dshmobile.ui.warroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.Memo
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MemoTags {
    const val CARD = "ag-memo"
    const val ROW = "ag-memo-row-"
    const val CHECK = "ag-memo-check-"
    const val DELETE = "ag-memo-delete-"
    const val UNDO = "ag-memo-undo"
    const val EMPTY = "ag-memo-empty"
}

/**
 * 備忘錄 (T12b): his memos in his words, the time 小葵 reminds him, 打勾 and 刪除 (with 復原 for the last delete). New memos come
 * from 「幫我記…」「提醒我…」 in the conversation.
 */
@Composable
fun MemoCard(ready: StartState.Ready, zone: ZoneId = ZoneId.systemDefault()) {
    val revision by ready.brain.revision.collectOnMain()
    val scope = rememberCoroutineScope()
    var deleted by remember { mutableStateOf<String?>(null) }
    val rows by produceState<List<Memo>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.memos() }.getOrDefault(emptyList()) } }
    }
    fun run(block: suspend () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching { block() } } }
    Panel(title = stringResource(R.string.ag_memo_title), modifier = Modifier.testTag(MemoTags.CARD)) {
        if (rows.isEmpty()) Text(stringResource(R.string.ag_memo_empty), color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.testTag(MemoTags.EMPTY))
        val ordered = rows.filter { it.doneAt == null } + rows.filter { it.doneAt != null }
        ordered.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(color = Sakura.MilkTea)
            Row(Modifier.fillMaxWidth().testTag(MemoTags.ROW + m.id), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = m.doneAt != null, onCheckedChange = { on -> run { ready.brain.checkMemo(m.id, on) } }, modifier = Modifier.testTag(MemoTags.CHECK + m.id))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        m.text, color = if (m.doneAt != null) Sakura.TextSecondary else Sakura.Text, fontSize = 15.sp,
                        textDecoration = if (m.doneAt != null) TextDecoration.LineThrough else null,
                    )
                    m.dueAt?.let { due ->
                        val z = Instant.ofEpochMilli(due).atZone(zone)
                        Text(stringResource(R.string.ag_memo_due, z.monthValue, z.dayOfMonth, z.hour, z.minute), color = Sakura.TextSecondary, fontSize = 12.sp)
                    }
                }
                SoftButton(stringResource(R.string.ag_memo_delete), onClick = { deleted = m.id; run { ready.brain.deleteMemo(m.id) } }, modifier = Modifier.testTag(MemoTags.DELETE + m.id))
            }
        }
        deleted?.let { id ->
            SoftButton(stringResource(R.string.ag_memo_undo), onClick = { deleted = null; run { ready.brain.restoreMemo(id) } }, modifier = Modifier.testTag(MemoTags.UNDO))
        }
    }
}
