package app.dshmobile.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.coach.CoachEngine
import app.dshmobile.coach.CoachMessage
import app.dshmobile.coach.CoachRules
import app.dshmobile.coach.Speaker
import app.dshmobile.coach.Strip
import app.dshmobile.coach.AgencyRole
import app.dshmobile.coach.AgencySchedule
import app.dshmobile.coach.BaselineStep
import app.dshmobile.coach.TraineeCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura

private val Bubble = RoundedCornerShape(16.dp)
private val StripShape = RoundedCornerShape(14.dp)

/** The company's home: the 練習生證 on top, then the conversation with the 經紀人 and the teachers. The dock below sends to them. */
@Composable
fun CoachPage(engine: CoachEngine) {
    val ui by engine.ui.collectAsState()
    // 入社建檔: the first visit with no file yet, the 經紀人 starts it.
    LaunchedEffect(Unit) { engine.startOnboarding() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun handIn(step: BaselineStep, uri: android.net.Uri?, ext: String) {
        if (uri == null) return
        scope.launch {
            val name = "${if (step == BaselineStep.PHOTO) "photo" else "recording"}-${System.currentTimeMillis()}.${baselineExtension(context.contentResolver.getType(uri), ext)}"
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        java.io.File(engine.baselineDir, name).outputStream().use { input.copyTo(it) }
                    }
                }.isSuccess
            }
            if (ok) engine.onBaselineFile(step, name)
        }
    }
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) {
        handIn(BaselineStep.PHOTO, it, "jpg")
    }
    val recordingPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) {
        handIn(BaselineStep.RECORDING, it, "m4a")
    }
    // A new coach day (05:00) shows the moment the page does.
    LaunchedEffect(Unit) {
        engine.refresh()
        // Left open across the day start (05:00), the strip turns over to the new day on its own.
        while (true) {
            kotlinx.coroutines.delay(engine.nextRefreshDelay())
            engine.refresh()
        }
    }
    // Back from the background (a night asleep), the strip is recomputed at once.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) engine.refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val pad = LocalPagePadding.current
    val list = rememberLazyListState()
    val count = ui.messages.size + if (ui.busy) 1 else 0
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    Column(
        Modifier.fillMaxSize().testTag(Tags.COACH_PAGE).padding(top = pad.calculateTopPadding(), start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TraineeCardView(ui.card, ui.strip, onReopen = engine::reopenBaseline, onPhoto = { photoPicker.launch("image/*") }, onRecording = { recordingPicker.launch("audio/*") })
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = pad.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.messages.isEmpty()) item {
                Text(
                    stringResource(R.string.coach_empty), color = Sakura.TextSecondary, fontSize = 15.sp, lineHeight = 22.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                )
            }
            items(ui.messages, key = { it.id }) { m -> MessageBubble(m) }
            if (ui.busy) item {
                Text(stringResource(R.string.coach_busy), color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.testTag(Tags.COACH_BUSY))
            }
        }
    }
}

@Composable
private fun StripRow(s: Strip) {
    val kcal = if (s.overBy > 0) stringResource(R.string.coach_strip_over, s.overBy) else stringResource(R.string.coach_strip_left, s.remainingKcal)
    val protein = stringResource(R.string.coach_strip_protein, s.proteinLeftG)
    val goal = s.nextGoalKg?.let { stringResource(R.string.coach_strip_goal, CoachRules.kg(it)) } ?: stringResource(R.string.coach_strip_no_goal)
    Text(
        "$kcal ・ $protein ・ $goal",
        color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().testTag(Tags.COACH_STRIP),
    )
}

@Composable
private fun MessageBubble(m: CoachMessage) {
    val mine = m.who == Speaker.OWNER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        m.speaker?.let { r ->
            Text(
                stringResource(if (r == AgencyRole.AGENT) R.string.agency_role_agent else R.string.agency_role_body),
                color = Sakura.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp).testTag(Tags.AGENCY_ROLE + m.id),
            )
        }
        Text(
            m.text, color = Sakura.Text, fontSize = 15.sp, lineHeight = 22.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) Sakura.Primary.copy(alpha = .38f) else Sakura.White.copy(alpha = .88f), Bubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(Tags.COACH_MSG + m.id),
        )
    }
}

