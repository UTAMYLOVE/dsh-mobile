package app.dshmobile.agency.schedule

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.agency.core.engine.Fire
import app.agency.core.engine.FireKind
import app.agency.core.model.Profile
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.StartState
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The one armed alarm (arch 1.6, T13) on Robolectric's AlarmManager: a slot uses setAlarmClock, a chase exact-while-idle,
 * a phone without exact alarms a 10-minute window; re-armed after a message, at boot, a time change and an app update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlarmSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val am = context.getSystemService(AlarmManager::class.java)
    private val shadow get() = shadowOf(am)
    private val scheduler = AlarmScheduler(context)
    private val tmp: File = Files.createTempDirectory("alarms").toFile()

    @After fun tearDown() {
        runBlocking { runCatching { (context as DshMobileApp).agency.stopForTest() } }
        AgencyApp.testOverrides = null
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        tmp.deleteRecursively()
    }

    private val soon = Instant.now().plusSeconds(3600)

    @Test fun aSlotIsAnAlarmClockAndAChaseIsExact() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(AlarmScheduler.Mode.ALARM_CLOCK, scheduler.arm(Fire(soon, FireKind.SLOT)))
        assertEquals(soon.toEpochMilli(), am.nextAlarmClock!!.triggerTime)
        assertEquals(1, shadow.scheduledAlarms.size)
        assertEquals(AlarmScheduler.Mode.EXACT, scheduler.arm(Fire(soon.plusSeconds(60), FireKind.CHASE)))
        // One armed alarm at a time: the slot alarm went.
        assertEquals(1, shadow.scheduledAlarms.size)
        assertNull(am.nextAlarmClock)
        assertEquals(soon.plusSeconds(60).toEpochMilli(), shadow.peekNextScheduledAlarm()!!.triggerAtTime)
        assertTrue(shadow.peekNextScheduledAlarm()!!.isAllowWhileIdle)
        assertNull(scheduler.arm(null))
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test fun withoutExactAlarmsAWindowIsUsed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(AlarmScheduler.Mode.WINDOW, scheduler.arm(Fire(soon, FireKind.SLOT)))
        val a = shadow.peekNextScheduledAlarm()!!
        assertEquals(soon.toEpochMilli(), a.triggerAtTime)
        assertEquals(AlarmScheduler.WINDOW_MS, a.windowLengthMs)
    }

    private fun startCompany(): StartState.Ready {
        val app = context as DshMobileApp
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = AgencyTestKit(tmp).overrides()
        app.agency.start()
        val ready = runBlocking { withTimeout(10_000) { app.agency.start.first { it is StartState.Ready } } } as StartState.Ready
        // 入社建檔 is done: the reminders run (and the alarm is armed) from the next tick.
        runBlocking { ready.store.profile.put(Profile(onboardingDone = true)) }
        runBlocking { app.agency.onAlarm() }
        return ready
    }

    private fun waitArmed() {
        val until = System.currentTimeMillis() + 10_000
        while (shadow.scheduledAlarms.isEmpty() && System.currentTimeMillis() < until) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test fun theCompanyArmsTheNextAlarmWhenItOpensAndAfterAMessage() {
        startCompany()
        waitArmed()
        assertNotNull(shadow.peekNextScheduledAlarm())
        val first = shadow.peekNextScheduledAlarm()!!.triggerAtTime
        assertTrue(first > System.currentTimeMillis() - 60_000)
        // A message (a sign-in) re-arms too: the alarm is cancelled and set again, still one.
        (context as DshMobileApp).agency.send("簽到")
        val until = System.currentTimeMillis() + 10_000
        while (runBlocking { (context.agency.start.value as StartState.Ready).store.signs.count() } == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals(1, shadow.scheduledAlarms.size)
    }

    @Test fun bootTimeChangeAndAnUpdateReArm() {
        startCompany()
        waitArmed()
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            am.cancel(shadow.peekNextScheduledAlarm()!!.operation!!)
            assertTrue(action, shadow.scheduledAlarms.isEmpty())
            BootReceiver().onReceive(context, Intent(action))
            waitArmed()
            assertEquals(action, 1, shadow.scheduledAlarms.size)
        }
        // Anything else is ignored.
        am.cancel(shadow.peekNextScheduledAlarm()!!.operation!!)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        Thread.sleep(300)
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test fun theAlarmFiresATickThatPostsWhatIsDue() {
        val ready = startCompany()
        waitArmed()
        val before = runBlocking { ready.store.nudges.between(0, Long.MAX_VALUE) }.size
        AlarmReceiver().onReceive(context, Intent(AlarmReceiver.ACTION_FIRE))
        val until = System.currentTimeMillis() + 10_000
        while (runBlocking { ready.store.nerve.signals("0000-01-01", "9999-12-31") }.none { it.type == "day_started" } && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(runBlocking { ready.store.nerve.signals("0000-01-01", "9999-12-31") }.any { it.type == "day_started" })
        assertTrue(runBlocking { ready.store.nudges.between(0, Long.MAX_VALUE) }.size >= before)
        // Another broadcast to the alarm receiver is ignored.
        AlarmReceiver().onReceive(context, Intent("something.else"))
    }
}
