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
class QuizView(val lessonId: Long, val title: String, val questions: List<QuizQuestion>, val unavailable: String? = null)

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
class TimerPlan(
    val task: String,
    val title: String,
    val rounds: Int,
    val moves: List<TimerMove>,
    val restSeconds: Int,
    /** Not empty = a checklist instead of a countdown (grooming: the items that are due). */
    val checklist: List<String> = emptyList(),
)

/** The training timer. */
interface TimerUi {
    /** [task] is a `TaskId` name (「CORE」…) as given by the plan item's `Tap.Open(TIMER, arg)`. */
    fun plan(task: String): TimerPlan

    /** [feel] is "EASY", "OK" or "HARD"; [awaySeconds] is how long the app was in the background while it ran. */
    fun finish(task: String, rounds: Int, minutes: Int, feel: String, awaySeconds: Int)

    /** He left the app while the timer was running: after a short while a quiet notice may be posted. */
    fun leftWhileRunning() {}

    /** He is back (or the page closed): the notice is taken away. */
    fun cameBack() {}
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
    /** Plain lines about the last weigh-in ("上次 71.3 公斤"), empty when none. */
    fun last(): List<String>
    suspend fun log(kg: Double?, waistCm: Double?, photo: Attachment.Photo?): String

    /** Every body photo in date order (the 全身照 page). Called on an IO thread. */
    fun timeline(): List<BodyShotRow> = emptyList()
}

/** One point of the body-photo timeline: [date] is already in his words ("11/23"), [note] a short line (weight, waist) or blank. */
class BodyShotRow(val date: String, val photoId: String, val note: String = "")

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

/**
 * One singing take's numbers (all computed by code). [shift] is the "higher / lower by N semitones" line when the comparison
 * with a reference gave one; [chart] is the pitch-curve picture (PNG bytes) of this take; [compare] the comparison lines.
 */
class SongResult(
    val inTunePct: Int,
    val range: String,
    val longestPhraseSec: Int,
    val loudness: String,
    val comment: String,
    val shift: String = "",
    val chart: ByteArray? = null,
    val compare: List<String> = emptyList(),
)

/** An imported reference melody, as listed. */
class SongRefRow(val id: String, val title: String, val source: String)

/** What importing a reference came to: [title] when it worked, else [failure] in his words (both null when he backed out). */
class RefImport(val title: String?, val failure: String?)

/** What the lyric check came to: [lines] to show, or [failure] in his words. */
class LyricsOutcome(val lines: List<String>, val failure: String?)

interface SingingUi {
    /** Starts recording; false when the microphone is refused. */
    suspend fun start(): Boolean

    /** Stops, analyses on the phone, returns the numbers; null when nothing usable was recorded. */
    suspend fun stop(): SongResult?
    fun history(): List<SongResult>

    /** The references he imported so far. */
    fun references(): List<SongRefRow> = emptyList()

    /** The file picker, then the import of a recorded demo, a melody file (MIDI) or a karaoke text file. */
    suspend fun importReference(): RefImport = RefImport(null, null)

    /** The reference the next take is compared with (null = none). */
    fun chooseReference(id: String?) {}

    /** True = 嚴格 (the default), false = 一般. */
    fun setStrict(strict: Boolean) {}

    /** True when a DeepSeek key exists, so the lyric check can be offered at all. */
    fun lyricsAvailable(): Boolean = false

    /**
     * Sends the LAST take to speech-to-text and compares it with [lyrics]. Nothing is sent unless [consent] is true (he ticked the
     * box for this one time).
     */
    suspend fun checkLyrics(lyrics: String, consent: Boolean): LyricsOutcome = LyricsOutcome(emptyList(), null)
}

class EvalItem(val key: String, val label: String, val recorded: Boolean, val suggested: Int?, val reason: String)
class EvalView(val title: String, val items: List<EvalItem>, val confirmed: Boolean)

interface EvalUi {
    fun view(monthly: Boolean): EvalView

    /** Records the song / dance / … of that item through the recorder or camera; true when something was recorded. */
    suspend fun record(monthly: Boolean, key: String): Boolean

    /** His confirmed 1..5 score per [EvalItem.key]. */
    fun confirm(monthly: Boolean, scores: Map<String, Int>)

    /** The monthly fitness test (push-ups in one go, plank seconds), kept for [confirm]. Null = not done. */
    fun setFitness(pushUps: Int?, plankSeconds: Int?) {}
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

    /** True when a Claude key is already saved (an older version's key carried over): the keys step may then be left blank. */
    fun keysSaved(): Boolean = false

    /** True when a DeepSeek key is saved too (the second key). */
    fun deepSeekSaved(): Boolean = false

    /** Whether the main (Claude) key itself is saved; [keysSaved] is true when either key is. */
    fun claudeSaved(): Boolean = keysSaved()
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

/** What the photo is for, once he has taken one (the camera knows its purpose from where it was opened). */
interface PhotoUi {
    /** A photo answering a spot check: the NOTE line, or null when no check is open. */
    fun spot(photoId: String): String? = null

    /** A photo for a meal already recorded without one: the NOTE line, or null when there is no such meal (it then goes to chat). */
    fun mealPhoto(slot: String, photoId: String, fromGallery: Boolean): String? = null

    /** The Sunday same-angle body photo: the NOTE line. */
    fun bodyPhoto(photoId: String): String? = null
}

/** One row of his own food table. */
class FoodRow(val name: String, val kcal: Int, val proteinG: Double)

/** Recording a meal by hand (no model). */
interface MealUi {
    /** His own food table. */
    fun foods(): List<FoodRow> = emptyList()

    /** Runs the direct meal path; the NOTE line (the meal card is in the conversation), or null when it could not be recorded. */
    fun log(slot: String, picks: List<FoodRow>, photo: Attachment.Photo?): String? = null
}

/** This week's real-person task. */
class RealTaskView(val text: String, val done: Boolean)

/** The plan items that must work without the model. */
interface PracticeUi {
    fun realTask(): RealTaskView? = null

    /** He did it: the NOTE line, or null when the feature is closed. */
    fun realTaskDone(): String? = null
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
    val photos: PhotoUi? = null,
    val meals: MealUi? = null,
    val practice: PracticeUi? = null,
)

interface UiGraphProvider {
    val uiGraph: UiGraph
}
