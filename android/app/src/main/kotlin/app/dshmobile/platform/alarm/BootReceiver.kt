package app.dshmobile.platform.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dshmobile.DshMobileApp

/**
 * After a reboot, a clock or time-zone change and an app update the system has forgotten the alarm: the remembered instant is
 * armed again at once (no database needed), then [Wake.run] lets the company recompute what is due and arm the right next one.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val graph = (context.applicationContext as? DshMobileApp)?.graph
        runCatching { graph?.alarms?.restore() }
        val pending = goAsync()
        Wake.launch(context) { pending?.finish() }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
