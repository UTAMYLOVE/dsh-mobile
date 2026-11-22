package app.agency.core.llm

import app.agency.core.llm.LlmTestKit.Companion.completion
import app.agency.core.llm.LlmTestKit.Companion.json
import app.agency.core.llm.LlmTestKit.Companion.user
import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatClientTest {
    private val kit = LlmTestKit()

    @After fun tearDown() = kit.close()

    private val schema = buildJsonObject { put("type", "object") }
    private val getToday = ToolSpec("get_today", "今天的紀錄", buildJsonObject {
        put("type", "object"); put("properties", JsonObject(emptyMap())); put("required", kotlinx.serialization.json.JsonArray(emptyList()))
        put("additionalProperties", false)
    })

    @Test fun jsonModeIntakeRequestShape() = runBlocking {
        kit.enqueue(200, completion("""{"role":"assistant","content":"{\"kind\":\"meal\"}"}"""))
        val r = kit.deepseek().chat(user.copy(output = Output.Json(schema)))
        assertTrue(r is ChatResult.Ok)
        val req = kit.take()
        assertEquals("/chat/completions", req.path)
        assertEquals("Bearer sk-test-key", req.getHeader("Authorization"))
        val body = json(req)
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"disabled"}""", body["thinking"].toString())
        assertEquals("""{"type":"json_object"}""", body["response_format"].toString())
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(400, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("reasoning_effort"))
        val messages = body["messages"]!!.jsonArray
        assertEquals("""{"role":"system","content":"回 json"}""", messages[0].toString())
        assertEquals("""{"role":"user","content":"我吃了雞胸肉"}""", messages[1].toString())
    }

    @Test fun wordingUsesTheHigherTemperature() = runBlocking {
        kit.enqueue(200, completion("""{"role":"assistant","content":"好"}"""))
        kit.deepseek().chat(user.copy(purpose = Purpose.UTTERANCE))
        val body = json(kit.take())
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertFalse(body.containsKey("response_format"))
    }

    @Test fun aToolLoopTurnEchoesReasoningAndToolCallsUnchanged() = runBlocking {
        val first = """{"role":"assistant","content":"","reasoning_content":"先看今天的紀錄。",
            "tool_calls":[{"id":"call_00_a1","type":"function","function":{"name":"get_today","arguments":"{}"},"index":0}]}"""
        kit.enqueue(200, completion(first, finish = "tool_calls"))
        kit.enqueue(200, completion("""{"role":"assistant","content":"今天還剩 600 大卡。","reasoning_content":"算好了。"}"""))
        val client = kit.deepseek()
        val chat = ChatRequest("系統", listOf(Turn.User("今天還能吃多少")), Purpose.CHAT, 800, tools = listOf(getToday), thinking = Thinking.LOW)

        val r1 = client.chat(chat) as ChatResult.Ok
        assertEquals(listOf(ToolCall("call_00_a1", "get_today", "{}")), r1.toolCalls)
        assertNull(r1.text)
        val req1 = kit.take()
        assertEquals("/beta/chat/completions", req1.path)
        val body1 = json(req1)
        assertEquals("""{"type":"enabled"}""", body1["thinking"].toString())
        assertEquals("low", body1["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body1.containsKey("temperature"))
        val fn = body1["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals(JsonPrimitive(true), fn["strict"])
        assertEquals(getToday.parameters, fn["parameters"])

        val r2 = client.chat(chat.copy(turns = chat.turns + r1.assistant + Turn.ToolResult("call_00_a1", """{"kcal_left":600}""")))
        assertEquals("今天還剩 600 大卡。", (r2 as ChatResult.Ok).text)
        val messages = json(kit.take())["messages"]!!.jsonArray
        assertEquals(Json.parseToJsonElement(first), messages[2])
        assertEquals("""{"role":"tool","tool_call_id":"call_00_a1","content":"{\"kcal_left\":600}"}""", messages[3].toString())
    }

    @Test fun missingToolResultsAreRepairedAndStrayOnesDropped() = runBlocking {
        kit.enqueue(200, completion("""{"role":"assistant","content":"好"}"""))
        val calls = listOf(ToolCall("a", "get_today", "{}"), ToolCall("b", "get_today", "{}"))
        val assistant = Turn.Assistant(Wire.OPENAI, buildJsonObject { put("role", "assistant"); put("content", "") }, null, calls)
        val turns = listOf(Turn.ToolResult("stray", "{}"), Turn.User("嗨"), assistant, Turn.ToolResult("b", "{}"), Turn.ToolResult("b", "{}"))
        kit.deepseek().chat(ChatRequest("s", turns, Purpose.CHAT, 100))
        val messages = json(kit.take())["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("b", "a"), messages.drop(3).map { it["tool_call_id"]!!.jsonPrimitive.content })
        assertEquals(ToolPairing.MISSING_RESULT, messages[4]["content"]!!.jsonPrimitive.content)
    }

    @Test fun inlineToolCallsAreParsedAndEchoedAsRealToolCalls() = runBlocking {
        val inline = "我查一下。<tool_call>{\"name\":\"get_today\",\"arguments\":{}}</tool_call>" +
            "<tool_call>{\"name\":\"delete_everything\",\"arguments\":{}}</tool_call>"
        kit.enqueue(200, completion(buildJsonObject {
            put("role", "assistant"); put("content", inline); put("reasoning_content", "想一下")
        }.toString()))
        val r = kit.deepseek().chat(ChatRequest("s", listOf(Turn.User("今天")), Purpose.CHAT, 100, tools = listOf(getToday))) as ChatResult.Ok
        assertEquals(listOf(ToolCall("inline_1", "get_today", "{}")), r.toolCalls)
        assertEquals("我查一下。", r.text)
        val raw = r.assistant.raw.jsonObject
        assertEquals("想一下", raw["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("inline_1", raw["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun usageComesFromTheCacheHitAndMissFields() = runBlocking {
        kit.enqueue(200, completion("""{"role":"assistant","content":"好"}"""))
        val r = kit.deepseek().chat(user) as ChatResult.Ok
        val expected = Usage(inputTokens = 70, outputTokens = 20, cacheReadTokens = 30)
        assertEquals(expected, r.usage)
        assertEquals(listOf(Triple(Provider.DEEPSEEK, "deepseek-flash", expected)), kit.recorded)
    }

    @Test fun anEmptyJsonReplyIsABadResponse() = runBlocking {
        kit.enqueue(200, completion("""{"role":"assistant","content":"   "}"""))
        assertEquals(ChatResult.Failed(Fail.BAD_RESPONSE), kit.deepseek().chat(user.copy(output = Output.Json(schema))))
    }

    @Test fun contentFilterIsRefused() = runBlocking {
        kit.enqueue(200, completion("""{"role":"assistant","content":""}""", finish = "content_filter"))
        assertEquals(ChatResult.Failed(Fail.REFUSED), kit.deepseek().chat(user))
    }

    @Test fun statusMapping401IsKeyRejectedAnd400IsNotRetried() = runBlocking {
        kit.enqueue(401)
        assertEquals(ChatResult.Failed(Fail.KEY_REJECTED), kit.deepseek().chat(user))
        kit.enqueue(400)
        kit.enqueue(200, completion("""{"role":"assistant","content":"不該到這裡"}"""))
        assertEquals(ChatResult.Failed(Fail.BAD_RESPONSE), kit.deepseek().chat(user))
        assertEquals(2, kit.server.requestCount)
        assertEquals(emptyList<Long>(), kit.sleeps)
    }

    @Test fun a429IsRetriedOnceAfterTwoSeconds() = runBlocking {
        kit.enqueue(429)
        kit.enqueue(200, completion("""{"role":"assistant","content":"好"}"""))
        assertTrue(kit.deepseek().chat(user) is ChatResult.Ok)
        assertEquals(listOf(2_000L), kit.sleeps)
        kit.enqueue(503)
        kit.enqueue(429)
        kit.enqueue(200, completion("""{"role":"assistant","content":"不該到這裡"}"""))
        assertEquals(ChatResult.Failed(Fail.RATE), kit.deepseek().chat(user))
        assertEquals(4, kit.server.requestCount)
    }

    @Test fun genericServiceSendsNoDeepseekFieldsAndIsNotMetered() = runBlocking {
        val ep = CompatEndpoint.generic(kit.base.newBuilder().addPathSegments("v1/chat/completions").build().toString(), "my-model",
            jsonMode = false, tools = false, allowHttp = true)!!
        kit.enqueue(200, completion("""{"role":"assistant","content":"好"}"""))
        kit.deepseek(ep).chat(user.copy(output = Output.Json(schema), tools = listOf(getToday)))
        val req = kit.take()
        assertEquals("/v1/chat/completions", req.path)
        val body = json(req)
        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("response_format"))
        assertFalse(body.containsKey("tools"))
        assertTrue(kit.recorded.isEmpty())
    }

    @Test fun genericServiceNeedsHttpsAndAModel() {
        assertNull(CompatEndpoint.generic("http://example.com/v1", "m", jsonMode = true, tools = true))
        assertNull(CompatEndpoint.generic("https://example.com/v1", " ", jsonMode = true, tools = true))
        assertEquals("https://example.com/v1/", CompatEndpoint.generic("https://example.com/v1/chat/completions?x=1", "m", true, true)!!.base.toString())
    }

    @Test fun genericDailyQuotaIsACap() = runBlocking {
        val ep = CompatEndpoint.generic(kit.base.toString(), "m", jsonMode = true, tools = true, allowHttp = true)
        val client = OpenAiCompatClient(kit.transport, { ep }, { kit.key }, kit.checks, kit.sink, quota = { false })
        assertEquals(ChatResult.Failed(Fail.CAP), client.chat(user))
        assertEquals(0, kit.server.requestCount)
    }

    @Test fun noKeyAndNoSetupFailBeforeAnyRequest() = runBlocking {
        kit.key = " "
        assertEquals(ChatResult.Failed(Fail.NO_KEY), kit.deepseek().chat(user))
        kit.key = "sk"
        assertEquals(ChatResult.Failed(Fail.NO_SETUP), kit.deepseek(endpoint = null).chat(user))
        assertEquals(0, kit.server.requestCount)
    }

    @Test fun theCallBudgetRefusesARunawayLoop() = runBlocking {
        kit.budget = app.agency.core.metering.CallBudget(maxPerMinute = 1, clock = kit.clock)
        kit.enqueue(200, completion("""{"role":"assistant","content":"好"}"""))
        assertTrue(kit.deepseek().chat(user) is ChatResult.Ok)
        assertEquals(ChatResult.Failed(Fail.RATE), kit.deepseek().chat(user))
        assertEquals(1, kit.server.requestCount)
    }
}
