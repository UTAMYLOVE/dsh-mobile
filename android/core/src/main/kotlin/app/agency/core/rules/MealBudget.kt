package app.agency.core.rules

import app.agency.core.model.CompanyRules
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.SlotKey
import kotlin.math.ceil

/** What the next meal may be (L5, the pre-meal suggestion, a craving's 「換成」): code's numbers only. */
data class NextMealBudget(val slot: SlotKey, val kcal: Int, val proteinG: Int, val usual: Int) {
    /** Smaller than his usual meal for that slot: the day's meals so far leave less room. */
    val smaller: Boolean get() = kcal < usual
}

/**
 * The size of the meals still to come (plan v3 §4: 午餐 / 第一餐 about 700, 晚餐 about 714, the 21:00 portion 雞胸 117 + 豆漿
 * 193; owner: these are rough guides, only the day's totals and the rules matter). The 21:00 portion and the morning 豆漿 are
 * fixed (riding safety), so a budget only ever shrinks 午餐, 第一餐 or 晚餐, never below [MIN_MEAL_KCAL] (a meal is eaten, never
 * skipped), never so far that the day ends under the floor (1200), and never when the day is already over the quota: then the
 * answer is 「明天回到 1700」, not a smaller meal.
 */
object MealBudget {
    const val CHICKEN_KCAL = 117
    const val SOY_KCAL = 193
    const val MIN_MEAL_KCAL = 200

    /** The meals a budget may shrink (the fixed portions are not among them). */
    val ADJUSTABLE = setOf(SlotKey.LUNCH, SlotKey.FIRST_MEAL, SlotKey.DINNER)

    fun usual(key: SlotKey, plan: DayPlan): Int = when (key) {
        SlotKey.LUNCH, SlotKey.FIRST_MEAL -> 700
        SlotKey.DINNER -> 714
        SlotKey.RIDE_MEAL -> CHICKEN_KCAL + if (plan.soyMorning) 0 else SOY_KCAL
        SlotKey.MORNING_SOY -> SOY_KCAL
        else -> 0
    }

    /**
     * The next adjustable meal after [afterMinute] that is not eaten yet ([eatenSlots]) and its budget given [eatenKcal] and
     * [eatenProteinG] so far; null when none is left, or when the day is over the quota already (no cutting to make up).
     */
    fun next(plan: DayPlan, rules: CompanyRules, eatenKcal: Int, eatenProteinG: Double, eatenSlots: Set<SlotKey>, afterMinute: Int): NextMealBudget? {
        val later = plan.slots.filter { it.key in SlotKey.MEALS && it.key !in eatenSlots && it.minute > afterMinute }
        val next = later.firstOrNull { it.key in ADJUSTABLE } ?: return null
        val left = rules.quotaKcal - eatenKcal
        if (left <= 0) return null
        val reserve = later.filter { it.key != next.key }.sumOf { usual(it.key, plan) }
        val usual = usual(next.key, plan)
        // Never a budget that leaves the day under the floor: enough to reach it, up to the single-meal cap.
        val floorNeed = minOf(rules.mealCapKcal, rules.floorKcal - eatenKcal - reserve)
        val kcal = maxOf(minOf(usual, rules.mealCapKcal, left - reserve), floorNeed, MIN_MEAL_KCAL)
        val proteinLeft = CoachRules.remainingProtein(rules.proteinG, eatenProteinG)
        val protein = ceil(proteinLeft.toDouble() / later.size).toInt()
        return NextMealBudget(next.key, kcal, protein, usual)
    }
}
