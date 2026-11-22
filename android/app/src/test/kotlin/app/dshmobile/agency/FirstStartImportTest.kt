package app.dshmobile.agency

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.ImportKind
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.migrate.LegacyImporter
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTab
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.settings.LegacyTags
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The first start over a v0.8.3 install: his records come over, show, and nothing legacy is lost (arch 1.14, T06). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h720dp-mdpi")
class FirstStartImportTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("first-start").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private val agencyPrefs get() = app.getSharedPreferences(AgencyPrefKeys.NAME, Context.MODE_PRIVATE)

    private fun launch() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
    }

    /** Closes the screen and the company, then opens both again: the next start of the same install. */
    private fun restart() {
        scenario?.close()
        runBlocking { app.agency.stopForTest() }
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    @Test fun hisRecordsComeOverAndShowAndTheFolderIsKeptAside() {
        AgencyTestKit.seedV083(app)
        val state = File(app.filesDir, "coach/state.json").readBytes()
        launch()
        rule.waitForTag(AgencyTags.HOME, 20_000)
        // The old conversation, with the role labels of the company's lines.
        rule.waitForText("要看包裝上的標示，拍給我。")
        assertTrue(rule.onAllNodesWithText("【", substring = true).fetchSemanticsNodes().isNotEmpty())
        runBlocking {
            assertEquals(11, ready().store.messages.count())
            assertTrue(ready().store.meals.count() > 0)
        }
        assertEquals(AgencyPrefKeys.MIGRATED_DONE, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        val kept = app.filesDir.listFiles { f -> f.name.startsWith("coach.migrated-") }!!.single()
        assertArrayEquals(state, File(kept, "state.json").readBytes())
        assertTrue(!File(app.filesDir, "coach").exists())
        // His provider choice came with him.
        assertEquals("DEEPSEEK", agencyPrefs.getString(AgencyPrefKeys.PROVIDER, null))
        assertTrue(rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty())

        // 設定 › 舊資料: done, and how many of each.
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performClick()
        rule.waitForText("搬完了")
        rule.onNodeWithTag(LegacyTags.COUNT + ImportKind.MESSAGES.name).assertIsDisplayed()
        rule.onNodeWithText("對話 11 筆").assertIsDisplayed()

        // The next start imports nothing again.
        restart()
        rule.waitForTag(AgencyTags.HOME)
        runBlocking { assertEquals(11, ready().store.messages.count()) }
    }

    @Test fun aPartialImportIsSaidOnceNeverBlocksAndKeepsTheFolder() {
        // Cut after the last complete value: every record survives, but the file does not parse.
        val cut = AgencyTestKit.fixture("v083-state.json").decodeToString().trimEnd().dropLast(1).toByteArray()
        AgencyTestKit.seedV083(app, cut)
        launch()
        rule.waitForTag(AgencyTags.HOME, 20_000)
        rule.waitForTag(AgencyTags.NOTICE)
        rule.onNodeWithText("有些舊紀錄讀不出來；讀得到的都搬好了，舊資料都還留著。").assertIsDisplayed()
        assertEquals(AgencyPrefKeys.MIGRATED_PARTIAL, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertArrayEquals(cut, File(app.filesDir, "coach/state.json").readBytes())
        rule.onNodeWithTag(AgencyTags.NOTICE_OK).performClick()
        rule.waitUntil { rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty() }

        // The next start: no 「正在搬資料」, no notice, the folder still there.
        restart()
        rule.waitForTag(AgencyTags.HOME)
        assertTrue(rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty())
        assertTrue(File(app.filesDir, "coach/state.json").isFile)

        // 設定 › 舊資料 reads the outcome row: partial, with 再試一次 (the same files stay partial).
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performClick()
        rule.waitForTag(LegacyTags.RETRY)
        rule.onNodeWithTag(LegacyTags.RETRY).performClick()
        rule.waitForTag(LegacyTags.RETRY)
        assertEquals(AgencyPrefKeys.MIGRATED_PARTIAL, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        assertArrayEquals(cut, File(app.filesDir, "coach/state.json").readBytes())
        runBlocking {
            val rows = ready().store.journal.migrations()
            assertNotNull(rows.single { it.id == LegacyImporter.LOG_ID && !it.ok })
        }
    }

    @Test fun aFailedImportNeverBlocksDeletesNothingAndTriesAgainNextStart() {
        AgencyTestKit.seedV2(app)
        val coach = File(app.filesDir, "coach")
        val before = coach.walkTopDown().filter { it.isFile }.associate { it.relativeTo(coach).path to it.readBytes().toList() }
        // The vault's key is lost: the baseline files cannot be sealed, so the import rolls back.
        kit.vaultLocked = true
        launch()
        rule.waitForTag(AgencyTags.HOME, 20_000)
        rule.waitForText("搬資料沒有成功，舊資料都還在。")
        assertEquals(before, coach.walkTopDown().filter { it.isFile }.associate { it.relativeTo(coach).path to it.readBytes().toList() })
        assertEquals(0, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        runBlocking { assertEquals(0, ready().store.messages.count()) }

        kit.vaultLocked = false
        restart()
        rule.waitForTag(AgencyTags.HOME, 20_000)
        runBlocking { assertEquals(9, ready().store.messages.count()) }
        assertEquals(AgencyPrefKeys.MIGRATED_DONE, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
    }
}
