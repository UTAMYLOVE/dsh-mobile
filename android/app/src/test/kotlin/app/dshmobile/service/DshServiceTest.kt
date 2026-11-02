package app.dshmobile.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.DshMobileApp
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.security.KeyVault
import org.junit.After
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val tmp: File = Files.createTempDirectory("svctest").toFile()
    private val processes = java.util.concurrent.CopyOnWriteArrayList<Process>()
    private var testController: DshController? = null

    @After fun cleanup() {
        testController?.stop()
        processes.forEach { it.destroyForcibly() }
        tmp.deleteRecursively()
    }

    /** Swaps the app's lazy `controller` for one with a fake `sh` dsh, the seam DshService reads (`app.controller`). */
    private fun installFakeController(script: (launch: Int) -> String = { """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 60""" }): DshController {
        File(tmp, "rootfs/etc").mkdirs()
        val c = DshController(
            filesDir = tmp, isRootfsInstalled = { true },
            launcher = { _, _ ->
                ProcessBuilder("sh", "-c", script(processes.size)).start().also { processes.add(it) }
            },
            vault = object : KeyVault {
                override fun get(p: Provider): String? = null
                override fun put(p: Provider, key: String) = Unit
                override fun clear(p: Provider) = Unit
            },
            meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
            errorLog = ErrorLog(tmp), healthCheck = { true }, dnsServers = { emptyList() },
            onNotify = {}, onCostAlert = {}, onRateGuard = {}, launchTimeoutMs = 10_000, stopGraceMs = 500,
        )
        testController = c
        val field = DshMobileApp::class.java.getDeclaredField("controller\$delegate")
        field.isAccessible = true
        field.set(app, lazyOf(c))
        return c
    }

    @Test fun destroyReleasesTheServiceHoldButLeavesDshRunningForAnotherHolder() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = installFakeController()
        val controller = Robolectric.buildService(DshService::class.java)
        controller.create().startCommand(0, 0)
        waitUntil { c.isRunning.value }
        c.retain("heartbeat")
        assertEquals(setOf("service", "heartbeat"), c.holders)

        controller.destroy()
        assertEquals(setOf("heartbeat"), c.holders)
        Thread.sleep(500) // destroy stops on a background thread; give a wrongful stop time to happen
        assertTrue(c.isRunning.value)
        assertTrue(processes.single().isAlive)
    }

    @Test fun destroyStopsDshWhenNobodyElseHoldsIt() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = installFakeController()
        val controller = Robolectric.buildService(DshService::class.java)
        controller.create().startCommand(0, 0)
        waitUntil { c.isRunning.value }
        controller.destroy()
        waitUntil { !c.isRunning.value }
        waitUntil { !processes.single().isAlive }
        assertTrue(c.holders.isEmpty())
    }

    private val line = """echo "dsh web: http://127.0.0.1:45678/?token=abc";"""

    @Test fun threeQuickCrashesAreAGiveUpAndTheScreenIsToldAboutIt() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = installFakeController { "$line exit 1" } // every dsh dies right after it came up
        val service = Robolectric.buildService(DshService::class.java).create().startCommand(0, 0).get()
        assertFalse("a fresh start is not a give-up", app.serviceGaveUp.value)

        waitUntil(30_000) { app.serviceGaveUp.value }
        waitUntil { shadowOf(service).isStoppedBySelf }
        assertEquals(3, processes.size)
        assertFalse(c.isRunning.value)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.any { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "DSH Mobile 已停止" })
    }

    @Test fun aServiceStartClearsAStaleGiveUp() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        installFakeController()
        app.serviceGaveUp.value = true
        Robolectric.buildService(DshService::class.java).create().startCommand(0, 0)
        assertFalse(app.serviceGaveUp.value)
    }

    @Test fun afterAGiveUpARetryRunsUnderTheServiceAgainAndALaterCrashIsHandled() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // dsh #0-#2 crash at once (give up); #3 (the retry) runs for one second and then dies unexpectedly; #4 stays up.
        val c = installFakeController { n -> if (n < 3) "$line exit 1" else if (n == 3) "$line exec sleep 1" else "$line exec sleep 60" }
        val first = Robolectric.buildService(DshService::class.java)
        val gaveUp = first.create().startCommand(0, 0).get()
        waitUntil(30_000) { app.serviceGaveUp.value }
        waitUntil { shadowOf(gaveUp).isStoppedBySelf }
        first.destroy()

        // What MainActivity.retry() does: start the service again.
        val second = Robolectric.buildService(DshService::class.java)
        second.create().startCommand(0, 0)
        assertFalse(app.serviceGaveUp.value)
        assertTrue("service" in c.holders)

        // The crash of #3 goes through the new service's CrashPolicy path: a restart (#4), not a silent dead end.
        waitUntil(30_000) { processes.size == 5 && c.isRunning.value }
        assertFalse("not a second give-up", app.serviceGaveUp.value)
        assertTrue("service" in c.holders)
        second.destroy()
    }

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
