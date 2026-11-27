package app.dshmobile.ui.agency

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.FavoritePlace
import app.agency.core.model.Message
import app.agency.core.model.MessageRefs
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.tapTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.meal.ResultTags
import app.dshmobile.ui.settings.AreaTags
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsTags
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Nearby food on screen (T16): a result card shows the search's title and summary with the 估算 note; 打開 hands only its link
 * to the browser; 存成常去店家 is a proposal saved on his 好; 設定 › 附近搜尋 keeps a coarse area only and lists the places.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class NearbyScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("nearby").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    private fun open() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
    }

    @Test fun aResultCardOpensItsLinkAndSavesOnlyOnHisYes() {
        open()
        runBlocking {
            ready().store.places.putResult(SearchResult("r-1", SearchKind.PLACE, "大安區 雞胸便當 推薦", "https://example.org/a", "健康雞胸便當大安店", "雞胸便當，有燙青菜。", null, 2.0, 2.1, System.currentTimeMillis()))
            ready().store.messages.insert(
                Message("line-r", System.currentTimeMillis(), Who.COMPANY, RoleKey.BODY, "meal_talk", text = "附近有健康雞胸便當大安店。", refs = MessageRefs(results = listOf("r-1")), wordedBy = WordedBy.MODEL),
            )
        }
        app.agency.send("刮好了")
        rule.waitForTag(ResultTags.CARD + "r-1", 30_000)
        rule.waitForText("熱量都是估算")
        rule.onNodeWithTag(ResultTags.OPEN + "r-1").performScrollTo().performClick()
        var opened: Intent? = null
        scenario!!.onActivity { opened = shadowOf(it).nextStartedActivity }
        assertEquals(Intent.ACTION_VIEW, opened!!.action)
        assertEquals("https://example.org/a", opened!!.dataString)
        rule.tapTag(ResultTags.SAVE + "r-1")
        rule.waitUntil(10_000) { runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.any { it.kind == ProposalKind.SAVE_PLACE } }
        assertTrue(runBlocking { ready().store.places.favorites() }.isEmpty())
        val p = runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        rule.waitForTag(ProposalTags.YES + p.id)
        rule.tapTag(ProposalTags.YES + p.id)
        rule.waitUntil(10_000) { runBlocking { ready().store.places.favorites() }.isNotEmpty() }
        assertEquals("健康雞胸便當大安店", runBlocking { ready().store.places.favorites() }.single().title)
    }

    @Test fun theAreaPageKeepsACoarseAreaAndListsThePlaces() {
        open()
        runBlocking {
            ready().store.places.insertFavorite(FavoritePlace("f-1", "好味道自助餐", "https://example.org/b", "大安區", null, 1))
        }
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.AREA.name).performScrollTo().performClick()
        rule.waitForTag(AreaTags.FIELD)
        rule.onNodeWithTag(AreaTags.FIELD).performTextInput("和平東路 3 巷")
        rule.onNodeWithTag(AreaTags.SAVE).performClick()
        rule.waitForText("不要填門牌、巷弄或樓層")
        assertEquals(null, runBlocking { ready().store.profile.get() }?.areaText)
        rule.onNodeWithTag(AreaTags.FIELD).performTextClearance()
        rule.onNodeWithTag(AreaTags.FIELD).performTextInput("大安區")
        rule.onNodeWithTag(AreaTags.SAVE).performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.profile.get() }?.areaText == "大安區" }
        rule.waitForTag(AreaTags.PLACE + "f-1")
        rule.onNodeWithTag(AreaTags.DELETE + "f-1").performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.places.favorites() }.isEmpty() }
    }
}
