package app.dshmobile.ui.fog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The effect level the screen draws with, one per application: the user's choice ([level], saved in [UiPrefs]) held down by the
 * [governor]'s cap when the screen drops frames ([effective]). Choosing a level takes effect at once and gives the governor a
 * fresh start.
 */
class EffectsController(private val prefs: UiPrefs, val governor: JankGovernor = JankGovernor()) {
    private val _level = MutableStateFlow(prefs.effectLevel)
    val level: StateFlow<EffectLevel> = _level
    private val _effective = MutableStateFlow(effectiveLevel(_level.value, governor.cap.value))
    val effective: StateFlow<EffectLevel> = _effective

    init {
        governor.floor = _level.value
        governor.onCapChanged = ::recompute
    }

    @Synchronized private fun recompute() {
        _effective.value = effectiveLevel(_level.value, governor.cap.value)
    }

    fun set(level: EffectLevel) {
        prefs.effectLevel = level
        _level.value = level
        governor.floor = level
        governor.reset() // also recomputes
        recompute()
    }

    fun effects(reducedMotion: Boolean): Effects = effectsFor(effective.value, reducedMotion)
}
