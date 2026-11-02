package app.dshmobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.dshmobile.DshMobileApp
import app.dshmobile.gateway.Provider
import app.dshmobile.system.OnboardingState
import app.dshmobile.system.OnboardingState.Step
import app.dshmobile.system.PhantomProcessDetector
import app.dshmobile.ui.theme.SakuraTheme

/** Hosts [OnboardingScreen] with the app's real singletons; once finished it opens MainActivity (Task 11a) and closes. */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as DshMobileApp
        val state = OnboardingState(getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE))
        val installer = app.rootfsInstaller
        val vault = app.keyVault
        // A step recorded as done is only true while its result still exists (new rootfs version, wiped key).
        if (state.isDone(Step.ROOTFS) && !installer.isInstalled()) state.clear(Step.ROOTFS)
        if (state.isDone(Step.DEEPSEEK_KEY) && vault.get(Provider.DEEPSEEK) == null) state.clear(Step.DEEPSEEK_KEY)
        setContent {
            SakuraTheme {
                OnboardingScreen(
                    state = state,
                    platform = AndroidOnboardingPlatform(this),
                    vault = vault,
                    detector = PhantomProcessDetector(contentResolver),
                    isRootfsInstalled = installer::isInstalled,
                    installRootfs = installer::install,
                    onFinished = ::openMain,
                )
            }
        }
    }

    private fun openMain() {
        try {
            startActivity(OnboardingIntents.mainActivity(packageName))
        } catch (_: ActivityNotFoundException) {
            // MainActivity arrives with Task 11a; until then onboarding just closes.
        }
        finish()
    }
}
