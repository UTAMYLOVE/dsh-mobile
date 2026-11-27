package app.agency.core.schedule

import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.store.AgencyStore
import java.time.LocalDate

/** The plan sections in the store: seeded with plan v3's defaults on the first open, read back as [PlanSpecs]. */
class PlanStore(private val store: AgencyStore, private val zone: java.time.ZoneId = app.agency.core.time.AgencyClock.TAIPEI) {
    /** Writes the default of every section that has none yet (version 1, by [PlanSection.BY_MIGRATION]); returns how many. */
    suspend fun seedIfMissing(at: Long, rollout: RolloutSpec = RolloutSpec.SEED): Int {
        val have = store.rules.sections().map { it.section }.toSet()
        // A new install starts in week 1 of the rollout (owner 2026-11-27): only lessons.
        val defaults = PlanSpecs(rollout = rollout)
        var n = 0
        store.transaction {
            PlanSectionKey.entries.filter { it !in have }.forEach {
                store.rules.putSection(it, defaults.json(it), PlanSection.BY_MIGRATION, at)
                n++
            }
        }
        return n
    }

    suspend fun specs(): PlanSpecs = PlanSpecs.decode(store.rules.sections().associate { it.section to it.json })

    /**
     * [day]'s plan from the sections and its 「今天休息」/休假餐/「早上很餓」 row, plus today's adjustments (T11b) and its 理論課
     * (T19c: from the day he first said 好 to a video; a 複習考 when one falls on [day]).
     */
    suspend fun plan(day: LocalDate, adjust: List<DayAdjust> = emptyList(), reduction: Reduction? = null): DayPlan =
        DayPlanner.build(day, specs(), store.days.get(day.toString()), adjust, reduction, lessons(day))

    private suspend fun lessons(day: LocalDate): LessonDay {
        val all = store.lessons.all()
        if (all.isEmpty()) return LessonDay()
        val started = all.any { l -> l.listedAt != null && listedDay(l.listedAt) <= day }
        val review = all.any { it.reviewDay == day.toString() }
        return LessonDay(if (started) app.agency.core.lessons.LessonPlan.DAILY else 0, review)
    }

    private fun listedDay(at: Long): LocalDate = app.agency.core.rules.CoachRules.coachDay(java.time.Instant.ofEpochMilli(at), zone, DayClock.DAY_START_HOUR)
}
