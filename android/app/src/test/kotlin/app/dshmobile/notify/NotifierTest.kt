package app.dshmobile.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.gateway.NotifyRequest
import app.dshmobile.gateway.Provider
import app.dshmobile.update.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var notifier: Notifier

    @Before fun setUp() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier = Notifier(context)
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun tapIntent(n: Notification): Intent = shadowOf(n.contentIntent).savedIntent

    private fun Notification.text(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test fun createsThreeChannelsWithTheRightImportance() {
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("dshm_service").importance)
        assertNotNull(manager.getNotificationChannel("dshm_messages"))
        assertNotNull(manager.getNotificationChannel("dshm_alerts"))
        assertTrue(manager.getNotificationChannel("dshm_alerts").importance > NotificationManager.IMPORTANCE_LOW)
    }

    @Test fun showOpensMainActivityWithSessionIdOnTheMessagesChannel() {
        notifier.show(NotifyRequest("提醒", "該開會了", "sess-1"))
        val n = posted().single()
        assertEquals("dshm_messages", n.channelId)
        assertEquals("提醒", n.title())
        assertEquals("該開會了", n.text())
        val intent = tapIntent(n)
        assertEquals("app.dshmobile.ui.MainActivity", intent.component!!.className)
        assertEquals("app.dshmobile", intent.component!!.packageName)
        assertEquals("sess-1", intent.getStringExtra("sessionId"))
        assertTrue(shadowOf(n.contentIntent).isActivity)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun showWithoutSessionIdHasNoExtra() {
        notifier.show(NotifyRequest("t", "b", null))
        assertNull(tapIntent(posted().single()).getStringExtra("sessionId"))
    }

    @Test fun twoSessionsGetSeparateNotificationsAndIntents() {
        notifier.show(NotifyRequest("a", "x", "s1"))
        notifier.show(NotifyRequest("b", "y", "s2"))
        val all = posted()
        assertEquals(2, all.size)
        assertEquals(setOf("s1", "s2"), all.map { tapIntent(it).getStringExtra("sessionId") }.toSet())
    }

    @Test fun pendingIntentsAreImmutable() {
        notifier.show(NotifyRequest("t", "b", "s"))
        notifier.showCrashGiveUp()
        val intents = posted().flatMap { n -> listOfNotNull(n.contentIntent) + n.actions.orEmpty().map { it.actionIntent } }
        assertTrue(intents.size >= 4)
        for (pi in intents) {
            val flags = shadowOf(pi).flags
            assertTrue("FLAG_IMMUTABLE missing", flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
    }

    @Test fun costAlertTextAndChannel() {
        notifier.showCostAlert(12.5)
        val n = posted().single()
        assertEquals("dshm_alerts", n.channelId)
        assertTrue(n.text().contains("今日花費已超過 US\$12.50"))
    }

    @Test fun rateGuardNamesTheProvider() {
        notifier.showRateGuard(Provider.ANTHROPIC)
        val n = posted().single()
        assertEquals("dshm_alerts", n.channelId)
        assertTrue((n.title() + n.text()).contains("Anthropic"))
    }

    @Test fun crashGiveUpHasRollbackAndExportActions() {
        notifier.showCrashGiveUp()
        val n = posted().single()
        assertEquals("dshm_alerts", n.channelId)
        val actions = n.actions
        assertEquals(listOf("退版", "查看錯誤記錄"), actions.map { it.title.toString() })
        val extras = actions.map { shadowOf(it.actionIntent).savedIntent }
        assertEquals(listOf("rollback", "export_error_log"), extras.map { it.getStringExtra("action") })
        assertTrue(extras.all { it.component!!.className == "app.dshmobile.ui.MainActivity" })
    }

    @Test fun serviceNotificationIsOngoingOnTheServiceChannel() {
        val n = notifier.serviceNotification()
        assertEquals("dshm_service", n.channelId)
        assertEquals("DSH Mobile 執行中", n.title())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun missingPermissionIsSilent() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.show(NotifyRequest("t", "b", "s"))
        notifier.showCostAlert(1.0)
        notifier.showRateGuard(Provider.DEEPSEEK)
        notifier.showCrashGiveUp()
        assertTrue(posted().isEmpty())
    }

    @Test fun updateAvailableOpensMainActivityWithTheInstallUpdateAction() {
        notifier.showUpdateAvailable(ReleaseInfo(7, "0.7.0", "https://github.com/o/r/a.apk", "a".repeat(64)))
        val n = posted().single()
        assertEquals("dshm_alerts", n.channelId)
        assertTrue(n.title(), n.title().isNotBlank())
        assertTrue(n.text().contains("0.7.0"))
        val intent = tapIntent(n)
        assertEquals("app.dshmobile.ui.MainActivity", intent.component!!.className)
        assertEquals("install_update", intent.getStringExtra("action"))
        // Only the action travels in the intent: MainActivity is exported, so no URL or hash may ride along.
        assertEquals(setOf("action"), intent.extras!!.keySet())
        assertTrue(shadowOf(n.contentIntent).isActivity)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(shadowOf(n.contentIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test fun aSecondUpdateNoticeReplacesTheFirst() {
        notifier.showUpdateAvailable(ReleaseInfo(7, "0.7.0", "https://github.com/o/r/a.apk", "a".repeat(64)))
        notifier.showUpdateAvailable(ReleaseInfo(8, "0.8.0", "https://github.com/o/r/a.apk", "b".repeat(64)))
        assertEquals(1, posted().size)
        assertTrue(posted().single().text().contains("0.8.0"))
    }

    @Test fun updateReadyNoticeRetriesThroughTheSameAction() {
        notifier.showUpdateReady(ReleaseInfo(7, "0.7.0", "https://github.com/o/r/a.apk", "a".repeat(64)))
        val n = posted().single()
        assertEquals("install_update", tapIntent(n).getStringExtra("action"))
        assertEquals(setOf("action"), tapIntent(n).extras!!.keySet())
        assertTrue(n.text().contains("已下載") && n.text().contains("0.7.0"))
    }
}
