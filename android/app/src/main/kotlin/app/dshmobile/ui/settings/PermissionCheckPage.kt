package app.dshmobile.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.notify.AgencyNotifier
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.SoftButton

object PermissionTags {
    const val NOTIFY = "ag-perm-notify"
    const val CHANNEL = "ag-perm-channel"
    const val EXACT = "ag-perm-exact"
    const val BATTERY = "ag-perm-battery"
    const val OPEN = "ag-perm-open-"
}

/** What the reminders need, checked now (T13): notifications on, 行程提醒 not muted, exact alarms, and the battery hint. */
data class PermissionState(val notifications: Boolean, val scheduleChannel: Boolean, val exact: Boolean, val batteryFree: Boolean) {
    companion object {
        fun read(context: Context, agency: AgencyApp): PermissionState {
            val nm = context.getSystemService(NotificationManager::class.java)
            agency.notifier.channels()
            val ch = runCatching { nm.getNotificationChannel(AgencyNotifier.CHANNEL_SCHEDULE) }.getOrNull()
            val pm = context.getSystemService(PowerManager::class.java)
            return PermissionState(
                notifications = runCatching { nm.areNotificationsEnabled() }.getOrDefault(false),
                scheduleChannel = ch == null || ch.importance >= NotificationManager.IMPORTANCE_HIGH,
                exact = agency.scheduler.exactAllowed(),
                batteryFree = runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false),
            )
        }
    }
}

/** 設定 › 權限檢查 (arch T13): each item says whether it is fine and, when not, what to open in the system settings. */
@Composable
fun PermissionCheckPage(agency: AgencyApp) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val s = remember(refresh) { PermissionState.read(context, agency) }
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        refresh++
    }
    val appNotify = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    Section(stringResource(R.string.ag_set_permissions)) {
        Row(PermissionTags.NOTIFY, s.notifications, R.string.ag_perm_notify_ok, R.string.ag_perm_notify_bad) { open(appNotify) }
        Row(PermissionTags.CHANNEL, s.scheduleChannel, R.string.ag_perm_channel_ok, R.string.ag_perm_channel_bad) {
            open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, AgencyNotifier.CHANNEL_SCHEDULE))
        }
        Row(PermissionTags.EXACT, s.exact, R.string.ag_perm_exact_ok, R.string.ag_perm_exact_bad) {
            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts(PACKAGE, context.packageName, null)))
        }
        Row(PermissionTags.BATTERY, s.batteryFree, R.string.ag_perm_battery_ok, R.string.ag_perm_battery_bad) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private const val PACKAGE = "package"

@Composable
private fun Row(tag: String, ok: Boolean, okText: Int, badText: Int, onOpen: () -> Unit) {
    Text(stringResource(if (ok) okText else badText), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(tag))
    if (!ok) SoftButton(stringResource(R.string.ag_perm_open), onClick = onOpen, modifier = Modifier.testTag(PermissionTags.OPEN + tag))
}
