package app.dshmobile.heartbeat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HeartbeatSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun infos(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(HeartbeatScheduler.WORK_NAME).get()

    private fun intervalMinutes(info: WorkInfo) = TimeUnit.MILLISECONDS.toMinutes(info.periodicityInfo!!.repeatIntervalMillis)

    @Test fun uniqueNameIsTheDocumentedOne() {
        assertEquals("dshm-heartbeat", HeartbeatScheduler.WORK_NAME)
    }

    @Test fun defaultIntervalIs45Minutes() {
        HeartbeatScheduler.schedule(context)
        assertEquals(45, intervalMinutes(infos().single()))
    }

    @Test fun intervalBelowTheMinimumIsRaisedTo30() {
        HeartbeatScheduler.schedule(context, 5)
        assertEquals(30, intervalMinutes(infos().single()))
    }

    @Test fun intervalAboveTheMaximumIsLoweredTo60() {
        HeartbeatScheduler.schedule(context, 90)
        assertEquals(60, intervalMinutes(infos().single()))
    }

    @Test fun intervalsInsideTheRangeAreKept() {
        HeartbeatScheduler.schedule(context, 30)
        assertEquals(30, intervalMinutes(infos().single()))
        HeartbeatScheduler.schedule(context, 60)
        assertEquals(60, intervalMinutes(infos().single()))
    }

    @Test fun requiresANetworkConnection() {
        HeartbeatScheduler.schedule(context, 45)
        assertEquals(NetworkType.CONNECTED, infos().single().constraints.requiredNetworkType)
    }

    @Test fun schedulingAgainUpdatesTheSameUniqueWorkInsteadOfAddingOne() {
        HeartbeatScheduler.schedule(context, 45)
        val id = infos().single().id
        HeartbeatScheduler.schedule(context, 60)
        val after = infos().single()
        assertEquals(id, after.id) // UPDATE keeps the work and its run history
        assertEquals(60, intervalMinutes(after))
        HeartbeatScheduler.schedule(context, 60)
        assertEquals(1, infos().size)
    }

    @Test fun cancelRemovesTheWork() {
        HeartbeatScheduler.schedule(context)
        HeartbeatScheduler.cancel(context)
        assertTrue(infos().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun nextRunAtReportsTheScheduledTime() {
        runBlocking { assertNull(HeartbeatScheduler.nextRunAt(context).first()) }
        HeartbeatScheduler.schedule(context, 45)
        val before = System.currentTimeMillis()
        val next = runBlocking { HeartbeatScheduler.nextRunAt(context).first() }
        assertEquals(infos().single().nextScheduleTimeMillis, next)
        assertTrue("the next run is at most one interval away", next!! in (before - 60_000)..(before + 45 * 60_000 + 60_000))
    }
}
