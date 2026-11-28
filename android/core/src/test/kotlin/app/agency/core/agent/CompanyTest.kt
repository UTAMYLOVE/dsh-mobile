package app.agency.core.agent

import app.agency.core.agent.tools.ToolRig
import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.lessons.LessonBook
import app.agency.core.lessons.LessonState
import app.agency.core.llm.ScriptedLlm
import app.agency.core.llm.ScriptedLlm.Companion.calls
import app.agency.core.llm.ScriptedLlm.Companion.says
import app.agency.core.llm.Turn
import app.agency.core.plan.ItemStatus
import app.agency.core.remind.ReminderLog
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import app.agency.core.stage.StageState
import app.agency.core.stage.Stages
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.ProposalRec
import app.agency.core.store.Role
import app.agency.core.store.add
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import app.agency.core.week.BodyTrend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyTest {
    private val store = MemStore()
    private val clock = TestClock()
    private var key = true
    private val llm = ScriptedLlm()
    private val company = Company(store, clock, Providers(llm, ScriptedLlm(fallback = says("""{"ok":true,"reason":""}""")), ToolRig.FakeSearch()) { key })

    private fun stage(n: Int) {
        val joined = TestClock.MONDAY.minusDays(((n - 1) * 7).toLong())
        store.writeDoc(Doc.STAGE, StageState(joinedOn = joined.toString(), stage = n, startedOn = TestClock.MONDAY.toString()))
    }

    private fun onboard() {
        company.saveRules(Fixtures.rules(), "他喜歡直接的說法。", "原文")
        company.rollover()
    }

    @Test fun readyNeedsRulesAndAKey() {
        assertFalse(company.today().ready)
        onboard()
        assertTrue(company.today().ready)
        key = false
        assertFalse(company.today().ready)
    }

    @Test fun rolloverJoinsOnTheDayRulesAreReady() {
        company.rollover()
        assertNull(Stages.get(store))
        onboard()
        assertEquals(Days.today(clock).toString(), Stages.get(store)!!.joinedOn)
        assertEquals("第 1 階段 · 第 1 天", company.today().stageLabel)
        assertEquals(listOf("lessons", "passed", "streak"), company.today().stickers.map { it.key })
        assertEquals("他喜歡直接的說法。", company.rules().let { RuleDocs.handbook(store) })
    }

    @Test fun sayRunsAToolRoundAndStoresTheReplyWithItsCard() = runBlocking {
        onboard()
        stage(2)
        llm.then(calls("log_water", """{"bottles":1,"ml":null}"""), says("記好了，水 900 毫升，目標 900 毫升。"))
        assertEquals(SayResult.Ok, company.say("喝完一壺了"))
        val reply = store.messages(1).single()
        assertEquals(Role.MANAGER, reply.role)
        assertEquals("記好了，水 900 毫升，目標 900 毫升。", reply.text)
        assertTrue(reply.cards.single() is Card.Summary)
        assertEquals(ItemStatus.DONE, company.item("water")!!.status)
        // The request carried the persona, the rules and the context block.
        val req = llm.requests.first()
        assertTrue(req.system.contains("小葵") && req.system.contains("每日 2100 大卡"))
        assertTrue((req.turns.first() as Turn.User).text.contains("今天的計畫"))
        assertEquals(listOf("water"), listOf(company.today().stickers.first { it.key == "water" }.key))
        assertEquals("1／1 壺", company.today().stickers.first { it.key == "water" }.value)
    }

    @Test fun directRunsWithoutAModelAndLeavesANote() {
        onboard()
        stage(2)
        val line = company.direct("log_water", buildJsonObject { put("bottles", 1) })
        assertEquals("✓ 已記：喝水 1 壺", line)
        assertEquals(Role.NOTE, store.messages(1).single().role)
        assertTrue(llm.requests.isEmpty())
        assertEquals("沒有這個動作。", company.direct("log_meal", buildJsonObject { }))
        assertTrue(company.direct("check_in", buildJsonObject { put("score", 12) }).startsWith("沒記到"))
    }

    @Test fun aScoreCardSignsHimIn() = runBlocking {
        onboard()
        stage(2)
        company.direct("check_in", buildJsonObject { put("score", null as Int?) })
        val msg = store.messages(1).single()
        assertEquals(SayResult.Ok, company.answerCard(msg.id, 0, "4"))
        assertEquals(4, (store.messages(5).first { it.id == msg.id }.cards[0] as Card.Score).picked)
        assertEquals(ItemStatus.DONE, company.item("check_in")!!.status)
        assertTrue(company.answerCard(msg.id, 0, "5") is SayResult.NotSent)
    }

    private fun proposalCard(kind: String, title: String, value: Int? = null): Message {
        val e = store.add(Days.today(clock), clock.now(), Kind.PROPOSAL, ProposalRec(kind, title, "", value))
        return store.addMessage(clock.now(), Role.MANAGER, "要不要？", listOf(Card.Proposal(e.id, title, "")))
    }

    @Test fun proposalsApplyOnYesOnly() = runBlocking {
        onboard()
        val habit = proposalCard("habit", "每天念五個單字")
        assertEquals(SayResult.Ok, company.answerCard(habit.id, 0, "好"))
        assertTrue(company.today().plan.items.none { it.id.startsWith("habit:") })
        clock.set(TestClock.MONDAY.plusDays(1), "08:00")
        assertTrue(company.today().plan.items.any { it.id.startsWith("habit:") })
        val note = proposalCard("note", "明天起早十分鐘出門")
        assertEquals(SayResult.Ok, company.answerCard(note.id, 0, "yes"))
        assertTrue(Memory.lines(store).any { it.contains("早十分鐘") })
        val kcal = proposalCard(BodyTrend.KCAL_UP, "每天多吃 100 大卡", 100)
        assertEquals(SayResult.Ok, company.answerCard(kcal.id, 0, "不要"))
        assertEquals(2100, company.rules().diet.kcal)
        val kcal2 = proposalCard(BodyTrend.KCAL_UP, "每天多吃 100 大卡", 100)
        assertEquals(SayResult.Ok, company.answerCard(kcal2.id, 0, "好"))
        assertEquals(2200, company.rules().diet.kcal)
        assertEquals(Card.Answer.YES, (store.messages(30).first { it.id == kcal2.id }.cards[0] as Card.Proposal).answer)
        assertTrue(company.answerCard(kcal2.id, 0, "好") is SayResult.NotSent)
    }

    @Test fun videoCardsApproveAndDecline() = runBlocking {
        onboard()
        llm.then(calls("find_videos", """{"subject":"語言","query":""}"""), says("找到了，看卡片。"))
        company.say("幫我找今天的語言影片")
        val msg = store.messages(1).single()
        val card = msg.cards.single() as Card.Videos
        val a = card.items[0].lessonId
        val b = card.items[1].lessonId
        assertEquals(SayResult.Ok, company.answerCard(msg.id, 0, "approve:$a"))
        assertEquals(SayResult.Ok, company.answerCard(msg.id, 0, "decline:$b"))
        val book = LessonBook(store, clock, company.rules())
        assertEquals(LessonState.APPROVED, book.get(a)!!.state)
        assertEquals(LessonState.DECLINED, book.get(b)!!.state)
        val updated = store.messages(10).first { it.id == msg.id }.cards.single() as Card.Videos
        assertEquals(listOf(a), updated.approved)
        assertEquals(listOf(b), updated.declined)
        assertTrue(company.answerCard(msg.id, 0, "approve:$b") is SayResult.NotSent)
        assertTrue(store.messages(3).any { it.role == Role.NOTE && it.text.contains("加入清單") })
    }

    @Test fun theLessonPagesCarryALessonToTheEnd() = runBlocking {
        onboard()
        llm.then(calls("find_videos", """{"subject":"語言","query":""}"""), says("看卡片。"))
        company.say("幫我找今天的語言影片")
        val id = (store.messages(1).single().cards.single() as Card.Videos).items[0].lessonId
        company.approveLesson(id)
        assertNotNull(company.openLesson(id))
        company.backFromLesson(id)
        // The quiz comes from the lesson's own material through the chat model.
        llm.then(says("""{"questions":[
            {"q":"一","options":["甲","乙","丙","丁"],"answer":1,"quote":"重點一：先深呼吸再開口說話"},
            {"q":"二","options":["甲","乙","丙","丁"],"answer":2,"quote":"重點二：每天固定練習十分鐘"},
            {"q":"三","options":["甲","乙","丙","丁"],"answer":3,"quote":"重點三：錄音回放檢查自己"}]}"""))
        val quiz = company.quizFor(id)!!
        assertTrue(company.answerQuiz(id, quiz.questions.map { it.answer }).passed)
        assertTrue(company.writeNote(id, "先深呼吸，再每天練十分鐘。") is app.agency.core.lessons.LessonResult.Ok)
        assertEquals(LessonState.NOTED, company.lessons().byState[LessonState.NOTED]!!.single().state)
        assertEquals(1, company.lessons().passedTotal)
    }

    @Test fun remindersWaitForCheckInOnceItIsOpen() {
        onboard()
        stage(2)
        clock.set(TestClock.MONDAY, "11:50")
        val d = company.reminders(ReminderLog())
        assertEquals(listOf("check_in"), d.notices.map { it.key })
        company.direct("check_in", buildJsonObject { put("score", 4) })
        val d2 = company.reminders(d.log)
        assertTrue(d2.notices.any { it.key == "meal:FIRST" })
    }

    @Test fun inStageOneLessonsNeedNoCheckIn() {
        onboard()
        store.add(Days.today(clock), clock.now(), Kind.MEMO, app.agency.core.store.Memo("交作業", clock.now().minusSeconds(60).toString()))
        val d = company.reminders(ReminderLog())
        assertTrue(d.notices.any { it.key.startsWith("memo:") })
        assertTrue(company.reminders(ReminderLog()).notices.none { it.key.startsWith("memo:") })
    }

    @Test fun timerAndMealPhotoFinishTheirItems() {
        onboard()
        stage(2)
        clock.set(TestClock.MONDAY, "21:15")
        assertTrue(company.timerDone("CORE", null, 5, app.agency.core.store.Feel.OK, 30).startsWith("✓"))
        assertEquals(ItemStatus.DONE, company.item("task:CORE")!!.status)
        assertNull(company.mealPhoto(app.agency.core.store.MealSlot.FIRST, "p1", true))
        val r = ToolRig.FakeSearch()
        assertNotNull(r)
        store.add(Days.today(clock), clock.now(), Kind.MEAL, app.agency.core.store.Meal(app.agency.core.store.MealSlot.FIRST, listOf(
            app.agency.core.store.MealItem("雞胸", 120, 24.0, app.agency.core.store.FoodSource.TABLE))))
        assertNotNull(company.mealPhoto(app.agency.core.store.MealSlot.FIRST, "p1", true))
        assertEquals(ItemStatus.DONE, company.item("meal:FIRST")!!.status)
    }

    @Test fun traineeCardMoneyAndMemory() {
        onboard()
        company.direct("log_money", buildJsonObject { put("amount", 150); put("bucket", "fun"); put("note", "電影") })
        assertEquals(150, company.moneyView().week.fun_)
        val card = company.traineeCard()
        assertEquals("STAR", card.stageName)
        assertEquals(1, card.dayNumber)
        Memory.remember(store, "他怕冷")
        assertEquals(listOf("他怕冷"), company.memoryLines())
        assertEquals(listOf("他怕冷"), company.forget("怕冷"))
        assertTrue(company.ruleGroups().isNotEmpty())
    }

    @Test fun savedRulesAreReadEveryCall() {
        onboard()
        assertEquals(2100, company.rules().diet.kcal)
        company.saveRules(Fixtures.rules().copy(diet = Fixtures.rules().diet.copy(kcal = 2300)), "", "")
        assertEquals(2300, store.readDoc<Rules>(Doc.RULES)!!.diet.kcal)
        assertEquals(2300, company.rules().diet.kcal)
    }
}

private object RuleDocs {
    fun handbook(store: MemStore) = app.agency.core.agent.tools.RulesDoc.handbook(store)
}

private typealias Message = app.agency.core.store.Message
