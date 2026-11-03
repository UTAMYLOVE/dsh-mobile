package app.dshmobile.ui.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {
    @Test fun fullHasEverything() {
        assertEquals(Effects(blur = true, ambient = true, spin = true, depthTransitions = true, countUp = true, pageMs = 560), effectsFor(EffectLevel.FULL, false))
    }

    @Test fun balancedOnlyDropsBlur() {
        assertEquals(Effects(false, true, true, true, true, 560), effectsFor(EffectLevel.BALANCED, false))
    }

    @Test fun saverFadesAndStopsEverythingThatMoves() {
        assertEquals(Effects(false, false, false, false, false, 200, perspectiveGrid = false), effectsFor(EffectLevel.SAVER, false))
    }

    /** SAVER is the safe mode: its grid is drawn flat (no 3D transform). The grid's shape is not motion: reduced motion keeps it. */
    @Test fun onlySaverDrawsTheGridFlat() {
        for (reduced in listOf(false, true)) {
            assertTrue(effectsFor(EffectLevel.FULL, reduced).perspectiveGrid)
            assertTrue(effectsFor(EffectLevel.BALANCED, reduced).perspectiveGrid)
            assertFalse(effectsFor(EffectLevel.SAVER, reduced).perspectiveGrid)
        }
    }

    @Test fun reducedMotionStopsAllMotionAtEveryLevel() {
        for (level in EffectLevel.entries) {
            val e = effectsFor(level, true)
            assertFalse("$level ambient", e.ambient)
            assertFalse("$level spin", e.spin)
            assertFalse("$level depth", e.depthTransitions)
            assertFalse("$level countUp", e.countUp)
            assertEquals("$level pageMs", 0, e.pageMs)
        }
        assertTrue(effectsFor(EffectLevel.FULL, true).blur)
        assertFalse(effectsFor(EffectLevel.BALANCED, true).blur)
        assertFalse(effectsFor(EffectLevel.SAVER, true).blur)
    }
}
