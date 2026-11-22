package app.agency.core.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class AgencyClockTest {
    @Test fun aClockReadsTheGivenTimeInTaipeiByDefault() {
        val at = Instant.parse("2026-11-22T04:30:00Z")
        val clock = AgencyClock.of(Clock.fixed(at, ZoneOffset.UTC))
        assertEquals(at, clock.now())
        assertEquals(at.toEpochMilli(), clock.millis())
        assertEquals(ZoneId.of("Asia/Taipei"), clock.zone)
        assertEquals(12, clock.now().atZone(clock.zone).hour)
    }

    @Test fun theZoneCanBeChosen() {
        val clock = AgencyClock.of(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), ZoneOffset.UTC)
        assertEquals(ZoneOffset.UTC, clock.zone)
    }
}
