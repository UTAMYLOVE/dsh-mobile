package app.dshmobile.platform.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.time.Instant

/**
 * One exact alarm at a time: code decides when the next thing could be due (`Decision.nextWakeAt`) and calls [arm]; the one
 * PendingIntent (fixed request code, immutable) is replaced each time, so there is never more than one. The instant is also kept
 * in [prefs] (not secret), because the system forgets every alarm at a reboot: [restore] arms it again from [BootReceiver].
 * When exact alarms are not allowed (the permission was taken away) a ten-minute window is used instead.
 */
class AlarmScheduler(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
) {
    enum class Mode { EXACT, WINDOW }

    private val am: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun exactAllowed(): Boolean = runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

    /** The instant last armed and not yet fired or cancelled, or null. */
    fun armedAt(): Instant? = prefs.getLong(KEY_AT, NONE).takeIf { it != NONE }?.let(Instant::ofEpochMilli)

    /** Arms [at] (cancelling the one before), or only cancels when it is null. Returns how it was armed. */
    fun arm(at: Instant?): Mode? {
        val pi = fireIntent()
        am.cancel(pi)
        if (at == null) {
            prefs.edit().remove(KEY_AT).apply()
            return null
        }
        prefs.edit().putLong(KEY_AT, at.toEpochMilli()).apply()
        val ms = at.toEpochMilli()
        if (!exactAllowed()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, ms, WINDOW_MS, pi)
            return Mode.WINDOW
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        return Mode.EXACT
    }

    /** The alarm went off: forget it (the wake handler arms the next one). */
    fun fired() {
        prefs.edit().remove(KEY_AT).apply()
    }

    /** After a reboot or an update: arms the remembered instant again when it is still ahead of [now]; null when nothing to do. */
    fun restore(now: Instant = Instant.now()): Mode? {
        val at = armedAt() ?: return null
        return if (at.isAfter(now)) arm(at) else null
    }

    private fun fireIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_FIRE, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val REQUEST_FIRE = 7401
        const val WINDOW_MS = 10 * 60_000L
        private const val PREFS = "xiaoyu_alarm"
        private const val KEY_AT = "armed_at"
        private const val NONE = -1L
    }
}
