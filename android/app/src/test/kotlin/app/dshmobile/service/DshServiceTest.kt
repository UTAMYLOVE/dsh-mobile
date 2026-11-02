package app.dshmobile.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.DshMobileApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Only the paths that need no running dsh: the rootfs is never installed here. */
@RunWith(RobolectricTestRunner::class)
class DshServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp

    private fun waitUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("condition not met in time", cond())
    }

    @Test fun rootfsNotInstalledStopsTheServiceWithoutCrashPolicyOrGiveUpNotice() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.applicationInfo.nativeLibraryDir = app.cacheDir.path // Robolectric leaves it null; ProotRunner only stores it
        val controller = Robolectric.buildService(DshService::class.java)
        val service = controller.create().startCommand(0, 0).get()
        assertEquals(setOf("service"), app.controller.holders)
        waitUntil { shadowOf(service).isStoppedBySelf }

        assertTrue(app.errorLog.file().readText().contains("rootfs not installed"))
        // No "agent crashed repeatedly" notice, and the crash policy has not been consulted: the next crash still has all its tries.
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.none { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "DSH Mobile 已停止" })
        assertEquals(
            listOf(CrashPolicy.Decision.RESTART, CrashPolicy.Decision.RESTART, CrashPolicy.Decision.GIVE_UP),
            List(3) { app.crashPolicy.onCrash() },
        )

        controller.destroy()
        assertTrue(app.controller.holders.isEmpty())
    }
}
