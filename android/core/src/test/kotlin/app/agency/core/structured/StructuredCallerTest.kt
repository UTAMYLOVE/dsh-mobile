package app.agency.core.structured

import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A model that answers from a script and records every request. */
class ScriptedLlm(vararg replies: ChatResult) : LlmClient {
    private val script = ArrayDeque(replies.toList())
    val requests = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResult {
        requests += request
        return script.removeFirstOrNull() ?: error("the script ran out: an unexpected call")
    }

    companion object {
        fun ok(text: String?): ChatResult = ChatResult.Ok(text, emptyList(), Turn.Assistant(Wire.OPENAI, JsonPrimitive(text ?: ""), text, emptyList()), null)
        fun failed(f: Fail): ChatResult = ChatResult.Failed(f)
    }
}

class StructuredCallerTest {
    private val good = """{"text":"現在去睡。","ask":null,"options":[],"recommended":null,"memory":[]}"""

    private fun caller(llm: LlmClient, choice: ProviderChoice = ProviderChoice.DEEPSEEK) = StructuredCaller(llm) { choice }

    private suspend fun StructuredCaller.utter(check: (Utterance) -> Checked<Utterance> = { Checked.Accept(it) }) =
        call(Utterance.SPEC, "系統", listOf(Turn.User("他的話")), Purpose.UTTERANCE, 400, check) { Utterance("模板") }

    @Test fun aValidReplyIsTakenOnTheFirstCallAsJsonWithThinkingOff() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.ok(good))
        val r = caller(llm).utter()
        assertEquals(Structured.Model(Utterance("現在去睡。"), 1), r)
        val req = llm.requests.single()
        assertTrue(req.output is Output.Json)
        assertEquals(Utterance.SCHEMA, (req.output as Output.Json).schema)
        assertEquals(Thinking.OFF, req.thinking)
        assertEquals(400, req.maxTokens)
    }

    @Test fun violationsAreSentBackAndItStopsAfterTwoRetriesWithTheFallback() = runBlocking {
        val bad = """{"text":"","ask":null,"options":["一","二","三","四","五"],"recommended":null,"memory":[]}"""
        val llm = ScriptedLlm(ScriptedLlm.ok(bad), ScriptedLlm.ok(bad), ScriptedLlm.ok(bad))
        val r = caller(llm).utter()
        assertEquals(3, llm.requests.size)
        assertTrue(r is Structured.Template)
        r as Structured.Template
        assertEquals(Utterance("模板"), r.value)
        assertNull(r.fail)
        assertEquals(3, r.attempts)
        // The second request: the original turn, the bad output echoed, then one user turn with every reason.
        val second = llm.requests[1].turns
        assertEquals(3, second.size)
        assertEquals(Turn.AssistantText(bad), second[1])
        val reasons = (second[2] as Turn.User).text
        assertTrue(reasons, reasons.startsWith("上一個輸出不合格："))
        assertTrue(reasons, reasons.contains("text：要有要說的話"))
        assertTrue(reasons, reasons.contains("options：最多 4 個選項"))
        assertTrue(reasons, reasons.endsWith("只修正這些地方，重新輸出 JSON。"))
        assertEquals(5, llm.requests[2].turns.size)
    }

    @Test fun aRetryThatFixesTheOutputIsUsed() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.ok("不是 JSON"), ScriptedLlm.ok(good))
        val r = caller(llm).utter()
        assertEquals(Structured.Model(Utterance("現在去睡。"), 2), r)
        assertTrue((llm.requests[1].turns.last() as Turn.User).text.contains("不是一個完整的 JSON 物件"))
    }

    @Test fun theCallersCheckDecidesAndItsReasonsGoBack() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.ok(good), ScriptedLlm.ok(good), ScriptedLlm.ok(good))
        val r = caller(llm).utter { Checked.Reject(listOf(Violation("text", "數字「300」不在資料裡"))) }
        assertTrue(r is Structured.Template)
        assertTrue((llm.requests[1].turns.last() as Turn.User).text.contains("text：數字「300」不在資料裡"))
    }

    @Test fun fencedJsonIsRepairedLocally() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.ok("好的，這是結果：\n```json\n{\"text\":\"吃完拍給我。\"}\n```\n"))
        val r = caller(llm).utter()
        assertEquals(Structured.Model(Utterance("吃完拍給我。"), 1), r)
    }

    @Test fun localRepairTakesTheOutermostObjectAndFillsOptionalNulls() {
        val o = LocalRepair.parse("前言 {\"text\":\"有 } 括號\",\"memory\":[{\"op\":\"add\"}]} 後記 {\"x\":1}", Utterance.DEFAULTS)!!
        assertEquals("有 } 括號", (o["text"] as JsonPrimitive).content)
        assertTrue(o.containsKey("ask") && o.containsKey("options") && o.containsKey("recommended"))
    }

    @Test fun aTruncatedReplyIsNeverTakenAsComplete() = runBlocking {
        // DeepSeek's finish_reason "length" comes back as Ok with the text cut off; a nested object inside is complete.
        val cut = """{"kind":"meal","items":[{"name":"雞胸","portion":1,"fried":false}],"weight_kg":null,"label":{"name":"雞"""
        assertNull(LocalRepair.objectText(cut))
        val llm = ScriptedLlm(ScriptedLlm.ok(cut), ScriptedLlm.ok(cut), ScriptedLlm.ok(cut))
        val r = StructuredCaller(llm) { ProviderChoice.DEEPSEEK }
            .call(Intake.spec("雞胸"), Intake.SYSTEM, listOf(Turn.User("雞胸")), Purpose.INTAKE, 600) { null }
        assertEquals(Structured.Template<Intake>(null, 3, null, listOf(StructuredCaller.NOT_JSON)), r)
    }

    @Test fun anEmptyReplyCountsAsOneFailedAttempt() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.failed(Fail.BAD_RESPONSE), ScriptedLlm.ok("  "), ScriptedLlm.ok(good))
        val r = caller(llm).utter()
        assertEquals(Structured.Model(Utterance("現在去睡。"), 3), r)
        // No output to echo: just the reason.
        assertEquals(listOf(Turn.User("他的話"), Turn.User(StructuredCaller.retryText(listOf(StructuredCaller.EMPTY)))), llm.requests[1].turns)
    }

    @Test fun otherFailuresGoToTheTemplateWithoutRetry() = runBlocking {
        for (f in listOf(Fail.CAP, Fail.NO_KEY, Fail.OFFLINE, Fail.REFUSED, Fail.RATE)) {
            val llm = ScriptedLlm(ScriptedLlm.failed(f))
            val r = caller(llm).utter()
            assertEquals(Structured.Template(Utterance("模板"), 1, f, emptyList()), r)
            assertEquals(1, llm.requests.size)
        }
    }

    @Test fun claudeCallsGetAtLeastTheTokenFloor() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.ok("""{"text":"喝水。"}"""), ScriptedLlm.ok("""{"text":"喝水。"}"""))
        StructuredCaller(llm) { ProviderChoice.CLAUDE }.call(NudgeLine.SPEC, "s", listOf(Turn.User("u")), Purpose.NUDGE, 100) { null }
        assertEquals(StructuredCaller.CLAUDE_MIN_TOKENS, llm.requests.single().maxTokens)
        StructuredCaller(llm) { ProviderChoice.DEEPSEEK }.call(NudgeLine.SPEC, "s", listOf(Turn.User("u")), Purpose.NUDGE, 100) { null }
        assertEquals(100, llm.requests.last().maxTokens)
    }
}
