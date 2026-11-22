package app.dshmobile.ui.agency.lessons

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.Brain
import app.agency.core.brain.handlers.LessonBoard
import app.agency.core.brain.handlers.LessonView
import app.agency.core.lessons.AskedQuestion
import app.agency.core.lessons.LessonPlan
import app.agency.core.lessons.LessonStage
import app.agency.core.model.LessonCategory
import app.agency.core.model.PracticalKind
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActions
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.agency.onboarding.Recorder
import app.dshmobile.ui.kit.Chip
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LessonTags {
    const val SCREEN = "ag-lessons"
    const val TODAY = "ag-lessons-today"
    const val LAST = "ag-lessons-last"
    const val FIND = "ag-lessons-find"
    const val CATEGORY = "ag-lessons-cat-"
    const val CANDIDATE = "ag-lessons-candidate-"
    const val YES = "ag-lessons-yes-"
    const val NO = "ag-lessons-no-"
    const val LIST = "ag-lessons-list-"
    const val GO = "ag-lessons-go-"
    const val WATCHED = "ag-lessons-watched-"
    const val POINT = "ag-lessons-point-"
    const val POINTS_SEND = "ag-lessons-points-send"
    const val OPTION = "ag-lessons-opt-"
    const val REVIEW_OPTION = "ag-lessons-ropt-"
    const val SUBMIT = "ag-lessons-submit"
    const val NOTE = "ag-lessons-note"
    const val NOTE_SEND = "ag-lessons-note-send"
    const val PRACTICAL = "ag-lessons-practical-"
    const val REVIEW = "ag-lessons-review-"
    const val REVIEW_SUBMIT = "ag-lessons-review-submit-"
    const val HISTORY = "ag-lessons-history-"
}

/**
 * 理論課 (T19c): today's lessons with their step (待看／先寫三個重點／待考／寫筆記／通過), the one open now with what to do next
 * (去看 hands the stored link to his YouTube app or browser; 看完了; the three questions, 交卷; the note), the 複習考 due, 找影片
 * and the candidates (好 puts one in 「課程清單」, 不要 drops it), the list, and the scores. The manager's last lesson line
 * shows on top. Every rule is code's in the brain; this only shows and asks.
 */
@Composable
fun LessonsScreen(agency: AgencyApp, ready: StartState.Ready, actions: AgencyActions) {
    val revision by ready.brain.revision.collectOnMain()
    val board by produceState<LessonBoard?>(null, revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.lessonBoard() }.getOrNull() } }
    }
    val last by produceState<String?>(null, revision) {
        onMain {
            value = withContext(Dispatchers.IO) {
                runCatching { ready.store.messages.recent(20).lastOrNull { it.kind == Brain.LESSON_KIND || it.kind == Brain.LESSON_FOUND_KIND }?.text }.getOrNull()
            }
        }
    }
    val busy by ready.brain.busy.collectOnMain()
    val scope = rememberCoroutineScope()
    fun go(id: String) {
        scope.launch { agency.openLesson(id)?.let { url -> onMain { actions.openLink(url) } } }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(LessonTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        last?.let { Panel(title = stringResource(R.string.ag_lesson_said)) { Text(it, color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.testTag(LessonTags.LAST)) } }
        val b = board ?: return@Column
        Today(b, agency, actions, busy, ::go)
        if (b.reviews.isNotEmpty()) Reviews(b, agency, busy)
        Find(b, agency, busy)
        if (b.list.isNotEmpty()) {
            Panel(title = stringResource(R.string.ag_lesson_list_title, b.list.size)) {
                b.list.forEach { l ->
                    Text(
                        "${l.category.zh}・${l.title}" + if (l.locked) "（${stringResource(R.string.ag_lesson_locked)}）" else "",
                        color = if (l.locked) Sakura.TextSecondary else Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(LessonTags.LIST + l.id),
                    )
                }
            }
        }
        if (b.history.isNotEmpty()) {
            Panel(title = stringResource(R.string.ag_lesson_history_title)) {
                b.history.forEach { l ->
                    val first = l.score?.let { stringResource(R.string.ag_lesson_score, it) } ?: stringResource(R.string.ag_lesson_points_instead)
                    val review = l.reviewScore?.let { "・" + stringResource(R.string.ag_lesson_review_score, it) } ?: ""
                    val retry = if (l.retries > 0) "・" + stringResource(R.string.ag_lesson_retries, l.retries) else ""
                    Text(
                        shortDay(l.passedDay) + " " + l.category.zh + "・" + l.title + '\n' + first + retry + review,
                        color = Sakura.Text, fontSize = 13.sp, modifier = Modifier.testTag(LessonTags.HISTORY + l.id),
                    )
                }
            }
        }
    }
}

