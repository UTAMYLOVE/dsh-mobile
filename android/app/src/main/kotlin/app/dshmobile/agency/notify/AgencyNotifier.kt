package app.dshmobile.agency.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.agency.core.engine.Post
import app.agency.core.engine.PostChannel
import app.dshmobile.R
import app.dshmobile.ui.agency.AgencyActivity
import java.util.Locale
import kotlin.math.abs

/**
 * The agency's notifications (arch 1.6): four channels. 行程提醒 (high: sound, vibration, pops up, stays until the gap is
 * filled), 騎車中 (default: sounds once per ride, no pop-up, later updates silent), 公司訊息, and 花費與錯誤 (the daily spend
 * alert and the per-minute budget notice, kept from the old notifier). Every one opens the company; a slot reminder carries
 * its slot (the meal camera comes with T15). A refused post (notifications off) is not an error.
 */
class AgencyNotifier(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** Creates (or keeps) the four channels; the names are what he sees in the system settings. */
    fun channels() {
        runCatching {
            val schedule = NotificationChannel(CHANNEL_SCHEDULE, "行程提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "吃飯、簽到、訓練的時間到了，還沒回報就每三十分鐘再提醒一次。"
                enableVibration(true)
            }
            val riding = NotificationChannel(CHANNEL_RIDING, "騎車中", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "跑單的時候只響一聲，不會一直跳出來。"
            }
            val messages = NotificationChannel(CHANNEL_MESSAGES, "公司訊息", NotificationManager.IMPORTANCE_DEFAULT)
            val alerts = NotificationChannel(CHANNEL_ALERTS, "花費與錯誤", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannels(listOf(schedule, riding, messages, alerts))
        }
    }

    /** One push of the scheduler: the gaps (one notification, ongoing) or a link's reminder. */
    fun post(p: Post) {
        runCatching {
            channels()
            val channel = if (p.channel == PostChannel.RIDING) CHANNEL_RIDING else CHANNEL_SCHEDULE
            val gap = p.key == GAP_KEY
            val n = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (p.channel == PostChannel.RIDING) RIDING_TITLE else SCHEDULE_TITLE)
                .setContentText(p.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(p.text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(if (p.channel == PostChannel.RIDING) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                .setOngoing(gap && p.channel == PostChannel.SCHEDULE)
                .setAutoCancel(!gap)
                .setOnlyAlertOnce(!p.alert)
                .setSilent(!p.alert)
                .setContentIntent(open(idOf(p.key), p.slot?.name))
                .build()
            manager.notify(idOf(p.key), n)
        }
    }

    /** The model's words for a push still on screen: the same notification, silently. Nothing when it is gone already. */
    fun update(p: Post) {
        if (!showing(idOf(p.key))) return
        post(p.copy(alert = false))
    }

    fun showing(id: Int): Boolean = runCatching { manager.activeNotifications.any { it.id == id } }.getOrDefault(false)

    /** Nothing is missing any more: the gap notification goes. */
    fun cancelGaps() {
        runCatching { manager.cancel(ID_GAPS) }
    }

    /** Today's spend went above his alert amount ([usd], in US dollars). */
    fun costAlert(usd: Double) {
        runCatching {
            channels()
            val n = Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("花費提醒")
                .setContentText(costText(usd))
                .setContentIntent(open(ID_COST, null))
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
            channels()
            val n = Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(RATE_TITLE)
                .setContentText(RATE_TEXT)
                .setContentIntent(open(ID_RATE, null))
                .setAutoCancel(true)
                .build()
            manager.notify(ID_RATE, n)
        }
    }

    /** 新版本通知: one notification, tapping it opens the release page (a page of the releases repository only). */
    fun newRelease(versionName: String, page: String) {
        if (!page.startsWith(app.agency.core.net.ReleaseCheck.PAGE_PREFIX) && page != app.agency.core.net.ReleaseCheck.RELEASES_PAGE) return
        runCatching {
            channels()
            val view = PendingIntent.getActivity(
                context, ID_RELEASE, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(RELEASE_TITLE)
                .setContentText(releaseText(versionName))
                .setContentIntent(view)
                .setAutoCancel(true)
                .build()
            manager.notify(ID_RELEASE, n)
        }
    }

    private fun open(requestCode: Int, slot: String?): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, AgencyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (slot != null) putExtra(EXTRA_SLOT, slot) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_SCHEDULE = "agency_schedule"
        const val CHANNEL_RIDING = "agency_riding"
        const val CHANNEL_MESSAGES = "agency_messages"
        const val CHANNEL_ALERTS = "agency_alerts"
        const val EXTRA_SLOT = "slot"
        const val GAP_KEY = app.agency.core.engine.Nudger.GAP_KEY
        const val ID_COST = 7101
        const val ID_RATE = 7102
        const val ID_RELEASE = 7103
        const val RELEASE_TITLE = "小葵姐・新版本"
        fun releaseText(versionName: String) = "有新版本 $versionName 可以更新了。點這裡看下載頁，設定裡也看得到。"
        const val ID_GAPS = 7201
        /** Every reminder is hers (owner 2026-11-26: the manager is 小葵, he calls her 小葵姐). */
        const val SCHEDULE_TITLE = "小葵姐・行程提醒"
        const val RIDING_TITLE = "小葵姐・騎車中"
        const val RATE_TITLE = "請求太頻繁"
        const val RATE_TEXT = "一分鐘內打給模型的次數太多，先停一下，以免白白耗掉額度。"
        private const val RATE_NOTICE_GAP_MS = 60_000L

        /** One notification id per key: the gaps are one; each link reminder its own (7300–7389). */
        fun idOf(key: String): Int = if (key == GAP_KEY) ID_GAPS else 7300 + abs(key.hashCode() % 90)

        fun costText(usd: Double): String = String.format(Locale.ROOT, "今天的花費超過 %.2f 美元了", usd)
    }
}
