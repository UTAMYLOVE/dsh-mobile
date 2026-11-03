package app.dshmobile.ui.fog

import android.content.SharedPreferences

/** Small per-install UI choices: how much animation to draw, and which conversation was open last. */
class UiPrefs(private val prefs: SharedPreferences) {
    var effectLevel: EffectLevel
        get() = prefs.getString(KEY_LEVEL, null)?.let { name -> EffectLevel.entries.firstOrNull { it.name == name } } ?: EffectLevel.FULL
        set(value) {
            prefs.edit().putString(KEY_LEVEL, value.name).apply()
        }

    var lastSessionId: String?
        get() = prefs.getString(KEY_SESSION, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, value) }.apply()
        }

    companion object {
        const val PREFS = "dshm_ui"
        private const val KEY_LEVEL = "effect_level"
        private const val KEY_SESSION = "last_session_id"
    }
}
