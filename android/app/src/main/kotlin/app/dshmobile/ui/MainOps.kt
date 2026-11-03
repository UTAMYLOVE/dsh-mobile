package app.dshmobile.ui

import app.dshmobile.log.ErrorLog
import app.dshmobile.service.DshRuntime
import app.dshmobile.service.RunningDsh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The slow or stateful actions of the main screen, kept out of MainActivity so they can be tested without a view. */
class MainOps(
    private val runtime: DshRuntime,
    /** `DshVersions::previous`: the version a rollback goes back to, null when there is none. */
    private val previousVersion: () -> String?,
    /** `DshVersions::rollback`: false when it could not switch. */
    private val rollbackVersion: () -> Boolean,
    private val errorLog: ErrorLog,
) {
    sealed interface RollbackOutcome {
        data class RolledBack(val version: String, val running: RunningDsh) : RollbackOutcome
        data object NoPrevious : RollbackOutcome
        data class Failed(val reason: String) : RollbackOutcome
    }

    suspend fun ensureRunning(): RunningDsh = runtime.ensureRunning()

    /**
     * Stops dsh, goes back one version and starts dsh again. When there is nothing to go back to, nothing is touched. When
     * the switch itself fails, the version that was current is started again. Every failure is logged (no URLs).
     */
    suspend fun rollback(): RollbackOutcome {
        val target = withContext(Dispatchers.IO) { previousVersion() } ?: return RollbackOutcome.NoPrevious
        val switched = withContext(Dispatchers.IO) {
            runtime.stop()
            try {
                rollbackVersion()
            } catch (e: Exception) {
                errorLog.append(TAG, "switching to $target failed: ${describe(e)}")
                false
            }
        }
        val running = try {
            runtime.ensureRunning()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorLog.append(TAG, "dsh did not start after the rollback: ${describe(e)}")
            return RollbackOutcome.Failed("dsh did not start")
        }
        if (!switched) return RollbackOutcome.Failed("could not switch to $target")
        return RollbackOutcome.RolledBack(target, running)
    }

    private fun describe(e: Exception) = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private companion object {
        const val TAG = "rollback"
    }
}
