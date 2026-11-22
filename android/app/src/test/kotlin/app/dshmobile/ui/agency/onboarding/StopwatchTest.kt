package app.dshmobile.ui.agency.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchTest {
    @Test fun wholeSecondsFromStartToStop() {
        var now = 10_000L
        val w = Stopwatch { now }
        assertEquals(0, w.seconds())
        w.start()
        assertTrue(w.running)
        now += 64_900
        assertEquals(64, w.seconds())
        w.stop()
        now += 5_000
        assertFalse(w.running)
        assertEquals(64, w.seconds()) // stopped: time no longer counts
        w.start()
        assertEquals(0, w.seconds())
    }

    @Test fun theRecorderClockShowsMinutesAndStopsAtThree() {
        assertEquals("0:00", clock(0))
        assertEquals("1:05", clock(65_400))
        assertEquals("3:00", clock(999_999))
    }
}