@Composable
private fun Today(b: LessonBoard, agency: AgencyApp, actions: AgencyActions, busy: Boolean, go: (String) -> Unit) {
    Panel(title = stringResource(R.string.ag_lesson_today_title, b.passedToday, LessonPlan.DAILY), modifier = Modifier.testTag(LessonTags.TODAY)) {
        if (!b.started) Text(stringResource(R.string.ag_lesson_not_started), color = Sakura.TextSecondary, fontSize = 14.sp)
        b.today.forEach { l ->
            Text(
                "${l.category.zh}・${l.title}", color = if (l.locked) Sakura.TextSecondary else Sakura.Text, fontSize = 15.sp,
                fontWeight = if (!l.locked && l.stage != LessonStage.PASSED) FontWeight.Medium else FontWeight.Normal,
            )
            Text(if (l.locked) stringResource(R.string.ag_lesson_locked) else l.stage.zh, color = Sakura.TextSecondary, fontSize = 13.sp)
            if (!l.locked) key(l.id) { Step(l, b.questions, agency, actions, busy, go) }
        }
    }
}

/** What the open lesson asks for now (and a passed one's optional 實作). */
@Composable
private fun Step(l: LessonView, questions: List<AskedQuestion>, agency: AgencyApp, actions: AgencyActions, busy: Boolean, go: (String) -> Unit) {
    when (l.stage) {
        LessonStage.TO_WATCH -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinkButton(stringResource(R.string.ag_lesson_go), onClick = { go(l.id) }, modifier = Modifier.testTag(LessonTags.GO + l.id))
            SoftButton(stringResource(R.string.ag_lesson_watched), enabled = !busy, onClick = { agency.lessonWatched(l.id) }, modifier = Modifier.testTag(LessonTags.WATCHED + l.id))
        }
        LessonStage.KEY_POINTS -> {
            val points = remember { mutableStateListOf("", "", "") }
            Text(stringResource(R.string.ag_lesson_points_hint, LessonPlan.POINT_HAN), color = Sakura.TextSecondary, fontSize = 13.sp)
            points.indices.forEach { i ->
                OutlinedTextField(
                    value = points[i], onValueChange = { points[i] = it.take(120) }, singleLine = true,
                    label = { Text(stringResource(R.string.ag_lesson_point, i + 1)) }, modifier = Modifier.fillMaxWidth().testTag(LessonTags.POINT + i),
                )
            }
            PinkButton(
                stringResource(R.string.ag_lesson_send), enabled = !busy && points.all { it.isNotBlank() },
                onClick = { agency.lessonKeyPoints(l.id, points.toList()) }, modifier = Modifier.testTag(LessonTags.POINTS_SEND),
            )
        }
        LessonStage.TO_QUIZ -> if (questions.isNotEmpty()) {
            Quiz(questions, LessonTags.OPTION, LessonTags.SUBMIT, busy) { agency.answerLesson(l.id, it) }
        } else {
            Text(stringResource(R.string.ag_lesson_wait_quiz), color = Sakura.TextSecondary, fontSize = 13.sp)
        }
        LessonStage.NOTE -> {
            var note by rememberSaveable { mutableStateOf("") }
            val n = LessonPlan.han(note)
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(1000) }, minLines = 3,
                label = { Text(stringResource(R.string.ag_lesson_note, LessonPlan.NOTE_HAN)) }, modifier = Modifier.fillMaxWidth().testTag(LessonTags.NOTE),
            )
            Text(stringResource(R.string.ag_lesson_note_count, n), color = Sakura.TextSecondary, fontSize = 12.sp)
            PinkButton(
                stringResource(R.string.ag_lesson_send), enabled = !busy && n >= LessonPlan.NOTE_HAN,
                onClick = { agency.lessonNote(l.id, note) }, modifier = Modifier.testTag(LessonTags.NOTE_SEND),
            )
        }
        LessonStage.PASSED -> {
            val kind = l.category.practical
            if (kind != null && !l.practicalDone) Practical(l, kind, agency, actions)
            else if (kind != null) Text(stringResource(R.string.ag_lesson_practical_done), color = Sakura.TextSecondary, fontSize = 13.sp)
        }
    }
}

