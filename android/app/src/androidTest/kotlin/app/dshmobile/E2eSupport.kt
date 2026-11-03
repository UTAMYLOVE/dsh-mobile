package app.dshmobile

import android.Manifest
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.dshmobile.gateway.Provider
import app.dshmobile.system.OnboardingState
import app.dshmobile.ui.MainActivity
import app.dshmobile.ui.MainUiState
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Waiting, shell access and diagnostics shared by the end-to-end tests. Nothing here prints a URL (they carry tokens). */
object E2e {
    val app: DshMobileApp get() = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp

    /** Rulings 2-4: onboarding skipped, the fake key in the real Keystore vault, the bundled rootfs installed from the APK asset. */
    fun prepareApp() {
        val app = app
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (!app.rootfsInstaller.isInstalled()) {
            val t0 = System.currentTimeMillis()
            app.rootfsInstaller.install { }
            Log.i(FakeModel.TAG, "rootfs installed in ${(System.currentTimeMillis() - t0) / 1000} s")
        }
        app.keyVault.put(Provider.DEEPSEEK, FakeModel.E2E_KEY)
        val state = OnboardingState(app.getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE))
        OnboardingState.Step.entries.forEach { state.markDone(it) }
    }

    /** The last lines of the app's error log (already masked when written: session token, launch token, 64-hex runs). */
    fun errorLogTail(lines: Int = 60): String = try {
        app.errorLog.file().readLines().takeLast(lines).joinToString("\n")
    } catch (e: Exception) {
        "(no error log: ${e.javaClass.simpleName})"
    }

    fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("timed out after ${timeoutMs / 1000} s waiting for $what\n--- error log tail ---\n${errorLogTail()}")
    }

    /** Waits until the main screen's shell is Ready: dsh runs and the native interface's remote to it is open. */
    fun awaitShellReady(scenario: ActivityScenario<MainActivity>) {
        waitFor(300_000, "the native shell to be ready (dsh up, remote open)") {
            var ready = false
            scenario.onActivity { ready = it.uiStateForTest.value == MainUiState.Ready }
            ready
        }
    }

    /** Class names of every WebView in [activity]'s view tree (the main flow must have none). */
    fun webViewsIn(activity: MainActivity): List<String> {
        val found = mutableListOf<String>()
        fun walk(v: View) {
            if (v is WebView) found += v.javaClass.name
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(activity.window.decorView)
        return found
    }

    /** On failure, puts the masked error log into logcat (uploaded by CI on failure). */
    class DumpErrorLogOnFailure : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e(FakeModel.TAG, "${description.methodName} failed; model requests: ${FakeModel.instance.seen}")
            errorLogTail(400).lineSequence().forEach { Log.e(FakeModel.TAG, "errorlog| $it") }
        }
    }
}
