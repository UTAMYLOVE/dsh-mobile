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
import androidx.compose.foundation.lazy.itemsIndexed
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
import app.agency.core.model.Meal
import app.agency.core.model.MemoryEvent
import app.agency.core.model.PendingLabel
import app.agency.core.model.SearchResult
import app.dshmobile.ui.agency.meal.SearchResultCard
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.ui.agency.meal.MealActions
import app.dshmobile.ui.agency.meal.MealCard
import app.agency.core.model.MemoryOp
import app.agency.core.model.Message
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.dshmobile.R
import app.dshmobile.ui.kit.Chip
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.Type
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape

/**
 * What a bubble can do besides showing its text (T12): 重送, a 討論 chip, 撤銷 under a memory line; and (T15) the meal card
 * under the body teacher's line about a meal ([meal]).
 */
data class ChatActions(
    val onResend: (String) -> Unit = {},
    val onOption: (String) -> Unit = {},
    val onUndo: (String) -> Unit = {},
    val meal: MealActions = MealActions(),
)

/** The meals the visible lines point at (by id), the items waiting for a label, and the vault their photos open from. */
data class MealView(
    val meals: Map<String, Meal> = emptyMap(),
    val pending: List<PendingLabel> = emptyList(),
    val vault: MediaVault? = null,
    /** The nearby results the lines point at (T16), by id. */
    val results: Map<String, SearchResult> = emptyMap(),
    /** The photo readings the lines point at (T15b), by id. */
    val reads: Map<String, app.agency.core.model.PhotoRead> = emptyMap(),
)

object ChatTags {
    const val UNSENT = "ag-unsent-"
    const val RESEND = "ag-resend-"
    const val OPTION = "ag-option-"
    const val MEMORY = "ag-memory-"
    const val UNDO = "ag-memory-undo-"
}

/**
 * The one conversation, oldest at the top and kept scrolled to the newest (redesign §四): her lines on the left in white, his on
 * the right in his colour; her face and name only on the first line of a run, the time under the last. A line of his that did not get through says
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
    meals: MealView = MealView(),
) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        // On the next frame, never inside the measure that composed this list (the home screen's panels are sized from the
        // room they get, so the list may be composed while the screen is measured).
        androidx.compose.runtime.withFrameNanos { }
        state.scrollToItem(messages.size - 1 + if (hasOlder) 1 else 0)
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasOlder) {
            item(key = "older") {
                TextButton(onClick = onOlder, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ag_older), color = Sakura.TextSecondary) }
            }
        }
        itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
            val prev = messages.getOrNull(i - 1)
            val next = messages.getOrNull(i + 1)
            Bubble(m, m.id == asking, memory, actions, busy, meals, first = !sameGroup(prev, m), last = !sameGroup(m, next))
        }
    }
}

/** Two lines are one group: the same speaker (and role) within [GROUP_MS]. */
private fun sameGroup(a: Message?, b: Message?): Boolean =
    a != null && b != null && a.who == b.who && a.role == b.role && b.at - a.at in 0..GROUP_MS

private const val GROUP_MS = 3 * 60_000L

@Composable
private fun Bubble(m: Message, chips: Boolean, memory: Map<String, MemoryEvent>, actions: ChatActions, busy: Boolean, meals: MealView, first: Boolean, last: Boolean) {
    val mine = m.who == Who.OWNER
    val company = m.who == Who.COMPANY
    Row(Modifier.fillMaxWidth().padding(top = if (first) 6.dp else 0.dp).testTag(AgencyTags.BUBBLE + m.id), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (company) {
            // Her face and name only on the first line of a group (redesign §四); the later ones line up under it.
            if (first) Avatar(m.role) else Spacer(Modifier.width(AVATAR))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (company && first) Text(whoName(m.role), style = Type.Caption, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            val shape = when {
                mine -> RoundedCornerShape(topStart = 18.dp, topEnd = if (first) 6.dp else 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                else -> RoundedCornerShape(topStart = if (first) 6.dp else 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            }
            val accent = LocalAccent.current
            val color = when (m.who) {
                Who.OWNER -> accent.fill.copy(alpha = if (m.unsent) .45f else 1f)
                Who.COMPANY -> Sakura.White
                Who.SYSTEM -> Sakura.MilkTeaSoft
            }
            BoxWithConstraints {
                val max = if (mine) maxWidth * 0.86f else maxWidth
                Text(
                    m.text, style = Type.Chat.copy(color = if (mine) accent.onFill else Sakura.Text),
                    modifier = Modifier.widthIn(max = max).background(color, shape).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
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
            if (company) {
                m.refs.records.mapNotNull { meals.meals[it] }.forEach { meal ->
                    MealCard(meal, meals.pending.filter { it.mealId == meal.id }, meals.vault, actions.meal, busy)
                }
                m.refs.results.mapNotNull { meals.results[it] }.forEach { r -> SearchResultCard(r, actions.meal.onLink, actions.meal.onSavePlace, busy) }
                m.refs.photoRead?.let { meals.reads[it] }?.let { r -> app.dshmobile.ui.agency.meal.PhotoReadCard(r, actions.meal, busy) }
            }
            if (last) {
                Text(
                    java.time.Instant.ofEpochMilli(m.at).atZone(app.agency.core.time.AgencyClock.TAIPEI).toLocalTime().let { t -> String.format(java.util.Locale.ROOT, "%02d:%02d", t.hour, t.minute) },
                    style = Type.Caption, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private val AVATAR = 34.dp

/** 「葵」 on blush for her, 「體」 on aqua for the body teacher (redesign §四). */
@Composable
private fun Avatar(role: RoleKey?) {
    val (letter, tone) = when (role) {
        RoleKey.BODY -> stringResource(R.string.ag_avatar_body) to Sakura.AquaSoft
        RoleKey.MANAGER -> stringResource(R.string.ag_avatar_manager) to Sakura.Background
        null -> stringResource(R.string.ag_avatar_company) to Sakura.LilacSoft
    }
    Box(Modifier.size(AVATAR).background(tone, CircleShape).border(1.dp, Sakura.Gold.copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, color = Sakura.Text, fontSize = 15.sp)
    }
}

@Composable
private fun whoName(role: RoleKey?): String = stringResource(
    when (role) {
        RoleKey.MANAGER -> R.string.ag_who_manager
        RoleKey.BODY -> R.string.ag_who_body
        null -> R.string.ag_who_company
    },
)

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
