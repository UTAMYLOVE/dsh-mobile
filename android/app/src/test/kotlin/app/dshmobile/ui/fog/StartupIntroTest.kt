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
    private val opaque = mutableListOf<Boolean>()
    private var start = 0L

    private fun show(intro: Intro, reducedMotion: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                StartupIntro(intro, null, reducedMotion, onOpaqueChange = { opaque += it }) { done++ }
            }
        }
        start = rule.mainClock.currentTime
        at(100)
    }

    /** Moves the clock to [ms] after the intro was shown. */
    private fun at(ms: Long) {
        val now = rule.mainClock.currentTime - start
        if (ms > now) rule.mainClock.advanceTimeBy(ms - now)
    }

    @Test fun theBeatsGoByAndTheWelcomeNamesTheUser() {
        // At 4 s the marks are a 132, b 792, c 1448, hit 1592, d 2024, e 3608.
        show(Intro(enabled = true, name = "小雨", number = "No.0420", seconds = 4f))
        rule.onNodeWithText("需要訪問許可").assertIsDisplayed()
        at(1200)
        rule.onNodeWithText("身份確認").assertIsDisplayed()
        rule.onNodeWithText("No.0420").assertIsDisplayed()
        rule.onNodeWithText("Lv.1").assertIsDisplayed() // blank level: the default
        at(1700)
        rule.onNodeWithText("核驗通過").assertIsDisplayed()
        at(2300)
        rule.onNodeWithText("歡迎回來，小雨").assertIsDisplayed()
        assertEquals(0, done)
        assertEquals(listOf(true), opaque)
        at(4100)
        rule.waitForIdle()
        assertEquals(1, done)
        assertEquals(true, opaque.last() == false)
    }

    @Test fun aTapOpensTheExitAtOnceWhenAllowed() {
        show(Intro(enabled = true, seconds = 8f))
        rule.onNodeWithText("跳過").assertIsDisplayed()
        rule.onNodeWithTag(INTRO_TAG).performClick()
        // The tap opens the 280 ms iris at once: the screen underneath shows through and the intro is gone right after.
        at(100 + 60)
        assertEquals(listOf(true, false), opaque)
        assertEquals(0, done)
        at(100 + 400)
        rule.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun aTapDoesNothingWhenSkippingIsOff() {
        show(Intro(enabled = true, seconds = 8f, skippable = false))
        rule.onNodeWithTag(INTRO_TAG).performClick()
        at(600)
        assertEquals(0, done)
        rule.onNodeWithText("跳過").assertDoesNotExist()
    }

    @Test fun reducedMotionShowsOnlyTheWelcome() {
        show(Intro(enabled = true, welcome = "嗨", seconds = 2f), reducedMotion = true)
        rule.onNodeWithText("嗨").assertIsDisplayed()
        rule.onNodeWithText("需要訪問許可").assertDoesNotExist()
        at(2100)
        rule.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun theShortestAndLongestRunsEndOnTime() {
        show(Intro(enabled = true, name = "Mika", seconds = 1.5f))
        at(1400)
        assertEquals(0, done)
        at(1600)
        rule.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun theWelcomeLine() {
        assertEquals("歡迎回來", IntroTimeline.welcome(Intro(), "歡迎回來"))
        assertEquals("早安，阿明", IntroTimeline.welcome(Intro(welcome = " 早安 ", name = "阿明"), "歡迎回來"))
    }
}
