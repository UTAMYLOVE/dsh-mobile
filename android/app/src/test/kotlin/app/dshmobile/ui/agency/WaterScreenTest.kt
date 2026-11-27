package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Baseline
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.schedule.PlanStore
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.tapTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsTags
import app.dshmobile.ui.settings.WATER_START
import app.dshmobile.ui.settings.WATER_START_SAVE
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 喝水 on screen (T17): the quick buttons record plain water, the 練習生證 shows the bottle, 設定 › 作息 sets the plan's start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class WaterScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("water").toFile()
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
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 186.0, weightKg = 94.0, completedAt = 1))
        }
        app.agency.send("刮好了")
        rule.waitForTag(AgencyTags.WATER_FULL, 30_000)
    }

    private fun halves() = runBlocking { ready().store.water.between("0000-01-01", "9999-12-31") }.sumOf { it.halfBottles }

    @Test fun theButtonsRecordTheBottleAndTheCardShowsIt() {
        open()
        rule.tapTag(AgencyTags.WATER_HALF, 30_000)
        rule.waitUntil(10_000) { halves() == 1 }
        rule.waitForText("記下：喝了半壺。", 30_000)
        rule.tapTag(AgencyTags.WATER_FULL, 30_000)
        rule.waitUntil(10_000) { halves() == 3 }
        assertEquals(3, runBlocking { ready().brain.card() }.waterHalves)
    }

    @Test fun theWaterPlanStartIsHisToSet() {
        open()
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.SCHEDULE.name).performScrollTo().performClick()
        rule.waitForTag(WATER_START)
        rule.onNodeWithTag(WATER_START).performScrollTo().performTextInput("11/21")
        rule.onNodeWithTag(WATER_START_SAVE).performScrollTo().performClick()
        rule.waitForText("日期要寫成 2026-11-21 這樣。")
        rule.onNodeWithTag(WATER_START).performTextClearance()
        rule.onNodeWithTag(WATER_START).performTextInput("2026-11-21")
        rule.onNodeWithTag(WATER_START_SAVE).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { PlanStore(ready().store).specs() }.water.startDay == "2026-11-21" }
        assertEquals(PlanSection.BY_OWNER_SETTINGS, runBlocking { ready().store.rules.section(PlanSectionKey.WATER) }!!.changedBy)
    }
}
