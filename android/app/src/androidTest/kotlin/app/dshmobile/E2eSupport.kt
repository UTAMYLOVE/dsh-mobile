package app.dshmobile

import android.Manifest
import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.dshmobile.gateway.Provider
import app.dshmobile.system.OnboardingState
import app.dshmobile.ui.MainActivity
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Waiting, page access and diagnostics shared by the end-to-end tests. Nothing here prints a URL (they carry tokens). */
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

    fun ActivityScenario<MainActivity>.webView(): WebView {
        var view: WebView? = null
        onActivity { view = it.webViewForTest }
        return view!!
    }

    /** Runs [js] in the page and returns its JSON-encoded result (`"null"` while there is no page). */
    fun ActivityScenario<MainActivity>.eval(js: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView().evaluateJavascript(js) { result = it ?: "null"; latch.countDown() }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "no answer from the page" }
        return result
    }

    fun ActivityScenario<MainActivity>.onDsh(): Boolean {
        var url: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { url = webView().url }
        return url?.startsWith("http://127.0.0.1:") == true
    }

    /** Waits until the main screen shows dsh's own page (title set, app root rendered) painted in FOGCITY's blush pink. */
    fun ActivityScenario<MainActivity>.awaitDshInFogcity() {
        waitFor(300_000, "dsh to load in the WebView") { onDsh() }
        waitFor(120_000, "dsh's page (document.title and a rendered body)") {
            eval("document.title.length > 0 && document.body != null && document.body.children.length > 0") == "true"
        }
        waitFor(60_000, "the FOGCITY background") { eval(BG) == FOGCITY }
    }

    const val BG = "getComputedStyle(document.body).backgroundColor"
    /** FOGCITY's blush background #FEDFE1 (packages/ui/src/fogcity.ts). */
    const val FOGCITY = "\"rgb(254, 223, 225)\""

    /** On failure, puts the masked error log into logcat (uploaded by CI on failure). */
    class DumpErrorLogOnFailure : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e(FakeModel.TAG, "${description.methodName} failed; model requests: ${FakeModel.instance.seen}")
            errorLogTail(400).lineSequence().forEach { Log.e(FakeModel.TAG, "errorlog| $it") }
        }
    }
}
