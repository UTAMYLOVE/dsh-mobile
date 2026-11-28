package app.agency.core.day

import app.agency.core.day.Kit.monday
import app.agency.core.day.Kit.tuesday
import app.agency.core.plan.TaskId
import app.agency.core.stage.StageState
import app.agency.core.store.Body
import app.agency.core.store.Kind
import app.agency.core.store.MealSlot
import app.agency.core.store.MemStore
import app.agency.core.store.RidePhase
import app.agency.core.store.StatusType
import app.agency.core.store.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayStateTest {
    private val stage = Kit.stage(2)

    private fun state(s: MemStore, day: java.time.LocalDate = monday, st: StageState = stage) = Kit.state(s, day, st)

    @Test fun mealTotalsAndLeftKcal() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 600)
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 700)
        val st = state(s)
        assertEquals(1300, st.kcal)
        assertEquals(700, st.leftKcal)
        assertEquals(60.0, st.proteinG, 0.0)
        assertEquals(2, st.meals.size)
    }

    @Test fun budgetReservesWhatTheLaterMealsAreExpectedToTake() {
        val st = state(MemStore())
        assertEquals(800, st.budget(MealSlot.FIRST))
        assertEquals(900, st.budget(MealSlot.DINNER))
        assertEquals(900, st.budget(MealSlot.NIGHT))
    }

    @Test fun aBigFirstMealShrinksDinner() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 900)
        assertEquals(700, state(s).budget(MealSlot.DINNER))
    }

    @Test fun budgetAfterDinnerNoLongerReservesDinner() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 800)
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 800)
        assertEquals(400, state(s).budget(MealSlot.NIGHT))
    }

    @Test fun budgetIsNeverNegative() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 900)
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 900)
        Kit.meal(s, monday, "21:00", MealSlot.NIGHT, 900)
        assertEquals(0, state(s).budget(MealSlot.EXTRA))
        assertEquals(-700, state(s).leftKcal)
    }

    @Test fun waterTargetRampsUpAfterTheStartingPeriod() {
        val st = state(MemStore())
        assertEquals(1000, st.waterTargetMl(1))
        assertEquals(1000, st.waterTargetMl(14))
        assertEquals(1250, st.waterTargetMl(15))
        assertEquals(1250, st.waterTargetMl(21))
        assertEquals(1500, st.waterTargetMl(22))
        assertEquals(2000, st.waterTargetMl(500))
    }

    @Test fun waterTargetIsZeroWhenTheRuleIsBlank() {
        val blank = Kit.rules.copy(water = app.agency.core.rules.WaterRule())
        assertEquals(0, Kit.state(MemStore(), monday, stage, blank).waterTargetMl(5))
    }

    @Test fun dayNumberCountsFromJoining() {
        assertEquals(4, state(MemStore()).dayNumber)
    }

    @Test fun waterIsSummed() {
        val s = MemStore()
        Kit.water(s, monday, "08:00", 500)
        Kit.water(s, monday, "10:00", 250)
        assertEquals(750, state(s).waterMl)
    }

    @Test fun rideOutBackAndIncome() {
        val s = MemStore()
        Kit.ride(s, monday, "19:00", RidePhase.OUT)
        assertTrue(state(s).riding)
        Kit.ride(s, monday, "23:00", RidePhase.BACK, income = 1100)
        val st = state(s)
        assertFalse(st.riding)
        assertNotNull(st.rideOutAt)
        assertNotNull(st.rideBackAt)
        assertEquals(1100, st.income)
        assertTrue(st.rode)
    }

    @Test fun aSecondTripMakesHimRidingAgain() {
        val s = MemStore()
        Kit.ride(s, monday, "19:00", RidePhase.OUT)
        Kit.ride(s, monday, "21:00", RidePhase.BACK, income = 300)
        Kit.ride(s, monday, "22:00", RidePhase.OUT)
        assertTrue(state(s).riding)
    }

    @Test fun lastTaskRecordWins() {
        val s = MemStore()
        Kit.task(s, monday, "20:00", TaskId.STRENGTH, rounds = 2)
        Kit.task(s, monday, "21:00", TaskId.STRENGTH, rounds = 3)
        assertEquals(3, state(s).tasks.getValue(TaskId.STRENGTH).rounds)
    }

    @Test fun bodyMergesWeightAndWaist() {
        val s = MemStore()
        s.add(monday, Kit.at(monday, "08:00"), Kind.BODY, Body(kg = 79.5))
        s.add(monday, Kit.at(monday, "08:01"), Kind.BODY, Body(waistCm = 88.0))
        val b = state(s).body!!
        assertEquals(79.5, b.kg!!, 0.0)
        assertEquals(88.0, b.waistCm!!, 0.0)
    }

    @Test fun checkInAndOutAreRead() {
        val s = MemStore()
        Kit.checkIn(s, monday, "07:10", score = 3)
        Kit.checkOut(s, monday, "00:30")
        val st = state(s)
        assertEquals(3, st.checkIn!!.score)
        assertEquals("好", st.checkOut!!.good)
        assertNotNull(st.checkInAt)
        assertNotNull(st.checkOutAt)
    }

    @Test fun sickLastsUntilRecovered() {
        val s = MemStore()
        Kit.status(s, monday, "09:00", StatusType.SICK)
        val st = state(s, tuesday)
        assertTrue(st.sick)
        assertEquals(2, st.sickDays)
        assertFalse(st.firstDayBack)
        Kit.status(s, tuesday, "10:00", StatusType.RECOVERED)
        val later = state(s, tuesday)
        assertFalse(later.sick)
        assertEquals(0, later.sickDays)
        assertTrue(later.firstDayBack)
    }

    @Test fun firstDayBackIsYesterdayOrTodayOnly() {
        val s = MemStore()
        Kit.status(s, monday, "09:00", StatusType.INJURED)
        assertTrue(state(s, monday).injured)
        Kit.status(s, monday, "20:00", StatusType.RECOVERED)
        assertTrue(state(s, tuesday).firstDayBack)
        assertFalse(state(s, java.time.LocalDate.of(2030, 1, 9)).firstDayBack)
    }

    @Test fun recoveredWithoutIllnessChangesNothing() {
        val s = MemStore()
        Kit.status(s, monday, "09:00", StatusType.RECOVERED)
        val st = state(s)
        assertFalse(st.firstDayBack)
        assertFalse(st.unwell)
    }

    @Test fun examWeekLastsSevenDays() {
        val s = MemStore()
        Kit.status(s, monday, "09:00", StatusType.EXAM_WEEK)
        assertTrue(state(s, monday.plusDays(6)).examWeek)
        assertFalse(state(s, monday.plusDays(7)).examWeek)
    }

    @Test fun restDayIsThatDayOnly() {
        val s = MemStore()
        Kit.status(s, monday, "09:00", StatusType.REST_DAY)
        assertTrue(state(s, monday).restDay)
        assertFalse(state(s, tuesday).restDay)
    }

    @Test fun sleepHoursMeasureTheGapFromLastNightsCheckOut() {
        val s = MemStore()
        // Monday's check-out at 01:00 belongs to Monday but happens early on Tuesday's calendar date.
        s.add(monday, app.agency.core.base.Days.at(monday, java.time.LocalTime.of(1, 0), Kit.zone), Kind.CHECK_OUT, app.agency.core.store.CheckOut())
        Kit.checkIn(s, tuesday, "07:00")
        assertEquals(6.0, state(s, tuesday).sleepHours!!, 0.001)
    }

    @Test fun sleepHoursAreNullWhenEitherSideIsMissing() {
        val s = MemStore()
        Kit.checkIn(s, tuesday, "07:00")
        assertNull(state(s, tuesday).sleepHours)
        val s2 = MemStore()
        Kit.checkOut(s2, monday, "01:00")
        assertNull(state(s2, tuesday).sleepHours)
    }

    @Test fun todaysViolationsAreListed() {
        val s = MemStore()
        s.add(monday, Kit.at(monday, "13:00"), Kind.VIOLATION, app.agency.core.store.Violation("OVER_KCAL"))
        assertEquals(listOf("OVER_KCAL"), state(s).violations.map { it.code })
    }
}
