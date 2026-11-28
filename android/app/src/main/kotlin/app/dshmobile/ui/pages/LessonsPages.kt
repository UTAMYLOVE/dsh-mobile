package app.dshmobile.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.agency.core.agent.CompanyApi
import app.dshmobile.R
import app.dshmobile.ui.LessonRow
import app.dshmobile.ui.LessonState
import app.dshmobile.ui.LessonsUi
import app.dshmobile.ui.LocalIo
import app.dshmobile.ui.QuizResult
import app.dshmobile.ui.QuizView
import app.dshmobile.ui.rememberIoRunner
import app.dshmobile.ui.rememberLoaded
import app.dshmobile.ui.theme.Block
import app.dshmobile.ui.theme.ChipPill
import app.dshmobile.ui.theme.InkField
import app.dshmobile.ui.theme.KText
import app.dshmobile.ui.theme.Pal
import app.dshmobile.ui.theme.PageFrame
import app.dshmobile.ui.theme.PillButton
import app.dshmobile.ui.theme.PillShape
import app.dshmobile.ui.theme.Type
import app.dshmobile.ui.theme.sticker
import app.dshmobile.ui.theme.tap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The lessons list: videos waiting for his yes, ones to watch, quizzes and notes to do, and a box for 「我想學…」. */
@Composable
fun LessonsPage(lessons: LessonsUi, api: CompanyApi, onBack: () -> Unit, onQuiz: (Long, Boolean) -> Unit, onWished: () -> Unit) {
    val list = rememberLoaded { lessons.lessons() }
    val run = rememberIoRunner()
    val scope = rememberCoroutineScope()
    var wish by remember { mutableStateOf("") }
    val io = LocalIo.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val wishFmt = { w: String -> context.getString(R.string.say_wish, w) }
    PageFrame(stringResource(R.string.lessons_title), onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { InkField(wish, { wish = it }, stringResource(R.string.lessons_wish_hint), tag = "wish") }
            PillButton(stringResource(R.string.lessons_wish_send), {
                val w = wish.trim()
                if (w.isNotEmpty()) {
                    scope.launch { withContext(io) { api.say(wishFmt(w)) } }
                    onWished()
                }
            }, Modifier.testTag("wish_send"), enabled = wish.isNotBlank(), size = Type.Small)
        }
        val rows = list.value
        if (rows != null && rows.isEmpty()) KText(stringResource(R.string.lessons_empty), alpha = 0.7f)
        rows?.forEach { row ->
            LessonCard(
                row,
                onApprove = { yes -> run { lessons.approve(row.id, yes) }; list.reload() },
                onWatch = { run { lessons.watch(row.id) } },
                onQuiz = { note -> onQuiz(row.id, note) },
            )
        }
    }
}

@Composable
private fun LessonCard(row: LessonRow, onApprove: (Boolean) -> Unit, onWatch: () -> Unit, onQuiz: (Boolean) -> Unit) {
    Block(Modifier.testTag("lesson_${row.id}")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.sticker(Pal.Lavender, PillShape, shadow = 0.dp, outline = 1.5.dp).padding(horizontal = 10.dp, vertical = 2.dp)) {
                KText(row.subject, size = Type.Tiny, bold = true)
            }
            row.minutes?.let { KText(stringResource(R.string.card_minutes, it), size = Type.Tiny, alpha = 0.7f) }
        }
        KText(row.title, size = Type.Heading, bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (row.state) {
                LessonState.PROPOSED -> {
                    PillButton(stringResource(R.string.card_add), { onApprove(true) }, Modifier.testTag("lesson_add_${row.id}"), Pal.Mint, Type.Small)
                    PillButton(stringResource(R.string.card_no), { onApprove(false) }, Modifier.testTag("lesson_no_${row.id}"), Pal.Paper, Type.Small)
                }
                LessonState.APPROVED -> PillButton(stringResource(R.string.card_watch), onWatch, Modifier.testTag("lesson_watch_${row.id}"), Pal.Lavender, Type.Small)
                LessonState.WATCHED -> PillButton(stringResource(R.string.lesson_do_quiz), { onQuiz(false) }, Modifier.testTag("lesson_quiz_${row.id}"), Pal.PinkStrong, Type.Small)
                LessonState.PASSED -> PillButton(stringResource(R.string.lesson_do_note), { onQuiz(true) }, Modifier.testTag("lesson_note_${row.id}"), Pal.PinkStrong, Type.Small)
                LessonState.REVIEW_DUE -> PillButton(stringResource(R.string.lesson_do_review), { onQuiz(false) }, Modifier.testTag("lesson_review_${row.id}"), Pal.Butter, Type.Small)
                LessonState.NOTED -> KText(stringResource(R.string.lesson_done), size = Type.Small, alpha = 0.6f)
            }
        }
    }
}

