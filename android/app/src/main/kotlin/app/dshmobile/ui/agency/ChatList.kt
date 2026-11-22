package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryOp
import app.agency.core.model.Message
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.dshmobile.R
import app.dshmobile.ui.kit.Chip
import app.dshmobile.ui.kit.Sakura

/** What a bubble can do besides showing its text (T12): 重送, a 討論 chip, 撤銷 under a memory line. */
data class ChatActions(
    val onResend: (String) -> Unit = {},
    val onOption: (String) -> Unit = {},
    val onUndo: (String) -> Unit = {},
)

object ChatTags {
    const val UNSENT = "ag-unsent-"
    const val RESEND = "ag-resend-"
    const val OPTION = "ag-option-"
    const val MEMORY = "ag-memory-"
    const val UNDO = "ag-memory-undo-"
}

/**
 * The one conversation, oldest at the top and kept scrolled to the newest. Every company line shows its role above it
 * (【經紀人】 or 【體態管理老師】), imported ones too; his lines sit on the right. A line of his that did not get through says
 * 「沒送出」 with 「重送」 beside it; her newest 討論 question has its options as chips (the recommended one marked); what she
 * remembered from a reply sits under it in grey with 撤銷. [memory] holds the memory events by id.
 */
@Composable
fun ChatList(
    messages: List<Message>,
    hasOlder: Boolean,
    onOlder: () -> Unit,
    modifier: Modifier = Modifier,
    memory: Map<String, MemoryEvent> = emptyMap(),
    actions: ChatActions = ChatActions(),
    busy: Boolean = false,
) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) state.scrollToItem(messages.size - 1 + if (hasOlder) 1 else 0)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp).testTag(AgencyTags.CHAT), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.ag_empty_chat), color = Sakura.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        return
    }
    // Chips only under the newest company line, while it is a 討論 question (an older one's options are no longer asked).
    val asking = messages.lastOrNull { it.who == Who.COMPANY }?.takeIf { it.mode == MessageMode.DISCUSS && it.refs.options.isNotEmpty() }?.id
    LazyColumn(
        modifier.fillMaxWidth().testTag(AgencyTags.CHAT),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasOlder) {
            item(key = "older") {
                TextButton(onClick = onOlder, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ag_older), color = Sakura.TextSecondary) }
            }
        }
        items(messages, key = { it.id }) { Bubble(it, it.id == asking, memory, actions, busy) }
    }
}

@Composable
private fun Bubble(m: Message, chips: Boolean, memory: Map<String, MemoryEvent>, actions: ChatActions, busy: Boolean) {
    val mine = m.who == Who.OWNER
    Column(
        Modifier.fillMaxWidth().testTag(AgencyTags.BUBBLE + m.id),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (m.who == Who.COMPANY) {
            Text(roleLabel(m.role), color = Sakura.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        }
        val shape = RoundedCornerShape(18.dp)
        val color = when (m.who) {
            Who.OWNER -> Sakura.Primary.copy(alpha = if (m.unsent) .3f else .55f)
            Who.COMPANY -> Sakura.White
            Who.SYSTEM -> Sakura.MilkTea.copy(alpha = .5f)
        }
        Text(
            m.text, color = Sakura.Text, fontSize = 15.sp, lineHeight = 21.sp,
            modifier = Modifier.widthIn(max = 300.dp).background(color, shape).padding(horizontal = 14.dp, vertical = 9.dp),
        )
        if (mine && m.unsent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ag_unsent), color = Sakura.RoseBrown, fontSize = 12.sp, modifier = Modifier.testTag(ChatTags.UNSENT + m.id))
                TextButton(onClick = { actions.onResend(m.id) }, enabled = !busy, modifier = Modifier.testTag(ChatTags.RESEND + m.id)) {
                    Text(stringResource(R.string.ag_resend), color = Sakura.Text, fontSize = 13.sp)
                }
            }
        }
        if (chips) {
            val recommended = stringResource(R.string.ag_recommended)
            Row(
                Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                m.refs.options.forEachIndexed { i, o ->
                    val marked = i == m.refs.recommended
                    Chip(
                        o, onClick = { if (!busy) actions.onOption(o) }, marked = marked,
                        modifier = Modifier.testTag(ChatTags.OPTION + i).let { if (marked) it.semantics { contentDescription = "$o，$recommended" } else it },
                    )
                }
            }
        }
        m.refs.memory.mapNotNull { memory[it] }.forEach { e -> MemoryLine(m.role, e, actions.onUndo) }
    }
}

@Composable
private fun MemoryLine(role: RoleKey?, e: MemoryEvent, onUndo: (String) -> Unit) {
    val who = stringResource(if (role == RoleKey.BODY) R.string.ag_role_body_plain else R.string.ag_role_manager_plain)
    val text = when (e.op) {
        MemoryOp.ADD -> stringResource(R.string.ag_mem_added, who, e.after.orEmpty())
        MemoryOp.UPDATE -> stringResource(R.string.ag_mem_updated, who, e.after.orEmpty())
        MemoryOp.DELETE -> stringResource(R.string.ag_mem_deleted, who, e.before.orEmpty())
    }
    Row(Modifier.padding(start = 6.dp).testTag(ChatTags.MEMORY + e.id), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Sakura.TextSecondary, fontSize = 12.sp, modifier = Modifier.widthIn(max = 240.dp))
        if (e.undone) {
            Text(stringResource(R.string.ag_mem_undone), color = Sakura.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        } else {
            TextButton(onClick = { onUndo(e.id) }, modifier = Modifier.testTag(ChatTags.UNDO + e.id)) {
                Text(stringResource(R.string.ag_mem_undo), color = Sakura.Text, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun roleLabel(role: RoleKey?): String = stringResource(
    when (role) {
        RoleKey.MANAGER -> R.string.ag_role_manager
        RoleKey.BODY -> R.string.ag_role_body
        null -> R.string.ag_role_company
    },
)
