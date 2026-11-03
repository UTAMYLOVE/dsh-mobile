package app.dshmobile.system

import android.content.ContentResolver
import android.provider.Settings

enum class PhantomState { DISABLED, ENABLED, UNKNOWN }

/**
 * Reads whether Android's phantom-process monitor (the limit that kills child processes of an app,
 * which multi-agent runs hit) is switched off. Read-only: the app never writes system settings,
 * the user flips the toggle in Developer options.
 */
class PhantomProcessDetector(private val resolver: ContentResolver) {
    fun state(): PhantomState {
        val value = try {
            Settings.Global.getString(resolver, SETTING)
        } catch (_: RuntimeException) {
            return PhantomState.UNKNOWN
        }
        return when (value) {
            "false" -> PhantomState.DISABLED
            "true" -> PhantomState.ENABLED
            else -> PhantomState.UNKNOWN
        }
    }

    private companion object {
        const val SETTING = "settings_enable_monitor_phantom_procs"
    }
}
