package app.dshmobile.update

import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.log.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class DailyUpdateCheckTest {
    private val dir: File = Files.createTempDirectory("dailyupdate").toFile()
    private val log = ErrorLog(dir)
    private val clock = MutableClock(Instant.parse("2026-11-02T01:00:00Z")) // 09:00 in Taipei
    private val prefs = InMemorySharedPreferences()
    private val info = ReleaseInfo(2, "0.2.0", "https://github.com/o/r/a.apk", "a".repeat(64))
    private var calls = 0
    private var result: () -> ReleaseInfo? = { info }
    private val announced = ArrayList<ReleaseInfo>()

    private val daily = DailyUpdateCheck(prefs, { calls++; result() }, { announced.add(it) }, log, clock)

    @After fun tearDown() { dir.deleteRecursively() }

    private fun logText() = if (log.file().exists()) log.file().readText() else ""

    @Test fun theFirstRunChecksAndAnnouncesAnUpdate() = runBlocking {
        daily.runIfDue()
        assertEquals(1, calls)
        assertEquals(listOf(info), announced)
    }

    @Test fun noUpdateAnnouncesNothing() = runBlocking {
        result = { null }
        daily.runIfDue()
        assertEquals(1, calls)
        assertTrue(announced.isEmpty())
    }

    @Test fun twoRunsOnTheSameTaipeiDayCheckOnce() = runBlocking {
        daily.runIfDue()
        clock.advance(Duration.ofHours(14)) // 23:00 Taipei, still the same day
        daily.runIfDue()
        assertEquals(1, calls)
    }

    @Test fun theDayChangesAtTaipeiMidnight() = runBlocking {
        daily.runIfDue()
        clock.now = Instant.parse("2026-11-02T15:59:00Z") // 23:59 on the 2nd in Taipei
        daily.runIfDue()
        assertEquals(1, calls)
        clock.now = Instant.parse("2026-11-02T16:01:00Z") // 00:01 on the 3rd in Taipei, still the 2nd in UTC
        daily.runIfDue()
        assertEquals(2, calls)
    }

    @Test fun utcMidnightInTheMiddleOfATaipeiDayDoesNotStartANewDay() = runBlocking {
        clock.now = Instant.parse("2026-11-02T16:30:00Z") // 00:30 on the 3rd in Taipei
        daily.runIfDue()
        clock.now = Instant.parse("2026-11-03T00:30:00Z") // 08:30 on the 3rd in Taipei, a new day in UTC
        daily.runIfDue()
        assertEquals(1, calls)
    }

    @Test fun aThrowingCheckIsLoggedWithTheClassOnlyAndRetriedNextTime() = runBlocking {
        result = { throw IOException("SECRET-URL-https://x/y?token=abc") }
        daily.runIfDue()
        assertTrue(announced.isEmpty())
        assertTrue(logText(), logText().contains("[update]") && logText().contains("IOException"))
        assertFalse(logText().contains("SECRET-URL"))
        result = { info }
        daily.runIfDue()
        assertEquals(2, calls)
        assertEquals(listOf(info), announced)
    }

    @Test fun aThrowingAnnouncementIsLoggedAndDoesNotEscape() = runBlocking {
        val bad = DailyUpdateCheck(prefs, { info }, { throw SecurityException("no") }, log, clock)
        bad.runIfDue()
        assertTrue(logText().contains("SecurityException"))
    }

    @Test fun cancellationIsNotSwallowedAndDoesNotCountAsChecked() {
        result = { throw CancellationException("stop") }
        try {
            runBlocking { daily.runIfDue() }
            assertTrue("must rethrow", false)
        } catch (_: CancellationException) {
        }
        result = { info }
        runBlocking { daily.runIfDue() }
        assertEquals(2, calls)
    }

    @Test fun theCheckedDaySurvivesANewInstance() = runBlocking {
        daily.runIfDue()
        val again = DailyUpdateCheck(prefs, { calls++; info }, { announced.add(it) }, log, clock)
        again.runIfDue()
        assertEquals(1, calls)
    }
}
