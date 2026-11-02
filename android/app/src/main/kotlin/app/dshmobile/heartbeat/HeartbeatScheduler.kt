package app.dshmobile.heartbeat

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
}
