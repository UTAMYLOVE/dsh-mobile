package app.dshmobile.agency

import android.content.Context
import androidx.compose.ui.test.performScrollTo
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
import app.agency.core.migrate.ImportNote
import app.agency.core.migrate.LegacyMapper
import app.agency.core.model.Baseline
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
        // The old conversation, the company's lines under her name.
        rule.waitForText("要看包裝上的標示，拍給我。")
        assertTrue(rule.onAllNodesWithText("小葵姐").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("體態老師").fetchSemanticsNodes().isNotEmpty())
        runBlocking {
            // His 11 lines, then 入社建檔's welcome (v0.8.3 had no 入社建檔; T07 asks it on the first open).
            assertEquals(12, ready().store.messages.count())
            assertEquals(11, ready().store.messages.recent(20).count { it.legacy })
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
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performScrollTo().performClick()
        rule.waitForText("搬完了")
        rule.onNodeWithTag(LegacyTags.COUNT + ImportKind.MESSAGES.name).assertIsDisplayed()
        rule.onNodeWithText("對話 11 筆").assertIsDisplayed()

        // The next start imports nothing again, and does not welcome him twice.
        restart()
        rule.waitForTag(AgencyTags.HOME)
        runBlocking { assertEquals(12, ready().store.messages.count()) }
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
        // The process dies before he taps 知道了 (review M4): the next start still says it once.
        restart()
        rule.waitForTag(AgencyTags.HOME)
        rule.waitForTag(AgencyTags.NOTICE)
        rule.onNodeWithTag(AgencyTags.NOTICE_OK).performClick()
        rule.waitUntil { rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty() }

        // The next start: no 「正在搬資料」, no notice, the folder still there.
        restart()
        rule.waitForTag(AgencyTags.HOME)
        assertTrue(rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty())
        assertTrue(File(app.filesDir, "coach/state.json").isFile)

        // 設定 › 舊資料 reads the outcome row: partial, with 再試一次 (the same files stay partial).
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performScrollTo().performClick()
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

    /** T06 review I1: a vault that fails takes no file, but every record comes; a later start seals the files. */
    @Test fun aVaultFailureStillBringsTheRecords() {
        AgencyTestKit.seedV2(app)
        val names = listOf("photo-1795224000000.jpg", "photo-1795224600000.jpg", "recording-1795225200000.m4a")
        kit.vaultLocked = true
        launch()
        rule.waitForTag(AgencyTags.HOME, 20_000)
        rule.waitForText("紀錄都搬好了；照片和錄音還沒搬進來，原檔還在舊資料夾，之後會再試。")
        assertEquals(AgencyPrefKeys.MIGRATED_DONE, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        val kept = app.filesDir.listFiles { f -> f.name.startsWith("coach.migrated-") }!!.single()
        names.forEach { assertArrayEquals(AgencyTestKit.fixture("agency-v2-baseline/$it"), File(kept, "baseline/$it").readBytes()) }
        runBlocking {
            assertEquals(9, ready().store.messages.count())
            assertEquals(0, ready().store.media.count())
            val note = ImportNote.fromJson(ready().store.journal.migrations().single { it.id == LegacyImporter.LOG_ID }.note!!)
            assertEquals(names, note.mediaPending)
        }
        // 舊資料 lists them by kind and order (his file names are Latin), each with 分享.
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performScrollTo().performClick()
        rule.waitForText("照片 2")
        rule.onNodeWithText("錄音 1").assertExists()
        rule.onNodeWithTag(LegacyTags.SHARE + names[2]).assertExists()
        assertTrue(rule.onAllNodesWithText("photo-", substring = true).fetchSemanticsNodes().isEmpty())

        kit.vaultLocked = false
        restart()
        rule.waitForTag(AgencyTags.HOME, 20_000)
        runBlocking {
            assertEquals(3, ready().store.media.count())
            val b = ready().store.baselines.get(Baseline.JOIN)!!
            assertEquals(LegacyMapper.mediaId(names[1]), b.photoFront)
            assertEquals(LegacyMapper.mediaId(names[2]), b.recording)
            val note = ImportNote.fromJson(ready().store.journal.migrations().single { it.id == LegacyImporter.LOG_ID }.note!!)
            assertTrue(note.mediaPending.isEmpty())
        }
        rule.waitUntil { rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty() }
    }

    /** A store that does not keep what it was given still rolls everything back; 再試一次 retries now (review M6). */
    @Test fun aMismatchRollsBackDeletesNothingAndCanBeRetriedAtOnce() {
        AgencyTestKit.seedV2(app)
        val coach = File(app.filesDir, "coach")
        val before = coach.walkTopDown().filter { it.isFile }.associate { it.relativeTo(coach).path to it.readBytes().toList() }
        kit.dropMessages = true
        launch()
        rule.waitForTag(AgencyTags.HOME, 20_000)
        rule.waitForText("搬資料沒有成功，舊資料都還在。")
        assertEquals(before, coach.walkTopDown().filter { it.isFile }.associate { it.relativeTo(coach).path to it.readBytes().toList() })
        assertEquals(0, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        kit.dropMessages = false
        runBlocking { assertEquals(0, ready().store.messages.count()) }

        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performScrollTo().performClick()
        rule.waitForTag(LegacyTags.RETRY)
        rule.onNodeWithTag(LegacyTags.RETRY).performClick()
        rule.waitForText("搬完了", 20_000)
        runBlocking { assertEquals(9, ready().store.messages.count()) }
        assertEquals(AgencyPrefKeys.MIGRATED_DONE, agencyPrefs.getInt(AgencyPrefKeys.MIGRATED, 0))
        // The failure line is gone once the import is done.
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.COMPANY.name).performClick()
        assertTrue(rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty())
    }
}
