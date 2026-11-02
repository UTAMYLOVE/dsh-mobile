package app.dshmobile.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import app.dshmobile.notify.Notifier

/** What the onboarding screen needs from the device; faked in tests. The app only ever opens settings screens, it never changes settings. */
interface OnboardingPlatform {
    fun notificationsGranted(): Boolean
    fun batteryOptimizationIgnored(): Boolean
    fun developerOptionsEnabled(): Boolean
    fun openBatterySettings()
    fun openDeveloperSettings()
    fun openDeviceInfoSettings()
}

object OnboardingIntents {
    fun ignoreBatteryOptimizations(packageName: String): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))

    fun developerSettings(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    fun deviceInfoSettings(): Intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)

    /** MainActivity is addressed by class name; Task 11a creates it. */
    fun mainActivity(packageName: String): Intent =
        Intent().setComponent(ComponentName(packageName, Notifier.MAIN_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

class AndroidOnboardingPlatform(private val activity: Activity) : OnboardingPlatform {
    private val context: Context get() = activity

    override fun notificationsGranted(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun batteryOptimizationIgnored(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    override fun developerOptionsEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: RuntimeException) {
        false
    }

    override fun openBatterySettings() {
        if (!start(OnboardingIntents.ignoreBatteryOptimizations(context.packageName))) {
            start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun openDeveloperSettings() {
        start(OnboardingIntents.developerSettings())
    }

    override fun openDeviceInfoSettings() {
        if (!start(OnboardingIntents.deviceInfoSettings())) start(Intent(Settings.ACTION_SETTINGS))
    }

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
