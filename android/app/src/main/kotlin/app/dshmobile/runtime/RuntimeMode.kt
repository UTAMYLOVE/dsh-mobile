package app.dshmobile.runtime

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How dsh runs. Since 0.7.0 Android's own node is the default (the owner, 2026-11-10: proot 「讓手機又卡又耗電」); off is the
 * older proot + Debian, kept as 「相容模式」 for a phone where the native runtime does not work. The key is new on purpose:
 * 0.5.1 switched everybody's old `native` flag off.
 */
class RuntimeModeStore(private val prefs: SharedPreferences) {
    private val _native = MutableStateFlow(prefs.getBoolean(KEY, true))
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
        private const val KEY = "native_v2"
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
