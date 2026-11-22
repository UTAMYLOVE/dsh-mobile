package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.brain.OrderDiff
import app.agency.core.brain.ProposalApplier
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.SearchClient
import app.agency.core.llm.SearchHit
import app.agency.core.llm.SearchResult
import app.agency.core.llm.ToolCall
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.metering.MutableClock
import app.agency.core.model.DayOverride
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16: the meal talk of the body teacher, the nearby search (real results only) and the pre-meal suggestion. Invented data. */
class MealTalkT16Test {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-11-23") // a Monday, a class day
    private val searched = mutableListOf<Pair<String, String>>()
    private var answer: SearchResult = SearchResult.Ok(
        "大安區 雞胸便當 推薦",
        listOf(
            SearchHit("https://example.org/a", "健康雞胸便當大安店", "雞胸便當，有燙青菜。", "2026-10-01T00:00:00Z", 2, 2.1),
            SearchHit("https://example.org/b", "好味道自助餐", "自助餐，可以夾青菜和雞腿。", null, -1, 1.6),
        ),
    )
    private val search = SearchClient { area, dish -> searched += area to dish; answer }

    init {
        runBlocking { store.profile.put(Profile(heightCm = 170.0, startWeightKg = 78.0, areaText = "大安區", onboardingDone = true)) }
    }

