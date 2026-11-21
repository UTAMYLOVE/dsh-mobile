package app.dshmobile.coach

import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCoachModelTest {
    private val server = MockWebServer().apply { start() }
    private val other = MockWebServer().apply { start() }
    private val prefs = InMemorySharedPreferences()
    private val settings = CoachSettings(prefs)
    private val clock = MutableClock()
    private val meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = clock)
    private val vault = FakeVault(mutableMapOf("DEEPSEEK" to "ds-secret-key-123", "ANTHROPIC" to "an-secret-key-456", CoachSettings.COMPAT_KEY to "cp-secret-key-789"))
    private val logDir = Files.createTempDirectory("coach-log").toFile()
    private val log = ErrorLog(logDir)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val model = HttpCoachModel(
        http, vault, settings, meter,
        { mapOf(Provider.DEEPSEEK to server.url("/anthropic"), Provider.ANTHROPIC to server.url("/")) }, log, clock,
    )

    @After fun tearDown() {
        server.shutdown(); other.shutdown(); logDir.deleteRecursively()
    }

    private val ok = """{"content":[{"type":"thinking","thinking":"..."},{"type":"text","text":"好"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":3}}"""

    private fun run(system: String = "系統", turns: List<Turn> = listOf(Turn.user("嗨"))) = runBlocking { model.complete(system, turns, 2048) }

    @Test fun deepseekSpeaksTheMessagesFormatWithItsKey() {
        server.enqueue(MockResponse().setBody(ok))
        assertEquals(ModelReply.Ok("好"), run())
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/anthropic/v1/messages", req.path)
        assertEquals("ds-secret-key-123", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val body = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("deepseek-flash", (body["model"] as JsonPrimitive).content)
        assertEquals("系統", (body["system"] as JsonPrimitive).content)
        assertTrue(body["messages"] is JsonArray)
        assertEquals("2048", (body["max_tokens"] as JsonPrimitive).content)
        assertNull(body["temperature"])
        assertNull(body["output_config"])
        assertTrue(meter.todayByModel().any { it.model == "deepseek-flash" })
    }

    @Test fun claudeUsesItsOwnKeyAndLowEffort() {
        settings.provider = CoachProvider.CLAUDE
        server.enqueue(MockResponse().setBody(ok))
        run()
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", req.path)
        assertEquals("an-secret-key-456", req.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("claude-sonnet-5-5", (body["model"] as JsonPrimitive).content)
        assertNull(body["thinking"])
        assertEquals("low", ((body["output_config"] as JsonObject)["effort"] as JsonPrimitive).content)
    }

    @Test fun aRefusalIsReported() {
        server.enqueue(MockResponse().setBody("""{"content":[],"stop_reason":"refusal"}"""))
        assertEquals(ModelReply.Failed(Fail.REFUSED), run())
    }

    @Test fun theCompatibleServiceSpeaksChatCompletions() {
        settings.provider = CoachProvider.COMPAT
        settings.compatModel = "some-model"
        // MockWebServer is plain http; the https rule is checked separately below.
        val httpsBase = server.url("/v1").toString()
        settings.compatUrl = httpsBase
        assertEquals(ModelReply.Failed(Fail.NO_SETUP), run()) // http:// is refused
        assertEquals(0, server.requestCount)
    }

    private fun compatModel() = HttpCoachModel(http, vault, settings, meter, { emptyMap() }, log, clock, requireHttps = false).also {
        settings.provider = CoachProvider.COMPAT
        settings.compatModel = "some-model"
        settings.compatUrl = server.url("/v1").toString()
    }

    @Test fun theCompatibleRequestShape() {
        val m = compatModel()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}"""))
        assertEquals(ModelReply.Ok("好"), runBlocking { m.complete("系統", listOf(Turn.user("嗨"), Turn.assistant("嗯"), Turn.user("再說")), 2048) })
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer cp-secret-key-789", req.getHeader("Authorization"))
        assertNull(req.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("some-model", (body["model"] as JsonPrimitive).content)
        val msgs = body["messages"] as JsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), msgs.map { ((it as JsonObject)["role"] as JsonPrimitive).content })
        assertEquals("系統", ((msgs[0] as JsonObject)["content"] as JsonPrimitive).content)
        assertEquals(1, prefs.getInt(CoachSettings.K_COMPAT_COUNT, 0))
    }

    @Test fun theCompatibleServiceNeverFollowsARedirectAndRejectsANullContent() {
        val m = compatModel()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal").toString()))
        assertEquals(ModelReply.Failed(Fail.BAD_RESPONSE), runBlocking { m.complete("系統", listOf(Turn.user("嗨")), 2048) })
        assertEquals(0, other.requestCount)
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":null},"finish_reason":"stop"}]}"""))
        assertEquals(ModelReply.Failed(Fail.BAD_RESPONSE), runBlocking { m.complete("系統", listOf(Turn.user("嗨")), 2048) })
    }

    @Test fun theCompatibleDailyCapStopsBeforeAnyRequest() {
        val m = compatModel()
        prefs.edit().putString(CoachSettings.K_COMPAT_COUNT_DAY, clock.instant().atZone(java.time.ZoneId.of("Asia/Taipei")).toLocalDate().toString())
            .putInt(CoachSettings.K_COMPAT_COUNT, COMPAT_DAILY_CAP).apply()
        assertEquals(ModelReply.Failed(Fail.CAP), runBlocking { m.complete("系統", listOf(Turn.user("嗨")), 2048) })
        assertEquals(0, server.requestCount)
    }

    @Test fun theKeyTravelsOnlyInItsHeaderNeverInTheBody() {
        server.enqueue(MockResponse().setBody(ok))
        run(system = "系統", turns = listOf(Turn.user("嗨")))
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertFalse(req.body.readUtf8().contains("ds-secret-key-123"))
        assertEquals("ds-secret-key-123", req.getHeader("x-api-key"))
    }

    @Test fun rejectedKeysRedirectsAndMissingKeys() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ModelReply.Failed(Fail.KEY_REJECTED), run())
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal").toString()))
        assertEquals(ModelReply.Failed(Fail.BAD_RESPONSE), run())
        assertEquals(0, other.requestCount)
        vault.clear(Provider.DEEPSEEK)
        val before = server.requestCount
        assertEquals(ModelReply.Failed(Fail.NO_KEY), run())
        assertEquals(before, server.requestCount)
    }

    @Test fun theDailyCapStopsBeforeAnyRequest() {
        meter.hardCapUsd = 0.0
        assertEquals(ModelReply.Failed(Fail.CAP), run())
        assertEquals(0, server.requestCount)
    }

    @Test fun theLogNeverHoldsAKey() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("ds-secret-key-123"))
        assertEquals(ModelReply.Failed(Fail.SERVER), run())
        val text = log.file().takeIf { it.isFile }?.readText().orEmpty()
        assertTrue(text.contains("coach"))
        assertFalse(text.contains("ds-secret-key-123"))
    }
}
