package app.dshmobile.ui.fog

import app.dshmobile.gateway.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsControllerTest {
    private val store = InMemorySharedPreferences()
    private val prefs = UiPrefs(store)

    private fun JankGovernor.bad(windows: Int) = repeat(windows * 120) { onFrame(true) }

    @Test fun startsFromThePrefsAndPersistsChanges() {
        prefs.effectLevel = EffectLevel.BALANCED
        val c = EffectsController(prefs)
        assertEquals(EffectLevel.BALANCED, c.level.value)
        c.set(EffectLevel.SAVER)
        assertEquals(EffectLevel.SAVER, c.level.value)
        assertEquals(EffectLevel.SAVER, c.effective.value)
        assertEquals(EffectLevel.SAVER, UiPrefs(store).effectLevel)
    }

    @Test fun settingALevelResetsTheCap() {
        val c = EffectsController(prefs)
        c.governor.bad(4)
        assertEquals(EffectLevel.SAVER, c.effective.value)
        c.set(EffectLevel.FULL)
        assertEquals(EffectLevel.FULL, c.effective.value)
        assertEquals(EffectLevel.FULL, c.governor.cap.value)
    }

    @Test fun effectiveFollowsTheCap() {
        val c = EffectsController(prefs)
        assertEquals(EffectLevel.FULL, c.level.value)
        c.governor.bad(2)
        assertEquals(EffectLevel.BALANCED, c.effective.value)
        assertEquals("the user's own choice is not changed by the cap", EffectLevel.FULL, c.level.value)
    }

    @Test fun effectsComeFromTheEffectiveLevel() {
        val c = EffectsController(prefs)
        assertTrue(c.effects(reducedMotion = false).blur)
        c.governor.bad(2)
        assertFalse(c.effects(reducedMotion = false).blur)
        assertEquals(effectsFor(EffectLevel.BALANCED, true), c.effects(reducedMotion = true))
    }

    @Test fun settingALevelSetsTheGovernorsFloor() {
        val c = EffectsController(prefs)
        c.set(EffectLevel.BALANCED)
        assertEquals(EffectLevel.BALANCED, c.governor.floor)
        c.governor.bad(2)
        assertEquals(EffectLevel.SAVER, c.effective.value)
    }

    @Test fun theFloorStartsAtThePrefsLevel() {
        prefs.effectLevel = EffectLevel.SAVER
        assertEquals(EffectLevel.SAVER, EffectsController(prefs).governor.floor)
    }
}
