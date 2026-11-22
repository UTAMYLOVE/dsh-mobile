package app.dshmobile.agency.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.agency.core.engine.Fire
import app.agency.core.engine.FireKind
import app.dshmobile.ui.agency.AgencyActivity

/**
 * One armed alarm at a time (arch 1.6, the dot-tasks pattern): code computes the next fire and this re-arms the one
 * PendingIntent (fixed request code, immutable). A slot uses `setAlarmClock` (exempt from Doze, shown as the next alarm); a chase
 * or a link's reminder `setExactAndAllowWhileIdle`. When exact alarms are not allowed, a 10-minute window instead (and 設定 ›
 * 權限檢查 says why reminders may be late).
 */
class AlarmScheduler(private val context: Context) {
    enum class Mode { ALARM_CLOCK, EXACT, WINDOW }

    private val am: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun exactAllowed(): Boolean = runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

    /** Arms [fire] (cancelling the one before), or only cancels when there is none; the mode used. */
    fun arm(fire: Fire?): Mode? {
        val pi = fireIntent()
        am.cancel(pi)
        if (fire == null) return null
        val at = fire.at.toEpochMilli()
        if (!exactAllowed()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
            return Mode.WINDOW
        }
        return if (fire.kind == FireKind.SLOT) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), pi)
            Mode.ALARM_CLOCK
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Mode.EXACT
        }
    }

    private fun fireIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_FIRE, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What the system's alarm icon opens: the company. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_SHOW, Intent(context, AgencyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val REQUEST_FIRE = 7401
        const val REQUEST_SHOW = 7402
        const val WINDOW_MS = 10 * 60_000L
    }
}
