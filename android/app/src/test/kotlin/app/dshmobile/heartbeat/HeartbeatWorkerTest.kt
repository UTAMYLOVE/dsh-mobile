package app.dshmobile.heartbeat

import app.dshmobile.runtime.DesktopSignals
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.NotifyRequest
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.notify.Notifier
import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.FakeDshServer
import app.dshmobile.security.KeyVault
import app.dshmobile.service.DshController
import app.dshmobile.update.DailyUpdateCheck
import app.dshmobile.update.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real [DshController] with a fake dsh process (a shell that prints a launch line) in front of a [FakeDshServer]. */
@RunWith(RobolectricTestRunner::class)
class HeartbeatWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val filesDir: File = Files.createTempDirectory("hbtest").toFile()
    private val errorLog = ErrorLog(filesDir)
    private val notified = CopyOnWriteArrayList<NotifyRequest>()
    private val starts = AtomicInteger()
    private val processes = CopyOnWriteArrayList<Process>()
    private val heartbeatRequests = CopyOnWriteArrayList<String>()
    private var installed = true
    private var heartbeatResponse: () -> MockResponse = { MockResponse().setResponseCode(200).setBody("""{"promoted":[],"reminders":[]}""") }
    private var duringHeartbeat: () -> Unit = {}
    private var serviceNotification: () -> Notification = { Notifier(context).serviceNotification() }
    private var timeoutMs = 20_000L
    private var tokenValid = true

    private val dsh = FakeDshServer(tokenValid = { tokenValid }, heartbeat = { request ->
        heartbeatRequests.add(request.body.readUtf8())
        duringHeartbeat()
        heartbeatResponse()
    })

    private val controller = DshController(
        patchProvider = { app.dshmobile.config.DshPatch.render(it) },
        filesDir = filesDir,
        isRootfsInstalled = { installed },
        launcher = { _, _ ->
            starts.incrementAndGet()
            ProcessBuilder("sh", "-c", """echo "dsh web: http://127.0.0.1:${dsh.server.port}/?token=good"; exec sleep 60""")
                .start().also { processes.add(it) }
        },
        vault = object : KeyVault {
            override fun get(p: Provider): String? = null
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        },
        meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
        errorLog = errorLog,
        healthCheck = { true },
        dnsServers = { emptyList() },
        onNotify = {},
        onCostAlert = {},
        onRateGuard = {},
        launchTimeoutMs = 10_000,
        killer = DesktopSignals.killer(listOf("dshm-test-none-${System.nanoTime()}")),
    ).also { File(filesDir, "rootfs/etc").mkdirs() }

    @After fun cleanup() {
        controller.stop()
        processes.forEach { it.destroyForcibly() }
        dsh.close()
        filesDir.deleteRecursively()
    }

    private var updateCheck: DailyUpdateCheck? = null
    private var gaveUp = false

    private fun deps() = HeartbeatDeps(
        runtime = controller,
        clientFor = { launch -> DshClient(OkHttpClient(), launch) },
        notify = { notified.add(it) },
        serviceNotification = { serviceNotification() },
        errorLog = errorLog,
        timeoutMs = timeoutMs,
        updateCheck = updateCheck,
        serviceGaveUp = { gaveUp },
    )

    private fun worker() = TestListenableWorkerBuilder<HeartbeatWorker>(context)
        .setWorkerFactory(HeartbeatWorkerFactory { deps() })
        .build()

    private fun runHeartbeat(): ListenableWorker.Result = runBlocking { worker().doWork() }

    private fun log(): String = if (errorLog.file().exists()) errorLog.file().readText() else ""

    private fun twoReminders() = MockResponse().setResponseCode(200).setBody(
        """{"promoted":["a","b","c"],"reminders":[{"title":"Dentist","body":"Tomorrow 10:00"},{"title":"Rent","body":"Due Friday"}]}""",
    )

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("condition not met in time", cond())
    }

    // ---- what it posts ----

    @Test fun twoRemindersBecomeTwoNotifications() {
        heartbeatResponse = ::twoReminders
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(
            listOf(NotifyRequest("Dentist", "Tomorrow 10:00", null), NotifyRequest("Rent", "Due Friday", null)),
            notified.toList(),
        )
        assertEquals(listOf("{}"), heartbeatRequests.toList())
    }

    @Test fun promotedCountIsLoggedWithoutAnyContent() {
        heartbeatResponse = ::twoReminders
        runHeartbeat()
        val text = log()
        assertTrue(text, text.contains("[heartbeat]") && text.contains("promoted=3"))
        assertFalse(text.contains("Dentist"))
        assertFalse(text.contains("Tomorrow"))
        assertFalse(text.contains("Rent"))
    }

    @Test fun noRemindersMeansNoNotifications() {
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertTrue(notified.isEmpty())
        assertTrue(log().contains("promoted=0"))
    }

    @Test fun malformedReminderEntriesAreSkippedAndGoodOnesStillPosted() {
        heartbeatResponse = {
            MockResponse().setResponseCode(200)
                .setBody("""{"promoted":[],"reminders":[{"title":"Only title"},{"title":"Ok","body":"fine"},"junk",{"title":1,"body":2}]}""")
        }
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(listOf(NotifyRequest("Ok", "fine", null)), notified.toList())
    }

    @Test fun anUnparseableBodyIsLoggedAsSuchAndNothingIsPosted() {
        heartbeatResponse = { MockResponse().setResponseCode(200).setBody("not json SECRETBODY") }
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertTrue(notified.isEmpty())
        assertTrue(log().contains("heartbeat"))
        assertFalse(log().contains("SECRETBODY"))
    }

    @Test fun aFailingNotificationDoesNotStopTheOthers() {
        heartbeatResponse = ::twoReminders
        var first = true
        val flaky = HeartbeatDeps(
            runtime = controller,
            clientFor = { DshClient(OkHttpClient(), it) },
            notify = { if (first) { first = false; throw SecurityException("boom") } else notified.add(it) },
            serviceNotification = { serviceNotification() },
            errorLog = errorLog,
        )
        val w = TestListenableWorkerBuilder<HeartbeatWorker>(context).setWorkerFactory(HeartbeatWorkerFactory { flaky }).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { w.doWork() })
        assertEquals(listOf("Rent"), notified.map { it.title })
    }

    // ---- skipping quietly ----

    @Test fun serverErrorsAreSkippedWithSuccessNoNotificationAndOnlyTheStatusLogged() {
        for (code in listOf(500, 409, 502)) {
            notified.clear()
            heartbeatResponse = { MockResponse().setResponseCode(code).setBody("""{"error":"LEAKME"}""") }
            assertEquals("HTTP $code", ListenableWorker.Result.success(), runHeartbeat())
            assertTrue(notified.isEmpty())
            assertTrue(log().contains("HTTP $code"))
        }
        assertFalse(log().contains("LEAKME"))
        assertFalse(log().contains("dream_busy"))
    }

    @Test fun rootfsNotInstalledIsSkippedWithSuccess() {
        installed = false
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertTrue(notified.isEmpty())
        assertEquals(0, starts.get())
        assertTrue(log().contains("RootfsNotInstalledException"))
        assertTrue(controller.holders.isEmpty())
    }

    @Test fun aLoginFailureIsSkippedWithSuccessAndReleasesAndStops() {
        tokenValid = false
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertTrue(notified.isEmpty())
        assertTrue(controller.holders.isEmpty())
        waitUntil { !processes.single().isAlive }
        assertFalse(log().contains("token=good"))
    }

    @Test fun aSlowRouteTimesOutQuietlyAndTheHeartbeatLetsGo() {
        heartbeatResponse = { MockResponse().setResponseCode(200).setBody("""{"promoted":[],"reminders":[{"title":"late","body":"x"}]}""").setBodyDelay(3, TimeUnit.SECONDS) }
        timeoutMs = 700
        val started = System.currentTimeMillis()
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertTrue(System.currentTimeMillis() - started < 2_500)
        assertTrue(notified.isEmpty())
        assertTrue(controller.holders.isEmpty())
        assertFalse(processes.single().isAlive)
    }

    @Test fun aCancelledHeartbeatStillReleasesAndStops() = runBlocking {
        val inRoute = CountDownLatch(1)
        duringHeartbeat = { inRoute.countDown() }
        heartbeatResponse = { MockResponse().setResponseCode(200).setBody("{}").setBodyDelay(3, TimeUnit.SECONDS) }
        val job = async(Dispatchers.Default) { worker().doWork() }
        assertTrue(inRoute.await(10, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(controller.holders.isEmpty())
        waitUntil { processes.isNotEmpty() && !processes.single().isAlive }
        assertTrue(notified.isEmpty())
    }

    @Test fun aFailureToGoForegroundIsLoggedAndTheHeartbeatContinues() {
        serviceNotification = { throw IllegalStateException("ForegroundServiceStartNotAllowedException stand-in") }
        heartbeatResponse = ::twoReminders
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(2, notified.size)
        assertTrue(log().contains("foreground"))
    }

    // ---- not touching somebody else's dsh ----

    @Test fun whenTheServiceAlreadyHoldsDshNoSecondDshIsStartedAndItKeepsRunning() = runBlocking {
        controller.retain("service")
        controller.ensureRunning()
        assertEquals(1, starts.get())
        heartbeatResponse = ::twoReminders
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(1, starts.get())
        assertEquals(2, notified.size)
        assertTrue(controller.isRunning.value)
        assertTrue(processes.single().isAlive)
        assertEquals(setOf("service"), controller.holders)
    }

    /** M-1: after a give-up only the user restarts dsh; the heartbeat must not cold-start a known-broken dsh every period. */
    @Test fun afterAGiveUpTheHeartbeatNeitherStartsDshNorDreamsAndSucceeds() {
        gaveUp = true
        heartbeatResponse = ::twoReminders
        var checked = 0
        updateCheck = dailyUpdate { checked++; null }
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals("no dsh was started", 0, starts.get())
        assertTrue("no dream was asked for", heartbeatRequests.isEmpty())
        assertTrue(notified.isEmpty())
        assertTrue(controller.holders.isEmpty())
        assertTrue(log(), log().contains("[heartbeat] skipped: dsh gave up"))
        assertEquals("the daily update check does not need dsh and still runs", 1, checked)
    }

    @Test fun whenNobodyElseHoldsDshItIsStoppedAfterwards() {
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(1, starts.get())
        assertFalse(controller.isRunning.value)
        assertFalse(processes.single().isAlive)
        assertTrue(controller.holders.isEmpty())
    }

    @Test fun theHeartbeatHoldsDshWhileItWorks() {
        var heldDuring: Set<String> = emptySet()
        duringHeartbeat = { heldDuring = controller.holders }
        runHeartbeat()
        assertEquals(setOf("heartbeat"), heldDuring)
    }

    @Test fun whenTheServiceJoinsDuringTheHeartbeatTheHeartbeatDoesNotStopDsh() {
        duringHeartbeat = { controller.retain("service") } // the user starts the service while the heartbeat is mid-route
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(1, starts.get())
        assertTrue(controller.isRunning.value)
        assertTrue(processes.single().isAlive)
        assertEquals(setOf("service"), controller.holders)
    }

    @Test fun twoHeartbeatsOneAfterTheOtherEachColdStartAndStop() {
        runHeartbeat()
        runHeartbeat()
        assertEquals(2, starts.get())
        assertFalse(controller.isRunning.value)
    }

    // ---- the daily update check ----

    private val release = ReleaseInfo(2, "0.2.0", "https://github.com/o/r/a.apk", "a".repeat(64))
    private val updateClock = MutableClock(java.time.Instant.parse("2026-11-02T01:00:00Z"))
    private val updateChecks = AtomicInteger()
    private val announced = CopyOnWriteArrayList<ReleaseInfo>()

    private fun dailyUpdate(check: suspend () -> ReleaseInfo?) = DailyUpdateCheck(
        InMemorySharedPreferences(), { updateChecks.incrementAndGet(); check() }, { announced.add(it) }, errorLog, updateClock,
    )

    @Test fun theFirstHeartbeatOfTheDayChecksForAnUpdateAndTheSecondDoesNot() {
        updateCheck = dailyUpdate { release }
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(1, updateChecks.get())
        assertEquals(listOf(release), announced.toList())
        assertEquals(2, heartbeatRequests.size)
    }

    @Test fun theNextTaipeiDayChecksAgain() {
        updateCheck = dailyUpdate { null }
        runHeartbeat()
        updateClock.now = java.time.Instant.parse("2026-11-02T16:05:00Z") // just past midnight in Taipei
        runHeartbeat()
        assertEquals(2, updateChecks.get())
    }

    @Test fun aThrowingUpdateCheckDoesNotChangeTheHeartbeatResult() {
        heartbeatResponse = ::twoReminders
        updateCheck = dailyUpdate { throw java.io.IOException("offline") }
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(2, notified.size) // the reminders still went out
        assertTrue(log().contains("[update]") && log().contains("IOException"))
        assertTrue(controller.holders.isEmpty())
    }

    @Test fun theUpdateCheckDoesNotNeedDsh() {
        installed = false
        updateCheck = dailyUpdate { release }
        assertEquals(ListenableWorker.Result.success(), runHeartbeat())
        assertEquals(listOf(release), announced.toList())
    }
}
