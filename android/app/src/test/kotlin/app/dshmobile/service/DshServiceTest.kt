package app.dshmobile.service

import app.dshmobile.runtime.DesktopSignals
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
import app.dshmobile.remote.PendingPrompt
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
            onNotify = {}, onCostAlert = {}, onRateGuard = {}, launchTimeoutMs = 10_000, killer = DesktopSignals.killer(listOf("dshm-test-none-${System.nanoTime()}")),
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

        waitUntil(60_000) { app.serviceGaveUp.value }
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
        waitUntil(60_000) { app.serviceGaveUp.value }
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

    private fun idleMain(ms: Long = 0) = shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** The service posts to the main thread; the test's paused looper only runs what [idleMain] lets it. */
    private fun waitUntilMain(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) { idleMain(); Thread.sleep(10) }
        assertTrue("condition not met in time", cond())
    }

    private fun stoppedServiceName() = shadowOf(app as Application).nextStoppedService?.component?.className

    private fun doneNotices() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .count { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "DSH 完成了" }

    private fun serviceTitle(service: DshService) =
        shadowOf(service).lastForegroundNotification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)

    @Test fun swipingTheTaskAwayWhileWorkingKeepsTheServiceAndDsh() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, service) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }
        waitUntil { c.isRunning.value }

        // The owner leaves and swipes the app away; the ten minutes run out while dsh still works.
        app.uiHolder.onUiStart()
        app.uiHolder.onUiStop()
        service.onTaskRemoved(null)
        idleMain(11 * 60_000)
        assertEquals("the UI's countdown must not stop a working service", null, stoppedServiceName())
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(setOf("service", "work"), c.holders)
        assertTrue(processes.single().isAlive)

        // The work ends: the usual ten minutes start again and then stop the service.
        remote.status("a", false)
        waitUntilMain { app.uiHolder.graceScheduled }
        assertEquals(null, stoppedServiceName())
        idleMain(11 * 60_000)
        assertEquals(DshService::class.java.name, stoppedServiceName())
        sc.destroy()
    }

    @Test fun swipingTheTaskAwayWhenIdleBehavesAsBefore() {
        val remote = FakeWorkRemote()
        val (c, sc, service) = startWithFakeRemote(remote)
        waitUntil { c.isRunning.value }

        app.uiHolder.onUiStart()
        app.uiHolder.onUiStop()
        service.onTaskRemoved(null)
        idleMain(11 * 60_000) // the countdown ends: service stop requested as before
        assertEquals(DshService::class.java.name, stoppedServiceName())
        assertEquals(setOf("service"), c.holders)
        sc.destroy()
        waitUntil { !c.isRunning.value }
    }

    @Test fun workEndingWhileTheScreenIsUpPostsNoNoticeAndNoCountdown() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, service) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }
        app.uiHolder.onUiStart()

        remote.status("a", false)
        waitUntil { "work" !in c.holders && serviceTitle(service) == "DSH Mobile 執行中" }
        Thread.sleep(150); idleMain() // a wrongful notice or countdown would be posted to the main thread right after
        assertEquals(0, doneNotices())
        assertFalse(app.uiHolder.graceScheduled)
        sc.destroy()
    }

    @Test fun workEndingInTheBackgroundPostsTheNoticeAndStartsTheCountdown() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, _) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }

        remote.status("a", false)
        waitUntilMain { app.uiHolder.graceScheduled }
        assertFalse("work" in c.holders)
        assertEquals(1, doneNotices())
        sc.destroy()
    }

    @Test fun pressingStopPostsNoDoneNotice() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, service) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }

        service.onStartCommand(android.content.Intent().putExtra("action", "stop_work"), 0, 2)
        waitUntil { remote.cancelled.toList() == listOf("a") }
        remote.status("a", false)
        waitUntilMain { app.uiHolder.graceScheduled } // the notice, if any, is posted before this in the same step
        assertEquals(0, doneNotices())
        sc.destroy()
    }

    @Test fun aCrashRestartInTheBackgroundThatFindsNoWorkAppliesTheNormalEndRule() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        // dsh #0 dies a second after it came up, while working; #1 stays up.
        val (c, sc, _) = startWithFakeRemote(remote) { n -> if (n == 0) "$line sleep 1; exit 1" else "$line exec sleep 60" }
        waitUntil { "work" in c.holders }
        remote.sessions = listOf(FakeWorkRemote.row("a", false)) // by the time the new dsh is watched, nothing is running

        waitUntil(30_000) { processes.size == 2 && c.isRunning.value }
        waitUntilMain { app.uiHolder.graceScheduled }
        assertFalse("work" in c.holders)
        assertEquals("a quiet end, not a done notice", 0, doneNotices())
        assertEquals(null, stoppedServiceName())
        idleMain(11 * 60_000)
        assertEquals(DshService::class.java.name, stoppedServiceName())
        sc.destroy()
    }

    @Test fun anotherStartCommandForTheSameDshKeepsTheWatcher() {
        val remote = FakeWorkRemote()
        var built = 0
        val (c, sc, service) = startWithFakeRemote(remote)
        app.workRemoteFactory = { _, _ -> built++; remote }
        val first = service.tracker
        waitUntil { c.isRunning.value }

        service.onStartCommand(null, 0, 2) // the UI opened again; ensureRunning answers with the same dsh
        Thread.sleep(300)
        assertEquals(0, built)
        assertTrue(first === service.tracker)
        sc.destroy()
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

    @Test fun aCrashWhileWorkIsHeldStillFollowsTheCrashPolicy() {
        // Work is in flight the whole time (the fake always lists one running session), yet three quick crashes still give up.
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = "寫小說")))
        val (c, sc, service) = startWithFakeRemote(remote) { "$line exit 1" }

        waitUntil(60_000) { app.serviceGaveUp.value }
        waitUntil { shadowOf(service).isStoppedBySelf }
        assertEquals(3, processes.size)
        assertFalse(c.isRunning.value)
        sc.destroy()
        assertFalse("the work hold dies with the service", "work" in c.holders)
    }

    @Test fun theWorkingNotificationClipsALongTitleAndLeaksNoUrlOrToken() {
        val long = "字".repeat(100)
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("a", true, title = long), FakeWorkRemote.row("b", true, title = "x"), FakeWorkRemote.row("c", true)))
        val (_, sc, service) = startWithFakeRemote(remote)
        waitUntil { serviceTitle(service) == "DSH 正在工作…" && shadowOf(service).lastForegroundNotification!!.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().endsWith("等 3 件") }
        val text = shadowOf(service).lastForegroundNotification!!.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals("字".repeat(39) + "…" + " 等 3 件", text)
        assertFalse(text.contains("http") || text.contains("token"))
        sc.destroy()
    }

    // --- final review A-I3: dsh waits on the owner ---

    private fun waitingNotices() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .filter { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "DSH 在等你回答" }

    @Test fun aQuestionWhileNobodyLooksIsAnnouncedAndOpensTheTopLevelConversation() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("session-a", true, title = "寫小說"), FakeWorkRemote.row("child-1", true, parent = "session-a")))
        val (c, sc, _) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }
        remote.prompts.value = listOf(PendingPrompt.Approval("ev1", "child-1", "bash", null, "run"))
        waitUntilMain { waitingNotices().isNotEmpty() }
        val n = waitingNotices().single()
        assertEquals("寫小說", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals("a subagent's question opens its parent", "session-a", shadowOf(n.contentIntent).savedIntent.getStringExtra("sessionId"))
        // Answered (here or elsewhere): the notice goes.
        remote.prompts.value = emptyList()
        waitUntilMain { waitingNotices().isEmpty() }
        sc.destroy()
    }

    @Test fun aQuestionWhileTheScreenIsUpPostsNothing() {
        val remote = FakeWorkRemote(listOf(FakeWorkRemote.row("session-a", true, title = "寫小說")))
        val (c, sc, _) = startWithFakeRemote(remote)
        waitUntil { "work" in c.holders }
        app.uiHolder.onUiStart()
        try {
            remote.prompts.value = listOf(PendingPrompt.Approval("ev1", "session-a", "bash", null, "run"))
            Thread.sleep(200); idleMain()
            assertTrue(waitingNotices().isEmpty())
        } finally {
            app.uiHolder.onUiStop()
        }
        sc.destroy()
    }
}
