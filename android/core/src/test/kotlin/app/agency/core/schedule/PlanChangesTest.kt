package app.agency.core.schedule

import app.agency.core.model.PlanSectionKey
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanChangesTest {
    private val today = LocalDate.parse("2026-11-23")
    private fun check(section: PlanSectionKey, vararg ops: Pair<String, String>) = PlanChanges.check(PlanSpecs(), section, ops.toList(), today)
    private fun reason(c: PlanChanges.Checked) = (c as PlanChanges.Checked.Bad).problem.reason

    @Test fun timesMoveWithBeforeAndAfter() {
        val ok = check(PlanSectionKey.SCHEDULE, "class_wake" to "08:20") as PlanChanges.Checked.Ok
        assertEquals(listOf(PlanOp("class_wake", "上課日起床", "08:30", "08:20")), ok.change.ops)
        assertEquals("08:20", ok.specs.schedule.classWake)
        assertEquals("12:10", ok.specs.meals.classLunch) // nothing else changes
    }

    @Test fun bedtimeOnlyEarlierAndThe2100PortionStays() {
        assertTrue(reason(check(PlanSectionKey.SCHEDULE, "class_sleep_latest" to "03:00")).contains("只能提早"))
        assertTrue(check(PlanSectionKey.SCHEDULE, "class_sleep_latest" to "02:20") is PlanChanges.Checked.Ok)
        assertTrue(reason(check(PlanSectionKey.MEALS, "dinner" to "20:30")).contains("21:00"))
        assertTrue(PlanPath.entries.none { it.key.contains("ride_meal") || it.key.contains("rounds") })
    }

    @Test fun badInputIsSaidPlainly() {
        assertTrue(reason(check(PlanSectionKey.SCHEDULE)).contains("至少"))
        assertTrue(reason(check(PlanSectionKey.SCHEDULE, "class_wake" to "08:30")).contains("一樣"))
        assertTrue(reason(check(PlanSectionKey.SCHEDULE, "class_wake" to "25:00")).contains("HH:mm"))
        assertTrue(reason(check(PlanSectionKey.SCHEDULE, "class_wake" to "08:20", "class_wake" to "08:10")).contains("一次"))
        assertTrue(reason(check(PlanSectionKey.WATER, "start_day" to "2026-11-20")).contains("過去"))
        assertTrue(check(PlanSectionKey.WATER, "start_day" to "2026-11-30") is PlanChanges.Checked.Ok)
        assertTrue(reason(check(PlanSectionKey.SCHEDULE, "dinner" to "17:30")).contains("不在這一段"))
        assertTrue(reason(check(PlanSectionKey.MEALS, "class_lunch" to "16:00")).contains("兩小時"))
    }
}
