package app.dshmobile.platform.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dshmobile.DshMobileApp

/** The armed alarm fired: forget it and hand over to [Wake.run]. Not exported; only our own PendingIntent reaches it. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        (context.applicationContext as? DshMobileApp)?.graph?.alarms?.fired()
        val pending = goAsync()
        Wake.launch(context) { pending?.finish() }
    }

    companion object {
        const val ACTION_FIRE = "app.dshmobile.platform.alarm.FIRE"
    }
}
