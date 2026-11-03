package app.dshmobile.ui

import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.security.SessionToken
import app.dshmobile.service.DshHolderRuntime
import app.dshmobile.service.RunningDsh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class SettingsJobsTest {
    private class FakeHolders : DshHolderRuntime {
        val held = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val events = CopyOnWriteArrayList<String>()
        override fun retain(owner: String) { held += owner; events += "retain $owner" }
        override fun release(owner: String) { held -= owner; events += "release $owner" }
        override fun stopIfUnretained(): Boolean { events += "stopIfUnretained"; return held.isEmpty() }
        override fun stop() { events += "stop" }
        override suspend fun ensureRunning(): RunningDsh =
            RunningDsh(1, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:1/?token=x", 1))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val holders = FakeHolders()
    private val jobs = SettingsJobs(scope, holders)

    @After fun tearDown() = scope.cancel()

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    /** M-2: the ten-minute UI grace may end during a 15-minute npm install; the job's own hold keeps dsh up until it is done. */
    @Test fun aJobHoldsDshWhileItRunsAndLetsGoAfterwards() {
        val gate = CompletableDeferred<Unit>()
        var heldInside = false
        assertTrue(jobs.launch { heldInside = SettingsJobs.HOLDER in holders.held; gate.await() })
        waitUntil { heldInside }
        assertTrue(jobs.busy.value)

        gate.complete(Unit)
        waitUntil { !jobs.busy.value }
        assertFalse(SettingsJobs.HOLDER in holders.held)
        assertEquals(listOf("retain settings-job", "release settings-job", "stopIfUnretained"), holders.events)
    }

    @Test fun aFailingJobAlsoLetsGo() {
        assertTrue(jobs.launch { throw IllegalStateException("boom") })
        waitUntil { !jobs.busy.value }
        assertEquals(listOf("retain settings-job", "release settings-job", "stopIfUnretained"), holders.events)
    }

    @Test fun oneJobAtATime() {
        val gate = CompletableDeferred<Unit>()
        assertTrue(jobs.launch { gate.await() })
        assertFalse(jobs.launch { })
        gate.complete(Unit)
        waitUntil { !jobs.busy.value }
        assertTrue(jobs.launch { })
    }
}
