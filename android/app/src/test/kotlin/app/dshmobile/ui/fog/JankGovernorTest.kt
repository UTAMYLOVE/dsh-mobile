package app.dshmobile.ui.fog

import org.junit.Assert.assertEquals
import org.junit.Test

class JankGovernorTest {
    private fun JankGovernor.window(janky: Int, frames: Int = 120) = repeat(frames) { onFrame(it < janky) }

    @Test fun smoothFramesNeverDegrade() {
        val g = JankGovernor()
        repeat(20) { g.window(0) }
        assertEquals(EffectLevel.FULL, g.cap.value)
        assertEquals(2400L, g.frames)
        assertEquals(0L, g.janky)
    }

    @Test fun oneBadWindowIsNotEnough() {
        val g = JankGovernor()
        g.window(60)
        assertEquals(EffectLevel.FULL, g.cap.value)
    }

    @Test fun twoBadWindowsInARowStepDownOnce() {
        val g = JankGovernor()
        g.window(12); g.window(12)
        assertEquals(EffectLevel.BALANCED, g.cap.value)
        g.window(12)
        assertEquals("the count starts over after a step", EffectLevel.BALANCED, g.cap.value)
        g.window(12)
        assertEquals(EffectLevel.SAVER, g.cap.value)
        repeat(6) { g.window(120) }
        assertEquals(EffectLevel.SAVER, g.cap.value)
    }

    @Test fun elevenJankyFramesIsStillAGoodWindow() {
        val g = JankGovernor()
        repeat(6) { g.window(11) }
        assertEquals(EffectLevel.FULL, g.cap.value)
    }

    @Test fun aGoodWindowBetweenResetsTheStreak() {
        val g = JankGovernor()
        g.window(30); g.window(0); g.window(30)
        assertEquals(EffectLevel.FULL, g.cap.value)
        g.window(30)
        assertEquals(EffectLevel.BALANCED, g.cap.value)
    }

    @Test fun resetGoesBackToFull() {
        val g = JankGovernor()
        repeat(4) { g.window(30) }
        assertEquals(EffectLevel.SAVER, g.cap.value)
        g.reset()
        assertEquals(EffectLevel.FULL, g.cap.value)
        g.window(30)
        assertEquals("a half streak from before the reset does not count", EffectLevel.FULL, g.cap.value)
    }

    @Test fun withAutoDegradeOffItOnlyCounts() {
        val g = JankGovernor()
        g.autoDegrade = false
        repeat(4) { g.window(24) }
        assertEquals(EffectLevel.FULL, g.cap.value)
        assertEquals(96L, g.janky)
        assertEquals(480L, g.frames)
    }

    @Test fun degradingTellsTheListenerWhatItSaw() {
        val g = JankGovernor()
        val seen = mutableListOf<Triple<EffectLevel, Int, Int>>()
        g.onDegrade = { level, janky, frames -> seen += Triple(level, janky, frames) }
        g.window(24); g.window(24)
        assertEquals(listOf(Triple(EffectLevel.BALANCED, 48, 240)), seen)
    }

    @Test fun effectiveLevelTakesTheThriftierOne() {
        assertEquals(EffectLevel.BALANCED, effectiveLevel(EffectLevel.FULL, EffectLevel.BALANCED))
        assertEquals(EffectLevel.SAVER, effectiveLevel(EffectLevel.SAVER, EffectLevel.BALANCED))
        assertEquals(EffectLevel.FULL, effectiveLevel(EffectLevel.FULL, EffectLevel.FULL))
        assertEquals(EffectLevel.SAVER, effectiveLevel(EffectLevel.BALANCED, EffectLevel.SAVER))
    }
}
