package app.dshmobile.runtime

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The 「實驗：原生模式」 switch (owner, 2026-11-04): off = proot + Debian (the default), on = Android's own node. */
class RuntimeModeStore(private val prefs: SharedPreferences) {
    private val _native = MutableStateFlow(prefs.getBoolean(KEY, false))
    val native: StateFlow<Boolean> = _native

    fun set(native: Boolean) {
        prefs.edit().putBoolean(KEY, native).apply()
        _native.value = native
    }

    companion object {
        const val PREFS = "dshm_runtime"
        private const val KEY = "native"
    }
}

/** What Settings shows and changes: [available] is false when this APK has no native runtime (the switch is then off). */
class NativeModeControl(val available: Boolean, val enabled: StateFlow<Boolean>, val set: (Boolean) -> Unit)
