package app.agency.core.store

import app.agency.core.agent.Card
import kotlinx.serialization.Serializable

/** Entry kinds. One payload class each, below. Add a kind here, never an ad-hoc string elsewhere. */
object Kind {
    const val CHECK_IN = "check_in"
    const val CHECK_OUT = "check_out"
    const val MEAL = "meal"
    const val WATER = "water"
    const val RIDE = "ride"
    const val TASK = "task"
    const val STATUS = "status"
    const val BODY = "body"
    const val MOOD = "mood"
    const val MONEY = "money"
    const val MEMO = "memo"
    const val VIOLATION = "violation"
    const val DRINK = "drink"
    const val PROPOSAL = "proposal"
    const val LESSON = "lesson"
    const val HABIT = "habit"
    const val EVAL = "eval"
    const val SONG = "song"
    const val SPOT_CHECK = "spot_check"
    const val REAL_TASK = "real_task"
    const val WEEK = "week"
    const val CARD = "photocard"

    /** One closed day: how much of it was done ([DayClose]). Written by rollover only. */
    const val DAY_CLOSE = "day_close"

    /** A note 小葵 wrote for herself about this day (the daily memory notes). */
    const val NOTE = "note"
}

/** Morning sign-in: how he feels, 1..5. */
@Serializable
data class CheckIn(val score: Int)

/** Night sign-out. [mood] 1..5 once the mood feature is open. */
@Serializable
data class CheckOut(val good: String = "", val improve: String = "", val mood: Int? = null)

enum class MealSlot { FIRST, DINNER, NIGHT, EXTRA }

enum class FoodSource {
    /** From his own food table in the rules: exact. */
    TABLE,

    /** Read from a label, receipt or menu he confirmed. */
    LABEL,

    /** A model's estimate, already raised by the strict margin. */
    ESTIMATE,
}

/** [banned] names the banned category it fell under, or null. */
@Serializable
data class MealItem(val name: String, val kcal: Int, val proteinG: Double, val source: FoodSource, val banned: String? = null)

/** One meal. [photoId] is a media-vault id; [photoLate] marks a photo added afterwards from the gallery (「補交」). */
@Serializable
data class Meal(
    val slot: MealSlot,
    val items: List<MealItem>,
    val photoId: String? = null,
    val photoLate: Boolean = false,
    val note: String = "",
)

@Serializable
data class Water(val ml: Int)

enum class RidePhase { OUT, BACK }

/** Leaving for delivery work / back home. [income] (whole currency units) comes with BACK when he says it. */
@Serializable
data class Ride(val phase: RidePhase, val income: Int? = null)

enum class Feel { EASY, OK, HARD }

/**
 * A routine item done: [task] is a [app.agency.core.plan.TaskId] name. [reduced] = the code allowed a smaller load (illness,
 * injury, first day back); [awaySeconds] = time spent in another app while its timer ran.
 */
@Serializable
data class TaskDone(
    val task: String,
    val rounds: Int? = null,
    val minutes: Int? = null,
    val feel: Feel? = null,
    val photoId: String? = null,
    val reduced: Boolean = false,
    val awaySeconds: Int = 0,
    val note: String = "",
)

enum class StatusType { REST_DAY, SICK, INJURED, RECOVERED, EXAM_WEEK }

/** A day-level state he declared. SICK / INJURED last until RECOVERED; REST_DAY is that day only; EXAM_WEEK lasts 7 days. */
@Serializable
data class Status(val type: StatusType, val note: String = "")

@Serializable
data class Body(val kg: Double? = null, val waistCm: Double? = null, val photoId: String? = null)

@Serializable
data class Mood(val score: Int, val note: String = "")

enum class Bucket { LIVING, FUN, INCOME }

@Serializable
data class Money(val amount: Int, val bucket: Bucket, val note: String = "")

/** [dueAt] is an ISO-8601 instant or null. */
@Serializable
data class Memo(val text: String, val dueAt: String? = null, val done: Boolean = false, val notified: Boolean = false)

/** A broken rule, written by code only. [code] is a [app.agency.core.day.ViolationCode] name. */
@Serializable
data class Violation(val code: String, val note: String = "")

enum class DrinkType { CAFFEINE, ZERO_CAL }

@Serializable
data class Drink(val type: DrinkType)

/** A day note 小葵 wrote for her own memory. */
@Serializable
data class Note(val text: String)

/**
 * A day that rollover closed. [done] / [total] are the counted plan items; [missed] maps the id of every counted item that was not
 * done to its title (for 「同一件事連續沒做」); [perfect] = something was counted, all of it done, and no violation.
 */
@Serializable
data class DayClose(
    val done: Int,
    val total: Int,
    val violations: Int,
    val perfect: Boolean,
    val missed: Map<String, String> = emptyMap(),
)

/** The weekly result (`Kind.WEEK`) of the calendar week starting on [weekStart] (ISO Monday). Weights are kilograms. */
@Serializable
data class WeekResult(
    val weekStart: String,
    val completionPct: Int,
    val violations: Int,
    val rideIncome: Int,
    val rideTarget: Int,
    val rideDays: Int,
    val weightKg: Double? = null,
    val weightChangeKg: Double? = null,
    val lessonsPassed: Int = 0,
    val perfectDays: Int = 0,
)

/** A photocard (`Kind.CARD`): [kind] is "7" or "30" (days in a row), [earnedOn] the ISO day the streak reached it. */
@Serializable
data class Photocard(val kind: String, val earnedOn: String)

/** The real-person task of the week (`Kind.REAL_TASK`). */
@Serializable
data class RealTask(val text: String, val done: Boolean = false)

/**
 * A proposal that needs his yes (`Kind.PROPOSAL`). [kind] e.g. "kcal_up", "habit"; [value] a number it carries (kcal_up: +100);
 * [answer] starts OPEN.
 */
@Serializable
data class ProposalRec(
    val kind: String,
    val title: String,
    val body: String,
    val value: Int? = null,
    val answer: Card.Answer = Card.Answer.OPEN,
)
