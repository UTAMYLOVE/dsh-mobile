package app.dshmobile.agency.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.app.Notification
import app.agency.core.engine.Nudger
import app.agency.core.engine.Post
import app.agency.core.engine.PostChannel
import app.agency.core.schedule.SlotKey
import app.dshmobile.ui.agency.AgencyActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The company's notices (the old dsh notifier's spend and rate-guard notices, kept): channel, text, and a tap opens the company. */
@RunWith(RobolectricTestRunner::class)
class AgencyNotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val notifier = AgencyNotifier(app)

    @Before fun grant() = shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun aNewReleaseIsOneNotificationOpeningItsPageOnly() {
        notifier.newRelease("0.9.1", "https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/v0.9.1%2B19")
        val n = shadowOf(nm).getNotification(AgencyNotifier.ID_RELEASE)
        assertEquals(AgencyNotifier.RELEASE_TITLE, shadowOf(n).contentTitle.toString())
        assertEquals(AgencyNotifier.releaseText("0.9.1"), shadowOf(n).contentText.toString())
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/v0.9.1%2B19", intent.dataString)
        // A page outside the releases repository is never offered.
        nm.cancelAll()
        notifier.newRelease("9.9.9", "https://evil.example/x")
        assertEquals(null, shadowOf(nm).getNotification(AgencyNotifier.ID_RELEASE))
    }

    @Test fun theSpendAlertSaysTheAmountAndOpensTheCompany() {
        notifier.costAlert(1.5)
        val n = shadowOf(nm).getNotification(AgencyNotifier.ID_COST)
        assertEquals(AgencyNotifier.CHANNEL_ALERTS, n.channelId)
        assertEquals("今天的花費超過 1.50 美元了", shadowOf(n).contentText.toString())
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(AgencyActivity::class.java.name, intent.component!!.className)
        assertTrue(n.contentIntent.isImmutable)
    }

    @Test fun theRateNoticeIsShownAtMostOnceAMinute() {
        notifier.rateLimited(now = 1_000_000)
        val first = shadowOf(nm).getNotification(AgencyNotifier.ID_RATE)
        assertEquals(AgencyNotifier.RATE_TITLE, shadowOf(first).contentTitle.toString())
        assertEquals(AgencyActivity::class.java.name, shadowOf(first.contentIntent).savedIntent.component!!.className)
        nm.cancelAll()
        notifier.rateLimited(now = 1_030_000)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        notifier.rateLimited(now = 1_061_000)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        // No Latin letter in what he reads.
        listOf(AgencyNotifier.RATE_TITLE, AgencyNotifier.RATE_TEXT, AgencyNotifier.costText(2.0)).forEach {
            assertTrue(it, it.none { c -> c in 'A'..'Z' || c in 'a'..'z' })
        }
    }

    @Test fun theFourChannelsAndTheirImportance() {
        notifier.channels()
        val byId = nm.notificationChannels.associateBy { it.id }
        assertEquals(setOf(AgencyNotifier.CHANNEL_SCHEDULE, AgencyNotifier.CHANNEL_RIDING, AgencyNotifier.CHANNEL_MESSAGES, AgencyNotifier.CHANNEL_ALERTS), byId.keys)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, byId.getValue(AgencyNotifier.CHANNEL_SCHEDULE).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, byId.getValue(AgencyNotifier.CHANNEL_RIDING).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, byId.getValue(AgencyNotifier.CHANNEL_ALERTS).importance)
        assertEquals("行程提醒", byId.getValue(AgencyNotifier.CHANNEL_SCHEDULE).name.toString())
        assertEquals("騎車中", byId.getValue(AgencyNotifier.CHANNEL_RIDING).name.toString())
        assertEquals("花費與錯誤", byId.getValue(AgencyNotifier.CHANNEL_ALERTS).name.toString())
    }

    @Test fun aGapPushIsProminentStaysAndLeadsToItsSlot() {
        notifier.post(Post(Nudger.GAP_KEY, "n1", "午餐時間到了，做完回報。", PostChannel.SCHEDULE, alert = true, gapKeys = listOf(SlotKey.LUNCH), slot = SlotKey.LUNCH))
        val n = shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS)
        assertEquals(AgencyNotifier.CHANNEL_SCHEDULE, n.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL == 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        assertEquals("LUNCH", shadowOf(n.contentIntent).savedIntent.getStringExtra(AgencyNotifier.EXTRA_SLOT))
        // The model's words replace it on the same id, silently; once the gap is filled it goes, and a late update stays away.
        notifier.update(Post(Nudger.GAP_KEY, "n1", "午餐，現在吃。", PostChannel.SCHEDULE, alert = true))
        val worded = shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS)
        assertEquals("午餐，現在吃。", shadowOf(worded).contentText.toString())
        assertTrue(worded.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        notifier.cancelGaps()
        assertNull(shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS))
        notifier.update(Post(Nudger.GAP_KEY, "n1", "晚到的字", PostChannel.SCHEDULE, alert = false))
        assertNull(shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS))
    }

    @Test fun whileRidingTheSecondPushIsSilent() {
        notifier.post(Post(Nudger.GAP_KEY, "n1", "21:00 那份蛋白質時間到了，做完回報。", PostChannel.RIDING, alert = true))
        val first = shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS)
        assertEquals(AgencyNotifier.CHANNEL_RIDING, first.channelId)
        assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT == 0)
        notifier.post(Post(Nudger.GAP_KEY, "n2", "還缺：21:00 那份蛋白質。", PostChannel.RIDING, alert = false))
        val second = shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS)
        assertTrue(second.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(AgencyNotifier.idOf(Nudger.GAP_KEY), AgencyNotifier.ID_GAPS)
        assertTrue(AgencyNotifier.idOf("preride") in 7300..7389)
    }

    @Test fun withoutThePermissionNothingIsPostedAndNothingThrows() {
        shadowOf(app as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.costAlert(1.0)
        notifier.rateLimited(now = 5)
    }
}
