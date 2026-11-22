package app.agency.core.llm

import app.agency.core.llm.LlmTestKit.Companion.json
import app.agency.core.llm.LlmTestKit.Companion.user
import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class AnthropicClientTest {
    private val kit = LlmTestKit()

    @After fun tearDown() = kit.close()

    private val schema = buildJsonObject {
        put("type", "object"); put("properties", buildJsonObject { put("kind", buildJsonObject { put("type", "string") }) })
        put("required", JsonArray(listOf(JsonPrimitive("kind")))); put("additionalProperties", false)
    }
    private val tool = ToolSpec("get_today", "今天的紀錄", buildJsonObject {
        put("type", "object"); put("properties", JsonObject(emptyMap())); put("required", JsonArray(emptyList())); put("additionalProperties", false)
    })

    private fun message(content: String, stop: String = "end_turn", model: String = "claude-sonnet-5-5") =
        """{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":$content,"stop_reason":"$stop",
           "usage":{"input_tokens":50,"output_tokens":10,"cache_read_input_tokens":5,"cache_creation_input_tokens":2}}"""

    @Test fun structuredOutputRequestShape() = runBlocking {
        kit.enqueue(200, message("""[{"type":"text","text":"{\"kind\":\"meal\"}"}]"""))
        val r = kit.claude().chat(user.copy(output = Output.Json(schema)))
        assertEquals("{\"kind\":\"meal\"}", (r as ChatResult.Ok).text)
        val req = kit.take()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-test-key", req.getHeader("x-api-key"))
        assertEquals(AnthropicClient.API_VERSION, req.getHeader("anthropic-version"))
        assertNull(req.getHeader("Authorization"))
        val body = json(req)
        val config = body["output_config"]!!.jsonObject
        assertEquals(buildJsonObject { put("type", "json_schema"); put("schema", schema) }, config["format"])
        assertEquals("low", config["effort"]!!.jsonPrimitive.content)
        for (k in listOf("temperature", "top_p", "top_k", "thinking", "tools", "tool_choice")) assertFalse(k, body.containsKey(k))
        val system = body["system"]!!.jsonArray[0].jsonObject
        assertEquals("回 json", system["text"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"ephemeral"}""", system["cache_control"].toString())
        assertEquals("""[{"role":"user","content":[{"type":"text","text":"我吃了雞胸肉"}]}]""", body["messages"].toString())
    }

    @Test fun toolsAreStrictWithAutoChoice() = runBlocking {
        kit.enqueue(200, message("""[{"type":"text","text":"好"}]"""))
        kit.claude().chat(user.copy(tools = listOf(tool), purpose = Purpose.CHAT))
        val body = json(kit.take())
        val t = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals(JsonPrimitive(true), t["strict"])
        assertEquals(tool.parameters, t["input_schema"])
        assertEquals("""{"type":"auto"}""", body["tool_choice"].toString())
        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("temperature"))
    }

    @Test fun serverSideFallbackOnlyWhereTheModelHasOne() = runBlocking {
        kit.enqueue(200, message("""[{"type":"text","text":"好"}]"""))
        kit.claude("claude-sonnet-5-5").chat(user)
        val sonnet = kit.take()
        assertEquals(AnthropicClient.FALLBACK_BETA, sonnet.getHeader("anthropic-beta"))
        assertEquals(JsonPrimitive("default"), json(sonnet)["fallbacks"])
        kit.enqueue(200, message("""[{"type":"text","text":"好"}]""", model = "claude-haiku-5-5"))
        kit.claude("claude-haiku-5-5").chat(user)
        val haiku = kit.take()
        assertNull(haiku.getHeader("anthropic-beta"))
        assertFalse(json(haiku).containsKey("fallbacks"))
    }

    @Test fun refusalIsRefused() = runBlocking {
        kit.enqueue(200, message("[]", stop = "refusal"))
        assertEquals(ChatResult.Failed(Fail.REFUSED), kit.claude().chat(user))
    }

    @Test fun aToolLoopEchoesTheWholeContentAndGroupsResults() = runBlocking {
        val content = """[{"type":"thinking","thinking":"","signature":"sig-1"},{"type":"text","text":"我查一下。"},
            {"type":"tool_use","id":"toolu_1","name":"get_today","input":{}},{"type":"tool_use","id":"toolu_2","name":"get_today","input":{}}]"""
        kit.enqueue(200, message(content, stop = "tool_use"))
        kit.enqueue(200, message("""[{"type":"text","text":"還剩 600 大卡。"}]"""))
        val client = kit.claude()
        val chat = ChatRequest("s", listOf(Turn.User("今天")), Purpose.CHAT, 800, tools = listOf(tool), thinking = Thinking.LOW)
        val r1 = client.chat(chat) as ChatResult.Ok
        assertEquals(listOf("toolu_1", "toolu_2"), r1.toolCalls.map { it.id })
        assertEquals("我查一下。", r1.text)
        kit.take()
        client.chat(chat.copy(turns = chat.turns + r1.assistant + Turn.ToolResult("toolu_2", "{}") +
            Turn.ToolResult("toolu_1", """{"error":"FAILED"}""", isError = true)))
        val messages = json(kit.take())["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals(Json.parseToJsonElement(content), messages[1].jsonObject["content"])
        val results = messages[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("toolu_2", "toolu_1"), results.map { it["tool_use_id"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive(true), results[1]["is_error"])
    }

    @Test fun usageIsRecordedOnTheModelThatAnswered() = runBlocking {
        kit.enqueue(200, message("""[{"type":"text","text":"好"}]""", model = "claude-sonnet-5"))
        val r = kit.claude().chat(user) as ChatResult.Ok
        val u = Usage(inputTokens = 50, outputTokens = 10, cacheReadTokens = 5, cacheWriteTokens = 2)
        assertEquals(u, r.usage)
        assertEquals(listOf(Triple(Provider.ANTHROPIC, "claude-sonnet-5", u)), kit.recorded)
    }

    @Test fun aServerSideFallbackBooksEveryAttemptOnItsOwnModel() = runBlocking {
        // usage.iterations is the per-attempt record; top-level usage covers only the attempt that answered.
        // The shape of the live docs' sample, with invented numbers.
        kit.enqueue(200, """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
            "content":[{"type":"fallback","from":{"model":"claude-sonnet-5-5"},"to":{"model":"claude-sonnet-5"},
            "trigger":{"type":"refusal","category":null}},{"type":"text","text":"好"}],
            "stop_reason":"end_turn","stop_details":null,"usage":{"input_tokens":50,"output_tokens":10,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0,
            "iterations":[{"type":"message","model":"claude-sonnet-5-5","input_tokens":48,"output_tokens":7,"cache_read_input_tokens":3,
            "cache_creation_input_tokens":0},
            {"type":"fallback_message","model":"claude-sonnet-5","input_tokens":50,"output_tokens":10,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0}]}}""")
        val r = kit.claude("claude-sonnet-5-5").chat(user) as ChatResult.Ok
        assertEquals("好", r.text)
        assertEquals(
            listOf(
                Triple(Provider.ANTHROPIC, "claude-sonnet-5-5", Usage(inputTokens = 48, outputTokens = 7, cacheReadTokens = 3)),
                Triple(Provider.ANTHROPIC, "claude-sonnet-5", Usage(inputTokens = 50, outputTokens = 10)),
            ),
            kit.recorded,
        )
    }

    @Test fun aLaterHopThatDeclinesIsBookedOnTheModelItNames() = runBlocking {
        // Two models decline in turn and a third answers: each `message` entry names its own model.
        kit.enqueue(200, """{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-4-8",
            "content":[{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-5"},
            "trigger":{"type":"refusal","category":"cyber"}},{"type":"fallback","from":{"model":"claude-opus-5"},
            "to":{"model":"claude-opus-4-8"},"trigger":{"type":"refusal","category":"cyber"}},{"type":"text","text":"好"}],
            "stop_reason":"end_turn","stop_details":null,"usage":{"input_tokens":30,"output_tokens":9,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0,
            "iterations":[{"type":"message","model":"claude-opus-5-5","input_tokens":31,"output_tokens":0,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0},
            {"type":"message","model":"claude-opus-5","input_tokens":32,"output_tokens":0,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0},
            {"type":"fallback_message","model":"claude-opus-4-8","input_tokens":30,"output_tokens":9,"cache_read_input_tokens":0,
            "cache_creation_input_tokens":0}]}}""")
        assertTrue(kit.claude("claude-opus-5-5").chat(user) is ChatResult.Ok)
        assertEquals(
            listOf(
                Triple(Provider.ANTHROPIC, "claude-opus-5-5", Usage(inputTokens = 31, outputTokens = 0)),
                Triple(Provider.ANTHROPIC, "claude-opus-5", Usage(inputTokens = 32, outputTokens = 0)),
                Triple(Provider.ANTHROPIC, "claude-opus-4-8", Usage(inputTokens = 30, outputTokens = 9)),
            ),
            kit.recorded,
        )
    }

    @Test fun emptyTextIsABadResponse() = runBlocking {
        kit.enqueue(200, message("""[{"type":"text","text":" "}]""", stop = "max_tokens"))
        assertEquals(ChatResult.Failed(Fail.BAD_RESPONSE), kit.claude().chat(user))
    }

    @Test fun overloadedIsRetriedOnceThenServer() = runBlocking {
        kit.enqueue(529)
        kit.enqueue(529)
        assertEquals(ChatResult.Failed(Fail.SERVER), kit.claude().chat(user))
        assertEquals(2, kit.server.requestCount)
        kit.enqueue(403)
        assertEquals(ChatResult.Failed(Fail.KEY_REJECTED), kit.claude().chat(user))
        assertEquals(3, kit.server.requestCount)
    }

    @Test fun anotherProvidersTurnIsRebuiltAsBlocks() = runBlocking {
        kit.enqueue(200, message("""[{"type":"text","text":"好"}]"""))
        val foreign = Turn.Assistant(Wire.OPENAI, buildJsonObject { put("reasoning_content", "不該送出") }, "我查一下",
            listOf(ToolCall("c1", "get_today", "{}")))
        kit.claude().chat(ChatRequest("s", listOf(Turn.User("今天"), foreign, Turn.ToolResult("c1", "{}")), Purpose.CHAT, 100))
        val body = kit.take().body.readUtf8()
        assertFalse(body.contains("不該送出"))
        assertTrue(body.contains(""""type":"tool_use","id":"c1","name":"get_today","input":{}"""))
    }
}
