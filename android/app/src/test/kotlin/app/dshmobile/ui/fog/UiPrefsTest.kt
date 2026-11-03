package app.dshmobile.ui.fog

import app.dshmobile.gateway.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiPrefsTest {
    @Test fun effectLevelDefaultsToFullAndPersists() {
        val store = InMemorySharedPreferences()
        val prefs = UiPrefs(store)
        assertEquals(EffectLevel.FULL, prefs.effectLevel)
        assertNull(prefs.lastSessionId)
        prefs.effectLevel = EffectLevel.SAVER
        prefs.lastSessionId = "s-1"
        val again = UiPrefs(store)
        assertEquals(EffectLevel.SAVER, again.effectLevel)
        assertEquals("s-1", again.lastSessionId)
        again.lastSessionId = null
        assertNull(UiPrefs(store).lastSessionId)
        assertEquals("dshm_ui", UiPrefs.PREFS)
    }

    @Test fun anUnknownStoredLevelFallsBackToFull() {
        val store = InMemorySharedPreferences()
        store.edit().putString("effect_level", "TURBO").apply()
        assertEquals(EffectLevel.FULL, UiPrefs(store).effectLevel)
    }
}
