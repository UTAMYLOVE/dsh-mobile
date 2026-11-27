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
 * The size of the meals still to come (owner 2026-11-26: three meals, no eating window and no snack: 午餐 / 第一餐 about 700,
 * 晚餐 about 700, the 21:00 portion 雞胸 117 + 豆漿 193 or a swap of the same protein; these are rough guides, only the day's
 * totals and the rules matter). A meal may go up to the single-meal cap (800: a ceiling, not a target): 午餐 may be 800, and
 * the meals after it follow what is left (晚餐 smaller then). The 21:00 portion and the morning 豆漿 are fixed (riding safety),
 * so a budget only ever shrinks 午餐, 第一餐 or 晚餐, never below [MIN_MEAL_KCAL] (a meal is eaten, never skipped), never so far
 * that the day ends under the floor (1200), and never when the day is already over the quota: then the answer is 「明天回到
 * 1700」, not a smaller meal.
 */
object MealBudget {
    const val CHICKEN_KCAL = 117
    const val SOY_KCAL = 193
    const val MIN_MEAL_KCAL = 200

    /** The meals a budget may shrink (the fixed portions are not among them). */
    val ADJUSTABLE = setOf(SlotKey.LUNCH, SlotKey.FIRST_MEAL, SlotKey.DINNER)

    fun usual(key: SlotKey, plan: DayPlan): Int = when (key) {
        SlotKey.LUNCH, SlotKey.FIRST_MEAL -> 700
        SlotKey.DINNER -> 700
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
        val rest = later.filter { it.key != next.key }
        // What the later meals must keep: the fixed portions as they are, an adjustable meal at least its smallest (it follows
        // what is left: 「午餐可以吃到 800，晚餐自動調整」).
        val reserve = rest.sumOf { if (it.key in ADJUSTABLE) MIN_MEAL_KCAL else usual(it.key, plan) }
        val usual = usual(next.key, plan)
        // Never a budget that leaves the day under the floor: enough to reach it, up to the single-meal cap.
        val floorNeed = minOf(rules.mealCapKcal, rules.floorKcal - eatenKcal - rest.sumOf { usual(it.key, plan) })
        val kcal = maxOf(minOf(rules.mealCapKcal, left - reserve), floorNeed, MIN_MEAL_KCAL)
        val proteinLeft = CoachRules.remainingProtein(rules.proteinG, eatenProteinG)
        val protein = ceil(proteinLeft.toDouble() / later.size).toInt()
        return NextMealBudget(next.key, kcal, protein, usual)
    }

    /**
     * The room for one meal he asks about or orders for [slot]: a planned meal not eaten yet gets its budget (L5's when
     * smaller, its usual size otherwise; a planned meal is eaten whatever the day), within the single-meal cap; an extra gets
     * what is left today, or null when nothing is.
     */
    fun room(plan: DayPlan, rules: CompanyRules, slot: SlotKey?, eatenKcal: Int, eatenProteinG: Double, eatenSlots: Set<SlotKey>, minute: Int): Int? {
        if (slot != null && slot !in eatenSlots && slot in SlotKey.MEALS) {
            val b = plan.budgets[slot]
                ?: next(plan, rules, eatenKcal, eatenProteinG, eatenSlots, (plan.slot(slot)?.minute ?: minute) - 1)?.takeIf { it.slot == slot }?.kcal
                ?: usual(slot, plan)
            return minOf(b, rules.mealCapKcal)
        }
        val left = rules.quotaKcal - eatenKcal
        return if (left <= 0) null else minOf(left, rules.mealCapKcal)
    }
}
