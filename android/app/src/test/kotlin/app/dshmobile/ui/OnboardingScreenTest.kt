package app.dshmobile.ui

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.R
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.Provider
import app.dshmobile.security.KeyVault
import app.dshmobile.system.OnboardingState
import app.dshmobile.system.OnboardingState.Step
import app.dshmobile.system.PhantomProcessDetector
import app.dshmobile.ui.theme.SakuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private class FakePlatform : OnboardingPlatform {
    var notificationsGranted = false
    var batteryIgnored = false
    var developerOptions = true
    var batteryOpened = 0
    var developerOpened = 0
    var deviceInfoOpened = 0
    override fun notificationsGranted() = notificationsGranted
    override fun batteryOptimizationIgnored() = batteryIgnored
    override fun developerOptionsEnabled() = developerOptions
    override fun openBatterySettings() { batteryOpened++ }
    override fun openDeveloperSettings() { developerOpened++ }
    override fun openDeviceInfoSettings() { deviceInfoOpened++ }
}

private class RecordingVault : KeyVault {
    val puts = mutableListOf<Pair<Provider, String>>()
    override fun get(p: Provider): String? = puts.lastOrNull { it.first == p }?.second
    override fun put(p: Provider, key: String) { puts += p to key }
    override fun clear(p: Provider) { puts.removeAll { it.first == p } }
}

