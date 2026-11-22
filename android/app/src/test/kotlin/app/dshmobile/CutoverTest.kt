package app.dshmobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.dshmobile.agency.migrate.RetiredWork
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Arch §2 D0/D2: dsh is gone, but an older build left works and channels behind. A start cancels the heartbeat and every
 * helper wake (as they were tagged), deletes the old channels, and a stale heartbeat that still runs ends at once.
 */
@RunWith(RobolectricTestRunner::class)
class CutoverTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp

    @Before fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
    }

    private fun infos(): List<WorkInfo> = WorkManager.getInstance(app).getWorkInfosByTag(RetiredWork.HEARTBEAT_TAG).get()

    @Test fun aStartCancelsTheHeartbeatAndEveryHelperWake() {
        // As the old build enqueued them: the periodic heartbeat under its unique name and a helper wake, both tagged.
        val wm = WorkManager.getInstance(app)
        wm.enqueueUniquePeriodicWork(
            RetiredWork.HEARTBEAT_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RetiredWork.Retired>(30, TimeUnit.MINUTES).addTag(RetiredWork.HEARTBEAT_TAG).build(),
        )
        wm.enqueue(OneTimeWorkRequestBuilder<RetiredWork.Retired>().setInitialDelay(1, TimeUnit.HOURS).addTag(RetiredWork.HEARTBEAT_TAG).build())
        assertTrue(infos().size == 2 && infos().none { it.state.isFinished })
        app.onCreate()
        assertTrue(infos().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(wm.getWorkInfosForUniqueWork(RetiredWork.HEARTBEAT_NAME).get().all { it.state.isFinished })
    }

    @Test fun aStaleHeartbeatThatStillRunsEndsAtOnce() {
        // The app's factory is asked for the old heartbeat's class name, as WorkManager would for a stale request.
        val probe = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters): ListenableWorker? =
                app.workManagerConfiguration.workerFactory.createWorker(c, RetiredWork.HEARTBEAT_TAG, p)
        }
        val worker = TestListenableWorkerBuilder.from(app, RetiredWork.Retired::class.java).setWorkerFactory(probe).build()
        assertTrue(worker is RetiredWork.Retired)
        assertEquals(ListenableWorker.Result.success(), worker.startWork().get())
        // Any other class is left to WorkManager's own factory.
        val params = TestListenableWorkerBuilder.from(app, RetiredWork.Retired::class.java).build()
        assertNull(app.workManagerConfiguration.workerFactory.createWorker(app, "app.dshmobile.agency.SomeWorker", workerParams(params)))
    }

    private fun workerParams(w: ListenableWorker): WorkerParameters =
        ListenableWorker::class.java.getDeclaredField("mWorkerParams").apply { isAccessible = true }.get(w) as WorkerParameters

    @Test fun theOldChannelsAreDeleted() {
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        RetiredWork.CHANNELS.forEach {
            nm.createNotificationChannel(android.app.NotificationChannel(it, "舊", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        app.onCreate()
        RetiredWork.CHANNELS.forEach { assertNull(it, nm.getNotificationChannel(it)) }
        assertEquals(listOf("dshm_messages", "dshm_alerts", "dshm_service"), RetiredWork.CHANNELS)
    }
}
