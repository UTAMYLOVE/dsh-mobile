package app.dshmobile.ui

import android.os.Handler
import android.os.Looper
import app.dshmobile.service.DshHolderRuntime
import app.dshmobile.service.RunningDsh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

private class FakeRuntime : DshHolderRuntime {
    val events = mutableListOf<String>()
    val held = linkedSetOf<String>()
    override fun retain(owner: String) { events += "retain:$owner"; held += owner }
    override fun release(owner: String) { events += "release:$owner"; held -= owner }
    override fun stopIfUnretained(): Boolean { events += "stopIfUnretained"; return held.isEmpty() }
    override fun stop() { events += "stop" }
    override suspend fun ensureRunning(): RunningDsh = throw UnsupportedOperationException()
}

@RunWith(RobolectricTestRunner::class)
class UiHolderTest {
    private val looper = Looper.getMainLooper()
    private val runtime = FakeRuntime()
    private val stoppedServices = mutableListOf<String>()
    private val order get() = runtime.events + stoppedServices

    private fun holder(graceMs: Long = BACKGROUND_GRACE_MS) = UiHolder(
        runtime = runtime,
        holders = { runtime.held.toSet() },
        stopService = { runtime.events += "stopService"; stoppedServices.clear() },
        handler = Handler(looper),
        graceMs = graceMs,
        runAsync = { it() },
    )

    @Test fun graceIsTenMinutes() {
        assertEquals(600_000L, BACKGROUND_GRACE_MS)
    }

    @Test fun startRetainsUi() {
        val h = holder()
        h.onUiStart()
        assertEquals(setOf("ui"), runtime.held)
        assertFalse(h.graceScheduled)
    }

    @Test fun stopReleasesUiAndSchedulesTheCheck() {
        val h = holder()
        h.onUiStart()
        h.onUiStop()
        assertTrue(runtime.held.isEmpty())
        assertTrue(h.graceScheduled)
        assertEquals(listOf("retain:ui", "release:ui"), runtime.events)
    }

    @Test fun nothingHappensBeforeTheGraceEnds() {
        val h = holder()
        h.onUiStart(); h.onUiStop()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS - 1))
        assertFalse(runtime.events.contains("stopService"))
        assertFalse(runtime.events.contains("stopIfUnretained"))
    }

    @Test fun afterTheGraceTheServiceIsStoppedThenDshIfUnretained() {
        val h = holder()
        h.onUiStart(); h.onUiStop()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS))
        assertEquals(listOf("retain:ui", "release:ui", "stopService", "stopIfUnretained"), runtime.events)
        assertFalse(h.graceScheduled)
    }

    @Test fun returningWithinTheGraceCancelsTheCheck() {
        val h = holder()
        h.onUiStart(); h.onUiStop()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS / 2))
        h.onUiStart()
        assertFalse(h.graceScheduled)
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS * 2))
        assertEquals(listOf("retain:ui", "release:ui", "retain:ui"), runtime.events)
    }

    @Test fun theCheckSurvivesTheActivity() {
        // The holder is process-level: a second "activity" (second onUiStart/onUiStop pair) restarts the full ten minutes.
        val h = holder()
        h.onUiStart(); h.onUiStop()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS - 1_000))
        h.onUiStart(); h.onUiStop()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS - 1))
        assertFalse(runtime.events.contains("stopService"))
        shadowOf(looper).idleFor(Duration.ofMillis(1))
        assertTrue(runtime.events.contains("stopService"))
    }

    @Test fun stoppingTwiceKeepsOneTimer() {
        val h = holder()
        h.onUiStart(); h.onUiStop(); h.onUiStop()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS))
        assertEquals(1, runtime.events.count { it == "stopService" })
    }

    @Test fun ifUiHoldsAgainAtTheEndNothingIsStopped() {
        val h = holder()
        h.onUiStart(); h.onUiStop()
        runtime.retain("ui") // a hold that appeared without going through onUiStart
        runtime.events.clear()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS))
        assertTrue(runtime.events.isEmpty())
    }

    @Test fun anotherHolderDoesNotPreventStoppingTheServiceButKeepsDshAlive() {
        val h = holder()
        runtime.retain("heartbeat")
        h.onUiStart(); h.onUiStop()
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS))
        assertTrue(runtime.events.contains("stopService"))
        assertTrue(runtime.events.contains("stopIfUnretained"))
        assertEquals(setOf("heartbeat"), runtime.held) // stopIfUnretained itself refuses while heartbeat holds
    }

    @Test fun aSecondScreenOpenedOnTopKeepsTheHoldWhileTheFirstOneStops() {
        // Android's order when Settings opens on top of Main: Main.onStart, Settings.onStart, Main.onStop (then the reverse on the way back).
        val h = holder()
        h.onUiStart()              // main
        h.onUiStart()              // settings
        h.onUiStop()               // main stops
        assertEquals(setOf("ui"), runtime.held)
        assertFalse(h.graceScheduled)
        shadowOf(looper).idleFor(Duration.ofMillis(BACKGROUND_GRACE_MS))
        assertFalse(runtime.events.contains("stopService"))
        h.onUiStart()              // back to main
        h.onUiStop()               // settings stops
        assertEquals(setOf("ui"), runtime.held)
        h.onUiStop()               // main goes to the background
        assertTrue(runtime.held.isEmpty())
        assertTrue(h.graceScheduled)
    }

    @Test fun retainHappensBeforeAnythingElseOnStart() {
        val h = holder()
        h.onUiStart()
        assertEquals("retain:ui", runtime.events.first())
    }
}
