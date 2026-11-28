package app.agency.core.week

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.rules.Fixtures
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.add
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomeTest {
    private val rules = Fixtures.rules() // weekly target 4500
    private val monday: LocalDate = TestClock.MONDAY
    private val wednesday = monday.plusDays(2)

    private fun ride(s: MemStore, d: LocalDate, phase: RidePhase, income: Int? = null, minute: Int = 0) =
        s.add(d, Days.at(d, LocalTime.of(20, minute), TestClock.ZONE), Kind.RIDE, Ride(phase, income))

    private fun twoDays(s: MemStore) {
        ride(s, monday, RidePhase.OUT); ride(s, monday, RidePhase.BACK, 1000, 5)
        ride(s, monday.plusDays(1), RidePhase.OUT); ride(s, monday.plusDays(1), RidePhase.BACK, 1500, 5)
    }

    @Test fun weekTotalLeftAndPerRemainingDayBeforeTodaysRide() {
        val s = MemStore()
        twoDays(s)
        val w = Income.week(s, wednesday, rules)
        assertEquals(monday, w.weekStart)
        assertEquals(2500, w.income)
        assertEquals(2, w.daysRidden)
        assertEquals(4500, w.target)
        assertEquals(2000, w.left)
        assertEquals(5, w.remainingDays) // today still to ride, then Thursday to Sunday
        assertEquals(400, w.perRemainingDay)
        assertFalse(w.reached)
    }

    @Test fun todaysRideBackMovesTodayOutOfTheRemainingDays() {
        val s = MemStore()
        twoDays(s)
        ride(s, wednesday, RidePhase.OUT); ride(s, wednesday, RidePhase.BACK, 500, 5)
        val w = Income.week(s, wednesday, rules)
        assertEquals(3000, w.income)
        assertEquals(3, w.daysRidden)
        assertEquals(1500, w.left)
        assertEquals(4, w.remainingDays)
        assertEquals(375, w.perRemainingDay)
    }

    @Test fun lastWeekIsNotCounted() {
        val s = MemStore()
        ride(s, monday.minusDays(1), RidePhase.BACK, 5000)
        assertEquals(0, Income.week(s, wednesday, rules).income)
    }

    @Test fun reachingTheTargetLeavesNothingAndSundayHasNoDaysLeft() {
        val s = MemStore()
        ride(s, monday, RidePhase.OUT); ride(s, monday, RidePhase.BACK, 4600, 5)
        val w = Income.week(s, wednesday, rules)
        assertTrue(w.reached)
        assertEquals(0, w.left)
        assertEquals(0, w.perRemainingDay)
        val sunday = monday.plusDays(6)
        val last = MemStore()
        ride(last, monday, RidePhase.OUT); ride(last, monday, RidePhase.BACK, 3400, 5)
        ride(last, sunday, RidePhase.OUT); ride(last, sunday, RidePhase.BACK, 200, 5)
        val s2 = Income.week(last, sunday, rules)
        assertEquals(0, s2.remainingDays)
        assertEquals(900, s2.left)
        assertEquals(900, s2.perRemainingDay)
    }

    @Test fun incomeIsJudgedOnTheWeekTotalNotPerDay() {
        val s = MemStore()
        ride(s, monday, RidePhase.OUT); ride(s, monday, RidePhase.BACK, 100, 5)
        val w = Income.week(s, monday.plusDays(3), rules)
        assertEquals(4400, w.left)
        assertEquals(4, w.remainingDays)
        assertEquals(1100, w.perRemainingDay)
    }
}
