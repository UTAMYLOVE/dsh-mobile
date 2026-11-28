package app.agency.core.stage

import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.day.DayState
import app.agency.core.lessons.Habits
import app.agency.core.lessons.LessonDay
import app.agency.core.lessons.Levels
import app.agency.core.plan.DayPlan
import app.agency.core.plan.DayPlanner
import app.agency.core.plan.PlanItem
import app.agency.core.rules.Rules
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.readDoc
import app.agency.core.week.Progression
import app.agency.core.week.WeekMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one place that assembles everything the planner needs. Tools, reminders and the home screen all call [plan], so they cannot
 * disagree about today. Reads only.
 */
object Today {
    /** Illness is looked up this far back so a long sickness is not forgotten after the 14-day window. */
    const val UNWELL_LOOKBACK_DAYS = 60L
    private const val RECENT_DAYS = 14L

    /** The stored stage, or (before he has joined) a stage-1 round starting [day], without writing it. */
    /** The stage as it stood on [day] (a past day keeps the stage it had, see [StageDays.asOf]). */
    fun stage(store: Store, day: LocalDate): StageState =
        store.readDoc<StageState>(Doc.STAGE)?.let { app.agency.core.day.StageDays.asOf(it, day) } ?: StageState(joinedOn = day.toString())

    fun levels(store: Store): Levels = store.readDoc<Levels>(Doc.LEVELS) ?: Levels()

    /** The state of logical [day]: its entries, 14 days of `recent`, and older status entries for a long illness. */
    fun state(store: Store, day: LocalDate, rules: Rules, stage: StageState, zone: ZoneId): DayState {
        val recent = store.entries(day.minusDays(RECENT_DAYS), day.minusDays(1)) +
            store.entries(day.minusDays(UNWELL_LOOKBACK_DAYS), day.minusDays(RECENT_DAYS + 1), Kind.STATUS)
        return DayState.of(day, store.entries(day), recent, rules, stage, zone)
    }

    /** Today's plan. [extra] are ready-made stage-4 items. */
    fun plan(store: Store, clock: Clock, rules: Rules, extra: List<PlanItem> = emptyList()): DayPlan {
        val day = Days.today(clock)
        return planFor(store, clock, rules, stage(store, day), day, clock.now(), extra)
    }

    /** The plan of [day] as it looks at [now] (rollover passes the day's end to see what was missed). */
    fun planFor(
        store: Store,
        clock: Clock,
        rules: Rules,
        stage: StageState,
        day: LocalDate,
        now: Instant,
        extra: List<PlanItem> = emptyList(),
    ): DayPlan {
        val state = state(store, day, rules, stage, clock.zone)
        val levels = levels(store)
        return DayPlanner.plan(
            day = day,
            now = now,
            rules = rules,
            stageState = stage,
            state = state,
            week = WeekMath.soFar(store, day, rules),
            lessons = LessonDay.of(day, store, rules, state.examWeek, levels),
            habits = Habits(store, clock).today(day),
            training = Progression.effective(rules, levels),
            extra = extra,
        )
    }
}
