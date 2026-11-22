package app.dshmobile.ui.agency

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.agency.core.brain.handlers.OnboardingStep
import app.dshmobile.DshMobileApp
import app.dshmobile.ui.kit.SakuraTheme

/**
 * The launcher (arch 1.13): 小雨娛樂. Opens the company ([app.dshmobile.agency.AgencyApp.start], off the main thread) and shows
 * the start screens, then 公司 / 指揮室 / 設定.
 *
 * The camera's answer is registered here, before the screen exists, so a result that comes back after the process died (the
 * camera app was open for a long time) still reaches the pending capture (arch T07).
 */
class AgencyActivity : ComponentActivity() {
    private val agency get() = (application as DshMobileApp).agency

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        agency.onCameraResult(r.resultCode == Activity.RESULT_OK)
    }

    /** Reminders need notifications (asked at run time); asked once, 設定 › 權限檢查 shows it afterwards. */
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val agency = agency
        agency.start()
        setContent { SakuraTheme { AgencyRoot(agency, AgencyActions(takePhoto = ::takePhoto, askNotifications = ::askNotificationsOnce)) } }
    }

    private fun askNotificationsOnce() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(app.agency.core.migrate.AgencyPrefKeys.NAME, MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
        runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private fun takePhoto(step: OnboardingStep) {
        val intent = agency.photoIntent(step) ?: return
        try {
            camera.launch(intent)
        } catch (_: ActivityNotFoundException) {
            agency.cameraMissing()
        }
    }
}

private const val ASKED_NOTIFICATIONS = "asked_notifications"

/**
 * What the screens ask the activity to do (things that need an activity): the camera app, and the notification permission
 * (asked once when the reminders start, after 入社建檔).
 */
class AgencyActions(val takePhoto: (OnboardingStep) -> Unit = {}, val askNotifications: () -> Unit = {})
