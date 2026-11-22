package app.agency.core.schedule

import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.store.AgencyStore
import java.time.LocalDate

/** The plan sections in the store: seeded with plan v3's defaults on the first open, read back as [PlanSpecs]. */
class PlanStore(private val store: AgencyStore) {
    /** Writes the default of every section that has none yet (version 1, by [PlanSection.BY_MIGRATION]); returns how many. */
    suspend fun seedIfMissing(at: Long): Int {
        val have = store.rules.sections().map { it.section }.toSet()
        val defaults = PlanSpecs()
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

    /** [day]'s plan from the sections and its 「今天休息」/休假餐/「早上很餓」 row, plus today's adjustments (T11b). */
    suspend fun plan(day: LocalDate, adjust: List<DayAdjust> = emptyList(), reduction: Reduction? = null): DayPlan =
        DayPlanner.build(day, specs(), store.days.get(day.toString()), adjust, reduction)
}
