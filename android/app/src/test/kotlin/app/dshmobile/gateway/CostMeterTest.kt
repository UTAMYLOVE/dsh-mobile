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

    @Test fun perProviderTodayAddsUpToTheTotal() {
        val m = meter(MutableClock(at("2026-11-02T10:00:00")))
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        m.record(Provider.DEEPSEEK, "deepseek-v4-flash", Usage(inputTokens = 1_000_000, outputTokens = 0))
        val ds = m.todayUsd(Provider.DEEPSEEK)
        val cl = m.todayUsd(Provider.ANTHROPIC)
        assertEquals(2.0, cl, 1e-9)
        assertTrue(ds > 0.0)
        assertEquals(m.todayUsd(), ds + cl, 1e-9)
    }

    @Test fun monthRollsOverAtTaipeiMidnightOnTheFirst() {
        val clock = MutableClock(Instant.parse("2026-10-31T15:59:00Z")) // Taipei 10/31 23:59
        val m = meter(clock)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertEquals(2.0, m.monthUsd(), 1e-9)
        clock.now = Instant.parse("2026-10-31T16:00:00Z") // Taipei 11/01 00:00
        assertEquals(0.0, m.monthUsd(), 1e-12)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        clock.now = at("2026-11-20T10:00:00")
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        assertEquals(4.0, m.monthUsd(), 1e-9)
    }

    @Test fun revisionBumpsOnRecordAndOnSettings() {
        val m = meter(MutableClock(at("2026-11-02T10:00:00")))
        val r0 = m.revision.value
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", twoDollars)
        val r1 = m.revision.value
        assertTrue(r1 > r0)
        m.alertUsd = 9.0
        val r2 = m.revision.value
        assertTrue(r2 > r1)
        m.hardCapUsd = 30.0
        assertTrue(m.revision.value > r2)
    }

    @Test fun oldStoresWithoutTheNewKeysStillReportTheTotal() {
        val store = InMemorySharedPreferences()
        store.edit().putString("day", "2026-11-02").putString("day_usd", "3.5").apply()
        val m = meter(MutableClock(at("2026-11-02T10:00:00")), store)
        assertEquals(3.5, m.todayUsd(), 1e-12)
        assertEquals(0.0, m.todayUsd(Provider.DEEPSEEK), 0.0)
        assertEquals(0.0, m.todayUsd(Provider.ANTHROPIC), 0.0)
        assertEquals(0.0, m.monthUsd(), 0.0)
    }

    @Test fun todayIsSplitPerModelAndANewDayStartsEmpty() {
        val clock = MutableClock(at("2026-11-02T10:00:00"))
        val m = meter(clock)
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(inputTokens = 100, outputTokens = 10, cacheReadTokens = 300))
        m.record(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(inputTokens = 100, outputTokens = 10))
        m.record(Provider.DEEPSEEK, "deepseek-v4-flash", Usage(inputTokens = 5, outputTokens = 5))
        val all = m.todayByModel()
        org.junit.Assert.assertEquals(listOf("claude-sonnet-5-5", "deepseek-v4-flash"), all.map { it.model })
        val sonnet = all.first()
        org.junit.Assert.assertEquals(2L, sonnet.requests)
        org.junit.Assert.assertEquals(200L, sonnet.inputTokens)
        org.junit.Assert.assertEquals(0.6, sonnet.cacheHit!!, 0.001)
        clock.now = at("2026-11-03T10:00:00")
        org.junit.Assert.assertTrue(m.todayByModel().isEmpty())
        m.record(Provider.DEEPSEEK, "deepseek-v4-flash", Usage(inputTokens = 5, outputTokens = 5))
        org.junit.Assert.assertEquals(1L, m.todayByModel().single().requests)
    }
}
