package app.dshmobile.ui

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import app.agency.core.agent.Attachment
import app.agency.core.agent.CompanyApi

/*
 * The UI-side doors. The screens call only [CompanyApi] and the small interfaces below; another module implements them for
 * real, and `Demo*` (DemoData.kt) implements them with invented data.
 *
 * Threads: plain functions are called by the UI on an IO thread (they may read the store or disk). `suspend` functions are
 * called on the main thread and must switch threads themselves (camera, pickers and recorders need the main thread to
 * start). The UI never keeps what it is given beyond the screen.
 */

/** Camera, pickers, paste and stored photos. */
interface MediaUi {
    /** Opens the in-app camera; [purpose] is a plain word for the title ("晚餐"). Null when he backs out. */
    suspend fun takePhoto(purpose: String = ""): Attachment.Photo?

    /** The system photo picker, several at once; empty when he backs out. */
    suspend fun pickPhotos(): List<Attachment.Photo>

    /** The document picker (text, CSV, JSON, Markdown); null when he backs out or the file is too big or unreadable. */
    suspend fun pickFile(): Attachment.File?

    /** Content pasted from the clipboard or a keyboard (image / file URIs): cleaned and read like a picked one. */
    suspend fun fromUris(uris: List<Uri>): List<Attachment>

    /** A small decoded picture of a vault photo for display, or null when it cannot be shown (a drawing is shown instead). */
    suspend fun thumbnail(photoId: String): ImageBitmap?
}

/** One lesson as listed. */
data class LessonRow(
    val id: Long,
    val subject: String,
    val title: String,
    val minutes: Int?,
    val state: LessonState,
)

enum class LessonState { PROPOSED, APPROVED, WATCHED, PASSED, NOTED, REVIEW_DUE }

class QuizQuestion(val question: String, val options: List<String>)

/** [lessonId] null never happens; [questions] empty means "no quiz for this lesson: write three lines of notes". */
class QuizView(val lessonId: Long, val title: String, val questions: List<QuizQuestion>)

/** [wrong] holds the indexes of the questions answered wrongly. */
class QuizResult(val correct: Int, val total: Int, val passed: Boolean, val wrong: List<Int>)

/** Lessons: the list, watching, the quiz, the note. */
interface LessonsUi {
    fun lessons(): List<LessonRow>

    /** 加入清單 / 不要 on a proposed lesson. */
    fun approve(lessonId: Long, yes: Boolean)

    /** 去看: opens exactly that lesson's URL outside the app and records the time. */
    fun watch(lessonId: Long)

    /** A (new) quiz for the lesson, or one without questions when none can be made. */
    suspend fun quiz(lessonId: Long): QuizView

    /** [answers]: the chosen option index per question. Code grades. */
    fun grade(lessonId: Long, answers: List<Int>): QuizResult

    /** The note after passing (the caller checks the length; false when rejected). */
    fun saveNote(lessonId: Long, note: String): Boolean
}

class TimerMove(val name: String, val seconds: Int)

/** [rounds] repeats of [moves], with [restSeconds] between rounds. [title] is the task's name. */
class TimerPlan(val task: String, val title: String, val rounds: Int, val moves: List<TimerMove>, val restSeconds: Int)

/** The training timer. */
interface TimerUi {
    /** [task] is a `TaskId` name (「CORE」…) as given by the plan item's `Tap.Open(TIMER, arg)`. */
    fun plan(task: String): TimerPlan

    /** [feel] is "EASY", "OK" or "HARD"; [awaySeconds] is how long the app was in the background while it ran. */
    fun finish(task: String, rounds: Int, minutes: Int, feel: String, awaySeconds: Int)
}

class MemoRow(val id: Long, val text: String, val due: String?, val done: Boolean)

interface MemoUi {
    fun list(): List<MemoRow>
    fun setDone(id: Long, done: Boolean)
    fun remove(id: Long)

    /** [dueAt] is free text he may leave blank. */
    fun add(text: String, dueAt: String?)
}

/** The weekly weigh-in sheet. */
interface BodyUi {
    /** Plain lines about the last weigh-in ("上次 68.4 公斤"), empty when none. */
    fun last(): List<String>
    suspend fun log(kg: Double?, waistCm: Double?, photo: Attachment.Photo?): String
}

/** [bucket] is "LIVING", "FUN" or "INCOME". */
interface MoneyUi {
    /** This month so far in plain lines. */
    fun summary(): List<String>
    fun add(amount: Int, bucket: String, note: String): String
}

class Photocard(val title: String, val date: String, val photoId: String?)
class DiaryDay(val label: String, val perfect: Boolean)

/** The trainee card (idol profile). [colorHex] is "#RRGGBB" or blank. */
class TraineeCard(
    val stageName: String,
    val colorHex: String,
    val level: String,
    val stageLabel: String,
    val daysSinceJoin: Int,
    val photocards: List<Photocard>,
    val diary: List<DiaryDay>,
)

