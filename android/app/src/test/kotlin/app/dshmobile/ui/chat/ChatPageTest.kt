package app.dshmobile.ui.chat

import app.dshmobile.chat.SessionRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Virtual time without `runTest`: runTest also fails on exceptions other test classes leaked into the JVM before it started
 * (SettingsJobsTest's intended "boom"), which made these tests depend on the test order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPageTest {
    private fun row(id: String) = SessionRow(id, id, null, null, null, 1, false)
    private val scheduler = TestCoroutineScheduler()
    private val backgroundScope = CoroutineScope(StandardTestDispatcher(scheduler))
    private fun runTest(body: suspend () -> Unit) {
        try {
            runBlocking { body() }
        } finally {
            backgroundScope.cancel()
        }
    }
    private fun advanceTimeBy(ms: Long) = scheduler.advanceTimeBy(ms)
    private fun runCurrent() = scheduler.runCurrent()

    @Test fun searchWaitsAQuarterSecondAndOnlyAsksForTheLastQuery() = runTest {
        val asked = mutableListOf<String>()
        val search = ListSearch({ q -> asked += q; listOf(row(q)) }, backgroundScope, debounceMs = 250)
        search.query("霧")
        advanceTimeBy(100)
        search.query("霧城")
        advanceTimeBy(249)
        runCurrent()
        assertEquals(emptyList<String>(), asked)
        assertNull(search.results.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("霧城"), asked)
        assertEquals("霧城" to listOf(row("霧城")), search.results.value)
    }

    @Test fun resultsForAnOlderQueryAreNeverShown() = runTest {
        val slow = CompletableDeferred<Unit>()
        val search = ListSearch({ q -> if (q == "a") slow.await(); listOf(row(q)) }, backgroundScope, debounceMs = 250)
        search.query("a")
        advanceTimeBy(300) // "a" is now being searched, slowly
        search.query("ab")
        slow.complete(Unit) // "a"'s answer arrives after "ab" was typed
        runCurrent()
        assertNull("nothing for the old query", search.results.value)
        advanceTimeBy(300)
        runCurrent()
        assertEquals("ab" to listOf(row("ab")), search.results.value)
        search.query("")
        runCurrent()
        assertNull("a blank query clears the results at once", search.results.value)
    }

    @Test fun aFailedSearchShowsNoResults() = runTest {
        val search = ListSearch({ throw java.io.IOException("down") }, backgroundScope, debounceMs = 250)
        search.query("x")
        advanceTimeBy(300)
        runCurrent()
        assertEquals("x" to emptyList<SessionRow>(), search.results.value)
    }

    @Test fun theDayTurnsAtMidnightInTaipei() {
        val taipei = ZoneId.of("Asia/Taipei")
        // 23:59:30 in Taipei is 15:59:30 UTC.
        val now = Instant.parse("2026-11-03T15:59:30Z")
        assertEquals(LocalDate.of(2026, 11, 3), now.atZone(taipei).toLocalDate())
        assertEquals(30_000L, msUntilNextMidnight(now, taipei))
        assertEquals(24 * 3_600_000L, msUntilNextMidnight(Instant.parse("2026-11-03T16:00:00Z"), taipei))
    }
}
