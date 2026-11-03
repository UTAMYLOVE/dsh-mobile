package app.dshmobile.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import app.dshmobile.ui.DshWebViewClient.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val PORT = 41234

@RunWith(RobolectricTestRunner::class)
class DshWebViewClientTest {
    private fun decide(url: String?, port: Int = PORT) = DshWebViewClient.decide(url, port)

    // --- decide(): the navigation whitelist ---

    @Test fun dshOriginIsAllowed() {
        assertEquals(Decision.ALLOW, decide("http://127.0.0.1:$PORT/"))
        assertEquals(Decision.ALLOW, decide("http://127.0.0.1:$PORT"))
        assertEquals(Decision.ALLOW, decide("http://127.0.0.1:$PORT/?token=abc"))
        assertEquals(Decision.ALLOW, decide("http://127.0.0.1:$PORT/assets/a.js#x"))
        assertEquals(Decision.ALLOW, decide("HTTP://127.0.0.1:$PORT/UPPER"))
    }

    @Test fun otherPortsOnLoopbackAreBlocked() {
        assertEquals(Decision.BLOCK, decide("http://127.0.0.1:${PORT + 1}/"))
        assertEquals(Decision.BLOCK, decide("http://127.0.0.1/"))
        assertEquals(Decision.BLOCK, decide("http://127.0.0.1:80/"))
    }

    @Test fun loopbackWithoutKnownPortIsBlocked() {
        assertEquals(Decision.BLOCK, decide("http://127.0.0.1:$PORT/", port = -1))
        assertEquals(Decision.BLOCK, decide("http://127.0.0.1:$PORT/", port = 0))
    }

    @Test fun lookAlikeHostsAreExternalNotAllowed() {
        assertEquals(Decision.EXTERNAL, decide("http://127.0.0.1.evil.com:$PORT/"))
        assertEquals(Decision.EXTERNAL, decide("http://evil.com:$PORT/"))
    }

    @Test fun credentialsInTheUrlAreNeverAllowed() {
        // Whatever the browser would make of it, the WebView must not load it.
        assertTrue(decide("http://127.0.0.1:$PORT@evil.com/") != Decision.ALLOW)
        assertTrue(decide("http://evil.com@127.0.0.1:$PORT/") != Decision.ALLOW)
        assertTrue(decide("http://user:pw@127.0.0.1:$PORT/") != Decision.ALLOW)
    }

    @Test fun localhostAndOtherLoopbackNamesAreBlocked() {
        assertEquals(Decision.BLOCK, decide("http://localhost:$PORT/"))
        assertEquals(Decision.BLOCK, decide("http://LOCALHOST:$PORT/"))
        assertEquals(Decision.BLOCK, decide("http://foo.localhost:$PORT/"))
        assertEquals(Decision.BLOCK, decide("http://127.0.0.2:$PORT/"))
        assertEquals(Decision.BLOCK, decide("http://[::1]:$PORT/"))
        assertEquals(Decision.BLOCK, decide("http://0.0.0.0:$PORT/"))
    }

    @Test fun httpsToLoopbackIsBlocked() {
        assertEquals(Decision.BLOCK, decide("https://127.0.0.1:$PORT/"))
    }

    @Test fun otherWebUrlsGoToTheSystemBrowser() {
        assertEquals(Decision.EXTERNAL, decide("https://example.com/"))
        assertEquals(Decision.EXTERNAL, decide("http://example.com/path?q=1"))
        assertEquals(Decision.EXTERNAL, decide("HTTPS://Example.com"))
        assertEquals(Decision.EXTERNAL, decide("http://10.0.0.1/"))
        assertEquals(Decision.EXTERNAL, decide("http://192.168.1.1:8080/"))
    }

    @Test fun dangerousAndUnknownSchemesAreBlocked() {
        for (url in listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", "file:///etc/passwd", "file:///data/data/app.dshmobile/",
            "content://app.dshmobile.export/export/a.zip", "intent://x#Intent;scheme=http;end", "data:text/html,<b>x</b>",
            "blob:http://127.0.0.1:$PORT/uuid", "about:blank", "ftp://example.com/", "mailto:a@b.c", "tel:123",
            "market://details?id=x", "chrome://crash", "//example.com/", "relative/path", "",
        )) {
            assertEquals(url, Decision.BLOCK, decide(url))
        }
    }

    @Test fun nullAndMalformedUrlsAreBlocked() {
        assertEquals(Decision.BLOCK, decide(null))
        assertEquals(Decision.BLOCK, decide("http://"))
        assertEquals(Decision.BLOCK, decide("http://exa mple.com/"))
        assertEquals(Decision.BLOCK, decide("http://127.0.0.1:99999999/"))
        assertEquals(Decision.BLOCK, decide("http://127.0.0.1:abc/"))
        assertEquals(Decision.BLOCK, decide("   "))
    }

    // --- the client ---

    private class FakeRequest(private val uri: String, private val gesture: Boolean, private val main: Boolean = true) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(uri)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private val opened = mutableListOf<String>()
    private val client = DshWebViewClient(openExternal = { opened += it.toString() }).also { it.dshPort = PORT }

    @Test fun allowedUrlsAreLoadedByTheWebView() {
        assertFalse(client.shouldOverrideUrlLoading(null, FakeRequest("http://127.0.0.1:$PORT/x", gesture = true)))
        assertTrue(opened.isEmpty())
    }

    @Test fun externalUrlsOpenInTheBrowserOnlyAfterAUserGesture() {
        assertTrue(client.shouldOverrideUrlLoading(null, FakeRequest("https://example.com/a", gesture = true)))
        assertEquals(listOf("https://example.com/a"), opened)
        assertTrue(client.shouldOverrideUrlLoading(null, FakeRequest("https://example.com/b", gesture = false)))
        assertEquals("a script cannot open the browser by itself", listOf("https://example.com/a"), opened)
    }

    @Test fun blockedUrlsAreSwallowed() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "http://127.0.0.1:${PORT + 1}/", "http://localhost:$PORT/")) {
            assertTrue(url, client.shouldOverrideUrlLoading(null, FakeRequest(url, gesture = true)))
        }
        assertTrue(opened.isEmpty())
    }

    @Test fun theWhitelistFollowsThePortOfTheCurrentDsh() {
        val fresh = DshWebViewClient(openExternal = { opened += it.toString() })
        // Before dsh is known nothing on loopback may load.
        assertTrue(fresh.shouldOverrideUrlLoading(null, FakeRequest("http://127.0.0.1:$PORT/", gesture = true)))
        fresh.dshPort = PORT
        assertFalse(fresh.shouldOverrideUrlLoading(null, FakeRequest("http://127.0.0.1:$PORT/", gesture = true)))
        fresh.dshPort = PORT + 5
        assertTrue(fresh.shouldOverrideUrlLoading(null, FakeRequest("http://127.0.0.1:$PORT/", gesture = true)))
    }

    @Test fun externalOpenFailureDoesNotCrash() {
        val c = DshWebViewClient(openExternal = { throw android.content.ActivityNotFoundException() }).also { it.dshPort = PORT }
        assertTrue(c.shouldOverrideUrlLoading(null, FakeRequest("https://example.com/", gesture = true)))
    }
}
