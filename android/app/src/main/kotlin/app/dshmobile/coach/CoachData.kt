package app.dshmobile.coach

import java.time.DayOfWeek

/** Where an item's kcal came from: the package label, the food table, or an estimate (shown 標示／食物表／估算). */
enum class KcalSource { LABEL, TABLE, ESTIMATE }

data class MealItem(
    val name: String,
    val portion: Double,
    val kcal: Int,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
    val fried: Boolean,
    val source: KcalSource,
    /** A table hit whose table entry is itself an estimate. */
    val tableEstimated: Boolean = false,
    /** The model's high end before the margin (estimates only). */
    val estimateHigh: Int? = null,
)

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
) {
    val kcal: Int get() = items.sumOf { it.kcal }
    val proteinG: Double get() = items.sumOf { it.proteinG ?: 0.0 }
}

data class WeightEntry(val id: String, val at: Long, val day: String, val kg: Double, val waistCm: Double?)

data class FoodEntry(
    val name: String,
    val aliases: List<String>,
    val kcal: Int,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
    val fried: Boolean,
    val estimated: Boolean,
    val seeded: Boolean,
    val addedAt: Long,
)

/** An estimated item waiting for the owner's label or portion before it may enter the food table. */
data class PendingFood(val mealId: String, val itemIndex: Int, val name: String, val countedKcal: Int, val askedAt: Long)

data class CoachState(
    val version: Int = 1,
    val seeded: Boolean = false,
    val meals: List<Meal> = emptyList(),
    val weights: List<WeightEntry> = emptyList(),
    val food: List<FoodEntry> = emptyList(),
    val pending: List<PendingFood> = emptyList(),
)

enum class Speaker { OWNER, COACH }

data class CoachMessage(val id: String, val at: Long, val who: Speaker, val text: String, val kind: String?)

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
