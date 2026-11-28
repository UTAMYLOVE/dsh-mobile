package app.dshmobile.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.agency.core.net.ReleaseCheck
import app.dshmobile.R
import app.dshmobile.ui.MainActivity
import kotlin.math.abs

/**
 * His notifications: two channels. [CHANNEL_LOUD] rings and vibrates (a reminder that is due); [CHANNEL_QUIET] is silent (a chase
 * while he rides, an update note). Every notification taps through to [MainActivity] (with the [EXTRA_KEY] of the notice), and on
 * the lock screen shows only that a reminder exists, never its text. A refused post (notifications off, permission not given)
 * is not an error: [post] returns false.
 */
class Notifier(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** Creates (or keeps) the two channels; their names are what he sees in the system settings. Safe to call often. */
    fun ensureChannels() {
        runCatching {
            val loud = NotificationChannel(CHANNEL_LOUD, context.getString(R.string.channel_loud_name), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.channel_loud_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                }
            val quiet = NotificationChannel(CHANNEL_QUIET, context.getString(R.string.channel_quiet_name), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.channel_quiet_desc)
                    enableVibration(false)
                    setSound(null, null)
                }
            manager.createNotificationChannels(listOf(loud, quiet))
        }
    }

    /** True when the POST_NOTIFICATIONS permission is still to be asked (Android 13 and later). */
    fun needsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Whether a post would be shown now: permission granted and notifications not switched off in the system settings. */
    fun enabled(): Boolean = !needsPermission() && runCatching { manager.areNotificationsEnabled() }.getOrDefault(false)

    /**
     * Shows (or replaces, by [key]) one notification; [loud] picks the channel. [openUrl] makes a tap open that page instead of
     * the app (only a page of the releases repository is ever accepted). Returns whether it was handed to the system.
     */
    fun post(key: String, title: String, text: String, loud: Boolean, openUrl: String? = null): Boolean {
        if (!enabled()) return false
        return runCatching {
            ensureChannels()
            val channel = if (loud) CHANNEL_LOUD else CHANNEL_QUIET
            val public = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notify_locked_text))
                .build()
            val n = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!loud)
                .setSilent(!loud)
                .setContentIntent(tapIntent(key, openUrl))
                .build()
            manager.notify(idOf(key), n)
            true
        }.getOrDefault(false)
    }

    fun cancel(key: String) {
        runCatching { manager.cancel(idOf(key)) }
    }

    /** Whether the notification of [key] is on screen now. */
    fun showing(key: String): Boolean = runCatching { manager.activeNotifications.any { it.id == idOf(key) } }.getOrDefault(false)

    private fun tapIntent(key: String, openUrl: String?): PendingIntent {
        val intent = if (openUrl != null && releasePage(openUrl)) {
            Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_KEY, key)
        }
        return PendingIntent.getActivity(context, idOf(key), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_LOUD = "xiaoyu_loud"
        const val CHANNEL_QUIET = "xiaoyu_quiet"

        /** The intent extra [MainActivity] reads to know which notice was tapped. */
        const val EXTRA_KEY = "notice_key"

        /** The permission the UI asks with `RequestPermission` (Android 13 and later). */
        const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

        /** One stable notification id per key. */
        fun idOf(key: String): Int = 7000 + abs(key.hashCode() % 9000)

        fun releasePage(url: String): Boolean = url == ReleaseCheck.RELEASES_PAGE || url.startsWith(ReleaseCheck.PAGE_PREFIX)
    }
}
