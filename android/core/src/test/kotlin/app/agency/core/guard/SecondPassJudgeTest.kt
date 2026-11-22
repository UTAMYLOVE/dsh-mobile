package app.agency.core.guard

import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.LlmTestKit
import app.agency.core.llm.OpenAiCompatClient
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

    // The time limit makes a judge without its own timeout fail here instead of hanging the suite.
    @Test(timeout = 5_000) fun aTimeoutIsNoAnswer() = runBlocking {
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

    @Test fun theExcuseClassIsAboutTheSlipAndExemptsComfortForHisFeelingsOrBody() {
        val excuse = RubricFollowingJudge.examples(SecondPassJudge.SYSTEM).toMap().getValue("excuse")
        assertTrue(excuse.toString(), "吃超過沒關係" in excuse && "破戒沒關係" in excuse && "偶爾一次很正常" in excuse)
        assertTrue(excuse.toString(), "偶爾放縱很正常" in excuse && "一次兩次不會怎樣" in excuse)
        // A bare 「沒關係」 is never an example on its own.
        assertTrue(excuse.toString(), "沒關係" !in excuse)
        // The limit is on the word 沒關係 only; every other excuse still counts (FIX-T05c re-review N1).
        assertTrue(SecondPassJudge.SYSTEM.contains("「沒關係」只有用在破戒或吃超過上才算違規"))
        assertTrue(SecondPassJudge.SYSTEM.contains("其他替破戒或超量找理由、淡化的說法（例如偶爾放縱很正常、一次兩次不會怎樣）一律算藉口"))
        assertTrue(SecondPassJudge.SYSTEM.contains("對他的心情或身體說沒關係"))
        assertTrue(SecondPassJudge.SYSTEM.contains("溫柔安慰他"))
        assertEquals("替吃超過或違規找藉口、說破戒沒關係", JudgeClass.EXCUSE.label)
    }

    @Test fun gentleCorpusLinesOnABreachDayAreCleanForAJudgeThatFollowsTheRubric() = runBlocking {
        val gentle = javaClass.getResource("/guard/corpus.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it[1] == "CLEAN" && it[2] == "GENTLE" }
            .map { it[5] }
        assertTrue(gentle.size >= 15)
        assertTrue(gentle.count { "沒關係" in it } >= 8)
        val fake = RubricFollowingJudge()
        val j = judge(fake)
        for (line in gentle + listOf("沒關係，心情不好就先休息，難過可以跟我說。", "哭出來沒關係，我在。")) {
            assertEquals(line, Verdict.Clean, j.judge(line, "今天超過 380 大卡，也有違規"))
        }
        // The same fake is not blind: a slip excused in the rubric's words is a breach.
        assertEquals(JudgeClass.EXCUSE, (j.judge("吃超過沒關係，明天再說。", "今天超過 380 大卡") as Verdict.Breach).cls)
        assertEquals(JudgeClass.EXCUSE, (j.judge("偶爾一次很正常。", "今天有違規") as Verdict.Breach).cls)
        // Excuses without 沒關係 are still excuses.
        assertEquals(JudgeClass.EXCUSE, (j.judge("偶爾放縱很正常啦，明天再說。", "今天超過 380 大卡") as Verdict.Breach).cls)
        assertEquals(JudgeClass.EXCUSE, (j.judge("一次兩次不會怎樣。", "今天有違規") as Verdict.Breach).cls)
    }

    @Test fun theCompatibleServicesDailyQuotaCountsTheJudge() = runBlocking {
        LlmTestKit().use { kit ->
            val ep = CompatEndpoint.generic(kit.base.toString(), "m", jsonMode = true, tools = false, allowHttp = true)
            var taken = 0
            val counting = OpenAiCompatClient(kit.transport, { ep }, { kit.key }, kit.checks, kit.sink, takeQuota = { taken++; true })
            kit.enqueue(200, LlmTestKit.completion("""{"role":"assistant","content":"{\"violation\":false,\"class\":\"none\",\"reason\":\"\"}"}"""))
            assertEquals(Verdict.Clean, judge(counting).judge(reply, context))
            assertEquals(1, taken)
            assertEquals(1, kit.server.requestCount)

            val spent = OpenAiCompatClient(kit.transport, { ep }, { kit.key }, kit.checks, kit.sink, takeQuota = { false })
            assertEquals(Verdict.NoAnswer(Fail.CAP), judge(spent).judge(reply, context))
            assertEquals(1, kit.server.requestCount)
        }
    }
}
