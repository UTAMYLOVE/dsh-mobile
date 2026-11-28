package app.dshmobile.platform

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.AppGraph
import app.dshmobile.DshMobileApp
import app.dshmobile.data.TestDb
import app.dshmobile.platform.alarm.AlarmReceiver
import app.dshmobile.platform.alarm.AlarmScheduler
import app.dshmobile.platform.alarm.BootReceiver
import app.dshmobile.platform.alarm.WakeHandler
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotifierAlarmTest {
    private val app: DshMobileApp = ApplicationProvider.getApplicationContext()
    private val context: Context = app
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val am get() = context.getSystemService(AlarmManager::class.java)

    @Test fun twoChannelsOneLoudOneSilent() {
        Notifier(context).ensureChannels()
        val loud = nm.getNotificationChannel(Notifier.CHANNEL_LOUD)
        val quiet = nm.getNotificationChannel(Notifier.CHANNEL_QUIET)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, loud.importance)
        assertTrue(loud.shouldVibrate())
        assertNotNull(loud.sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, quiet.importance)
        assertFalse(quiet.shouldVibrate())
        assertNull(quiet.sound)
        assertEquals("提醒", loud.name.toString())
        assertEquals(2, nm.notificationChannels.count { it.id.startsWith("xiaoyu_") })
    }

    @Test fun postNeedsThePermissionThenShowsAndCancels() {
        val n = Notifier(context)
        assertTrue(n.needsPermission())
        assertFalse(n.post("memo", "標題", "內容", loud = true))
        shadowOf(app).grantPermissions(Notifier.PERMISSION)
        assertFalse(n.needsPermission())
        assertTrue(n.post("memo", "標題", "內容", loud = true))
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(Notifier.CHANNEL_LOUD, posted.channelId)
        assertNotNull(posted.contentIntent)
        assertNotNull(posted.publicVersion)
        assertTrue(n.post("memo", "標題", "又一次", loud = false))
        assertEquals(1, shadowOf(nm).allNotifications.size) // same key replaces
        assertEquals(Notifier.CHANNEL_QUIET, shadowOf(nm).allNotifications.single().channelId)
        n.cancel("memo")
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test fun armSetsOneExactAlarmAndReplacesIt() {
        val s = AlarmScheduler(context)
        val t1 = Instant.parse("2031-01-01T00:00:00Z")
        val t2 = Instant.parse("2031-01-02T00:00:00Z")
        assertEquals(AlarmScheduler.Mode.EXACT, s.arm(t1))
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        assertEquals(t1.toEpochMilli(), shadowOf(am).nextScheduledAlarm!!.triggerAtMs)
        s.arm(t2)
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        assertEquals(t2.toEpochMilli(), shadowOf(am).nextScheduledAlarm!!.triggerAtMs)
        assertEquals(t2, s.armedAt())
        assertNull(s.arm(null))
        assertEquals(0, shadowOf(am).scheduledAlarms.size)
        assertNull(s.armedAt())
    }

    @Test fun withoutExactPermissionAWindowIsUsed() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(AlarmScheduler.Mode.WINDOW, AlarmScheduler(context).arm(Instant.parse("2031-01-01T00:00:00Z")))
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }

    private fun forget() {
        val pi = PendingIntent.getBroadcast(
            context, AlarmScheduler.REQUEST_FIRE, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        am.cancel(pi!!)
    }

    @Test fun aRebootArmsTheRememberedAlarmAgainAndWakesTheHandler() {
        val latch = CountDownLatch(1)
        app.graph = AppGraph(app, openDb = { TestDb.open(context) }).also { it.wake = WakeHandler { latch.countDown() } }
        val at = Instant.now().plusSeconds(3600)
        app.graph.alarms.arm(at)
        forget() // the system loses its alarms at a reboot
        assertEquals(0, shadowOf(am).scheduledAlarms.size)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        assertEquals(at.toEpochMilli(), shadowOf(am).nextScheduledAlarm!!.triggerAtMs)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test fun anUnrelatedBroadcastDoesNothing() {
        val latch = CountDownLatch(1)
        app.graph = AppGraph(app, openDb = { TestDb.open(context) }).also { it.wake = WakeHandler { latch.countDown() } }
        BootReceiver().onReceive(context, Intent("some.other.ACTION"))
        assertFalse(latch.await(300, TimeUnit.MILLISECONDS))
    }

    @Test fun theAlarmReceiverForgetsTheInstantAndWakesTheHandler() {
        val latch = CountDownLatch(1)
        app.graph = AppGraph(app, openDb = { TestDb.open(context) }).also { it.wake = WakeHandler { latch.countDown() } }
        app.graph.alarms.arm(Instant.now().plusSeconds(60))
        AlarmReceiver().onReceive(context, Intent(AlarmReceiver.ACTION_FIRE))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNull(app.graph.alarms.armedAt())
    }
}
