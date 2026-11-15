package app.dshmobile.ui.fog

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

/**
 * Every look the picker offers plays through [StartupIntro] with the same contract: it covers the screen, ends exactly once on
 * time at 1.5 and 8 s, a tap opens the 280 ms exit, reduced motion is one short still, the skip button is there for TalkBack
 * and the welcome line is announced. (櫻紋陣's own run is covered by StartupIntroTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IntroStylesTest {
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
    }

    private fun at(ms: Long) {
        val now = rule.mainClock.currentTime - start
        if (ms > now) rule.mainClock.advanceTimeBy(ms - now)
    }

    private fun playsOnTime(style: IntroStyle, seconds: Float) {
        show(Intro(enabled = true, name = "小雨", seconds = seconds, style = style))
        rule.onNodeWithTag(INTRO_TAG).assertExists()
        at((seconds * 1000).toLong() - 150)
        assertEquals("$style ended early", 0, done)
        assertEquals(true, opaque.first())
        at((seconds * 1000).toLong() + 200)
        rule.waitForIdle()
        assertEquals("$style ends once", 1, done)
        assertEquals(false, opaque.last())
    }

    @Test fun stardustPlaysOnTime() = playsOnTime(IntroStyle.STARDUST, 1.5f)
    @Test fun petalStormPlaysOnTime() = playsOnTime(IntroStyle.PETAL_STORM, 8f)
    @Test fun tunnelPlaysOnTime() = playsOnTime(IntroStyle.TUNNEL, 1.5f)
    @Test fun oneStrokePlaysOnTime() = playsOnTime(IntroStyle.ONE_STROKE, 8f)
    @Test fun stardustLongRunPlaysOnTime() = playsOnTime(IntroStyle.STARDUST, 8f)
    @Test fun tunnelLongRunPlaysOnTime() = playsOnTime(IntroStyle.TUNNEL, 8f)
    @Test fun petalStormShortRunPlaysOnTime() = playsOnTime(IntroStyle.PETAL_STORM, 1.5f)
    @Test fun oneStrokeShortRunPlaysOnTime() = playsOnTime(IntroStyle.ONE_STROKE, 1.5f)

    private fun tapSkips(style: IntroStyle) {
        show(Intro(enabled = true, name = "小雨", seconds = 8f, style = style))
        at(300)
        rule.onNodeWithText("跳過").assertExists()
        rule.onNodeWithTag(INTRO_TAG).performClick()
        at(300 + 60)
        assertEquals(listOf(true, false), opaque)
        assertEquals(0, done)
        at(300 + 420)
        rule.waitForIdle()
        assertEquals("$style: a tap ends it within the 280 ms iris", 1, done)
    }

    @Test fun aTapSkipsStardust() = tapSkips(IntroStyle.STARDUST)
    @Test fun aTapSkipsPetalStorm() = tapSkips(IntroStyle.PETAL_STORM)
    @Test fun aTapSkipsTunnel() = tapSkips(IntroStyle.TUNNEL)
    @Test fun aTapSkipsOneStroke() = tapSkips(IntroStyle.ONE_STROKE)

    private fun reducedIsOneStill(style: IntroStyle) {
        show(Intro(enabled = true, name = "小雨", seconds = 8f, style = style), reducedMotion = true)
        at(200)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
        at(2400)
        assertEquals(0, done)
        at(2700)
        rule.waitForIdle()
        assertEquals("$style: the still shows for at most 2.5 s", 1, done)
    }

    @Test fun reducedMotionStardust() = reducedIsOneStill(IntroStyle.STARDUST)
    @Test fun reducedMotionPetalStorm() = reducedIsOneStill(IntroStyle.PETAL_STORM)
    @Test fun reducedMotionTunnel() = reducedIsOneStill(IntroStyle.TUNNEL)
    @Test fun reducedMotionOneStroke() = reducedIsOneStill(IntroStyle.ONE_STROKE)

    private fun announcesTheWelcome(style: IntroStyle) {
        show(Intro(enabled = true, name = "小雨", seconds = 3f, style = style, skippable = false))
        at(2500)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
        // Not skippable: no skip button for TalkBack, and a tap does nothing.
        rule.onNodeWithText("跳過").assertDoesNotExist()
        rule.onNodeWithTag(INTRO_TAG).performClick()
        at(2600)
        assertEquals(0, done)
    }

    @Test fun stardustAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.STARDUST)
    @Test fun petalStormAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.PETAL_STORM)
    @Test fun tunnelAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.TUNNEL)
    @Test fun oneStrokeAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.ONE_STROKE)
}
