package app.dshmobile.service

import app.dshmobile.remote.HostEvent
import app.dshmobile.remote.MuxState
import app.dshmobile.service.FakeWorkRemote.Companion.row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTrackerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("not met in time: $what", cond())
    }

    @Test fun aRunningStatusMakesItBusyAndRetainsWork() {
        val remote = FakeWorkRemote()
        val tracker = WorkTracker(remote, scope)
        waitUntil("first baseline applied") { tracker.synced.value }
        assertFalse(tracker.busy.value)

        remote.emit(HostEvent.SessionAdded(row("a", true, title = "寫小說")))
        waitUntil("busy") { tracker.busy.value }
        assertEquals(listOf(RunningWork("a", null, "寫小說")), tracker.running.value)
    }

    @Test fun allSessionsIdleReleasesWorkOnce() {
        val remote = FakeWorkRemote(listOf(row("a", false), row("b", false)))
        val tracker = WorkTracker(remote, scope)
        val busyChanges = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        scope.launch { tracker.busy.collect { busyChanges.add(it) } }
        waitUntil("first baseline applied") { tracker.synced.value }
        waitUntil("collector") { busyChanges.isNotEmpty() }

        remote.status("a", true)
        remote.status("b", true)
        waitUntil("two running") { tracker.running.value.size == 2 }
        // busy is a StateFlow: a collector that has not seen the rise yet would miss it if both went idle at once.
        waitUntil("rise seen") { true in busyChanges }
        remote.status("a", false)
        remote.status("b", false)
        waitUntil("idle") { !tracker.busy.value }
        Thread.sleep(50)
        assertEquals("false, then one rise and one fall", listOf(false, true, false), busyChanges.toList())
    }

    @Test fun aSubagentRunningKeepsItBusyAfterTheLeadIsIdle() {
        val remote = FakeWorkRemote(listOf(row("lead", false, title = "主"), row("kid", false, parent = "lead")))
        val tracker = WorkTracker(remote, scope)
        waitUntil("first baseline applied") { tracker.synced.value }
        remote.status("lead", true)
        remote.status("kid", true)
        waitUntil("both") { tracker.running.value.size == 2 }
        remote.status("lead", false)
        waitUntil("lead gone") { tracker.running.value.map { it.sessionId } == listOf("kid") }
        assertTrue(tracker.busy.value)
        assertEquals("lead", tracker.running.value.single().parentId)
        remote.status("kid", false)
        waitUntil("idle") { !tracker.busy.value }
    }

    @Test fun reconnectRebuildsBusyFromTheListBaseline() {
        val remote = FakeWorkRemote(listOf(row("a", true, title = "A")))
        val tracker = WorkTracker(remote, scope)
        waitUntil("busy from the first baseline") { tracker.busy.value }

        // While disconnected: a finished and a new one started, both unseen as events.
        remote.setState(MuxState.RECONNECTING)
        Thread.sleep(100) // a StateFlow conflates: let the tracker see the gap
        remote.sessions = listOf(row("a", false, title = "A"), row("b", true, title = "B"))
        remote.setState(MuxState.OPEN)
        waitUntil("rebuilt") { tracker.running.value.map { it.sessionId } == listOf("b") }

        remote.setState(MuxState.RECONNECTING)
        Thread.sleep(100) // a StateFlow conflates: let the tracker see the gap
        remote.sessions = listOf(row("b", false))
        remote.setState(MuxState.OPEN)
        waitUntil("idle after reconnect") { !tracker.busy.value }
    }

    @Test fun stopAllCancelsTopLevelAndInterruptsChildren() {
        val remote = FakeWorkRemote(listOf(row("a", true), row("b", true), row("k1", true, parent = "a"), row("k2", true, parent = "b")))
        val tracker = WorkTracker(remote, scope)
        waitUntil("four running") { tracker.running.value.size == 4 }

        runBlocking { tracker.stopAll() }
        assertEquals(setOf("a", "b"), remote.cancelled.toSet())
        assertEquals(setOf("k1" to "a", "k2" to "b"), remote.interrupted.toSet())
    }

    @Test fun anIdleEventLostWhileEventsReopenedClearsBusyOnTheNextBaseline() {
        val remote = FakeWorkRemote(listOf(row("a", true)))
        val tracker = WorkTracker(remote, scope)
        waitUntil("busy") { tracker.busy.value }

        remote.sessions = listOf(row("a", false)) // the idle event was lost; only the list knows
        remote.reopenEvents()
        waitUntil("idle after the events reopened") { !tracker.busy.value }
    }

    @Test fun theWatchdogReconcilesAStuckBusyWithoutAnyReopen() {
        val remote = FakeWorkRemote(listOf(row("a", true)))
        val tracker = WorkTracker(remote, scope, watchdogMs = 50)
        waitUntil("busy") { tracker.busy.value }

        remote.sessions = listOf(row("a", false))
        waitUntil("watchdog clears busy") { !tracker.busy.value }
    }
}
