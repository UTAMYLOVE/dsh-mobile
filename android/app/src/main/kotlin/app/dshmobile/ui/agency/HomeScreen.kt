package app.dshmobile.ui.agency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.Discussion
import app.agency.core.brain.ProposalView
import app.agency.core.model.MemoryEvent
import app.agency.core.model.Message
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.agency.core.engine.IdolCardFacts
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.ImportNotice
import app.dshmobile.agency.StartState
import app.agency.core.brain.handlers.OnboardingStatus
import app.dshmobile.ui.agency.onboarding.BaselineBadges
import app.dshmobile.ui.agency.onboarding.OnboardingFlow
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 公司: the 練習生證 (arch T10), the import notice once, the one conversation (imported lines with their role labels) and the
 * composer.
 */
@Composable
fun HomeScreen(agency: AgencyApp, ready: StartState.Ready, actions: AgencyActions = AgencyActions()) {
    // collectOnMain / onMain below are defensive: on the phone these already run on the main thread; under the Compose test
    // rule they would not. Keep them (AgencyMainThreadTest fails without each).
    val revision by ready.brain.revision.collectOnMain()
    val busy by ready.brain.busy.collectOnMain()
    val notice by agency.notice.collectOnMain()
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision, limit) {
        onMain {
            val (list, count) = withContext(Dispatchers.IO) {
                val list = runCatching { ready.store.messages.recent(limit) }.getOrDefault(emptyList())
                list to runCatching { ready.store.messages.count() }.getOrDefault(list.size)
            }
            messages = list
            total = count
        }
    }
    // Her memory events (for the grey lines under her bubbles) and the proposals waiting for his yes (T12).
    val memory by produceState<Map<String, MemoryEvent>>(emptyMap(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.store.memory.events().associateBy { it.id } }.getOrDefault(emptyMap()) } }
    }
    val proposals by produceState<List<ProposalView>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.pendingProposals() }.getOrDefault(emptyList()) } }
    }
    val scope = rememberCoroutineScope()
    val chatActions = remember(agency, ready) {
        ChatActions(
            onResend = agency::resend,
            onOption = agency::send,
            onUndo = { id -> scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.undoMemory(id) } } } },
        )
    }
    val onboarding by produceState<OnboardingStatus?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.onboardingStatus() }.getOrNull() } }
    }
    var cardRevision by remember { mutableIntStateOf(0) }
    val settingsRevision by agency.settings.revision.collectOnMain()
    val card by produceState<IdolCardFacts?>(null, revision, cardRevision, settingsRevision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.card() }.getOrNull() } }
    }
    // The reminders start once 入社建檔 is walked through: that is when he is asked for notifications (once).
    LaunchedEffect(onboarding?.completed) { if (onboarding?.completed == true) actions.askNotifications() }
    Column(Modifier.fillMaxSize().imePadding().testTag(AgencyTags.HOME)) {
        card?.let { IdolCardView(agency, it, onboarding, onSaved = { cardRevision++ }) }
        onboarding?.let { status ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) { BaselineBadges(agency, status) }
        }
        notice?.let { NoticeBar(it, agency::dismissNotice) }
        ChatList(
            messages, hasOlder = total > messages.size, onOlder = { limit += PAGE }, modifier = Modifier.weight(1f),
            memory = memory, actions = chatActions, busy = busy,
        )
        ProposalStrip(proposals, busy) { id, yes -> scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.decide(id, yes) } } } }
        onboarding?.let { OnboardingFlow(agency, it, actions) }
        // The ride buttons come with the reminders, after 入社建檔 (the 入社建檔 panel has the room until then).
        if (onboarding?.completed == true) RideButtons(busy) { agency.send(it) }
        Composer(busy = busy, onSend = agency::send)
    }
}

private const val PAGE = 500

/**
 * 出門跑單 / 收工 (arch T13): the same words he may type; riding makes reminders sound once. 「這裡有問題」 (T12) opens 討論
 * with the manager.
 */
@Composable
private fun RideButtons(busy: Boolean, onSay: (String) -> Unit) {
    val start = stringResource(R.string.ag_ride_start)
    val end = stringResource(R.string.ag_ride_end)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftButton(start, onClick = { onSay(start) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_START))
        SoftButton(end, onClick = { onSay(end) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_END))
        SoftButton(stringResource(R.string.ag_discuss), onClick = { onSay(Discussion.OPEN_WORD) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.DISCUSS))
    }
}

@Composable
private fun NoticeBar(n: ImportNotice, onDismiss: () -> Unit) {
    val text = stringResource(
        when (n) {
            ImportNotice.PARTIAL -> R.string.ag_notice_partial
            ImportNotice.MEDIA_PENDING -> R.string.ag_notice_media
            ImportNotice.FAILED -> R.string.ag_notice_failed
        },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Sakura.Aqua.copy(alpha = .5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp).testTag(AgencyTags.NOTICE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(AgencyTags.NOTICE_OK)) {
            Text(stringResource(R.string.ag_notice_ok), color = Sakura.Text)
        }
    }
}
