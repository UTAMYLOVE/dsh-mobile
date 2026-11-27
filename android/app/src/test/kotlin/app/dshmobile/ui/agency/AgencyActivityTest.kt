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
import app.agency.core.brain.Brain
import app.agency.core.metering.Provider
import app.agency.core.model.Who
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.agency.TestOverrides
import app.dshmobile.agency.secure.DataLockedException
import app.dshmobile.agency.secure.TransientDataException
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.RoomAgencyStore
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.agency.core.model.Profile
import app.agency.core.time.AgencyClock
import app.agency.core.metering.MutableClock
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
        // The card loads after the screen (its numbers are read off the main thread).
        rule.waitForTag(AgencyTags.TRAINEE)
        rule.onNodeWithTag(AgencyTags.TRAINEE).assertIsDisplayed()
        // 入社建檔 starts on the first open (T07): the manager's welcome and first question, written by code.
        rule.waitForText("入社第一件事：建檔")
        rule.onNodeWithText("【小葵姐】").assertIsDisplayed()

        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.WAR_ROOM.name).performClick()
        rule.onNodeWithText("後台手帳").assertIsDisplayed()

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
        // Nothing of dsh or the old coach is left in the app to build (T09).
        listOf("controller", "coach", "coachStore", "rootfsInstaller", "notifier").forEach { f ->
            assertTrue(f, DshMobileApp::class.java.declaredFields.none { it.name.startsWith(f + "\$") || it.name == f })
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
        rule.onNodeWithText("【小葵姐】").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("1234") == 0)
        val stored = runBlocking { (app.agency.start.value as StartState.Ready).store.messages.recent(10) }
        // The welcome of 入社建檔 first; 「姐姐在嗎」 is no answer to its question, so it goes to the manager's loop.
        assertEquals(listOf(Who.COMPANY, Who.OWNER, Who.COMPANY), stored.map { it.who })
        assertEquals(Brain.BASELINE_KIND, stored[0].kind)
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

    /** Review M5: the lock screen offers 再試一次; opening again deletes nothing and may work. */
    @Test fun theLockCanBeRetried() {
        kit.failures += DataLockedException("busy keystore")
        launch()
        rule.waitForTag(AgencyTags.LOCKED)
        rule.onNodeWithTag(AgencyTags.RETRY).performClick()
        rule.waitForTag(AgencyTags.HOME)
        assertEquals(2, kit.opens)
    }

    /** Review M12: a throw outside the start's own catches ends on 再試一次, not a crash. */
    @Test fun anUnexpectedThrowAtStartIsATryAgainScreen() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides().let { o ->
            TestOverrides(o.modelBase, o.openDatabase, { error("invented") }, o.store)
        }
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.UNAVAILABLE)
    }

    /** Review M7: a plaintext copy he shared from 舊資料 is gone at the next start. */
    @Test fun sharedCopiesAreClearedAtStart() {
        val copy = File(app.cacheDir, "export/photo-1.jpg").apply { parentFile!!.mkdirs(); writeText("invented") }
        launch()
        rule.waitForTag(AgencyTags.HOME)
        assertFalse(copy.exists())
    }

    /** Controller ruling on review M3: the clerk runs on the cheapest model of the provider he chose, the reply on his model. */
    @Test fun theClerkUsesTheCheapestModelOfHisProvider() {
        val bodies = mutableListOf<String>()
        val s = AgencyTestKit.fakeDeepSeek("現在去喝水。", bodies = bodies).also { server = it }
        launch(AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-test-0123456789abcdef"), s)
        rule.waitForTag(AgencyTags.HOME)
        app.agency.settings.deepseekModel = "deepseek-v4-pro"
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitForText("現在去喝水。")
        val models = bodies.map { org.json.JSONObject(it).getString("model") }
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models)
        assertTrue(bodies[0].contains("資料整理員"))
    }

    /** Review M11: the compatible service's daily cap counts in his coach day, from his own day start. */
    @Test fun theCompatQuotaDayFollowsHisDayStart() {
        runBlocking {
            AgencyDatabase.build(app, File(tmp, "agency.db").path, FrameworkSQLiteOpenHelperFactory()).let { db ->
                RoomAgencyStore(db).profile.put(Profile(dayStartHour = 0))
                db.close()
            }
        }
        // 03:00 in Taipei on 2026-11-24: with a 05:00 day start it would still be the 23rd.
        val clock = AgencyClock.of(MutableClock(java.time.Instant.parse("2026-11-23T19:00:00Z")))
        val agency = AgencyApp(app, AgencyTestKit.MemoryKeys(), app.costMeter, app.errorLog, kit.overrides(), clock)
        agency.start()
        rule.waitUntil(10_000) { agency.start.value is StartState.Ready }
        assertEquals("2026-11-24", agency.quotaDay())
        runBlocking { agency.stopForTest() }
    }
}
