package app.agency.core.agent

import app.agency.core.base.TestClock
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.Effort
import app.agency.core.llm.Fail
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.ScriptedLlm
import app.agency.core.llm.ScriptedLlm.Companion.calls
import app.agency.core.llm.ScriptedLlm.Companion.fails
import app.agency.core.llm.ScriptedLlm.Companion.says
import app.agency.core.llm.Thinking
import app.agency.core.llm.Thoughts
import app.agency.core.llm.ToolSpec
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.rules.Fixtures
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.MemStore
import app.agency.core.store.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {
    private val store = MemStore()
    private val clock = TestClock()
    private val rules = Fixtures.rules()

    private class FakeContext(
        val text: String,
        override val unwellReducedByCode: Boolean = false,
        override val closedFeatureWords: List<String> = emptyList(),
        override val neverBelowKg: Double = 60.0,
        override val mayPlayScene: Boolean = false,
        override val mayBeSweet: Boolean = false,
    ) : ContextSource {
        override fun block() = text
    }

    private class FakeTool(
        name: String,
        private val body: suspend (JsonObject, ToolCtx) -> ToolOut,
    ) : Tool {
        override val spec = ToolSpec(name, "測試工具", buildJsonObject {
            put("type", "object"); put("properties", JsonObject(emptyMap())); put("required", JsonArray(emptyList())); put("additionalProperties", false)
        })
        override val feature: Feature? = null
        val runs = ArrayList<JsonObject>()
        override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolOut { runs += args; return body(args, ctx) }
    }

    private val contextText = "現在 07:10，第 2 階段 · 第 1 天。今天喝水 0 壺（目標 2 壺）。今天吃了 0 大卡（目標 2000 大卡）。"
    private var context: ContextSource = FakeContext(contextText)
    private val photoIdsSeen = ArrayList<List<String>>()

    private val water = FakeTool("log_water") { _, _ ->
        ToolOut(buildJsonObject { put("water_bottles", 1); put("target_bottles", 2) }, listOf(Card.Summary("喝水", listOf("1／2 壺"))))
    }
    private val getDay = FakeTool("get_day") { _, _ -> ToolOut(buildJsonObject { put("kcal", 1260); put("kcal_left", 740) }) }
    private val boom = FakeTool("boom") { _, _ -> error("disk on fire") }
    private val remember = FakeTool("remember") { a, ctx ->
        Memory.remember(ctx.store, a["text"]!!.jsonPrimitive.content)
        ToolOut(buildJsonObject { put("ok", true) })
    }
    private val noteToday = FakeTool("note_today") { a, ctx ->
        Memory.noteToday(ctx.store, ctx.clock, a["text"]!!.jsonPrimitive.content)
        ToolOut(buildJsonObject { put("ok", true) })
    }
    private val tools = listOf(water, getDay, boom, remember, noteToday)

    private val judgeOk = buildJsonObject { put("ok", true); put("reason", "") }.toString()
    private val small = ScriptedLlm(fallback = { ScriptedLlm.ok(judgeOk, emptyList()) })

    private var photos = mapOf<String, ByteArray>()

    private fun agent(llm: ScriptedLlm) = Agent(
        llm = llm,
        small = small,
        store = store,
        clock = clock,
        tools = { tools },
        toolCtx = { ids -> photoIdsSeen += ids; ToolCtx(store, clock, rules, StageState(joinedOn = "2030-01-07", stage = 2), ids) },
        context = { context },
        system = { "系統提示" },
        loadPhoto = { photos[it] },
    )

    private fun manager() = store.messages(100).filter { it.role == Role.MANAGER }
    private fun owner() = store.messages(100).filter { it.role == Role.OWNER }

    @Test fun aPlainReply() = runBlocking {
        val llm = ScriptedLlm(says("早安，阿星。先簽到。"))
        assertEquals(SayResult.Ok, agent(llm).reply("早安", emptyList()))
        assertEquals(listOf("早安"), owner().map { it.text })
        assertEquals(listOf("早安，阿星。先簽到。"), manager().map { it.text })
        val r = llm.requests.single()
        assertEquals("系統提示", r.system)
        assertEquals(Purpose.CHAT, r.purpose)
        assertEquals(Thinking.LOW, r.thinking)
        assertEquals(Effort.MEDIUM, r.effort)
        assertEquals(tools.map { it.spec.name }, r.tools.map { it.name })
        assertEquals(0, small.requests.size) // nothing about food, weight or training: no judge
    }

    @Test fun aTwoRoundToolLoopCollectsCards() = runBlocking {
        val llm = ScriptedLlm(calls("log_water", """{"bottles":1}"""), calls("get_day"), says("記好了，喝了 1 壺，還差 1 壺。"))
        assertEquals(SayResult.Ok, agent(llm).reply("喝完一壺了", emptyList()))
        assertEquals(3, llm.requests.size)
        assertEquals(listOf(buildJsonObject { put("bottles", 1) }), water.runs)
        assertEquals(1, getDay.runs.size)
        val second = llm.requests[1].turns
        assertTrue(second[second.size - 2] is Turn.Assistant)
        val result = second.last() as Turn.ToolResult
        assertFalse(result.isError)
        assertTrue(result.content.contains("water_bottles"))
        assertEquals(2, llm.requests[2].turns.count { it is Turn.ToolResult })
        val m = manager().single()
        assertEquals("記好了，喝了 1 壺，還差 1 壺。", m.text)
        assertEquals(listOf(Card.Summary("喝水", listOf("1／2 壺"))), m.cards)
    }

    @Test fun toolErrorsGoBackToTheModel() = runBlocking {
        val llm = ScriptedLlm(calls("nope"), calls("boom"), calls("log_water", "not json"), says("好，換個方法。"))
        assertEquals(SayResult.Ok, agent(llm).reply("幫我記", emptyList()))
        val results = llm.requests.last().turns.filterIsInstance<Turn.ToolResult>()
        assertEquals(3, results.size)
        assertTrue(results.all { it.isError })
        assertTrue(results[0].content.contains("unknown tool"))
        assertTrue(results[1].content.contains("disk on fire"))
        assertTrue(results[2].content.contains("JSON object"))
        assertTrue(water.runs.isEmpty())
    }

    @Test fun theGuardAsksForARewriteAndTheRewriteIsKept() = runBlocking {
        val llm = ScriptedLlm(calls("get_day"), says("今天 1300 大卡了。"), says("今天 1260 大卡了，還剩 740 大卡。"))
        assertEquals(SayResult.Ok, agent(llm).reply("今天吃多少了", emptyList()))
        assertEquals(3, llm.requests.size)
        val note = (llm.requests[2].turns.last() as Turn.User).text
        assertTrue(note.contains("檢查沒過"))
        assertTrue(note.contains("今天 1300 大卡了。"))
        assertEquals("今天 1260 大卡了，還剩 740 大卡。", manager().single().text)
        assertEquals(1, small.requests.size) // 大卡 → the judge looked once
    }

    @Test fun aRewriteThatStillFailsLosesOnlyTheBadSentence() = runBlocking {
        val bad = "記好了，晚餐照吃。今天 1300 大卡。"
        val llm = ScriptedLlm(says(bad), says(bad), says(bad))
        assertEquals(SayResult.Ok, agent(llm).reply("今天吃多少了", emptyList()))
        assertEquals(3, llm.requests.size) // first answer + two rewrites, nothing more
        assertEquals("記好了，晚餐照吃。", manager().single().text)
    }

    @Test fun whenNothingIsLeftSheWritesOneNeutralLineHerself() = runBlocking {
        val bad = "明天少吃一點。"
        val llm = ScriptedLlm(calls("log_water"), says(bad), says(bad), says(bad), says("辛苦了，先喝水。"))
        assertEquals(SayResult.Ok, agent(llm).reply("吃太多了", emptyList()))
        val m = manager().single()
        assertEquals("辛苦了，先喝水。", m.text)
        assertEquals(1, m.cards.size)
        assertTrue((llm.requests.last().turns.last() as Turn.User).text.contains("中性"))
    }

    @Test fun aJudgeVetoGetsExactlyOneRewrite() = runBlocking {
        small.then({ ScriptedLlm.ok("""{"ok":false,"reason":"吃多了不能說沒關係"}""", emptyList()) })
        val llm = ScriptedLlm(says("吃多了沒關係，蛋白質不錯。"), says("吃多了會讓身體囤脂肪，晚餐小一點。"))
        assertEquals(SayResult.Ok, agent(llm).reply("今天吃太多了", emptyList()))
        assertEquals(2, llm.requests.size)
        assertTrue((llm.requests[1].turns.last() as Turn.User).text.contains("吃多了不能說沒關係"))
        assertEquals("吃多了會讓身體囤脂肪，晚餐小一點。", manager().single().text)
        val j = small.requests.single()
        assertEquals(Purpose.JUDGE, j.purpose)
        assertTrue(j.output is Output.Json)
    }

    @Test fun theJudgeSeesTodaysTotalsAndThisTurnsToolResultsNotEverything() = runBlocking {
        context = object : ContextSource {
            override fun block() = "現在 07:10。\n最近七天：一大段和這次無關的歷史。\n吃：今天 1260 大卡／目標 2000 大卡"
            override fun judgeFacts() = "吃：今天 1260 大卡／目標 2000 大卡"
            override val unwellReducedByCode = false
            override val closedFeatureWords = emptyList<String>()
            override val neverBelowKg = 60.0
            override val mayPlayScene = false
            override val mayBeSweet = false
        }
        val llm = ScriptedLlm(calls("get_day"), says("今天 1260 大卡了，還剩 740 大卡。"))
        assertEquals(SayResult.Ok, agent(llm).reply("今天吃多少了", emptyList()))
        val input = (small.requests.single().turns.single() as Turn.User).text
        assertTrue(input.contains("吃：今天 1260 大卡／目標 2000 大卡"))
        assertTrue(input.contains("kcal_left"))
        assertFalse(input.contains("最近七天"))
    }

    @Test fun aFailedJudgeCallNeverBlocks() = runBlocking {
        small.then(fails(Fail.OFFLINE))
        val llm = ScriptedLlm(says("體重記好了，下週同一天再量。"))
        assertEquals(SayResult.Ok, agent(llm).reply("量好體重了", emptyList()))
        assertEquals("體重記好了，下週同一天再量。", manager().single().text)
    }

    @Test fun aProviderFailureIsNotSentAndCanBeResent() = runBlocking {
        val llm = ScriptedLlm(fails(Fail.OFFLINE))
        val a = agent(llm)
        val r = a.reply("在嗎", emptyList())
        assertEquals(SayResult.NotSent(Agent.reason(Fail.OFFLINE)), r)
        val mine = owner().single()
        assertEquals(JsonPrimitive(true), mine.meta["failed"])
        assertTrue(manager().isEmpty())
        llm.then(says("在。"))
        assertEquals(SayResult.Ok, a.resend(mine.id))
        assertNull(owner().single().meta["failed"])
        assertEquals("在。", manager().single().text)
        assertEquals(listOf("在嗎"), owner().map { it.text })
    }

    @Test fun everyFailHasAShortReasonWithoutLatinLetters() {
        Fail.entries.forEach { f ->
            val r = Agent.reason(f)
            assertTrue(r.isNotBlank() && r.length < 40)
            assertTrue(r, r.none { it in 'A'..'Z' || it in 'a'..'z' })
        }
    }

    @Test fun photosGoOnlyOnTheUserTurnTheyCameWith() = runBlocking {
        val jpeg = byteArrayOf(1, 2, 3)
        val llm = ScriptedLlm(calls("log_water"), says("看到了，是一個便當，對嗎？"), says("好。"))
        val a = agent(llm)
        a.reply("這是午餐", listOf(Attachment.Photo("p1", jpeg, fromGallery = false), Attachment.File("菜單.txt", "雞胸 120")))
        for (req in llm.requests.take(2)) {
            val users = req.turns.filterIsInstance<Turn.User>()
            assertEquals(1, users.count { it.images.isNotEmpty() })
            assertTrue(users.last().images.single().contentEquals(jpeg))
            assertTrue(users.last().text.contains("菜單.txt") && users.last().text.contains("雞胸 120"))
        }
        assertEquals(listOf("p1"), photoIdsSeen.first())
        assertEquals(listOf(Card.Photo("p1"), Card.File("菜單.txt", 6)), owner().single().cards)
        // The next message: the earlier photo is only a bracketed note now.
        a.reply("對", emptyList())
        val next = llm.requests.last().turns.filterIsInstance<Turn.User>()
        assertTrue(next.all { it.images.isEmpty() })
        assertTrue(next.any { it.text.contains("附了 1 張照片") })
    }

    @Test fun aResentPhotoComesFromTheVault() = runBlocking {
        val jpeg = byteArrayOf(9, 9)
        photos = mapOf("p2" to jpeg)
        val llm = ScriptedLlm(fails(Fail.RATE), says("收到照片。"))
        val a = agent(llm)
        assertTrue(a.reply("看", listOf(Attachment.Photo("p2", jpeg, fromGallery = true))) is SayResult.NotSent)
        assertEquals(SayResult.Ok, a.resend(owner().single().id))
        assertTrue(llm.requests.last().turns.filterIsInstance<Turn.User>().last().images.single().contentEquals(jpeg))
    }

    @Test fun theRequestHoldsSystemContextSummaryAndRecentTurnsInOrder() = runBlocking {
        Memory.setSummary(store, "上週他說想學舞。", throughId = 0)
        store.addMessage(clock.now(), Role.OWNER, "早安")
        store.addMessage(clock.now(), Role.MANAGER, "早，先簽到。", meta = buildJsonObject { put("reasoning", "他剛起床。") })
        store.addMessage(clock.now(), Role.OWNER, "沒送出的話", meta = buildJsonObject { put("failed", true) })
        store.addMessage(clock.now(), Role.NOTE, "✓ 已記：簽到")
        val llm = ScriptedLlm(says("好，記得喝水。"))
        agent(llm).reply("喝完一壺了", emptyList())
        val r = llm.requests.single()
        assertEquals("系統提示", r.system)
        assertEquals(3, r.turns.size)
        val first = (r.turns[0] as Turn.User).text
        val iContext = first.indexOf(contextText)
        val iSummary = first.indexOf("上週他說想學舞。")
        val iMorning = first.indexOf("早安")
        assertTrue(iContext in 0 until iSummary && iSummary < iMorning)
        assertTrue(first.contains("可以演小劇場：否"))
        val reply = r.turns[1] as Turn.Assistant
        assertEquals("早，先簽到。", reply.text)
        assertEquals(Wire.OPENAI to "他剛起床。", Thoughts.of(reply))
        val last = (r.turns[2] as Turn.User).text
        assertTrue(last.indexOf("〔紀錄〕✓ 已記：簽到") in 0 until last.indexOf("喝完一壺了"))
        assertFalse(r.turns.any { it is Turn.User && it.text.contains("沒送出的話") })
    }

    @Test fun compactionFlushesMemoryThenWritesTheSummary() = runBlocking {
        repeat(45) { i -> store.addMessage(clock.now(), if (i % 2 == 0) Role.OWNER else Role.MANAGER, "舊訊息 $i") }
        val llm = ScriptedLlm(says("好。"), calls("remember", """{"text":"他喜歡豆漿"}"""), calls("note_today", """{"text":"今天很累"}"""),
            says("沒有了"))
        small.then(says("他最近在練習穩定作息。"))
        assertEquals(SayResult.Ok, agent(llm).reply("嗯", emptyList()))
        assertEquals(listOf("他喜歡豆漿"), Memory.lines(store))
        assertEquals(1, Memory.notes(store, clock).size)
        assertEquals("他最近在練習穩定作息。", Memory.summary(store))
        val all = store.messages(100)
        assertEquals(47, all.size)
        assertEquals(all[26].id, Memory.summarisedThrough(store))
        val flush = llm.requests[1]
        assertEquals(setOf("remember", "note_today"), flush.tools.map { it.name }.toSet())
        assertTrue((flush.turns.first() as Turn.User).text.contains("舊訊息 0"))
        val summary = small.requests.single()
        assertEquals(Purpose.SUMMARY, summary.purpose)
        assertFalse((summary.turns.single() as Turn.User).text.contains("舊訊息 30"))

        // The next request carries the summary and only the messages after it.
        val next = ScriptedLlm(says("好。"))
        agent(next).reply("再一句", emptyList())
        val turns = next.requests.single().turns
        val text = turns.filterIsInstance<Turn.User>().joinToString { it.text } + turns.filterIsInstance<Turn.AssistantText>().joinToString { it.text }
        assertTrue(text.contains("他最近在練習穩定作息。"))
        assertFalse(text.contains("舊訊息 26"))
        assertTrue(text.contains("舊訊息 27"))
    }

    @Test fun belowTheThresholdNothingIsCompacted() = runBlocking {
        repeat(10) { store.addMessage(clock.now(), Role.OWNER, "訊息 $it") }
        val llm = ScriptedLlm(says("好。"))
        agent(llm).reply("嗯", emptyList())
        assertEquals(1, llm.requests.size)
        assertEquals("", Memory.summary(store))
    }

    @Test fun aToolStatusSetThisTurnCountsForTheGuard() = runBlocking {
        val injured = FakeContext(contextText, unwellReducedByCode = true)
        val setStatus = FakeTool("set_status") { _, _ -> context = injured; ToolOut(buildJsonObject { put("ok", true) }) }
        val llm = ScriptedLlm(calls("set_status", """{"type":"INJURED"}"""), says("先停下來，今天不用練了。"))
        val a = Agent(llm, small, store, clock, { listOf(setStatus) },
            { ToolCtx(store, clock, rules, StageState(joinedOn = "2030-01-07", stage = 2), it) }, { context }, { "系統提示" })
        assertEquals(SayResult.Ok, a.reply("膝蓋刺痛", emptyList()))
        assertEquals("先停下來，今天不用練了。", manager().single().text)
        assertEquals(2, llm.requests.size)
    }

    @Test fun outOfRoundsSheIsAskedForText() = runBlocking {
        val steps = (1..7).map { calls("get_day") } + says("查好了。")
        val llm = ScriptedLlm(*steps.toTypedArray())
        assertEquals(SayResult.Ok, agent(llm).reply("查", emptyList()))
        assertEquals(Agent.MAX_ROUNDS, getDay.runs.size)
        assertEquals("查好了。", manager().single().text)
        val last: ChatRequest = llm.requests.last()
        assertTrue(last.turns.any { it is Turn.User && it.text.contains("工具已經用太多次") })
        assertTrue(llm.requests.all { it.tools.isNotEmpty() })
    }
}
