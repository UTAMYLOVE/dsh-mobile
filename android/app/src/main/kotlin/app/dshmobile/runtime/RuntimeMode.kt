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

    private val _permission = MutableStateFlow(readLevel())

    /**
     * How much the agent may do without a question (設定 → 執行方式 → 權限), in both modes. Android has neither of dsh's sandbox
     * backends, so dsh's own "workspace-write" refused every command until the model escalated it and the owner approved, and
     * a subagent could not run a command at all. Conversations therefore run with full access inside Android's app sandbox
     * and the app's guard plugin (plugins/packages/guard) decides by this level. The owner, 2026-11-15: 「要做成那種可以全開指令
     * 也可以危險的擋不危險的不擋的那種」.
     */
    val permission: StateFlow<PermissionLevel> = _permission

    fun setPermission(level: PermissionLevel) {
        prefs.edit().putString(KEY_PERMISSION, level.id).apply()
        _permission.value = level
    }

    /** Before 2026-11-15 there was one switch, 「原生模式：指令不用每次問我」: on becomes [PermissionLevel.FULL]; everybody else starts at SMART. */
    private fun readLevel(): PermissionLevel =
        PermissionLevel.of(prefs.getString(KEY_PERMISSION, null))
            ?: if (prefs.getBoolean(KEY_NO_APPROVAL, false)) PermissionLevel.FULL else PermissionLevel.SMART

    companion object {
        const val PREFS = "dshm_runtime"
        private const val KEY = "native_v2"
        private const val KEY_NO_APPROVAL = "native_no_approval"
        private const val KEY_PERMISSION = "permission_level"
    }
}

/** What Settings shows and changes: [available] is false when this APK has no native runtime (the switch is then off). */
class NativeModeControl(
    val available: Boolean,
    val enabled: StateFlow<Boolean>,
    val set: (Boolean) -> Unit,
    val permission: StateFlow<PermissionLevel> = MutableStateFlow(PermissionLevel.SMART),
    val setPermission: (PermissionLevel) -> Unit = {},
)

/**
 * The three permission levels. [id] is what the guard plugin reads (`DSHM_GUARD_LEVEL`); [preset] is the dsh permission preset
 * new conversations start with: full access with questions allowed ([GUARDED_PRESET], so the guard can ask), or dsh's own
 * "danger-full-access" where nothing is ever asked.
 */
enum class PermissionLevel(val id: String, val preset: String) {
    /** Asks before every command that is not plainly read-only, every file change and before work is handed to other agents. */
    ASK("ask", GUARDED_PRESET),
    /** Ordinary work in the workspace runs; the risky kinds ask (deleting or writing elsewhere, sending data out, running downloaded code ...). */
    SMART("smart", GUARDED_PRESET),
    /** Never asks. */
    FULL("full", "danger-full-access");

    companion object {
        fun of(id: String?): PermissionLevel? = entries.firstOrNull { it.id == id }
    }
}

/** The dsh permission preset the app adds (DshPatch.permission): no dsh sandbox (there is none on Android), approvals on. */
const val GUARDED_PRESET = "dshm-guarded"
