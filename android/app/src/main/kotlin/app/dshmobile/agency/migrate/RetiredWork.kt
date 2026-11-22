package app.dshmobile.agency.migrate

import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.dshmobile.log.ErrorLog

/**
 * What the old dsh build left in the system (arch §2 D0/D2): its WorkManager heartbeat and helper wakes, and its notification
 * channels. Each start cancels the works and deletes the channels; a stale run that starts before the cancel lands is given a
 * worker that ends at once (its class no longer exists, and WorkManager would otherwise fail to reflect it).
 */
object RetiredWork {
    /** The old heartbeat's unique work name and the tag every heartbeat and helper wake carried (its class name). */
    const val HEARTBEAT_NAME = "dshm-heartbeat"
    const val HEARTBEAT_TAG = "app.dshmobile.heartbeat.HeartbeatWorker"

    /** The old channels: messages, alerts and the service's. */
    val CHANNELS = listOf("dshm_messages", "dshm_alerts", "dshm_service")

    fun cancel(context: Context, log: ErrorLog) {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(HEARTBEAT_NAME)
            wm.cancelAllWorkByTag(HEARTBEAT_TAG)
        } catch (e: Exception) {
            log.append("cutover", "could not cancel the old works (${e.javaClass.simpleName})")
        }
    }

    fun deleteChannels(context: Context, log: ErrorLog) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            CHANNELS.forEach(nm::deleteNotificationChannel)
        }.onFailure { log.append("cutover", "could not delete the old channels (${it.javaClass.simpleName})") }
    }

    /** Ends any work of the old dsh build at once; every other class is left to WorkManager's default. */
    class Factory : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            if (workerClassName.startsWith(OLD_PREFIX)) Retired(appContext, workerParameters) else null
    }

    class Retired(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    private const val OLD_PREFIX = "app.dshmobile.heartbeat."
}
