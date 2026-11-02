package app.dshmobile.update

import android.content.SharedPreferences

/**
 * "An update was requested but the install permission was missing": set when MainActivity sends the user to the
 * "install unknown apps" settings page, checked when the activity comes back to the foreground. Persisted, so it survives
 * the process being killed while the user is in Settings.
 */
class PendingUpdate(private val prefs: SharedPreferences) {
    var isPending: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) { prefs.edit().putBoolean(KEY, value).apply() }

    companion object {
        private const val KEY = "pending_install"
    }
}
