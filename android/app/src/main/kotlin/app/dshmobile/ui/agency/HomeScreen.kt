package app.dshmobile.ui.agency

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.runtime.key
import app.dshmobile.ui.agency.sign.BedtimeReviewSheet
import app.dshmobile.ui.agency.sign.ConditionPicker
import app.dshmobile.ui.agency.sign.SignTags
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
    // The screen's height less the keyboard (no subcomposition: the conversation's effects never start during a measure).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val room = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - with(density) { ime.toDp() }).coerceAtLeast(ROOM_MIN)
    Column(Modifier.fillMaxSize().imePadding().testTag(AgencyTags.HOME)) {
        // The open card's rows take about [CARD_SHARE] of the screen and scroll inside it; with 補交 and 藝名 open too the whole card
        // takes at most [CARD_AREA_SHARE] and scrolls: the conversation and the composer keep their room on a small phone too.
        card?.let { c ->
            Column(Modifier.fillMaxWidth().heightIn(max = room * CARD_AREA_SHARE).verticalScroll(rememberScrollState()).testTag(AgencyTags.CARD_AREA)) {
                IdolCardView(agency, c, onboarding, onSaved = { cardRevision++ }, rowsMax = (room * CARD_SHARE - CARD_HEAD).coerceIn(CARD_ROWS_MIN, OPEN_ROWS_MAX))
            }
        }
        onboarding?.let { status ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) { BaselineBadges(agency, status) }
        }
        notice?.let { NoticeBar(it, agency::dismissNotice) }
        // The conversation and the panels under it share what is left after the card and the composer: the composer is never
        // squeezed out (with every rollout week open the buttons and pickers are taller than a small phone), and the panels leave
        // the conversation at least [CHAT_MIN] (or [PANEL_SHARE] of the room on a small screen) and scroll beyond it.
        ChatAndPanels(
            Modifier.weight(1f).fillMaxWidth(),
            chat = {
                ChatList(
                    messages, hasOlder = total > messages.size, onOlder = { limit += PAGE }, modifier = Modifier.fillMaxSize(),
                    memory = memory, actions = chatActions, busy = busy, meals = mealView,
                )
            },
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(AgencyTags.PANELS)) {
                ProposalStrip(proposals, busy) { id, yes -> scope.launch { withContext(Dispatchers.IO) { runCatching { ready.brain.decide(id, yes) } } } }
                onboarding?.let { OnboardingFlow(agency, it, actions) }
                // The quick buttons come with the reminders, after 入社建檔 (the 入社建檔 panel has the room until then).
                labelling?.let { p ->
                    LabelForm(p, onSave = { kcal, protein -> labelling = null; agency.label(p.mealId, p.itemIdx, kcal, protein) }, onClose = { labelling = null })
                }
                if (onboarding?.completed == true) {
                    if (conditionDue) ConditionPicker { score, sleep, energy, ill -> agency.condition(score, sleep, energy, ill) }
                    reviewOpen?.let { open -> key(open) { BedtimeReviewSheet(open) { good, fix -> agency.review(good, fix) } } }
                    if (moodDue) app.dshmobile.ui.agency.sign.MoodPicker(agency::mood)
                    MediaLine(agency)
                    QuickButtons(card, busy, onPhoto = actions.takeMealPhoto, onTarget = actions.photo, onPick = actions.pick) { agency.send(it) }
                    TrainingButtons(card, busy, onWalk = agency::walkDone) { kind ->
                        scope.launch {
                            val open = withContext(Dispatchers.IO) {
                                runCatching { ready.brain.routine(kind)?.let { TrainingOpen(kind, it, ready.brain.strengthMoves()) } }.getOrNull()
                            }
                            training = open
                        }
                    }
                    PracticeButtons(
                        card, busy,
                        onTimer = { kind ->
                            scope.launch {
                                training = withContext(Dispatchers.IO) { runCatching { ready.brain.routine(kind)?.let { TrainingOpen(kind, it, emptyList()) } }.getOrNull() }
                            }
                        },
                        onTap = agency::practiceTap,
                        onCheck = { actions.photo(app.dshmobile.agency.media.PhotoTarget.Check) },
                        onBody = { actions.photo(app.dshmobile.agency.media.PhotoTarget.Body) },
                        onEval = { eval = it },
                        onSinging = { singing = true },
                    )
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
            app.dshmobile.agency.MediaNotice.NO_CAMERA_PERMISSION -> R.string.ag_media_no_camera_permission
        },
    )
    Text(text, color = Sakura.RoseBrown, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).testTag(AgencyTags.MEDIA_NOTICE))
}

private const val PAGE = 500

/**
 * The conversation over the panels below it (proposals, 入社建檔, pickers, buttons): the panels are measured first and take what
 * they need up to the room less [CHAT_MIN] (at least [PANEL_SHARE] of it on a small screen), scrolling beyond; the conversation
 * takes the rest. A plain layout, not a subcomposition: the conversation's effects (its scroll to the newest line) never start
 * inside a measure.
 */
