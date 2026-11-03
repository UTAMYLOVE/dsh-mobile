package app.dshmobile.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.DshMobileApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** M-7: the app's crash window runs on the monotonic `SystemClock.elapsedRealtime`, not the wall clock (which the user can set). */
@RunWith(RobolectricTestRunner::class)
class CrashPolicyClockTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp

    @Test fun theAppsCrashWindowFollowsElapsedRealtime() {
        val policy = app.crashPolicy
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11)) // both crashes leave the ten-minute window
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
    }

    @Test fun theDefaultClockIsElapsedRealtime() {
        val policy = CrashPolicy()
        policy.onCrash()
        policy.onCrash()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        assertEquals(CrashPolicy.Decision.RESTART, policy.onCrash())
    }
}
