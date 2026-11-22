package app.agency.core.brain

import app.agency.core.engine.Signal
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.ToolCall
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.metering.MutableClock
import app.agency.core.model.MessageMode
import app.agency.core.model.Message
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.persona.PromptBuilder
import app.agency.core.rules.RulesBook
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.PlanStore
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12 through the brain with a scripted model: the ten tools, proposals that wait for his yes (the one proposal table),
 * 討論 one question at a time, memory under her bubble with 撤銷, thinking kept and sent back (owner's choice 乙), and a
 * line of his that could not be sent (「沒送出」 / 重送).
 */
class ChatT12Test {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock(Instant.parse("2026-11-23T04:00:00Z")) // Monday 12:00 in Taipei
    private var n = 0
    private val logs = mutableListOf<String>()
    private var provider = ProviderChoice.DEEPSEEK

    init {
        runBlocking {
            store.profile.put(Profile(onboardingDone = false))
            PlanStore(store).seedIfMissing(0)
        }
    }

    private fun brain(llm: ScriptedLlm, clerk: ScriptedLlm = llm) =
        Brain(store, llm, { provider }, AgencyClock.of(clock), intakeLlm = clerk, log = { logs += it }) { "id-%05d".format(++n) }

    private fun intake(kind: String) = ScriptedLlm.ok("""{"kind":"$kind"}""")

    /** A reply as DeepSeek returns it: the Utterance JSON, with its reasoning. */
    private fun reply(text: String, reasoning: String? = "先想一下", ask: String? = null, options: List<String> = emptyList(), memory: String = "[]"): ChatResult {
        val json = buildJsonObject {
            put("text", text); put("ask", ask?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("options", JsonArray(options.map { JsonPrimitive(it) }))
            put("recommended", if (options.isEmpty()) kotlinx.serialization.json.JsonNull else JsonPrimitive(0))
            put("memory", kotlinx.serialization.json.Json.parseToJsonElement(memory))
        }.toString()
        val raw = buildJsonObject {
            put("role", "assistant"); put("content", json); reasoning?.let { put("reasoning_content", it) }
        }
        return ChatResult.Ok(json, emptyList(), Turn.Assistant(Wire.OPENAI, raw, json, emptyList()), null)
    }

    private fun call(name: String, args: String, id: String = "c-${++n}"): ChatResult {
        val c = ToolCall(id, name, args)
        val raw = buildJsonObject {
            put("role", "assistant"); put("content", ""); put("reasoning_content", "要叫工具")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject { put("id", id); put("type", "function"); put("function", buildJsonObject { put("name", name); put("arguments", args) }) })
            })
        }
        return ChatResult.Ok(null, listOf(c), Turn.Assistant(Wire.OPENAI, raw, null, listOf(c)), null)
    }

    private fun judgeOk() = ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}""")

    private fun stored(): List<Message> = runBlocking { store.messages.recent(100) }

    // --- thinking (乙) ---

    @Test fun herThinkingIsKeptAndSentBackOnTheNextRoundWithThinkingOn() {
        val llm = ScriptedLlm(intake("chat"), reply("早點睡。", reasoning = "他昨天很晚睡"), intake("chat"), reply("去喝水。"))
        val b = brain(llm)
        val first = runBlocking { b.onText("姐姐在嗎") }
        assertEquals(Thinking.LOW, llm.requests[1].thinking)
        val row = runBlocking { store.thinking.of(listOf(first[1].id)) }.single()
        assertEquals("他昨天很晚睡", row.payload)
        assertEquals("OPENAI", row.wire)
        assertEquals("DEEPSEEK", row.provider)
        runBlocking { b.onText("我在做功課") }
        val second = llm.requests[3]
        assertEquals(Thinking.LOW, second.thinking)
        val back = second.turns.filterIsInstance<Turn.Assistant>().single()
        assertEquals("他昨天很晚睡", ((back.raw as JsonObject)["reasoning_content"] as JsonPrimitive).content)
        assertEquals("早點睡。", back.text)
        // Never shown, never in a message, never in memory, never logged.
        assertTrue(stored().none { it.text.contains("很晚睡") })
        assertTrue(runBlocking { store.memory.items() }.none { it.text.contains("很晚睡") })
        assertTrue(logs.none { it.contains("很晚睡") })
    }

    @Test fun anImportedReplyWithoutThinkingTurnsItOffForThatRoundAndLogsOneLineWithoutContent() {
        runBlocking {
            store.messages.insert(Message("old-1", 1, Who.OWNER, text = "早安", legacy = true))
            store.messages.insert(Message("old-2", 2, Who.COMPANY, role = RoleKey.MANAGER, text = "去吃早餐。", legacy = true))
        }
        val llm = ScriptedLlm(intake("chat"), reply("好。", reasoning = null), intake("chat"), reply("去睡。"))
        val b = brain(llm)
        runBlocking { b.onText("在嗎") }
        assertEquals(Thinking.OFF, llm.requests[1].thinking)
        assertEquals(Turn.AssistantText("【經紀人】去吃早餐。"), llm.requests[1].turns[1])
        assertEquals(1, logs.size)
        assertFalse(logs.single().contains("早餐"))
        // The reply of that round had no thinking: it goes back as a labelled line on his side, so it does not keep thinking
        // off once the imported lines are gone.
        runBlocking { (1..7L).forEach { store.messages.insert(Message("f$it", clock.millis() + it, Who.COMPANY, role = RoleKey.MANAGER, kind = "link", text = "提醒$it", wordedBy = WordedBy.TEMPLATE)) } }
        runBlocking { b.onText("好喔") }
        assertEquals(Thinking.LOW, llm.requests[3].thinking)
        assertTrue(llm.requests[3].turns.none { it is Turn.AssistantText })
        assertTrue((llm.requests[3].turns.last() as Turn.User).text.contains(PromptBuilder.CONTEXT + "【經紀人】好。"))
    }

    @Test fun claudesThinkingBlocksAreKeptButStayInTheirRound() {
        provider = ProviderChoice.CLAUDE
        val json = """{"text":"早點睡。","ask":null,"options":[],"recommended":null,"memory":[]}"""
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", "簽章") })
            add(buildJsonObject { put("type", "text"); put("text", json) })
        }
        val claude = ChatResult.Ok(json, emptyList(), Turn.Assistant(Wire.ANTHROPIC, content, json, emptyList()), null)
        val llm = ScriptedLlm(intake("chat"), claude, intake("chat"), reply("去喝水。"))
        val b = brain(llm)
        val out = runBlocking { b.onText("在嗎") }
        val row = runBlocking { store.thinking.of(listOf(out[1].id)) }.single()
        assertEquals("ANTHROPIC", row.wire)
        assertTrue(row.payload.contains("簽章") && !row.payload.contains("早點睡"))
        runBlocking { b.onText("好") }
        assertEquals(Turn.AssistantText("【經紀人】早點睡。"), llm.requests[3].turns[1])
        assertTrue(logs.isEmpty())
    }

    // --- offline: 沒送出 and 重送 ---

    @Test fun withNoNetworkHisLineIsKeptAsUnsentAndAResendThatGetsThroughClearsItOnTheSameRow() {
        val llm = ScriptedLlm(ScriptedLlm.failed(Fail.OFFLINE), ScriptedLlm.failed(Fail.OFFLINE), intake("chat"), reply("在。去喝水。"))
        val b = brain(llm)
        val out = runBlocking { b.onText("姐姐在嗎") }
        assertEquals(1, out.size)
        assertTrue(out[0].unsent)
        assertEquals(listOf(Who.OWNER), stored().map { it.who })
        assertEquals(Fail.OFFLINE, b.lastFail.value)
        // Still offline: it stays marked, nothing is written.
        assertTrue(runBlocking { b.resend(out[0].id) }.single().unsent)
        assertEquals(1, stored().size)
        // Back online.
        val again = runBlocking { b.resend(out[0].id) }
        assertFalse(again[0].unsent)
        assertEquals(out[0].id, again[0].id)
        assertEquals(listOf(Who.OWNER, Who.COMPANY), stored().map { it.who })
        assertEquals(1, stored().count { it.text == "姐姐在嗎" })
        assertFalse(stored()[0].unsent)
        assertEquals("在。去喝水。", stored()[1].text)
        assertNull(b.lastFail.value)
        assertTrue(runBlocking { b.resend(out[0].id) }.isEmpty()) // not marked any more
    }

    // --- proposals ---

    private val changeArgs = """{"section":"schedule","ops":[{"path":"class_wake","value":"08:20"}],"reason":"早一點起來比較有時間吃早餐"}"""

    @Test fun aProposalWaitsForHisYesThenWritesTheNextVersionWithHistory() {
        val llm = ScriptedLlm(intake("chat"), call("propose_change", changeArgs), reply("我提了一個調整，你看一下卡片。"), judgeOk())
        val b = brain(llm)
        val out = runBlocking { b.onText("我想早一點起床") }
        val p = runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        assertEquals(ProposalKind.PLAN_CHANGE, p.kind)
        assertEquals(listOf(p.id), out[1].refs.proposals)
        // Not applied before his yes.
        assertEquals("08:30", runBlocking { PlanStore(store).specs() }.schedule.classWake)
        assertEquals(1, runBlocking { store.rules.section(PlanSectionKey.SCHEDULE) }!!.version)
        val card = runBlocking { b.pendingProposals() }.single()
        assertEquals("改作息", card.title)
        assertEquals(listOf(DiffLine("上課日起床", "08:30", "08:20")), card.lines)
        // His exact 「好」: the one pending proposal.
        val yes = runBlocking { b.onText("好") }
        assertEquals("好，改了：上課日起床 08:30 → 08:20。", yes[1].text)
        assertEquals(Discussion.DECIDED_KIND, yes[1].kind)
        assertEquals("08:20", runBlocking { PlanStore(store).specs() }.schedule.classWake)
        val section = runBlocking { store.rules.section(PlanSectionKey.SCHEDULE) }!!
        assertEquals(2, section.version)
        assertEquals(p.id, section.changedBy)
        assertEquals(2, runBlocking { store.rules.sectionHistory(PlanSectionKey.SCHEDULE) }.size)
        assertEquals(2, runBlocking { store.proposals.get(p.id) }!!.appliedVersion)
        assertTrue(runBlocking { b.pendingProposals() }.isEmpty())
    }

    @Test fun noChangesNothingAndAnUnansweredProposalLapsesWithTheDay() {
        val llm = ScriptedLlm(intake("chat"), call("propose_change", changeArgs), reply("看一下卡片。"), judgeOk())
        val b = brain(llm)
        runBlocking { b.onText("我想早一點起床") }
        val id = runBlocking { b.pendingProposals() }.single().id
        val no = runBlocking { b.decide(id, accept = false) }
        assertEquals("好，不改，照原本的。", no!!.text)
        assertEquals(ProposalStatus.REJECTED, runBlocking { store.proposals.get(id) }!!.status)
        assertEquals(1, runBlocking { store.rules.section(PlanSectionKey.SCHEDULE) }!!.version)

        val llm2 = ScriptedLlm(intake("chat"), call("propose_change", changeArgs), reply("看一下卡片。"), judgeOk())
        val b2 = brain(llm2)
        runBlocking { b2.onText("我想早一點起床") }
        val id2 = runBlocking { b2.pendingProposals() }.single().id
        clock.now = clock.now.plusSeconds(24 * 3600)
        assertTrue(runBlocking { b2.pendingProposals() }.isEmpty())
        assertEquals(ProposalStatus.EXPIRED, runBlocking { store.proposals.get(id2) }!!.status)
        assertNull(runBlocking { b2.decide(id2, accept = true) })
        assertEquals("08:30", runBlocking { PlanStore(store).specs() }.schedule.classWake)
    }

    @Test fun rulesAreNotInTheSchemaAndACraftedCallIsRejected() {
        val crafted = """{"section":"rules","ops":[{"path":"class_wake","value":"08:20"}],"reason":"想改規章"}"""
        val llm = ScriptedLlm(intake("chat"), call("propose_change", crafted), reply("規章要你自己到設定改。"))
        runBlocking { brain(llm).onText("每天熱量可以調高嗎") }
        // 「每天熱量…多一點」 is a rule-change attempt: the fixed reply, no model at all.
        assertTrue(llm.requests.isEmpty())
        assertEquals(RulesBook.LOCKED, stored().last().text)
        val schema = app.agency.core.tools.ProposeChangeTool.SECTIONS
        assertFalse("rules" in schema)
        // Through the loop anyway: the tool says why and nothing is written.
        val llm2 = ScriptedLlm(intake("chat"), call("propose_change", crafted), reply("規章只有你自己能在設定改。"))
        runBlocking { brain(llm2).onText("我想早一點起床") }
        val result = llm2.requests[2].turns.filterIsInstance<Turn.ToolResult>().single()
        assertTrue(result.isError && result.content.contains("BAD_ARGS") && result.content.contains("設定"))
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.isEmpty())
    }

    @Test fun twoPendingProposalsAreDecidedOnTheirCardsNotByOneYes() {
        val llm = ScriptedLlm(
            intake("chat"), call("propose_change", changeArgs),
            call("propose_change", """{"section":"meals","ops":[{"path":"dinner","value":"17:30"}],"reason":"晚餐晚一點"}"""),
            reply("兩個提案，你看卡片。"), judgeOk(),
        )
        val b = brain(llm)
        runBlocking { b.onText("作息想調一下") }
        assertEquals(2, runBlocking { b.pendingProposals() }.size)
        assertEquals("現在有 2 個提案在等你，在提案卡上一個一個按「好」或「不要」。", runBlocking { b.onText("好") }[1].text)
        assertEquals(2, runBlocking { b.pendingProposals() }.size)
    }

    // --- 討論 ---

    @Test fun discussionAsksOneQuestionAtATimeAndChangesOnlyAfterHisYes() {
        val twoQuestions = reply("你覺得哪裡不適合？還是時間不對？", ask = "是起床太早嗎？")
        val llm = ScriptedLlm(
            twoQuestions, reply("好，先弄清楚。", ask = "是起床太早，還是晚上太晚睡？", options = listOf("起床太早", "太晚睡")),
            intake("chat"), call("propose_change", changeArgs.replace("08:20", "08:40").replace("早一點起來比較有時間吃早餐", "晚十分鐘起床")),
            reply("那就晚十分鐘起床，你說好才改。", ask = "這樣可以嗎？", options = listOf("可以", "不要")),
        )
        val b = brain(llm)
        val open = runBlocking { b.onText("這裡有問題") }
        // The tap skips the clerk; a reply with two questions went back once.
        assertEquals(listOf(Purpose.CHAT, Purpose.CHAT), llm.requests.take(2).map { it.purpose })
        assertTrue(llm.requests[1].turns.last().let { it is Turn.User && it.text.contains("上一個輸出不合格") })
        assertEquals(MessageMode.DISCUSS, open[1].mode)
        assertEquals("好，先弄清楚。\n是起床太早，還是晚上太晚睡？", open[1].text)
        assertEquals(listOf("起床太早", "太晚睡"), open[1].refs.options)
        assertTrue(llm.requests[0].system.contains("【模式：討論】"))
        // His answer stays in the discussion: she proposes; nothing changes until his yes.
        val next = runBlocking { b.onText("起床太早") }
        assertTrue(llm.requests[3].system.contains("【模式：討論】"))
        assertEquals(MessageMode.DISCUSS, next[1].mode)
        assertEquals("08:30", runBlocking { PlanStore(store).specs() }.schedule.classWake)
        val yes = runBlocking { b.onText("好") }
        assertEquals("好，改了：上課日起床 08:30 → 08:40。", yes[1].text)
        assertEquals("08:40", runBlocking { PlanStore(store).specs() }.schedule.classWake)
        // The answer closed the discussion.
        assertFalse(Discussion.active(stored(), java.time.LocalDate.parse("2026-11-23")) { java.time.LocalDate.parse("2026-11-23") })
    }

    @Test fun heEndsADiscussionWithHisWord() {
        val llm = ScriptedLlm(reply("好。", ask = "哪裡不適合？"))
        val b = brain(llm)
        runBlocking { b.onText("這裡有問題") }
        val end = runBlocking { b.onText("先這樣") }
        assertEquals(Discussion.END_LINE, end[1].text)
        assertEquals(1, llm.requests.size)
        assertFalse(Discussion.active(stored(), java.time.LocalDate.parse("2026-11-23")) { java.time.LocalDate.parse("2026-11-23") })
    }

    // --- memory ---

    @Test fun aMemoryEventIsShownUnderHerReplyAndCanBeUndone() {
        val memory = """[{"op":"add","block":"likes","item_id":null,"text":"他不吃香菜","quote":"我不吃香菜"}]"""
        val llm = ScriptedLlm(intake("chat"), reply("記住了，去吃飯。", memory = memory), judgeOk())
        val b = brain(llm)
        val out = runBlocking { b.onText("我不吃香菜") }
        val ev = out[1].refs.memory.single()
        assertEquals("他不吃香菜", runBlocking { store.memory.event(ev) }!!.after)
        assertEquals(listOf("他不吃香菜"), runBlocking { store.memory.items() }.map { it.text })
        assertTrue(runBlocking { b.undoMemory(ev) }.ok)
        assertTrue(runBlocking { store.memory.items() }.isEmpty())
        assertFalse(runBlocking { b.undoMemory(ev) }.ok)
    }

    @Test fun theRememberToolAndTheReplyShareTheLimitOfTwo() {
        val tool = """{"events":[{"op":"add","block":"likes","item_id":null,"text":"他喜歡吃雞腿","quote":"喜歡雞腿"},{"op":"add","block":"likes","item_id":null,"text":"他喜歡甜的飲料","quote":"甜的飲料"}]}"""
        val memory = """[{"op":"add","block":"likes","item_id":null,"text":"他喜歡豆漿","quote":"豆漿"}]"""
        val llm = ScriptedLlm(intake("chat"), call("remember", tool), reply("好。", memory = memory), judgeOk())
        val out = runBlocking { brain(llm).onText("我喜歡雞腿和甜的飲料和豆漿") }
        assertEquals(2, out[1].refs.memory.size)
        assertEquals(setOf("他喜歡吃雞腿", "他喜歡甜的飲料"), runBlocking { store.memory.items() }.map { it.text }.toSet())
    }

    @Test fun heCanDeleteAnItemHimselfAndUndoThat() {
        val memory = """[{"op":"add","block":"likes","item_id":null,"text":"他不吃香菜","quote":"我不吃香菜"}]"""
        val llm = ScriptedLlm(intake("chat"), reply("好。", memory = memory), judgeOk())
        val b = brain(llm)
        runBlocking { b.onText("我不吃香菜") }
        val item = runBlocking { store.memory.items() }.single()
        assertTrue(runBlocking { b.forgetMemory(item.id) })
        assertTrue(runBlocking { store.memory.items() }.isEmpty())
        assertTrue(runBlocking { b.undoMemory(store.memory.events().first().id) }.ok)
        assertEquals(1, runBlocking { store.memory.items() }.size)
    }

    // --- L14 through the day start ---

    @Test fun l14ThreeDaysMissedOpensADiscussionAtTheDayStart() {
        runBlocking {
            store.profile.put(Profile(onboardingDone = true))
            // Reminders started on Wednesday the 18th; nothing was reported on the 19th, 20th, 21st and 22nd.
            store.nerve.insertSignal(app.agency.core.model.StoredSignal(Signal.REMINDERS_ON, "2026-11-18", Instant.parse("2026-11-18T04:00:00Z").toEpochMilli(), "reminders_on", Signal.encode(Signal.RemindersOn(Signal.REMINDERS_ON, 0))))
        }
        val llm = ScriptedLlm(intake("chat"), reply("先講原因。", ask = "是時間不夠嗎？", options = listOf("時間不夠", "忘記了")))
        val b = brain(llm)
        val out = runBlocking { b.onText("在嗎") }
        val q = out.single { it.who == Who.COMPANY && it.kind == "link" }
        assertEquals("起床簽到已經連三天沒做到了。我要先弄清楚原因：是哪裡卡住了？", q.text)
        assertEquals(MessageMode.DISCUSS, q.mode)
        assertEquals(app.agency.core.engine.Links.L14Discuss.OPTIONS, q.refs.options)
        // The reply to his message is in 討論 already.
        assertTrue(llm.requests[1].system.contains("【模式：討論】"))
        assertEquals(MessageMode.DISCUSS, out.last().mode)
        // Once a day.
        assertEquals(1, runBlocking { store.nerve.effects("2026-11-23") }.count { it.link == "L14" && it.kind == app.agency.core.model.EffectKind.SAY })
    }

    @Test fun theDefaultPlanIsAlwaysAValidPlanForTheChecks() {
        val specs = PlanSpecs()
        val ok = app.agency.core.schedule.PlanChanges.check(specs, PlanSectionKey.MEALS, listOf("dinner" to "17:30"), java.time.LocalDate.parse("2026-11-23"))
        assertTrue(ok is app.agency.core.schedule.PlanChanges.Checked.Ok)
    }

    @Test fun dizzyIsAGentleDayWithTheDoctorLineAndNoModel() {
        val llm = ScriptedLlm(intake("chat"), reply("先坐著休息。"))
        val b = brain(llm)
        val out = runBlocking { b.onText("我今天頭暈") }
        assertTrue(llm.requests.isEmpty())
        assertTrue(out.any { it.text == "頭暈就先坐下來，正常吃東西、喝水；一直暈或更嚴重就去看醫生。" })
        // Her next reply is in the gentle voice (code decides the tone, all day).
        runBlocking { b.onText("姐姐在嗎") }
        assertTrue(llm.requests[1].system.contains("【這一次的語氣：放軟】"))
    }
}
