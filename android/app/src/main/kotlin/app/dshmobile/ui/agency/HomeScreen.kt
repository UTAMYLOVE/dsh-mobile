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
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import app.agency.core.model.Meal
import app.agency.core.model.PendingLabel
import app.dshmobile.ui.agency.meal.LabelForm
import app.dshmobile.ui.agency.meal.MealActions
import app.dshmobile.ui.agency.meal.MealTags
import app.dshmobile.ui.agency.meal.PhotoViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.agency.core.engine.RoutineStep
import app.agency.core.engine.SlotStatus
import app.agency.core.engine.StrengthMove
import app.agency.core.model.TrainingKind
import app.agency.core.schedule.SlotKey
import app.dshmobile.ui.agency.training.TrainingScreen
import app.dshmobile.ui.agency.training.TrainingTags

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
    // The meals the lines point at (T15), the items waiting for a label, and the dialogs for a photo and a label.
    val mealView by produceState(MealView(), revision, messages) {
        onMain {
            value = withContext(Dispatchers.IO) {
                val ids = messages.flatMap { it.refs.records }.distinct()
                val results = messages.flatMap { it.refs.results }.distinct()
                MealView(
                    ids.mapNotNull { id -> runCatching { ready.store.meals.get(id) }.getOrNull() }.associateBy { it.id },
                    runCatching { ready.brain.pendingLabels() }.getOrDefault(emptyList()),
                    agency.mediaVault(),
                    results.mapNotNull { id -> runCatching { ready.store.places.result(id) }.getOrNull() }.associateBy { it.id },
                )
            }
        }
    }
    var viewing by remember { mutableStateOf<Meal?>(null) }
    var labelling by remember { mutableStateOf<PendingLabel?>(null) }
    val chatActions = remember(agency, ready, actions) {
        ChatActions(
            onResend = agency::resend,
            onOption = agency::send,
            onUndo = { id -> scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.undoMemory(id) } } } },
            meal = MealActions(
                onPhoto = actions.takeMealPhoto, onOpen = { viewing = it }, onLabel = { labelling = it }, onLink = actions.openLink,
                onSavePlace = { id -> scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.proposeSavePlace(id) } } } },
            ),
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
    // A guided timer (T18) takes the whole screen while it runs.
    var training by remember { mutableStateOf<TrainingOpen?>(null) }
    training?.let { open ->
        TrainingScreen(
            open.kind, open.steps, open.moves,
            onFinished = { seconds, report ->
                training = null
                when (open.kind) {
                    TrainingKind.CORE7 -> agency.coreDone(seconds)
                    TrainingKind.STRENGTH -> report?.let(agency::strengthDone)
                    else -> agency.stretchDone(seconds)
                }
            },
            onClose = { training = null },
        )
        return
    }
    Column(Modifier.fillMaxSize().imePadding().testTag(AgencyTags.HOME)) {
        card?.let { IdolCardView(agency, it, onboarding, onSaved = { cardRevision++ }) }
        onboarding?.let { status ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) { BaselineBadges(agency, status) }
        }
        notice?.let { NoticeBar(it, agency::dismissNotice) }
        ChatList(
            messages, hasOlder = total > messages.size, onOlder = { limit += PAGE }, modifier = Modifier.weight(1f),
            memory = memory, actions = chatActions, busy = busy, meals = mealView,
        )
        ProposalStrip(proposals, busy) { id, yes -> scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.decide(id, yes) } } } }
        onboarding?.let { OnboardingFlow(agency, it, actions) }
        // The quick buttons come with the reminders, after 入社建檔 (the 入社建檔 panel has the room until then).
        labelling?.let { p ->
            LabelForm(p, onSave = { kcal, protein -> labelling = null; agency.label(p.mealId, p.itemIdx, kcal, protein) }, onClose = { labelling = null })
        }
        if (onboarding?.completed == true) {
            MediaLine(agency)
            QuickButtons(busy, onPhoto = actions.takeMealPhoto) { agency.send(it) }
            TrainingButtons(card, busy, onWalk = agency::walkDone) { kind ->
                scope.launch {
                    val open = withContext(Dispatchers.IO) {
                        runCatching { ready.brain.routine(kind)?.let { TrainingOpen(kind, it, ready.brain.strengthMoves()) } }.getOrNull()
                    }
                    training = open
                }
            }
        }
        Composer(busy = busy, onSend = agency::send)
    }
    viewing?.let { m ->
        PhotoViewer(
            m, agency.mediaVault(),
            onDelete = {
                viewing = null
                scope.launch { agency.deleteMealPhoto(m.id) }
            },
            onClose = { viewing = null },
        )
    }
}

