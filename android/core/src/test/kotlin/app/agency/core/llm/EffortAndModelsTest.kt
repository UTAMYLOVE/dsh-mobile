package app.agency.core.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortAndModelsTest {
    private val kit = LlmTestKit()

    @After fun tearDown() = kit.close()

    private val chat = ChatRequest("系統", listOf(Turn.User("今天")), Purpose.CHAT, 800, thinking = Thinking.LOW)

    @Test fun claudeSendsTheRequestedEffort() {
        for (e in Effort.entries) {
            val body = kit.claude().body(AnthropicClient.DEFAULT_MODEL, chat.copy(effort = e))
            assertEquals(e.wire, body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
        // Callers that never asked keep the old request.
        assertEquals("low", kit.claude().body(AnthropicClient.DEFAULT_MODEL, chat.copy(thinking = Thinking.OFF))["output_config"]!!
            .jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun deepseekSendsTheEffortOnlyWhenThinking() {
        val ep = CompatEndpoint.deepseek(base = kit.base)
        val thinking = kit.deepseek(ep).body(ep, chat.copy(effort = Effort.HIGH), withTools = false)
        assertEquals("""{"type":"enabled"}""", thinking["thinking"].toString())
        assertEquals("high", thinking["reasoning_effort"]!!.jsonPrimitive.content)
        val off = kit.deepseek(ep).body(ep, chat.copy(thinking = Thinking.OFF, effort = Effort.HIGH), withTools = false)
        assertFalse(off.containsKey("reasoning_effort"))
    }

    @Test fun aSyntheticAssistantTurnRoundTripsOnBothWires() = runBlocking {
        val calls = listOf(ToolCall("c1", "log_water", """{"bottles":1}"""))
        val turns = listOf(Turn.User("喝完一壺"), Turn.Assistant.synthetic(null, calls), Turn.ToolResult("c1", """{"ok":true}"""))
        val claude = kit.claude().body(AnthropicClient.DEFAULT_MODEL, chat.copy(turns = turns)).toString()
        assertTrue(claude.contains("\"tool_use\"") && claude.contains("\"tool_result\""))
        val ep = CompatEndpoint.deepseek(base = kit.base)
        val ds = kit.deepseek(ep).body(ep, chat.copy(turns = turns), withTools = false).toString()
        assertTrue(ds.contains("\"tool_calls\"") && ds.contains("\"tool_call_id\":\"c1\""))
    }

    @Test fun tiersUseOnlyKnownModelIds() {
        for (t in Tier.entries) {
            assertTrue(Models.chat(ProviderChoice.CLAUDE, t) in AnthropicClient.MODELS)
            assertTrue(Models.chat(ProviderChoice.DEEPSEEK, t) in CompatEndpoint.DEEPSEEK_MODELS)
            assertNull(Models.chat(ProviderChoice.COMPAT, t))
        }
        assertEquals("claude-haiku-5-5", Models.small(ProviderChoice.CLAUDE))
        assertEquals(CompatEndpoint.DEEPSEEK_FLASH, Models.small(ProviderChoice.DEEPSEEK))
        assertTrue(Models.seesImages(ProviderChoice.DEEPSEEK, CompatEndpoint.DEEPSEEK_FLASH))
        assertFalse(Models.seesImages(ProviderChoice.DEEPSEEK, CompatEndpoint.DEEPSEEK_PRO))
        assertTrue(CompatEndpoint.deepseek().vision)
        assertFalse(CompatEndpoint.deepseek(model = CompatEndpoint.DEEPSEEK_PRO).vision)
    }

    @Test fun scriptedLlmRecordsAndAnswersInOrder() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.calls("get_today"), ScriptedLlm.says("好"), ScriptedLlm.fails(Fail.OFFLINE))
        val r1 = llm.chat(chat) as ChatResult.Ok
        assertEquals("get_today", r1.toolCalls.single().name)
        assertEquals("好", (llm.chat(chat) as ChatResult.Ok).text)
        assertEquals(ChatResult.Failed(Fail.OFFLINE), llm.chat(chat))
        assertEquals(3, llm.requests.size)
        assertEquals(0, llm.remaining)
    }
}
