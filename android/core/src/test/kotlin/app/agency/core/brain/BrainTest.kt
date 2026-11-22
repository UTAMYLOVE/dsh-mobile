package app.agency.core.brain

import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import app.agency.core.metering.MutableClock
import app.agency.core.guard.SecondPassJudge
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Profile
import app.agency.core.model.Message
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.persona.Roles
import app.agency.core.engine.Tone
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock(Instant.parse("2026-11-23T04:00:00Z")) // 12:00 in Taipei
    private var n = 0

    private fun brain(llm: ScriptedLlm) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-${++n}" }

    private fun intake(kind: String) = ScriptedLlm.ok("""{"kind":"$kind"}""")
    private fun utter(text: String) = ScriptedLlm.ok("""{"text":"$text","ask":null,"options":[],"recommended":null,"memory":[]}""")
    private fun ask(text: String, q: String) = ScriptedLlm.ok("""{"text":"$text","ask":"$q","options":[],"recommended":null,"memory":[]}""")

    private fun stored(): List<Message> = runBlocking { store.messages.recent(100) }

    @Test fun aCommandWordIsStoredAndAnsweredByCodeWithoutAModel() {
        val llm = ScriptedLlm()
        val out = runBlocking { brain(llm).onText(" 喝完一壺！") }
        assertTrue(llm.requests.isEmpty())
        assertEquals(listOf(Who.OWNER, Who.COMPANY), stored().map { it.who })
        assertEquals("喝完一壺！", out[0].text)
        assertEquals(Router.NOT_YET, out[1].text)
        assertEquals(RoleKey.BODY, out[1].role)
        assertEquals(WordedBy.TEMPLATE, out[1].wordedBy)
    }

    @Test fun aRecordKindTheClerkFindsGetsTheNotYetLineFromItsRole() {
        val llm = ScriptedLlm(intake("meal"))
        val out = runBlocking { brain(llm).onText("中午吃了雞腿便當") }
        assertEquals(1, llm.requests.size)
        assertEquals(Purpose.INTAKE, llm.requests[0].purpose)
        assertEquals(Router.NOT_YET, out[1].text)
        assertEquals(RoleKey.BODY, out[1].role)
        assertEquals("meal", out[1].kind)
    }

    @Test fun chatGoesToTheManagersLoopAndOnlyTheGuardedReplyIsStored() {
        val llm = ScriptedLlm(intake("chat"), utter("你今天還能吃 1234 大卡。現在去吃午餐。"), judgeOk())
        val b = brain(llm)
        val out = runBlocking { b.onText("姐姐在嗎") }
        val reply = out[1]
        assertEquals(RoleKey.MANAGER, reply.role)
        assertEquals(WordedBy.MODEL, reply.wordedBy)
        assertEquals("現在去吃午餐。", reply.text)
        assertFalse(stored().any { it.text.contains("1234") })
        val chat = llm.requests[1]
        assertEquals(Purpose.CHAT, chat.purpose)
        assertEquals(
            listOf("get_today", "query_records", "lookup_food", "compute", "search_places", "get_plan", "propose_change", "propose_meal_order", "save_place", "remember"),
            chat.tools.map { it.name },
        )
        assertTrue(chat.system.contains("【你的角色：經紀人】"))
        assertNull(b.lastFail.value)
        assertFalse(b.busy.value)
    }

    @Test fun aFoodTableNameIsEvidenceForTheGuard() {
        runBlocking { store.food.insert(FoodEntry("悟饕池上便當", emptyList(), 760, 30.0, null, null, false, false, false, 0)) }
        val llm = ScriptedLlm(intake("chat"), utter("晚餐可以吃悟饕池上便當。吃完拍給我。"), judgeOk())
        val out = runBlocking { brain(llm).onText("晚餐吃什麼好") }
        assertEquals("晚餐可以吃悟饕池上便當。吃完拍給我。", out[1].text)
    }

    @Test fun legacyLinesGoToTheModelWithTheirRoleLabels() {
        runBlocking {
            store.messages.insert(Message("old-1", 1, Who.OWNER, text = "早安", legacy = true))
            store.messages.insert(Message("old-2", 2, Who.COMPANY, role = RoleKey.BODY, text = "記得吃早餐。", legacy = true))
        }
        val llm = ScriptedLlm(intake("chat"), utter("去吃早餐。"), judgeOk())
        runBlocking { brain(llm).onText("好") }
        val turns = llm.requests[1].turns
        assertEquals(Turn.User("早安"), turns[0])
        assertEquals(Turn.AssistantText("【體態管理老師】記得吃早餐。"), turns[1])
    }

    @Test fun noAnswerFromTheProviderIsTheTemplateAndSaysWhy() {
        val llm = ScriptedLlm(ScriptedLlm.failed(Fail.KEY_REJECTED))
        val b = brain(llm)
        // Low mood (not illness: that is answered by code, agency-links §3b) is the manager's, in the gentle voice.
        val out = runBlocking { b.onText("我今天心情很差") }
        assertEquals(1, llm.requests.size)
        assertEquals(Roles.template(RoleKey.MANAGER, Tone.GENTLE) + Brain.KEY_REJECTED, out[1].text)
        assertEquals(WordedBy.TEMPLATE, out[1].wordedBy)
        assertEquals(Fail.KEY_REJECTED, b.lastFail.value)
        assertEquals(2, stored().size)
    }

    @Test fun theLoopFailingAfterTheClerkAlsoSaysWhy() {
        val llm = ScriptedLlm(intake("mood"), ScriptedLlm.failed(Fail.SERVER))
        val out = runBlocking { brain(llm).onText("今天心情不好") }
        assertEquals(Roles.template(RoleKey.MANAGER, Tone.GENTLE) + Brain.failLine(Fail.SERVER), out[1].text)
    }

    @Test fun blankTextWritesNothing() {
        val llm = ScriptedLlm()
        assertTrue(runBlocking { brain(llm).onText("   ") }.isEmpty())
        assertTrue(stored().isEmpty())
    }

    @Test fun everyFixedLineIsTraditionalChineseWithoutLatin() {
        val lines = Fail.entries.map(Brain::failLine) + Router.NOT_YET
        assertTrue(lines.none { Regex("[A-Za-z]").containsMatchIn(it) })
    }

    @Test fun theSeedTableIsEvidenceOnAFreshInstall() {
        val llm = ScriptedLlm(intake("chat"), utter("早餐可以吃全家 紐奧良風味烤雞鮮蔬三明治。吃完拍給我。"), judgeOk())
        val out = runBlocking { brain(llm).onText("早餐吃什麼") }
        assertEquals("早餐可以吃全家 紐奧良風味烤雞鮮蔬三明治。吃完拍給我。", out[1].text)
    }

    @Test fun theClerkGetsItsOwnClientAndOnlyTheLastFourLines() {
        runBlocking { (1..9).forEach { i -> store.messages.insert(Message("h$i", i.toLong(), if (i % 2 == 1) Who.OWNER else Who.COMPANY, role = RoleKey.MANAGER.takeIf { i % 2 == 0 }, text = "第${i}句")) } }
        val clerk = ScriptedLlm(intake("chat"))
        val llm = ScriptedLlm(utter("去喝水。"))
        val b = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock), intakeLlm = clerk) { "id-${++n}" }
        runBlocking { b.onText("好") }
        assertEquals(listOf(Purpose.INTAKE), clerk.requests.map { it.purpose })
        assertEquals(listOf(Purpose.CHAT), llm.requests.map { it.purpose })
        val clerkText = clerk.requests[0].turns.joinToString("\n") { (it as? Turn.User)?.text ?: (it as Turn.AssistantText).text }
        assertTrue(clerkText, clerkText.contains("第7句") && clerkText.contains("第9句"))
        assertFalse(clerkText, clerkText.contains("第5句"))
        // The manager still sees the whole window.
        assertTrue(llm.requests[0].turns.any { (it as? Turn.User)?.text?.contains("第1句") == true })
    }

    @Test fun memoryIsAskedForNowThatItIsApplied() {
        val llm = ScriptedLlm(intake("chat"), utter("去喝水。"))
        runBlocking { brain(llm).onText("記得我不吃香菜") }
        val system = llm.requests[1].system
        assertTrue(system.contains("memory 只放"))
        assertFalse(system.contains("memory 永遠給空陣列"))
    }

    @Test fun aReplyThatNeverPassesSaysTheModelDidNotAnswerWell() {
        val llm = ScriptedLlm(intake("chat"), ScriptedLlm.failed(Fail.REFUSED))
        val out = runBlocking { brain(llm).onText("在嗎") }
        assertTrue(out[1].text.endsWith("模型這次沒有好好回答，再說一次試試。"))
    }

    private fun judgeOk() = ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}""")

    /** A meal of [kcal] today (coach day of the test clock), with [breaches]. */
    private fun eat(kcal: Int, breaches: List<String> = emptyList()) = runBlocking {
        val day = SnapshotReader(store, AgencyClock.of(clock)).today(Profile()).toString()
        store.meals.insert(
            Meal("m-$kcal", day, clock.millis() - 60_000, text = "便當", breaches = breaches,
                items = listOf(MealItem("雞腿便當", 1.0, kcal, 30.0, null, null, true, KcalSource.ESTIMATE))),
        )
    }

    private fun withClerk(clerk: ScriptedLlm, llm: ScriptedLlm) =
        Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock), intakeLlm = clerk) { "id-${++n}" }

    @Test fun onAnOverrunDayTheGuardedReplyGoesToTheJudgeOnTheCheapClientWithOnlyTheReplyAndOneLine() {
        eat(2080) // quota 1700: 380 over
        runBlocking { store.messages.insert(Message("h1", 1, Who.OWNER, text = "昨天的秘密話")) }
        val clerk = ScriptedLlm(intake("chat"), judgeOk())
        val llm = ScriptedLlm(utter("晚餐照平常吃。"))
        val out = runBlocking { withClerk(clerk, llm).onText("我中午吃太多了") }
        assertEquals("晚餐照平常吃。", out[1].text)
        assertEquals(WordedBy.MODEL, out[1].wordedBy)
        assertEquals(listOf(Purpose.INTAKE, Purpose.JUDGE), clerk.requests.map { it.purpose })
        val req = clerk.requests[1]
        assertEquals(SecondPassJudge.SYSTEM, req.system)
        assertEquals(listOf(Turn.User(SecondPassJudge.userText("晚餐照平常吃。", "今天超過 380 大卡"))), req.turns)
        // Nothing of his: not his message, not the history, not the facts.
        val all = req.system + (req.turns.single() as Turn.User).text
        listOf("吃太多", "秘密話", "雞腿便當", "quota", "2080", "1700").forEach { assertFalse(it, all.contains(it)) }
    }

    @Test fun aBreachTheJudgeFindsIsRewrittenBeforeAnythingIsStored() {
        eat(600, breaches = listOf("fried"))
        val clerk = ScriptedLlm(
            intake("chat"),
            ScriptedLlm.ok("""{"violation":true,"class":"compensation","reason":"叫他晚餐不吃"}"""),
            judgeOk(),
        )
        val llm = ScriptedLlm(utter("晚餐就省了吧。"), utter("晚餐照計畫吃。"))
        val out = runBlocking { withClerk(clerk, llm).onText("中午吃了炸雞") }
        assertEquals("晚餐照計畫吃。", out[1].text)
        assertEquals(2, llm.requests.size)
        assertEquals(listOf(Purpose.INTAKE, Purpose.JUDGE, Purpose.JUDGE), clerk.requests.map { it.purpose })
        assertTrue(clerk.requests[1].turns.single().let { it is Turn.User && it.text.startsWith("今天的情況：今天有違規\n") })
        assertFalse(stored().any { it.text.contains("省了") })
    }

    @Test fun anOrdinaryDayAndChatWithoutFoodNeverCallTheJudge() {
        eat(500)
        val clerk = ScriptedLlm(intake("chat"))
        val llm = ScriptedLlm(utter("早點睡。"))
        runBlocking { withClerk(clerk, llm).onText("姐姐在嗎") }
        assertEquals(listOf(Purpose.INTAKE), clerk.requests.map { it.purpose })

        val other = ScriptedLlm(intake("discuss"))
        runBlocking { withClerk(other, ScriptedLlm(ask("好，先弄清楚。", "是哪一天不方便？"))).onText("核心可以改到晚上嗎") }
        assertEquals(listOf(Purpose.INTAKE), other.requests.map { it.purpose })
    }

    @Test fun foodTalkInAnyChatKindCallsTheJudgeOnACleanDay() {
        // No meal today, no breach: only the conversation is about food. 「chat」, 「mood」 and 「discuss」 all count.
        for (kind in listOf("chat", "mood", "discuss")) {
            val clerk = ScriptedLlm(intake(kind), judgeOk())
            val discuss = kind == "discuss"
            val llm = ScriptedLlm(if (discuss) ask("今天照計畫走。", "是哪一餐？") else utter("今天照計畫走。"))
            runBlocking { withClerk(clerk, llm).onText("昨天宵夜吃太多今天怎麼辦") }
            assertEquals(kind, listOf(Purpose.INTAKE, Purpose.JUDGE), clerk.requests.map { it.purpose })
            val shown = if (discuss) "今天照計畫走。\n是哪一餐？" else "今天照計畫走。"
            assertEquals(kind, SecondPassJudge.userText(shown, "他正在討論吃的"), (clerk.requests[1].turns.single() as Turn.User).text)
        }
    }

    @Test fun foodItemsTheClerkFindsMakeItFoodTalkWithoutAFoodWord() {
        val items = """[{"name":"雞腿便當","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":700,"kcal_high":900,"protein_low":30,"protein_high":40}]"""
        val clerk = ScriptedLlm(ScriptedLlm.ok("""{"kind":"chat","items":$items}"""), judgeOk())
        val llm = ScriptedLlm(utter("好，照計畫。"))
        runBlocking { withClerk(clerk, llm).onText("雞腿便當好不好") }
        assertEquals(listOf(Purpose.INTAKE, Purpose.JUDGE), clerk.requests.map { it.purpose })
    }

    @Test fun drinksCountAsFoodTalkButWaterDoesNot() {
        for (t in listOf("喝了珍奶", "喝可樂", "我剛喝了一杯珍奶", "昨天喝了兩罐可樂", "好想喝汽水", "珍奶好想要", "來杯拿鐵", "喝了冬瓜茶", "晚上喝了一碗湯",
            "喝水果茶", "喝了一杯水果茶", "冬瓜茶好想要", "買了綠茶", "紅茶", "烏龍拿鐵", "早上一杯豆漿", "牛奶", "優酪乳",
            "氣泡飲", "運動飲料", "能量飲")) {
            assertTrue(t, Brain.mentionsFood(t))
        }
        for (t in listOf("去喝水", "喝溫水", "多喝開水", "喝一杯水再睡", "喝完水去睡", "喝三杯水", "喝很多水", "喝礦泉水",
            "喝夠水", "喝多一點水", "姐姐在嗎", "早點睡。")) {
            assertFalse(t, Brain.mentionsFood(t))
        }
        val clerk = ScriptedLlm(intake("chat"), judgeOk())
        runBlocking { withClerk(clerk, ScriptedLlm(utter("好，明天照計畫。"))).onText("喝了珍奶") }
        assertEquals(listOf(Purpose.INTAKE, Purpose.JUDGE), clerk.requests.map { it.purpose })
        val water = ScriptedLlm(intake("chat"))
        runBlocking { withClerk(water, ScriptedLlm(utter("好，早點睡。"))).onText("我去喝溫水") }
        assertEquals(listOf(Purpose.INTAKE), water.requests.map { it.purpose })
    }

    @Test fun aReplyThatNamesFoodIsJudgedEvenWhenHisWordsDoNot() {
        val clerk = ScriptedLlm(intake("chat"), judgeOk())
        val llm = ScriptedLlm(utter("晚餐照平常吃。"))
        runBlocking { withClerk(clerk, llm).onText("姐姐在嗎") }
        assertEquals(listOf(Purpose.INTAKE, Purpose.JUDGE), clerk.requests.map { it.purpose })
        assertEquals(SecondPassJudge.userText("晚餐照平常吃。", "他正在討論吃的"), (clerk.requests[1].turns.single() as Turn.User).text)
    }
}
