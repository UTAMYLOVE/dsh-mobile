package app.agency.core.schedule

import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.store.InMemoryAgencyStore
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanStoreTest {
    @Test fun theDefaultsAreSeededOnceAndAStoredChangeWins() = runBlocking {
        val store = InMemoryAgencyStore()
        val plans = PlanStore(store)
        assertEquals(4, plans.seedIfMissing(10L))
        assertEquals(0, plans.seedIfMissing(20L))
        assertEquals(PlanSectionKey.entries.toList(), store.rules.sections().map { it.section })
        store.rules.sections().forEach { assertEquals(1, it.version); assertEquals(PlanSection.BY_MIGRATION, it.changedBy) }
        store.rules.putSection(PlanSectionKey.MEALS, PlanSpecs.JSON.encodeToString(MealSpec.serializer(), MealSpec(dinner = "17:30")), PlanSection.BY_OWNER_SETTINGS, 30L)
        assertEquals("17:30", plans.plan(LocalDate.parse("2026-12-07")).slot(SlotKey.DINNER)!!.time)
    }
}
