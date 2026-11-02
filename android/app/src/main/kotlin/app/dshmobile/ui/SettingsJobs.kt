package app.dshmobile.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The settings actions that stop and start dsh or switch its version (apply, update, rollback, restart). They run in [scope],
 * which belongs to the application (`DshMobileApp.applicationScope`), not to the screen: leaving or destroying Settings must not
 * cut a stop -> switch -> start sequence short and leave dsh stopped. The screen only observes [busy] and the message flows, so a
 * reopened Settings shows a job that is still running. One job at a time.
 */
class SettingsJobs(private val scope: CoroutineScope) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Result of the last apply/restart, and of the last update/rollback; they survive the screen. */
    val applyMessage = MutableStateFlow<String?>(null)
    val versionMessage = MutableStateFlow<String?>(null)

    /** Starts [block] unless a job is already running; returns whether it started. */
    fun launch(block: suspend () -> Unit): Boolean {
        if (!_busy.compareAndSet(false, true)) return false
        scope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
        return true
    }
}
