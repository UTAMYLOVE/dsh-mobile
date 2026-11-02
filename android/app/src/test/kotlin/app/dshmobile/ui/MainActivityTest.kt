package app.dshmobile.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.BuildConfig
import app.dshmobile.DshMobileApp
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.notify.Notifier
import app.dshmobile.runtime.CommandResult
import app.dshmobile.runtime.DshVersions
import app.dshmobile.runtime.RootfsInstaller
import app.dshmobile.security.KeyVault
import app.dshmobile.service.DshController
import app.dshmobile.service.DshService
import app.dshmobile.system.OnboardingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private const val LAUNCH_URL = "http://127.0.0.1:45678/?token=abc"

private fun launchUrl(n: Int) = "http://127.0.0.1:${45678 + n}/?token=abc"

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("maintest").toFile()
    private val processes = java.util.concurrent.CopyOnWriteArrayList<Process>()
    private var controller: DshController? = null

    @Before fun setUp() {
        // FileProvider caches its path strategy statically; every Robolectric test has a fresh cache dir.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null).let { @Suppress("UNCHECKED_CAST") (it as MutableMap<String, Any>).clear() }
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() {
        // End the activities first: their collectors and timers must not outlive the test.
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        controller?.stop()
        processes.forEach { it.destroyForcibly() }
        tmp.deleteRecursively()
        File(app.cacheDir, "export").deleteRecursively()
    }

    private fun swap(field: String, value: Any) {
        val f = DshMobileApp::class.java.getDeclaredField("$field\$delegate")
        f.isAccessible = true
        f.set(app, lazyOf(value))
    }

    /** Onboarding finished, rootfs marker present, a key in a fake vault, and a fake `sh` dsh behind the controller. */
    private fun completeSetup() {
        val prefs = app.getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE)
        val state = OnboardingState(prefs)
        OnboardingState.Step.values().forEach { state.markDone(it) }
        File(app.filesDir, "rootfs/${RootfsInstaller.VERSION_MARKER}").apply { parentFile!!.mkdirs() }.writeText(BuildConfig.ROOTFS_VERSION)
        swap("keyVault", object : KeyVault {
            override fun get(p: Provider): String? = "sk-test-key-0123456789"
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        })
        File(tmp, "rootfs/etc").mkdirs()
        val c = DshController(
            filesDir = tmp, isRootfsInstalled = { true },
            launcher = { _, _ ->
                // every start prints its own launch URL (port 45678, 45679, ...), so a restart is visible in the WebView
                val url = launchUrl(processes.size)
                ProcessBuilder("sh", "-c", """echo "dsh web: $url"; exec sleep 60""").start().also { processes.add(it) }
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
        controller = c
        swap("controller", c)
    }

    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<MainActivity>>()

    private fun build(intent: Intent = Intent()) =
        Robolectric.buildActivity(MainActivity::class.java, intent.setComponent(ComponentName(app, MainActivity::class.java)))
            .also { activities += it }

    private fun waitUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun errorLogText(): String = app.errorLog.file().takeIf { it.exists() }?.readText().orEmpty()

    // --- onboarding gate ---

    @Test fun unfinishedOnboardingStartsOnboardingAndFinishes() {
        val activity = build().create().get()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(OnboardingActivity::class.java.name, started.component!!.className)
        assertTrue(activity.isFinishing)
    }

    @Test fun unfinishedOnboardingDoesNotHoldDshOrStartTheService() {
        val c = DshController(
            filesDir = tmp, isRootfsInstalled = { false }, launcher = { _, _ -> error("never") },
            vault = object : KeyVault {
                override fun get(p: Provider): String? = null
                override fun put(p: Provider, key: String) = Unit
                override fun clear(p: Provider) = Unit
            },
            meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
            errorLog = ErrorLog(tmp), healthCheck = { true }, dnsServers = { emptyList() },
            onNotify = {}, onCostAlert = {}, onRateGuard = {},
        )
        swap("controller", c)
        build().create().start().resume()
        assertTrue(c.holders.isEmpty())
        val next = shadowOf(app as Application).nextStartedService
        assertNull(next)
    }

    @Test fun finishedOnboardingStaysOnTheMainScreen() {
        completeSetup()
        val activity = build().create().get()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun theKeyCanBeMissingEvenWhenTheStepsAreDone() {
        completeSetup()
        swap("keyVault", object : KeyVault {
            override fun get(p: Provider): String? = null
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        })
        val activity = build().create().get()
        assertEquals(OnboardingActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(activity.isFinishing)
    }

    // --- start / stop ---

    @Test fun startRetainsUiStartsTheServiceAndLoadsTheLaunchUrl() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        assertTrue("ui" in c.holders)
        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService.component!!.className)
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == LAUNCH_URL }
        assertTrue(c.isRunning.value)
    }

    @Test fun stopReleasesUiAndSchedulesTheGraceCheck() {
        completeSetup()
        val c = controller!!
        val ctl = build().create().start().resume()
        waitUntil { c.isRunning.value }
        ctl.pause().stop()
        assertFalse("ui" in c.holders)
        assertTrue(app.uiHolder.graceScheduled)
        // coming back cancels it and holds again
        ctl.restart().start().resume()
        assertTrue("ui" in c.holders)
        assertFalse(app.uiHolder.graceScheduled)
    }

    @Test fun theWebViewIsConfiguredStrictly() {
        completeSetup()
        val activity = build().create().get()
        val s = activity.webView.settings
        assertTrue(s.javaScriptEnabled)
        assertTrue(s.domStorageEnabled)
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
        assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
        assertFalse(s.supportMultipleWindows())
    }

    @Test fun theWebViewNeverGetsAJavascriptBridge() {
        completeSetup()
        val activity = build().create().get()
        assertTrue(shadowOf(activity.webView).getJavascriptInterface("anything") == null)
    }

    // --- the activity never starts dsh on its own ---

    private fun idleFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test fun afterAStopTheActivityDoesNotStartDshAgain() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }
        assertEquals(1, processes.size)

        c.stop() // what DshService does on GIVE_UP (or anyone on a deliberate stop)
        waitUntil { !c.isRunning.value }
        idleFor(1_500)

        assertFalse(c.isRunning.value)
        assertEquals("no new dsh was started", 1, processes.size)
        assertEquals(launchUrl(0), shadowOf(activity.webView).lastLoadedUrl)
    }

    @Test fun afterTheServiceGaveUpTheActivityShowsTheErrorScreenAndStaysStopped() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }

        app.serviceGaveUp.value = true // DshService sets this before its controller.stop() on GIVE_UP
        c.stop()
        waitUntil { activity.uiStateForTest.value == MainUiState.Error }
        idleFor(1_000)

        assertEquals(1, processes.size)
        assertFalse(c.isRunning.value)
        assertEquals(MainUiState.Error, activity.uiStateForTest.value)
    }

    @Test fun retryAfterAGiveUpClearsTheFlagAndStartsTheServiceAgain() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }
        shadowOf(app as Application).clearStartedServices()
        app.serviceGaveUp.value = true
        c.stop()
        waitUntil { activity.uiStateForTest.value == MainUiState.Error }

        activity.retry() // the "重試" button

        assertFalse(app.serviceGaveUp.value)
        // Without the service nobody supervises the dsh the retry starts (no CrashPolicy, no notice, no restart).
        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService.component!!.className)
    }

    /** The activity is open, dsh gave up (what DshService does: flag first, then stop) and the user has left and come back. */
    private fun giveUpThenReenter(): Pair<org.robolectric.android.controller.ActivityController<MainActivity>, MainActivity> {
        val c = controller!!
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }
        app.serviceGaveUp.value = true
        c.stop()
        waitUntil { activity.uiStateForTest.value == MainUiState.Error }
        shadowOf(app as Application).clearStartedServices()
        ctl.pause().stop() // e.g. the settings round trip, or switching apps
        ctl.restart().start().resume()
        return ctl to activity
    }

    @Test fun aGiveUpSurvivesLeavingAndComingBackNothingRestartsDsh() {
        completeSetup()
        val c = controller!!
        val (_, activity) = giveUpThenReenter()
        idleFor(1_500)

        assertTrue("the gave-up flag is still set", app.serviceGaveUp.value)
        assertEquals(MainUiState.Error, activity.uiStateForTest.value)
        assertNull("DshService must not be started", shadowOf(app as Application).nextStartedService)
        assertEquals("ensureRunning must not have started a new dsh", 1, processes.size)
        assertFalse(c.isRunning.value)
        assertTrue("the UI still holds dsh (bookkeeping as usual)", "ui" in c.holders)
    }

    @Test fun retryAfterAGiveUpAndReentryStartsTheServiceAndClearsTheFlag() {
        completeSetup()
        val (_, activity) = giveUpThenReenter()

        activity.retry()

        assertFalse(app.serviceGaveUp.value)
        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService.component!!.className)
    }

    @Test fun aFreshOpenWithoutAGiveUpStartsNormally() {
        completeSetup()
        val c = controller!!
        assertFalse(app.serviceGaveUp.value)
        val activity = build().create().start().resume().get()
        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService.component!!.className)
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }
        assertTrue(c.isRunning.value)
        assertTrue("ui" in c.holders)
    }

    @Test fun theRollbackActionAfterAGiveUpClearsTheFlagAndRestartsDsh() {
        completeSetup()
        val c = controller!!
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        val (ctl, activity) = giveUpThenReenter()
        assertEquals(MainUiState.Error, activity.uiStateForTest.value)

        ctl.newIntent(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_ROLLBACK)) // the crash notification's action
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitUntil { currentOf(opt) == "0.2.0" && processes.size == 2 && c.isRunning.value }
        waitUntil { !app.serviceGaveUp.value }

        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService.component!!.className)
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(1) }
    }

    @Test fun whenDshComesBackElsewhereTheActivityReloadsTheNewLaunchUrl() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }

        c.stop()
        waitUntil { !c.isRunning.value }
        idleFor(300)
        kotlinx.coroutines.runBlocking { c.ensureRunning() } // e.g. the service's delayed restart after a crash
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(1) }

        assertEquals(2, processes.size)
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value) // Ready only follows onPageFinished
    }

    // --- intent dispatch ---

    @Test fun exportErrorLogOpensAShareChooser() {
        completeSetup()
        app.errorLog.append("test", "something happened")
        val activity = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_EXPORT_ERROR_LOG)).create().get()
        waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
    }

    @Test fun exportErrorLogViaNewIntentToo() {
        completeSetup()
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        ctl.newIntent(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_EXPORT_ERROR_LOG))
        waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity.action)
    }

    @Test fun anExtraIsHandledOnceNotAgainAfterRecreation() {
        completeSetup()
        val ctl = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_EXPORT_ERROR_LOG)).create()
        val activity = ctl.get()
        waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
        shadowOf(activity).nextStartedActivity
        assertNull(activity.intent.getStringExtra(Notifier.EXTRA_ACTION))
    }

    @Test fun aSessionIdIsOnlyLoggedForTheDeepLinkTodo() {
        completeSetup()
        val activity = build(Intent().putExtra(Notifier.EXTRA_SESSION_ID, "session-1111-2222")).create().get()
        assertFalse(activity.isFinishing)
        val text = errorLogText()
        assertTrue(text, text.contains("[deeplink]") && text.contains("session-1111-2222"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun rollbackAsksBeforeDoingAnything() {
        completeSetup()
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        val activity = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_ROLLBACK)).create().start().resume().get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("0.2.1", currentOf(opt)) // nothing happened yet
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("0.2.1", currentOf(opt))
        assertFalse(activity.isFinishing)
    }

    @Test fun confirmedRollbackSwitchesVersionRestartsDshAndReloads() {
        completeSetup()
        val c = controller!!
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_ROLLBACK)).create().start().resume()
        waitUntil { c.isRunning.value }
        val first = processes.size
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitUntil { currentOf(opt) == "0.2.0" && processes.size == first + 1 && c.isRunning.value }
        assertFalse(processes.first().isAlive)
        assertTrue(processes.last().isAlive)
    }

    @Test fun rollbackWithNothingToGoBackToSaysSoAndKeepsDshRunning() {
        completeSetup()
        val c = controller!!
        val opt = versionsDir(previous = null, current = "0.2.1")
        val activity = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_ROLLBACK)).create().start().resume().get()
        waitUntil { c.isRunning.value }
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitUntil { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() != null }
        assertEquals("0.2.1", currentOf(opt))
        assertTrue(c.isRunning.value)
        assertEquals(1, processes.size)
        assertFalse(activity.isFinishing)
    }

    // --- settings button ---

    @Test fun settingsOpensTheSettingsActivityByName() {
        completeSetup()
        val activity = build().create().get()
        activity.openSettings()
        val i = shadowOf(activity).nextStartedActivity
        assertEquals(ComponentName(app.packageName, "app.dshmobile.ui.SettingsActivity"), i.component)
    }

    // --- helpers ---

    private fun versionsDir(previous: String?, current: String): File {
        val opt = File(app.filesDir, "rootfs/opt/dsh").apply { mkdirs() }
        listOfNotNull(previous, current).forEach { File(opt, it).mkdirs() }
        Files.createSymbolicLink(File(opt, "current").toPath(), Path.of("/opt/dsh/$current"))
        previous?.let { File(opt, ".previous").writeText("$it\n") }
        swap("dshVersions", DshVersions(opt, { _, _ -> CommandResult(1, "", "no guest in tests") }, app.errorLog))
        return opt
    }

    private fun currentOf(opt: File): String = Files.readSymbolicLink(File(opt, "current").toPath()).fileName.toString()

    private val MainActivity.webView: WebView get() = this.webViewForTest
}
