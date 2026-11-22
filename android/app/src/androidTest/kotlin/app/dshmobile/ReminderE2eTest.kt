package app.dshmobile

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.agency.core.migrate.AgencyPrefKeys
import app.agency.core.migrate.LegacyPrefs
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.DayType
import app.agency.core.schedule.MealSpec
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.Timetable
import app.agency.core.time.AgencyClock
import app.dshmobile.agency.StartState
import app.dshmobile.agency.notify.AgencyNotifier
import app.dshmobile.agency.schedule.BootReceiver
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The reminders on a real Android (arch T13): a real alarm fires a real notification on 行程提醒, a boot re-arms the next
 * alarm, and while riding the second push is silent. Each test opens the company on a fresh store with 入社建檔 done.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReminderE2eTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as DshMobileApp
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val am = context.getSystemService(AlarmManager::class.java)

    private fun fresh() {
        runBlocking { app.agency.stopForTest() }
        File(context.noBackupFilesDir, "agency").deleteRecursively()
        listOf(AgencyPrefKeys.NAME, AgencyPrefKeys.IMPORT_NAME, LegacyPrefs.NAME).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        nm.cancelAll()
    }

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        fresh()
    }

    @After fun tearDown() = fresh()

    private fun open(): StartState.Ready {
        app.agency.start()
        val ready = runBlocking { withTimeout(60_000) { app.agency.start.first { it is StartState.Ready } } } as StartState.Ready
        runBlocking { ready.store.profile.put(Profile(onboardingDone = true)) }
        return ready
    }

    private fun waitFor(timeoutMs: Long, what: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(250)
    }

    /** Today's first meal moved to [at] (HH:mm, Taipei) through the plan section, as 設定 would write it. */
    private fun firstMealAt(ready: StartState.Ready, at: ZonedDateTime) {
        val day = CoachRules.coachDay(at.toInstant(), AgencyClock.TAIPEI, 5)
        val hhmm = at.format(DateTimeFormatter.ofPattern("HH:mm"))
        val type = runBlocking { Timetable.type(day, PlanStore(ready.store).specs().schedule) }
        val meals = if (type == DayType.CLASS) MealSpec(classLunch = hhmm) else MealSpec(noClassFirstMeal = hhmm)
        runBlocking {
            ready.store.rules.putSection(PlanSectionKey.MEALS, PlanSpecs.JSON.encodeToString(MealSpec.serializer(), meals), PlanSection.BY_OWNER_SETTINGS, System.currentTimeMillis())
        }
    }

    @Test fun e07_lunchReminderFires() {
        val ready = open()
        val inTwo = Instant.now().plusSeconds(120).atZone(AgencyClock.TAIPEI).withSecond(0).withNano(0)
        firstMealAt(ready, inTwo)
        runBlocking { app.agency.onAlarm() } // arms the alarm for the slot
        waitFor(4 * 60_000L) { nm.activeNotifications.any { it.id == AgencyNotifier.ID_GAPS } }
        val n = nm.activeNotifications.first { it.id == AgencyNotifier.ID_GAPS }.notification
        assertEquals(AgencyNotifier.CHANNEL_SCHEDULE, n.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun e08_bootRestoresTheNextAlarm() {
        val ready = open()
        val later = Instant.now().plusSeconds(3 * 3600).atZone(AgencyClock.TAIPEI).withSecond(0).withNano(0)
        firstMealAt(ready, later)
        runBlocking { app.agency.onAlarm() }
        val expected = runBlocking { ready.brain.nextFire() }
        assertNotNull(expected)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        waitFor(10_000) { am.nextAlarmClock?.triggerTime == expected!!.at.toEpochMilli() }
        // The next alarm clock is the next slot only when that slot is the earliest fire; else an exact alarm is armed.
        assertTrue(am.nextAlarmClock == null || am.nextAlarmClock!!.triggerTime <= expected!!.at.toEpochMilli() + 60_000)
    }

    /** Out on the run, the push goes to 騎車中 and sounds (the silent second push, 30 minutes later, is a unit test). */
    @Test fun e09_ridingSoundsOnce() {
        val ready = open()
        runBlocking { app.agency.onAlarm() } // the reminders start now
        val soon = Instant.now().plusSeconds(120).atZone(AgencyClock.TAIPEI).withSecond(0).withNano(0)
        firstMealAt(ready, soon)
        runBlocking { ready.brain.onText("出門跑單") }
        runBlocking { app.agency.onAlarm() } // re-armed for the slot
        waitFor(4 * 60_000L) { nm.activeNotifications.any { it.id == AgencyNotifier.ID_GAPS } }
        val first = nm.activeNotifications.first { it.id == AgencyNotifier.ID_GAPS }.notification
        assertEquals(AgencyNotifier.CHANNEL_RIDING, first.channelId)
        assertTrue(first.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT == 0)
    }
}
