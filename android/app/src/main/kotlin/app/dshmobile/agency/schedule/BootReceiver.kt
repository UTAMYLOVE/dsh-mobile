package app.dshmobile.agency.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dshmobile.DshMobileApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** After a reboot, a clock or time-zone change and an app update, the next alarm is armed again (arch 1.6 boot restore). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext as? DshMobileApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> app.errorLog.append(TAG, "fire threw (${e.javaClass.simpleName})") }).launch {
            try {
                withTimeoutOrNull(AlarmReceiver.BUDGET_MS) { app.agency.onAlarm() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "alarm"
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
