package app.agency.core.plan

import app.agency.core.day.Kit
import app.agency.core.day.Kit.monday
import app.agency.core.day.Kit.saturday
import app.agency.core.remind.Anchors
import app.agency.core.remind.ReminderLog
import app.agency.core.remind.Reminders
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixWavePlanTest {
    /** B13: on a late free-day sign-in the practice before the first meal moves with the meal instead of being late at once. */
    @Test fun preMealPracticeShiftsWithTheFirstMeal() {
        val sc = Scene(day = saturday, stageN = 4)
        Kit.checkIn(sc.store, saturday, "10:50")
        val p = sc.plan("12:40")
        assertEquals(LocalTime.of(13, 50), p.item("meal:FIRST").at)
        for (id in listOf("task:DANCE", "task:VOCAL")) {
            assertEquals(id, LocalTime.of(13, 50), p.item(id).until)
            assertEquals(id, ItemStatus.PENDING, p.item(id).status)
        }
    }

    /** B6: an untimed item is reminded from its start, one untimed notice per wake, then chased every 30 minutes. */
    @Test fun untimedItemsAreRemindedOneAtATime() {
        val sc = Scene(day = monday, stageN = 3)
        Kit.checkIn(sc.store, monday, "06:50")
        val anchors = Anchors(checkInAt = Kit.at(monday, "06:50"), rideDay = true, classEndAt = Kit.at(monday, "15:30"))
        fun decide(hhmm: String, log: ReminderLog) = Reminders.decide(
            Kit.at(monday, hhmm), Kit.zone, sc.plan(hhmm), true, false, LocalTime.of(0, 50), emptyList(), null, log, anchors = anchors,
        )
        val untimed = setOf("task:POSTURE", "task:MORNING_STRETCH", "task:VOCAL_BASICS")
        val early = decide("09:40", ReminderLog())
        assertTrue(early.notices.none { it.key in untimed })
        assertEquals(Kit.at(monday, "09:50"), early.nextWakeAt)
        var log = early.log
        val keys = ArrayList<String>()
        for (t in listOf("09:50", "09:55", "10:00")) {
            val d = decide(t, log)
            assertTrue("$t: ${d.notices}", d.notices.count { it.key in untimed } <= 1)
            keys += d.notices.map { it.key }.filter { it in untimed }
            log = d.log
        }
        assertEquals(untimed, keys.toSet())
        // Nothing again until half an hour after each first notice, then a chase.
        assertTrue(decide("10:10", log).notices.none { it.key in untimed })
        val chase = decide("10:21", log)
        assertEquals(1, chase.notices.count { it.key in untimed })
        // Done: never again.
        Kit.task(sc.store, monday, "10:30", TaskId.POSTURE)
        var l2 = chase.log
        for (t in listOf("10:52", "11:24", "11:56")) {
            val d = decide(t, l2)
            assertTrue(d.notices.none { it.key == "task:POSTURE" })
            l2 = d.log
        }
        // Training waits for the ride back on a ride day: never reminded before.
        assertTrue(keys.none { it.startsWith("task:CORE") })
    }
}
