package app.agency.core.engine

import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.WaterSpec
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * His 1 L bottle (plan v3 §5 喝水, arch 1.5 `WaterPlan`), in half bottles: weeks 1–2 of the plan one bottle a day (2 halves),
 * then half a bottle more every two weeks, at most two bottles (4). Only plain water counts. Most of it between waking and
 * leaving for the run, small sips on the run, not much late at night: the pace is checked at 15:00 (half of the day's target,
 * L10) and the target is due before he leaves (L8's reminder 20 minutes before; on a day without a run, by 20:00).
 */
object WaterPlan {
    /** The half-way check of L10. */
    val HALF_CHECK_MINUTE = DayClock.minute("15:00")

    /** By when the bottle is finished on a day without a run (a rest day). */
    val NO_RIDE_DEADLINE_MINUTE = DayClock.minute("20:00")

    /** The day the plan counts from: the start he set in 設定 › 作息, else the day he joined, else [day] itself. */
    fun start(spec: WaterSpec, joinDate: String?, day: LocalDate): LocalDate =
        listOf(spec.startDay, joinDate).firstNotNullOfOrNull { s -> s?.let { runCatching { LocalDate.parse(it) }.getOrNull() } } ?: day

    /** [day]'s target in half bottles (a day before the start is week 1). */
    fun target(day: LocalDate, spec: WaterSpec, joinDate: String? = null): Int {
        val weeks = ChronoUnit.DAYS.between(start(spec, joinDate, day), day).coerceAtLeast(0) / 7
        val steps = (weeks / spec.stepWeeks.coerceAtLeast(1)).toInt()
        return (spec.baseHalves + steps).coerceAtMost(spec.maxHalves)
    }

    /** Half of the target, rounded up: what should be in by [HALF_CHECK_MINUTE]. */
    fun half(target: Int): Int = (target + 1) / 2

    /** The minute the day's water is due: before the run (its earliest start), or [NO_RIDE_DEADLINE_MINUTE] without one. */
    fun deadline(plan: DayPlan): Int = plan.slot(SlotKey.RIDE_OUT)?.minute ?: NO_RIDE_DEADLINE_MINUTE

    /** 「1 壺半」 for 3 halves, 「半壺」 for 1. */
    fun bottles(halves: Int): String = when {
        halves <= 0 -> "0 壺"
        halves == 1 -> "半壺"
        halves % 2 == 0 -> "${halves / 2} 壺"
        else -> "${halves / 2} 壺半"
    }
}
