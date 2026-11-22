package app.agency.core.scenario

import app.agency.core.brain.Brain
import app.agency.core.engine.DayState
import app.agency.core.engine.Fire
import app.agency.core.engine.FireKind
import app.agency.core.engine.Nudger
import app.agency.core.engine.PostChannel
import app.agency.core.engine.Tick
import app.agency.core.engine.IllKind
import app.agency.core.engine.Signal
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.Message
import app.agency.core.model.ProposalStatus
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayFlag
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.Reduction
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整天情境 (agency-links §4): one invented Monday (a class day) played through the brain in order: he wakes late, shaves, has
 * a fever and says yes to the today-only change, misses lunch (the alarm's push and the 30-minute chase), rides (one sound),
 * eats the on-run portion, gets home under the floor (the held reminder goes out) and signs out (quiet until tomorrow).
 * Batch 3 (T12) adds conversation to the same day: a chat report the manager answers with a tool, an attempt to change the
 * rules (fixed reply, no model), and a 討論 at night (one question at a time; the plan changes only after his 「好」, and
 * tomorrow's wake follows it). At the end: the day state, the plan, the proposals, the reminders and the lines. Later batches
 * add meals with photos, water, training and the bedtime review to this same day, and the emulator plays it last.
 */
class WholeDayScenarioTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    // The model is asked only for the conversation (T12): the clerk and the manager's tool loop; every other line is code's.
    private val llm = ScriptedLlm(
        // 12:30 his report: the clerk, a get_today call, then the reply (with its thinking).
        intake("chat"), call("get_today", "{}"), reply("收到。上課專心，下課回報。", reasoning = "他在學校"),
        // 23:40 his 「這裡有問題」: a reply with two questions goes back once, then one question with options.
        reply("哪裡有問題？是起床還是睡覺？", ask = "是早上嗎？"),
        reply("說說看。", ask = "是早上起床太趕，還是晚上太晚睡？", options = listOf("早上太趕", "晚上太晚")),
        // 23:41 his answer: the clerk, a plan change proposal, the reply asking for his yes, and the judge (he talked about food).
        intake("chat"), call("propose_change", """{"section":"schedule","ops":[{"path":"class_wake","value":"08:20"}],"reason":"早十分鐘起來吃早餐"}"""),
        reply("那上課日早十分鐘起床。你說好才改。", ask = "這樣可以嗎？", options = listOf("可以", "再想想")),
        ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}"""),
    )
    private var n = 0
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private val monday = LocalDate.parse("2026-12-07")

    init {
        // 入社建檔 is done (reminders start after it).
        runBlocking { store.profile.put(app.agency.core.model.Profile(onboardingDone = true)) }
    }

    private fun intake(kind: String) = ScriptedLlm.ok("""{"kind":"$kind"}""")

    private fun reply(text: String, reasoning: String = "想一下", ask: String? = null, options: List<String> = emptyList()): app.agency.core.llm.ChatResult {
        val json = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(text))
            put("ask", ask?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("options", kotlinx.serialization.json.JsonArray(options.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("recommended", if (options.isEmpty()) kotlinx.serialization.json.JsonNull else kotlinx.serialization.json.JsonPrimitive(0))
            put("memory", kotlinx.serialization.json.JsonArray(emptyList()))
        }.toString()
        val raw = kotlinx.serialization.json.JsonObject(
            mapOf("role" to kotlinx.serialization.json.JsonPrimitive("assistant"), "content" to kotlinx.serialization.json.JsonPrimitive(json), "reasoning_content" to kotlinx.serialization.json.JsonPrimitive(reasoning)),
        )
        return app.agency.core.llm.ChatResult.Ok(json, emptyList(), app.agency.core.llm.Turn.Assistant(app.agency.core.llm.Wire.OPENAI, raw, json, emptyList()), null)
    }

    private fun call(name: String, args: String): app.agency.core.llm.ChatResult {
        val c = app.agency.core.llm.ToolCall("call-$name", name, args)
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"role":"assistant","content":"","reasoning_content":"查一下","tool_calls":[{"id":"call-$name","type":"function","function":{"name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(args)}}}]}""",
        )
        return app.agency.core.llm.ChatResult.Ok(null, listOf(c), app.agency.core.llm.Turn.Assistant(app.agency.core.llm.Wire.OPENAI, raw, null, listOf(c)), null)
    }

    private fun t(hhmm: String): Long = monday.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant().toEpochMilli()
    private fun at(hhmm: String) { clock.now = Instant.ofEpochMilli(t(hhmm)) }

    /** He types [text] at [hhmm]; the company's lines back. */
    private fun say(hhmm: String, text: String): List<String> {
        at(hhmm)
        return runBlocking { brain.onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    private fun signal(hhmm: String, s: (Long) -> Signal) = runBlocking { at(hhmm); brain.signal(s(t(hhmm))) }
    private fun state(): DayState = runBlocking { brain.nerve.state(monday) }
    private fun plan(): DayPlan = runBlocking { brain.nerve.plan(monday) }
    private fun effects() = runBlocking { store.nerve.effects(monday.toString()) }
    private fun tick(hhmm: String): Tick = runBlocking { at(hhmm); brain.tick() }

    @Test fun aClassDayWithALateWakeAFeverARideAndAnEarlyNight() {
        // 07:00 the morning alarm: the reminders are running (nothing is due yet).
        assertTrue(tick("07:00").posts.isEmpty())
        // 10:00, 90 minutes late: lunch follows (13:40), sleep stays; shaving is due at sign-in; the day starts (17:40 water).
        val wake = say("10:00", "簽到")
        assertEquals("簽到了，比表定晚 90 分鐘。", wake.first())
        assertEquals("13:40", plan().slot(SlotKey.LUNCH)!!.time)
        assertEquals("02:30", plan().slot(SlotKey.SIGN_OUT)!!.time)
        assertEquals(listOf("記下：刮鬍子。"), say("10:05", "刮好了"))

        // 11:00 a fever: one today-only proposal (code decides); 11:01 his 「好」 applies it to today only.
        val fever = say("11:00", "我好像發燒了")
        assertTrue(fever.single(), fever.single().startsWith("先照顧身體。發燒的話"))
        assertEquals(1, runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.size)
        assertEquals(listOf("好，只改今天：今天核心和肌力先停，伸展照做十分鐘。不用明天補做，好好休息。"), say("11:01", "好"))
        assertEquals(Reduction.SICK, plan().reduction)
        assertNull(plan().slot(SlotKey.STRENGTH))
        assertNull(plan().slot(SlotKey.CORE))
        assertTrue(plan().slot(SlotKey.STRETCH)!!.required)
        // Asking to skip the stretch as well: the stretch is the minimum of a sick day.
        assertEquals(listOf("今天已經調整過了：伸展照做，這是最少的。"), say("12:00", "伸展也可以不要做嗎"))

        // 12:30 a chat report: the clerk, then the manager's loop (a tool, then a guarded reply in the gentle voice of a
        // sick day). Her thinking is kept for the next round, never shown.
        assertEquals(listOf("收到。上課專心，下課回報。"), say("12:30", "姐姐，我到學校了，下午有課"))
        val chat = runBlocking { store.messages.recent(100) }.last()
        assertEquals(app.agency.core.model.WordedBy.MODEL, chat.wordedBy)
        assertEquals("他在學校", runBlocking { store.thinking.of(listOf(chat.id)) }.single().payload)
        assertTrue(llm.requests[1].system.contains("【這一次的語氣：放軟】"))
        // 12:40 an attempt to change the rules in chat: the fixed reply, no model, nothing written.
        assertEquals(listOf(app.agency.core.rules.RulesBook.LOCKED), say("12:40", "每天熱量可以調高嗎"))
        assertEquals(3, llm.requests.size)

        // The alarm fires (T13): what was due goes out (the shave reminder), lunch is pushed at 13:40 and chased every
        // 30 minutes; a fire in between posts nothing; the same minute twice counts once.
        val shave = tick("10:06")
        assertEquals(listOf("刮鬍子（每兩天一次），刮好說「刮好了」。"), shave.posts.map { it.text })
        assertEquals(Fire(Instant.ofEpochMilli(t("13:40")), FireKind.SLOT), shave.next)
        val push = tick("13:40").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("午餐時間到了，做完回報。", push.text)
        assertEquals(PostChannel.SCHEDULE, push.channel)
        assertTrue(push.alert)
        assertTrue(tick("13:55").posts.isEmpty())
        assertEquals("還缺：午餐。", tick("14:10").posts.single().text)
        assertTrue(tick("14:10").posts.isEmpty())

        // 17:40 the water reminder before the ride; dinner (17:15) is missing too by then.
        val pre = tick("17:40")
        assertEquals(setOf("preride", Nudger.GAP_KEY), pre.posts.map { it.key }.toSet())
        assertEquals("還缺：午餐、晚餐。", pre.posts.single { it.key == Nudger.GAP_KEY }.text)

        // 18:05 out on the run: reminders sound once; awake 8 hours after a night we do not know: no tired-riding warning.
        val out = say("18:05", "出門跑單")
        assertEquals(listOf("出門了。騎車小心，提醒只響一聲。21:00 記得吃雞胸。"), out)
        assertTrue(state().riding)
        assertTrue(DayFlag.RIDING_ONE_SOUND in state().flags)
        val ride = tick("21:00").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals(PostChannel.RIDING, ride.channel)
        assertTrue(ride.alert)
        // 21:05 the on-run portion (the meal feature writes the record and emits the signal from T14 on).
        runBlocking { store.meals.insert(app.agency.core.model.Meal("meal-run", monday.toString(), t("21:05"), slotKey = "RIDE_MEAL", text = "雞胸")) }
        signal("21:05") { Signal.MealLogged("meal-run", it, SlotKey.RIDE_MEAL, 310, 40.0) }
        val silent = tick("21:35").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("還缺：午餐、晚餐。", silent.text)
        assertFalse(silent.alert) // the second push while riding is silent

        // 23:30 home: no 宵夜 tonight (L9), then the floor check (L6): 310 is under 1200.
        assertEquals(listOf("收工了。"), say("23:30", "收工"))
        assertTrue(DayFlag.LATE_SNACK_OFF in state().flags)
        assertFalse(state().riding)

        // Home: the floor reminder goes out now (it was held during the ride).
        assertTrue(tick("23:31").posts.any { it.key == "floor" && it.text == "今天才吃 310 大卡，不到 1200。要吃夠，不准少吃；吃了拍照回報。" })

        // 23:40 a 討論 about the morning: one question at a time (a reply with two went back), chips under it.
        val open = say("23:40", "這裡有問題")
        assertEquals(listOf("說說看。\n是早上起床太趕，還是晚上太晚睡？"), open)
        assertTrue(llm.requests[4].turns.last().let { it is app.agency.core.llm.Turn.User && it.text.contains("上一個輸出不合格") })
        assertEquals(listOf("早上太趕", "晚上太晚"), runBlocking { store.messages.recent(1) }.single().refs.options)
        // 23:41 his answer: she proposes moving tomorrow's wake; nothing changes until his yes.
        assertEquals(listOf("那上課日早十分鐘起床。你說好才改。\n這樣可以嗎？"), say("23:41", "我想早一點起來吃早餐再去上課"))
        assertTrue(llm.requests[6].system.contains("【模式：討論】"))
        assertEquals("08:30", runBlocking { brain.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.WAKE)!!.time)
        assertEquals(1, runBlocking { brain.pendingProposals() }.size)
        // 23:42 his 「好」: applied by code, a new version of the schedule; the discussion is over.
        assertEquals(listOf("好，改了：上課日起床 08:30 → 08:20。"), say("23:42", "好"))
        assertEquals("08:20", runBlocking { brain.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.WAKE)!!.time)
        assertEquals(9, llm.requests.size) // the whole script, nothing more

        // 01:00 to bed early (fine): signed out; nothing more until tomorrow's wake slot.
        assertEquals(listOf("簽退了。晚安，明天見。"), say("01:00", "我要睡了"))
        val night = tick("01:30")
        assertTrue(night.posts.isEmpty())
        // Tomorrow's wake is the new 08:20 (the change he said yes to).
        assertEquals(Fire(LocalDate.parse("2026-12-08").atStartOfDay(AgencyClock.TAIPEI).plusHours(8).plusMinutes(20).toInstant(), FireKind.SLOT), night.next)

        // The end of the day.
        val st = state()
        assertEquals(t("10:00"), st.wokeAt)
        assertEquals(t("01:00"), st.signedOutAt)
        assertEquals(IllKind.SICK, st.ill)
        assertTrue(st.gentle)
        assertEquals(310, st.kcal)
        val byLink = effects().groupBy { it.link }
        assertEquals(setOf("L1", "L2", "L3", "L3b", "L8", "L9", "L6", "L12", "L17"), byLink.keys)
        assertTrue(effects().none { it.kind == EffectKind.NOTIFY && it.status == EffectStatus.PENDING })
        val nudges = runBlocking { store.nudges.between(0, Long.MAX_VALUE) }.map { it.text }
        assertEquals(
            listOf(
                "刮鬍子（每兩天一次），刮好說「刮好了」。", "午餐時間到了，做完回報。", "還缺：午餐。", "還缺：午餐、晚餐。",
                "快出門了：出門前先喝水，路上只能小口喝。", "還缺：午餐、晚餐、跑單途中的雞胸。", "還缺：午餐、晚餐。",
                "還缺：午餐、晚餐。", // 23:31: still chased (home now, so it sounds again)
                "今天才吃 310 大卡，不到 1200。要吃夠，不准少吃；吃了拍照回報。",
            ),
            nudges,
        )
        assertEquals(
            listOf(app.agency.core.model.ProposalKind.TODAY_CHANGE, app.agency.core.model.ProposalKind.PLAN_CHANGE),
            runBlocking { store.proposals.withStatus(ProposalStatus.ACCEPTED) }.sortedBy { it.createdAt }.map { it.kind },
        )
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.isEmpty())
        // 公司規章 were never touched; the model was asked only for the conversation, and her thinking never shows.
        assertNull(runBlocking { store.rules.current() })
        assertTrue(runBlocking { store.rules.history() }.isEmpty())
        assertEquals(
            listOf("INTAKE", "CHAT", "CHAT", "CHAT", "CHAT", "INTAKE", "CHAT", "CHAT", "JUDGE"),
            llm.requests.map { it.purpose.name },
        )
        assertTrue(runBlocking { store.messages.recent(200) }.none { it.text.contains("他在學校") || it.text.contains("查一下") })
        val lines = runBlocking { store.messages.recent(100) }.filter { it.who == Who.COMPANY }.map(Message::text)
        assertTrue(lines.any { it.startsWith("先照顧身體。發燒的話，今天的訓練我提一個調整：今天核心和肌力先停，伸展照做十分鐘。你說「好」才改") })
        // Tomorrow is a normal day again.
        assertTrue(runBlocking { brain.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.STRENGTH) != null)
    }
}
