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
 * The agency's notifications, first cut (T06): one channel and the daily spend alert, opening the company. The slot reminders,
 * the riding channel and deep links replace the old `Notifier` in T13. A refused post (notifications off) is not an error.
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
            val open = PendingIntent.getActivity(
                context, ID_COST, Intent(context, AgencyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
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

    companion object {
        const val CHANNEL_ALERTS = "agency_alerts"
        const val ID_COST = 7101

        fun costText(usd: Double): String = String.format(Locale.ROOT, "今天的花費超過 %.2f 美元了", usd)
    }
}