@Composable
private fun ChatAndPanels(modifier: Modifier, chat: @Composable () -> Unit, panels: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(contents = listOf(chat, panels), modifier = modifier) { (chatParts, panelParts), c ->
        val h = c.maxHeight
        val cap = maxOf(h - CHAT_MIN.roundToPx(), (h * PANEL_SHARE).toInt()).coerceIn(0, h)
        val placedPanels = panelParts.map { it.measure(c.copy(minHeight = 0, maxHeight = cap)) }
        val chatHeight = (h - placedPanels.sumOf { it.height }).coerceAtLeast(0)
        val placedChat = chatParts.map { it.measure(c.copy(minHeight = chatHeight, maxHeight = chatHeight)) }
        layout(c.maxWidth, h) {
            placedChat.forEach { it.place(0, 0) }
            var y = chatHeight
            placedPanels.forEach { it.place(0, y); y += it.height }
        }
    }
}

/** The least screen height the caps are worked out from (a split screen). */
private val ROOM_MIN = 320.dp

/** The least room the conversation keeps above the panels. */
private val CHAT_MIN = 160.dp

/** On a small screen the panels below the conversation may take at least this share of the room (they scroll beyond it). */
private const val PANEL_SHARE = 0.4f

/** About the most of the screen the open trainee card may take (its rows scroll beyond it). */
private const val CARD_SHARE = 0.35f

/** The most of the screen the whole card (with 補交 and 藝名) takes before it scrolls. */
private const val CARD_AREA_SHARE = 0.5f

/** The open card's photo, name and band above its rows. */
private val CARD_HEAD = 100.dp

/** The least room the open card's rows keep. */
private val CARD_ROWS_MIN = 60.dp

/**
 * The quick buttons: 拍這餐 (T15: the camera for the meal being reported), 喝完一壺 / 喝了半壺 (T17), 出門跑單 / 收工 (arch T13: the same words he may
 * type; riding makes reminders sound once) and 「這裡有問題」 (T12: 討論 with the manager). The row scrolls sideways.
 */
@Composable
private fun QuickButtons(
    card: IdolCardFacts?,
    busy: Boolean,
    onPhoto: () -> Unit,
    onTarget: (app.dshmobile.agency.media.PhotoTarget) -> Unit = {},
    onPick: (app.dshmobile.agency.media.PhotoTarget) -> Unit = {},
    onSay: (String) -> Unit,
) {
    val start = stringResource(R.string.ag_ride_start)
    val end = stringResource(R.string.ag_ride_end)
    val slots = card?.slots.orEmpty()
    fun done(key: SlotKey) = slots.firstOrNull { it.key == key }?.status == SlotStatus.DONE
    // 分週上線: a button of a feature not open yet is not shown.
    fun open(f: app.agency.core.schedule.Feature) = card?.open?.contains(f) ?: true
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 簽到 until he has, then 簽退 (T19): the same words he may type.
        if (open(app.agency.core.schedule.Feature.SIGN) && !done(SlotKey.WAKE)) {
            val w = stringResource(R.string.ag_sign_in)
            PinkButton(w, onClick = { onSay(w) }, enabled = !busy, modifier = Modifier.testTag(SignTags.SIGN_IN))
        }
        if (open(app.agency.core.schedule.Feature.MEALS)) {
            PinkButton(stringResource(R.string.ag_meal_photo), onClick = onPhoto, enabled = !busy, modifier = Modifier.testTag(MealTags.PHOTO))
            // T15c: a meal photo from the gallery (its capture time picks the meal; late is 補交).
            SoftButton(stringResource(R.string.ag_pick_meal), onClick = { onPick(app.dshmobile.agency.media.PhotoTarget.Meal) }, enabled = !busy, modifier = Modifier.testTag(MealTags.PICK))
            // T15b: a menu or a receipt for the image model to read (a list he confirms).
            SoftButton(stringResource(R.string.ag_photo_menu), onClick = { onTarget(app.dshmobile.agency.media.PhotoTarget.Menu) }, enabled = !busy, modifier = Modifier.testTag(MealTags.MENU))
            SoftButton(stringResource(R.string.ag_photo_receipt), onClick = { onTarget(app.dshmobile.agency.media.PhotoTarget.Receipt) }, enabled = !busy, modifier = Modifier.testTag(MealTags.RECEIPT))
        }
        // 身體照 (T15b): the teacher's words on a body photo.
        SoftButton(stringResource(R.string.ag_photo_body), onClick = { onTarget(app.dshmobile.agency.media.PhotoTarget.Body) }, enabled = !busy, modifier = Modifier.testTag(MealTags.BODY))
        // 喝水 (T17): the same words he may type.
        if (open(app.agency.core.schedule.Feature.WATER)) {
            val full = stringResource(R.string.ag_water_full)
            val half = stringResource(R.string.ag_water_half)
            SoftButton(full, onClick = { onSay(full) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.WATER_FULL))
            SoftButton(half, onClick = { onSay(half) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.WATER_HALF))
        }
        if (open(app.agency.core.schedule.Feature.RIDE)) {
            SoftButton(start, onClick = { onSay(start) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_START))
            SoftButton(end, onClick = { onSay(end) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.RIDE_END))
        }
        SoftButton(stringResource(R.string.ag_discuss), onClick = { onSay(Discussion.OPEN_WORD) }, enabled = !busy, modifier = Modifier.testTag(AgencyTags.DISCUSS))
        if (open(app.agency.core.schedule.Feature.SIGN) && done(SlotKey.WAKE) && !done(SlotKey.SIGN_OUT)) {
            val o = stringResource(R.string.ag_sign_out)
            SoftButton(o, onClick = { onSay(o) }, enabled = !busy, modifier = Modifier.testTag(SignTags.SIGN_OUT))
        }
    }
}

