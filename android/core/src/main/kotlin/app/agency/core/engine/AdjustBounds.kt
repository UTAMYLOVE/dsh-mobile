package app.agency.core.engine

import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.SlotKey

/**
 * The SafetyBounds of a 今天微調 (agency-links §1.3, owner: 「連動只做安排上的調整，不放寬任何要求」): a link may move the first
 * meal (not past three hours before the next meal, never earlier than planned) or the walk (before the core), swap the 豆漿 to
 * the morning, or set a flag. [base] is today's plan before any 今天微調. It can never remove a required item, move sleep, the wake slot, the ride or the on-run chicken
 * breast, and it has no way to touch 公司規章 (no effect carries them).
 */
object AdjustBounds {
    val MOVABLE = setOf(SlotKey.LUNCH, SlotKey.FIRST_MEAL, SlotKey.WALK, SlotKey.LESSONS)

    fun allows(change: DayAdjust, plan: DayPlan, base: DayPlan, firstMealGapMin: Int): Boolean = when (change) {
        is DayAdjust.Flag -> true
        // Owner 2026-11-26: the first day after an illness has two rounds (never none, never more than planned).
        is DayAdjust.Recovery -> plan.slot(SlotKey.STRENGTH) != null && change.rounds in 1 until base.strengthRounds
        // A smaller meal, never none and never above the single-meal cap's bound (the link keeps the floor of the day).
        is DayAdjust.MealBudget -> change.key in app.agency.core.rules.MealBudget.ADJUSTABLE && plan.slot(change.key) != null &&
            change.kcal in app.agency.core.rules.MealBudget.MIN_MEAL_KCAL..app.agency.core.rules.SafetyBounds.MEAL_CAP_MAX
        DayAdjust.SoyMorning -> plan.slot(SlotKey.RIDE_MEAL) != null
        is DayAdjust.Move -> {
            val slot = plan.slot(change.key)
            when {
                slot == null || change.key !in MOVABLE -> false
                // 理論課 (L18): earlier only (never later than planned), at least an hour after the first meal.
                change.key == SlotKey.LESSONS -> {
                    val first = plan.slot(plan.firstMeal)?.minute
                    val from = base.slot(SlotKey.LESSONS)?.minute
                    first != null && from != null && change.minute <= from && change.minute >= first + 60
                }
                change.key == SlotKey.WALK -> {
                    val core = plan.slot(SlotKey.CORE)?.minute ?: plan.slot(SlotKey.DINNER)!!.minute
                    change.minute in DayClock.minute("13:00")..(core - 30)
                }
                else -> {
                    val next = plan.slot(SlotKey.DINNER)?.minute
                    val from = base.slot(change.key)?.minute
                    next != null && from != null && change.minute >= from && change.minute <= next - firstMealGapMin
                }
            }
        }
    }
}
