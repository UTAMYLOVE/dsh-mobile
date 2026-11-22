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
import app.agency.core.model.RoleKey
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
 * a fever and says yes to the today-only change, rides (one sound), gets home and signs out (quiet until tomorrow).
 * Batch 3 (T12) adds conversation to the same day: a chat report the manager answers with a tool, an attempt to change the
 * rules (fixed reply, no model), and a 討論 at night (one question at a time; the plan changes only after his 「好」, and
 * tomorrow's wake follows it). Batch 4 (T14–T17) adds the meals and the water: lunch with friends (the body teacher's one
 * pick as an order card, his 好, the report, its photo), dinner made smaller by what lunch left (L5) after the pre-meal
 * suggestion, a violation at dinner (L13, and the judge on for the rest of the day), the 21:00 portion on the run with its
 * photo, and the bottle: half by the afternoon (no 15:00 reminder) and finished before he leaves (no pre-ride reminder).
 * At the end: the day state, the plan, the proposals, the reminders and the lines. Later batches add training and the
 * bedtime review to this same day, and the emulator plays it last.
 */
class WholeDayScenarioTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    // The model is asked only for the conversation (T12): the clerk and the manager's tool loop; every other line is code's.
    private val llm = ScriptedLlm(
        // 12:30 his report: the clerk, a get_today call, then the reply (with its thinking).
        intake("chat"), call("get_today", "{}"), reply("收到。上課專心，下課回報。", reasoning = "他在學校"),
        // 13:10 lunch with friends: the body teacher's pick as an order card (no clerk), then the judge (it is about food).
        call("propose_meal_order", """{"slot":"lunch","items":[{"name":"清燉牛肉麵","portion":1,"kcal_high":520,"protein_low":30},{"name":"燙青菜","portion":1,"kcal_high":80,"protein_low":2}],"place_result_id":null}"""),
        reply("點清燉牛肉麵加燙青菜，湯不要喝完。你按好就是這個。"), judge(),
        // 13:45 (he had an egg more than the card said), 17:20, 21:05 his meal reports: the clerk only (the rest is code).
        meal(item("清燉牛肉麵", 520, 30), item("燙青菜", 80, 2), item("滷蛋", 70, 6)),
        meal(item("炸雞排", 500, 30, fried = true)),
        meal(item("即食雞胸", 130, 20), item("統一高纖豆漿", 200, 15)),
        // 23:40 his 「這裡有問題」: a reply with two questions goes back once, then one question with options; the judge reads it
        // (there was a violation today).
        reply("哪裡有問題？是起床還是睡覺？", ask = "是早上嗎？"),
        reply("說說看。", ask = "是早上起床太趕，還是晚上太晚睡？", options = listOf("早上太趕", "晚上太晚")), judge(),
        // 23:41 his answer: the clerk, a plan change proposal, the reply asking for his yes, and the judge.
        intake("chat"), call("propose_change", """{"section":"schedule","ops":[{"path":"class_wake","value":"08:20"}],"reason":"早十分鐘起來吃早餐"}"""),
        reply("那上課日早十分鐘起床。你說好才改。", ask = "這樣可以嗎？", options = listOf("可以", "再想想")), judge(),
    )
    private var n = 0
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private val monday = LocalDate.parse("2026-12-07")

    init {
        // 入社建檔 is done (reminders start after it).
        runBlocking { store.profile.put(app.agency.core.model.Profile(onboardingDone = true)) }
    }

    private fun intake(kind: String) = ScriptedLlm.ok("""{"kind":"$kind"}""")
    private fun judge() = ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}""")
    private fun item(name: String, high: Int, protein: Int, fried: Boolean = false) =
        """{"name":"$name","portion":1,"fried":$fried,"processed":false,"zero_cal":false,"kcal_low":${high - 100},"kcal_high":$high,"protein_low":$protein,"protein_high":$protein}"""
    private fun meal(vararg items: String) = ScriptedLlm.ok("""{"kind":"meal","items":[${items.joinToString(",")}]}""")
    private fun photo(hhmm: String, id: String): String = runBlocking {
        at(hhmm)
        brain.attachMealPhoto(app.agency.core.model.Media(id, app.agency.core.model.MediaKind.PHOTO, app.agency.core.model.MediaPurpose.MEAL, "image/jpeg", 10, "00", t(hhmm))).text
    }

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
        // 10:00, 90 minutes late: lunch follows (13:40), sleep stays; shaving is due at sign-in.
        val wake = say("10:00", "簽到")
        assertEquals("簽到了，比表定晚 90 分鐘。", wake.first())
        assertEquals("13:40", plan().slot(SlotKey.LUNCH)!!.time)
        assertEquals("02:30", plan().slot(SlotKey.SIGN_OUT)!!.time)
        assertEquals(listOf("記下：刮鬍子。"), say("10:05", "刮好了"))
        // 10:30 the first half of the bottle (his target these first two weeks: one bottle).
        assertEquals(listOf("記下：喝了半壺。今天 半壺，目標 1 壺。\n出門跑單前還要喝 半壺，分幾次喝，不要一次灌。"), say("10:30", "喝了半壺"))

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

        // The alarm (T13): what was due goes out (the shave reminder); the next fire is the moved lunch.
        val shave = tick("10:06")
        assertEquals(listOf("刮鬍子（每兩天一次），刮好說「刮好了」。"), shave.posts.map { it.text })
        assertEquals(Fire(Instant.ofEpochMilli(t("13:40")), FireKind.SLOT), shave.next)

        // 13:10 lunch with friends (T16): no lunch menu is planned on a class day; the body teacher picks one order there, as a
        // card, never 「別跟朋友吃」; his 好 settles it.
        val friends = runBlocking { at("13:10"); brain.onText("中午跟朋友吃牛肉麵") }.filter { it.who == Who.COMPANY }.single()
        assertEquals(RoleKey.BODY, friends.role)
        assertEquals("點清燉牛肉麵加燙青菜，湯不要喝完。你按好就是這個。", friends.text)
        assertTrue(llm.requests[3].system.contains("他要跟朋友去吃「牛肉麵」"))
        // 572 + 88 = 660 fits what lunch may be today (1700 less a usual dinner and the 21:00 portion: 676).
        assertEquals(listOf("好，這餐就點這個：清燉牛肉麵、燙青菜，660 大卡。吃完拍照回報。"), say("13:11", "好"))
        // 13:40 the moved lunch is pushed; 13:45 his report (the clerk sorts it, code counts it: strict estimates; he had an
        // egg more), the order is eaten, and dinner gets what is left after the 21:00 portion (L5); 13:46 its photo.
        assertEquals("午餐時間到了，做完回報。", tick("13:40").posts.single { it.key == Nudger.GAP_KEY }.text)
        val lunch = say("13:45", "吃了清燉牛肉麵、燙青菜，還多吃一顆滷蛋").single().lines()
        assertEquals("記在午餐。", lunch[0])
        assertEquals("記下：清燉牛肉麵 1份 572 大卡（估算，取高值加一成）。", lunch[1])
        assertEquals("記下：燙青菜 1份 88 大卡（估算，取高值加一成）。", lunch[2])
        assertEquals("記下：滷蛋 1份 77 大卡（估算，取高值加一成）。", lunch[3])
        assertTrue(lunch.contains("今天吃了 737 大卡，剩 963 大卡，蛋白質還差 102 克。"))
        assertEquals("因為今天到現在吃了 737 大卡，所以晚餐建議 653 大卡以內、蛋白質至少 51 克；照樣要吃，不能跳過。", lunch.last())
        assertEquals(653, plan().budgets[SlotKey.DINNER])
        assertEquals(app.agency.core.model.OrderStatus.EATEN, runBlocking { store.orders.onDay(monday.toString()) }.single().status)
        assertEquals("照片收到，午餐已證實。", photo("13:46", "photo-lunch"))
        assertTrue(tick("14:10").posts.isEmpty())
        // 15:00 the water check (L10): half of the bottle is in, so there is nothing to say.
        assertTrue(tick("15:00").posts.isEmpty())

        // 16:45 the body teacher's suggestion for dinner, sized by L5 (code's numbers; the model may only word it).
        val pre = tick("16:45").posts.single()
        assertEquals("premeal", pre.key)
        assertTrue(pre.text, pre.text.startsWith("晚餐 17:15 快到了：今天建議 653 大卡以內、蛋白質至少 51 克。"))
        // 17:20 dinner is fried chicken: a breach (the body teacher says what, the manager records it coolly, L13).
        val dinner = say("17:20", "晚餐吃了炸雞排")
        assertTrue(dinner[0], dinner[0].contains("炸雞排是炸物，休假餐以外不准吃。"))
        assertEquals("記一次違規：休假餐以外吃了禁止的東西。今天第 1 次，會算進評鑑。下一餐照計畫。", dinner[1])
        assertTrue(dinner.none { it.contains("少吃") })
        assertEquals("照片收到，晚餐已證實。", photo("17:21", "photo-dinner"))
        // 17:30 the rest of the bottle, before he leaves: done, so the 17:40 pre-ride reminder has nothing to say.
        assertTrue(say("17:30", "喝了半壺").single().endsWith("今天的水達標了。"))
        assertTrue(tick("17:40").posts.isEmpty())

        // 18:05 out on the run: reminders sound once; awake 8 hours after a night we do not know: no tired-riding warning.
        val out = say("18:05", "出門跑單")
        assertEquals(listOf("出門了。騎車小心，提醒只響一聲。21:00 記得吃雞胸。"), out)
        assertTrue(state().riding)
        assertTrue(DayFlag.RIDING_ONE_SOUND in state().flags)
        val ride = tick("21:00").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("還缺：跑單途中的雞胸。", ride.text)
        assertEquals(PostChannel.RIDING, ride.channel)
        assertTrue(ride.alert)
        // 21:05 the on-run portion, reported and photographed on the way: never a breach.
        val run = say("21:05", "跑單路上吃了即食雞胸和統一高纖豆漿").single()
        assertTrue(run, run.startsWith("記在跑單途中的雞胸。"))
        assertTrue(run.contains("今天吃了 1597 大卡，剩 103 大卡"))
        assertEquals("照片收到，跑單途中的雞胸已證實。", photo("21:06", "photo-run"))
        assertTrue(tick("21:35").posts.isEmpty())

        // 23:30 home: no 宵夜 tonight (L9), then the floor check (L6): 1597 is above 1200, nothing to remind.
        assertEquals(listOf("收工了。"), say("23:30", "收工"))
        assertTrue(DayFlag.LATE_SNACK_OFF in state().flags)
        assertFalse(state().riding)
        assertTrue(tick("23:31").posts.isEmpty())

        // 23:40 a 討論 about the morning: one question at a time (a reply with two went back), chips under it; the judge reads
        // the reply (a violation today).
        val open = say("23:40", "這裡有問題")
        assertEquals(listOf("說說看。\n是早上起床太趕，還是晚上太晚睡？"), open)
        assertTrue(llm.requests[10].turns.last().let { it is app.agency.core.llm.Turn.User && it.text.contains("上一個輸出不合格") })
        assertEquals(app.agency.core.llm.Purpose.JUDGE, llm.requests[11].purpose)
        assertEquals(listOf("早上太趕", "晚上太晚"), runBlocking { store.messages.recent(1) }.single().refs.options)
        // 23:41 his answer: she proposes moving tomorrow's wake; nothing changes until his yes.
        assertEquals(listOf("那上課日早十分鐘起床。你說好才改。\n這樣可以嗎？"), say("23:41", "我想早一點起來吃早餐再去上課"))
        assertTrue(llm.requests[13].system.contains("【模式：討論】"))
        assertEquals("08:30", runBlocking { brain.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.WAKE)!!.time)
        assertEquals(1, runBlocking { brain.pendingProposals() }.size)
        // 23:42 his 「好」: applied by code, a new version of the schedule; the discussion is over.
        assertEquals(listOf("好，改了：上課日起床 08:30 → 08:20。"), say("23:42", "好"))
        assertEquals("08:20", runBlocking { brain.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.WAKE)!!.time)
        assertEquals(16, llm.requests.size) // the whole script, nothing more

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
        assertEquals(1597, st.kcal)
        assertEquals(1, st.breaches)
        assertEquals(2, st.waterHalves)
        assertEquals(setOf(SlotKey.LUNCH, SlotKey.DINNER, SlotKey.RIDE_MEAL), st.mealSlots)
        val meals = runBlocking { store.meals.onDay(monday.toString()) }
        assertEquals(listOf("LUNCH", "DINNER", "RIDE_MEAL"), meals.map { it.slotKey })
        assertTrue(meals.all { it.verified })
        assertEquals(listOf(emptyList(), listOf("PROCESSED"), emptyList()), meals.map { it.breaches })
        assertTrue(runBlocking { brain.card() }.let { it.unverified.isEmpty() && it.waterHalves == 2 && it.waterTargetHalves == 2 && it.breachesToday == 1 })
        val byLink = effects().groupBy { it.link }
        assertEquals(setOf("L1", "L2", "L3", "L3b", "L5", "L8", "L9", "L10", "L12", "L13", "L17"), byLink.keys)
        assertTrue(effects().none { it.kind == EffectKind.NOTIFY && it.status == EffectStatus.PENDING })
        val nudges = runBlocking { store.nudges.between(0, Long.MAX_VALUE) }.map { it.text }
        assertEquals(4, nudges.size)
        assertEquals("刮鬍子（每兩天一次），刮好說「刮好了」。", nudges[0])
        assertEquals("午餐時間到了，做完回報。", nudges[1])
        assertTrue(nudges[2].startsWith("晚餐 17:15 快到了"))
        assertEquals("還缺：跑單途中的雞胸。", nudges[3])
        assertEquals(
            listOf(app.agency.core.model.ProposalKind.TODAY_CHANGE, app.agency.core.model.ProposalKind.MEAL_ORDER, app.agency.core.model.ProposalKind.PLAN_CHANGE),
            runBlocking { store.proposals.withStatus(ProposalStatus.ACCEPTED) }.sortedBy { it.createdAt }.map { it.kind },
        )
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.isEmpty())
        // 公司規章 were never touched; the model was asked only for the conversation and the sorting of his words, and her
        // thinking never shows.
        assertNull(runBlocking { store.rules.current() })
        assertTrue(runBlocking { store.rules.history() }.isEmpty())
        assertEquals(
            listOf("INTAKE", "CHAT", "CHAT", "CHAT", "CHAT", "JUDGE", "INTAKE", "INTAKE", "INTAKE", "CHAT", "CHAT", "JUDGE", "INTAKE", "CHAT", "CHAT", "JUDGE"),
            llm.requests.map { it.purpose.name },
        )
        assertTrue(runBlocking { store.messages.recent(300) }.none { it.text.contains("他在學校") || it.text.contains("查一下") })
        val lines = runBlocking { store.messages.recent(200) }.filter { it.who == Who.COMPANY }.map(Message::text)
        assertTrue(lines.any { it.startsWith("先照顧身體。發燒的話，今天的訓練我提一個調整：今天核心和肌力先停，伸展照做十分鐘。你說「好」才改") })
        // No photo, media id or file ever went into a prompt.
        assertTrue(llm.requests.none { r -> r.system.contains("photo-") || r.turns.any { it.toString().contains("photo-") } })
        // Tomorrow is a normal day again.
        assertTrue(runBlocking { brain.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.STRENGTH) != null)
    }
}