/** The optional 實作: a photo through the camera, or a recording on the phone (it never leaves it). */
@Composable
private fun Practical(l: LessonView, kind: PracticalKind, agency: AgencyApp, actions: AgencyActions) {
    var recording by remember { mutableStateOf(false) }
    Text(stringResource(R.string.ag_lesson_practical, kind.zh), color = Sakura.TextSecondary, fontSize = 13.sp)
    when {
        kind != PracticalKind.RECORDING ->
            SoftButton(stringResource(R.string.ag_lesson_practical_photo), onClick = { actions.takeLessonPhoto(l.id) }, modifier = Modifier.testTag(LessonTags.PRACTICAL + l.id))
        !recording ->
            SoftButton(stringResource(R.string.ag_lesson_practical_record), onClick = { recording = true }, modifier = Modifier.testTag(LessonTags.PRACTICAL + l.id))
        else -> Recorder(agency) { file, ms -> agency.handInLessonRecording(l.id, file, ms) }
    }
}

/** Three questions (or one fresh one): a pick per question, then 交卷. The answers stay in the brain. */
@Composable
private fun Quiz(questions: List<AskedQuestion>, optionTag: String, submitTag: String, busy: Boolean, onSubmit: (List<Int>) -> Unit) {
    val picks = remember(questions.map { it.index }) { mutableStateListOf<Int?>().apply { repeat(questions.size) { add(null) } } }
    questions.forEachIndexed { qi, q ->
        Text("${qi + 1}. ${q.q}", color = Sakura.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            q.options.forEachIndexed { oi, o ->
                Chip(o, onClick = { picks[qi] = oi }, marked = picks[qi] == oi, modifier = Modifier.testTag("$optionTag${q.index}-$oi"))
            }
        }
    }
    PinkButton(
        stringResource(R.string.ag_lesson_submit), enabled = !busy && picks.all { it != null },
        onClick = { onSubmit(picks.map { it!! }) }, modifier = Modifier.testTag(submitTag),
    )
}

@Composable
private fun Reviews(b: LessonBoard, agency: AgencyApp, busy: Boolean) {
    Panel(title = stringResource(R.string.ag_lesson_review_title)) {
        Text(stringResource(R.string.ag_lesson_review_hint), color = Sakura.TextSecondary, fontSize = 13.sp)
        b.reviews.forEach { r ->
            Text("${r.category.zh}・${r.title}", color = Sakura.Text, fontSize = 15.sp)
            val qs = b.reviewQuestions[r.id]
            if (qs.isNullOrEmpty()) {
                SoftButton(stringResource(R.string.ag_lesson_review_start), enabled = !busy, onClick = { agency.startReview(r.id) }, modifier = Modifier.testTag(LessonTags.REVIEW + r.id))
            } else {
                key(r.id) { Quiz(qs, LessonTags.REVIEW_OPTION, LessonTags.REVIEW_SUBMIT + r.id, busy) { agency.answerReview(r.id, it) } }
            }
        }
    }
}

@Composable
private fun Find(b: LessonBoard, agency: AgencyApp, busy: Boolean) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    Panel(title = stringResource(R.string.ag_lesson_find_title)) {
        Text(stringResource(R.string.ag_lesson_find_hint), color = Sakura.TextSecondary, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LessonCategory.entries.forEach { c ->
                Chip(c.zh, onClick = { category = if (category == c.name) null else c.name }, marked = category == c.name, modifier = Modifier.testTag(LessonTags.CATEGORY + c.name))
            }
        }
        PinkButton(
            stringResource(R.string.ag_lesson_find), enabled = !busy, onClick = { agency.findVideos(LessonCategory.parse(category)) },
            modifier = Modifier.testTag(LessonTags.FIND),
        )
        b.candidates.forEach { c ->
            Column(Modifier.padding(top = 6.dp).testTag(LessonTags.CANDIDATE + c.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${c.category.zh}・${c.title}", color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                c.summary?.takeIf { it.isNotBlank() }?.let { Text(it, color = Sakura.TextSecondary, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.ag_lesson_yes), onClick = { if (!busy) agency.approveLesson(c.id) }, modifier = Modifier.testTag(LessonTags.YES + c.id))
                    Chip(stringResource(R.string.ag_lesson_no), onClick = { if (!busy) agency.dropLesson(c.id) }, modifier = Modifier.testTag(LessonTags.NO + c.id))
                }
            }
        }
    }
}

/** `2026-12-07` → `12/07`. */
private fun shortDay(day: String?): String = day?.drop(5)?.replace('-', '/').orEmpty()
