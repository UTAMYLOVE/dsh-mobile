package app.dshmobile.ui.fog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupIntroTest {
    @get:Rule val rule = createComposeRule()
    private var done = 0

    private fun show(intro: Intro, reducedMotion: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent { FogTheme(effectsFor(EffectLevel.FULL, false)) { StartupIntro(intro, null, reducedMotion) { done++ } } }
        rule.mainClock.advanceTimeBy(100)
    }

    @Test fun theStepsGoByAndTheWelcomeNamesTheUser() {
        show(Intro(enabled = true, name = "小雨", number = "No.0420", seconds = 4f))
        rule.onNodeWithText("需要訪問許可").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1200) // 32%
        rule.onNodeWithText("身份確認中…").assertIsDisplayed()
        rule.onNodeWithText("No.0420").assertIsDisplayed()
        rule.onNodeWithText("Lv.1").assertIsDisplayed() // blank level: the default
        rule.mainClock.advanceTimeBy(1000) // 57%
        rule.onNodeWithText("核驗通過 ♡").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1000) // 82%
        rule.onNodeWithText("歡迎回來，小雨").assertIsDisplayed()
        assertEquals(0, done)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun aTapSkipsWhenAllowed() {
        show(Intro(enabled = true, seconds = 8f))
        rule.onNodeWithTag(INTRO_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun aTapDoesNothingWhenSkippingIsOff() {
        show(Intro(enabled = true, seconds = 8f, skippable = false))
        rule.onNodeWithTag(INTRO_TAG).performClick()
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0, done)
        rule.onNodeWithText("點一下跳過").assertDoesNotExist()
    }

    @Test fun reducedMotionShowsOnlyTheWelcome() {
        show(Intro(enabled = true, welcome = "嗨", seconds = 2f), reducedMotion = true)
        rule.onNodeWithText("嗨").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2100)
        rule.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun theTimelineAndWelcomeLine() {
        assertEquals(IntroStep.PERMISSION, IntroTimeline.step(0f))
        assertEquals(IntroStep.CHECKING, IntroTimeline.step(.3f))
        assertEquals(IntroStep.PASSED, IntroTimeline.step(.6f))
        assertEquals(IntroStep.WELCOME, IntroTimeline.step(1f))
        assertEquals("歡迎回來", IntroTimeline.welcome(Intro(), "歡迎回來"))
        assertEquals("早安，阿明", IntroTimeline.welcome(Intro(welcome = " 早安 ", name = "阿明"), "歡迎回來"))
    }
}
