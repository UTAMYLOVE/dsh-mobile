package app.agency.core.agent

import app.agency.core.plan.DayPlan
import app.agency.core.plan.PlanItem
import app.agency.core.store.Message
import kotlinx.serialization.json.JsonObject

/** One of the three small stickers on the home screen. [key] is "eaten", "water" or "done" (stage 1: lessons-only variants). */
data class Sticker(val key: String, val label: String, val value: String)

/** Everything the home screen shows besides the conversation. */
data class Today(
    /** 「第 2 階段 · 第 3 天」. */
    val stageLabel: String,
    val stickers: List<Sticker>,
    val plan: DayPlan,
    /** Quick replies that fit this moment (at most four), each sent as his words when tapped. */
    val chips: List<String>,
    /** False until the rules are filled in and a key is saved: the app shows onboarding instead of home. */
    val ready: Boolean,
    /**
     * Whether the mood dots belong on check-out (the mood feature is open at the current stage). Null = unknown (a fake that
     * does not say): the screen then falls back to reading the stage from [stageLabel].
     */
    val moodOpen: Boolean? = null,
)

/** Something attached to what he says. Photos are already cleaned (downscaled, metadata stripped) by the app. */
sealed interface Attachment {
    /** [jpeg] is what may be sent to a model; [photoId] is the encrypted original in the media vault. */
    class Photo(val photoId: String, val jpeg: ByteArray, val fromGallery: Boolean) : Attachment

    /** A pasted or picked file, already read to text by the app (plain text, CSV, JSON, Markdown). */
    class File(val name: String, val text: String) : Attachment
}

/** What happened to one thing he said. */
sealed interface SayResult {
    /** 小葵 answered; the messages are in the store. */
    data object Ok : SayResult

    /** Not sent (offline, no key, spending cap…). His message is stored with `meta.failed = true` and can be resent. */
    data class NotSent(val reason: String) : SayResult
}

/**
 * The one door between the app and the company. The UI never touches the store, the rules or a model directly.
 * All functions are safe to call off the main thread; [say] suspends for the model.
 */
interface CompanyApi {
    fun today(): Today

    /** The newest [limit] messages before [beforeId], oldest first. */
    fun messages(limit: Int = 60, beforeId: Long? = null): List<Message>

    /** He said something (typed, spoken, a chip, a card's "say" tap). */
    suspend fun say(text: String, attachments: List<Attachment> = emptyList()): SayResult

    /** Sends a failed message again. */
    suspend fun resend(messageId: Long): SayResult

    /** A fallback tap: runs [tool] in code at once (no model call) and adds a NOTE line. Returns that line's text. */
    fun direct(tool: String, args: JsonObject): String

    /** A tap on a plan item, resolved to say / open / direct by the caller from [PlanItem.tap]. Kept here for symmetry. */
    fun item(id: String): PlanItem?

    /** He answered a card (a score dot, a proposal's yes/no, a video approved…). Code records it; 小葵 is told on the next turn. */
    suspend fun answerCard(messageId: Long, cardIndex: Int, answer: String): SayResult

    /**
     * Housekeeping to run when the app opens and on every alarm: closes finished days, judges a finished stage round, writes the
     * week's result, opens the next stage. Idempotent.
     */
    fun rollover()
}

/** One lesson as the lessons screen lists it. */
data class LessonRow(
    val id: Long,
    val subject: String,
    val title: String,
    val url: String,
    val state: app.agency.core.lessons.LessonState,
    /** Its state in his words (「待看」「待測驗」…). */
    val stateText: String,
    val minutes: Int? = null,
    val score: Int? = null,
    val total: Int = 0,
    val notesOnly: Boolean = false,
    val reviewOn: String? = null,
)

/** The lessons screen: today's slots (the same ones as the plan), then every lesson grouped by state. */
data class LessonsView(
    val today: List<app.agency.core.plan.LessonSlot>,
    val byState: Map<app.agency.core.lessons.LessonState, List<LessonRow>>,
    /** Lessons whose review quiz is due today. */
    val reviews: List<LessonRow>,
    val passedTotal: Int,
)

/** The trainee card (idol-profile page). */
data class CardView(
    val stageName: String,
    val callName: String,
    val memberColor: String,
    val level: String,
    val stage: Int,
    val stageLabel: String,
    val dayNumber: Int,
    val photocards: List<app.agency.core.store.Photocard>,
    /** Logical days that were perfect (every counted item done, no violation), oldest first. */
    val perfectDays: List<java.time.LocalDate>,
)

/** One recorded song. */
data class SongRow(val entryId: Long, val day: java.time.LocalDate, val rec: app.agency.core.extras.SongRec)

/** The evaluation screen. */
data class EvalView(
    val weeklyOpen: Boolean,
    val weeklyDue: Boolean,
    val weekly: app.agency.core.extras.EvalRec?,
    val monthlyOpen: Boolean,
    val monthlyDue: Boolean,
    val monthly: app.agency.core.extras.EvalRec?,
    val rotatingItem: String,
    val personalTask: app.agency.core.extras.PersonalTask?,
)

