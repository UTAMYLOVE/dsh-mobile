package app.dshmobile.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class DshClientTest {
    private val dsh = FakeDshServer()
    private val other = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @After fun cleanup() { dsh.close(); other.shutdown() }

    @Test fun loginThenGetCarriesTheCookie() = runBlocking {
        val client = DshClient(OkHttpClient(), dsh.launch)
        client.login()
        client.get("/dshm/health/memory").use { assertEquals(200, it.code) }
        assertTrue(dsh.unauthenticated.isEmpty())
    }

    @Test fun getWithoutLoginIsUnauthorized() = runBlocking {
        DshClient(OkHttpClient(), dsh.launch).get("/").use { assertEquals(401, it.code) }
    }

    @Test fun postJsonSendsBodyAsJson() = runBlocking {
        val client = DshClient(OkHttpClient(), dsh.launch)
        client.login()
        client.postJson("/echo", """{"a":1}""").use { assertEquals("""{"a":1}""", it.body!!.string()) }
        val sent = dsh.requests.last()
        assertEquals("POST", sent.method)
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun postJsonForTextReturnsCodeAndBody() = runBlocking {
        val client = DshClient(OkHttpClient(), dsh.launch)
        client.login()
        val r = client.postJsonForText("/echo", """{"a":1}""")
        assertEquals(DshClient.TextResponse(200, """{"a":1}"""), r)
    }

    @Test fun postJsonForTextIsCancelledPromptlyWhileTheBodyDribbles() = runBlocking {
        FakeDshServer(heartbeat = { MockResponse().setBody("slow").setBodyDelay(4, java.util.concurrent.TimeUnit.SECONDS) }).use { slow ->
            val client = DshClient(OkHttpClient(), slow.launch)
            client.login()
            val started = System.currentTimeMillis()
            val result = kotlinx.coroutines.withTimeoutOrNull(500) { client.postJsonForText("/dshm/heartbeat", "{}") }
            assertEquals(null, result)
            assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 2_500)
        }
    }

    @Test fun loginFailsWhenTheServerRefusesTheToken() = runBlocking {
        FakeDshServer(tokenValid = { false }).use { refusing ->
            try {
                DshClient(OkHttpClient(), refusing.launch).login()
                fail("expected IOException")
            } catch (e: IOException) {
                assertTrue(!e.message.orEmpty().contains("token=good"))
            }
        }
    }

    @Test fun neverFollowsARedirectToAnotherHost() = runBlocking {
        other.enqueue(MockResponse().setBody("secret"))
        MockWebServer().use { redirecting ->
            redirecting.start(InetAddress.getByName("127.0.0.1"), 0)
            redirecting.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "http://127.0.0.1:${other.port}/"))
            val client = DshClient(OkHttpClient(), LaunchUrl("http://127.0.0.1:${redirecting.port}/?token=x", redirecting.port))
            try {
                client.login()
                fail("expected IOException")
            } catch (_: IOException) {
            }
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun cookiesAreOnlySentToTheDshPort() = runBlocking {
        val client = DshClient(OkHttpClient(), dsh.launch)
        client.login()
        // A client has exactly one origin; anything else has to be refused rather than sent the cookie.
        try {
            client.get("//127.0.0.1:${other.port}/x").close()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, other.requestCount)
    }
}