/**
 * The quiz of one lesson, then the note. A lesson without questions goes straight to the note. A failed quiz offers a new one.
 * [startWithNote]: the quiz was passed earlier and only the note is left.
 */
@Composable
fun QuizPage(lessons: LessonsUi, lessonId: Long, startWithNote: Boolean, onBack: () -> Unit, onFinished: () -> Unit) {
    val io = LocalIo.current
    var attempt by remember { mutableIntStateOf(0) }
    val quiz by produceState<QuizView?>(null, lessonId, attempt) { value = lessons.quiz(lessonId) }
    val answers = remember(quiz) { mutableStateListOf<Int>().also { l -> repeat(quiz?.questions?.size ?: 0) { l.add(-1) } } }
    var result by remember(quiz) { mutableStateOf<QuizResult?>(null) }
    var note by remember { mutableStateOf("") }
    var noteRejected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val q = quiz
    PageFrame(q?.title?.ifBlank { null } ?: stringResource(R.string.quiz_title), onBack) {
        if (q == null) return@PageFrame
        if (q.unavailable != null) {
            // No quiz can be made right now (the model could not be reached): the lesson stays as it is.
            Block(Modifier.testTag("quiz_unavailable"), fill = Pal.Butter) { KText(q.unavailable, bold = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.quiz_retry), { attempt++ }, Modifier.testTag("quiz_again"), Pal.Mint, Type.Small)
                PillButton(stringResource(R.string.quiz_back), onBack, Modifier.testTag("quiz_leave"), Pal.White, Type.Small)
            }
            return@PageFrame
        }
        val noteMode = startWithNote || q.questions.isEmpty() || result?.passed == true
        if (!noteMode) {
            q.questions.forEachIndexed { qi, qq ->
                val wrong = result?.wrong?.contains(qi) == true
                Block(Modifier.testTag("question_$qi"), fill = if (wrong) Pal.Pink else Pal.White) {
                    KText("${qi + 1}．${qq.question}", bold = true)
                    qq.options.forEachIndexed { oi, o ->
                        val picked = answers[qi] == oi
                        Row(
                            Modifier.fillMaxWidth().tap({ if (result == null) answers[qi] = oi }).testTag("option_${qi}_$oi").padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(22.dp).sticker(if (picked) Pal.PinkStrong else Pal.Paper, CircleShape, shadow = 0.dp, outline = 2.dp))
                            KText(o)
                        }
                    }
                }
            }
            val r = result
            if (r == null) {
                PillButton(stringResource(R.string.quiz_submit), {
                    scope.launch { result = withContext(io) { lessons.grade(lessonId, answers.toList()) } }
                }, Modifier.testTag("quiz_submit"), enabled = answers.all { it >= 0 })
            } else {
                KText(stringResource(R.string.quiz_result, r.correct, r.total), size = Type.Heading, bold = true, modifier = Modifier.testTag("quiz_result"))
                PillButton(stringResource(R.string.quiz_retry), { attempt++ }, Modifier.testTag("quiz_retry"), Pal.Butter)
            }
        } else {
            if (result?.passed == true) KText(stringResource(R.string.quiz_pass), size = Type.Heading, bold = true)
            KText(
                stringResource(if (q.questions.isEmpty()) R.string.quiz_note_none else R.string.quiz_note_title),
                size = Type.Body, bold = true,
            )
            InkField(note, { note = it; noteRejected = false }, stringResource(R.string.quiz_note_hint), singleLine = false, minLines = 4, tag = "note_field")
            if (noteRejected) KText(stringResource(R.string.quiz_note_short), size = Type.Small, color = Pal.Warn)
            PillButton(stringResource(R.string.btn_save), {
                scope.launch {
                    val ok = note.trim().length >= 10 && withContext(io) { lessons.saveNote(lessonId, note.trim()) }
                    if (ok) onFinished() else noteRejected = true
                }
            }, Modifier.testTag("note_save"))
        }
    }
}
