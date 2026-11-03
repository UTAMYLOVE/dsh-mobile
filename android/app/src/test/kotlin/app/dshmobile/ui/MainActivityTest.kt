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
import app.dshmobile.config.DshPatch
import app.dshmobile.BuildConfig
import app.dshmobile.DshMobileApp
import app.dshmobile.R
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
import app.dshmobile.update.ApkInstaller
import app.dshmobile.update.ApkUpdateChecker
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWebView
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
    /** When set, the fake dsh no longer starts (e.g. the version a rollback switched to is broken). */
    @Volatile private var failLaunches = false

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
            patchProvider = { DshPatch.render(it) },
            filesDir = tmp, isRootfsInstalled = { true },
            launcher = { _, _ ->
                if (failLaunches) throw java.io.IOException("fake dsh does not start")
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
            patchProvider = { DshPatch.render(it) },
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

    // --- I-7: file inputs and JavaScript dialogs ---

    private class FakeChooserParams(private val intent: Intent?) : android.webkit.WebChromeClient.FileChooserParams() {
        override fun getMode() = MODE_OPEN
        override fun getAcceptTypes() = arrayOf("*/*")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent(): Intent = intent ?: throw android.content.ActivityNotFoundException("no picker")
    }

    private fun pickerIntent() = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")

    @Test fun aFileInputOpensTheSystemPickerAndGetsThePickedUri() {
        completeSetup()
        val activity = build().create().start().resume().get()
        val chrome = shadowOf(activity.webView).webChromeClient
        assertTrue(chrome is DshChromeClient)
        var answer: Array<android.net.Uri>? = arrayOf()
        var answers = 0
        assertTrue(chrome!!.onShowFileChooser(activity.webView, { answer = it; answers++ }, FakeChooserParams(pickerIntent())))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_GET_CONTENT, started.intent.action)
        val picked = android.net.Uri.parse("content://com.example.docs/document/42")
        shadowOf(activity).receiveResult(started.intent, android.app.Activity.RESULT_OK, Intent().setData(picked))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, answers)
        assertEquals(listOf(picked), answer!!.toList())
    }

    @Test fun aCancelledPickerAnswersNull() {
        completeSetup()
        val activity = build().create().start().resume().get()
        val chrome = shadowOf(activity.webView).webChromeClient!!
        var answer: Array<android.net.Uri>? = arrayOf()
        var answers = 0
        chrome.onShowFileChooser(activity.webView, { answer = it; answers++ }, FakeChooserParams(pickerIntent()))
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(started.intent, android.app.Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, answers)
        assertNull(answer)
    }

    @Test fun withoutAPickerTheFileInputIsAnsweredNullAtOnce() {
        completeSetup()
        val activity = build().create().start().resume().get()
        var answers = 0
        var answer: Array<android.net.Uri>? = arrayOf()
        shadowOf(activity.webView).webChromeClient!!.onShowFileChooser(activity.webView, { answer = it; answers++ }, FakeChooserParams(null))
        assertEquals(1, answers)
        assertNull(answer)
    }

    @Test fun javascriptDialogsUseTheWebViewsDefaultDialogs() {
        completeSetup()
        val activity = build().create().get()
        val chrome = shadowOf(activity.webView).webChromeClient!!
        // false = not handled here: the WebView shows its own dialog (without a chrome client they are silently dismissed).
        assertFalse(chrome.onJsAlert(activity.webView, "http://127.0.0.1/", "hi", null))
        assertFalse(chrome.onJsConfirm(activity.webView, "http://127.0.0.1/", "sure?", null))
        assertFalse(chrome.onJsPrompt(activity.webView, "http://127.0.0.1/", "name?", "", null))
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

    /**
     * A second start of the same dsh while its page is still loading (here a stop/start of the screen; in the rollback tests the
     * collector racing the explicit restart) must not claim Ready: Ready only follows onPageFinished. This used to flake
     * `rollbackAfterAGiveUpWithNothingToGoBackToWhileDshRunsShowsDsh` (Ready instead of Loading).
     */
    @Test fun aSecondStartOfTheSameDshBeforeThePageFinishedStaysLoading() {
        completeSetup()
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }
        idleFor(300)
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value)

        ctl.pause().stop().restart().start().resume() // same dsh, same launch URL, page not finished yet
        idleFor(500)
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value)
        assertEquals(launchUrl(0), shadowOf(activity.webView).lastLoadedUrl)

        shadowOf(activity.webView).webViewClient.onPageFinished(activity.webView, "http://127.0.0.1:45678/")
        assertEquals(MainUiState.Ready, activity.uiStateForTest.value)

        ctl.pause().stop().restart().start().resume() // back from the background with the page loaded: straight to Ready
        idleFor(500)
        assertEquals(MainUiState.Ready, activity.uiStateForTest.value)
    }

    /**
     * Follow-up 1 (lag): while dsh is shown, the Compose overlay is only the settings button in the top-right corner (no
     * full-screen layer composited over the WebView every frame); loading and error screens cover the screen again.
     */
    @Test fun whenReadyTheOverlayShrinksToTheSettingsButtonAndGrowsBackForLoading() {
        completeSetup()
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }
        val overlay = activity.overlayForTest
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, overlay.layoutParams.width)
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, overlay.layoutParams.height)

        shadowOf(activity.webView).webViewClient.onPageFinished(activity.webView, "http://127.0.0.1:45678/")
        idleFor(200)
        assertEquals(MainUiState.Ready, activity.uiStateForTest.value)
        val small = overlay.layoutParams as android.widget.FrameLayout.LayoutParams
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, small.width)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, small.height)
        assertEquals(android.view.Gravity.TOP or android.view.Gravity.END, small.gravity)

        controller!!.stop() // dsh gone: Loading covers the page again
        waitUntil { activity.uiStateForTest.value == MainUiState.Loading }
        idleFor(200)
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, overlay.layoutParams.width)
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, overlay.layoutParams.height)
    }

    /** Follow-up 1 (lag): the time from opening the screen to Ready goes to the error log (tag perf), once, without URL or token. */
    @Test fun theTimeToReadyIsLoggedOnceUnderPerf() {
        completeSetup()
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == launchUrl(0) }
        shadowOf(activity.webView).webViewClient.onPageFinished(activity.webView, "http://127.0.0.1:45678/")
        ctl.pause().stop().restart().start().resume() // Ready again from the background: not a new measurement
        idleFor(300)
        val lines = errorLogText().lines().filter { "[perf]" in it }
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines.single(), Regex("""\[perf] main screen ready \d+ ms after it opened \(process started \d+ ms before that\)""").containsMatchIn(lines.single()))
        assertFalse(lines.single().contains("127.0.0.1"))
        assertFalse(lines.single().contains("token"))
    }

    /** Follow-up 1 (lag): the WebView's renderer gets Android's "important" priority and stays hardware accelerated. */
    @Config(shadows = [RendererPriorityWebViewShadow::class]) // Robolectric's WebView drops the renderer priority
    @Test fun theWebViewRendererIsImportantAndHardwareAccelerated() {
        completeSetup()
        val activity = build().create().start().resume().get()
        assertEquals(WebView.RENDERER_PRIORITY_IMPORTANT, activity.webView.rendererRequestedPriority)
        assertTrue(activity.webView.rendererPriorityWaivedWhenNotVisible)
        assertTrue(activity.webView.layerType != android.view.View.LAYER_TYPE_SOFTWARE)
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertTrue("hardware acceleration is off for MainActivity", info.flags and android.content.pm.ActivityInfo.FLAG_HARDWARE_ACCELERATED != 0)
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

    // --- every rollback outcome after a give-up: the screen follows dsh's real state ---

    private fun confirmRollbackFromTheNotification(ctl: org.robolectric.android.controller.ActivityController<MainActivity>) {
        ctl.newIntent(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_ROLLBACK))
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    private fun waitForToast(res: Int, vararg args: Any) {
        val text = app.getString(res, *args)
        waitUntil { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == text }
    }

    /** Dsh runs: the give-up is over, the service supervises again and the page shows the running dsh's launch URL. */
    private fun assertShowsDsh(activity: MainActivity, url: String) {
        waitUntil { shadowOf(activity.webView).lastLoadedUrl == url }
        idleFor(300)
        assertTrue(controller!!.isRunning.value)
        assertFalse("the gave-up flag is cleared", app.serviceGaveUp.value)
        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService?.component?.className)
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value) // Ready only follows onPageFinished
    }

    /** Dsh is down: Error with Retry, the flag stays set, nothing starts the service or dsh. */
    private fun assertStaysOnError(activity: MainActivity, processCount: Int) {
        idleFor(1_000)
        assertFalse(controller!!.isRunning.value)
        assertTrue("the gave-up flag stays set", app.serviceGaveUp.value)
        assertEquals(MainUiState.Error, activity.uiStateForTest.value)
        assertNull("DshService must not be started", shadowOf(app as Application).nextStartedService)
        assertEquals(processCount, processes.size)
        assertEquals(launchUrl(0), shadowOf(activity.webView).lastLoadedUrl)
    }

    @Test fun rollbackAfterAGiveUpRolledBackAndRunningShowsTheNewDsh() {
        completeSetup()
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        val (ctl, activity) = giveUpThenReenter()

        confirmRollbackFromTheNotification(ctl)
        waitForToast(R.string.main_rollback_done, "0.2.0")

        assertEquals("0.2.0", currentOf(opt))
        assertShowsDsh(activity, launchUrl(1))
    }

    @Test fun rollbackAfterAGiveUpRolledBackButDshDiedStaysOnError() {
        completeSetup()
        val c = controller!!
        val (ctl, activity) = giveUpThenReenter()
        activity.rollbackStep = {
            val running = c.ensureRunning()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                c.stop() // the rolled-back dsh died right after it came up
                while (c.isRunning.value) Thread.sleep(10)
            }
            MainOps.RollbackOutcome.RolledBack("0.2.0", running)
        }

        confirmRollbackFromTheNotification(ctl)
        waitForToast(R.string.main_rollback_done, "0.2.0")

        assertStaysOnError(activity, processCount = 2)
    }

    @Test fun rollbackAfterAGiveUpWithNothingToGoBackToWhileDshRunsShowsDsh() {
        completeSetup()
        val c = controller!!
        versionsDir(previous = null, current = "0.2.1")
        val (ctl, activity) = giveUpThenReenter()
        kotlinx.coroutines.runBlocking { c.ensureRunning() } // e.g. a heartbeat brought dsh up; the give-up still shows
        idleFor(300)
        assertEquals(MainUiState.Error, activity.uiStateForTest.value)

        confirmRollbackFromTheNotification(ctl)
        waitForToast(R.string.main_rollback_none)

        assertShowsDsh(activity, launchUrl(1))
    }

    @Test fun rollbackAfterAGiveUpWithNothingToGoBackToWhileDshIsDownStaysOnError() {
        completeSetup()
        versionsDir(previous = null, current = "0.2.1")
        val (ctl, activity) = giveUpThenReenter()

        confirmRollbackFromTheNotification(ctl)
        waitForToast(R.string.main_rollback_none)

        assertStaysOnError(activity, processCount = 1)
    }

    @Test fun rollbackAfterAGiveUpThatFailedButLeftDshRunningShowsDsh() {
        completeSetup()
        val c = controller!!
        val (ctl, activity) = giveUpThenReenter()
        activity.rollbackStep = {
            c.ensureRunning() // the switch failed, the version that was current came up again
            MainOps.RollbackOutcome.Failed("could not switch to 0.2.0")
        }

        confirmRollbackFromTheNotification(ctl)
        waitForToast(R.string.main_rollback_failed)

        assertShowsDsh(activity, launchUrl(1))
    }

    @Test fun rollbackAfterAGiveUpThatFailedWithDshDownStaysOnError() {
        completeSetup()
        versionsDir(previous = "0.2.0", current = "0.2.1")
        val (ctl, activity) = giveUpThenReenter()
        failLaunches = true // the real MainOps: dsh does not start after the switch

        confirmRollbackFromTheNotification(ctl)
        waitForToast(R.string.main_rollback_failed)

        assertStaysOnError(activity, processCount = 1)
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

    // --- I-2: the main-screen rollback is an application job (SettingsJobs), not the activity's ---

    @Test fun aRollbackOutlivesTheActivityThatStartedIt() {
        completeSetup()
        val c = controller!!
        c.retain("test-service") // what the running DshService holds in the app
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        val ctl = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_ROLLBACK)).create().start().resume()
        waitUntil { c.isRunning.value }
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ctl.pause().stop().destroy() // the user leaves at once and the activity goes away mid-rollback

        waitUntil { currentOf(opt) == "0.2.0" && processes.size == 2 && c.isRunning.value }
        waitUntil { !app.settingsJobs.busy.value }
        idleFor(300)
        assertTrue("dsh runs on the version rolled back to", c.isRunning.value && processes.last().isAlive)
        waitForToast(R.string.main_rollback_done, "0.2.0")
    }

    @Test fun aServiceStartRefusedInTheBackgroundAfterARollbackIsLoggedNotACrash() {
        completeSetup()
        val c = controller!!
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        val (ctl, _) = giveUpThenReenter()
        confirmRollbackFromTheNotification(ctl)
        ctl.pause().stop() // the user switches apps while the rollback runs
        // Android 12+ refuses a foreground-service start from the background.
        app.startDshService = { throw android.app.ForegroundServiceStartNotAllowedException("app is in the background") }

        waitUntil { currentOf(opt) == "0.2.0" && processes.size == 2 }
        waitUntil { !app.settingsJobs.busy.value }
        idleFor(1_000) // any continuation on the main thread has run (a crash would surface here)
        assertTrue(errorLogText(), errorLogText().contains("could not start the service (ForegroundServiceStartNotAllowedException)"))
        assertFalse("the rollback ended the give-up", app.serviceGaveUp.value)
        assertEquals("0.2.0", currentOf(opt))
        c.stop()
    }

    @Test fun aRollbackIsRefusedWhileASettingsJobRuns() {
        completeSetup()
        val c = controller!!
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val activity = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_ROLLBACK)).create().start().resume().get()
            waitUntil { c.isRunning.value }
            assertTrue(app.settingsJobs.launch { gate.await() }) // e.g. an update started in Settings
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idleFor(1_000)
            assertEquals("nothing was switched while the other job runs", "0.2.1", currentOf(opt))
            assertEquals(1, processes.size)
            assertEquals(activity.getString(R.string.main_rollback_busy), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        } finally {
            gate.complete(Unit)
        }
        waitUntil { !app.settingsJobs.busy.value }
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

    // --- shell app update ---

    private val apkBytes = ByteArray(50_000) { (it * 7).toByte() }
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A releases "repo" on a MockWebServer: the newest release is v0.9.0+9 whose hash file says [hash]. */
    private fun serveRelease(
        server: okhttp3.mockwebserver.MockWebServer,
        hash: String,
        apkRequested: java.util.concurrent.CountDownLatch? = null,
        apkGate: java.util.concurrent.CountDownLatch? = null,
    ) {
        val base = server.url("/")
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                request.path == "/repos/o/r/releases/latest" -> okhttp3.mockwebserver.MockResponse().setBody(
                    """{"tag_name":"v0.9.0+9","assets":[{"name":"a.apk","browser_download_url":"${base}a.apk"},{"name":"a.apk.sha256","browser_download_url":"${base}a.apk.sha256"}]}""",
                )
                request.path == "/a.apk.sha256" -> okhttp3.mockwebserver.MockResponse().setBody("$hash  a.apk")
                request.path == "/a.apk" -> {
                    apkRequested?.countDown()
                    apkGate?.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(apkBytes))
                }
                else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
            }
        }
        val prefixes = listOf(base.toString())
        swap("updateChecker", ApkUpdateChecker(okhttp3.OkHttpClient(), "o/r", 1, base, prefixes))
        swap("apkInstaller", ApkInstaller(app, okhttp3.OkHttpClient(), prefixes))
    }

    @Test fun theInstallUpdateActionDownloadsVerifiesAndOpensTheInstaller() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, sha(apkBytes))
            val activity = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_INSTALL_UPDATE)).create().start().resume().get() // in the foreground: the installer may start
            waitUntil { shadowOf(activity).peekNextStartedActivity()?.action == Intent.ACTION_VIEW }
            val install = shadowOf(activity).nextStartedActivity
            assertEquals("application/vnd.android.package-archive", install.type)
            assertEquals("content", install.data!!.scheme)
            assertTrue(File(app.cacheDir, "update/dsh-mobile-9.apk").isFile)
            assertNull(activity.intent.getStringExtra(Notifier.EXTRA_ACTION))
        } finally {
            server.shutdown()
            File(app.cacheDir, "update").deleteRecursively()
        }
    }

    @Test fun aWrongHashNeverReachesTheInstallerAndLeavesNoApk() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, "0".repeat(64))
            val activity = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_INSTALL_UPDATE)).create().get()
            waitUntil { errorLogText().contains("[update]") }
            waitUntil { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == activity.getString(R.string.main_update_failed) }
            assertTrue(shadowOf(activity).nextStartedActivity == null)
            assertTrue(File(app.cacheDir, "update").list().orEmpty().isEmpty())
        } finally {
            server.shutdown()
            File(app.cacheDir, "update").deleteRecursively()
        }
    }

    private fun pendingFlag() = app.getSharedPreferences("dshm_update", Context.MODE_PRIVATE).getBoolean("pending_install", false)

    private fun installActionIntent() = Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_INSTALL_UPDATE)

    @Test fun withoutInstallPermissionTheSettingsPageOpensFirstAndNothingIsDownloaded() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, sha(apkBytes))
            val activity = build(installActionIntent()).create().get()
            waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
            val settings = shadowOf(activity).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
            assertEquals(android.net.Uri.parse("package:${app.packageName}"), settings.data)
            assertEquals("no release lookup and no download before the permission", 0, server.requestCount)
            assertTrue(pendingFlag())
            assertEquals(activity.getString(R.string.main_update_needs_permission), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertFalse(File(app.cacheDir, "update").exists() && File(app.cacheDir, "update").list().orEmpty().isNotEmpty())
        } finally {
            server.shutdown()
            app.getSharedPreferences("dshm_update", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun resumingWithTheGrantContinuesTheUpdateAndClearsTheFlag() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, sha(apkBytes))
            val ctl = build(installActionIntent()).create().start().resume()
            val activity = ctl.get()
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
            assertEquals(0, server.requestCount)
            // The user allows installs in Settings and comes back.
            shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
            ctl.pause().resume()
            waitUntil { shadowOf(activity).peekNextStartedActivity()?.action == Intent.ACTION_VIEW }
            val install = shadowOf(activity).nextStartedActivity
            assertEquals("application/vnd.android.package-archive", install.type)
            assertTrue(File(app.cacheDir, "update/dsh-mobile-9.apk").isFile)
            assertFalse(pendingFlag())
        } finally {
            server.shutdown()
            File(app.cacheDir, "update").deleteRecursively()
            app.getSharedPreferences("dshm_update", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun resumingWhileStillNotAllowedDoesNothingAndKeepsTheFlag() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, sha(apkBytes))
            val ctl = build(installActionIntent()).create().start().resume()
            val activity = ctl.get()
            shadowOf(activity).nextStartedActivity // the settings page
            ctl.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(200)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(0, server.requestCount)
            assertTrue(pendingFlag())
        } finally {
            server.shutdown()
            app.getSharedPreferences("dshm_update", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun aWrongHashAfterTheGrantStillNeverInstalls() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, "0".repeat(64))
            val ctl = build(installActionIntent()).create().start().resume()
            val activity = ctl.get()
            shadowOf(activity).nextStartedActivity
            shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
            ctl.pause().resume()
            waitUntil { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == activity.getString(R.string.main_update_failed) }
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(File(app.cacheDir, "update").list().orEmpty().isEmpty())
            assertFalse("a failed attempt must not loop on every resume", pendingFlag())
        } finally {
            server.shutdown()
            File(app.cacheDir, "update").deleteRecursively()
            app.getSharedPreferences("dshm_update", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun ifTheInstallerCannotBeLaunchedAReadyNoticeIsPostedWithTheSameAction() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, sha(apkBytes))
            // An installer whose hand-off fails (no activity can handle it).
            swap("apkInstaller", object : ApkInstaller(app, okhttp3.OkHttpClient(), listOf(server.url("/").toString())) {
                override fun install(apk: File) = throw android.content.ActivityNotFoundException("none")
            })
            val activity = build(installActionIntent()).create().start().resume().get()
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            waitUntil { shadowOf(manager).allNotifications.isNotEmpty() }
            val n = shadowOf(manager).allNotifications.single()
            assertEquals("install_update", shadowOf(n.contentIntent).savedIntent.getStringExtra("action"))
            assertTrue(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("已下載"))
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            server.shutdown()
            File(app.cacheDir, "update").deleteRecursively()
        }
    }

    /** I-6: Android silently blocks an activity start from the background, so a download that ends there goes to a notification. */
    @Test fun aDownloadThatEndsWhileTheAppIsInTheBackgroundPostsTheReadyNoticeInsteadOfTheInstaller() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        val requested = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            serveRelease(server, sha(apkBytes), apkRequested = requested, apkGate = gate)
            val ctl = build(installActionIntent()).create().start().resume()
            val activity = ctl.get()
            waitUntil { requested.count == 0L }
            ctl.pause().stop() // the user switches apps during the ~130 MB download
            gate.countDown()

            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            waitUntil { shadowOf(manager).allNotifications.isNotEmpty() }
            idleFor(300)
            val n = shadowOf(manager).allNotifications.single()
            assertEquals("install_update", shadowOf(n.contentIntent).savedIntent.getStringExtra("action"))
            assertTrue(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("已下載"))
            assertTrue("the verified APK stays for the notification's tap", File(app.cacheDir, "update/dsh-mobile-9.apk").isFile)
            assertNull("no installer is started from the background", shadowOf(activity).nextStartedActivity)
        } finally {
            gate.countDown()
            server.shutdown()
            File(app.cacheDir, "update").deleteRecursively()
        }
    }

    @Test fun whenThereIsNoNewerReleaseItSaysSoAndInstallsNothing() {
        completeSetup()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            serveRelease(server, sha(apkBytes))
            swap("updateChecker", ApkUpdateChecker(okhttp3.OkHttpClient(), "o/r", 9, server.url("/"), listOf(server.url("/").toString())))
            val activity = build(Intent().putExtra(Notifier.EXTRA_ACTION, Notifier.ACTION_INSTALL_UPDATE)).create().get()
            waitUntil { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == activity.getString(R.string.main_update_none) }
            assertTrue(shadowOf(activity).nextStartedActivity == null)
        } finally {
            server.shutdown()
        }
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

/** Robolectric's ShadowWebView plus the renderer priority, which the stock shadow silently drops. */
@Implements(WebView::class)
class RendererPriorityWebViewShadow : ShadowWebView() {
    private var priority = WebView.RENDERER_PRIORITY_WAIVED
    private var waived = false

    @Implementation
    fun setRendererPriorityPolicy(rendererRequestedPriority: Int, waivedWhenNotVisible: Boolean) {
        priority = rendererRequestedPriority
        waived = waivedWhenNotVisible
    }

    @Implementation
    fun getRendererRequestedPriority(): Int = priority

    @Implementation
    fun getRendererPriorityWaivedWhenNotVisible(): Boolean = waived
}
