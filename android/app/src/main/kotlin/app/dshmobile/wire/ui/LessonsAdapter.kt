package app.dshmobile.wire.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.agency.core.agent.CompanyAdmin
import app.agency.core.agent.LessonRow as CoreRow
import app.agency.core.lessons.Quiz
import app.agency.core.lessons.LessonState as CoreState
import app.dshmobile.ui.LessonRow
import app.dshmobile.ui.LessonState
import app.dshmobile.ui.LessonsUi
import app.dshmobile.ui.QuizQuestion
import app.dshmobile.ui.QuizResult
import app.dshmobile.ui.QuizView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The lessons list, watching, the quiz and the note: thin calls into [CompanyAdmin]. 「去看」 opens the video's own address in the
 * browser (only http and https) and remembers it in the small preferences [Away]; when the app comes back to the front [cameBack]
 * tells the company he returned: after at least [WATCHED_AFTER_MS] away he is taken to have watched it (the quiz opens), after a
 * shorter time he only returned (the lesson stays on the list).
 */
class LessonsAdapter(
    private val context: Context,
    private val admin: CompanyAdmin,
    private val now: () -> Long = System::currentTimeMillis,
) : LessonsUi {
    private val prefs get() = context.applicationContext.getSharedPreferences(Away.PREFS, Context.MODE_PRIVATE)

    override fun lessons(): List<LessonRow> {
        val view = admin.lessons()
        val reviews = view.reviews.associateBy { it.id }
        val rows = LinkedHashMap<Long, LessonRow>()
        view.byState.values.flatten().forEach { r -> map(r, reviews[r.id] != null)?.let { rows[r.id] = it } }
        view.reviews.forEach { r -> map(r, true)?.let { rows[r.id] = it } }
        return rows.values.toList()
    }

    private fun map(r: CoreRow, review: Boolean): LessonRow? {
        val state = when {
            review -> LessonState.REVIEW_DUE
            else -> when (r.state) {
                CoreState.PROPOSED -> LessonState.PROPOSED
                CoreState.APPROVED -> LessonState.APPROVED
                CoreState.WATCHED -> LessonState.WATCHED
                CoreState.PASSED -> LessonState.PASSED
                CoreState.NOTED, CoreState.REVIEWED -> LessonState.NOTED
                CoreState.REVIEW_DUE -> LessonState.REVIEW_DUE
                CoreState.DECLINED, CoreState.WISH -> return null
            }
        }
        return LessonRow(r.id, r.subject, r.title, r.minutes, state)
    }

    override fun approve(lessonId: Long, yes: Boolean) {
        if (yes) admin.approveLesson(lessonId) else admin.declineLesson(lessonId)
    }

    override fun watch(lessonId: Long) {
        val url = admin.openLesson(lessonId) ?: return
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        prefs.edit().putLong(Away.ID, lessonId).putLong(Away.AT, now()).commit()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { prefs.edit().remove(Away.ID).remove(Away.AT).commit() }
    }

    /** The app is in front again: tell the company he came back from the video he opened (if any). */
    fun cameBack() {
        val id = prefs.getLong(Away.ID, -1L).takeIf { it >= 0 } ?: return
        val away = now() - prefs.getLong(Away.AT, now())
        prefs.edit().remove(Away.ID).remove(Away.AT).commit()
        admin.backFromLesson(id, watched = away >= WATCHED_AFTER_MS)
    }

    private fun isReview(id: Long): Boolean = admin.lessons().reviews.any { it.id == id }

    override suspend fun quiz(lessonId: Long): QuizView = withContext(Dispatchers.IO) {
        val row = admin.lessons().let { v -> v.reviews.firstOrNull { it.id == lessonId } ?: v.byState.values.flatten().firstOrNull { it.id == lessonId } }
        val title = row?.title.orEmpty()
        val quiz: Quiz? = when {
            row == null -> null
            isReview(lessonId) -> admin.reviewQuizFor(lessonId)
            row.state == CoreState.WATCHED -> admin.quizFor(lessonId)
            else -> null // passed: only the note is left
        }
        QuizView(lessonId, title, quiz?.questions.orEmpty().map { QuizQuestion(it.q, it.options) })
    }

    override fun grade(lessonId: Long, answers: List<Int>): QuizResult {
        val review = isReview(lessonId)
        // The wrong ones are told from the quiz the lesson holds now (it exists, so no model call is made).
        val quiz = runBlocking { if (review) admin.reviewQuizFor(lessonId) else admin.quizFor(lessonId) }
        val r = if (review) admin.answerReview(lessonId, answers) else admin.answerQuiz(lessonId, answers)
        val wrong = quiz?.questions?.indices?.filter { answers.getOrNull(it) != quiz.questions[it].answer }.orEmpty()
        return QuizResult(r.right, r.total, r.passed, if (r.refusal == null) wrong else emptyList())
    }

    override fun saveNote(lessonId: Long, note: String): Boolean =
        admin.writeNote(lessonId, note) is app.agency.core.lessons.LessonResult.Ok

    object Away {
        const val PREFS = "xiaoyu_wire"
        const val ID = "away_lesson"
        const val AT = "away_at"
    }

    companion object {
        /** Away at least this long after 「去看」: he is taken to have watched it. */
        const val WATCHED_AFTER_MS = 60_000L
    }
}
