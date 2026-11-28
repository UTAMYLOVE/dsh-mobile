package app.dshmobile.wire

import app.agency.core.agent.Attachment
import app.agency.core.agent.CardView
import app.agency.core.agent.Company
import app.agency.core.agent.CompanyAdmin
import app.agency.core.agent.CompanyApi
import app.agency.core.agent.EvalView
import app.agency.core.agent.LessonsView
import app.agency.core.agent.SayResult
import app.agency.core.agent.SongRow
import app.agency.core.agent.Today
import app.agency.core.agent.tools.MemoRow
import app.agency.core.agent.tools.MoneyView
import app.agency.core.extras.EvalResult
import app.agency.core.extras.MonthlyInput
import app.agency.core.extras.SongResult
import app.agency.core.lessons.LessonResult
import app.agency.core.lessons.Quiz
import app.agency.core.lessons.QuizResult
import app.agency.core.plan.PlanItem
import app.agency.core.remind.Decision
import app.agency.core.remind.ReminderLog
import app.agency.core.rules.ImportResult
import app.agency.core.rules.RuleGroup
import app.agency.core.rules.Rules
import app.agency.core.store.Feel
import app.agency.core.store.MealSlot
import app.agency.core.store.Message
import kotlinx.serialization.json.JsonObject

/**
 * The one [Company] the screens see. It holds the real [Company] and builds a new one when what its providers depend on changes
 * (a company is only its store, clock and providers, so this is cheap): today that is whether a DeepSeek key exists (search is
 * offered only with one). Keys saved in 設定 therefore apply at once.
 *
 * [onChanged] is told after everything that changes the plan (a sentence, a card answer, a tap, a screen finishing an item), so
 * the wake handler can follow the plan with the next alarm. [quiet] is the plain company without that hook: the wake handler
 * itself uses it (it must not trigger itself).
 */
class WiredCompany(
    private val build: (searchOn: Boolean) -> Company,
    private val searchOn: () -> Boolean,
    private val onChanged: () -> Unit = {},
) : CompanyApi, CompanyAdmin {
    private var current: Pair<Boolean, Company>? = null

    private fun c(): Company = synchronized(this) {
        val want = searchOn()
        current?.takeIf { it.first == want }?.second ?: build(want).also { current = want to it }
    }

    /** The company without the change hook. */
    val quiet: Company get() = c()

    private inline fun <T> changed(block: () -> T): T = try {
        block()
    } finally {
        runCatching(onChanged)
    }

    // ---- CompanyApi ----
    override fun today(): Today = c().today()
    override fun messages(limit: Int, beforeId: Long?): List<Message> = c().messages(limit, beforeId)
    override suspend fun say(text: String, attachments: List<Attachment>): SayResult = changed { c().say(text, attachments) }
    override suspend fun resend(messageId: Long): SayResult = changed { c().resend(messageId) }
    override fun direct(tool: String, args: JsonObject): String = changed { c().direct(tool, args) }
    override fun item(id: String): PlanItem? = c().item(id)
    override suspend fun answerCard(messageId: Long, cardIndex: Int, answer: String): SayResult = changed { c().answerCard(messageId, cardIndex, answer) }
    override fun rollover() = c().rollover()

    // ---- CompanyAdmin ----
    override suspend fun importRules(text: String): ImportResult = c().importRules(text)
    override fun saveRules(rules: Rules, handbook: String, rulesText: String) = changed { c().saveRules(rules, handbook, rulesText) }
    override fun rules(): Rules = c().rules()
    override fun ruleGroups(): List<RuleGroup> = c().ruleGroups()
    override fun reminders(log: ReminderLog): Decision = c().reminders(log)
    override fun lessons(): LessonsView = c().lessons()
    override fun approveLesson(id: Long): LessonResult = changed { c().approveLesson(id) }
    override fun declineLesson(id: Long): LessonResult = changed { c().declineLesson(id) }
    override fun openLesson(id: Long): String? = changed { c().openLesson(id) }
    override fun backFromLesson(id: Long, watched: Boolean): LessonResult = changed { c().backFromLesson(id, watched) }
    override suspend fun quizFor(id: Long): Quiz? = c().quizFor(id)
    override suspend fun quizStatus(id: Long): app.agency.core.agent.QuizStatus = c().quizStatus(id)
    override fun logMeal(slot: String, picks: List<app.agency.core.agent.MealPick>, photoId: String?, fromGallery: Boolean): String? =
        changed { c().logMeal(slot, picks, photoId, fromGallery) }
    override fun realTaskNow(): app.agency.core.store.RealTask? = c().realTaskNow()
    override fun realTaskDone(): String? = changed { c().realTaskDone() }
    override fun answerQuiz(id: Long, answers: List<Int>): QuizResult = changed { c().answerQuiz(id, answers) }
    override fun writeNote(id: Long, text: String): LessonResult = changed { c().writeNote(id, text) }
    override suspend fun reviewQuizFor(id: Long): Quiz? = c().reviewQuizFor(id)
    override fun answerReview(id: Long, answers: List<Int>): QuizResult = changed { c().answerReview(id, answers) }
    override fun timerDone(task: String, rounds: Int?, minutes: Int?, feel: Feel?, awaySeconds: Int): String =
        changed { c().timerDone(task, rounds, minutes, feel, awaySeconds) }
    override fun mealPhoto(slot: MealSlot, photoId: String, fromGallery: Boolean): String? = changed { c().mealPhoto(slot, photoId, fromGallery) }
    override fun bodyPhoto(photoId: String): String = changed { c().bodyPhoto(photoId) }
    override fun spotAnswer(photoId: String): String? = changed { c().spotAnswer(photoId) }
    override fun memos(): List<MemoRow> = c().memos()
    override fun memoDone(id: Long): Boolean = changed { c().memoDone(id) }
    override fun memoAdd(text: String, dueAt: String?): MemoRow? = changed { c().memoAdd(text, dueAt) }
    override fun memoRemove(id: Long): Boolean = changed { c().memoRemove(id) }
    override fun memoryLines(): List<String> = c().memoryLines()
    override fun forget(line: String): List<String> = c().forget(line)
    override fun traineeCard(): CardView = c().traineeCard()
    override fun moneyView(): MoneyView = c().moneyView()
    override fun songs(): List<SongRow> = c().songs()
    override fun recordSong(pcm: ShortArray, rate: Int, title: String, referenceId: String?, vaultId: String?): SongResult =
        changed { c().recordSong(pcm, rate, title, referenceId, vaultId) }
    override fun evalView(): EvalView = c().evalView()
    override suspend fun submitWeeklyEval(songEntryId: Long, danceRecordingId: String, selfReport: String): EvalResult =
        changed { c().submitWeeklyEval(songEntryId, danceRecordingId, selfReport) }
    override suspend fun submitMonthlyEval(input: MonthlyInput): EvalResult = changed { c().submitMonthlyEval(input) }
}
