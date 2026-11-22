package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Baseline
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Profile
import app.agency.core.model.WaterLog
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.DayPlanner
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.kit.MemberColour
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 練習生證 on the screen (arch T10, review A4): its content, the fold with TalkBack's state, stickers, and the 藝名 /
 * 代表色 setup after 入社建檔. Everything is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class IdolCardScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("idol-card").toFile()
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

    private fun ready() = app.agency.start.value as StartState.Ready
    private fun today() = CoachRules.coachDay(Instant.now(), ZoneId.of("Asia/Taipei"), 5)

    private fun type(text: String) {
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput(text)
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    /** 入社建檔 done, so the card does not open by itself, and a typed line refreshes it. */
    private fun seedFinished(profile: Profile) = runBlocking {
        val s = ready().store
        s.profile.put(profile.copy(onboardingDone = true))
        s.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 80.0, completedAt = 1L))
    }

    @Test fun theOpenCardShowsTheIdolFileAndFoldsToOneLine() {
        launch()
        // While 入社建檔 asks, the card stays folded (the 入社建檔 panel has the buttons; the conversation keeps its room).
        waitTag(IdolTags.FOLDED)
        rule.onNodeWithTag(IdolTags.FOLDED).assert(state("已收起"))
        rule.onNodeWithTag(IdolTags.FOLDED).performClick()
        waitTag(IdolTags.NAME)
        rule.onNodeWithTag(AgencyTags.TRAINEE).assertExists()
        runBlocking {
            val s = ready().store
            s.profile.put(Profile(stageName = null, joinDate = today().minusDays(9).toString(), onboardingDone = false))
            val d = today().toString()
            s.meals.insert(Meal("m1", d, 1L, slotKey = DayPlanner.build(today()).firstMeal.name, text = "便當", breaches = listOf("PROCESSED"), items = listOf(MealItem("便當", 1.0, 714, 48.0, null, null, false, KcalSource.TABLE))))
            s.water.insert(WaterLog("w1", d, 2L, 2))
        }
        type("藝名 小雨")
        rule.waitForText("記下：藝名「小雨」。")
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("小雨", useUnmergedTree = true).assertExists() }.isSuccess }
        rule.onNodeWithTag(IdolTags.NUMBER, useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("小雨娛樂 ・ 練習生 第 001 號"))))
        rule.onNodeWithText("等級 練習生 C", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("入社第 10 天", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("今天還剩 986 大卡、蛋白質還差 92 克", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("今日違規 1", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("下一個目標 75 公斤", useUnmergedTree = true).assertExists()
        assertEquals(2, rule.onAllNodesWithTag(IdolTags.STICKER + "0", useUnmergedTree = true).fetchSemanticsNodes().size + rule.onAllNodesWithTag(IdolTags.STICKER + "1", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag(IdolTags.BAND, useUnmergedTree = true).assertExists()

        // Fold by a tap: one line, and TalkBack hears the state.
        rule.onNodeWithTag(IdolTags.NAME, useUnmergedTree = true).performClick()
        rule.waitForTag(IdolTags.FOLDED)
        rule.onNodeWithTag(IdolTags.FOLDED).assert(state("已收起"))
        rule.onNodeWithText("小雨娛樂 ・ 入社第 10 天 ・ 今日違規 1 ・ 剩 986 大卡").assertExists()
        rule.onNodeWithTag(IdolTags.FOLDED).performClick()
        waitTag(IdolTags.NAME)
    }

    @Test fun theFoldedLineSaysWhatIsMissingAndTheSetupAsksForNameAndColour() {
        launch()
        rule.waitForTag(IdolTags.CARD)
        seedFinished(Profile(joinDate = today().toString()))
        runBlocking { ready().brain.setIdol("小雨", null) } // bumps the revision
        // Done but six items missing: open by itself, with 補交; the setup asks for the colour.
        waitTag(IdolTags.SETUP)
        rule.onNodeWithTag(IdolTags.COLOR + "6", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.profile.get()?.memberColor } == MemberColour.PRESETS[6].second }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(IdolTags.SETUP, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag(IdolTags.NAME, useUnmergedTree = true).performClick()
        rule.waitForTag(IdolTags.FOLDED)
        rule.onNodeWithText("小雨娛樂 ・ 入社第 1 天 ・ 今日違規 0 ・ 剩 1700 大卡 ・ 入社資料缺 6 項").assertExists()
    }

    @Test fun notNowHidesTheSetupForGood() {
        launch()
        rule.waitForTag(IdolTags.CARD)
        seedFinished(Profile())
        runBlocking { ready().brain.setIdol(null, null) }
        waitTag(IdolTags.SETUP)
        rule.onNodeWithTag(IdolTags.SETUP_SKIP, useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(IdolTags.SETUP, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertTrue(app.agency.settings.idolSetupSkipped)
        rule.onNodeWithText("還沒有藝名", useUnmergedTree = true).assertExists()
        rule.onNode(state("已展開")).assertExists()
    }
}
