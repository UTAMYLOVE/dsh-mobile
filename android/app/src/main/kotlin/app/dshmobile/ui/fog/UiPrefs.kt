package app.dshmobile.ui.fog

import android.content.SharedPreferences

/** Small per-install UI choices: how much animation to draw, which conversation was open last, each conversation's 審查輪數. */
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

    /**
     * A conversation's 審查輪數 (0 = 不審). Kept here because the chat's cache dies with the app process, and the owner's
     * 「審查 N 輪」 then silently went back to 不審. A stored value, not a setting; 0 is not stored, so only conversations with a
     * choice leave a key.
     */
    fun reviewRounds(sessionId: String): Int =
        prefs.getInt(KEY_ROUNDS + sessionId, 0).coerceIn(0, app.dshmobile.chat.ReviewRounds.MAX)

    fun setReviewRounds(sessionId: String, rounds: Int) {
        prefs.edit().apply { if (rounds <= 0) remove(KEY_ROUNDS + sessionId) else putInt(KEY_ROUNDS + sessionId, rounds) }.apply()
    }

    companion object {
        const val PREFS = "dshm_ui"
        private const val KEY_LEVEL = "effect_level"
        private const val KEY_SESSION = "last_session_id"
        private const val KEY_ROUNDS = "review_rounds:"
    }
}
