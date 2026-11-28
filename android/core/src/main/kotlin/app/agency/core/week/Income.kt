package app.agency.core.week

import app.agency.core.rules.Rules
import app.agency.core.store.Kind
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Store
import java.time.LocalDate

/**
 * Ride income of one calendar week. [remainingDays] counts the days after [day] up to Sunday, plus [day] itself while he has not
 * come back from today's ride; [perRemainingDay] is [left] spread over them (rounded up), or [left] when none remain.
 */
data class IncomeWeek(
    val weekStart: LocalDate,
    val income: Int,
    val daysRidden: Int,
    val target: Int,
    val left: Int,
    val remainingDays: Int,
    val perRemainingDay: Int,
) {
    val reached: Boolean get() = target > 0 && income >= target
}

/** Income is judged by the week total only, never per day. */
object Income {
    fun week(store: Store, day: LocalDate, rules: Rules): IncomeWeek {
        val monday = Weeks.monday(day)
        val rides = store.entries(monday, day, Kind.RIDE).mapNotNull { e ->
            runCatching { e.read<Ride>() }.getOrNull()?.let { e to it }
        }
        val income = rides.sumOf { it.second.income ?: 0 }
        val ridden = rides.filter { it.second.phase == RidePhase.OUT }.map { it.first.day }.distinct().size
        val target = rules.ride.weeklyIncome
        val left = (target - income).coerceAtLeast(0)
        val backToday = rides.any { it.first.day == day && it.second.phase == RidePhase.BACK }
        val remaining = (7 - day.dayOfWeek.value) + if (backToday) 0 else 1
        val per = if (remaining <= 0) left else (left + remaining - 1) / remaining
        return IncomeWeek(monday, income, ridden, target, left, remaining, per)
    }
}
