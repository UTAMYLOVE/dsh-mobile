package app.agency.core.llm

import app.agency.core.llm.LlmTestKit.Companion.completion
import app.agency.core.llm.LlmTestKit.Companion.json
import app.agency.core.structured.JsonSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner's choice 乙 at the wire (the shapes of the T04 clients, unchanged): a stored reply with its reasoning goes back to
 * DeepSeek as `{role, content, reasoning_content}` and the tools request keeps thinking on; Claude gets the same turn as
 * plain text (its own thinking blocks never leave their round); [Thoughts.of] reads both providers' thinking.
 */
class ThinkingReplayTest {
    private val kit = LlmTestKit()
    private val getToday = ToolSpec("get_today", "今天", JsonSchema.obj())

    @After fun close() = kit.close()

    @Test fun aReplayedReplyKeepsDeepSeekThinkingOnWithItsReasoning() = runBlocking {
        kit.enqueue(200, completion("""{"role":"assistant","content":"好","reasoning_content":"嗯"}"""))
        val turns = listOf(Turn.User("早安"), Thoughts.replay("早點睡。", "他昨天很晚睡"), Turn.User("今天還能吃多少"))
        kit.deepseek().chat(ChatRequest("s", turns, Purpose.CHAT, 800, tools = listOf(getToday), thinking = Thinking.LOW))
        val body = json(kit.take())
        assertEquals("""{"type":"enabled"}""", body["thinking"].toString())
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        val assistant = body["messages"]!!.jsonArray[2].jsonObject
        assertEquals(setOf("role", "content", "reasoning_content"), assistant.keys)
        assertEquals("他昨天很晚睡", assistant["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("早點睡。", assistant["content"]!!.jsonPrimitive.content)
    }

    @Test fun claudeGetsAReplayedReplyAsTextOnly() = runBlocking {
        kit.enqueue(200, """{"id":"m","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"好"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""")
        val turns = listOf(Turn.User("早安"), Thoughts.replay("早點睡。", "他昨天很晚睡"), Turn.User("在嗎"))
        kit.claude().chat(ChatRequest("s", turns, Purpose.CHAT, 1024, tools = listOf(getToday), thinking = Thinking.LOW))
        val sent = kit.take().body.readUtf8()
        assertFalse(sent.contains("他昨天很晚睡"))
        assertTrue(sent.contains("早點睡。"))
    }

    @Test fun thoughtsReadsDeepSeekReasoningAndClaudeBlocksAndNothingElse() {
        val ds = Turn.Assistant(Wire.OPENAI, Json.parseToJsonElement("""{"role":"assistant","content":"好","reasoning_content":"想"}"""), "好", emptyList())
        assertEquals(Wire.OPENAI to "想", Thoughts.of(ds))
        val off = Turn.Assistant(Wire.OPENAI, Json.parseToJsonElement("""{"role":"assistant","content":"好","reasoning_content":null}"""), "好", emptyList())
        assertNull(Thoughts.of(off))
        val claude = Turn.Assistant(
            Wire.ANTHROPIC,
            Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"s1"},{"type":"redacted_thinking","data":"x"},{"type":"text","text":"好"}]"""),
            "好", emptyList(),
        )
        val (wire, payload) = Thoughts.of(claude)!!
        assertEquals(Wire.ANTHROPIC, wire)
        val blocks = Json.parseToJsonElement(payload) as JsonArray
        assertEquals(listOf("thinking", "redacted_thinking"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertNull(Thoughts.of(Turn.Assistant(Wire.ANTHROPIC, Json.parseToJsonElement("""[{"type":"text","text":"好"}]"""), "好", emptyList())))
    }
}
