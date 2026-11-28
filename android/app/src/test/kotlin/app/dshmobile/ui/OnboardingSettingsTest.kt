package app.dshmobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.dshmobile.ui.onboarding.OnboardingFlow
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsRoot
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h828dp-xhdpi")
class OnboardingSettingsTest {
    @get:Rule val rule = createComposeRule()

    private fun ComposeContentTestRule.count(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun ComposeContentTestRule.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun onboardingWalksFromWelcomeToDayOne() {
        val ob = DemoOnboarding()
        val finished = AtomicBoolean()
        rule.show { OnboardingFlow(ob, { finished.set(true) }) }
        rule.onNodeWithText("歡迎來到小雨娛樂").assertIsDisplayed()
        rule.onNodeWithTag("ob_next").performClick()
        // keys: the next button needs the first key
        rule.onNodeWithTag("ob_next").assertIsNotEnabled()
        rule.onNodeWithTag("key_claude").performTextInput("abc")
        rule.onNodeWithTag("ob_next").assertIsEnabled()
        rule.onNodeWithTag("ob_next").performClick()
        // paste
        rule.until { rule.count("document") > 0 }
        rule.onNodeWithTag("document").performTextInput("起床七點，每天兩千大卡。")
        rule.onNodeWithTag("ob_next").performClick()
        // 小葵 reads, then the summary
        rule.until { rule.has("我整理成這樣，對嗎？") }
        rule.onNodeWithText("作息").assertIsDisplayed()
        rule.onNodeWithText("還缺：跑單收入").assertIsDisplayed()
        rule.onNodeWithTag("ob_next").performClick()
        // names
        rule.onNodeWithTag("call_name").performTextInput("阿星")
        rule.onNodeWithTag("stage_name").performTextInput("小星")
        rule.onNodeWithTag("color_#88AAFF").performClick()
        rule.onNodeWithTag("ob_next").performClick()
        rule.until { rule.has("最後，讓小葵姐能提醒你") }
        rule.onNodeWithTag("perm_notify").performClick()
        rule.onNodeWithTag("ob_next").performClick()
        rule.until { finished.get() }
        assertEquals(listOf("keys", "confirm:小星:#88AAFF", "notify", "finish"), ob.log.toList())
    }

    @Test fun aKeyThatCannotBeSavedShowsAnError() {
        val ob = DemoOnboarding()
        rule.show { OnboardingFlow(ob, {}, startAt = app.dshmobile.ui.onboarding.OnboardStep.KEYS) }
        rule.onNodeWithTag("key_claude").performTextInput("x")
        rule.onNodeWithTag("ob_next").performClick()
        rule.until { rule.has("存不起來，請再試一次。") || rule.count("document") > 0 }
    }

    @Test fun settingsMenuListsEveryPage() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}) }
        SettingsPage.entries.drop(1).forEach { rule.onNodeWithTag("set_${it.name}").assertIsDisplayed() }
    }

    @Test fun savingCompanyRulesAsksTwice() {
        val settings = DemoSettings()
        rule.show { SettingsRoot(settings, DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.RULES) }
        rule.until { rule.count("rule_wake") > 0 }
        rule.onNodeWithTag("rules_save").assertIsNotEnabled()
        rule.onNodeWithTag("rule_wake").performTextReplacement("07:30")
        rule.onNodeWithTag("rules_save").assertIsEnabled()
        rule.onNodeWithTag("rules_save").performClick()
        rule.onNodeWithTag("confirm_1").assertIsDisplayed()
        rule.onNodeWithTag("confirm_yes").performClick()
        rule.onNodeWithTag("confirm_2").assertIsDisplayed()
        assertTrue(settings.saved.isEmpty())
        rule.onNodeWithTag("confirm_yes").performClick()
        rule.until { settings.saved.size == 1 }
        assertEquals(mapOf("wake" to "07:30"), settings.saved[0])
        rule.until { rule.has("已存好。") }
    }

    @Test fun cancellingTheConfirmationSavesNothing() {
        val settings = DemoSettings()
        rule.show { SettingsRoot(settings, DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.RULES) }
        rule.until { rule.count("rule_wake") > 0 }
        rule.onNodeWithTag("rule_wake").performTextReplacement("07:30")
        rule.onNodeWithTag("rules_save").performClick()
        rule.onNodeWithTag("confirm_cancel").performClick()
        assertTrue(settings.saved.isEmpty())
    }

    @Test fun modelPageShowsChoicesAndKeys() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.MODEL) }
        rule.until { rule.count("tier_balanced") > 0 }
        rule.onNodeWithTag("model_claude").assertIsDisplayed()
        rule.onNodeWithText("已經設定好了").assertIsDisplayed()
    }

    @Test fun spendMemoryRemindBackupImportOldVersionLicensesRender() {
        val pages = listOf(
            SettingsPage.SPEND to "今天花了 4 元",
            SettingsPage.MEMORY to "喜歡吃烤地瓜",
            SettingsPage.REMIND to "試一則",
            SettingsPage.BACKUP to "備份",
            SettingsPage.IMPORT to "選檔案",
            SettingsPage.OLD to "今天吃了便當",
            SettingsPage.VERSION to "有新版本 1.0.1",
            SettingsPage.LICENSES to "字體 · 霞鶩文楷",
        )
        var page by androidx.compose.runtime.mutableStateOf(pages[0].first)
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = page) }
        pages.forEach { (p, text) ->
            rule.runOnIdle { page = p }
            rule.until { rule.has(text) }
        }
    }

    @Test fun backupExportNeedsALongEnoughPassword() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.BACKUP) }
        rule.onNodeWithTag("backup_export").assertIsNotEnabled()
        rule.onNodeWithTag("backup_pass").performTextInput("secret1")
        rule.onNodeWithTag("backup_export").performClick()
        rule.until { rule.has("備份好了，檔案在下載資料夾。") }
    }

    @Test fun importPreviewsThenRestoresAfterTwoConfirmations() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.IMPORT) }
        rule.onNodeWithTag("import_pass").performTextInput("secret1")
        rule.onNodeWithTag("import_pick").performClick()
        rule.until { rule.count("import_preview") > 0 }
        rule.onNodeWithTag("import_apply").performClick()
        rule.onNodeWithTag("confirm_yes").performClick()
        rule.onNodeWithTag("confirm_yes").performClick()
        rule.until { rule.count("import_result") > 0 }
    }

    @Test fun oldDataIsDeletedOnlyAfterTwoConfirmations() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.OLD) }
        rule.until { rule.count("old_delete") > 0 }
        rule.onNodeWithTag("old_delete").performClick()
        rule.onNodeWithTag("confirm_yes").performClick()
        assertTrue(rule.has("今天吃了便當"))
        rule.onNodeWithTag("confirm_yes").performClick()
        rule.until { rule.has("舊版資料已經刪掉了。") }
    }
}
