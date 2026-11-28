package app.dshmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.dshmobile.ui.onboarding.OnboardingFlow
import app.dshmobile.ui.pages.LessonsPage
import app.dshmobile.ui.pages.QuizPage
import app.dshmobile.ui.pages.TimerPage
import app.dshmobile.ui.pages.TraineePage
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsRoot
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * When the system property `dshm.shots` is set (gradle `-Pdshm.shots=<dir>`), saves pictures of the main screens there so the
 * owner can be shown what it looks like. Without the property these tests do nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h828dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun only() = assumeTrue(shotsDir() != null)

    private fun ComposeContentTestRule.count(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun ComposeContentTestRule.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun shot(name: String) {
        val dir = shotsDir() ?: return
        rule.waitForIdle()
        val bmp = grabWindow(rule.activity)
        val file = File(dir, "$name.png")
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot saved: ${file.absolutePath} (${file.length()} bytes, ${bmp.width}x${bmp.height})")
        assertTrue("empty picture: ${file.name}", file.length() > 0)
    }

    @Test fun home() {
        rule.show { UiRoot(demoGraph(DemoCompany(), MemoryPanelPrefs())) }
        rule.until { rule.count("conversation") > 0 }
        shot("home-folded")
        rule.onNodeWithTag("toggle_handbook").performClick()
        shot("home-handbook-open")
        rule.onNodeWithTag("toggle_handbook").performClick()
        rule.onNodeWithTag("toggle_chips").performClick()
        shot("home-chips-folded")
        rule.onNodeWithTag("toggle_stickers").performClick()
        shot("home-stickers-folded")
    }

    @Test fun homeWithEveryCard() {
        rule.show { UiRoot(demoGraph(DemoCompany(allCards = true, failedMessage = true), MemoryPanelPrefs())) }
        rule.until { rule.count("conversation") > 0 }
        shot("home-cards")
    }

    @Test fun onboarding() {
        rule.show { OnboardingFlow(DemoOnboarding(), {}) }
        shot("onboarding-1-welcome")
        rule.onNodeWithTag("ob_next").performClick()
        rule.onNodeWithTag("key_claude").performTextInput("abcdef")
        shot("onboarding-2-keys")
        rule.onNodeWithTag("ob_next").performClick()
        rule.until { rule.count("document") > 0 }
        rule.onNodeWithTag("document").performTextInput("起床七點，每天兩千大卡。")
        shot("onboarding-3-paste")
        rule.onNodeWithTag("ob_next").performClick()
        rule.until { rule.has("我整理成這樣，對嗎？") }
        shot("onboarding-4-confirm")
        rule.onNodeWithTag("ob_next").performClick()
        rule.onNodeWithTag("call_name").performTextInput("阿星")
        shot("onboarding-5-name")
        rule.onNodeWithTag("ob_next").performClick()
        rule.until { rule.has("最後，讓小葵姐能提醒你") }
        shot("onboarding-6-permissions")
    }

    @Test fun lessons() {
        rule.show { LessonsPage(DemoLessons(), DemoCompany(), {}, { _, _ -> }, {}) }
        rule.until { rule.count("lesson_1") > 0 }
        shot("lessons")
    }

    @Test fun quiz() {
        rule.show { QuizPage(DemoLessons(), 3, false, {}, {}) }
        rule.until { rule.count("question_0") > 0 }
        rule.onNodeWithTag("option_0_1").performClick()
        shot("quiz")
    }

    @Test fun timer() {
        rule.mainClock.autoAdvance = false
        rule.show { TimerPage(DemoTimer(), "CORE", {}, {}, tickMs = 100_000) }
        rule.mainClock.advanceTimeBy(100)
        rule.until { rule.count("timer_start") > 0 }
        shot("timer")
    }

    @Test fun traineeCard() {
        rule.show { TraineePage(DemoTrainee(), DemoMedia(), {}) }
        rule.until { rule.count("trainee_card") > 0 }
        shot("trainee-card")
    }

    @Test fun settings() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}) }
        shot("settings")
    }

    @Test fun settingsRules() {
        rule.show { SettingsRoot(DemoSettings(), DemoBackup(), DemoOldData(), {}, startAt = SettingsPage.RULES) }
        rule.until { rule.count("rule_wake") > 0 }
        shot("settings-rules")
    }
}
