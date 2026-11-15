package app.dshmobile.ui.fog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
 * and the welcome line is announced. (櫻紋陣's own run is covered by StartupIntroTest.) 一筆描 is the short look: it runs its own
 * length for the setting (about 1.95 s, never past 2.5 s; IntroOneStrokeTest pins it), so it is held to that length here.
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

    /** How long [style] runs for a [seconds] setting: the setting itself, except the short 一筆描's own length. */
    private fun runMs(style: IntroStyle, seconds: Float): Long =
        if (style == IntroStyle.ONE_STROKE) OneStrokeTimes.lengthFor(seconds * 1000f).toLong() else (seconds * 1000).toLong()

    private fun playsOnTime(style: IntroStyle, seconds: Float) {
        show(Intro(enabled = true, name = "小雨", seconds = seconds, style = style))
        rule.onNodeWithTag(INTRO_TAG).assertExists()
        val end = runMs(style, seconds)
        at(end - 150)
        assertEquals("$style ended early", 0, done)
        assertEquals(true, opaque.first())
        at(end + 200)
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

    @Test fun oneStrokesStillIsAsShortAsTheLook() {
        // At the default 3 s the short look runs about 1.95 s, so its reduced-motion still is no longer than that either.
        show(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.ONE_STROKE), reducedMotion = true)
        val end = runMs(IntroStyle.ONE_STROKE, 3f)
        at(200)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
        at(end - 200)
        assertEquals(0, done)
        at(end + 100)
        rule.waitForIdle()
        assertEquals(1, done)
    }

    private fun announcesTheWelcome(style: IntroStyle) {
        show(Intro(enabled = true, name = "小雨", seconds = 3f, style = style, skippable = false))
        // Half a second before the end (2.5 s of the 3 s looks; 1.45 s of 一筆描's 1.95 s, once the name is written).
        val end = runMs(style, 3f)
        at(end - 500)
        rule.onNodeWithText("歡迎回來，小雨").assertExists()
        // Not skippable: no skip button for TalkBack, and a tap does nothing.
        rule.onNodeWithText("跳過").assertDoesNotExist()
        rule.onNodeWithTag(INTRO_TAG).performClick()
        at(end - 400)
        assertEquals(0, done)
    }

    @Test fun stardustAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.STARDUST)
    @Test fun petalStormAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.PETAL_STORM)
    @Test fun tunnelAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.TUNNEL)
    @Test fun oneStrokeAnnouncesTheWelcome() = announcesTheWelcome(IntroStyle.ONE_STROKE)

    @Test fun aLevelDropMidRunDoesNotChangeTheRunningLook() {
        // The jank governor steps the app down to SAVER a second into an 8 s 星屑迎接: the run keeps the level it started with,
        // so its own light iris still plays and the screen underneath stays hidden until the iris has covered it (on SAVER the
        // crossfade would start at the earlier exit mark).
        val tl = StardustTimeline.of(8000f)
        var level by androidx.compose.runtime.mutableStateOf(EffectLevel.FULL)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FogTheme(effectsFor(level, false)) {
                StartupIntro(Intro(enabled = true, name = "小雨", seconds = 8f, style = IntroStyle.STARDUST), null, false, onOpaqueChange = { opaque += it }) { done++ }
            }
        }
        start = rule.mainClock.currentTime
        at(1000)
        level = EffectLevel.SAVER
        at(((tl.exit + tl.hand) / 2f).toLong())
        assertEquals(listOf(true), opaque)
        at(8200)
        rule.waitForIdle()
        assertEquals(1, done)
    }
}