interface TraineeUi {
    fun card(): TraineeCard
}

/** One singing take's numbers (all computed by code). */
class SongResult(val inTunePct: Int, val range: String, val longestPhraseSec: Int, val loudness: String, val comment: String)

interface SingingUi {
    /** Starts recording; false when the microphone is refused. */
    suspend fun start(): Boolean

    /** Stops, analyses on the phone, returns the numbers; null when nothing usable was recorded. */
    suspend fun stop(): SongResult?
    fun history(): List<SongResult>
}

class EvalItem(val key: String, val label: String, val recorded: Boolean, val suggested: Int?, val reason: String)
class EvalView(val title: String, val items: List<EvalItem>, val confirmed: Boolean)

interface EvalUi {
    fun view(monthly: Boolean): EvalView

    /** Records the song / dance / … of that item through the recorder or camera; true when something was recorded. */
    suspend fun record(monthly: Boolean, key: String): Boolean

    /** His confirmed 1..5 score per [EvalItem.key]. */
    fun confirm(monthly: Boolean, scores: Map<String, Int>)
}

class PermissionState(val notifications: Boolean, val exactAlarms: Boolean)

/** One group of the confirmation summary after 小葵 read his document. */
class SummaryGroup(val title: String, val lines: List<String>)

class ImportOutcome(val groups: List<SummaryGroup>, val missing: List<String>, val failure: String?)

interface OnboardingUi {
    /** Stores the keys in the Keystore; blank deepseek = none. False when it could not be saved. */
    suspend fun saveKeys(claude: String, deepseek: String): Boolean

    /** 小葵 reads the pasted document; the confirmation summary comes back (or a failure in his language). */
    suspend fun readDocument(text: String): ImportOutcome
    suspend fun confirm(stageName: String, memberColorHex: String, callName: String)
    fun permissions(): PermissionState
    fun requestNotifications()
    fun openAlarmSettings()
    fun openBatteryGuide()

    /** Day 1 starts. */
    fun finish()
}

class RuleField(val key: String, val label: String, val value: String, val hint: String = "")
class RuleGroup(val title: String, val fields: List<RuleField>)
class ModelChoice(val useDeepSeekForChat: Boolean, val tier: String, val claudeKeySet: Boolean, val deepSeekKeySet: Boolean)
class SpendView(val today: String, val month: String, val capYuan: Int?)
class UpdateView(val version: String, val newer: String?)
class LicenseView(val name: String, val text: String)

/** Everything on the settings pages. Money texts are already formatted. */
interface SettingsUi {
    fun rulesGroups(): List<RuleGroup>

    /** Saves changed fields by key; returns a plain failure sentence, or null when saved. */
    fun saveRules(changes: Map<String, String>): String?

    fun model(): ModelChoice
    fun setModel(useDeepSeekForChat: Boolean, tier: String)

    /** [which] is "claude" or "deepseek". */
    suspend fun saveKey(which: String, key: String): Boolean
    fun spend(): SpendView
    fun setCap(yuan: Int?)
    fun memory(): List<String>
    fun forget(line: String)
    fun testNotification()
    fun openBatteryGuide()
    fun update(): UpdateView

    /** Opens the release page of the newer version. */
    fun openUpdate()
    fun licenses(): List<LicenseView>
}

/** Encrypted backup export / import. */
interface BackupUi {
    suspend fun export(passphrase: String): String

    /** Picks a backup file and decrypts it for a preview: a summary line, or null when the passphrase or file is wrong. */
    suspend fun preview(passphrase: String): String?

    /** Replaces everything on the phone with the previewed backup; returns a plain result sentence. */
    suspend fun apply(): String
}

class OldMessage(val who: String, val text: String, val time: String)

interface OldDataUi {
    fun available(): Boolean
    fun messages(): List<OldMessage>

    /** Deletes the old database and old media. */
    fun delete(): Boolean
}

/** Remembers the folded / unfolded state of the three home panels. */
interface PanelPrefs {
    /** [name] is "stickers", "handbook" or "chips". */
    fun get(name: String, default: Boolean): Boolean
    fun set(name: String, open: Boolean)
}

/** The bundle the app hands to the UI. The application object may implement [UiGraphProvider]; otherwise the demo is used. */
class UiGraph(
    val company: CompanyApi,
    val media: MediaUi,
    val lessons: LessonsUi,
    val timer: TimerUi,
    val memo: MemoUi,
    val body: BodyUi,
    val money: MoneyUi,
    val trainee: TraineeUi,
    val singing: SingingUi,
    val eval: EvalUi,
    val onboarding: OnboardingUi,
    val settings: SettingsUi,
    val backup: BackupUi,
    val oldData: OldDataUi,
    val panels: PanelPrefs? = null,
)

interface UiGraphProvider {
    val uiGraph: UiGraph
}