    private fun brain(llm: ScriptedLlm, s: SearchClient? = search) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock), search = s) { "id-%04d".format(++n) }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun reply(text: String): ChatResult {
        val json = """{"text":"$text","ask":null,"options":[],"recommended":null,"memory":[]}"""
        val raw = JsonObject(mapOf("role" to JsonPrimitive("assistant"), "content" to JsonPrimitive(json), "reasoning_content" to JsonPrimitive("想")))
        return ChatResult.Ok(json, emptyList(), Turn.Assistant(Wire.OPENAI, raw, json, emptyList()), null)
    }

    private fun call(name: String, args: String, id: String = "c-$name"): ChatResult {
        val c = ToolCall(id, name, args)
        val raw = Json.parseToJsonElement(
            """{"role":"assistant","content":"","reasoning_content":"查","tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${JsonPrimitive(args)}}}]}""",
        )
        return ChatResult.Ok(null, listOf(c), Turn.Assistant(Wire.OPENAI, raw, null, listOf(c)), null)
    }

    private val judgeOk = ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}""")

    private fun say(hhmm: String, text: String, llm: ScriptedLlm, s: SearchClient? = search): List<Message> {
        at(hhmm)
        return runBlocking { brain(llm, s).onText(text) }.filter { it.who == Who.COMPANY }
    }

    /** What the tool told the model, from the request after it. */
    private fun toolOutput(llm: ScriptedLlm, index: Int): String = llm.requests[index].turns.filterIsInstance<Turn.ToolResult>().last().content

    @Test fun nearbyRunsOneSearchOfAreaAndDishAndOnlyRealPlacesSurviveTheGuard() {
        val llm = ScriptedLlm(
            call("search_places", """{"dish":"雞胸便當"}"""),
            reply("附近有健康雞胸便當大安店，可以點雞胸便當。也可以去阿明牛肉麵店。"),
            judgeOk,
        )
        val out = say("16:30", "附近有什麼", llm)
        // No clerk: the body teacher's turn with its task.
        assertEquals(listOf(Purpose.CHAT, Purpose.CHAT, Purpose.JUDGE), llm.requests.map { it.purpose })
        assertTrue(llm.requests[0].system.contains("【這一次要做的事】"))
        assertTrue(llm.requests[0].system.contains("【你的角色：體態管理老師】"))
        // The query is his coarse area and the dish, nothing else.
        assertEquals(listOf("大安區" to "雞胸便當"), searched)
        val tool = toolOutput(llm, 1)
        assertTrue(tool.contains("健康雞胸便當大安店"))
        assertFalse("no link goes to the model", tool.contains("https") || tool.contains("example.org"))
        val m = out.single()
        assertEquals(RoleKey.BODY, m.role)
        assertEquals(MealDiscussion.TALK_KIND, m.kind)
        // The invented place is guarded out; the cards point at the two real results kept in the store.
        assertEquals("附近有健康雞胸便當大安店，可以點雞胸便當。", m.text)
        assertEquals(2, m.refs.results.size)
        val kept = m.refs.results.map { runBlocking { store.places.result(it) }!! }
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), kept.map { it.url })
    }

    @Test fun oneSearchARoundAndNoAreaMeansNoSearch() {
        val llm = ScriptedLlm(
            call("search_places", """{"dish":"雞胸便當"}"""), call("search_places", """{"dish":"牛肉麵"}""", "c-2"),
            reply("先吃雞胸便當。"), judgeOk,
        )
        say("16:30", "附近有什麼", llm)
        assertEquals(1, searched.size)
        assertTrue(toolOutput(llm, 2).contains("這一輪已經搜尋過一次了"))
        // Without his area nothing is searched; the model is told to send him to 設定.
        runBlocking { store.profile.put(store.profile.get()!!.copy(areaText = null)) }
        val again = ScriptedLlm(call("search_places", """{"dish":"雞胸便當"}"""), reply("先到設定填地區。"), judgeOk)
        say("16:40", "附近有什麼", again)
        assertEquals(1, searched.size)
        assertTrue(toolOutput(again, 1).contains("還沒在設定的附近搜尋填粗略的地區"))
    }

    @Test fun aSearchStoppedByTheSpendCapGivesNoPlaceAndNamesNone() {
        answer = SearchResult.Failed(Fail.CAP)
        val llm = ScriptedLlm(call("search_places", """{"dish":"雞胸便當"}"""), reply("阿明牛肉麵店不錯。先吃雞胸便當。"), judgeOk)
        val out = say("16:30", "附近有什麼", llm)
        assertTrue(toolOutput(llm, 1).contains("今天的花費到上限了"))
        assertEquals("先吃雞胸便當。", out.single().text)
        assertTrue(out.single().refs.results.isEmpty())
        assertTrue(runBlocking { store.places.results(app.agency.core.model.SearchKind.PLACE) }.isEmpty())
    }

    @Test fun lunchWithFriendsGetsOnePickAsAnOrderCardWithAnEstimate() {
        val llm = ScriptedLlm(
            call("propose_meal_order", """{"slot":"lunch","items":[{"name":"清燉牛肉麵","portion":1,"kcal_high":600,"protein_low":30}],"place_result_id":null}"""),
            reply("點清燉牛肉麵，加一份燙青菜，湯不要喝完。"),
            judgeOk,
        )
        val out = say("11:50", "中午跟朋友吃牛肉麵", llm)
        assertEquals(listOf(Purpose.CHAT, Purpose.CHAT, Purpose.JUDGE), llm.requests.map { it.purpose })
        assertTrue(llm.requests[0].system.contains("他要跟朋友去吃「牛肉麵」"))
        assertTrue(llm.requests[0].system.contains("不要叫他不要跟朋友吃"))
        assertEquals(RoleKey.BODY, out.single().role)
        val p = runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        assertEquals(ProposalKind.MEAL_ORDER, p.kind)
        val d = ProposalApplier.JSON.decodeFromString(OrderDiff.serializer(), p.diffJson)
        // 600 × 1.1 = 660: strict and marked 估算 until a menu or a label.
        assertEquals(660, d.kcal)
        assertTrue(d.items.single().estimated)
        assertTrue(ProposalApplier.view(p).lines.any { it.after.contains("（估算）") })
        // A meal he already ate with friends is a meal report, not a task.
        assertNull(MealDiscussion.task("中午跟朋友吃了牛肉麵"))
    }

    @Test fun anOrderOverTheCapOverTheRoomOrBannedIsRefusedWithTheReason() {
        runBlocking {
            store.meals.insert(Meal("m-0", day.toString(), 0, "LUNCH", "午餐", listOf(MealItem("便當", 1.0, 1100, 40.0, null, null, false, KcalSource.TABLE))))
        }
        val llm = ScriptedLlm(
            ScriptedLlm.ok("""{"kind":"chat"}"""),
            call("propose_meal_order", """{"slot":"dinner","items":[{"name":"大碗牛肉麵","portion":1,"kcal_high":800,"protein_low":30}],"place_result_id":null}""", "c-1"),
            call("propose_meal_order", """{"slot":"dinner","items":[{"name":"雞腿便當","portion":1,"kcal_high":500,"protein_low":30}],"place_result_id":null}""", "c-2"),
            call("propose_meal_order", """{"slot":"dinner","items":[{"name":"炸雞腿便當","portion":1,"kcal_high":200,"protein_low":30}],"place_result_id":null}""", "c-3"),
            call("propose_meal_order", """{"slot":"dinner","items":[{"name":"燙青菜加滷雞腿","portion":1,"kcal_high":250,"protein_low":25}],"place_result_id":"made-up"}""", "c-4"),
            reply("晚餐吃燙青菜加滷雞腿。"),
            judgeOk,
        )
        say("16:30", "晚餐只能買超商，吃什麼好", llm, null)
        // 880 > 800; 550 > the 290 dinner has after a 1100 lunch (1700 − 1100 − 310); fried; a place id not from a search.
        assertTrue(toolOutput(llm, 2), toolOutput(llm, 2).contains("超過單餐上限 800"))
        assertTrue(toolOutput(llm, 3), toolOutput(llm, 3).contains("晚餐今天建議 290 大卡以內"))
        assertTrue(toolOutput(llm, 4), toolOutput(llm, 4).contains("炸雞腿便當是炸物，休假餐以外不准吃"))
        assertTrue(toolOutput(llm, 5), toolOutput(llm, 5).contains("只能用這一輪搜尋結果的編號"))
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.isEmpty())
    }

    @Test fun aSavedPlaceWaitsForHisYes() {
        val llm = ScriptedLlm(call("search_places", """{"dish":"雞胸便當"}"""), reply("附近有健康雞胸便當大安店。"), judgeOk)
        val r = say("16:30", "附近有什麼", llm).single().refs.results.first()
        val b = brain(ScriptedLlm())
        val pid = runBlocking { b.proposeSavePlace(r) }!!
        assertTrue(runBlocking { store.places.favorites() }.isEmpty())
        assertEquals(ProposalKind.SAVE_PLACE, runBlocking { store.proposals.get(pid) }!!.kind)
        assertEquals("好，健康雞胸便當大安店存成常去店家了。", runBlocking { b.decide(pid, true) }!!.text)
        val fav = runBlocking { b.favorites() }.single()
        assertEquals("健康雞胸便當大安店", fav.title)
        assertEquals("https://example.org/a", fav.url)
        assertEquals("大安區", fav.area)
        assertTrue(runBlocking { b.deleteFavorite(fav.id) })
        assertNull(runBlocking { b.proposeSavePlace("gone") })
    }

    @Test fun thePreMealSuggestionComesOnceBeforeDinnerAndNeverForTheClassDayLunch() {
        val b = brain(ScriptedLlm())
        // Before 16:45 the next alarm is the suggestion's.
        at("16:00")
        assertEquals(day.atStartOfDay(AgencyClock.TAIPEI).plusHours(16).plusMinutes(45).toInstant(), runBlocking { b.nextFire() }!!.at)
        at("16:45")
        val post = runBlocking { b.tick() }.posts.single { it.key == "premeal" }
        // Nothing eaten yet today: the floor (1200) needs 890 besides the 21:00 portion, the cap allows 800.
        assertEquals(
            "晚餐 17:15 快到了：今天建議 800 大卡以內、蛋白質至少 70 克。可以吃給力盒子南加州烤雞腿（714 大卡、蛋白質 48 克）。" +
                "想吃別的就說「今天想吃…」，跟朋友吃就說「跟朋友吃…」，要找附近的店就說「附近有什麼」。",
            post.text,
        )
        assertTrue(post.nudgeId.endsWith(":DINNER"))
        assertNull(post.slot) // tapping it opens the conversation, not the camera
        assertTrue(post.word)
        // (The first alarm also gave the week's real-person task, L20: a link's line.)
        val line = runBlocking { store.messages.recent(5) }.last { it.kind != app.agency.core.engine.Nerve.KIND }
        assertEquals(RoleKey.BODY, line.role)
        assertEquals(MealDiscussion.PLAN_KIND, line.kind)
        at("16:50")
        assertTrue(runBlocking { b.tick() }.posts.none { it.key == "premeal" })
        // The class-day lunch is with friends: no suggestion for it (a Tuesday).
        day = LocalDate.parse("2026-11-24")
        at("11:45")
        assertTrue(runBlocking { b.tick() }.posts.none { it.key == "premeal" })
        at("12:05")
        assertTrue(runBlocking { b.tick() }.posts.none { it.key == "premeal" })
        // A no-class Wednesday: the first meal gets one at 12:30.
        day = LocalDate.parse("2026-11-25")
        at("12:30")
        assertTrue(runBlocking { b.tick() }.posts.single { it.key == "premeal" }.nudgeId.endsWith(":FIRST_MEAL"))
    }

    @Test fun noSuggestionForAMealAlreadyInAndARestDayStillHasItsMeals() {
        runBlocking { store.meals.insert(Meal("d-0", day.toString(), 0, "DINNER", "晚餐", listOf(MealItem("便當", 1.0, 600, 40.0, null, null, false, KcalSource.TABLE)))) }
        at("16:50")
        assertTrue(runBlocking { brain(ScriptedLlm()).tick() }.posts.none { it.key == "premeal" })
        day = day.plusDays(1)
        runBlocking { store.days.put(DayOverride(day.toString(), restDay = true, declaredAt = 0)) }
        at("16:50")
        assertEquals(1, runBlocking { brain(ScriptedLlm()).tick() }.posts.count { it.key == "premeal" })
    }

    @Test fun theSuggestionsWordingIsTheBodyTeachersAndJudged() {
        at("16:45")
        val post = runBlocking { brain(ScriptedLlm()).tick() }.posts.single { it.key == "premeal" }
        // The judge finds compensation: the template stays.
        val bad = ScriptedLlm(ScriptedLlm.ok("""{"text":"晚餐吃給力盒子南加州烤雞腿。"}"""), ScriptedLlm.ok("""{"violation":true,"class":"compensation","reason":"叫他少吃"}"""))
        assertNull(runBlocking { brain(bad).wordNudge(post.nudgeId) })
        assertTrue(bad.requests[0].system.contains("【你的角色：體態管理老師】"))
        assertEquals(Purpose.JUDGE, bad.requests[1].purpose)
        val good = ScriptedLlm(ScriptedLlm.ok("""{"text":"晚餐吃給力盒子南加州烤雞腿。"}"""), judgeOk)
        assertEquals("晚餐吃給力盒子南加州烤雞腿。", runBlocking { brain(good).wordNudge(post.nudgeId) })
    }

    @Test fun moreFoodTalkRightAfterTheSuggestionIsTheBodyTeachers() {
        at("16:45")
        runBlocking { brain(ScriptedLlm()).tick() }
        val llm = ScriptedLlm(ScriptedLlm.ok("""{"kind":"chat"}"""), reply("超商買即食雞胸加一份沙拉。"), judgeOk)
        val out = say("16:50", "我只能買超商", llm)
        assertEquals(RoleKey.BODY, out.single().role)
        assertTrue(llm.requests[1].system.contains("你們在討論下一餐吃什麼"))
    }

    @Test fun theAreaIsCoarseOnly() {
        assertNull(AreaText.problem("大安區"))
        assertNull(AreaText.problem(""))
        assertEquals("只填大概的地區（例如大安區），不要填門牌、巷弄或樓層。", AreaText.problem("復興南路一段 12 號"))
        assertTrue(AreaText.problem("Daan") != null)
        val b = brain(ScriptedLlm())
        assertTrue(runBlocking { b.setArea("和平東路 3 巷") } != null)
        assertEquals("大安區", runBlocking { store.profile.get() }!!.areaText)
        assertNull(runBlocking { b.setArea("信義區") })
        assertEquals("信義區", runBlocking { store.profile.get() }!!.areaText)
    }
}
