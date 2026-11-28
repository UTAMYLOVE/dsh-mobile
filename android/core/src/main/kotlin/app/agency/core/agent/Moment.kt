package app.agency.core.agent

import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.day.Bedtime
import app.agency.core.day.DayState
import app.agency.core.extras.Extras
import app.agency.core.plan.DayPlan
import app.agency.core.plan.PlanItem
import app.agency.core.rules.Rules
import app.agency.core.rules.TrainingRule
import app.agency.core.stage.StageState
import app.agency.core.stage.Today
import app.agency.core.store.Store
import app.agency.core.week.Progression
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Today as code sees it right now: stage, day state, the plan with the stage-4 items, the effective training load. Built
 * once per question (a tool's facts, the context block, the home screen), so they all read the same numbers.
 */
class Moment private constructor(
    val store: Store,
    val clock: Clock,
    val rules: Rules,
    val now: Instant,
    val day: LocalDate,
    val stage: StageState,
    val state: DayState,
    val plan: DayPlan,
    val training: TrainingRule,
) {
    val bedtime: LocalTime? get() = Bedtime.of(rules, stage, day)

    fun item(id: String): PlanItem? = plan.items.firstOrNull { it.id == id }

    companion object {
        fun of(store: Store, clock: Clock, rules: Rules): Moment {
            val now = clock.now()
            val day = Days.of(now, clock.zone)
            return at(store, clock, rules, day, now)
        }

        /** The plan of [day] as it looks at [now] (rollover passes the end of the day). */
        fun at(store: Store, clock: Clock, rules: Rules, day: LocalDate, now: Instant): Moment {
            val stage = Today.stage(store, day)
            val state = Today.state(store, day, rules, stage, clock.zone)
            val extra = extras(store, rules, stage, state, day, now)
            val plan = Today.planFor(store, clock, rules, stage, day, now, extra)
            return Moment(store, clock, rules, now, day, stage, state, plan, Progression.effective(rules, Today.levels(store)))
        }

        /** The stage-4 items of [day]; never breaks today's plan if one of them cannot be built. */
        fun extras(store: Store, rules: Rules, stage: StageState, state: DayState, day: LocalDate, now: Instant): List<PlanItem> =
            runCatching { Extras.items(day, now, store, rules, stage, state) }.getOrDefault(emptyList())
    }
}
