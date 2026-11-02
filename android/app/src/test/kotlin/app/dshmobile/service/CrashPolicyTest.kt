package app.dshmobile.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashPolicyTest {
    private var now = 0L
    private val policy = CrashPolicy(max = 3, windowMs = 600_000, clock = { now })

    @Test fun thirdCrashInsideWindowGivesUp() {
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
        now += 60_000
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
        now += 60_000
        assertEquals(CrashPolicy.Decision.GIVE_UP, policy.onCrash())
    }

    @Test fun crashesOutsideTheWindowDoNotCount() {
        policy.onCrash()
        now += 100_000
        policy.onCrash()
        now += 500_000 // first crash is now exactly 600 s old and has left the window
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
        now += 1_000
        assertEquals(CrashPolicy.Decision.GIVE_UP, policy.onCrash())
    }

    @Test fun slowCrashesNeverGiveUp() {
        repeat(10) {
            assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
            now += 400_000
        }
    }

    @Test fun resetClearsTheCount() {
        policy.onCrash()
        policy.onCrash()
        policy.reset()
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
        assertEquals(CrashPolicy.Decision.GIVE_UP, policy.onCrash())
    }
}
