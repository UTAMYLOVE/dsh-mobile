package app.dshmobile.ui

import android.os.Handler
import android.os.Looper
import app.dshmobile.service.DshHolderRuntime

/** How long dsh keeps running after the app left the foreground (Ruling: a quick app switch must not kill a working agent). */
const val BACKGROUND_GRACE_MS = 600_000L

/**
 * The UI's hold on dsh plus the background grace period. One instance lives as long as the process (owned by
 * `DshMobileApp`), so the countdown survives an Activity being recreated: [onUiStop] starts it, [onUiStart] cancels it.
 * When it ends and the UI still does not hold dsh, the service is stopped and dsh with it unless someone else
 * (e.g. the heartbeat) holds it. Main thread only.
 */
class UiHolder(
    private val runtime: DshHolderRuntime,
    private val holders: () -> Set<String>,
    private val stopService: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val graceMs: Long = BACKGROUND_GRACE_MS,
    /** Stopping dsh waits for the process to die; production runs it on its own thread. */
    private val runAsync: (() -> Unit) -> Unit = { work -> Thread(work, "dshm-ui-stop").start() },
) {
    private val check = Runnable { onGraceEnded() }

    var graceScheduled = false
        private set

    /** The hold comes first (before anything starts or calls `ensureRunning`), then a pending countdown is dropped. */
    fun onUiStart() {
        runtime.retain(HOLDER)
        cancelGrace()
    }

    fun onUiStop() {
        runtime.release(HOLDER)
        handler.removeCallbacks(check)
        handler.postDelayed(check, graceMs)
        graceScheduled = true
    }

    private fun cancelGrace() {
        handler.removeCallbacks(check)
        graceScheduled = false
    }

    private fun onGraceEnded() {
        graceScheduled = false
        if (HOLDER in holders()) return
        stopService() // its onDestroy lets go of the "service" hold
        runAsync { runtime.stopIfUnretained() }
    }

    companion object {
        const val HOLDER = "ui"
    }
}
