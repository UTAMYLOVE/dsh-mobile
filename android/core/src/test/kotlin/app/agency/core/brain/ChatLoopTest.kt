package app.agency.core.brain

import app.agency.core.engine.Tone
import app.agency.core.guard.Evidence
import app.agency.core.guard.JudgeClass
import app.agency.core.guard.SecondPassJudge
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.ToolCall
import app.agency.core.llm.ToolSpec
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.persona.Roles
import app.agency.core.structured.JsonSchema
import app.agency.core.structured.ScriptedLlm
import app.agency.core.structured.StructuredCaller
import app.agency.core.structured.Violation
import app.agency.core.structured.Utterance
import app.agency.core.tools.AgencyTool
import app.agency.core.tools.ToolRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLoopTest {
    /** A tool that answers {"kcal_left": 812} and counts its runs. */
    private class Left : AgencyTool {
        var runs = 0
        override val spec = ToolSpec("get_today", "今天", JsonSchema.obj())
        override suspend fun run(args: JsonObject): JsonElement {
            runs++
            return buildJsonObject { put("kcal_left", 812) }
        }
    }

    private val tool = Left()
    private val tools = ToolRegistry(listOf(tool))

    private fun round(tone: Tone = Tone.COLD, judgeContext: String? = null) =
        ChatRound("系統", listOf(Turn.User("今天還能吃多少")), RoleKey.MANAGER, MessageMode.NORMAL, tone, judgeContext) { outputs ->
        Evidence(listOf("""{"rules":{"quota_kcal":1700}}""") + outputs, ownerText = "今天還能吃多少")
    }

    private fun utter(text: String) = """{"text":"$text","ask":null,"options":[],"recommended":null,"memory":[]}"""

    private fun calls(vararg c: ToolCall): ChatResult =
        ChatResult.Ok(null, c.toList(), Turn.Assistant(Wire.OPENAI, JsonPrimitive("raw"), null, c.toList()), null)

    private fun run(llm: ScriptedLlm, tone: Tone = Tone.COLD) = runBlocking { ChatLoop(llm, { ProviderChoice.DEEPSEEK }).run(round(tone), tools) }

    private fun verdict(cls: JudgeClass?, reason: String = "") = ScriptedLlm.ok(
        if (cls == null) """{"violation":false,"class":"none","reason":""}""" else """{"violation":true,"class":"${cls.key}","reason":"$reason"}""",
    )

    private fun judged(writer: ScriptedLlm, judge: LlmClient, context: String? = OVER, timeoutMs: Long = SecondPassJudge.TIMEOUT_MS) = runBlocking {
        ChatLoop(writer, { ProviderChoice.DEEPSEEK }, judge = SecondPassJudge(judge, { ProviderChoice.DEEPSEEK }, timeoutMs))
            .run(round(judgeContext = context), tools)
    }

    @Test fun aNumberFromAToolResultIsKeptAndAnInventedOneIsDropped() {
        val llm = ScriptedLlm(
            calls(ToolCall("c1", "get_today", "{}")),
            ScriptedLlm.ok(utter("今天還剩 812 大卡。其實你只能再吃 1234 大卡。現在去吃飯。")),
        )
        val r = run(llm)
        assertTrue(r.fromModel)
        assertEquals("今天還剩 812 大卡。現在去吃飯。", r.utterance.text)
        assertEquals(1, tool.runs)
        assertEquals(2, r.steps)
        // The second step carries the assistant turn as it came, then the tool's answer; system, tools and schema unchanged.
        val second = llm.requests[1]
        assertTrue(second.turns[1] is Turn.Assistant)
        assertEquals("""{"kcal_left":812}""", (second.turns[2] as Turn.ToolResult).content)
        assertEquals(llm.requests[0].system, second.system)
        assertEquals(llm.requests[0].tools, second.tools)
        assertEquals(Purpose.CHAT, second.purpose)
        assertTrue(second.output is Output.Json)
    }

    @Test fun anUnknownToolAndBadArgumentsGoBackAsErrorsAndTheLoopRecovers() {
        val llm = ScriptedLlm(
            calls(ToolCall("c1", "set_rules", "{}"), ToolCall("c2", "get_today", "not json")),
            ScriptedLlm.ok(utter("先吃飯。")),
        )
        val r = run(llm)
        assertTrue(r.fromModel)
        val results = llm.requests[1].turns.filterIsInstance<Turn.ToolResult>()
        assertTrue(results.all { it.isError })
        assertTrue(results[0].content.contains("UNKNOWN_TOOL"))
        assertTrue(results[1].content.contains("BAD_ARGS"))
        assertEquals(0, tool.runs)
    }

    @Test fun aRepeatedCallIsNotRunAgainAndOnlyRepeatsEndOnTheTemplate() {
        val llm = ScriptedLlm(
            calls(ToolCall("c1", "get_today", "{}")),
            calls(ToolCall("c2", "get_today", "{}")),
        )
        val r = run(llm)
        assertFalse(r.fromModel)
        assertEquals(Roles.template(RoleKey.MANAGER, Tone.COLD), r.utterance.text)
        assertEquals(1, tool.runs)
        assertEquals(2, llm.requests.size)
    }

    @Test fun sixStepsAtMostThenTheTemplate() {
        val llm = ScriptedLlm(*Array(6) { i -> calls(ToolCall("c$i", "get_today", """{"n":$i}""")) })
        val r = run(llm)
        assertFalse(r.fromModel)
        assertEquals(6, llm.requests.size)
        assertEquals(6, r.steps)
    }

    @Test fun aRejectedReplyIsRetriedTwiceWithItsReasonsThenTheTemplate() {
        val llm = ScriptedLlm(
            ScriptedLlm.ok(utter("你只能再吃 1234 大卡。")),
            ScriptedLlm.ok("不是 json"),
            ScriptedLlm.ok(utter("再吃 999 大卡。")),
        )
        val r = run(llm, Tone.GENTLE)
        assertFalse(r.fromModel)
        assertEquals(Roles.template(RoleKey.MANAGER, Tone.GENTLE), r.utterance.text)
        assertEquals(3, llm.requests.size)
        val retry = llm.requests[1].turns.last() as Turn.User
        assertTrue(retry.text.startsWith("上一個輸出不合格："))
    }

    @Test fun anEmptyReplyIsOneFailedAttemptButNoAnswerAtAllEndsAtOnce() {
        val recovered = run(ScriptedLlm(ScriptedLlm.failed(Fail.BAD_RESPONSE), ScriptedLlm.ok(utter("去喝水。"))))
        assertTrue(recovered.fromModel)
        assertEquals("去喝水。", recovered.utterance.text)

        val offline = ScriptedLlm(ScriptedLlm.failed(Fail.OFFLINE))
        val r = run(offline)
        assertEquals(Fail.OFFLINE, r.fail)
        assertEquals(1, offline.requests.size)
    }

    @Test fun aModelReplyIsNeverEmptyOrLatin() {
        val r = run(ScriptedLlm(ScriptedLlm.ok(utter("好，去睡覺。OK。"))))
        assertTrue(r.fromModel)
        assertFalse(Regex("[A-Za-z]").containsMatchIn(r.utterance.text))
        assertTrue(Utterance.template(RoleKey.MANAGER, Tone.COLD).text.isNotBlank())
    }
    @Test fun theJudgeIsSkippedWhenTheTurnHasNoContextLine() {
        val judge = ScriptedLlm()
        val r = judged(ScriptedLlm(ScriptedLlm.ok(utter("去喝水。"))), judge, context = null)
        assertTrue(r.fromModel)
        assertEquals("去喝水。", r.utterance.text)
        assertTrue(judge.requests.isEmpty())
        assertEquals(0, r.judged)
    }

    @Test fun theJudgeIsSkippedWhenTheRuleGuardAlreadyRejected() {
        // The first reply breaks a rule (an invented figure is all it says): it goes back without asking the judge.
        val judge = ScriptedLlm(verdict(null))
        val writer = ScriptedLlm(ScriptedLlm.ok(utter("你只能再吃 1234 大卡。")), ScriptedLlm.ok(utter("先去喝水。")))
        val r = judged(writer, judge)
        assertEquals("先去喝水。", r.utterance.text)
        assertEquals(1, judge.requests.size)
        assertTrue((judge.requests.single().turns.single() as Turn.User).text.endsWith("先去喝水。"))
    }

    @Test fun aBreachTheJudgeFindsIsRewrittenWithItsReason() {
        val judge = ScriptedLlm(verdict(JudgeClass.COMPENSATION, "叫他晚餐只喝黑咖啡"), verdict(null))
        val writer = ScriptedLlm(ScriptedLlm.ok(utter("晚餐換成黑咖啡就好。")), ScriptedLlm.ok(utter("晚餐照平常吃，蛋白質吃夠。")))
        val r = judged(writer, judge)
        assertTrue(r.fromModel)
        assertEquals("晚餐照平常吃，蛋白質吃夠。", r.utterance.text)
        assertEquals(2, writer.requests.size)
        assertEquals(2, r.judged)
        val retry = writer.requests[1].turns.last() as Turn.User
        assertEquals(StructuredCaller.retryText(listOf(Violation("text", JudgeClass.COMPENSATION.label + "：叫他晚餐只喝黑咖啡"))), retry.text)
        // The rejected reply goes back as the provider returned it, before the reasons.
        assertTrue(writer.requests[1].turns[writer.requests[1].turns.size - 2] is Turn.Assistant)
        assertEquals(listOf(Turn.User("今天的情況：$OVER\n要檢查的回覆：\n晚餐換成黑咖啡就好。")), judge.requests[0].turns)
    }

    @Test fun judgeRewritesShareTheRetryLimitOfTwoThenTheTemplate() {
        val judge = ScriptedLlm(verdict(JudgeClass.EXCUSE), verdict(JudgeClass.EXCUSE))
        val writer = ScriptedLlm(
            ScriptedLlm.ok(utter("你只能再吃 1234 大卡。")), // the rule guard's retry
            ScriptedLlm.ok(utter("先去喝水。")),
            ScriptedLlm.ok(utter("去睡覺。")),
        )
        val r = judged(writer, judge)
        assertFalse(r.fromModel)
        assertEquals(Roles.template(RoleKey.MANAGER, Tone.COLD), r.utterance.text)
        assertEquals(3, writer.requests.size)
        assertEquals(2, judge.requests.size)
        assertEquals(null, r.fail)
    }

    @Test fun aJudgeTimeoutKeepsTheGuardedReply() {
        var asked: String? = null
        val slow = LlmClient { req ->
            asked = (req.turns.single() as Turn.User).text
            awaitCancellation()
        }
        // The rule guard drops the invented figure; the judge reads what is left, does not answer, and that reply stands.
        val writer = ScriptedLlm(ScriptedLlm.ok(utter("去喝水。其實你只能再吃 1234 大卡。")))
        val r = judged(writer, slow, timeoutMs = 50)
        assertTrue(r.fromModel)
        assertEquals("去喝水。", r.utterance.text)
        assertEquals(1, writer.requests.size)
        assertEquals(1, r.judged)
        assertEquals(SecondPassJudge.userText("去喝水。", OVER), asked)
    }

    @Test fun noAnswerFromTheJudgeKeepsTheGuardedReply() {
        listOf(Fail.CAP, Fail.RATE, Fail.OFFLINE, Fail.BAD_RESPONSE).forEach { f ->
            val writer = ScriptedLlm(ScriptedLlm.ok(utter("去喝水。")))
            val r = judged(writer, ScriptedLlm(ScriptedLlm.failed(f)))
            assertTrue(f.name, r.fromModel)
            assertEquals("去喝水。", r.utterance.text)
            assertEquals(1, writer.requests.size)
        }
        val unreadable = judged(ScriptedLlm(ScriptedLlm.ok(utter("去喝水。"))), ScriptedLlm(ScriptedLlm.ok("好")))
        assertEquals("去喝水。", unreadable.utterance.text)
    }

    private companion object {
        const val OVER = "今天超過 380 大卡"
    }
}
