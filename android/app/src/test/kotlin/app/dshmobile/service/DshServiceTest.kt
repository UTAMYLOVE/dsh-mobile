package app.dshmobile.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.config.DshPatch
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
    private fun installFakeController(script: (launch: Int) -> String = { """echo "dsh web: http://127.0.0.1:45678/?token=abc"; exec sleep 60""" }): DshController {
        File(tmp, "rootfs/etc").mkdirs()
        val c = DshController(
            patchProvider = { DshPatch.render(it) },
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

    /**
     * Spec §11: dsh is not resident while the app is not in the foreground. A sticky service would be restarted by the system
     * after a process death (null intent) and start dsh with no screen, grace timer or holder to end it.
     */
    @Test fun theServiceIsNotRestartedByTheSystemAfterItsProcessDies() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = installFakeController()
        val service = Robolectric.buildService(DshService::class.java).create().get()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        waitUntil { c.isRunning.value }
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

    // --- Task 16: work in the background (spec §5.11) ---

    private fun startWithFakeRemote(remote: FakeWorkRemote, script: (Int) -> String = { """echo "dsh web: http://127.0.0.1:45678/?token=abc"; exec sleep 60""" }):
        Triple<DshController, org.robolectric.android.controller.ServiceController<DshService>, DshService> {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = installFakeController(script)
        app.workRemoteFactory = { _, _ -> remote }
        val sc = Robolectric.buildService(DshService::class.java)
        val service = sc.create().startCommand(0, 0).get()
        waitUntil { service.tracker != null }
        return Triple(c, sc, service)
    }

    @Test fun swipingTheTaskAwayWhileWorkingKeepsTheServiceAndDsh() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, service) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }
        waitUntil { c.isRunning.value }

        service.onTaskRemoved(null)
        Thread.sleep(300)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(c.isRunning.value)
        assertTrue(processes.single().isAlive)
        assertEquals(setOf("service", "work"), c.holders)
        sc.destroy()
    }

    @Test fun swipingTheTaskAwayWhenIdleBehavesAsBefore() {
        val remote = FakeWorkRemote()
        val (c, sc, service) = startWithFakeRemote(remote)
        waitUntil { c.isRunning.value }

        service.onTaskRemoved(null) // as before: no reaction of its own; the holder and grace rules decide
        Thread.sleep(300)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(setOf("service"), c.holders)
        assertTrue(c.isRunning.value)
        sc.destroy()
        waitUntil { !c.isRunning.value }
    }

    @Test fun theStopActionStopsAllWork() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true), FakeWorkRemote.row("k", true, parent = "a")))
        val (_, sc, service) = startWithFakeRemote(remote)
        waitUntil { service.tracker!!.running.value.size == 2 }

        service.onStartCommand(android.content.Intent().putExtra("action", "stop_work"), 0, 2)
        waitUntil { remote.cancelled.toList() == listOf("a") && remote.interrupted.toList() == listOf("k" to "a") }
        sc.destroy()
    }

    @Test fun theWorkingNotificationNamesTheConversationAndOffersStop() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說"), FakeWorkRemote.row("b", true, title = "整理筆記")))
        val (_, sc, service) = startWithFakeRemote(remote)
        waitUntil { shadowOf(service).lastForegroundNotification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE) == "DSH 正在工作…" }

        val n = shadowOf(service).lastForegroundNotification!!
        assertEquals("寫小說 等 2 件", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        val stop = n.actions.single()
        assertEquals("停止", stop.title.toString())
        val intent = shadowOf(stop.actionIntent).savedIntent
        assertTrue(shadowOf(stop.actionIntent).isService)
        assertEquals(DshService::class.java.name, intent.component!!.className)
        assertEquals("stop_work", intent.getStringExtra("action"))

        remote.status("a", false)
        remote.status("b", false)
        waitUntil { shadowOf(service).lastForegroundNotification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE) == "DSH Mobile 執行中" }
        sc.destroy()
    }

    @Test fun whenTheWorkEndsInTheBackgroundTheWorkHoldGoesAndADoneNoticeIsPosted() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, _) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }

        remote.status("a", false)
        waitUntil { "work" !in c.holders }
        val manager = app.getSystemService(NotificationManager::class.java)
        waitUntil { shadowOf(manager).allNotifications.any { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "DSH 完成了" } }
        sc.destroy()
    }

    @Test fun aCrashWhileWorkIsHeldStillFollowsTheCrashPolicy() {
        // Work is in flight the whole time (the fake always lists one running session), yet three quick crashes still give up.
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, service) = startWithFakeRemote(remote) { "$line exit 1" }

        waitUntil(30_000) { app.serviceGaveUp.value }
        waitUntil { shadowOf(service).isStoppedBySelf }
        assertEquals(3, processes.size)
        assertFalse(c.isRunning.value)
        sc.destroy()
        assertFalse("the work hold dies with the service", "work" in c.holders)
    }
}
