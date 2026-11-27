package app.agency.core.engine

import app.agency.core.brain.TodayRecords
import app.agency.core.schedule.DayPlanner
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 指揮室 role cards (T20): each person's line from code — waiting, counting protein, busy, failed, idle. Invented data. */
class RoleActivityTest {
    private val day = LocalDate.parse("2026-12-08") // a Tuesday
    private val plan = DayPlanner.build(day, app.agency.core.PracticeOff.SPECS)

    private fun card(at: String, protein: Int = 45): IdolCardFacts {
        val now = RuleEngine.instant(day, app.agency.core.schedule.DayClock.minute(at), AgencyClock.TAIPEI)
        val slots = RuleEngine.status(plan, TodayRecords(day = day.toString()), now, AgencyClock.TAIPEI)
        return IdolCardFacts("小雨娛樂", null, null, "001", "C", null, emptyList(), null, 1700, protein, 0, 0, null, slots)
    }

    @Test fun eachRoleSaysWhatItIsDoing() {
        assertEquals("在等你 08:30 的起床簽到", RoleActivity.manager(card("09:00"), busy = false, failed = false))
        assertEquals("在等你 12:10 的午餐", RoleActivity.body(card("12:20"), busy = false, failed = false))
        assertEquals("在算你今天剩下的蛋白質：還差 45 克", RoleActivity.body(card("08:00"), busy = false, failed = false))
        assertEquals(RoleActivity.BUSY, RoleActivity.manager(card("09:00"), busy = true, failed = false))
        assertEquals(RoleActivity.FAILED, RoleActivity.body(card("09:00"), busy = false, failed = true))
        val empty = card("09:00").copy(slots = emptyList())
        assertEquals(RoleActivity.IDLE, RoleActivity.manager(empty, false, false))
        assertEquals(RoleActivity.IDLE, RoleActivity.body(empty, false, false))
        assertTrue(listOf(RoleActivity.BUSY, RoleActivity.FAILED, RoleActivity.IDLE).none { s -> s.any { it in 'a'..'z' || it in 'A'..'Z' } })
    }
}
