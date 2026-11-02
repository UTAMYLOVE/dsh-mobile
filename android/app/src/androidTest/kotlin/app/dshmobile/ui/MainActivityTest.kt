package app.dshmobile.ui

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dshmobile.DshMobileApp
import app.dshmobile.gateway.Provider
import app.dshmobile.system.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Needs the real thing: the bundled rootfs asset, a real dsh inside proot and the three bundled plugins, so it runs on the
 * emulator in CI (Task 13), not in the local unit-test run (locally it only has to compile). The DeepSeek key stored here is a
 * placeholder: nothing in this test makes a model call.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp

    @Before fun finishOnboarding() {
        if (!app.rootfsInstaller.isInstalled()) app.rootfsInstaller.install { }
        if (app.keyVault.get(Provider.DEEPSEEK) == null) app.keyVault.put(Provider.DEEPSEEK, "sk-instrumented-test-placeholder-0000")
        val state = OnboardingState(app.getSharedPreferences(OnboardingState.PREFS, Context.MODE_PRIVATE))
        OnboardingState.Step.values().forEach { state.markDone(it) }
    }

    private fun ActivityScenario<MainActivity>.webView(): WebView {
        var view: WebView? = null
        onActivity { view = it.webViewForTest }
        return view!!
    }

    /** Runs [js] in the page and returns its JSON result (`"null"` while there is no page yet). */
    private fun ActivityScenario<MainActivity>.eval(js: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView().evaluateJavascript(js) { result = it ?: "null"; latch.countDown() }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "no answer from the page" }
        return result
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(500)
        }
        fail("timed out waiting for $what")
    }

    private fun ActivityScenario<MainActivity>.currentUrl(): String? {
        var url: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { url = webView().url }
        return url
    }

    @Test fun showsTheDshPageInSakuraPink() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(150_000, "dsh to load in the WebView") { scenario.currentUrl()?.startsWith("http://127.0.0.1:") == true }
            // the page itself (not only the URL) must be there, then the plugin's stylesheet must have taken effect
            waitFor(60_000, "the sakura background") {
                scenario.eval("getComputedStyle(document.body).backgroundColor") == "\"rgb(255, 245, 248)\""
            }
            assertEquals("\"rgb(255, 245, 248)\"", scenario.eval("getComputedStyle(document.body).backgroundColor"))
        }
    }

    @Test fun anExternalLinkDoesNotNavigateTheWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(150_000, "dsh to load in the WebView") { scenario.currentUrl()?.startsWith("http://127.0.0.1:") == true }
            val before = scenario.currentUrl()
            scenario.eval(
                "(function(){var a=document.createElement('a');a.href='https://example.com/';a.id='dshm-ext';" +
                    "document.body.appendChild(a);a.click();return 1})()",
            )
            Thread.sleep(3_000)
            val after = scenario.currentUrl()
            assertTrue("stayed on dsh: $after", after?.startsWith("http://127.0.0.1:") == true)
            assertEquals(before, after)
        }
    }
}
