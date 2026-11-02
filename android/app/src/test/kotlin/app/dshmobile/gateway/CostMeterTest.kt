package app.dshmobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class CostMeterTest {
    private val taipei = ZoneId.of("Asia/Taipei")
    private val prices = PriceTable()

    // Sonnet 5.5: 1M input tokens cost exactly $2.00.
    private val twoDollars = Usage(inputTokens = 1_000_000, outputTokens = 0)

    private fun at(taipeiLocal: String): Instant = LocalDateTime.parse(taipeiLocal).atZone(taipei).toInstant()

    private fun meter(clock: Clock, store: InMemorySharedPreferences = InMemorySharedPreferences()) =
        CostMeter(store, prices, taipei, clock)

    @Test fun dayRollsOverAtTaipeiMidnight() {
        val clock = MutableClock(at("2026-11-02T23:59:00"))
        val m = meter(clock)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertEquals(2.0, m.todayUsd(), 1e-9)
        clock.now = at("2026-11-03T00:01:00")
        assertEquals(0.0, m.todayUsd(), 1e-12)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertEquals(2.0, m.todayUsd(), 1e-9)
    }

    @Test fun recordsAccumulateWithinTheDay() {
        val m = meter(MutableClock(at("2026-11-02T10:00:00")))
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertEquals(4.0, m.todayUsd(), 1e-9)
    }

    @Test fun totalsSurviveANewMeterOnTheSameStore() {
        val store = InMemorySharedPreferences()
        val clock = MutableClock(at("2026-11-02T10:00:00"))
        meter(clock, store).record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertEquals(2.0, meter(clock, store).todayUsd(), 1e-9)
    }

    @Test fun alertFiresOncePerDayAboveThreshold() {
        val clock = MutableClock(at("2026-11-02T10:00:00"))
        val m = meter(clock)
        assertEquals(5.0, m.alertUsd, 0.0)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertFalse(m.consumeAlert())
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars) // $6 > $5
        assertTrue(m.consumeAlert())
        assertFalse(m.consumeAlert())
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertFalse(m.consumeAlert())

        clock.now = at("2026-11-03T10:00:00")
        assertFalse(m.consumeAlert())
        repeat(3) { m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars) }
        assertTrue(m.consumeAlert())
    }

    @Test fun alertNeedsStrictlyMoreThanThreshold() {
        val m = meter(MutableClock(at("2026-11-02T10:00:00")))
        m.alertUsd = 2.0
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertFalse(m.consumeAlert())
    }

    @Test fun neverBlockedWithoutHardCap() {
        val m = meter(MutableClock(at("2026-11-02T10:00:00")))
        assertNull(m.hardCapUsd)
        repeat(100) { m.record(Provider.ANTHROPIC, "claude-fable-5-1", twoDollars) }
        assertFalse(m.isBlocked())
    }

    @Test fun blockedOnceHardCapReachedAndUnblockedNextDay() {
        val clock = MutableClock(at("2026-11-02T10:00:00"))
        val m = meter(clock)
        m.hardCapUsd = 4.0
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertFalse(m.isBlocked())
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertTrue(m.isBlocked())
        clock.now = at("2026-11-03T10:00:00")
        assertFalse(m.isBlocked())
    }

    @Test fun settingsArePersistedInTheSameStore() {
        val store = InMemorySharedPreferences()
        val clock = MutableClock(at("2026-11-02T10:00:00"))
        meter(clock, store).apply { alertUsd = 7.5; hardCapUsd = 20.0 }
        val again = meter(clock, store)
        assertEquals(7.5, again.alertUsd, 0.0)
        assertEquals(20.0, again.hardCapUsd!!, 0.0)
        again.hardCapUsd = null
        assertNull(meter(clock, store).hardCapUsd)
    }

    @Test fun concurrentRecordsAreNotLost() {
        val m = meter(MutableClock(at("2026-11-02T10:00:00")))
        val threads = List(8) { Thread { repeat(250) { m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(1_000_000, 0)) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(2.0 * 8 * 250, m.todayUsd(), 1e-6)
    }
}
