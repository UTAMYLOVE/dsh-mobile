package app.dshmobile.overview

import app.dshmobile.chat.SessionRow
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.gateway.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.ZoneId

class OverviewSourceTest {
    private val clock = MutableClock()
    private val meter = CostMeter(InMemorySharedPreferences(), PriceTable(), ZoneId.of("Asia/Taipei"), clock)
    private val reminders = ReminderLog(InMemorySharedPreferences(), clock)
    private val row = SessionRow("s1", "天氣", "晴", "m", null, 1L, false)

    private fun source(
        next: Flow<Long?> = flowOf(null),
        running: Flow<Int?> = flowOf(null),
        recent: Flow<List<SessionRow>> = flowOf(emptyList()),
        interval: Int = 45,
        unverified: String? = null,
    ) = OverviewSource(meter, reminders, { 6 }, { interval }, next, running, recent, { unverified }, clock)

    private fun state(s: OverviewSource) = runBlocking { s.state().first() }

    @Test fun combinesCostAgentsHeartbeatRemindersAndRecent() {
        meter.record(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(inputTokens = 1_000_000, outputTokens = 0))
        reminders.add("提醒", "內文")
        val next = clock.millis() + 15 * 60_000
        val s = state(source(next = flowOf(next), running = flowOf(2), recent = flowOf(listOf(row)), unverified = "0.3.0"))
        assertEquals(2.0, s.claudeUsd, 1e-9)
        assertEquals(0.0, s.deepseekUsd, 0.0)
        assertEquals(2.0, s.monthUsd, 1e-9)
        assertEquals(5.0, s.alertUsd, 0.0)
        assertEquals(2, s.agentsRunning)
        assertEquals(6, s.agentsLimit)
        assertEquals(next, s.nextHeartbeatAtMs)
        assertEquals(45, s.heartbeatIntervalMin)
        assertEquals(clock.millis(), s.nowMs)
        assertEquals(listOf("提醒"), s.reminders.map { it.title })
        assertEquals(listOf(row), s.recent)
        assertEquals("0.3.0", s.unverifiedDsh)
    }

    @Test fun withoutDshAgentsAreUnknownAndRecentIsEmpty() {
        val s = state(source())
        assertNull(s.agentsRunning)
        assertEquals(emptyList<SessionRow>(), s.recent)
        assertNull(s.nextHeartbeatMin)
        assertNull(s.heartbeatProgress)
        assertNull(s.unverifiedDsh)
    }

    @Test fun nextHeartbeatIsWholeMinutesRoundedUp() {
        fun min(deltaMs: Long) = state(source(next = flowOf(clock.millis() + deltaMs))).nextHeartbeatMin
        assertEquals(15, min(15 * 60_000))
        assertEquals(15, min(14 * 60_000 + 1))
        assertEquals(1, min(1))
        assertEquals(0, min(0))
        assertEquals(0, min(-5 * 60_000)) // overdue: never negative
    }

    @Test fun heartbeatProgressIsElapsedOverTheInterval() {
        val s = state(source(next = flowOf(clock.millis() + 15 * 60_000), interval = 45))
        assertEquals(0.6667f, s.heartbeatProgress!!, 0.001f)
        assertEquals(1f, state(source(next = flowOf(clock.millis() - 1), interval = 45)).heartbeatProgress!!, 0f)
        assertEquals(0f, state(source(next = flowOf(clock.millis() + 90 * 60_000), interval = 45)).heartbeatProgress!!, 0f)
        assertNull(state(source(next = flowOf(null))).heartbeatProgress)
    }

    @Test fun aNewRecordAndTheClockTickUpdateTheState() {
        val running = MutableStateFlow<Int?>(0)
        val src = OverviewSource(meter, reminders, { 6 }, { 45 }, flowOf(null), running, flowOf(emptyList()), { null }, clock, tickMs = 20)
        runBlocking {
            val first = src.state().first()
            clock.advance(Duration.ofMinutes(3))
            meter.record(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(inputTokens = 1_000_000, outputTokens = 0))
            val later = src.state().first { it.claudeUsd > 0.0 && it.nowMs > first.nowMs }
            assertEquals(2.0, later.claudeUsd, 1e-9)
        }
    }
}
