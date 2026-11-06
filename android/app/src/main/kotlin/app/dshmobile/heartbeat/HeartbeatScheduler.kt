package app.dshmobile.heartbeat

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic [HeartbeatWorker]. WorkManager re-creates its own periodic work after a reboot, using the
 * boot permission the app already has, so nothing here listens for boot.
 */
object HeartbeatScheduler {
    const val WORK_NAME = "dshm-heartbeat"
    const val DEFAULT_INTERVAL_MIN = 45
    const val MIN_INTERVAL_MIN = 30
    const val MAX_INTERVAL_MIN = 60

    /** [intervalMin] is clamped to 30..60 minutes. Calling again replaces the interval but keeps the existing work. */
    fun schedule(context: Context, intervalMin: Int = DEFAULT_INTERVAL_MIN) {
        val minutes = intervalMin.coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN).toLong()
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    const val HELPER_WAKE = "dshm-helper-wake"
    const val HOLD_MS_KEY = "holdMs"
    /** Start this long before a helper is due, and keep dsh up this long after starting (dsh's scheduler runs inside dsh). */
    const val WAKE_EARLY_MS = 90_000L
    const val HOLD_MS = 8 * 60_000L

    /**
     * Wakes dsh for the next always-on helper at [atMs] (or cancels the wake when null): a one-time run of the heartbeat that
     * also holds dsh for [HOLD_MS], so the helper's dsh schedule fires and its notice goes out. One work per due time (asking
     * again for the same time keeps it; a wake that is running when it asks for the next one is not cancelled by that).
     */
    fun wakeForHelper(context: Context, atMs: Long?, now: Long = System.currentTimeMillis()) {
        val wm = WorkManager.getInstance(context)
        if (atMs == null) return
        val delay = (atMs - WAKE_EARLY_MS - now).coerceAtLeast(0)
        val request = androidx.work.OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf(HOLD_MS_KEY to HOLD_MS))
            .build()
        wm.enqueueUniqueWork("$HELPER_WAKE-$atMs", androidx.work.ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * When the next heartbeat is due (epoch ms), as WorkManager reports it; null while nothing is scheduled. WorkManager may run
 * periodic work a little late, so this is when it becomes eligible, not a promise.
 */
fun HeartbeatScheduler.nextRunAt(context: Context): Flow<Long?> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(HeartbeatScheduler.WORK_NAME).map { infos ->
        infos.firstOrNull { !it.state.isFinished }?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE }
    }
