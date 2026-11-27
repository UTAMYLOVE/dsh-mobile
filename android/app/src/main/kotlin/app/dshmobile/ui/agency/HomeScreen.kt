package app.dshmobile.ui.agency

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import app.dshmobile.ui.agency.today.QuickChips
import app.dshmobile.ui.agency.today.TodayActions
import app.dshmobile.ui.agency.today.TodayCard
import app.dshmobile.ui.agency.today.TodayList
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
import androidx.compose.runtime.key
import app.dshmobile.ui.agency.sign.BedtimeReviewSheet
import app.dshmobile.ui.agency.sign.ConditionPicker
import app.dshmobile.ui.agency.sign.SignTags
import app.dshmobile.ui.agency.training.TrainingTags

/**
 * 公司 (redesign 01/02), conversation first: the 練習生證 folded to one line (its idol card opens over the screen), the 今天 card
 * (with the pickers and proposals above it, at most [PANEL_SHARE] of the room, scrolling), the conversation in all the room left,
 * the quick chips and the composer.
 */
@Composable
fun HomeScreen(
    agency: AgencyApp, ready: StartState.Ready, actions: AgencyActions = AgencyActions(),
    onLessons: () -> Unit = {}, onOnboarding: () -> Unit = {}, onWarRoom: () -> Unit = {},
) {
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
                val reads = messages.mapNotNull { it.refs.photoRead }.distinct()
                MealView(
                    ids.mapNotNull { id -> runCatching { ready.store.meals.get(id) }.getOrNull() }.associateBy { it.id },
                    runCatching { ready.brain.pendingLabels() }.getOrDefault(emptyList()),
                    agency.mediaVault(),
                    results.mapNotNull { id -> runCatching { ready.store.places.result(id) }.getOrNull() }.associateBy { it.id },
                    reads.mapNotNull { id -> runCatching { ready.brain.photoRead(id) }.getOrNull() }.associateBy { it.id },
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
                onConfirmRead = agency::confirmPhoto, onDropRead = agency::dropPhoto,
            ),
        )
    }
    val onboarding by produceState<OnboardingStatus?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.onboardingStatus() }.getOrNull() } }
    }
    // The morning score after the sign-in and the bedtime review after the sign-out (T19).
    val conditionDue by produceState(false, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.conditionDue() }.getOrDefault(false) } }
    }
    val reviewOpen by produceState<app.agency.core.brain.handlers.ReviewDesk.Open?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.reviewOpen() }.getOrNull() } }
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
                    TrainingKind.CARDIO -> agency.cardioDone(seconds)
                    in app.agency.core.engine.Practice.TIMED -> agency.practiceDone(open.kind, seconds)
                    else -> agency.stretchDone(seconds)
                }
            },
            onClose = { training = null },
        )
        return
    }
    // 升級典禮 (T19e): a level card he has not seen yet, once.
    var seen by remember { mutableIntStateOf(agency.settings.ceremoniesSeen) }
    val levelCards by produceState<List<Triple<String, app.agency.core.model.LessonCategory, Int>>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.levelCards().reversed() }.getOrDefault(emptyList()) } }
    }
    levelCards.getOrNull(seen)?.let { (_, cat, l) ->
        app.dshmobile.ui.agency.eval.CeremonyScreen(cat, l) { seen++; agency.settings.ceremoniesSeen = seen }
        return
    }
    var singing by remember { mutableStateOf(false) }
    if (singing) {
        app.dshmobile.ui.agency.eval.SingingScreen(agency) { singing = false }
        return
    }
    // 每週評鑑 / 月評 take the whole screen while he hands them in.
    var eval by remember { mutableStateOf<SlotKey?>(null) }
    eval?.let { key ->
        app.dshmobile.ui.agency.eval.EvalScreen(agency, monthly = key == SlotKey.MONTHLY_EVAL, takeVideo = actions.takeVideo, onClose = { eval = null })
        return
    }
    val moodDue by produceState(false, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.moodDue() }.getOrDefault(false) } }
    }
    val lessonBoard by produceState<app.agency.core.brain.handlers.LessonBoard?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.lessonBoard() }.getOrNull() } }
    }
    val scores by produceState<List<Pair<String, Int>>>(emptyList(), revision) {
        onMain {
            value = withContext(Dispatchers.IO) {
                runCatching { ready.brain.months().lastOrNull()?.scores.orEmpty().map { it.item.zh to it.score } }.getOrDefault(emptyList())
            }
        }
    }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var list by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val c = card
    if (sheet && c != null) {
        IdolCardSheet(
            agency, c, onboarding, scores, onClose = { sheet = false }, onWall = { sheet = false; onWarRoom() },
            onFile = { sheet = false; onOnboarding() }, onSaved = { cardRevision++ },
        )
        return
    }
    fun openTraining(kind: TrainingKind, moves: Boolean) {
        scope.launch {
            training = withContext(Dispatchers.IO) {
                runCatching { ready.brain.routine(kind)?.let { TrainingOpen(kind, it, if (moves) ready.brain.strengthMoves() else emptyList()) } }.getOrNull()
            }
        }
    }
    val todayActions = TodayActions(
        say = agency::send,
        mealPhoto = actions.takeMealPhoto,
        mealMore = { actions.pick(app.dshmobile.agency.media.PhotoTarget.Meal) },
        training = { key ->
            when (key) {
                SlotKey.CORE -> openTraining(TrainingKind.CORE7, true)
                SlotKey.STRENGTH -> openTraining(TrainingKind.STRENGTH, true)
                SlotKey.CARDIO -> openTraining(TrainingKind.CARDIO, true)
                else -> openTraining(TrainingKind.STRETCH, true)
            }
        },
        walk = agency::walkDone,
        practice = { key ->
            val item = app.agency.core.engine.Practice.of(key)
            when (item?.handIn) {
                app.agency.core.engine.HandIn.TIMER -> openTraining(item.kind, false)
                app.agency.core.engine.HandIn.TAP -> agency.practiceTap(item.kind)
                app.agency.core.engine.HandIn.PHOTO -> actions.photo(if (key == SlotKey.SURPRISE_CHECK) app.dshmobile.agency.media.PhotoTarget.Check else app.dshmobile.agency.media.PhotoTarget.Body)
                app.agency.core.engine.HandIn.EVAL -> eval = key
                null -> Unit
            }
        },
        singing = { singing = true },
        lessons = onLessons,
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(Sakura.Page).imePadding()) {
    val room = maxHeight
    Column(Modifier.fillMaxSize().testTag(AgencyTags.HOME)) {
        c?.let { IdolLine(agency, it, onOpen = { sheet = true }) }
        // The pickers, proposals and the 今天 card share at most [PANEL_SHARE] of the room and scroll; the conversation keeps the
        // rest (never less than 40%), the chips and the composer are always on screen.
        // The conversation keeps at least 40% of the room after the line, the chips and the composer (about [FIXED] together).
        val cap = minOf(room * PANEL_SHARE, maxOf(room * PANEL_MIN_SHARE, room * (1f - CHAT_SHARE) - FIXED))
        // While he types (the keyboard up) the conversation has the screen; the card comes back when the line is sent.
        val typing = imeVisible()
        if (!typing) Column(Modifier.fillMaxWidth().heightIn(max = cap).verticalScroll(rememberScrollState()).testTag(AgencyTags.PANELS)) {
            notice?.let { NoticeBar(it, agency::dismissNotice) }
            MediaLine(agency)
            ProposalStrip(proposals, busy) { id, yes -> scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.decide(id, yes) } } } }
            labelling?.let { p ->
                LabelForm(p, onSave = { kcal, protein -> labelling = null; agency.label(p.mealId, p.itemIdx, kcal, protein) }, onClose = { labelling = null })
            }
            if (onboarding?.completed == true) {
                if (conditionDue) ConditionPicker { score, sleep, energy, ill -> agency.condition(score, sleep, energy, ill) }
                reviewOpen?.let { open -> key(open) { BedtimeReviewSheet(open) { good, fix -> agency.review(good, fix) } } }
                if (moodDue) app.dshmobile.ui.agency.sign.MoodPicker(agency::mood)
            }
            // 入社建檔 still missing items (by name, F3), after it was finished: one row to go and hand them in.
            onboarding?.takeIf { it.completed && it.missing.isNotEmpty() }?.let { st ->
                OnboardingBanner(st.missing.map { app.dshmobile.ui.agency.onboarding.short(it) }, onOnboarding)
            }
            c?.let {
                TodayCard(it, lessonBoard, busy, todayActions, onList = { list = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
        ChatList(
            messages, hasOlder = total > messages.size, onOlder = { limit += PAGE }, modifier = Modifier.weight(1f),
            memory = memory, actions = chatActions, busy = busy, meals = mealView,
        )
        QuickChips(c, busy, onSay = agency::send, onPrefill = { draft = it }, onPhoto = actions.photo, onPick = actions.pick)
        Composer(busy = busy, onSend = agency::send, text = draft, onText = { draft = it })
    }
    }
    if (list && c != null) TodayList(c) { list = false }
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

/** The keyboard is up. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun imeVisible(): Boolean = androidx.compose.foundation.layout.WindowInsets.isImeVisible

/** 「入社建檔 還差：…」 with 去補 (it opens the 入社建檔 page). */
@Composable
private fun OnboardingBanner(missing: List<String>, onGo: () -> Unit) {
    app.dshmobile.ui.kit.Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(app.dshmobile.ui.agency.onboarding.OnboardingTags.BANNER), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.ag_ob_banner, missing.joinToString("、")), style = app.dshmobile.ui.kit.Type.Support,
                modifier = Modifier.weight(1f).testTag(app.dshmobile.ui.agency.onboarding.OnboardingTags.MISSING),
            )
            SoftButton(stringResource(R.string.ag_ob_banner_go), onClick = onGo, size = app.dshmobile.ui.kit.ButtonSize.Small, modifier = Modifier.testTag(app.dshmobile.ui.agency.onboarding.OnboardingTags.HAND_IN_LATER))
        }
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
            app.dshmobile.agency.MediaNotice.NO_CAMERA_PERMISSION -> R.string.ag_media_no_camera_permission
        },
    )
    Text(text, color = Sakura.RoseBrown, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).testTag(AgencyTags.MEDIA_NOTICE))
}

private const val PAGE = 500

/** The most of the room the pickers, proposals and the 今天 card take before they scroll (the conversation keeps the rest). */
private const val PANEL_SHARE = 0.45f

/** On a small screen (or with the keyboard up) they still get this much, scrolling. */
private const val PANEL_MIN_SHARE = 0.2f

/** The conversation's share of the room it keeps. */
private const val CHAT_SHARE = 0.4f

/** The trainee line, the chips and the composer together. */
private val FIXED = 200.dp

/** A guided timer to show: its routine and, for the rounds, the moves the report asks about. */
private data class TrainingOpen(val kind: TrainingKind, val steps: List<RoutineStep>, val moves: List<StrengthMove>)

/** Test tags of the practice buttons: [PRACTICE] + the slot name. */
object PracticeTags {
    const val PRACTICE = "ag-practice-"
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