/** A guided timer to show: its routine and, for the rounds, the moves the report asks about. */
private data class TrainingOpen(val kind: TrainingKind, val steps: List<RoutineStep>, val moves: List<StrengthMove>)

/**
 * The training buttons (T18), only for what today's plan still owes: 核心 (the 7 minutes), 肌力 (the rounds), 體能 (its three
 * days), 伸展 and 快走完成 on a no-class day. Each timer reports by itself when it is done.
 */
@Composable
private fun TrainingButtons(card: IdolCardFacts?, busy: Boolean, onWalk: () -> Unit, onOpen: (TrainingKind) -> Unit) {
    val slots = card?.slots.orEmpty()
    fun open(key: SlotKey) = slots.firstOrNull { it.key == key }?.let { it.status != SlotStatus.DONE } == true
    val items = buildList {
        if (open(SlotKey.CORE)) add(Triple(R.string.ag_train_core_button, TrainingTags.CORE) { onOpen(TrainingKind.CORE7) })
        if (open(SlotKey.STRENGTH)) add(Triple(R.string.ag_train_strength_button, TrainingTags.STRENGTH) { onOpen(TrainingKind.STRENGTH) })
        if (open(SlotKey.CARDIO)) add(Triple(R.string.ag_train_cardio_button, TrainingTags.CARDIO) { onOpen(TrainingKind.CARDIO) })
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

/** Test tags of the practice buttons: [PRACTICE] + the slot name. */
object PracticeTags {
    const val PRACTICE = "ag-practice-"
}

/**
 * The practice buttons (rollout weeks 3 and 4), only for what today's plan still owes: each timed item opens its guided timer,
 * a tap item records at once, 突擊檢查 and 全身對比照 open the camera, the evaluations their hand-in screen.
 */
@Composable
private fun PracticeButtons(
    card: IdolCardFacts?,
    busy: Boolean,
    onTimer: (TrainingKind) -> Unit,
    onTap: (TrainingKind) -> Unit,
    onCheck: () -> Unit,
    onBody: () -> Unit,
    onEval: (SlotKey) -> Unit,
    onSinging: () -> Unit = {},
) {
    // 唱歌分析 opens with the vocal practice (rollout week 3: 聲樂基本功).
    if (card?.open?.contains(app.agency.core.schedule.Feature.VOCAL_BASICS) == true) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            SoftButton(stringResource(R.string.ag_sing_open), onClick = onSinging, enabled = !busy, modifier = Modifier.testTag(PracticeTags.PRACTICE + "SINGING"))
        }
    }
    val owed = card?.slots.orEmpty().filter { it.key.kind == app.agency.core.schedule.SlotKind.PRACTICE && it.status != SlotStatus.DONE && it.status != SlotStatus.MISSED && (it.key != SlotKey.SURPRISE_CHECK || it.status == SlotStatus.OPEN) }
    if (owed.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        owed.map { it.key }.distinct().forEach { key ->
            val item = app.agency.core.engine.Practice.of(key) ?: return@forEach
            val click: () -> Unit = when (item.handIn) {
                app.agency.core.engine.HandIn.TIMER -> ({ onTimer(item.kind) })
                app.agency.core.engine.HandIn.TAP -> ({ onTap(item.kind) })
                app.agency.core.engine.HandIn.PHOTO -> if (key == SlotKey.SURPRISE_CHECK) onCheck else onBody
                app.agency.core.engine.HandIn.EVAL -> ({ onEval(key) })
            }
            val important = key == SlotKey.SURPRISE_CHECK || item.handIn == app.agency.core.engine.HandIn.EVAL
            if (important) {
                PinkButton(key.zh, onClick = click, enabled = !busy, modifier = Modifier.testTag(PracticeTags.PRACTICE + key.name))
            } else {
                SoftButton(key.zh, onClick = click, enabled = !busy, modifier = Modifier.testTag(PracticeTags.PRACTICE + key.name))
            }
        }
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
