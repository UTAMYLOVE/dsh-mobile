package app.agency.core.net

import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmTestKit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkGateTest {
    private val kit = LlmTestKit()
    private val other = MockWebServer().apply { start() }

    @After fun tearDown() {
        kit.close()
        other.shutdown()
    }

    private val production = NetworkGate({ NetworkGate.allowlist("https://my-llm.example.com/v1") })

    @Test fun theProductionAllowlistIsTheModelHostsPlusHisHttpsService() {
        assertEquals(
            setOf(Origin.https("api.deepseek.com"), Origin.https("api.anthropic.com"), Origin.https("my-llm.example.com")),
            NetworkGate.allowlist("https://My-LLM.example.com/v1/chat/completions"),
        )
        val bare = setOf(Origin.https("api.deepseek.com"), Origin.https("api.anthropic.com"))
        assertEquals(bare, NetworkGate.allowlist(null))
        assertEquals(bare, NetworkGate.allowlist("http://my-llm.example.com/v1"))
        assertEquals(bare, NetworkGate.allowlist("https://my-llm.example.com:8443/v1"))
        assertEquals(bare, NetworkGate.allowlist("not a url"))
    }

    @Test fun cleartextOtherHostsAndOtherPortsAreBlocked() {
        assertTrue(production.permits("https://api.deepseek.com/chat/completions".toHttpUrl()))
        assertTrue(production.permits("https://my-llm.example.com/v1/chat/completions".toHttpUrl()))
        assertFalse(production.permits("http://api.deepseek.com/chat/completions".toHttpUrl()))
        assertFalse(production.permits("https://api.deepseek.com:8443/chat/completions".toHttpUrl()))
        assertFalse(production.permits("https://example.com/".toHttpUrl()))
        assertFalse(production.permits("https://api.deepseek.com.evil.example/".toHttpUrl()))
    }

    @Test fun aBlockedRequestFailsBeforeAnySocketAndLogsTheHostOnly() {
        val hosts = mutableListOf<String>()
        val gate = NetworkGate({ setOf(Origin.https("api.deepseek.com")) }, { hosts += it })
        val client = HttpFactory.create(gate)
        // Port 9 of a host that does not resolve: a socket attempt would fail with a different exception.
        for (url in listOf("http://api.deepseek.com/x?key=secret", "https://nowhere.invalid:9/x", "https://api.deepseek.com:9/x")) {
            try {
                client.newCall(Request.Builder().url(url).post("{}".toRequestBody("application/json".toMediaType())).build()).execute()
                fail("not blocked: $url")
            } catch (e: BlockedHostException) {
                assertFalse(e.message.orEmpty().contains("secret"))
            }
        }
        assertEquals(listOf("api.deepseek.com", "nowhere.invalid", "api.deepseek.com"), hosts)
        assertEquals(3, gate.blockedCount)
    }

    @Test fun aClientSeesBlockedHost() = runBlocking {
        kit.allowed = emptySet()
        assertEquals(ChatResult.Failed(Fail.BLOCKED_HOST), kit.deepseek().chat(LlmTestKit.user))
        assertEquals(0, kit.server.requestCount)
        assertEquals(listOf(kit.base.host), kit.blockedHosts)
    }

    @Test fun redirectsAreNotFollowed() = runBlocking {
        kit.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal").toString()))
        assertEquals(ChatResult.Failed(Fail.BAD_RESPONSE), kit.deepseek().chat(LlmTestKit.user))
        assertEquals(0, other.requestCount)
    }

    @Test fun anAllowedHostsRedirectTargetIsBlockedEvenWithRedirectsOn() {
        kit.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal").toString()))
        val following = kit.http.newBuilder().followRedirects(true).build()
        try {
            following.newCall(Request.Builder().url(kit.base.resolve("/x")!!).header("Authorization", "Bearer k")
                .post("{}".toRequestBody("application/json".toMediaType())).build()).execute()
            fail("redirect target reached")
        } catch (e: java.io.IOException) {
            val blocked = (listOf(e) + e.suppressed).filterIsInstance<BlockedHostException>().single()
            assertEquals(other.url("/").host, blocked.host)
        }
        assertEquals(0, other.requestCount)
        assertEquals(1, kit.server.requestCount)
    }

    @Test fun theSpendCapBlocksBeforeAnySocket() = runBlocking {
        kit.capBlocked = true
        kit.allowed = emptySet() // a request that got as far as the gate would show up as BLOCKED_HOST
        assertEquals(ChatResult.Failed(Fail.CAP), kit.deepseek().chat(LlmTestKit.user))
        assertEquals(ChatResult.Failed(Fail.CAP), kit.claude().chat(LlmTestKit.user))
        assertTrue(kit.blockedHosts.isEmpty())
        assertEquals(0, kit.server.requestCount)
    }

    @Test fun theFactoryClientRefusesRedirectsAndHasOnlyTheGate() {
        val c = HttpFactory.create(production)
        assertFalse(c.followRedirects)
        assertFalse(c.followSslRedirects)
        assertEquals(listOf<Any>(HttpFactory.RuntimeToIo, production), c.interceptors)
        assertEquals(listOf<Any>(production), c.networkInterceptors)
        assertEquals(15_000, c.connectTimeoutMillis)
        assertEquals(60_000, c.readTimeoutMillis)
        assertEquals(90_000, c.callTimeoutMillis)
    }
}
