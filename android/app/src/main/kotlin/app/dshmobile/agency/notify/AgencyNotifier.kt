package app.dshmobile.agency.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Notification
import app.dshmobile.R
import app.dshmobile.ui.agency.AgencyActivity
import java.util.Locale

/**
 * The agency's notifications: one channel, the daily spend alert and the per-minute budget's notice (both kept from the old
 * dsh notifier, which went in T09), each opening the company. The slot reminders, the riding channel and deep links come with
 * T13. A refused post (notifications off) is not an error.
 */
class AgencyNotifier(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun channel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "公司提醒", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Today's spend went above his alert amount ([usd], in US dollars). */
    fun costAlert(usd: Double) {
        runCatching {
            channel()
            val open = openCompany(ID_COST)
            val n = Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("花費提醒")
                .setContentText(costText(usd))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(ID_COST, n)
        }
    }

    @Volatile private var lastRateNotice = 0L

    /**
     * The per-minute call budget refused a call: a runaway loop is stopped before it burns his key (ported from the old
     * notifier's rate-guard notice). At most once a minute; the same notice id, so a new one replaces the old.
     */
    fun rateLimited(now: Long = System.currentTimeMillis()) {
        if (now - lastRateNotice < RATE_NOTICE_GAP_MS) return
        lastRateNotice = now
        runCatching {
            channel()
            val n = Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(RATE_TITLE)
                .setContentText(RATE_TEXT)
                .setContentIntent(openCompany(ID_RATE))
                .setAutoCancel(true)
                .build()
            manager.notify(ID_RATE, n)
        }
    }

    private fun openCompany(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode, Intent(context, AgencyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ALERTS = "agency_alerts"
        const val ID_COST = 7101
        const val ID_RATE = 7102
        const val RATE_TITLE = "請求太頻繁"
        const val RATE_TEXT = "一分鐘內打給模型的次數太多，先停一下，以免白白耗掉額度。"
        private const val RATE_NOTICE_GAP_MS = 60_000L

        fun costText(usd: Double): String = String.format(Locale.ROOT, "今天的花費超過 %.2f 美元了", usd)
    }
}
