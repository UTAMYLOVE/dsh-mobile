package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.metering.Provider
import app.agency.core.model.Who
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.agency.secure.DataLockedException
import app.dshmobile.agency.secure.TransientDataException
import app.dshmobile.ui.settings.ModelKeyTags
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h720dp-mdpi")
class AgencyActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("agency-activity").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private var server: MockWebServer? = null

    private fun launch(keys: AgencyTestKit.MemoryKeys = AgencyTestKit.MemoryKeys(), base: MockWebServer? = null) {
        AgencyTestKit.swap(app, "keyVault", keys)
        AgencyApp.testOverrides = kit.overrides(base?.url("/"))
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        server?.shutdown()
        tmp.deleteRecursively()
    }

    @Test fun theLauncherIsTheCompany() {
        val launcher = app.packageManager.getLaunchIntentForPackage(app.packageName)!!
        assertEquals(AgencyActivity::class.java.name, launcher.component!!.className)
    }

    @Test fun aFreshInstallOpensOnHomeAndTheTabsWork() {
        launch()
        rule.waitForTag(AgencyTags.HOME)
        rule.onNodeWithTag(AgencyTags.TRAINEE).assertIsDisplayed()
        rule.onNodeWithText("還沒有對話。有事就跟經紀人說。").assertIsDisplayed()

        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.WAR_ROOM.name).performClick()
        rule.onNodeWithText("指揮室整修中").assertIsDisplayed()

        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.MODEL.name).performClick()
        rule.onNodeWithTag(ModelKeyTags.KEY_STATUS).assertIsDisplayed()
        rule.onNodeWithText("還沒有金鑰").assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.BACK).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.SPEND.name).performClick()
        rule.onNodeWithText("還沒有設每日上限，建議每天 1 美元。").assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.BACK).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performClick()
        rule.waitForText("沒有舊資料要搬。")

        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.COMPANY.name).performClick()
        rule.onNodeWithTag(AgencyTags.HOME).assertIsDisplayed()
        // dsh is never started: no service, and the old shell is not the screen.
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(shadowOf(app).nextStartedActivity?.component?.className == "app.dshmobile.ui.MainActivity")
        // Nothing of dsh or the old coach was even built.
        listOf("controller", "uiHolder", "shellSessions", "coach", "coachStore", "rootfsInstaller").forEach { f ->
            val lazy = DshMobileApp::class.java.getDeclaredField("$f\$delegate").apply { isAccessible = true }.get(app) as Lazy<*>
            assertFalse("$f was built", lazy.isInitialized())
        }
    }

    @Test fun aMessageGetsTheGuardedReplyOfTheManager() {
        val bodies = mutableListOf<String>()
        val s = AgencyTestKit.fakeDeepSeek("今天還能吃 1234 大卡。現在去喝水。", bodies = bodies).also { server = it }
        launch(AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-test-0123456789abcdef"), s)
        rule.waitForTag(AgencyTags.HOME)
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitForText("現在去喝水。")
        rule.onNodeWithText("【經紀人】").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("1234") == 0)
        val stored = runBlocking { (app.agency.start.value as StartState.Ready).store.messages.recent(10) }
        assertEquals(listOf(Who.OWNER, Who.COMPANY), stored.map { it.who })
        assertEquals(2, bodies.size)
        assertTrue(bodies.none { it.contains("sk-test") })
    }

    @Test fun aLockedDatabaseShowsTheLockAndDeletesNothing() {
        val file = File(app.noBackupFilesDir, "agency/agency.db").apply { parentFile!!.mkdirs(); writeText("invented") }
        kit.failures += DataLockedException("lost")
        launch()
        rule.waitForTag(AgencyTags.LOCKED)
        rule.onNodeWithText("資料鎖打不開").assertIsDisplayed()
        assertEquals("invented", file.readText())
        // Opening again does not help: start() does nothing once locked.
        app.agency.start()
        rule.waitForIdle()
        assertEquals(1, kit.opens)
    }

    @Test fun anythingElseThatBreaksTheOpenIsTheLockNotACrash() {
        kit.failures += UnsatisfiedLinkError("no sqlcipher")
        launch()
        rule.waitForTag(AgencyTags.LOCKED)
    }

    @Test fun aPassingFailureOffersToTryAgain() {
        kit.failures += TransientDataException("disk full")
        launch()
        rule.waitForTag(AgencyTags.UNAVAILABLE)
        rule.onNodeWithTag(AgencyTags.RETRY).performClick()
        rule.waitForTag(AgencyTags.HOME)
        assertEquals(2, kit.opens)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
}