/**
 * What the app needs besides the conversation door: onboarding, settings, reminders and the pages behind plan items. Every
 * function is a thin call into the package that owns the rule; each one that finishes a plan item also leaves a NOTE line so
 * 小葵 sees it on the next turn.
 *
 * Which screen finishes which plan item (no dead ends): CHECK_IN / CHECK_OUT / BODY (weight) → [CompanyApi.direct] with
 * `check_in` / `check_out` / `log_body`; TIMER → [timerDone]; CAMERA → [mealPhoto] (a meal already logged without a photo) or
 * [CompanyApi.say] with the photo (a new meal); CAMERA "spot" → [spotAnswer]; BODY "photo" → [bodyPhoto]; LESSONS →
 * [approveLesson], [openLesson], [backFromLesson], [quizFor], [answerQuiz], [writeNote]; QUIZ (a review) → [reviewQuizFor], [answerReview];
 * EVAL → [submitWeeklyEval] / [submitMonthlyEval]; SINGING → [recordSong]; MEMO → [memos], [memoDone], [memoRemove];
 * MONEY → [moneyView] and [CompanyApi.direct] with `log_money`.
 */
interface CompanyAdmin {
    /** Reads his pasted document into rules + handbook. Does not save anything. */
    suspend fun importRules(text: String): app.agency.core.rules.ImportResult

    /** Saves the confirmed rules, the handbook and the pasted original (doc `rules_text`). */
    fun saveRules(rules: app.agency.core.rules.Rules, handbook: String, rulesText: String)

    fun rules(): app.agency.core.rules.Rules

    /** Every rule as editable fields for 設定 → 公司規章. */
    fun ruleGroups(): List<app.agency.core.rules.RuleGroup>

    /** What to notify now and when to wake next; persist the returned log. */
    fun reminders(log: app.agency.core.remind.ReminderLog): app.agency.core.remind.Decision

    fun lessons(): LessonsView

    /** He took a found video into his list (from the lessons page; the video card uses [CompanyApi.answerCard]). */
    fun approveLesson(id: Long): app.agency.core.lessons.LessonResult

    fun declineLesson(id: Long): app.agency.core.lessons.LessonResult

    /** He tapped 「去看」: returns the URL to open, or null when the lesson cannot be opened now. */
    fun openLesson(id: Long): String?

    /** He came back from the video; [watched] = he says he watched it (the quiz becomes available). */
    fun backFromLesson(id: Long, watched: Boolean = true): app.agency.core.lessons.LessonResult

    /** The quiz of a watched lesson, made now when missing; null when the lesson falls back to notes only. */
    suspend fun quizFor(id: Long): app.agency.core.lessons.Quiz?

    fun answerQuiz(id: Long, answers: List<Int>): app.agency.core.lessons.QuizResult

    fun writeNote(id: Long, text: String): app.agency.core.lessons.LessonResult

    /** The quiz for a review due today (a fresh one when it can be made, else the original). */
    suspend fun reviewQuizFor(id: Long): app.agency.core.lessons.Quiz?

    fun answerReview(id: Long, answers: List<Int>): app.agency.core.lessons.QuizResult

    /** The training timer finished. Returns the NOTE line. */
    fun timerDone(task: String, rounds: Int?, minutes: Int?, feel: app.agency.core.store.Feel?, awaySeconds: Int): String

    /**
     * A photo from the camera / gallery for today's [slot] meal that was logged without one. Returns the NOTE line, or null
     * when there is no such meal (then the photo goes to 小葵 with [CompanyApi.say] so she can log the meal).
     */
    fun mealPhoto(slot: app.agency.core.store.MealSlot, photoId: String, fromGallery: Boolean): String?

    /** The Sunday same-angle body photo. Returns the NOTE line. */
    fun bodyPhoto(photoId: String): String

    /** A photo answering a spot check. Returns the NOTE line, or null when no check was open. */
    fun spotAnswer(photoId: String): String?

    fun memos(): List<app.agency.core.agent.tools.MemoRow>
    fun memoDone(id: Long): Boolean

    /**
     * A memo typed on the memo page. [dueAt] is free text (`HH:mm`, a date, or a date and time); blank or unreadable = no
     * reminder time. Returns the new row, or null when the text is blank or the feature is closed.
     */
    fun memoAdd(text: String, dueAt: String? = null): app.agency.core.agent.tools.MemoRow? = null
    fun memoRemove(id: Long): Boolean

    /** What 小葵 remembers (settings → 記憶). */
    fun memoryLines(): List<String>

    /** Removes every memory line containing [line]; returns what was removed. */
    fun forget(line: String): List<String>

    fun traineeCard(): CardView
    fun moneyView(): app.agency.core.agent.tools.MoneyView

    fun songs(): List<SongRow>

    /** A recorded take (16-bit PCM, never sent anywhere): analysed, stored, and shown as a summary NOTE. */
    fun recordSong(
        pcm: ShortArray,
        rate: Int,
        title: String,
        referenceId: String? = null,
        vaultId: String? = null,
    ): app.agency.core.extras.SongResult

    fun evalView(): EvalView

    suspend fun submitWeeklyEval(songEntryId: Long, danceRecordingId: String, selfReport: String = ""): app.agency.core.extras.EvalResult

    suspend fun submitMonthlyEval(input: app.agency.core.extras.MonthlyInput): app.agency.core.extras.EvalResult
}
