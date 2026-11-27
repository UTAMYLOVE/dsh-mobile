package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithText
import app.agency.core.schedule.RolloutSpec
import app.dshmobile.ui.agency.today.TodayTags
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Baseline
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.CompanyRules
import app.agency.core.model.Profile
import app.agency.core.model.WaterLog
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.DayPlanner
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.tapTag
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
        s.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 186.0, weightKg = 96.0, completedAt = 1L))
    }

    private fun open() {
        rule.onNodeWithTag(AgencyTags.TRAINEE).performClick()
        waitTag(AgencyTags.CARD_AREA)
    }

    private fun text(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    @Test fun theLineIsOneRowAndTheIdolCardOpensOverTheScreen() {
        launch()
        waitTag(IdolTags.FOLDED)
        rule.onNodeWithTag(AgencyTags.TRAINEE).assert(state("已收起"))
        runBlocking {
            val s = ready().store
            s.profile.put(Profile(stageName = null, joinDate = today().minusDays(9).toString(), onboardingDone = false))
            val d = today().toString()
            s.meals.insert(Meal("m1", d, 1L, slotKey = DayPlanner.build(today()).firstMeal.name, text = "便當", breaches = listOf("PROCESSED"), items = listOf(MealItem("便當", 1.0, 714, 48.0, null, null, false, KcalSource.TABLE))))
            s.water.insert(WaterLog("w1", d, 2L, 2))
            s.rules.put(CompanyRules(goalsKg = listOf(91.0, 84.0)), 3L) // invented targets (none ship in code)
        }
        type("藝名 小雨")
        rule.waitForText("記下：藝名「小雨」。")
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("小雨", useUnmergedTree = true).assertExists() }.isSuccess }
        // The line: name, grade, day and the stickers; no calories, breaches or missing items (F1, F3).
        rule.waitUntil(10_000) { text(IdolTags.FOLDED).startsWith("練習生 C ・ 入社第 10 天 ・ 貼紙") }
        listOf("大卡", "違規", "缺").forEach { assertFalse(it, text(IdolTags.FOLDED).contains(it)) }

        open()
        rule.onNodeWithTag(IdolTags.NUMBER, useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("小雨娛樂 ・ 練習生 第 001 號"))))
        rule.onNodeWithText("練習生 C", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("第 10 天", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(IdolTags.GOAL, useUnmergedTree = true).assertExists()
        assertTrue(text(IdolTags.GOAL).startsWith("下一個目標 91 公斤") || text(IdolTags.GOAL).startsWith("91 公斤"))
        rule.onNodeWithTag(IdolTags.BAND, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(IdolTags.STICKERS, useUnmergedTree = true).assertExists()
        // Folds back to the line.
        rule.onNodeWithContentDescription("收起").performClick()
        waitTag(AgencyTags.HOME)
        rule.onNodeWithTag(AgencyTags.TRAINEE).assertExists()
    }

    @Test fun withNoTargetSetThereIsNoGoalLineAtAll() {
        launch()
        waitTag(IdolTags.FOLDED)
        seedFinished(Profile(joinDate = today().toString(), stageName = "小雨", memberColor = MemberColour.PRESETS[0].second))
        runBlocking { ready().brain.setIdol("小雨", null) }
        open()
        rule.onNodeWithTag(IdolTags.NUMBER, useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithTag(IdolTags.GOAL, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("目標都到了", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun inWeekOneTheLineAndTheCardShowNothingThatIsNotOpenYet() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides(rollout = RolloutSpec.SEED)
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        waitTag(IdolTags.FOLDED)
        seedFinished(Profile(joinDate = today().minusDays(2).toString(), stageName = "小雨", memberColor = MemberColour.PRESETS[0].second))
        runBlocking { ready().brain.setIdol("小雨", null) }
        rule.waitUntil(10_000) { text(IdolTags.FOLDED) == "練習生 C ・ 入社第 3 天" }
        // The 今天 card: the lesson row and one 「… 第 2 週開放」 line; no water, meals or breaches anywhere.
        waitTag(TodayTags.HINT)
        assertTrue(text(TodayTags.HINT).endsWith("第 2 週開放"))
        listOf("大卡", "違規", "喝完一壺", "出門跑單", "拍這餐").forEach { w ->
            assertTrue(w, rule.onAllNodesWithText(w, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        open()
        assertTrue(rule.onAllNodesWithTag(IdolTags.STICKERS, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theSetupAsksForNameAndColourInsideTheCard() {
        launch()
        rule.waitForTag(IdolTags.CARD)
        seedFinished(Profile(joinDate = today().toString()))
        runBlocking { ready().brain.setIdol("小雨", null) } // bumps the revision
        open()
        waitTag(IdolTags.SETUP)
        rule.tapTag(IdolTags.COLOR + "6")
        rule.waitUntil(10_000) { runBlocking { ready().store.profile.get()?.memberColor } == MemberColour.PRESETS[6].second }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(IdolTags.SETUP, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun notNowHidesTheSetupForGood() {
        launch()
        rule.waitForTag(IdolTags.CARD)
        seedFinished(Profile())
        runBlocking { ready().brain.setIdol(null, null) }
        open()
        waitTag(IdolTags.SETUP)
        rule.tapTag(IdolTags.SETUP_SKIP)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(IdolTags.SETUP, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertTrue(app.agency.settings.idolSetupSkipped)
        rule.onNodeWithText("還沒有藝名", useUnmergedTree = true).assertExists()
    }
}
