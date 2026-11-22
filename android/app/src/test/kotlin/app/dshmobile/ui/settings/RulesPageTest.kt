package app.dshmobile.ui.settings

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.PlanSectionKey
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 設定 › 公司規章 and 作息 (arch T11): read-only, 修改 needs two confirmations and the bounds; the plan is seeded on open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class RulesPageTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("rules-page").toFile()
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

    private fun open(page: SettingsPage) {
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + page.name).performClick()
    }

    private fun setField(tag: String, value: String) {
        rule.onNodeWithTag(tag).performScrollTo().performTextClearance()
        rule.onNodeWithTag(tag).performTextInput(value)
    }

    @Test fun theRulesChangeOnlyAfterTwoConfirmationsWithHistoryAndAudit() {
        launch()
        open(SettingsPage.RULES)
        rule.waitForText("每天 1700 大卡、蛋白質 140 克")
        rule.waitForText("休假餐 2026 年 12 月 31 日前暫停")
        rule.onNodeWithTag(RulesTags.EDIT).performScrollTo().performClick()
        setField(RulesTags.QUOTA, "1800")
        rule.onNodeWithTag(RulesTags.SAVE).performScrollTo().performClick()
        rule.waitForTag(RulesTags.CONFIRM_1)
        assertNull(runBlocking { store().rules.current() })
        rule.onNodeWithTag(RulesTags.CONFIRM_1).performClick()
        rule.waitForTag(RulesTags.CONFIRM_2)
        assertNull(runBlocking { store().rules.current() })
        rule.onNodeWithTag(RulesTags.CONFIRM_2).performClick()
        rule.waitUntil(10_000) { runBlocking { store().rules.current() }?.quotaKcal == 1800 }
        rule.waitForText("已儲存，公司規章第 1 版。")
        assertEquals(1, runBlocking { store().rules.history() }.size)
        assertEquals("v1: quota_kcal 1700→1800", runBlocking { store().journal.audits() }.single { it.what == "rules_edit" }.detail)
    }

    @Test fun aRuleOutsideTheBoundsIsRefusedAndNothingIsWritten() {
        launch()
        open(SettingsPage.RULES)
        rule.waitForTag(RulesTags.EDIT)
        rule.onNodeWithTag(RulesTags.EDIT).performScrollTo().performClick()
        setField(RulesTags.FLOOR, "1000")
        rule.onNodeWithTag(RulesTags.SAVE).performScrollTo().performClick()
        rule.onNodeWithTag(RulesTags.CONFIRM_1).performClick()
        rule.onNodeWithTag(RulesTags.CONFIRM_2).performClick()
        rule.waitForText("最低熱量不能低於 1200 大卡。")
        assertNull(runBlocking { store().rules.current() })
        assertTrue(runBlocking { store().journal.audits() }.none { it.what == "rules_edit" })
    }

    @Test fun thePlanIsSeededOnOpenAndTheScheduleShowsSevenDays() {
        launch()
        rule.waitUntil(10_000) { runBlocking { store().rules.sections() }.size == 4 }
        assertEquals(PlanSectionKey.entries.toList(), runBlocking { store().rules.sections() }.map { it.section })
        open(SettingsPage.SCHEDULE)
        rule.waitForTag(SCHEDULE_DAY + "6")
        assertEquals(7, (0..6).sumOf { rule.onAllNodesWithTag(SCHEDULE_DAY + it).fetchSemanticsNodes().size })
    }
}
