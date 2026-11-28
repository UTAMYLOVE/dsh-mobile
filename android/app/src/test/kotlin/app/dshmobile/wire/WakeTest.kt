package app.dshmobile.wire

import app.agency.core.base.TestClock
import app.agency.core.remind.ReminderLog
import app.agency.core.store.Doc
import app.agency.core.store.readDoc
import app.dshmobile.platform.Notifier
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What an alarm does: rollover, notices, the kept log, the next alarm. Real wiring on plain SQLite, invented rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WakeTest {
    private val rig = WireRig()

    @After fun tearDown() = rig.close()

    private fun wake() = runBlocking { rig.wire.wake.onWake() }

    @Test fun aDueItemPostsANoticeAndArmsTheNextAlarm() {
        rig.onboarded()
        rig.clock.plus(Duration.ofMinutes(5)) // 07:05, the sign-in was due at 07:00
        wake()
        val posted = rig.notifications
        assertTrue("a notice for the sign-in", posted.isNotEmpty())
        assertTrue(posted.any { it.channelId == Notifier.CHANNEL_LOUD })
        val armed = rig.graph.alarms.armedAt()
        assertNotNull(armed)
        assertTrue(armed!!.isAfter(rig.clock.now()))
        assertNotNull(shadowOf(rig.alarmManager).nextScheduledAlarm)
        // The log was kept, so asking again right away says nothing new.
        val log = rig.graph.store.readDoc<ReminderLog>(CompanyWake.LOG_DOC)!!
        assertTrue(log.sent.containsKey("check_in"))
        val before = rig.notifications.size
        wake()
        assertEquals(before, rig.notifications.size)
    }

    @Test fun nothingDueStaysSilentButStillArmsTheAlarm() {
        rig.onboarded()
        rig.clock.set(TestClock.MONDAY, "06:30")
        wake()
        assertTrue(rig.notifications.isEmpty())
        val armed = rig.graph.alarms.armedAt()
        assertNotNull(armed)
        assertTrue(armed!!.isAfter(rig.clock.now()))
        assertNotNull(shadowOf(rig.alarmManager).nextScheduledAlarm)
    }

    @Test fun beforeOnboardingItDoesNothingAtAll() {
        wake()
        assertTrue(rig.notifications.isEmpty())
        assertNull(rig.graph.alarms.armedAt())
        assertNull(rig.graph.store.doc(Doc.STAGE))
    }

    @Test fun aNoteTheRolloverWroteRaisesOneQuietNotificationWhileNoScreenIsInFront() {
        rig.onboarded()
        rig.clock.set(TestClock.MONDAY.plusDays(7), "06:30") // a whole stage round has ended
        wake()
        val quiet = rig.notifications.filter { it.channelId == Notifier.CHANNEL_QUIET }
        assertEquals(1, quiet.size)
    }

    @Test fun whateverChangesThePlanMakesTheWakeHandlerArmTheAlarm() {
        rig.onboarded()
        assertNull(rig.graph.alarms.armedAt())
        rig.wire.company.memoAdd("交作業", null) // the hook fires after the change
        val end = System.currentTimeMillis() + 5_000
        while (rig.graph.alarms.armedAt() == null && System.currentTimeMillis() < end) Thread.sleep(25)
        assertNotNull(rig.graph.alarms.armedAt())
    }
}
