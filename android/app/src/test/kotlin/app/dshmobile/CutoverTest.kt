package app.dshmobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.dshmobile.heartbeat.HeartbeatScheduler
import app.dshmobile.heartbeat.HeartbeatWorker
import app.dshmobile.heartbeat.RetiredHeartbeatWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.robolectric.Robolectric
import org.robolectric.Shadows
import app.dshmobile.ui.agency.AgencyActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Arch §2 D0: from the cutover on, a start schedules no dsh work and cancels what an older build left. */
@RunWith(RobolectricTestRunner::class)
class CutoverTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp

    @Before fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
    }

    private fun infos(): List<WorkInfo> = WorkManager.getInstance(app).getWorkInfosByTag(HeartbeatWorker::class.java.name).get()

    @Test fun aStartCancelsTheHeartbeatAndEveryHelperWake() {
        HeartbeatScheduler.schedule(app)
        HeartbeatScheduler.wakeForHelper(app, System.currentTimeMillis() + 3_600_000)
        assertTrue(infos().size == 2 && infos().none { it.state.isFinished })
        app.onCreate()
        assertTrue(infos().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun aStartSchedulesNoDshWork() {
        app.onCreate()
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(HeartbeatScheduler.WORK_NAME).get().none { !it.state.isFinished })
    }

    @Test fun aStaleHeartbeatThatStillRunsBuildsNothingOfDsh() {
        // The app's factory is asked for the heartbeat's class name, as WorkManager would for a stale request.
        val probe = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters): ListenableWorker? =
                app.workManagerConfiguration.workerFactory.createWorker(c, HeartbeatWorker::class.java.name, p)
        }
        val worker = TestListenableWorkerBuilder.from(app, RetiredHeartbeatWorker::class.java).setWorkerFactory(probe).build()
        assertTrue(worker is RetiredHeartbeatWorker)
        assertEquals(ListenableWorker.Result.success(), worker.startWork().get())
        listOf("heartbeatDeps", "controller").forEach { f ->
            val lazy = DshMobileApp::class.java.getDeclaredField("$f\$delegate").apply { isAccessible = true }.get(app) as Lazy<*>
            assertFalse("$f was built", lazy.isInitialized())
        }
    }

    /** Review I2: the old shell's task or an old notification lands in the company; nothing of dsh is built. */
    @Test fun theOldChannelsAreDeleted() {
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        listOf("dshm_messages", "dshm_alerts", "dshm_service").forEach {
            nm.createNotificationChannel(android.app.NotificationChannel(it, "舊", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        app.onCreate()
        listOf("dshm_messages", "dshm_alerts", "dshm_service").forEach { assertNull(it, nm.getNotificationChannel(it)) }
    }

    private fun assertNotBuilt(vararg fields: String) = fields.forEach { f ->
        val lazy = DshMobileApp::class.java.getDeclaredField("$f\$delegate").apply { isAccessible = true }.get(app) as Lazy<*>
        assertFalse("$f was built", lazy.isInitialized())
    }
}
