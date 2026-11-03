package app.dshmobile.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.dshmobile.ui.shell.Tags
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
import app.dshmobile.runtime.FakeDshServer
import app.dshmobile.runtime.FakeMux
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.buildSettingsEnv
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("maintest").toFile()
    private val processes = java.util.concurrent.CopyOnWriteArrayList<Process>()
    private var controller: DshController? = null
    /** When set, the fake dsh no longer starts (e.g. the version a rollback switched to is broken). */
    @Volatile private var failLaunches = false
    /** One fake dsh (HTTP + mux) per start, in start order; the n-th start prints servers[n]'s launch URL. */
    private val servers = java.util.concurrent.CopyOnWriteArrayList<FakeDshServer>()
    private val muxes = java.util.concurrent.CopyOnWriteArrayList<FakeMux>()
    /** Every RPC body the fake dshes received (e.g. the `${'$'}events/result` answers). */
    private val rpcBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    /** The next fake dsh's mux holds its upgrade until this completes (the shell stays Loading). */
    @Volatile private var nextUpgradeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** dsh's RPC for every fake dsh: `{"ok":true}` unless a test answers otherwise (the chat tests answer more). */
    @Volatile private var rpc: (okhttp3.mockwebserver.RecordedRequest) -> okhttp3.mockwebserver.MockResponse = {
        okhttp3.mockwebserver.MockResponse().setResponseCode(200)
            .setBody("""{"type":"server-response","rpcId":"x","result":{"ok":true}}""")
    }

    @Before fun setUp() {
        // FileProvider caches its path strategy statically; every Robolectric test has a fresh cache dir.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null).let { @Suppress("UNCHECKED_CAST") (it as MutableMap<String, Any>).clear() }
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() {
        // End the activities first: their collectors and timers must not outlive the test.
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        app.shellSessions.detach()
        controller?.stop()
        muxes.forEach { it.upgradeGate?.complete(Unit) }
        servers.forEach { it.close() }
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
                // every start is a new fake dsh with its own port and launch URL, so a restart is visible in the shell
                val mux = FakeMux().also { m -> nextUpgradeGate?.let { m.upgradeGate = it; nextUpgradeGate = null } }
                val server = FakeDshServer(rpc = { req -> rpcBodies.add(req.body.clone().readUtf8()); rpc(req) }, mux = mux)
                muxes.add(mux)
                servers.add(server)
                val url = server.launch.url
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

    /** The launch URL the n-th fake dsh printed. */
    private fun launchUrl(n: Int): String = servers[n].launch.url

    /** The launch URL the shell's current session talks to (null: none attached). */
    private fun attachedUrl(): String? = app.shellSessions.current.value?.let { app.shellSessions.launch?.url }

    /** The shell is attached to the n-th fake dsh and its remote is open. */
    private fun waitForShellOn(n: Int, activity: MainActivity) {
        waitUntil { servers.size > n && attachedUrl() == launchUrl(n) && activity.uiStateForTest.value == MainUiState.Ready }
    }

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

    @Test fun startRetainsUiStartsTheServiceAndAttachesTheShell() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        assertTrue("ui" in c.holders)
        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService.component!!.className)
        waitUntil { servers.isNotEmpty() && attachedUrl() == launchUrl(0) }
        assertEquals(launchUrl(0), app.shellSessions.launch!!.url)
        waitForShellOn(0, activity)
        assertTrue(c.isRunning.value)
        assertEquals(MuxState.OPEN, app.shellSessions.current.value!!.remote.muxState.value)
    }

    @Test fun readyOnlyAfterTheRemoteIsOpen() {
        completeSetup()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        nextUpgradeGate = gate
        val activity = build().create().start().resume().get()
        waitUntil { muxes.isNotEmpty() && muxes[0].upgrades.get() > 0 }
        idleFor(500)
        assertEquals(launchUrl(0), attachedUrl())
        assertEquals("dsh runs but the remote is not open yet", MainUiState.Loading, activity.uiStateForTest.value)
        gate.complete(Unit)
        waitUntil { activity.uiStateForTest.value == MainUiState.Ready }
    }

    @Test fun aRemoteThatNeverOpensEndsInAnErrorWithTheReason() {
        completeSetup()
        nextUpgradeGate = kotlinx.coroutines.CompletableDeferred()
        val ctl = build().create()
        val activity = ctl.get()
        activity.remoteOpenTimeoutMs = 1_000
        ctl.start().resume()
        waitUntil { muxes.isNotEmpty() && muxes[0].upgrades.get() > 0 }
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value)
        // The timeout runs on the main looper, whose clock Robolectric only moves when told to.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_100))
        waitUntil { activity.uiStateForTest.value == MainUiState.Error }
        val text = errorLogText()
        assertTrue(text, text.contains("[remote] no connection (TimeoutCancellationException)"))
        assertFalse(text.contains("token="))
    }

    @Test fun noWebViewAnywhereInTheContentTree() {
        completeSetup()
        val activity = build().create().start().resume().get()
        waitForShellOn(0, activity)
        val found = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v is android.webkit.WebView) found += v.javaClass.name
            if (v is android.view.ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(activity.window.decorView)
        assertEquals(emptyList<String>(), found)
    }

    @Test fun theErrorLogNeverHoldsTheLaunchUrl() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitForShellOn(0, activity)
        c.stop() // and a second instance, so every path that logs has run
        waitUntil { !c.isRunning.value }
        kotlinx.coroutines.runBlocking { c.ensureRunning() }
        waitForShellOn(1, activity)
        val text = errorLogText()
        assertTrue("the perf line is there", text.contains("[perf]"))
        assertFalse(text, text.contains("token="))
        servers.forEach { assertFalse(text.contains(it.launch.url)) }
    }

    /** The ten-minute grace or a background give-up stops dsh with no screen started: the session must not keep retrying. */
    @Test fun aDshStoppedWhileNoScreenIsStartedDetachesTheSession() {
        completeSetup()
        val c = controller!!
        val ctl = build().create().start().resume()
        waitForShellOn(0, ctl.get())
        val session = app.shellSessions.current.value!!
        ctl.pause().stop() // the screen's own collector stops with it
        c.stop()
        waitUntil { !c.isRunning.value }
        waitUntil { app.shellSessions.current.value == null }
        assertEquals(MuxState.CLOSED, session.remote.muxState.value)
        val upgrades = muxes[0].upgrades.get()
        idleFor(1_500) // past the first reconnect backoffs: nothing tries the dead port again
        assertEquals(upgrades, muxes[0].upgrades.get())
    }

    @Test fun settingsCannotBeLeftWhileItsActionRunsAndSaysWhy() {
        completeSetup()
        val activity = build().create().start().resume().get()
        activity.navForTest.select(ShellTab.SETTINGS)
        idleFor(300)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            assertTrue(app.settingsJobs.launch { gate.await() }) // e.g. "apply and restart" running
            activity.navForTest.select(ShellTab.OVERVIEW)
            assertEquals(ShellTab.SETTINGS, activity.navForTest.tab)
            assertEquals(activity.getString(R.string.shell_settings_busy), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        } finally {
            gate.complete(Unit)
        }
        waitUntil { !app.settingsJobs.busy.value }
        activity.navForTest.select(ShellTab.OVERVIEW)
        assertEquals(ShellTab.OVERVIEW, activity.navForTest.tab)
    }

    // --- effects: live level, jank governor, paused animation ---
    // What the shell draws for a level (blur, animation, paused when stopped) is pinned in FogShellEffectsTest with compose's own
    // rule: a Robolectric activity has no composition until its window is shown, and showing it makes every idle() render frames.

    private fun feedWindows(activity: MainActivity, windows: Int, janky: Int) {
        repeat(30) { activity.governorForTest.onFrame(true) } // the warm-up after a start: ignored, not counted
        feedCounted(activity, windows, janky)
    }

    private fun feedCounted(activity: MainActivity, windows: Int, janky: Int) =
        repeat(windows * 120) { activity.governorForTest.onFrame(it % 120 < janky) }

    @Test fun degradingStepsTheLevelTheShellDrawsWithDown() {
        completeSetup()
        val activity = build().create().start().resume().get()
        waitForShellOn(0, activity)
        feedWindows(activity, windows = 2, janky = 24) // 240 frames, 24 janky each window: FULL -> BALANCED
        assertEquals(EffectLevel.BALANCED, app.effectsController.effective.value)
        assertEquals("the user's own choice stays", EffectLevel.FULL, app.effectsController.level.value)
        assertTrue(errorLogText(), errorLogText().contains("[perf] effects capped at BALANCED after 48 janky of 240 frames"))
    }

    @Test fun effectStrengthFromSettingsAppliesWithoutRestart() {
        completeSetup()
        val activity = build().create().start().resume().get()
        waitForShellOn(0, activity)
        // What the settings page's "effect strength" calls (Task 13 builds the control on it).
        buildSettingsEnv(activity, app, onEffectLevel = app.effectsController::set).onEffectLevel(EffectLevel.SAVER)
        assertEquals(EffectLevel.SAVER, app.effectsController.effective.value)
        assertEquals(EffectLevel.SAVER, app.uiPrefs.effectLevel)
        idleFor(300)
        assertEquals("dsh was not restarted", 1, servers.size)
        assertEquals(launchUrl(0), attachedUrl())
        assertEquals(MainUiState.Ready, activity.uiStateForTest.value)
    }

    @Test fun perfSummaryIsLoggedOnStopWithoutContent() {
        completeSetup()
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        waitForShellOn(0, activity)
        activity.governorForTest.autoDegrade = false
        feedWindows(activity, windows = 2, janky = 12) // 240 frames, 24 janky
        ctl.pause().stop()
        val text = errorLogText()
        assertTrue(text, text.contains("[perf] frames=240 janky=24 (10.0%) level=FULL"))
        assertTrue("only counts and the level, no content", text.lines().filter { it.contains("[perf] frames=") }.all { Regex("""\[perf\] frames=\d+ janky=\d+ \(\d+\.\d%\) level=[A-Z]+""").containsMatchIn(it) && !it.contains("conversation") })
        // A stop with no frames since its start logs nothing more.
        val lines = text.lines().count { it.contains("[perf] frames=") }
        ctl.start().resume().pause().stop()
        assertEquals(lines, errorLogText().lines().count { it.contains("[perf] frames=") })
    }

    @Test fun backFirstClosesShellNavigation() {
        completeSetup()
        val activity = build().create().start().resume().get()
        activity.navForTest.openSession("s1")
        assertEquals(ShellTab.CHAT, activity.navForTest.tab)
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull(activity.navForTest.openSessionId)
        assertFalse(activity.isFinishing)
        activity.onBackPressedDispatcher.onBackPressed() // then back to the first tab
        assertEquals(ShellTab.OVERVIEW, activity.navForTest.tab)
        assertFalse(activity.isFinishing)
    }

    // --- permission and question cards ---

    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun sendOnEvents(n: Int, json: String) {
        waitUntil { muxes.size > n && muxes[n].opened("\$events").isNotEmpty() }
        muxes[n].sendItem(muxes[n].opened("\$events").last(), json)
    }

    @Test fun anApprovalShowsOverAnyTabAndIsAnsweredWithTheClientId() {
        completeSetup()
        val ctl = build().create().start().resume().visible()
        val activity = ctl.get()
        waitForShellOn(0, activity)
        activity.navForTest.select(ShellTab.SETTINGS)
        sendOnEvents(0, """{"type":"ready","clientId":"c-main","host":{"home":"/root"}}""")
        sendOnEvents(0, """{"type":"waterfall","event":"approval/request","eventId":"e1","agentId":"session-a","request":{"toolName":"write","callId":"t1","reason":"escalate"}}""")
        waitUntil { hasTag(Tags.PROMPT_SHEET) }
        assertEquals(ShellTab.SETTINGS, activity.navForTest.tab)
        compose.onNodeWithTag(Tags.APPROVE_ONCE).performClick()
        waitUntil { rpcBodies.any { it.contains("allowed-once") } }
        val body = kotlinx.serialization.json.Json.parseToJsonElement(rpcBodies.last { it.contains("allowed-once") })
        val args = (((body as kotlinx.serialization.json.JsonObject)["payload"] as kotlinx.serialization.json.JsonObject)["args"]) as kotlinx.serialization.json.JsonObject
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"clientId":"c-main","eventId":"e1","outcome":{"kind":"result","value":"allowed-once"}}"""),
            args,
        )
        waitUntil { !hasTag(Tags.PROMPT_SHEET) }
    }

    @Test fun aSubagentApprovalNamesTheSubagent() {
        completeSetup()
        val ctl = build().create().start().resume().visible()
        val activity = ctl.get()
        waitForShellOn(0, activity)
        sendOnEvents(0, """{"type":"ready","clientId":"c-main","host":{"home":"/root"}}""")
        sendOnEvents(0, """{"type":"emit","event":"api-session/added","args":[{"sessionId":"s1","updatedAt":1,"running":true,"projections":{"values":{"title":"天氣"}}}]}""")
        sendOnEvents(0, """{"type":"emit","event":"api-session/added","args":[{"sessionId":"c1","parentSessionId":"s1","origin":"subagent","updatedAt":2,"running":true,"projections":{"values":{"subagent":{"label":"查資料"}}}}]}""")
        waitUntil { app.shellSessions.current.value?.tree?.state?.value?.summaries?.containsKey("c1") == true }
        sendOnEvents(0, """{"type":"waterfall","event":"approval/request","eventId":"e1","agentId":"c1","request":{"toolName":"write","callId":"t1","reason":"escalate"}}""")
        waitUntil { hasTag(Tags.PROMPT_SHEET) }
        compose.onNodeWithText("來自：子代理人「查資料」（來自 天氣）", substring = true).assertExists()
    }

    @Test fun backDoesNotDismissAPendingPrompt() {
        completeSetup()
        val ctl = build().create().start().resume().visible()
        val activity = ctl.get()
        waitForShellOn(0, activity)
        sendOnEvents(0, """{"type":"ready","clientId":"c-main","host":{"home":"/root"}}""")
        sendOnEvents(0, """{"type":"waterfall","event":"approval/request","eventId":"e1","agentId":"session-a","request":{"toolName":"write","callId":"t1","reason":"escalate"}}""")
        waitUntil { hasTag(Tags.PROMPT_SHEET) }
        activity.onBackPressedDispatcher.onBackPressed()
        idleFor(300)
        assertTrue(hasTag(Tags.PROMPT_SHEET))
        assertFalse(activity.isFinishing)
        assertTrue(rpcBodies.none { it.contains("allowed-once") || it.contains("rejected") })
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
        waitForShellOn(0, activity)
        assertEquals(1, processes.size)

        c.stop() // what DshService does on GIVE_UP (or anyone on a deliberate stop)
        waitUntil { !c.isRunning.value }
        idleFor(1_500)

        assertFalse(c.isRunning.value)
        assertEquals("no new dsh was started", 1, processes.size)
        assertNull("the shell let go of the stopped dsh", app.shellSessions.current.value)
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value)
    }

    @Test fun afterTheServiceGaveUpTheActivityShowsTheErrorScreenAndStaysStopped() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitForShellOn(0, activity)

        app.serviceGaveUp.value = true // DshService sets this before its controller.stop() on GIVE_UP
        c.stop()
        waitUntil { activity.uiStateForTest.value == MainUiState.Error }
        idleFor(1_000)

        assertEquals(1, processes.size)
        assertFalse(c.isRunning.value)
        assertEquals(MainUiState.Error, activity.uiStateForTest.value)
        assertNull(app.shellSessions.current.value)
    }

    @Test fun retryAfterAGiveUpClearsTheFlagAndStartsTheServiceAgain() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitForShellOn(0, activity)
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
        waitForShellOn(0, activity)
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
        waitForShellOn(0, activity)
        assertTrue(c.isRunning.value)
        assertTrue("ui" in c.holders)
    }

    /**
     * A second start of the same dsh while its remote is still connecting (a stop/start of the screen; in the rollback tests the
     * collector racing the explicit restart) must neither claim Ready nor make a new session: Ready only follows the open mux.
     */
    @Test fun aSecondStartOfTheSameDshBeforeTheRemoteOpensStaysLoading() {
        completeSetup()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        nextUpgradeGate = gate
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        waitUntil { servers.isNotEmpty() && attachedUrl() == launchUrl(0) }
        val session = app.shellSessions.current.value
        idleFor(300)
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value)

        ctl.pause().stop().restart().start().resume() // same dsh, same launch URL, remote not open yet
        idleFor(500)
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value)
        assertTrue("the same session is kept", session === app.shellSessions.current.value)

        gate.complete(Unit)
        waitUntil { activity.uiStateForTest.value == MainUiState.Ready }

        ctl.pause().stop().restart().start().resume() // back from the background with the remote open: straight to Ready
        idleFor(500)
        assertEquals(MainUiState.Ready, activity.uiStateForTest.value)
        assertTrue(session === app.shellSessions.current.value)
    }

    /** Follow-up 1 (lag): the time from opening the screen to Ready goes to the error log (tag perf), once, without URL or token. */
    @Test fun theTimeToReadyIsLoggedOnceUnderPerf() {
        completeSetup()
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        waitForShellOn(0, activity)
        ctl.pause().stop().restart().start().resume() // Ready again from the background: not a new measurement
        idleFor(300)
        val lines = errorLogText().lines().filter { "[perf]" in it }
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines.single(), Regex("""\[perf] main screen ready \d+ ms after it opened \(process started \d+ ms before that\)""").containsMatchIn(lines.single()))
        assertFalse(lines.single().contains("127.0.0.1"))
        assertFalse(lines.single().contains("token"))
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
        waitForShellOn(1, activity)
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

    /** Dsh runs: the give-up is over, the service supervises again and the shell is attached to the n-th dsh and Ready. */
    private fun assertShowsDsh(activity: MainActivity, n: Int) {
        waitForShellOn(n, activity)
        idleFor(300)
        assertTrue(controller!!.isRunning.value)
        assertFalse("the gave-up flag is cleared", app.serviceGaveUp.value)
        assertEquals(DshService::class.java.name, shadowOf(app as Application).nextStartedService?.component?.className)
        assertEquals(MainUiState.Ready, activity.uiStateForTest.value)
    }

    /** Dsh is down: Error with Retry, the flag stays set, nothing starts the service or dsh. */
    private fun assertStaysOnError(activity: MainActivity, processCount: Int) {
        idleFor(1_000)
        assertFalse(controller!!.isRunning.value)
        assertTrue("the gave-up flag stays set", app.serviceGaveUp.value)
        assertEquals(MainUiState.Error, activity.uiStateForTest.value)
        assertNull("DshService must not be started", shadowOf(app as Application).nextStartedService)
        assertEquals(processCount, processes.size)
        assertNull("no shell session on a stopped dsh", app.shellSessions.current.value)
    }

    @Test fun rollbackAfterAGiveUpRolledBackAndRunningShowsTheNewDsh() {
        completeSetup()
        val opt = versionsDir(previous = "0.2.0", current = "0.2.1")
        val (ctl, activity) = giveUpThenReenter()

        confirmRollbackFromTheNotification(ctl)
        waitForToast(R.string.main_rollback_done, "0.2.0")

        assertEquals("0.2.0", currentOf(opt))
        assertShowsDsh(activity, 1)
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

        assertShowsDsh(activity, 1)
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

        assertShowsDsh(activity, 1)
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

    @Test fun aNewDshInstanceReattachesTheShellToItsLaunchUrl() {
        completeSetup()
        val c = controller!!
        val activity = build().create().start().resume().get()
        waitForShellOn(0, activity)
        val first = app.shellSessions.current.value!!

        c.stop()
        waitUntil { !c.isRunning.value }
        waitUntil { app.shellSessions.current.value == null && activity.uiStateForTest.value == MainUiState.Loading }
        assertEquals("the old session's remote is closed", MuxState.CLOSED, first.remote.muxState.value)
        kotlinx.coroutines.runBlocking { c.ensureRunning() } // e.g. the service's delayed restart after a crash
        waitForShellOn(1, activity)

        assertEquals(2, processes.size)
        assertTrue(first !== app.shellSessions.current.value)
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

    // --- the chat page (Task 8) ---

    /** RPC calls the fake dsh received, as (path, body). */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    private fun rpcOk(value: String) = okhttp3.mockwebserver.MockResponse().setResponseCode(200)
        .setBody("""{"type":"server-response","rpcId":"x","result":{"ok":true,"value":$value}}""")

    /** A dsh with one conversation "s1"; creating one answers "new1"; prompts, cancels and the rest succeed. */
    private fun answerChatRpc() {
        rpc = { req ->
            val path = req.path.orEmpty()
            calls += path to req.body.clone().readUtf8()
            when (path) {
                "/api/session/list" -> rpcOk("""{"items":[{"sessionId":"s1","updatedAt":${System.currentTimeMillis()},"projections":{"values":{"title":"霧城"}}}]}""")
                "/api/session/create" -> rpcOk("""{"sessionId":"new1"}""")
                else -> rpcOk("null")
            }
        }
    }

    private fun called(method: String) = calls.filter { it.first == "/api/$method" }

    private fun type(text: String) {
        compose.onNodeWithTag(Tags.COMPOSER_INPUT).performTextInput(text)
        compose.waitForIdle()
    }

    @Test fun sendingFromAnotherTabSwitchesToChatAndPrompts() {
        completeSetup()
        answerChatRpc()
        val activity = build().create().start().resume().visible().get()
        waitForShellOn(0, activity)
        val nav = activity.navForTest
        nav.openSession("s1")
        nav.select(ShellTab.OVERVIEW)
        type("hi")
        compose.onNodeWithTag(Tags.SEND).performClick()
        waitUntil { called("session/prompt").isNotEmpty() }
        assertEquals(ShellTab.CHAT, nav.tab)
        val body = called("session/prompt").single().second
        assertTrue(body, body.contains("\"sessionId\":\"s1\"") && body.contains("\"hi\""))
        assertTrue("nothing was created", called("session/create").isEmpty())
        val input = compose.onNodeWithTag(Tags.COMPOSER_INPUT).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
        assertEquals("the input is empty again", "", input.text)
    }

    @Test fun withoutAnOpenConversationSendingCreatesOne() {
        completeSetup()
        answerChatRpc()
        val activity = build().create().start().resume().visible().get()
        waitForShellOn(0, activity)
        assertNull(activity.navForTest.openSessionId)
        type("第一句")
        compose.onNodeWithTag(Tags.SEND).performClick()
        waitUntil { called("session/prompt").isNotEmpty() }
        val order = calls.map { it.first }.filter { it == "/api/session/create" || it == "/api/session/prompt" }
        assertEquals(listOf("/api/session/create", "/api/session/prompt"), order)
        assertTrue(called("session/prompt").single().second.contains("\"sessionId\":\"new1\""))
        assertEquals("new1", activity.navForTest.openSessionId)
        assertEquals(ShellTab.CHAT, activity.navForTest.tab)
        activities.last().pause().stop()
        assertEquals("remembered for the next start", "new1", app.uiPrefs.lastSessionId)
    }

    @Test fun aDeepLinkOpensThatConversation() {
        completeSetup()
        val ctl = build(Intent().putExtra(Notifier.EXTRA_SESSION_ID, "s1")).create()
        val activity = ctl.get()
        assertFalse(activity.isFinishing)
        assertEquals("s1", activity.navForTest.openSessionId)
        assertEquals(ShellTab.CHAT, activity.navForTest.tab)
        assertFalse("nothing is logged for it any more", errorLogText().contains("[deeplink]"))
        assertNull(activity.intent.getStringExtra(Notifier.EXTRA_SESSION_ID))
        // And from a notification tapped while the screen is open.
        activity.navForTest.select(ShellTab.SETTINGS)
        ctl.newIntent(Intent().putExtra(Notifier.EXTRA_SESSION_ID, "s2"))
        assertEquals("s2", activity.navForTest.openSessionId)
        assertEquals(ShellTab.CHAT, activity.navForTest.tab)
    }

    /** M1: a deep link asks the same as a tab tap: while settings is busy it does nothing (settings says why). */
    @Test fun aDeepLinkWaitsWhileSettingsIsBusy() {
        completeSetup()
        val ctl = build().create().start().resume()
        val activity = ctl.get()
        activity.navForTest.select(ShellTab.SETTINGS)
        idleFor(300)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            assertTrue(app.settingsJobs.launch { gate.await() })
            ctl.newIntent(Intent().putExtra(Notifier.EXTRA_SESSION_ID, "s2"))
            assertEquals(ShellTab.SETTINGS, activity.navForTest.tab)
            assertFalse(activity.navForTest.openSessionId == "s2")
            assertEquals(activity.getString(R.string.shell_settings_busy), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        } finally {
            gate.complete(Unit)
        }
    }

    @Test fun stopShowsWhileRunningWithAnEmptyInput() {
        completeSetup()
        answerChatRpc()
        val activity = build().create().start().resume().visible().get()
        waitForShellOn(0, activity)
        activity.navForTest.openSession("s1")
        compose.waitForIdle()
        waitUntil { muxes[0].opened("session/follow").isNotEmpty() }
        compose.onNodeWithTag(Tags.SEND).assertExists()
        val follow = muxes[0].opened("session/follow").last()
        muxes[0].sendItem(follow, """{"type":"snapshot","header":{"version":4},"cursor":2,"records":[{"type":"event","event":{"type":"turn/start","seq":2,"time":1,"data":{}}}],"hasMore":false}""")
        waitUntil { compose.onAllNodesWithTag(Tags.STOP).fetchSemanticsNodes().isNotEmpty() }
        type("下一句") // with text it is a send again (queued behind the running turn)
        compose.onNodeWithTag(Tags.SEND).assertIsEnabled()
        compose.onNodeWithTag(Tags.COMPOSER_INPUT).performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.STOP).performClick()
        waitUntil { called("session/cancel").isNotEmpty() }
        assertTrue(called("session/cancel").single().second.contains("\"sessionId\":\"s1\""))
        assertTrue(called("session/prompt").isEmpty())
    }

    @Test fun sendIsDisabledWhileDshIsNotReady() {
        completeSetup()
        answerChatRpc()
        nextUpgradeGate = kotlinx.coroutines.CompletableDeferred()
        val activity = build().create().start().resume().visible().get()
        waitUntil { muxes.isNotEmpty() && muxes[0].upgrades.get() > 0 }
        assertEquals(MainUiState.Loading, activity.uiStateForTest.value)
        runCatching { compose.onNodeWithTag(Tags.COMPOSER_INPUT).performTextInput("hi") }
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(Tags.COMPOSER_HINT, useUnmergedTree = true).assertIsDisplayed()
        runCatching { compose.onNodeWithTag(Tags.SEND).performClick() }
        idleFor(300)
        assertTrue(called("session/prompt").isEmpty())
    }

    @Test fun theModelChipShowsTheNextModelBeforeItIsUsed() {
        completeSetup()
        answerChatRpc()
        val activity = build().create().start().resume().visible().get()
        waitForShellOn(0, activity)
        activity.navForTest.openSession("s1")
        waitUntil { muxes[0].opened("session/control").isNotEmpty() && called("session/list").isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.MODEL_CHIP).assertTextEquals("DEEPSEEK FLASH") // nothing chosen yet: the lead model
        muxes[0].sendItem(
            muxes[0].opened("session/control").last(),
            """{"type":"projection","sessionId":"s1","key":"modelSelection","value":{"lastUsed":{"provider":"deepseek-official","model":"deepseek-flash"},"next":{"provider":"deepseek-official","model":"deepseek-v4-pro"}},"seq":5}""",
        )
        waitUntil { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(Tags.MODEL_CHIP) and androidx.compose.ui.test.hasText("DEEPSEEK PRO")).fetchSemanticsNodes().isNotEmpty() }
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
}