/** Why the last meal photo was not kept (no camera app, the data lock, not saved), until the next try. */
@Composable
private fun MediaLine(agency: AgencyApp) {
    val notice by agency.mediaNotice.collectOnMain()
    val n = notice ?: return
    val text = stringResource(
        when (n) {
            app.dshmobile.agency.MediaNotice.NO_CAMERA -> R.string.ag_media_no_camera
            app.dshmobile.agency.MediaNotice.LOCKED -> R.string.ag_media_locked
            app.dshmobile.agency.MediaNotice.NOT_SAVED -> R.string.ag_media_not_saved
        },
    )
    Text(text, color = Sakura.RoseBrown, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).testTag(AgencyTags.MEDIA_NOTICE))
}

private const val PAGE = 500

/**
 * The quick buttons: 拍這餐 (T15: the camera for the meal being reported), 喝完一壺 / 喝了半壺 (T17), 出門跑單 / 收工 (arch T13: the same words he may
 * type; riding makes reminders sound once) and 「這裡有問題」 (T12: 討論 with the manager). The row scrolls sideways.
 */
@Composable
private fun QuickButtons(busy: Boolean, onPhoto: () -> Unit, onSay: (String) -> Unit) {
    val start = stringResource(R.string.ag_ride_start)
    val end = stringResource(R.string.ag_ride_end)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PinkButton(stringResource(R.string.ag_meal_photo), onClick = onPhoto, enabled = !busy, modifier = Modifier.testTag(MealTags.PHOTO))
        // 喝水 (T17): the same words he may type.
        val full = stringResource(R.string.ag_water_full)
        val half = stringResource(R.string.ag_water_half)
        SoftButton(full, onClick = { onSay(full) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.WATER_FULL))
        SoftButton(half, onClick = { onSay(half) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.WATER_HALF))
        SoftButton(start, onClick = { onSay(start) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_START))
        SoftButton(end, onClick = { onSay(end) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_END))
        SoftButton(stringResource(R.string.ag_discuss), onClick = { onSay(Discussion.OPEN_WORD) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.DISCUSS))
    }
}

/** A guided timer to show: its routine and, for the rounds, the moves the report asks about. */
private data class TrainingOpen(val kind: TrainingKind, val steps: List<RoutineStep>, val moves: List<StrengthMove>)

/**
 * The training buttons (T18), only for what today's plan still owes: 核心 (the 7 minutes), 肌力 (the rounds), 伸展 (any day, a
 * rest day's too) and 快走完成 on a no-class day. Each timer reports by itself when it is done.
 */
@Composable
private fun TrainingButtons(card: IdolCardFacts?, busy: Boolean, onWalk: () -> Unit, onOpen: (TrainingKind) -> Unit) {
    val slots = card?.slots.orEmpty()
    fun open(key: SlotKey) = slots.firstOrNull { it.key == key }?.let { it.status != SlotStatus.DONE } == true
    val items = buildList {
        if (open(SlotKey.CORE)) add(Triple(R.string.ag_train_core_button, TrainingTags.CORE) { onOpen(TrainingKind.CORE7) })
        if (open(SlotKey.STRENGTH)) add(Triple(R.string.ag_train_strength_button, TrainingTags.STRENGTH) { onOpen(TrainingKind.STRENGTH) })
        if (open(SlotKey.STRETCH)) add(Triple(R.string.ag_train_stretch_button, TrainingTags.STRETCH) { onOpen(TrainingKind.STRETCH) })
        if (open(SlotKey.WALK)) add(Triple(R.string.ag_train_walk_button, TrainingTags.WALK, onWalk))
    }
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, tag, onClick) -> SoftButton(stringResource(label), onClick = onClick, enabled = !busy, modifier = Modifier.testTag(tag)) }
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
