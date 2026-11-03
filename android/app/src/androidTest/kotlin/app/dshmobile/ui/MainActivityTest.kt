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

    /**
     * Waits until the WebView is on dsh's own page: past the launch URL (dsh answers it with a 303 to the clean `/`) and fully
     * loaded. URLs are never put into a message: the launch URL carries dsh's login token.
     */
    private fun ActivityScenario<MainActivity>.awaitDshPage() {
        waitFor(150_000, "dsh to load in the WebView") {
            val url = currentUrl()
            url != null && url.startsWith("http://127.0.0.1:") && !url.contains("token=")
        }
        waitFor(60_000, "dsh's page to finish loading") { eval("document.readyState") == "\"complete\"" }
    }

    private fun ActivityScenario<MainActivity>.currentUrl(): String? {
        var url: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { url = webView().url }
        return url
    }

    @Test fun showsTheDshPageInFogcity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.awaitDshPage()
            // the page itself (not only the URL) must be there, then the plugin's stylesheet must have taken effect
            waitFor(60_000, "the FOGCITY background") {
                scenario.eval("getComputedStyle(document.body).backgroundColor") == "\"rgb(254, 223, 225)\""
            }
            assertEquals("\"rgb(254, 223, 225)\"", scenario.eval("getComputedStyle(document.body).backgroundColor"))
        }
    }

    @Test fun anExternalLinkDoesNotNavigateTheWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.awaitDshPage()
            val before = scenario.currentUrl()
            scenario.eval(
                "(function(){var a=document.createElement('a');a.href='https://example.com/';a.id='dshm-ext';" +
                    "document.body.appendChild(a);a.click();return 1})()",
            )
            Thread.sleep(3_000)
            val after = scenario.currentUrl()
            assertTrue("the WebView left dsh", after?.startsWith("http://127.0.0.1:") == true)
            assertTrue("the WebView navigated away from dsh's page", before == after)
        }
    }
}
