package app.dshmobile.ui.warroom

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Profile
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTab
import app.dshmobile.ui.agency.AgencyTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 備忘錄 on screen (T12b): 「幫我記」 saves his words, the card checks it off, deletes it and 復原 brings it back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class MemoScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("memo").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    @Test fun aMemoIsSavedCheckedDeletedAndRestored() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        runBlocking { ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true)) }
        app.agency.send("幫我記：買洗衣精")
        rule.waitForText("記下了：「買洗衣精」，放在備忘錄。")
        val id = runBlocking { ready().store.memos.all() }.single().id
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.WAR_ROOM.name).performClick()
        rule.waitForTag(MemoTags.ROW + id)
        rule.onNodeWithTag(MemoTags.CHECK + id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.memos.get(id)!!.doneAt } != null }
        rule.onNodeWithTag(MemoTags.DELETE + id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.memos.all() }.isEmpty() }
        rule.waitForTag(MemoTags.EMPTY)
        rule.onNodeWithTag(MemoTags.UNDO).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.memos.all() }.size == 1 }
        rule.waitForTag(MemoTags.ROW + id)
        assertEquals("買洗衣精", runBlocking { ready().store.memos.get(id)!!.text })
        assertTrue(runBlocking { ready().store.memos.get(id)!!.deletedAt } == null)
    }
}
