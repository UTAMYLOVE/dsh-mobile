package app.agency.core.plan

import app.agency.core.day.DayState
import app.agency.core.day.Kit
import app.agency.core.rules.Rules
import app.agency.core.stage.StageState
import app.agency.core.store.MemStore
import java.time.Instant
import java.time.LocalDate

/** Builds a plan for [day] as it looks at [hhmm] (a logical-day time, so "00:40" is just after midnight). */
class Scene(
    val store: MemStore = MemStore(),
    val day: LocalDate = Kit.monday,
    val stageN: Int = 2,
    val rules: Rules = Kit.rules,
    val stageState: StageState = Kit.stage(stageN, day),
) {
    val monday: LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    fun state(): DayState = Kit.state(store, day, stageState, rules)

    fun at(hhmm: String): Instant = Kit.at(day, hhmm)

    fun plan(
        hhmm: String,
        week: WeekSoFar = WeekSoFar(monday),
        lessons: List<LessonSlot> = emptyList(),
        habits: List<HabitToday> = emptyList(),
        training: app.agency.core.rules.TrainingRule = rules.training,
        extra: List<PlanItem> = emptyList(),
        now: Instant = at(hhmm),
    ): DayPlan = DayPlanner.plan(day, now, rules, stageState, state(), week, lessons, habits, training, extra)

    fun chips(hhmm: String, plan: DayPlan = plan(hhmm)): List<String> = Chips.of(plan, state(), at(hhmm))
}

fun DayPlan.item(id: String): PlanItem = items.first { it.id == id }
fun DayPlan.has(id: String): Boolean = items.any { it.id == id }
fun DayPlan.ids(): List<String> = items.map { it.id }
