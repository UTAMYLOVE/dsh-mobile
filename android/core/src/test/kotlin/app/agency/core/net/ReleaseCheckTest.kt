package app.agency.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新版本通知: one GET of the latest release, only through the gate, with only the headers it needs; the tag is compared by code. */
class ReleaseCheckTest {
    private val server = MockWebServer().apply { start() }

    @After fun stop() = server.shutdown()

    private fun check(allow: Boolean = true): ReleaseCheck {
        val url = server.url("/repos/${ReleaseCheck.OWNER}/${ReleaseCheck.REPO}/releases/latest")
        val gate = NetworkGate({ if (allow) setOf(Origin(url.scheme, url.host, url.port)) else emptySet() })
        return ReleaseCheck(Transport(HttpFactory.create(gate)) {}, url)
    }

    @Test fun theLatestReleaseIsReadFromOneBareRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.9.1+19","html_url":"https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/v0.9.1%2B19","body":"說明"}"""))
        val r = check().latest()!!
        assertEquals(Release("v0.9.1+19", "0.9.1", 19, "https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/v0.9.1%2B19"), r)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals(0L, req.bodySize)
        // Nothing about him or the phone: no key, no cookie, a generic agent.
        assertEquals("dsh-mobile", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
        assertEquals(setOf("accept", "user-agent"), req.headers.names().map { it.lowercase() }.filter { it !in setOf("host", "connection", "accept-encoding") }.toSet())
        assertTrue(ReleaseCheck.newer(r, "0.9.0", 18))
        assertFalse(ReleaseCheck.newer(r, "0.9.1", 19))
    }

    @Test fun theGateDecidesAndABadAnswerIsNoRelease() = runBlocking {
        assertNull(check(allow = false).latest())
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(check().latest())
        server.enqueue(MockResponse().setBody("""{"tag_name":"nightly","html_url":"https://evil.example/x"}"""))
        assertNull(check().latest())
        // A page outside the releases repository is never offered.
        server.enqueue(MockResponse().setBody("""{"tag_name":"v1.0.0","html_url":"https://evil.example/x"}"""))
        assertEquals(ReleaseCheck.RELEASES_PAGE, check().latest()!!.page)
        assertEquals("1.0.0" to null, ReleaseCheck.parseTag("v1.0.0"))
        assertTrue(ReleaseCheck.newer(Release("v1.0.0", "1.0.0", null, ""), "0.9.0", 18))
        // The production allowlist has GitHub's API, nothing else new.
        assertEquals(setOf(NetworkGate.DEEPSEEK_HOST, NetworkGate.ANTHROPIC_HOST, NetworkGate.GITHUB_API_HOST), NetworkGate.allowlist(null).map { it.host }.toSet())
    }
}
