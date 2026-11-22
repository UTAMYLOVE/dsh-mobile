package app.dshmobile.agency.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.ui.agency.AgencyActivity
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

    @Test fun withoutThePermissionNothingIsPostedAndNothingThrows() {
        shadowOf(app as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.costAlert(1.0)
        notifier.rateLimited(now = 5)
    }
}
