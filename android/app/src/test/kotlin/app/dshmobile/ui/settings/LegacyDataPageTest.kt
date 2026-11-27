package app.dshmobile.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTab
import app.dshmobile.ui.agency.AgencyTags
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 設定 › 舊資料 after dsh (arch T09): the kept coach folder is deleted only on a second confirmation (and only after a finished
 * import); while a file never reached the vault, the whole folder is shared first; his old chats and novels can be shared as a
 * zip and are never deleted. Every record and file is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class LegacyDataPageTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("legacy-page").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    private fun open() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME, 20_000)
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.LEGACY.name).performScrollTo().performClick()
        rule.waitForTag(LegacyTags.STATUS)
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        File(app.filesDir, "userdata").deleteRecursively()
        tmp.deleteRecursively()
    }

    private fun kept(): File? = app.filesDir.listFiles { f -> f.name.startsWith("coach.migrated-") }?.singleOrNull()

    @Test fun theKeptFolderGoesOnlyOnTheSecondTap() {
        AgencyTestKit.seedV083(app)
        open()
        val folder = kept()!!
        rule.onNodeWithTag(LegacyTags.DELETE).performScrollTo().performClick()
        rule.onNodeWithTag(LegacyTags.CONFIRM_NO).performClick()
        assertTrue(folder.isDirectory)
        rule.onNodeWithTag(LegacyTags.DELETE).performScrollTo().performClick()
        rule.onNodeWithTag(LegacyTags.CONFIRM_YES).performClick()
        rule.waitForTag(LegacyTags.DELETED)
        assertFalse(folder.exists())
        // His records stay in the company; the deletion is in the audit log (no content).
        runBlocking {
            val store = (app.agency.start.value as StartState.Ready).store
            assertEquals(4, store.meals.count())
            assertTrue(store.journal.audits().any { it.what == "legacy_folder_deleted" && it.detail == "ok=true" })
        }
    }

    @Test fun aFolderWithFilesNotInTheVaultIsSharedBeforeItCanGo() {
        AgencyTestKit.seedV2(app)
        kit.vaultLocked = true
        open()
        rule.onNodeWithTag(LegacyTags.DELETE).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(LegacyTags.SHARE_FOLDER).performScrollTo().performClick()
        rule.waitUntil(10_000) { shadowOf(app).peekNextStartedActivity() != null || nextChooser() != null }
        val chooser = nextChooser()!!
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        val zip = File(app.cacheDir, "export").listFiles { f -> f.name.startsWith("coach-") }!!.single()
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.toString(), "baseline/recording-1795225200000.m4a" in names && "state.json" in names)
        rule.onNodeWithTag(LegacyTags.DELETE).performScrollTo().performClick()
        rule.onNodeWithTag(LegacyTags.CONFIRM_YES).performClick()
        rule.waitForTag(LegacyTags.DELETED)
        assertTrue(kept() == null)
    }

    private var chooserSeen: Intent? = null
    private fun nextChooser(): Intent? {
        chooserSeen = chooserSeen ?: scenario?.let { s ->
            var i: Intent? = null
            s.onActivity { a -> i = shadowOf(a).nextStartedActivity }
            i
        } ?: shadowOf(app).nextStartedActivity
        return chooserSeen
    }

    @Test fun oldChatsAndNovelsCanBeSharedAndOnlyTheNovelsDeletedAfterTwoConfirmations() {
        val userdata = File(app.filesDir, "userdata").apply { mkdirs() }
        File(userdata, "workspace/novels").mkdirs()
        // ASCII file names: this JVM's file-name encoding is not UTF-8 (the phone's is); the content is Chinese.
        File(userdata, "workspace/novels/chapter-1.md").writeText("虛構的小說內容")
        File(userdata, "home/chat.json").apply { parentFile!!.mkdirs(); writeText("{\"invented\":true}") }
        open()
        rule.onNodeWithTag(LegacyTags.CHATS).performScrollTo()
        rule.onNodeWithTag(LegacyTags.CHATS_EXPORT).performScrollTo().performClick()
        rule.waitUntil(10_000) { nextChooser() != null }
        val zip = File(app.cacheDir, "export").listFiles { f -> f.name.startsWith("old-chats-") }!!.single()
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name }.sorted() }
        assertEquals(listOf("home/chat.json", "workspace/novels/chapter-1.md"), names)
        assertEquals("虛構的小說內容", File(userdata, "workspace/novels/chapter-1.md").readText())
        // No delete of the old folder is offered for them.
        rule.onNodeWithTag(LegacyTags.DELETE).assertDoesNotExist()
        // 刪除舊小說 (owner 2026-11-27): two confirmations, the novels go, the old chats stay.
        rule.onNodeWithTag(LegacyTags.NOVELS_DELETE).performScrollTo().performClick()
        rule.onNodeWithTag(LegacyTags.NOVELS_CONFIRM_1).performScrollTo().performClick()
        assertTrue(File(userdata, "workspace/novels/chapter-1.md").exists()) // not yet
        rule.onNodeWithTag(LegacyTags.NOVELS_CONFIRM_2).performScrollTo().performClick()
        rule.waitUntil(10_000) { !File(userdata, "workspace/novels").exists() }
        assertEquals("{\"invented\":true}", File(userdata, "home/chat.json").readText())
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(LegacyTags.NOVELS_DELETED).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(LegacyTags.NOVELS_DELETE).assertDoesNotExist()
    }
}
