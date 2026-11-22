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

/**
 * The armed alarm fired (arch 1.6): the company opens if it is not open, posts what is due at once (code templates, on time
 * even offline), writes the nudges, re-arms, and asks the wording worker for the model's words. Not exported.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val app = context.applicationContext as? DshMobileApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> app.errorLog.append(TAG, "fire threw (${e.javaClass.simpleName})") }).launch {
            try {
                withTimeoutOrNull(BUDGET_MS) { app.agency.onAlarm() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "alarm"
        const val ACTION_FIRE = "app.dshmobile.agency.FIRE"

        /** A receiver has about ten seconds; the database open and one tick fit well within this. */
        const val BUDGET_MS = 9_000L
    }
}
