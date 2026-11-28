package app.agency.core.week

import app.agency.core.lessons.Lesson
import app.agency.core.rules.Rules
import app.agency.core.store.DayClose
import app.agency.core.store.Kind
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Store
import app.agency.core.store.WeekResult
import java.time.LocalDate

/** Builds the weekly result of one finished calendar week from what the store holds. Reads only. */
object WeekReport {
    private const val LESSON_LOOKBACK = 120L

    fun build(store: Store, weekStart: LocalDate, rules: Rules): WeekResult {
        val end = weekStart.plusDays(6)
        val closes = store.entries(weekStart, end, Kind.DAY_CLOSE).mapNotNull { runCatching { it.read<DayClose>() }.getOrNull() }
        val done = closes.sumOf { it.done }
        val total = closes.sumOf { it.total }
        val pct = if (total <= 0) 100 else done * 100 / total

        val rides = store.entries(weekStart, end, Kind.RIDE).mapNotNull { e ->
            runCatching { e.read<Ride>() }.getOrNull()?.let { e to it }
        }
        val income = rides.sumOf { it.second.income ?: 0 }
        val rideDays = rides.filter { it.second.phase == RidePhase.OUT }.map { it.first.day }.distinct().size

        val weights = BodyTrend.weekly(store, end)
        val weight = weights[0]
        val before = weights[1]

        val from = weekStart.toString()
        val to = end.toString()
        val passed = store.entries(weekStart.minusDays(LESSON_LOOKBACK), end, Kind.LESSON).count { e ->
            val p = runCatching { e.read<Lesson>().passedOn }.getOrNull()
            p != null && p >= from && p <= to
        }
        return WeekResult(
            weekStart = from,
            completionPct = pct,
            violations = closes.sumOf { it.violations },
            rideIncome = income,
            rideTarget = rules.ride.weeklyIncome,
            rideDays = rideDays,
            weightKg = weight,
            weightChangeKg = if (weight != null && before != null) Math.round((weight - before) * 10) / 10.0 else null,
            lessonsPassed = passed,
            perfectDays = closes.count { it.perfect },
        )
    }
}
