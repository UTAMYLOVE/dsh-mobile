package app.agency.core.plan

import app.agency.core.stage.Feature
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.JsonObject

/** Routine items that are simply "done" (with a timer, a photo or a word). Stored by name in [app.agency.core.store.TaskDone]. */
enum class TaskId(val feature: Feature, val title: String) {
    CORE(Feature.CORE, "核心"),
    STRENGTH(Feature.STRENGTH, "肌力"),
    STRETCH(Feature.STRETCH, "收操伸展"),
    MORNING_STRETCH(Feature.MORNING_STRETCH, "起床伸展"),
    VOCAL_BASICS(Feature.VOCAL_BASICS, "聲樂基本功"),
    CARDIO(Feature.CARDIO, "體能"),
    POSTURE(Feature.POSTURE, "靠牆站姿"),
    SUNSCREEN(Feature.SUNSCREEN, "擦防曬"),
    WALK(Feature.WALK, "快走"),
    DANCE(Feature.DANCE, "舞蹈"),
    VOCAL(Feature.VOCAL, "聲樂練習"),
    EXPRESSION(Feature.EXPRESSION, "表情鏡頭"),
    GROOMING(Feature.GROOMING, "儀容"),
    PERSONAL_TASK(Feature.MONTHLY_EVAL, "個人任務"),
}

enum class DayType { CLASS, FREE }

enum class ItemStatus {
    DONE,
    PENDING,

    /** Past its time, still doable today (this is what gets chased). */
    LATE,

    /** The day ended, or its window closed, without it. */
    MISSED,

    /** Not applicable today after an adjustment (shown greyed with the reason, never counted). */
    OFF,
}

/** Screens a plan item or a card can open. */
enum class Screen { LESSONS, QUIZ, TIMER, CAMERA, CHECK_IN, CHECK_OUT, BODY, EVAL, SINGING, MEMO, MONEY }

/** What tapping a plan item (or the 「下一件事」 button) does. */
sealed interface Tap {
    /** Sends [text] as if he had typed it (小葵 answers). */
    data class Say(val text: String) : Tap

    /** Opens a screen; [arg] says which (a [TaskId] name for TIMER, a meal slot for CAMERA, a lesson id for QUIZ…). */
    data class Open(val screen: Screen, val arg: String = "") : Tap

    /** Runs a tool at once without a model call and leaves a small note line in the conversation. */
    data class Direct(val tool: String, val args: JsonObject) : Tap
}

data class PlanItem(
    /** Stable within the day, e.g. "meal:DINNER", "task:CORE", "check_in", "lesson:2", "ride:out". */
    val id: String,
    val feature: Feature,
    val title: String,
    /** When it is due, or null for "any time today". */
    val at: LocalTime?,
    /** Its deadline, or null. */
    val until: LocalTime?,
    val status: ItemStatus,
    /** Whether it counts towards the day's completion. */
    val counts: Boolean,
    val tap: Tap,
    /** A short right-hand detail (「650 大卡」, 「8:31」, 「19:00 前」). */
    val detail: String = "",
    /** Chased by reminders every 30 minutes while LATE. */
    val chase: Boolean = true,
)

/** One automatic change to today, with its reason, in his language (shown under 「今天改了什麼」). */
data class Adjustment(val what: String, val why: String)

data class DayPlan(
    val day: LocalDate,
    val type: DayType,
    val items: List<PlanItem>,
    /** The one thing for the 「下一件事」 button, or null when today is finished (or nothing is open). */
    val next: PlanItem?,
    val adjustments: List<Adjustment>,
    /** He is out on delivery right now: reminders sound once and are not repeated loudly. */
    val riding: Boolean,
    val restDay: Boolean,
    /** Sick or injured today: code has reduced what it may reduce. */
    val unwell: Boolean,
    val done: Int,
    val total: Int,
)
