package app.dshmobile.chat

import app.dshmobile.gateway.PriceTable
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyStatsTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun e(type: String, seq: Long, time: Long, data: String = "{}") = """{"type":"$type","seq":$seq,"time":$time,"data":$data}"""
    private fun reply(seq: Long, time: Long, firstChunk: Long, input: Long, output: Long, cache: Long, text: String = "hi") = e(
        "assistant/message", seq, time,
        """{"message":{"role":"assistant","content":[{"type":"text","text":"$text"}]},
           "usage":{"inputTokens":$input,"outputTokens":$output,"cacheReadTokens":$cache,"totalTokens":0},
           "stream":[{"type":"chunk","time":$firstChunk,"chunk":{"type":"block-start","index":0,"blockType":"text"}}]}""",
    )
    private fun snap(vararg events: String) =
        j("""{"type":"snapshot","cursor":1,"records":[${events.joinToString(",") { """{"type":"event","event":$it}""" }}]}""")

    @Test fun aTwoStepTurnIsFiledUnderItsLastReply() {
        val s = ConversationReducer.reduce(
            ConversationState("s1"),
            snap(
                e("turn/start", 1, 1_000),
                e("step/start", 2, 1_100),
                e("request/context", 3, 1_150, """{"provider":"deepseek","model":"deepseek-v4-flash","contextWindow":1000000}"""),
                reply(4, 2_600, firstChunk = 1_600, input = 1_000, output = 100, cache = 500),
                e("step/start", 5, 3_000),
                reply(6, 4_000, firstChunk = 3_500, input = 200, output = 50, cache = 1_500),
                e("turn/end", 7, 4_100, """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
        )
        val r = s.replyStats.getValue("e6")
        assertEquals(1, s.replyStats.size)
        assertEquals("deepseek", r.provider)
        assertEquals(1_200L, r.inputTokens)
        assertEquals(150L, r.outputTokens)
        assertEquals(2_000L, r.cacheReadTokens)
        assertEquals(500L, r.ttftMs) // first step only: 1_600 - 1_100
        assertEquals(1_500L, r.decodeMs) // 1_000 + 500
        assertEquals(3_100L, r.durationMs)
        assertEquals(2, r.steps)
        assertEquals(100.0, r.tokensPerSecond!!, 0.01)
        assertEquals(ContextFill(1_700, 1_000_000), s.context)
        assertNull(s.turn)
    }

    @Test fun dshsContextPressureWinsOverTheEstimate() {
        val s = ConversationReducer.reduce(
            ConversationState("s1"),
            j(
                """{"type":"snapshot","cursor":1,"records":[],
                   "projections":{"values":{"contextPressure":{"pressureTokens":50000,"projectedTokens":51000,"contextWindow":200000}}}}""",
            ),
        )
        // projectedTokens (the next request's prompt) wins over pressureTokens (the last request's)
        assertEquals(51_000L, s.context!!.tokens)
        assertEquals(0.255f, s.context!!.fraction!!, 0.001f)
    }

    @Test fun withoutAProjectionDshsLastPromptSizeIsUsed() {
        val s = ConversationReducer.reduce(
            ConversationState("s1"),
            j("""{"type":"snapshot","cursor":1,"records":[],"projections":{"values":{"contextPressure":{"pressureTokens":50000,"contextWindow":200000}}}}"""),
        )
        assertEquals(ContextFill(50_000, 200_000), s.context)
    }

    @Test fun aRunningTurnHasNoStatsYet() {
        val s = ConversationReducer.reduce(
            ConversationState("s1"),
            snap(e("turn/start", 1, 1_000), e("step/start", 2, 1_000), reply(3, 2_000, 1_500, 10, 10, 0)),
        )
        assertTrue(s.replyStats.isEmpty())
        assertNotNull(s.turn)
    }

    @Test fun teamUsageSumsTheLeadAndEveryDescendant() {
        fun usage(input: Int) = mapOf(
            "tokenUsage" to j("""{"uncachedInputTokens":$input,"outputTokens":10,"cacheReadTokens":0,"cacheWriteTokens":0}"""),
            "sessionStats" to j("""{"turns":1,"steps":1,"llmMs":0,"toolMs":0,"ttftMs":100,"ttftSteps":1,"decodeMs":1000,"decodeTokens":10}"""),
        )
        fun sum(id: String, parent: String?) = app.dshmobile.remote.SessionSummary(
            id, parent, null, null, null, null, null, null, null, null, null,
        )
        val tree = SessionTreeState(
            summaries = listOf(sum("lead", null), sum("kid", "lead"), sum("grandkid", "kid"), sum("other", null)).associateBy { it.id },
            projections = mapOf("lead" to usage(100), "kid" to usage(20), "grandkid" to usage(3), "other" to usage(9_999)),
        )
        val u = teamUsage(tree, "lead")
        assertEquals(123L, u.inputTokens)
        assertEquals(30L, u.outputTokens)
        assertEquals(10.0, u.tokensPerSecond!!, 0.01)
        assertEquals(100L, u.averageTtftMs)
    }

    @Test fun theReplyLineReadsWell() {
        val r = ReplyStats("anthropic", "claude-sonnet-5-5", 1_200, 150, 2_000, 500, 1_500, 3_100, 2)
        val cost = replyCostUsd(PriceTable(), r, Instant.parse("2026-11-03T00:00:00Z"))
        assertNotNull(cost)
        val line = replyLine(r, cost)
        assertTrue(line, line.startsWith("100 tok/s · 首字 0.5 秒 · 入 3.2K（快取 2K） · 出 150 · US$"))
        assertTrue(line, line.endsWith("共 3.1 秒"))
        assertNull(replyCostUsd(PriceTable(), r.copy(provider = null), Instant.EPOCH))
    }

    /**
     * The conversation's 花費 (2026-11-15): dsh names DeepSeek's route `deepseek-official`, which was not recognised (no cost at
     * all for DeepSeek replies), and the cache writes, which Claude bills above the plain input price, were left out.
     */
    @Test fun aReplysCostCountsCacheWritesAndKnowsDsh_sProviderNames() {
        val s = ConversationReducer.reduce(
            ConversationState("s1"),
            snap(
                e("turn/start", 1, 1_000),
                e("request/context", 2, 1_050, """{"provider":"anthropic","model":"claude-opus-5-5","contextWindow":1000000}"""),
                e(
                    "assistant/message", 3, 2_000,
                    """{"message":{"role":"assistant","content":[{"type":"text","text":"hi"}]},
                       "usage":{"inputTokens":1000000,"outputTokens":0,"cacheReadTokens":0,"cacheWriteTokens":1000000}}""",
                ),
                e("turn/end", 4, 2_100, """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
        )
        val r = s.replyStats.getValue("e3")
        assertEquals(1_000_000L, r.cacheWriteTokens)
        assertEquals(ContextFill(2_000_000, 1_000_000), s.context)
        assertEquals(4.0 + 5.0, replyCostUsd(PriceTable(), r, Instant.EPOCH)!!, 1e-9)
        assertTrue(replyLine(r, null), replyLine(r, null).contains("入 2M"))
        val deepseek = ReplyStats("deepseek-official", "deepseek-flash", 1_000_000, 0, 0, null, 0, null, 1)
        assertNotNull("deepseek-official is DeepSeek", replyCostUsd(PriceTable(), deepseek, Instant.parse("2026-11-08T12:00:00Z")))
        assertEquals(app.dshmobile.gateway.Provider.DEEPSEEK, providerOf("deepseek-official"))
        assertEquals(app.dshmobile.gateway.Provider.ANTHROPIC, providerOf("anthropic"))
        assertNull(providerOf("openai"))
    }

    @Test fun shortFormats() {
        assertEquals("950", shortTokens(950))
        assertEquals("12.3K", shortTokens(12_340))
        assertEquals("1M", shortTokens(1_000_000))
        assertEquals("2 分 05 秒", shortDuration(125_000))
        assertEquals("US$0.0042", shortUsd(0.0042))
    }

    @Suppress("unused") private val keep = JsonObject(emptyMap())
}
