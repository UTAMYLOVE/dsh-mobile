package app.dshmobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import app.agency.core.memory.MemoryBlock
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryItem
import app.agency.core.model.MemoryOp
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MemoryTags {
    const val ITEM = "ag-mem-item-"
    const val DELETE = "ag-mem-delete-"
    const val EVENT = "ag-mem-event-"
    const val UNDO = "ag-mem-event-undo-"
    const val REASON = "ag-mem-reason"
}

/**
 * 設定 › 她記得的事 (arch 1.8, T12): the four blocks with what is in them (each can be deleted), and every change, newest first
 * (each can be undone while the item is as that change left it; the reason is said when it cannot).
 */
@Composable
fun MemoryPage(ready: StartState.Ready) {
    val revision by ready.brain.revision.collectOnMain()
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    val data by produceState<Pair<List<MemoryItem>, List<MemoryEvent>>>(emptyList<MemoryItem>() to emptyList(), revision) {
        onMain {
            value = withContext(Dispatchers.IO) {
                runCatching { ready.store.memory.items() to ready.store.memory.events() }.getOrDefault(emptyList<MemoryItem>() to emptyList())
            }
        }
    }
    val (items, events) = data
    Section(stringResource(R.string.ag_set_memory)) {
        Note(stringResource(R.string.ag_mem_intro))
        MemoryBlock.entries.forEach { b ->
            Label(b.zh)
            val inBlock = items.filter { it.block == b.key }
            if (inBlock.isEmpty()) Note(stringResource(R.string.ag_mem_none))
            inBlock.forEach { i ->
                Row(Modifier.fillMaxWidth().testTag(MemoryTags.ITEM + i.id), verticalAlignment = Alignment.CenterVertically) {
                    Text(i.text + (i.dueDay?.let { stringResource(R.string.ag_mem_due, it) } ?: ""), color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.forgetMemory(i.id) } } }
                    }, modifier = Modifier.testTag(MemoryTags.DELETE + i.id)) { Text(stringResource(R.string.ag_mem_delete), color = Sakura.Text) }
                }
            }
        }
    }
    Section(stringResource(R.string.ag_mem_history)) {
        reason?.let { Note(it, Modifier.testTag(MemoryTags.REASON)) }
        if (events.isEmpty()) Note(stringResource(R.string.ag_mem_history_none))
        events.forEachIndexed { n, e ->
            if (n > 0) HorizontalDivider(color = Sakura.MilkTea)
            val who = stringResource(if (e.sourceMessageId == null) R.string.ag_mem_by_you else R.string.ag_mem_by_her)
            val text = when (e.op) {
                MemoryOp.ADD -> stringResource(R.string.ag_mem_added, who, e.after.orEmpty())
                MemoryOp.UPDATE -> stringResource(R.string.ag_mem_updated, who, e.after.orEmpty())
                MemoryOp.DELETE -> stringResource(R.string.ag_mem_deleted, who, e.before.orEmpty())
            }
            Row(Modifier.fillMaxWidth().testTag(MemoryTags.EVENT + e.id), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (e.undone) {
                    Text(stringResource(R.string.ag_mem_undone), color = Sakura.TextSecondary, fontSize = 13.sp)
                } else {
                    TextButton(onClick = {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { ready.brain.undoMemory(e.id) }.getOrNull() }
                            onMain { reason = r?.reason }
                        }
                    }, modifier = Modifier.testTag(MemoryTags.UNDO + e.id)) { Text(stringResource(R.string.ag_mem_undo), color = Sakura.Text) }
                }
            }
        }
    }
}