@RunWith(RobolectricTestRunner::class)
// SDK 36, not the default 37: Compose UI tests idle through Espresso, which reflects on InputManager.getInstance(), absent from the Android 17 jar.
@Config(sdk = [36])
class OnboardingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver
    private val prefs = InMemorySharedPreferences()
    private val state = OnboardingState(prefs)
    private val platform = FakePlatform()
    private val vault = RecordingVault()
    private val fakeKey = "sk-" + "a1b2c3d4".repeat(4)
    private var finished = 0
    private var installed = false
    private val installCalls = mutableListOf<Int>()
    private var installBehaviour: (onProgress: (Int) -> Unit) -> Unit = { it(50); installed = true; it(100) }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun startAt(step: Step) {
        Step.values().takeWhile { it != step }.forEach { state.markDone(it) }
        rule.setContent {
            SakuraTheme {
                OnboardingScreen(
                    state = state,
                    platform = platform,
                    vault = vault,
                    detector = PhantomProcessDetector(resolver),
                    isRootfsInstalled = { installed },
                    installRootfs = { onProgress -> installCalls += 1; installBehaviour(onProgress) },
                    onFinished = { finished++ },
                )
            }
        }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    // ---- key step ----

    @Test fun emptyKeyCannotAdvance() {
        startAt(Step.DEEPSEEK_KEY)
        rule.onNodeWithText(str(R.string.onb_key_title)).assertExists()
        rule.onNodeWithText(str(R.string.onb_key_save)).assertIsNotEnabled()
        assertTrue(vault.puts.isEmpty())
        assertFalse(state.isDone(Step.DEEPSEEK_KEY))
    }

    @Test fun tooShortKeyCannotAdvance() {
        startAt(Step.DEEPSEEK_KEY)
        rule.onNode(isKeyField).performTextInput("sk-12")
        rule.onNodeWithText(str(R.string.onb_key_save)).assertIsNotEnabled()
        assertTrue(vault.puts.isEmpty())
    }

    @Test fun whitespaceOnlyKeyCannotAdvance() {
        startAt(Step.DEEPSEEK_KEY)
        rule.onNode(isKeyField).performTextInput(" ".repeat(40))
        rule.onNodeWithText(str(R.string.onb_key_save)).assertIsNotEnabled()
    }

    @Test fun keyFieldIsAPasswordFieldThatNeverShowsTheKey() {
        startAt(Step.DEEPSEEK_KEY)
        rule.onNode(isKeyField).performTextInput(fakeKey)
        assertTrue("key shown as text", rule.onAllNodes(showsKeyAsText).fetchSemanticsNodes().isEmpty())
    }

    @Test fun savingTheKeyPutsItInTheVaultAndMovesOn() {
        installBehaviour = { throw IOException("stop here") } // keep the rootfs step on screen
        startAt(Step.DEEPSEEK_KEY)
        rule.onNode(isKeyField).performTextInput("  $fakeKey  ")
        rule.onNodeWithText(str(R.string.onb_key_save)).assertIsEnabled().performClick()
        assertEquals(listOf(Provider.DEEPSEEK to fakeKey), vault.puts)
        assertTrue(state.isDone(Step.DEEPSEEK_KEY))
        waitForText(str(R.string.onb_rootfs_title))
    }

    @Test fun aVaultFailureShowsAGenericErrorWithoutTheKey() {
        val failing = object : KeyVault {
            override fun get(p: Provider): String? = null
            override fun put(p: Provider, key: String) = throw IllegalStateException("keystore down")
            override fun clear(p: Provider) = Unit
        }
        rule.setContent {
            SakuraTheme {
                OnboardingScreen(
                    state = state.also { Step.values().takeWhile { s -> s != Step.DEEPSEEK_KEY }.forEach(it::markDone) },
                    platform = platform, vault = failing, detector = PhantomProcessDetector(resolver),
                    isRootfsInstalled = { false }, installRootfs = {}, onFinished = {},
                )
            }
        }
        rule.onNode(isKeyField).performTextInput(fakeKey)
        rule.onNodeWithText(str(R.string.onb_key_save)).performClick()
        rule.onNodeWithText(str(R.string.onb_key_error)).assertExists()
        assertTrue("key shown as text", rule.onAllNodes(showsKeyAsText).fetchSemanticsNodes().isEmpty())
        assertFalse("step wrongly done", state.isDone(Step.DEEPSEEK_KEY))
    }

    @Test fun keyAndRootfsStepsOfferNoSkip() {
        startAt(Step.DEEPSEEK_KEY)
        assertTrue(rule.onAllNodesWithText(str(R.string.onb_skip)).fetchSemanticsNodes().isEmpty())
    }

    // ---- rootfs step ----

    @Test fun rootfsInstallRunsAndFinishesOnboarding() {
        startAt(Step.ROOTFS)
        rule.waitUntil(5_000) { finished == 1 }
        assertTrue(state.isDone(Step.ROOTFS))
        assertEquals(1, installCalls.size)
    }

    @Test fun rootfsFailureShowsReadableErrorAndRetry() {
        installBehaviour = { throw IOException("disk full") }
        startAt(Step.ROOTFS)
        waitForText("disk full")
        rule.onNodeWithText(str(R.string.onb_rootfs_retry)).assertExists()
        assertEquals(0, finished)
        assertFalse(state.isDone(Step.ROOTFS))
        assertTrue(rule.onAllNodesWithText(str(R.string.onb_skip)).fetchSemanticsNodes().isEmpty())

        installBehaviour = { installed = true; it(100) }
        rule.onNodeWithText(str(R.string.onb_rootfs_retry)).performClick()
        rule.waitUntil(5_000) { finished == 1 }
        assertEquals(2, installCalls.size)
        assertTrue(state.isDone(Step.ROOTFS))
    }

    @Test fun missingRootfsAssetIsAReadableErrorNotACrash() {
        installBehaviour = { throw java.io.FileNotFoundException("rootfs.tar.xz") }
        startAt(Step.ROOTFS)
        waitForText(str(R.string.onb_rootfs_missing))
        rule.onNodeWithText(str(R.string.onb_rootfs_retry)).assertExists()
    }

    @Test fun alreadyInstalledRootfsFinishesWithoutInstalling() {
        installed = true
        startAt(Step.ROOTFS)
        rule.waitUntil(5_000) { finished == 1 }
        assertEquals(0, installCalls.size)
        assertTrue(state.isDone(Step.ROOTFS))
    }

    // ---- optional steps ----

    @Test fun startsAtTheFirstUnfinishedStepAndSkipIsRemembered() {
        startAt(Step.NOTIFICATIONS)
        rule.onNodeWithText(str(R.string.onb_notif_title)).assertExists()
        rule.onNodeWithText(str(R.string.onb_skip)).performClick()
        assertTrue(state.isDone(Step.NOTIFICATIONS))
        rule.onNodeWithText(str(R.string.onb_battery_title)).assertExists()
    }

    @Test fun grantedNotificationsCompleteTheStepAutomatically() {
        platform.notificationsGranted = true
        startAt(Step.NOTIFICATIONS)
        rule.onNodeWithText(str(R.string.onb_battery_title)).assertExists()
        assertTrue(state.isDone(Step.NOTIFICATIONS))
    }

    @Test fun batteryStepOpensTheRequestAndCompletesOnceIgnored() {
        startAt(Step.BATTERY)
        rule.onNodeWithText(str(R.string.onb_battery_button)).performClick()
        assertEquals(1, platform.batteryOpened)
        assertFalse(state.isDone(Step.BATTERY))
        platform.batteryIgnored = true
        resumeActivity()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.onb_phantom_title)).assertExists()
        assertTrue(state.isDone(Step.BATTERY))
    }

    @Test fun alreadyIgnoredBatteryOptimizationCompletesAutomatically() {
        platform.batteryIgnored = true
        startAt(Step.BATTERY)
        rule.onNodeWithText(str(R.string.onb_phantom_title)).assertExists()
    }

    // ---- phantom step ----

    @Test fun phantomStepWithDeveloperOptionsOffShowsTheTutorialAndAboutPhoneButton() {
        platform.developerOptions = false
        startAt(Step.PHANTOM)
        rule.onNodeWithText(str(R.string.onb_phantom_tutorial), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.onb_phantom_open_about)).performClick()
        assertEquals(1, platform.deviceInfoOpened)
        assertEquals(0, platform.developerOpened)
    }

    @Test fun phantomStepWithDeveloperOptionsOnOpensDeveloperSettings() {
        platform.developerOptions = true
        startAt(Step.PHANTOM)
        assertTrue(rule.onAllNodesWithText(str(R.string.onb_phantom_tutorial), substring = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(str(R.string.onb_phantom_open_dev)).performClick()
        assertEquals(1, platform.developerOpened)
    }

    @Test fun phantomStateIsShownAndRedetectedOnResume() {
        Settings.Global.putString(resolver, "settings_enable_monitor_phantom_procs", "true")
        startAt(Step.PHANTOM)
        rule.onNodeWithText(str(R.string.onb_phantom_state_enabled)).assertExists()
        Settings.Global.putString(resolver, "settings_enable_monitor_phantom_procs", "false")
        resumeActivity()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.onb_phantom_state_disabled)).assertExists()
    }

    @Test fun unknownPhantomStateIsShownWhenTheSettingIsMissing() {
        startAt(Step.PHANTOM)
        rule.onNodeWithText(str(R.string.onb_phantom_state_unknown)).assertExists()
    }

    @Test fun phantomStepCanBeSkippedAndIsRemembered() {
        startAt(Step.PHANTOM)
        rule.onNodeWithText(str(R.string.onb_skip)).performClick()
        assertTrue(state.isDone(Step.PHANTOM))
        rule.onNodeWithText(str(R.string.onb_key_title)).assertExists()
    }

    private fun resumeActivity() {
        val scenario = rule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    // Text and EditableText are what is displayed and read out; the field's raw InputText semantic exists for tests only.
    private val showsKeyAsText = SemanticsMatcher("displays the key") { node ->
        val shown = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        shown.any { it.contains(fakeKey) }
    }

    private val isKeyField = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
}
