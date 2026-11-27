package app.agency.core.engine

import app.agency.core.schedule.Slot
import app.agency.core.schedule.SlotKey
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每週達標 (owner 2026-11-27): what was due done at least 90%, no breach, and the real-person task done (once it is open);
 * only weeks on target in a row count. Invented numbers.
 */
class WeekTargetTest {
    private val monday = LocalDate.parse("2026-12-14")
    private fun day(done: Int, missed: Int, breaches: Int = 0) = WeekReport.Day(
        monday, List(done) { SlotState(Slot(SlotKey.LUNCH, 700), SlotStatus.DONE, Instant.EPOCH) } +
            List(missed) { SlotState(Slot(SlotKey.DINNER, 1000), SlotStatus.MISSED, Instant.EPOCH) },
        breaches, 0,
    )

    @Test fun ninetyPercentNoBreachAndTheRealTask() {
        assertTrue(WeekReport.onTarget(9, 10, 0, true))
        assertFalse(WeekReport.onTarget(8, 10, 0, true)) // 80% was enough before
        assertFalse(WeekReport.onTarget(10, 10, 1, true))
        assertFalse(WeekReport.onTarget(10, 10, 0, false))
        assertFalse(WeekReport.onTarget(0, 0, 0, true))
        // Before the real-person task opens (week 4 of the rollout), it does not count.
        val w = WeekReport.of("w", 0, monday, listOf(day(9, 1)), Instant.EPOCH, realTask = false, earlierOnTarget = 2, level = "C", realTaskRequired = false)
        assertTrue(w.onTarget)
        assertEquals(3, w.weeksOnTarget)
        assertFalse(Links.L20Week.summary(w).contains("真人任務"))
        val off = WeekReport.of("w", 0, monday, listOf(day(8, 2)), Instant.EPOCH, realTask = true, earlierOnTarget = 2, level = "C")
        assertFalse(off.onTarget)
        assertEquals(0, off.weeksOnTarget) // the streak starts again
        assertTrue(Links.L20Week.summary(off).contains("這週沒達標（要該做的 90% 以上、沒有違規、真人任務做到）"))
    }

    @Test fun onlyWeeksInARowCount() {
        fun w(m: String, on: Boolean) = Signal.WeekEnded("week:$m", 0, m, onTarget = on)
        assertEquals(2, WeekReport.streak(listOf(w("2026-11-23", true), w("2026-11-30", false), w("2026-12-07", true), w("2026-12-14", true))))
        assertEquals(0, WeekReport.streak(listOf(w("2026-12-07", true), w("2026-12-14", false))))
        assertEquals(0, WeekReport.streak(emptyList()))
    }
}
