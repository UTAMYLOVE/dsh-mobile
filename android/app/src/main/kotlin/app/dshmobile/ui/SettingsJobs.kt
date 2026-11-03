package app.dshmobile.ui

import app.dshmobile.service.DshHolderRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The actions that stop and start dsh or switch its version (Settings' apply, update, rollback and restart, and the main
 * screen's rollback). They run in [scope], which belongs to the application (`DshMobileApp.applicationScope`), not to a screen:
 * leaving or destroying a screen must not cut a stop -> switch -> start sequence short and leave dsh stopped. Screens only
 * observe [busy] and the message flows, so a reopened Settings shows a job that is still running. One job at a time, so an update
 * and a rollback never interleave their stops and starts.
 *
 * While a job runs it holds dsh as [HOLDER] (an update's npm install can outlast the UI's ten-minute grace); afterwards it lets
 * go and stops dsh if nobody else holds it, like every other holder.
 */
class SettingsJobs(private val scope: CoroutineScope, private val holders: DshHolderRuntime) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Result of the last apply/restart, and of the last update/rollback; they survive the screen. */
    val applyMessage = MutableStateFlow<String?>(null)
    val versionMessage = MutableStateFlow<String?>(null)

    /** Starts [block] unless a job is already running; returns whether it started. */
    fun launch(block: suspend () -> Unit): Boolean {
        if (!_busy.compareAndSet(false, true)) return false
        holders.retain(HOLDER)
        scope.launch {
            try {
                block()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    holders.release(HOLDER)
                    holders.stopIfUnretained() // blocks until the process is gone, hence IO
                }
                _busy.value = false
            }
        }
        return true
    }

    /**
     * The one rollback, for Settings' button and the crash notification's action (final review B2-M1): [step] (stop, switch,
     * start) runs to its end whatever happens to the screens; a failure is [MainOps.RollbackOutcome.Failed] ([onFailure] logs
     * it, without a URL). A dsh that is up again goes back under the service ([supervise], which also ends a give-up); a dsh
     * that is down stays as it is. [then] tells the outcome. False when another job runs.
     */
    fun rollback(
        step: suspend () -> MainOps.RollbackOutcome,
        isRunning: () -> Boolean,
        supervise: () -> Unit,
        onFailure: (Exception) -> Unit = {},
        then: suspend (MainOps.RollbackOutcome) -> Unit,
    ): Boolean = launch {
        versionMessage.value = null
        val outcome = withContext(NonCancellable) {
            try {
                step()
            } catch (e: Exception) {
                // Also a start that a stop cancelled (CancellationException): the job itself is never cancelled.
                onFailure(e)
                MainOps.RollbackOutcome.Failed("rollback failed")
            }
        }
        if (isRunning()) runCatching { supervise() }
        withContext(NonCancellable) { then(outcome) }
    }

    companion object {
        const val HOLDER = "settings-job"
    }
}
