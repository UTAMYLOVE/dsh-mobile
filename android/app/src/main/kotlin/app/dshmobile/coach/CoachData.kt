package app.dshmobile.coach

import app.agency.core.model.FoodEntry
import app.agency.core.model.MealItem
import java.time.DayOfWeek

data class Meal(
    val id: String,
    val at: Long,
    /** The coach day, yyyy-MM-dd (see [CoachRules.coachDay]). */
    val day: String,
    /** The owner's words, at most 200 characters. */
    val text: String,
    val items: List<MealItem>,
    val cheat: Boolean,
    val offPlan: Boolean,
    val verified: Boolean = false,
    val evidence: String? = null,
    /** Which company rules this meal broke ([Breach] names); [offPlan] is true exactly when this is not empty. */
    val breaches: List<String> = if (offPlan) listOf(Breach.FRIED.name) else emptyList(),
) {
    val kcal: Int get() = items.sumOf { it.kcal }
    val proteinG: Double get() = items.sumOf { it.proteinG ?: 0.0 }
}

data class WeightEntry(val id: String, val at: Long, val day: String, val kg: Double, val waistCm: Double?)

/** An estimated item waiting for the owner's label or portion before it may enter the food table. */
data class PendingFood(val mealId: String, val itemIndex: Int, val name: String, val countedKcal: Int, val askedAt: Long)

/** A company rule a meal broke. */
enum class Breach(val zh: String) { FRIED("非休假餐吃炸物"), OVER700("單餐超過 700 大卡"), ZEROCAL("零卡飲料超過一週三瓶") }

/** One strength-training report; [rounds] only when the owner wrote the number. */
data class TrainingEntry(val id: String, val at: Long, val day: String, val rounds: Int?)

/** The steps of 入社建檔, asked one at a time by the 經紀人. */
enum class BaselineStep { BODY, PHOTO, RECORDING, PUSHUPS, PLANK }

/** 入社建檔: the baseline of every later evaluation. Files are paths under the coach folder; nothing here is sent to the model except the numbers. */
data class Baseline(
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val photo: String? = null,
    val recording: String? = null,
    val pushups: Int? = null,
    val plankSec: Int? = null,
    val skipped: Set<BaselineStep> = emptySet(),
    /** The step the 經紀人 is asking now; null when no question is open. */
    val step: BaselineStep? = null,
) {
    fun has(s: BaselineStep): Boolean = when (s) {
        BaselineStep.BODY -> heightCm != null && weightKg != null
        BaselineStep.PHOTO -> photo != null
        BaselineStep.RECORDING -> recording != null
        BaselineStep.PUSHUPS -> pushups != null
        BaselineStep.PLANK -> plankSec != null
    }
    val missing: List<BaselineStep> get() = BaselineStep.entries.filter { !has(it) }
}

data class CoachState(
    val version: Int = 2,
    val seeded: Boolean = false,
    val meals: List<Meal> = emptyList(),
    val weights: List<WeightEntry> = emptyList(),
    val food: List<FoodEntry> = emptyList(),
    val pending: List<PendingFood> = emptyList(),
    val trainings: List<TrainingEntry> = emptyList(),
    val baseline: Baseline? = null,
)

enum class Speaker { OWNER, COACH }

/** Who speaks for the company. All roles are the same model in a different role prompt, sharing one record. */
enum class AgencyRole(val zh: String) {
    AGENT("經紀人"), BODY("體態管理老師");

    companion object {
        /** The role of a message saved before roles existed (v0.8.3): body matters go to the 體態管理老師. */
        fun forKind(kind: String?): AgencyRole = if (kind in BODY_KINDS) BODY else AGENT
        val BODY_KINDS = setOf("meal", "craving", "weight", "food_info", "training")
    }
}

data class CoachMessage(val id: String, val at: Long, val who: Speaker, val text: String, val kind: String?, val role: AgencyRole? = null) {
    /** The speaker label of a company message (null for the owner's own). */
    val speaker: AgencyRole? get() = if (who == Speaker.OWNER) null else role ?: AgencyRole.forKind(kind)
}

/** Derived from the meals, never stored. */
data class DailySummary(val day: String, val reported: Boolean, val onPlan: Boolean, val kcal: Int, val proteinG: Double, val overBy: Int)

data class Totals(val overKcal: Int, val delayDays: String)

/** The owner's fixed file. Only the owner edits it (教練設定); the model never does. */
data class CoachProfile(
    val heightCm: Double = 168.0,
    val startWeightKg: Double = 80.0,
    val quotaKcal: Int = 1700,
    val proteinG: Int = 140,
    val fatG: Int = 50,
    val carbsG: Int = 170,
    val goals: List<Double> = listOf(80.0, 75.0, 68.0),
    val cheatDay: DayOfWeek = DayOfWeek.FRIDAY,
    val bedtime: String = "",
    val marginPct: Int = 10,
    val plannedMeal: String = "給力盒子 南加州烤雞腿",
    val evidenceMinutes: Int = 30,
    val dayStartHour: Int = 5,
)
