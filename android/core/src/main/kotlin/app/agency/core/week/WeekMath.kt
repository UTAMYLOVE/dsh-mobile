package app.agency.core.week

import app.agency.core.plan.TaskId
import app.agency.core.plan.WeekSoFar
import app.agency.core.rules.Rules
import app.agency.core.store.Kind
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.Store
import app.agency.core.store.TaskDone
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Small helpers shared by the week package: calendar weeks run Monday to Sunday. */
internal object Weeks {
    fun monday(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

/**
 * What the planner needs to know about this calendar week up to yesterday. Reads only; today's own records are the planner's
 * business (it reads them from the day state).
 */
object WeekMath {
    fun soFar(store: Store, day: LocalDate, rules: Rules): WeekSoFar {
        val weekStart = Weeks.monday(day)
        val end = day.minusDays(1)
        val entries = if (end.isBefore(weekStart)) emptyList() else store.entries(weekStart, end)

        val tasks = entries.filter { it.kind == Kind.TASK }
            .mapNotNull { e -> runCatching { e.read<TaskDone>() }.getOrNull()?.let { e to it } }
        val cardio = tasks.filter { it.second.task == TaskId.CARDIO.name }.map { it.first.day }.distinct().size
        val expression = tasks.filter { it.second.task == TaskId.EXPRESSION.name }.map { it.first.day }.distinct().size
        val restUsed = entries.any { e ->
            e.kind == Kind.STATUS && runCatching { e.read<Status>().type }.getOrNull() == StatusType.REST_DAY
        }
        val rides = entries.filter { it.kind == Kind.RIDE }
            .mapNotNull { e -> runCatching { e.read<Ride>() }.getOrNull()?.let { e to it } }
        val income = rides.sumOf { it.second.income ?: 0 }
        val rideDays = rides.filter { it.second.phase == RidePhase.OUT }.map { it.first.day }.distinct().size

        return WeekSoFar(
            weekStart = weekStart,
            cardioDone = cardio,
            expressionDone = expression,
            restDayUsed = restUsed,
            rideIncome = income,
            rideDays = rideDays,
            groomed = groomed(store, day, rules),
        )
    }

    /** Last day each grooming item was done (the item name is in the task's note; several names may be joined by 、 or a comma). */
    private fun groomed(store: Store, day: LocalDate, rules: Rules): Map<String, LocalDate> {
        if (rules.grooming.isEmpty()) return emptyMap()
        val window = rules.grooming.maxOf { it.everyDays }.coerceAtLeast(1).toLong()
        val names = rules.grooming.map { it.name }
        val last = HashMap<String, LocalDate>()
        for (e in store.entries(day.minusDays(window), day.minusDays(1), Kind.TASK)) {
            val t = runCatching { e.read<TaskDone>() }.getOrNull() ?: continue
            if (t.task != TaskId.GROOMING.name) continue
            val said = t.note.split('、', ',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            for (n in names) if (n in said) last.merge(n, e.day) { a, b -> if (a.isAfter(b)) a else b }
        }
        return last
    }
}
