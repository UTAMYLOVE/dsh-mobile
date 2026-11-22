package app.agency.core.schedule

import app.agency.core.model.DayOverride
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan v3 §4 as data: the plan of every weekday, the rest day, the soy swap and the coach-day clock (arch T11). */
class DayPlannerTest {
    // 2026-12-07 is a Monday.
    private fun day(offset: Long) = LocalDate.parse("2026-12-07").plusDays(offset)
    private fun times(p: DayPlan) = p.slots.map { "${it.time} ${it.key}" }

    @Test fun classDaysMondayTuesdayThursday() {
        for (offset in listOf(0L, 1L, 3L)) {
            val p = DayPlanner.build(day(offset))
            assertEquals(DayType.CLASS, p.type)
            assertEquals(
                listOf(
                    "08:30 WAKE", "12:10 LUNCH", "17:00 CORE", "17:15 DINNER", "18:00 RIDE_OUT", "21:00 RIDE_MEAL", "01:50 STRENGTH",
                    "02:10 STRETCH", "02:30 SIGN_OUT",
                ),
                times(p),
            )
            assertEquals("蛋白質至少 45 克", p.slot(SlotKey.LUNCH)!!.note)
            assertEquals(DayClock.minute("19:00"), p.slot(SlotKey.RIDE_OUT)!!.untilMinute)
            assertTrue(p.slot(SlotKey.STRENGTH)!!.afterRide)
        }
    }

    @Test fun fridayCoreIsAt1605() {
        val p = DayPlanner.build(day(4))
        assertEquals(DayType.CLASS, p.type)
        assertEquals("16:05", p.slot(SlotKey.CORE)!!.time)
    }

    @Test fun noClassDaysWednesdaySaturdaySunday() {
        for (offset in listOf(2L, 5L, 6L)) {
            val p = DayPlanner.build(day(offset))
            assertEquals(DayType.NO_CLASS, p.type)
            assertEquals(
                listOf(
                    "12:00 WAKE", "13:00 FIRST_MEAL", "16:20 WALK", "17:00 CORE", "17:15 DINNER", "18:00 RIDE_OUT", "21:00 RIDE_MEAL",
                    "03:20 STRENGTH", "03:40 STRETCH", "04:00 SIGN_OUT",
                ),
                times(p),
            )
            assertEquals(SlotKey.FIRST_MEAL, p.firstMeal)
        }
    }

    @Test fun aRestDayDropsDeliveryStrengthAndCoreAndKeepsTheTimes() {
        val d = day(0)
        val p = DayPlanner.build(d, override = DayOverride(d.toString(), restDay = true, declaredAt = 1))
        assertTrue(p.rest)
        assertEquals(listOf("08:30 WAKE", "12:10 LUNCH", "17:15 DINNER", "21:00 RIDE_MEAL", "22:00 STRETCH", "02:30 SIGN_OUT"), times(p))
        assertFalse(p.slot(SlotKey.STRETCH)!!.required) // allowed, not owed
        assertFalse(p.delivery)
        // A no-class rest day keeps its walk and its own times.
        val w = DayPlanner.build(day(2), override = DayOverride(day(2).toString(), restDay = true, declaredAt = 1))
        assertEquals(listOf("12:00 WAKE", "13:00 FIRST_MEAL", "16:20 WALK", "17:15 DINNER", "21:00 RIDE_MEAL", "22:00 STRETCH", "04:00 SIGN_OUT"), times(w))
    }

    @Test fun hungryMorningMovesTheSoyMilkAndKeepsTheChickenAt2100() {
        val d = day(0)
        val p = DayPlanner.build(d, override = DayOverride(d.toString(), soyMorning = true, declaredAt = 1))
        assertEquals("09:00", p.slot(SlotKey.MORNING_SOY)!!.time)
        assertEquals("21:00", p.slot(SlotKey.RIDE_MEAL)!!.time)
        assertEquals("雞胸（豆漿早上喝過了）", p.slot(SlotKey.RIDE_MEAL)!!.note)
        // The same through a 今天微調 (link L4).
        assertEquals(p.slots, DayPlanner.build(d, adjust = listOf(DayAdjust.SoyMorning)).slots)
        assertNull(DayPlanner.build(d).slot(SlotKey.MORNING_SOY))
    }

    @Test fun anIllnessReductionDropsCoreAndStrengthButKeepsTheStretch() {
        val p = DayPlanner.build(day(0), reduction = Reduction.INJURY)
        assertNull(p.slot(SlotKey.CORE))
        assertNull(p.slot(SlotKey.STRENGTH))
        assertTrue(p.slot(SlotKey.STRETCH)!!.required)
        assertEquals("10 分鐘，避開受傷的地方", p.slot(SlotKey.STRETCH)!!.note)
        assertTrue(p.delivery)
    }

    @Test fun aMoveChangesOnlyThatSlot() {
        val p = DayPlanner.build(day(0), adjust = listOf(DayAdjust.Move(SlotKey.LUNCH, DayClock.minute("13:40"))))
        assertEquals("13:40", p.slot(SlotKey.LUNCH)!!.time)
        assertEquals(listOf(SlotKey.WAKE, SlotKey.LUNCH, SlotKey.CORE), p.slots.take(3).map { it.key })
        assertEquals("02:30", p.slot(SlotKey.SIGN_OUT)!!.time)
    }

    @Test fun timesBeforeFiveBelongToTheNightAfter() {
        assertEquals(26 * 60 + 30, DayClock.minute("02:30"))
        assertEquals(5 * 60, DayClock.minute("05:00"))
        assertEquals("02:30", DayClock.hhmm(26 * 60 + 30))
    }

    @Test fun storedSectionsAreReadAndABrokenOneIsTheDefault() {
        val specs = PlanSpecs.decode(
            mapOf(
                app.agency.core.model.PlanSectionKey.SCHEDULE to """{"classWake":"08:20"}""",
                app.agency.core.model.PlanSectionKey.MEALS to "不是 JSON",
            ),
        )
        assertEquals("08:20", specs.schedule.classWake)
        assertEquals(MealSpec(), specs.meals)
        assertEquals("08:20", DayPlanner.build(day(0), specs).slot(SlotKey.WAKE)!!.time)
    }
}
