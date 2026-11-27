package app.dshmobile.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Baseline
import app.agency.core.model.Profile
import app.agency.core.schedule.RolloutSpec
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTab
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.agency.meal.MealTags
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

/** 設定 › 上線週數 (分週上線): a new install is in week 1 (no meal or ride buttons); one confirmed step opens week 2. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class RolloutPageTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("rollout-page").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    @Test fun weekOneHidesWhatIsClosedAndOneConfirmedStepOpensWeekTwo() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides(rollout = RolloutSpec.SEED)
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, completedAt = 1))
        }
        app.agency.send("刮好了")
        // The quick buttons are there (「這裡有問題」), but not the closed ones.
        rule.waitForTag(AgencyTags.DISCUSS, 30_000)
        assertEquals(1, runBlocking { ready().brain.rollout() }.week)
        assertTrue(rule.onAllNodesWithTag(MealTags.PHOTO).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag(AgencyTags.RIDE_START).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.ROLLOUT.name).performScrollTo().performClick()
        rule.waitForTag(RolloutTags.NOW)
        rule.onNodeWithTag(RolloutTags.NOW).assertTextEquals("現在是第 1 週")
        rule.onNodeWithTag(RolloutTags.WEEK + "2").assertTextEquals("第 2 週：簽到簽退、三餐拍照、喝水、核心、肌力和伸展、出門跑單、天氣提醒、外送收入、全勤小卡（還沒開放）")
        rule.onNodeWithTag(RolloutTags.LATER).performClick()
        rule.onNodeWithTag(RolloutTags.CONFIRM).performClick()
        rule.waitUntil(20_000) { runBlocking { ready().brain.rollout() }.week == 2 }
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(RolloutTags.NOW).assertTextEquals("現在是第 2 週") }.isSuccess }
        assertEquals(2, runBlocking { ready().brain.rollout() }.week)
    }
}
