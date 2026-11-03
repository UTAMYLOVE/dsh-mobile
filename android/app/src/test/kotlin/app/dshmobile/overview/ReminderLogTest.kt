package app.dshmobile.overview

import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class ReminderLogTest {
    private val clock = MutableClock()
    private val store = InMemorySharedPreferences()

    @Test fun keepsTheNewestTen() {
        val log = ReminderLog(store, clock)
        repeat(13) { log.add("t$it", "b$it"); clock.advance(Duration.ofMinutes(1)) }
        val all = log.recent(100)
        assertEquals(10, all.size)
        assertEquals("t12", all.first().title)
        assertEquals("t3", all.last().title)
        assertEquals(10, ReminderLog(store, clock).recent(100).size) // persisted
    }

    @Test fun recentIsNewestFirst() {
        val log = ReminderLog(store, clock)
        val r0 = log.revision.value
        log.add("舊", "a")
        clock.advance(Duration.ofMinutes(5))
        log.add("新", "b\n多行，含 \"引號\" 與 | 分隔")
        assertTrue(log.revision.value > r0)
        val r = log.recent()
        assertEquals(listOf("新", "舊"), r.map { it.title })
        assertEquals("b\n多行，含 \"引號\" 與 | 分隔", r.first().body)
        assertEquals(clock.millis(), r.first().at)
        assertEquals(1, log.recent(1).size)
    }
}
