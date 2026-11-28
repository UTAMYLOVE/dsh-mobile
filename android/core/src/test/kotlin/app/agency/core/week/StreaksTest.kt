package app.agency.core.week

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.store.CheckOut
import app.agency.core.store.DayClose
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Mood
import app.agency.core.store.add
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreaksTest {
    private val start: LocalDate = TestClock.MONDAY

    private fun d(n: Int) = start.plusDays(n.toLong())
    private fun at(day: LocalDate) = Days.at(day, LocalTime.of(23, 0), TestClock.ZONE)

    private fun close(s: MemStore, day: LocalDate, perfect: Boolean = true, missed: Map<String, String> = emptyMap()) =
        s.add(day, at(day), Kind.DAY_CLOSE, DayClose(if (perfect) 4 else 2, 4, if (perfect) 0 else 1, perfect, missed))

    @Test fun perfectDaysCountBackFromTheLastClosedDay() {
        val s = MemStore()
        (0..4).forEach { close(s, d(it)) }
        close(s, d(5), perfect = false)
        (6..8).forEach { close(s, d(it)) }
        assertEquals(3, Streaks.perfect(s, d(8)))
        assertEquals(0, Streaks.perfect(s, d(5)))
        assertEquals(5, Streaks.perfect(s, d(4)))
        assertEquals(0, Streaks.perfect(s, d(9)))
    }

    @Test fun lowMoodDaysUseMoodAndCheckOutScores() {
        val s = MemStore()
        s.add(d(0), at(d(0)), Kind.MOOD, Mood(2))
        s.add(d(1), at(d(1)), Kind.CHECK_OUT, CheckOut("", "", mood = 1))
        s.add(d(2), at(d(2)), Kind.MOOD, Mood(1))
        assertEquals(3, Streaks.lowMood(s, d(2)))
        assertTrue(Streaks.report(s, d(2)).lowMoodTalk)
        // today has no mood yet: yesterday's run still counts, it is not a break
        assertEquals(3, Streaks.lowMood(s, d(3)))
    }

    @Test fun aBetterDayOrAMissingRecordBreaksTheMoodRun() {
        val s = MemStore()
        s.add(d(0), at(d(0)), Kind.MOOD, Mood(1))
        s.add(d(1), at(d(1)), Kind.MOOD, Mood(4))
        s.add(d(2), at(d(2)), Kind.MOOD, Mood(2))
        s.add(d(3), at(d(3)), Kind.MOOD, Mood(2))
        assertEquals(2, Streaks.lowMood(s, d(3)))
        assertFalse(Streaks.report(s, d(3)).lowMoodTalk)
        assertEquals(0, Streaks.lowMood(s, d(5)))
    }

    @Test fun anItemMissedThreeClosedDaysInARowIsNamed() {
        val s = MemStore()
        close(s, d(0), false, mapOf("task:CORE" to "核心"))
        close(s, d(1), false, mapOf("task:CORE" to "核心", "water" to "喝水"))
        close(s, d(2), false, mapOf("task:CORE" to "核心", "water" to "喝水"))
        close(s, d(3), false, mapOf("task:CORE" to "核心", "water" to "喝水"))
        val runs = Streaks.missed(s, d(3))
        assertEquals(listOf(MissedRun("task:CORE", "核心", 4), MissedRun("water", "喝水", 3)), runs)
        assertEquals(runs, Streaks.report(s, d(3)).missed)
    }

    @Test fun twoDaysAreNotEnoughAndADoneDayResetsTheRun() {
        val s = MemStore()
        close(s, d(0), false, mapOf("water" to "喝水"))
        close(s, d(1), false, mapOf("water" to "喝水"))
        assertTrue(Streaks.missed(s, d(1)).isEmpty())
        close(s, d(2), false, mapOf("task:CORE" to "核心"))
        close(s, d(3), false, mapOf("water" to "喝水"))
        close(s, d(4), false, mapOf("water" to "喝水"))
        assertTrue(Streaks.missed(s, d(4)).isEmpty())
    }
}