/** 練習生證: company, number, level, day, today's schedule with ticks, today's violations, the numbers, and 入社建檔. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TraineeCardView(c: TraineeCard, s: Strip, onReopen: () -> Unit, onPhoto: () -> Unit, onRecording: () -> Unit) {
    // Folded to one line by default (the conversation is the main surface); it opens by itself only while 入社資料 is missing
    // or a 入社建檔 question is open. A tap folds or unfolds it, and the choice is remembered.
    val auto = c.baselineMissing.isNotEmpty() || c.asking != null
    var chosen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    val open = chosen ?: auto
    val expandedWord = stringResource(R.string.agency_card_expanded)
    val collapsedWord = stringResource(R.string.agency_card_collapsed)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Sakura.White.copy(alpha = .82f), StripShape)
            .border(1.dp, Sakura.Primary.copy(alpha = .6f), StripShape)
            .clickable(onClickLabel = stringResource(if (open) R.string.agency_card_fold else R.string.agency_card_unfold)) { chosen = !open }
            .semantics { stateDescription = if (open) expandedWord else collapsedWord }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(Tags.AGENCY_CARD),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!open) {
            val kcal = if (s.overBy > 0) stringResource(R.string.agency_card_line_over, s.overBy) else stringResource(R.string.agency_card_line_left, s.remainingKcal)
            val missing = if (c.baselineMissing.isNotEmpty()) " ・ " + stringResource(R.string.agency_card_baseline_missing, c.baselineMissing.size) else ""
            Text(
                stringResource(R.string.agency_card_line, c.company, c.dayNumber, c.violations, kcal) + missing,
                color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                modifier = Modifier.testTag(Tags.AGENCY_CARD_LINE),
            )
            return@Column
        }
        Text(stringResource(R.string.agency_card_title, c.company), color = Sakura.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.agency_card_id, c.number, c.level, c.dayNumber), color = Sakura.Text, fontSize = 13.sp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agency_card_schedule) + "：", color = Sakura.TextSecondary, fontSize = 13.sp)
            c.slots.forEach { sl ->
                val time = sl.minute?.let { AgencySchedule.clock(it % (24 * 60)) + " " } ?: ""
                Text(
                    (if (sl.done) "✓ " else "○ ") + time + sl.label,
                    color = if (sl.done) Sakura.Text else Sakura.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.testTag(Tags.AGENCY_SLOT + sl.key),
                )
            }
        }
        Text(stringResource(R.string.agency_card_violations, c.violations), color = Sakura.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(Tags.AGENCY_VIOLATIONS))
        StripRow(s)
        if (c.baselineMissing.isNotEmpty()) {
            Text(stringResource(R.string.agency_card_baseline_missing, c.baselineMissing.size), color = Sakura.TextSecondary, fontSize = 13.sp)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.asking == null) CardButton(stringResource(R.string.agency_card_reopen), Tags.AGENCY_REOPEN, onReopen)
                if (BaselineStep.PHOTO in c.baselineMissing) CardButton(stringResource(R.string.agency_card_photo), Tags.AGENCY_PHOTO, onPhoto)
                if (BaselineStep.RECORDING in c.baselineMissing) CardButton(stringResource(R.string.agency_card_recording), Tags.AGENCY_RECORDING, onRecording)
            }
        }
    }
}

@Composable
private fun CardButton(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label, color = Sakura.Text, fontSize = 13.sp,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .background(Sakura.Primary.copy(alpha = .45f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
    )
}

/** The picked file's own extension (HEIC, MP3, WAV…) from its type; [fallback] when the type is unknown. */
internal fun baselineExtension(mime: String?, fallback: String): String =
    mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,5}")) } ?: fallback
