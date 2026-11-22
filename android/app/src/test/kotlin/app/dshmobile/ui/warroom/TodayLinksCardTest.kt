package app.dshmobile.ui.warroom

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.ProposalStatus
import app.agency.core.rules.CoachRules
import app.agency.core.time.AgencyClock
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
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「今天的連動」 in the war room (T11b): the reason line, cancel for today, and 好 on a proposal. Everything is invented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class TodayLinksCardTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("links-card").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    private fun launch() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun store() = (app.agency.start.value as StartState.Ready).store
    private fun today() = CoachRules.coachDay(Instant.now(), AgencyClock.TAIPEI, 5).toString()

    private fun type(text: String) {
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput(text)
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
    }

    @Test fun aTodayChangeShowsWhyAndCanBeCancelledForTheDay() {
        launch()
        type("早上很餓")
        rule.waitForText("豆漿挪到早上喝")
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.WAR_ROOM.name).performClick()
        rule.waitForText("因為你說早上很餓，所以豆漿挪到早上喝；雞胸照樣 21:00 跑單途中吃。")
        val e = runBlocking { store().nerve.effects(today()) }.single { it.link == "L4" }
        rule.onNodeWithTag(LinkTags.CANCEL + e.id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { store().nerve.effect(e.id) }?.status == EffectStatus.CANCELLED }
        assertFalse(runBlocking { store().days.get(today()) }!!.soyMorning)
        rule.waitForText("已取消")
    }

    @Test fun aSickDayProposalWaitsForHisYes() {
        launch()
        type("我好像發燒了")
        rule.waitForText("先照顧身體。發燒的話")
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.WAR_ROOM.name).performClick()
        rule.waitForText("等你說好")
        val p = runBlocking { store().nerve.effects(today()) }.single { it.kind == EffectKind.PROPOSAL }
        rule.onNodeWithTag(LinkTags.YES + p.id).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { store().nerve.effect(p.id) }?.status == EffectStatus.ACCEPTED }
        assertEquals(ProposalStatus.ACCEPTED, runBlocking { store().proposals.get(p.proposalId!!) }!!.status)
        rule.waitForText("你說好了")
    }
}
