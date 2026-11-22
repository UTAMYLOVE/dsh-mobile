package app.agency.core.guard

import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.LlmTestKit
import app.agency.core.llm.Output
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Turn
import app.agency.core.metering.Provider
import app.agency.core.structured.ScriptedLlm
import app.agency.core.structured.StructuredCaller
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondPassJudgeTest {
    private fun judge(llm: LlmClient, choice: ProviderChoice = ProviderChoice.DEEPSEEK, timeoutMs: Long = SecondPassJudge.TIMEOUT_MS) =
        SecondPassJudge(llm, { choice }, timeoutMs)

    private val reply = "沒差啦，晚餐換成黑咖啡就好。"
    private val context = "今天超過 380 大卡"

    @Test fun thePromptIsTheFixedRubricPlusTheContextLineAndTheReplyOnly() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}"""))
        assertEquals(Verdict.Clean, judge(llm).judge(reply, context))
        val req = llm.requests.single()
        assertEquals(SecondPassJudge.SYSTEM, req.system)
        assertEquals(listOf(Turn.User("今天的情況：$context\n要檢查的回覆：\n$reply")), req.turns)
        // With the two fixed labels taken out, only the context line and the reply are left.
        val rest = (req.turns.single() as Turn.User).text.replace("今天的情況：", "").replace("要檢查的回覆：", "")
        assertEquals("$context\n\n$reply", rest)
        assertEquals(Purpose.JUDGE, req.purpose)
        assertEquals(Output.Json(SecondPassJudge.SCHEMA), req.output)
        assertEquals(Thinking.OFF, req.thinking)
        assertTrue(req.tools.isEmpty())
        assertEquals(SecondPassJudge.MAX_TOKENS, req.maxTokens)
    }

    @Test fun theRubricNamesTheThreeClassesAndCarriesNoFact() {
        listOf("compensation", "excuse", "attachment", "json").forEach { assertTrue(it, SecondPassJudge.SYSTEM.contains(it)) }
        // No digits: every figure the judge sees comes from the context line.
        assertTrue(SecondPassJudge.SYSTEM.none { it.isDigit() })
        assertEquals(listOf("compensation", "excuse", "attachment", "none"), JudgeClass.KEYS)
    }

    @Test fun claudeGetsTheTokenFloorBecauseItsThinkingCannotBeTurnedOff() = runBlocking {
        val llm = ScriptedLlm(ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}"""))
        judge(llm, ProviderChoice.CLAUDE).judge(reply, context)
        assertEquals(StructuredCaller.CLAUDE_MIN_TOKENS, llm.requests.single().maxTokens)
    }

    @Test fun verdictsAreReadStrictly() {
        assertEquals(Verdict.Clean, SecondPassJudge.read("""{"violation":false,"class":"none","reason":""}"""))
        assertEquals(
            Verdict.Breach(JudgeClass.COMPENSATION, "叫他用黑咖啡代替晚餐"),
            SecondPassJudge.read("""```json
{"violation":true,"class":"compensation","reason":"叫他用黑咖啡代替晚餐"}
```"""),
        )
        assertEquals(JudgeClass.EXCUSE, (SecondPassJudge.read("""{"violation":true,"class":"excuse","reason":""}""") as Verdict.Breach).cls)
        assertEquals(JudgeClass.ATTACHMENT, (SecondPassJudge.read("""{"violation":true,"class":"attachment","reason":"x"}""") as Verdict.Breach).cls)
        val long = "很".repeat(80)
        assertEquals(SecondPassJudge.REASON_CHARS, (SecondPassJudge.read("""{"violation":true,"class":"excuse","reason":"$long"}""") as Verdict.Breach).reason.length)
        // Unreadable, a string for the flag, or a violation without one of the three classes: no answer (the rules stand).
        listOf(
            "好的", """{"violation":"true","class":"excuse","reason":""}""", """{"violation":true,"class":"none","reason":""}""",
            """{"violation":true,"class":"rude","reason":""}""", """{"class":"excuse"}""", """{"violation":true,"class":"excuse"""",
        ).forEach { assertEquals(it, Verdict.NoAnswer(Fail.BAD_RESPONSE), SecondPassJudge.read(it)) }
    }

    @Test fun aBreachTellsTheWriterTheClassInCodeWordsAndTheReason() {
        assertEquals("吃超過或違規後叫他少吃、不吃、撐著或多運動來補：叫他用黑咖啡代替晚餐", Verdict.Breach(JudgeClass.COMPENSATION, "叫他用黑咖啡代替晚餐").violation.reason)
        assertEquals(JudgeClass.EXCUSE.label, Verdict.Breach(JudgeClass.EXCUSE, "").violation.reason)
    }

    @Test fun aTimeoutIsNoAnswer() = runBlocking {
        var cancelled = false
        val slow = LlmClient {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals(Verdict.NoAnswer(null), judge(slow, timeoutMs = 50).judge(reply, context))
        assertTrue("the call is cancelled, not left running", cancelled)
    }

    @Test fun aFailedCallOrAThrowingClientIsNoAnswer() = runBlocking {
        assertEquals(Verdict.NoAnswer(Fail.CAP), judge(ScriptedLlm(ScriptedLlm.failed(Fail.CAP))).judge(reply, context))
        assertEquals(Verdict.NoAnswer(Fail.OFFLINE), judge(ScriptedLlm(ScriptedLlm.failed(Fail.OFFLINE))).judge(reply, context))
        assertEquals(Verdict.NoAnswer(null), judge(LlmClient { error("boom") }).judge(reply, context))
        assertEquals(Verdict.NoAnswer(Fail.BAD_RESPONSE), judge(ScriptedLlm(ScriptedLlm.ok(null))).judge(reply, context))
    }

    @Test fun theContextLineIsWrittenByCodeAndNullWhenNoJudgeIsNeeded() {
        assertEquals("今天超過 380 大卡", SecondPassJudge.contextLine(380, breachToday = false, foodTalk = false))
        assertEquals("今天超過 380 大卡，也有違規", SecondPassJudge.contextLine(380, breachToday = true, foodTalk = true))
        assertEquals("今天有違規", SecondPassJudge.contextLine(0, breachToday = true, foodTalk = false))
        assertEquals("他正在討論吃的", SecondPassJudge.contextLine(-200, breachToday = false, foodTalk = true))
        assertEquals(null, SecondPassJudge.contextLine(0, breachToday = false, foodTalk = false))
        assertEquals(null, SecondPassJudge.contextLine(-500, breachToday = false, foodTalk = false))
    }

    @Test fun theJudgeIsMeteredAndSentAtTemperatureZeroOnTheCheapClient() = runBlocking {
        LlmTestKit().use { kit ->
            kit.enqueue(200, LlmTestKit.completion("""{"role":"assistant","content":"{\"violation\":true,\"class\":\"excuse\",\"reason\":\"說破戒很正常\"}"}"""))
            val v = judge(kit.deepseek()).judge(reply, context)
            assertEquals(Verdict.Breach(JudgeClass.EXCUSE, "說破戒很正常"), v)
            val body = LlmTestKit.json(kit.take())
            assertEquals(0.0, body["temperature"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1, kit.recorded.size)
            assertEquals(Provider.DEEPSEEK, kit.recorded.single().first)
        }
    }

    @Test fun theSpendCapStopsTheJudgeBeforeAnySocketAndTheRulesStand() = runBlocking {
        LlmTestKit().use { kit ->
            kit.capBlocked = true
            assertEquals(Verdict.NoAnswer(Fail.CAP), judge(kit.deepseek()).judge(reply, context))
            assertEquals(0, kit.server.requestCount)
            assertTrue(kit.recorded.isEmpty())
        }
    }
}
