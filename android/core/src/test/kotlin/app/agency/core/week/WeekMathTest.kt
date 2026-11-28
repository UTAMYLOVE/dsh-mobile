package app.agency.core.week

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.plan.TaskId
import app.agency.core.rules.Fixtures
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.TaskDone
import app.agency.core.store.add
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekMathTest {
    private val rules = Fixtures.rules()
    private val monday: LocalDate = TestClock.MONDAY
    private val wednesday = monday.plusDays(2)
    private val zone = TestClock.ZONE

    private fun at(d: LocalDate) = Days.at(d, LocalTime.of(20, 0), zone)

    private fun task(s: MemStore, d: LocalDate, id: TaskId, note: String = "") =
        s.add(d, at(d), Kind.TASK, TaskDone(id.name, note = note))

    @Test fun aWeekBeginsEmptyOnMonday() {
        val w = WeekMath.soFar(MemStore(), monday, rules)
        assertEquals(monday, w.weekStart)
        assertEquals(0, w.cardioDone)
        assertFalse(w.restDayUsed)
        assertEquals(0, w.rideIncome)
    }

    @Test fun countsThisCalendarWeekUpToYesterdayOnly() {
        val s = MemStore()
        task(s, monday.minusDays(1), TaskId.CARDIO)
        task(s, monday, TaskId.CARDIO)
        task(s, monday.plusDays(1), TaskId.CARDIO)
        task(s, monday.plusDays(1), TaskId.CARDIO)
        task(s, monday.plusDays(1), TaskId.EXPRESSION)
        task(s, wednesday, TaskId.CARDIO)
        val w = WeekMath.soFar(s, wednesday, rules)
        assertEquals(2, w.cardioDone)
        assertEquals(1, w.expressionDone)
    }

    @Test fun restDayAndRideAggregates() {
        val s = MemStore()
        s.add(monday.plusDays(1), at(monday), Kind.STATUS, Status(StatusType.REST_DAY))
        for ((d, income) in listOf(monday to 1000, monday.plusDays(1) to 1500)) {
            s.add(d, at(d), Kind.RIDE, Ride(RidePhase.OUT))
            s.add(d, at(d).plusSeconds(60), Kind.RIDE, Ride(RidePhase.BACK, income))
        }
        s.add(monday.minusDays(2), at(monday), Kind.RIDE, Ride(RidePhase.BACK, 9999))
        s.add(wednesday, at(wednesday), Kind.RIDE, Ride(RidePhase.BACK, 777))
        val w = WeekMath.soFar(s, wednesday, rules)
        assertTrue(w.restDayUsed)
        assertEquals(2500, w.rideIncome)
        assertEquals(2, w.rideDays)
    }

    @Test fun restDayOfTodayIsNotYetUsedUpToYesterday() {
        val s = MemStore()
        s.add(wednesday, at(wednesday), Kind.STATUS, Status(StatusType.REST_DAY))
        assertFalse(WeekMath.soFar(s, wednesday, rules).restDayUsed)
        assertTrue(WeekMath.soFar(s, wednesday.plusDays(1), rules).restDayUsed)
    }

    @Test fun groomedKeepsTheLastDoneDayPerItemAcrossWeeks() {
        val s = MemStore()
        val sunday = monday.minusDays(1)
        task(s, sunday, TaskId.GROOMING, "指甲")
        task(s, monday, TaskId.GROOMING, "指甲、修眉")
        task(s, monday.minusDays(5), TaskId.GROOMING, "修眉")
        task(s, monday.plusDays(1), TaskId.CORE, "指甲")
        val w = WeekMath.soFar(s, wednesday, rules)
        assertEquals(monday, w.groomed["指甲"])
        assertEquals(monday, w.groomed["修眉"])
        assertEquals(setOf("指甲", "修眉"), w.groomed.keys)
    }

    @Test fun groomedIsEmptyWithoutGroomingRules() {
        val s = MemStore()
        task(s, monday, TaskId.GROOMING, "指甲")
        assertTrue(WeekMath.soFar(s, wednesday, rules.copy(grooming = emptyList())).groomed.isEmpty())
    }
}
