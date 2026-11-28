package app.agency.core.extras

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.store.CheckIn
import app.agency.core.store.CheckOut
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.add
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotChecksTest {
    private val week = TestClock.MONDAY
    private val stage = Kit.stage(4)

    private class Env(salt: Long) {
        val store = MemStore()
        val clock = TestClock()
        val checks = SpotChecks(store, clock, Kit.rules)

        init {
            store.putDoc(SpotChecks.SALT_DOC, salt.toString())
        }
    }

    private fun minutes(t: Instant, clock: TestClock): Int = t.atZone(clock.zone).toLocalTime().let { it.hour * 60 + it.minute }

    /** Plays one day in five-minute steps (check-in at wake, optional ride, check-out) and returns when checks were asked. */
    private fun play(env: Env, day: LocalDate, ride: Boolean, rideOut: String = "19:30", back: String = "22:00"): List<Instant> {
        val classDay = day.dayOfWeek.value in Kit.rules.classWeekdays
        val wake = if (classDay) "07:00" else "10:00"
        val asked = ArrayList<Instant>()
        var t = Days.at(day, LocalTime.of(6, 0), env.clock.zone)
        val end = t.plus(Duration.ofHours(24))
        fun at(hhmm: String) = Days.at(day, LocalTime.parse(hhmm), env.clock.zone)
        while (t < end) {
            env.clock.instant = t
            if (t == at(wake)) env.store.add(day, t, Kind.CHECK_IN, CheckIn(4))
            if (ride && t == at(rideOut)) env.store.add(day, t, Kind.RIDE, Ride(RidePhase.OUT))
            if (ride && t == at(back)) env.store.add(day, t, Kind.RIDE, Ride(RidePhase.BACK))
            if (t == at("23:30")) env.store.add(day, t, Kind.CHECK_OUT, CheckOut())
            val state = Kit.stateOf(env.store, day, stage, env.clock)
            if (env.checks.issue(t, state) != null) asked += t
            t = t.plus(Duration.ofMinutes(5))
        }
        return asked
    }

    @Test fun twoOrThreePerWeekOnDifferentDaysInsideTheStaticWindows() {
        for (salt in 1L..300L) {
            val env = Env(salt)
            for (w in 0L..3L) {
                val plan = env.checks.plan(week.plusWeeks(w))
                assertTrue("$salt $w ${plan.size}", plan.size in 2..3)
                assertEquals(plan.size, plan.map { it.day }.toSet().size)
                plan.forEach { s ->
                    assertTrue(s.day >= week.plusWeeks(w) && s.day < week.plusWeeks(w + 1))
                    val m = s.at.hour * 60 + s.at.minute
                    if (s.day.dayOfWeek.value in Kit.rules.classWeekdays) {
                        assertTrue("class $s", m >= 16 * 60 && m < 19 * 60 + 30)
                    } else {
                        assertTrue("free $s", m >= 10 * 60 + 30 && m < 19 * 60 + 30)
                    }
                }
                // Deterministic: the same seed gives the same plan.
                assertEquals(plan, env.checks.plan(week.plusWeeks(w)))
            }
        }
    }

    @Test fun differentSaltsGiveDifferentPlans() {
        val plans = (1L..40L).map { Env(it).checks.plan(week) }.toSet()
        assertTrue(plans.size > 10)
        assertNotEquals(Env(1).checks.plan(week), Env(2).checks.plan(week))
    }

    @Test fun theSaltIsKeptOnceInADoc() {
        val store = MemStore()
        val clock = TestClock()
        var n = 100L
        val c = SpotChecks(store, clock, Kit.rules, newSalt = { n++ })
        assertEquals(100L, c.salt())
        assertEquals(100L, c.salt())
        assertEquals("100", store.doc(SpotChecks.SALT_DOC))
    }

    @Test fun simulatedWeeksAskOnlyInsideAllowedWindowsAndNeverWhileRiding() {
        for (salt in 1L..40L) {
            val env = Env(salt)
            val planned = env.checks.plan(week)
            var total = 0
            for (d in 0L..6L) {
                val day = week.plusDays(d)
                val classDay = day.dayOfWeek.value in Kit.rules.classWeekdays
                val ride = d % 2 == 0L
                val asked = play(env, day, ride)
                assertTrue(asked.size <= 1)
                total += asked.size
                asked.forEach { t ->
                    val m = minutes(t, env.clock)
                    val m2 = if (m < 6 * 60) m + 24 * 60 else m
                    val riding = ride && m2 >= 19 * 60 + 30 && m2 < 22 * 60
                    assertFalse("riding $salt $day $m", riding)
                    val beforeLeave = m2 < 19 * 60 + 30
                    val afterBack = ride && m2 >= 22 * 60
                    if (classDay) assertTrue("class $salt $day $m", (m2 >= 16 * 60 && beforeLeave) || afterBack)
                    else assertTrue("free $salt $day $m", (m2 >= 10 * 60 && beforeLeave) || afterBack)
                    assertTrue("before check-out", m2 < 23 * 60 + 30)
                    // Never before the planned moment.
                    assertTrue(t >= Days.at(day, planned.first { it.day == day }.at, env.clock.zone))
                }
            }
            assertEquals(planned.size, total)
            assertTrue(total in 2..3)
            // Asked again on the same day: never twice.
            assertEquals(0, play(env, week.plusDays(planned.first().day.dayOfWeek.value - 1L), ride = false).size)
        }
    }

    @Test fun aCheckWaitsForHisReturnAndNeverFiresWhileRiding() {
        val env = Env(7)
        val slot = env.checks.plan(week).first { it.day.dayOfWeek.value in Kit.rules.classWeekdays }
        // He leaves at 15:55 (before the slot), is back at 21:00: the check comes at the first step after he is back.
        val asked = play(env, slot.day, ride = true, rideOut = "15:55", back = "21:00")
        assertEquals(1, asked.size)
        assertEquals(Days.at(slot.day, LocalTime.of(21, 0), env.clock.zone), asked.single())
        // And one who never checks in is never asked.
        val env2 = Env(7)
        env2.clock.instant = Days.at(slot.day, LocalTime.of(18, 0), env2.clock.zone)
        val state = Kit.stateOf(env2.store, slot.day, stage, env2.clock)
        assertNull(env2.checks.due(env2.clock.instant, state))
        assertFalse(env2.checks.allowed(env2.clock.instant, state))
    }

    private fun askedEnv(): Triple<Env, SpotCheckRec, Instant> {
        val env = Env(7)
        val slot = env.checks.plan(week).first { it.day.dayOfWeek.value in Kit.rules.classWeekdays }
        val day = slot.day
        env.store.add(day, Days.at(day, LocalTime.of(7, 0), env.clock.zone), Kind.CHECK_IN, CheckIn(4))
        val at = Days.at(day, slot.at, env.clock.zone)
        env.clock.instant = at
        val state = Kit.stateOf(env.store, day, stage, env.clock)
        val due = env.checks.due(at, state)!!
        assertEquals(slot.ask, due.ask)
        val notice = env.checks.issue(at, state)!!
        assertTrue(notice.loud)
        assertEquals(due.key, notice.key)
        assertTrue(notice.text.contains(slot.ask.zh))
        assertTrue(notice.text.contains("十分鐘"))
        assertTrue((notice.title + notice.text).none { it in 'A'..'Z' || it in 'a'..'z' })
        // Issued once only.
        assertNull(env.checks.issue(at, state))
        assertNull(env.checks.due(at, Kit.stateOf(env.store, day, stage, env.clock)))
        return Triple(env, env.checks.todays(day).single().second, at)
    }

    @Test fun answeredInTimeIsDone() {
        val (env, rec, at) = askedEnv()
        assertEquals(SpotStatus.ASKED, rec.status)
        env.clock.instant = at.plus(Duration.ofMinutes(9))
        val done = env.checks.answer("vault-photo")!!
        assertEquals(SpotStatus.DONE, done.status)
        assertEquals("vault-photo", done.photoId)
        assertEquals(SpotStatus.DONE, env.checks.todays(Days.of(at, env.clock.zone)).single().second.status)
        assertNull(env.checks.answer("vault-photo-2"))
    }

    @Test fun answeredTooLateOrNeverIsMissedAndNotAViolation() {
        val (env, _, at) = askedEnv()
        env.clock.instant = at.plus(Duration.ofMinutes(11))
        val late = env.checks.answer("vault-photo")!!
        assertEquals(SpotStatus.MISSED, late.status)
        assertNull(late.photoId)
        val day = Days.of(at, env.clock.zone)
        assertTrue(env.store.entries(day, Kind.VIOLATION).isEmpty())

        val (env2, _, at2) = askedEnv()
        env2.clock.instant = at2.plus(Duration.ofMinutes(30))
        env2.checks.expire(env2.clock.instant)
        assertEquals(SpotStatus.MISSED, env2.checks.todays(Days.of(at2, env2.clock.zone)).single().second.status)
        assertTrue(env2.store.entries(Days.of(at2, env2.clock.zone), Kind.VIOLATION).isEmpty())
        assertNotNull(env2.checks.todays(Days.of(at2, env2.clock.zone)).single().first)
    }

    @Test fun noCheckToAnswerGivesNull() {
        assertNull(Env(1).checks.answer("x"))
    }
}
