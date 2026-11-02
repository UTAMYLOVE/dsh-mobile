package app.dshmobile.gateway

import app.dshmobile.security.KeyVault
import app.dshmobile.security.SessionToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class LocalGatewayTest {
    private class FakeVault(initial: Map<Provider, String> = emptyMap()) : KeyVault {
        private val keys = HashMap(initial)
        override fun get(p: Provider): String? = keys[p]
        override fun put(p: Provider, key: String) { keys[p] = key }
        override fun clear(p: Provider) { keys.remove(p) }
    }

    private val json = Json
    private val clock = MutableClock() // Saturday 02:00 UTC: DeepSeek off-peak
    private val token = SessionToken.generate()
    private val deepseekUpstream = MockWebServer()
    private val anthropicUpstream = MockWebServer()
    private val notifications = CopyOnWriteArrayList<NotifyRequest>()
    private val costAlerts = CopyOnWriteArrayList<Double>()
    private val rateGuards = CopyOnWriteArrayList<Provider>()
    private val meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = clock)
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private var gateway: LocalGateway? = null
    private var port = 0

    @Before fun setUp() {
        deepseekUpstream.start()
        anthropicUpstream.start()
    }

    @After fun tearDown() {
        gateway?.stop()
        deepseekUpstream.shutdown()
        anthropicUpstream.shutdown()
    }

    private fun startGateway(
        vault: KeyVault = FakeVault(mapOf(Provider.DEEPSEEK to "real-ds-key", Provider.ANTHROPIC to "real-an-key")),
        rateGuard: RateGuard = RateGuard(clock = Clock.systemUTC()),
        gatewayClock: Clock = Clock.systemUTC(),
    ): LocalGateway {
        val upstreams: Map<Provider, HttpUrl> = mapOf(
            Provider.DEEPSEEK to deepseekUpstream.url("/anthropic"),
            Provider.ANTHROPIC to anthropicUpstream.url("/"),
        )
        return LocalGateway(
            token, vault, meter, upstreams,
            onNotify = { notifications += it },
            onCostAlert = { costAlerts += it },
            onRateGuard = { rateGuards += it },
            rateGuard = rateGuard,
            clock = gatewayClock,
        ).also {
            gateway = it
            port = it.start()
        }
    }

    private fun url(path: String, tokenValue: String = token.value) = "http://127.0.0.1:$port/$tokenValue$path"

    private fun postJson(path: String, body: String, tokenValue: String = token.value, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url(path, tokenValue))
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    private fun Response.text(): String = checkNotNull(body).string()

    private fun sseEvent(data: String) = "data: $data\n\n"

    private fun padded(event: String, size: Int): String {
        require(event.length + 3 <= size)
        return event + ":" + "x".repeat(size - event.length - 3) + "\n\n"
    }

    private val messageStart = """{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1}}}"""
    private val messageDelta = """{"type":"message_delta","usage":{"input_tokens":100,"output_tokens":50}}"""
    private val expectedStreamCost =
        PriceTable().costUsd(Provider.DEEPSEEK, "deepseek-flash", Usage(inputTokens = 100, outputTokens = 50), clock.instant())

    private fun sseResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    private fun take(server: MockWebServer): RecordedRequest =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "upstream saw no request" }

    @Test fun injectsDeepseekKeyAndStripsFake() {
        startGateway()
        deepseekUpstream.enqueue(MockResponse().setBody("{}"))
        postJson(
            "/deepseek/v1/messages", """{"model":"deepseek-flash"}""",
            headers = mapOf(
                "x-api-key" to "dshm-proxy",
                "Authorization" to "Bearer dshm-proxy",
                "x-deepseek-harness-user-id" to "u1",
                "x-deepseek-harness-session-id" to "s1",
                "X-DeepSeek-Harness-Foo" to "made-up",
                "anthropic-version" to "2023-06-01",
            ),
        ).close()
        val seen = take(deepseekUpstream)
        assertEquals("real-ds-key", seen.getHeader("x-api-key"))
        assertEquals(listOf("real-ds-key"), seen.headers.values("x-api-key"))
        assertNull(seen.getHeader("Authorization"))
        assertNull(seen.getHeader("x-deepseek-harness-user-id"))
        assertNull(seen.getHeader("x-deepseek-harness-session-id"))
        assertTrue(seen.headers.none { it.first.lowercase().startsWith("x-deepseek-harness-") })
        assertTrue(seen.headers.none { it.second.contains("dshm-proxy") })
        assertEquals("2023-06-01", seen.getHeader("anthropic-version"))
        assertEquals("${deepseekUpstream.hostName}:${deepseekUpstream.port}", seen.getHeader("Host"))
        assertEquals("/anthropic/v1/messages", seen.path)
    }

    @Test fun injectsAnthropicKey() {
        startGateway()
        anthropicUpstream.enqueue(MockResponse().setBody("{}"))
        postJson("/anthropic/v1/messages", """{"model":"claude-sonnet-5-5"}""", headers = mapOf("x-api-key" to "dshm-proxy")).close()
        assertEquals("real-an-key", take(anthropicUpstream).getHeader("x-api-key"))
        assertEquals(0, deepseekUpstream.requestCount)
    }

    @Test fun stripsDshUploadFieldsFromBody() {
        startGateway()
        deepseekUpstream.enqueue(MockResponse().setBody("{}"))
        val original = """{"model":"deepseek-flash","max_tokens":5,"stream":false,
            "messages":[{"role":"user","content":"hi"}],
            "dsh_session_log":[{"secret":"log"}],"dsh_plugin_packages":{"a":"b"}}"""
        postJson("/deepseek/v1/messages", original).close()
        val sent = json.parseToJsonElement(take(deepseekUpstream).body.readUtf8()) as JsonObject
        val expected = json.parseToJsonElement(original) as JsonObject
        assertEquals(expected.keys - setOf("dsh_session_log", "dsh_plugin_packages"), sent.keys)
        for (k in sent.keys) assertEquals(expected[k], sent[k])
    }

    @Test fun nonJsonBodyIsForwardedUntouched() {
        startGateway()
        anthropicUpstream.enqueue(MockResponse().setBody("{}"))
        val request = Request.Builder().url(url("/anthropic/v1/files"))
            .post("--b\r\nbinary dsh_session_log\r\n--b--".toRequestBody("multipart/form-data; boundary=b".toMediaType()))
            .build()
        client.newCall(request).execute().close()
        assertEquals("--b\r\nbinary dsh_session_log\r\n--b--", take(anthropicUpstream).body.readUtf8())
    }

    @Test fun preservesPathAndQuery() {
        startGateway()
        anthropicUpstream.enqueue(MockResponse().setBody("{}"))
        postJson("/anthropic/v1/messages?beta=true", """{"model":"claude-sonnet-5-5"}""").close()
        assertEquals("/v1/messages?beta=true", take(anthropicUpstream).path)
    }

    @Test fun getRequestsAreForwardedWithoutBody() {
        startGateway()
        deepseekUpstream.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val response = client.newCall(Request.Builder().url(url("/deepseek/v1/models")).build()).execute()
        assertEquals("""{"data":[]}""", response.text())
        val seen = take(deepseekUpstream)
        assertEquals("GET", seen.method)
        assertEquals("/anthropic/v1/models", seen.path)
    }

    @Test fun upstreamStatusAndBodyArePassedThrough() {
        startGateway()
        deepseekUpstream.enqueue(MockResponse().setResponseCode(400).setHeader("x-request-id", "r1").setBody("""{"error":"bad"}"""))
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").use {
            assertEquals(400, it.code)
            assertEquals("r1", it.header("x-request-id"))
            assertEquals("""{"error":"bad"}""", it.text())
        }
    }

    @Test fun pathTraversalIsRejected() {
        startGateway()
        // OkHttp would normalise dot segments client-side, so send the raw request line ourselves.
        val segments = listOf(
            "..", "%2e%2e", "%2E%2E", ".",
            "..\\v1", "x\\..\\..\\v1", "..%5cv1", "x%5C..%5C..%5Cv1", "%2e%2e\\v1", "ok\\file",
        )
        for (segment in segments) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().write(
                    "GET /${token.value}/deepseek/$segment/secret HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                val statusLine = socket.getInputStream().bufferedReader().readLine()
                assertTrue("$segment: $statusLine", statusLine.contains(" 404 "))
            }
        }
        assertEquals(0, deepseekUpstream.requestCount)
    }

    @Test fun wrongTokenIs403AndNeverCallsUpstream() {
        startGateway()
        for (path in listOf("/deepseek/v1/messages", "/anthropic/v1/messages", "/notify", "/nothing")) {
            postJson(path, "{}", tokenValue = "0".repeat(64)).use { assertEquals(path, 403, it.code) }
        }
        val noToken = client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute()
        assertEquals(403, noToken.code)
        noToken.close()
        assertEquals(0, deepseekUpstream.requestCount)
        assertEquals(0, anthropicUpstream.requestCount)
        assertTrue(notifications.isEmpty())
    }

    @Test fun unknownPathIs404() {
        startGateway()
        postJson("/search/v1/messages", "{}").use { assertEquals(404, it.code) }
    }

    @Test fun streamsSseChunkByChunk() {
        startGateway()
        val chunk = padded(sseEvent(messageStart), 200)
        // Warm up Ktor/OkHttp classes so the timing below measures streaming, not class loading.
        deepseekUpstream.enqueue(MockResponse().setBody("{}"))
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").close()
        take(deepseekUpstream)

        deepseekUpstream.enqueue(sseResponse(chunk.repeat(3)).throttleBody(200, 200, TimeUnit.MILLISECONDS))
        val start = System.nanoTime()
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash","stream":true}""").use { response ->
            val source = response.body!!.source()
            val buffer = ByteArray(200)
            assertTrue(source.read(buffer) > 0)
            val firstMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("first chunk took ${firstMs}ms", firstMs < 150)
            source.readByteArray() // rest of the stream
            val totalMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("whole stream took only ${totalMs}ms, so upstream was not throttled", totalMs >= 350)
        }
    }

    @Test fun countsUsageFromFinalSseChunk() {
        startGateway()
        deepseekUpstream.enqueue(
            sseResponse(
                "event: message_start\n" + sseEvent(messageStart) +
                    "event: content_block_delta\n" + sseEvent("""{"type":"content_block_delta","delta":{"text":"hi"}}""") +
                    "event: message_delta\n" + sseEvent(messageDelta) +
                    "event: message_stop\n" + sseEvent("""{"type":"message_stop"}"""),
            ),
        )
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash","stream":true}""").use { it.text() }
        awaitTrue { meter.todayUsd() > 0 }
        Thread.sleep(300)
        assertEquals(expectedStreamCost, meter.todayUsd(), 1e-12)
    }

    @Test fun usageSplitAcrossNetworkChunksAndCrlfIsStillCounted() {
        startGateway()
        val body = "event: message_start\r\n" + "data: $messageStart\r\n\r\n" + "data: $messageDelta\r\n\r\n"
        deepseekUpstream.enqueue(sseResponse(body).throttleBody(7, 5, TimeUnit.MILLISECONDS))
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash","stream":true}""").use { it.text() }
        awaitTrue { meter.todayUsd() > 0 }
        Thread.sleep(300)
        assertEquals(expectedStreamCost, meter.todayUsd(), 1e-12)
    }

    @Test fun countsUsageFromNonStreamingJson() {
        startGateway()
        anthropicUpstream.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"content":[],"usage":{"input_tokens":1000000,"output_tokens":0}}"""),
        )
        postJson("/anthropic/v1/messages", """{"model":"claude-sonnet-5-5"}""").use { it.text() }
        awaitTrue { meter.todayUsd() > 0 }
        assertEquals(2.0, meter.todayUsd(), 1e-9)
    }

    @Test fun countsPartialUsageWhenStreamAborts() {
        startGateway()
        val body = padded(sseEvent(messageStart), 1000) + padded("", 1000)
        deepseekUpstream.enqueue(sseResponse(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        runCatching { postJson("/deepseek/v1/messages", """{"model":"deepseek-flash","stream":true}""").use { it.text() } }
        val partial = PriceTable().costUsd(Provider.DEEPSEEK, "deepseek-flash", Usage(inputTokens = 100, outputTokens = 1), clock.instant())
        awaitTrue { meter.todayUsd() > 0 }
        Thread.sleep(300)
        assertEquals(partial, meter.todayUsd(), 1e-12)
    }

    @Test fun countsUsageOnceWhenClientCancels() {
        startGateway()
        val first = padded(sseEvent(messageStart), 200)
        deepseekUpstream.enqueue(sseResponse(first.repeat(10)).throttleBody(200, 100, TimeUnit.MILLISECONDS))
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash","stream":true}""").use { response ->
            assertTrue(response.body!!.source().read(ByteArray(200)) > 0)
        } // closing mid-stream drops the connection
        val partial = PriceTable().costUsd(Provider.DEEPSEEK, "deepseek-flash", Usage(inputTokens = 100, outputTokens = 1), clock.instant())
        awaitTrue { meter.todayUsd() > 0 }
        Thread.sleep(500)
        assertEquals(partial, meter.todayUsd(), 1e-12)
    }

    @Test fun requestsWithoutUsageAreNotRecorded() {
        startGateway()
        deepseekUpstream.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"files":[]}"""))
        postJson("/deepseek/v1/files", "{}").use { it.text() }
        Thread.sleep(200)
        assertEquals(0.0, meter.todayUsd(), 0.0)
    }

    @Test fun dailyCapReturns429WhenEnabled() {
        startGateway()
        meter.hardCapUsd = 0.0
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").use {
            assertEquals(429, it.code)
            assertEquals("""{"error":"dshm_daily_cap"}""", it.text())
        }
        assertEquals(0, deepseekUpstream.requestCount)
    }

    @Test fun rateGuardReturns429AndNotifiesOnce() {
        startGateway(rateGuard = RateGuard(maxPerMinute = 2, clock = Clock.systemUTC()))
        repeat(2) { deepseekUpstream.enqueue(MockResponse().setBody("{}")) }
        repeat(2) { postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").use { r -> assertEquals(200, r.code) } }
        repeat(3) {
            postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").use { r ->
                assertEquals(429, r.code)
                assertEquals("""{"error":"dshm_rate_guard"}""", r.text())
            }
        }
        assertEquals(2, deepseekUpstream.requestCount)
        assertEquals(listOf(Provider.DEEPSEEK), rateGuards.toList())
    }

    @Test fun rateGuardNotifiesAgainAfterTenMinutes() {
        val shared = MutableClock()
        startGateway(rateGuard = RateGuard(maxPerMinute = 1, clock = shared), gatewayClock = shared)
        repeat(3) { deepseekUpstream.enqueue(MockResponse().setBody("{}")) }
        val status = { postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").use { it.code } }
        assertEquals(200, status())
        assertEquals(429, status())
        assertEquals(1, rateGuards.size)
        shared.advance(Duration.ofMinutes(9))
        assertEquals(200, status())
        assertEquals(429, status())
        assertEquals("still inside the 10 minute quiet period", 1, rateGuards.size)
        shared.advance(Duration.ofMinutes(2))
        assertEquals(200, status())
        assertEquals(429, status())
        assertEquals(2, rateGuards.size)
    }

    @Test fun notifyRouteInvokesCallback() {
        startGateway()
        postJson("/notify", """{"title":"t","body":"b","sessionId":"s1"}""").use {
            assertEquals(200, it.code)
            assertEquals("""{"ok":true}""", it.text())
        }
        postJson("/notify", """{"title":"t2","body":"b2"}""").close()
        assertEquals(listOf(NotifyRequest("t", "b", "s1"), NotifyRequest("t2", "b2", null)), notifications.toList())
        assertEquals(0, deepseekUpstream.requestCount)
    }

    @Test fun notifyRouteRejectsBrokenJson() {
        startGateway()
        for (bad in listOf("{broken", "[]", """{"title":"only"}""", """{"title":1,"body":"b"}""", "")) {
            postJson("/notify", bad).use { assertEquals(bad, 400, it.code) }
        }
        assertTrue(notifications.isEmpty())
    }

    @Test fun missingKeyReturns401WithReadableError() {
        startGateway(vault = FakeVault(mapOf(Provider.DEEPSEEK to "real-ds-key")))
        postJson("/anthropic/v1/messages", """{"model":"claude-sonnet-5-5"}""").use {
            assertEquals(401, it.code)
            assertEquals("""{"error":"dshm_missing_key","provider":"ANTHROPIC"}""", it.text())
        }
        assertEquals(0, anthropicUpstream.requestCount)
    }

    @Test fun malformedKeyIsRejectedWithoutEchoingIt() {
        startGateway(vault = FakeVault(mapOf(Provider.DEEPSEEK to "bad\nkey-SECRET")))
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").use {
            assertEquals(401, it.code)
            val text = it.text()
            assertEquals("""{"error":"dshm_invalid_key","provider":"DEEPSEEK"}""", text)
        }
        assertEquals(0, deepseekUpstream.requestCount)
    }

    @Test fun unreachableUpstreamIs502WithoutLeakingDetails() {
        startGateway()
        deepseekUpstream.shutdown()
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash"}""").use {
            assertEquals(502, it.code)
            val text = it.text()
            assertEquals("""{"error":"dshm_upstream_unreachable"}""", text)
        }
    }

    @Test fun costAlertFiresOnceWhenCrossingThreshold() {
        startGateway()
        meter.alertUsd = 0.00001
        val body = sseEvent(messageStart) + sseEvent(messageDelta)
        repeat(2) { deepseekUpstream.enqueue(sseResponse(body)) }
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash","stream":true}""").use { it.text() }
        awaitTrue { costAlerts.isNotEmpty() }
        assertEquals(expectedStreamCost, costAlerts.single(), 1e-12)
        postJson("/deepseek/v1/messages", """{"model":"deepseek-flash","stream":true}""").use { it.text() }
        awaitTrue { meter.todayUsd() > expectedStreamCost * 1.5 }
        Thread.sleep(300)
        assertEquals(1, costAlerts.size)
    }

    @Test fun bindsLoopbackOnly() {
        startGateway()
        assertTrue(port > 0)
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
        // 127.0.0.2 is also loopback on Linux, so a wildcard bind would accept it.
        val other = mutableListOf("127.0.0.2")
        NetworkInterface.getNetworkInterfaces().asSequence().filter { !it.isLoopback && it.isUp }
            .flatMap { it.inetAddresses.asSequence() }.filter { it.address.size == 4 }
            .forEach { other += it.hostAddress!! }
        for (host in other) {
            val refused = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1_000) } }.exceptionOrNull()
            assertTrue("$host should refuse connections but got $refused", refused is ConnectException)
        }
    }

    @Test fun stopClosesThePort() {
        val g = startGateway()
        g.stop()
        assertTrue(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) } }.isFailure)
        gateway = null
    }

    @Test fun upstreamDispatcherAllowsSixteenConcurrentSubagentsAndMore() {
        val g = startGateway()
        assertEquals(64, g.upstreamDispatcher.maxRequests)
        assertEquals(32, g.upstreamDispatcher.maxRequestsPerHost)
    }
}
