package app.dshmobile.ui.agency

import android.app.Activity
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
 * the start screens, then 公司 / 指揮室 / 設定. Nothing of dsh is started from here.
 *
 * The camera's answer is registered here, before the screen exists, so a result that comes back after the process died (the
 * camera app was open for a long time) still reaches the pending capture (arch T07).
 */
class AgencyActivity : ComponentActivity() {
    private val agency get() = (application as DshMobileApp).agency

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        agency.onCameraResult(r.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val agency = agency
        agency.start()
        setContent { SakuraTheme { AgencyRoot(agency, AgencyActions(takePhoto = ::takePhoto)) } }
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

/** What the screens ask the activity to do (things that need an activity: the camera app). */
class AgencyActions(val takePhoto: (OnboardingStep) -> Unit = {})
