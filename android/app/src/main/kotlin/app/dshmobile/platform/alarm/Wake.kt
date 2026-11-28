package app.dshmobile.platform.alarm

import android.content.Context
import app.dshmobile.DshMobileApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What happens when the app is woken without a screen (the alarm, a reboot, a clock change, an update): the company's own code,
 * registered once in `AppGraph.wake`. It decides what is due, posts the notifications and arms the next alarm. It must finish
 * within [Wake.BUDGET_MS] (a receiver has about ten seconds) and be safe to run twice in a row.
 */
fun interface WakeHandler {
    suspend fun onWake()
}

/** The single entry point the receivers use. */
object Wake {
    /** A receiver has about ten seconds; leave room to finish it. */
    const val BUDGET_MS = 9_000L

    private const val TAG = "wake"

    /** Runs the registered [WakeHandler] (nothing when none is registered yet). Never throws except for cancellation. */
    suspend fun run(context: Context) {
        val graph = (context.applicationContext as? DshMobileApp)?.graph ?: return
        val handler = graph.wake ?: run {
            graph.errorLog.append(TAG, "woken before a wake handler was registered")
            return
        }
        try {
            handler.onWake()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.errorLog.append(TAG, "wake failed (${e.javaClass.simpleName})")
        }
    }

    /** Runs [run] off the main thread within [BUDGET_MS], then calls [finish]; the receivers pass `pendingResult::finish`. */
    fun launch(context: Context, finish: () -> Unit = {}): Job {
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
        return scope.launch {
            try {
                withTimeoutOrNull(BUDGET_MS) { run(app) }
            } finally {
                finish()
            }
        }
    }
}
