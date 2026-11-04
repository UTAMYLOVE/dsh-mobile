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

    private val _noApproval = MutableStateFlow(prefs.getBoolean(KEY_NO_APPROVAL, false))
    /**
     * The owner's own choice, off by default (2026-11-05: 「原生的還是要我手動」): in native mode no dsh sandbox backend exists, so
     * dsh asks before nearly every command; on, dsh runs commands without asking. Android's app sandbox still keeps them to
     * the app's own files.
     */
    val noApproval: StateFlow<Boolean> = _noApproval

    fun setNoApproval(on: Boolean) {
        prefs.edit().putBoolean(KEY_NO_APPROVAL, on).apply()
        _noApproval.value = on
    }

    companion object {
        const val PREFS = "dshm_runtime"
        private const val KEY = "native"
        private const val KEY_NO_APPROVAL = "native_no_approval"
    }
}

/** What Settings shows and changes: [available] is false when this APK has no native runtime (the switch is then off). */
class NativeModeControl(
    val available: Boolean,
    val enabled: StateFlow<Boolean>,
    val set: (Boolean) -> Unit,
    val noApproval: StateFlow<Boolean> = MutableStateFlow(false),
    val setNoApproval: (Boolean) -> Unit = {},
)
