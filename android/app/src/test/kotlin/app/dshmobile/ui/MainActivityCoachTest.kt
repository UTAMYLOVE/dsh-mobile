package app.dshmobile.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.BuildConfig
import app.dshmobile.DshMobileApp
import app.dshmobile.coach.CoachEngine
import app.dshmobile.coach.CoachModel
import app.dshmobile.coach.ModelReply
import app.dshmobile.coach.Turn
import app.dshmobile.config.DshPatch
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.DesktopSignals
import app.dshmobile.runtime.RootfsInstaller
import app.dshmobile.security.KeyVault
import app.dshmobile.service.DshController
import app.dshmobile.system.OnboardingState
import app.dshmobile.ui.shell.Tags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Ruling Q1: a fresh start opens the coach (the chat-shell tests turn that off in their setup). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class MainActivityCoachTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("coach-main").toFile()
    private var main: ActivityController<MainActivity>? = null
    /** The coach's model: answers the parse call once [gate] completes. */
    private val gate = CompletableDeferred<Unit>()

    private fun swap(field: String, value: Any) {
        DshMobileApp::class.java.getDeclaredField("$field\$delegate").apply { isAccessible = true }.set(app, lazyOf(value))
    }

    private fun launch(): MainActivity {
        val state = OnboardingState(app.getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE))
        OnboardingState.Step.entries.forEach { state.markDone(it) }
        File(app.filesDir, "rootfs/${RootfsInstaller.VERSION_MARKER}").apply { parentFile!!.mkdirs() }.writeText(BuildConfig.ROOTFS_VERSION)
        val noKeys = object : KeyVault {
            override fun get(p: Provider): String? = null
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        }
        // A key is set (otherwise the app sends the owner back to onboarding); dsh itself never starts here.
        swap("keyVault", object : KeyVault {
            override fun get(p: Provider): String? = "sk-test-key-0123456789"
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        })
        File(tmp, "rootfs/etc").mkdirs()
        swap(
            "controller",
            DshController(
                patchProvider = { DshPatch.render(it) }, filesDir = tmp, isRootfsInstalled = { true },
                launcher = { _, _ -> throw java.io.IOException("no dsh in this test") },
                vault = noKeys, meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
                errorLog = ErrorLog(tmp), healthCheck = { true }, dnsServers = { emptyList() },
                onNotify = {}, onCostAlert = {}, onRateGuard = {}, launchTimeoutMs = 10_000,
                killer = DesktopSignals.killer(listOf("dshm-coach-none-${System.nanoTime()}")),
            ),
        )
        // The app warms the coach at start, so the engine itself is replaced (with a model that waits for [gate]).
        val model = object : CoachModel {
            override suspend fun complete(system: String, turns: List<Turn>, maxTokens: Int): ModelReply {
                gate.await()
                return ModelReply.Ok("""{"kind":"chat"}""")
            }
        }
        swap("coachModel", model)
        swap(
            "coach",
            CoachEngine(
                app.coachStore, app.coachSettings, model, java.time.Clock.systemUTC(), java.time.ZoneId.of("Asia/Taipei"),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ),
        )
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent().setComponent(ComponentName(app, MainActivity::class.java)))
        main = c
        val activity = c.setup().get()
        rule.waitForIdle()
        return activity
    }

    @After fun tearDown() {
        gate.complete(Unit)
        main?.let { runCatching { it.pause().stop().destroy() } }
        app.shellSessions.detach()
        tmp.deleteRecursively()
    }

    @Test fun aFreshStartOpensTheCoachAndFreeChatLeadsToTheChat() {
        val activity = launch()
        rule.onNodeWithTag(Tags.COACH_PAGE).assertExists()
        rule.onNodeWithTag(Tags.BAR_FREE_CHAT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.COACH_PAGE).assertDoesNotExist()
        val vm = ViewModelProvider(activity)[MainViewModel::class.java]
        assertFalse(vm.nav.coach)
        rule.onNodeWithTag(Tags.BAR_COACH).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.COACH_PAGE).assertExists()
    }

    @Test fun withTheSwitchOffTheChatOpensAndTheCoachIsStillThere() {
        app.coachSettings.openOnCoach = false
        launch()
        rule.onNodeWithTag(Tags.COACH_PAGE).assertDoesNotExist()
        rule.onNodeWithTag(Tags.BAR_COACH).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.COACH_PAGE).assertExists()
    }

    // M5: the coach has its own draft, and no stop button while it thinks.
    @Test fun theCoachHasItsOwnDraftAndNoDeadStopButton() {
        val activity = launch()
        val vm = ViewModelProvider(activity)[MainViewModel::class.java]
        rule.runOnIdle { vm.onText("給聊天的草稿") }
        rule.onNodeWithTag(Tags.COMPOSER_INPUT).performTextInput("嗨教練")
        rule.waitForIdle()
        assertEquals("嗨教練", vm.coachText)
        assertEquals("給聊天的草稿", vm.composerText)
        rule.onNodeWithTag(Tags.SEND).performClick()
        rule.waitUntil(5_000) { app.coach.ui.value.busy }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.STOP).assertDoesNotExist()
        assertEquals("給聊天的草稿", vm.composerText)
        gate.complete(Unit)
    }

    // M8 and the saved state: the switch applies at once, and a recreated screen comes back where it was.
    @Test fun theSwitchAppliesAtOnceAndTheSavedStateKeepsTheCoach() {
        val vm = MainViewModel(app, SavedStateHandle())
        assertTrue(vm.nav.coach)
        vm.setOpenOnCoach(false)
        assertFalse(vm.nav.coachHome)
        assertFalse(app.coachSettings.openOnCoach)
        vm.nav.coach = false
        vm.onCoachText("留著的草稿")
        val saved = vm.saveState()
        val again = MainViewModel(app, SavedStateHandle(mapOf(MainViewModel.STATE to saved)))
        assertFalse(again.nav.coach)
        assertEquals("留著的草稿", again.coachText)
        val onCoach = MainViewModel(app, SavedStateHandle()).also { it.nav.coach = true }.saveState()
        assertTrue(MainViewModel(app, SavedStateHandle(mapOf(MainViewModel.STATE to onCoach))).nav.coach)
    }
}
