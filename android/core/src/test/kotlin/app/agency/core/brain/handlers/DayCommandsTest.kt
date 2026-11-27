package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Message
import app.agency.core.rules.RulesBook
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「今天休息」, 休假餐 and 「早上很餓」 by code (arch T11), and a rule change in chat. No model call in any of them. */
class DayCommandsTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock(Instant.parse("2026-12-07T02:00:00Z")) // Monday 10:00 in Taipei
    private val llm = ScriptedLlm()
    private var n = 0
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }

    private fun say(text: String): Message = runBlocking { brain.onText(text) }.last()
    private fun day(d: String) = runBlocking { store.days.get(d) }

    @Test fun aRestDayOnceAWeekAndTheSecondAsksWhy() {
        val first = say("今天休息")
        assertTrue(first.text, first.text.startsWith("好，今天換成休息日：只是不跑單。有課照上，核心、肌力、伸展、理論課照做（訓練 18:30 開始）。時間照上課日走"))
        assertTrue(day("2026-12-07")!!.restDay)
        val plan = runBlocking { PlanStore(store).plan(LocalDate.parse("2026-12-07")) }
        // Only the run is off: the rounds stay.
        assertNull(plan.slot(SlotKey.RIDE_OUT))
        assertTrue(plan.slot(SlotKey.STRENGTH)!!.required)
        assertEquals("今天已經是休息日了。", say("今天休息").text)
        clock.advance(Duration.ofDays(2)) // Wednesday, same week
        assertEquals("這週已經休過一天了（星期一）。為什麼還要休？先說原因。", say("今天休息").text)
        assertNull(day("2026-12-09"))
        clock.advance(Duration.ofDays(5)) // the next Monday: a new week
        assertTrue(say("今天休息").text.startsWith("好，今天換成休息日"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun theCheatMealIsClosedUntil2027() {
        assertEquals(RulesBook.CHEAT_CLOSED, say("休假餐 星期四晚餐").text)
        assertNull(day("2026-12-10"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun fromJanuaryTheCheatMealIsDeclaredInAdvanceOnceAWeek() {
        clock.now = Instant.parse("2027-01-04T02:00:00Z") // Monday 10:00
        val ok = say("休假餐 星期四晚餐")
        assertEquals("記下：休假餐是星期四的晚餐（17:35）。那一餐零食、炸的、甜的都可以，熱量照樣算進 1700，單餐上限 800 照算。", ok.text)
        assertEquals("DINNER", day("2027-01-07")!!.cheatSlot)
        assertEquals("這週的休假餐已經訂在星期四了，一週只有一餐。", say("休假餐 明天午餐").text)
        assertEquals(CheatDeclare.FORMAT, say("休假餐 隨便").text)
        // Late: today's lunch (12:10) declared at 12:30 of the next Monday.
        clock.now = Instant.parse("2027-01-11T04:30:00Z")
        assertEquals("休假餐要事先說，這一餐的時間已經過了。", say("休假餐 今天午餐").text)
        assertNull(day("2027-01-11"))
        // The on-run chicken breast is never a 休假餐.
        assertEquals(CheatDeclare.FORMAT, say("休假餐 今天跑單的雞胸").text)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun hungryMorningSwapsTheSoyMilkOnce() {
        val r = say("早上很餓")
        assertEquals("好，今天的豆漿挪到早上喝，雞胸照樣留到 21:00 跑單途中吃。喝的時候拍照。", r.text)
        assertTrue(day("2026-12-07")!!.soyMorning)
        assertEquals("今天的豆漿已經挪到早上了。", say("早上很餓").text)
        val plan = runBlocking { PlanStore(store).plan(LocalDate.parse("2026-12-07")) }
        assertEquals("21:00", plan.slot(SlotKey.RIDE_MEAL)!!.time)
        assertEquals("09:00", plan.slot(SlotKey.MORNING_SOY)!!.time)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun aRuleChangeInChatGetsTheFixedReplyAndChangesNothing() {
        val r = say("每天熱量可以改成 2000 嗎")
        assertEquals(RulesBook.LOCKED, r.text)
        assertEquals(Brain.RULES_KIND, r.kind)
        assertNull(runBlocking { store.rules.current() })
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun aDeclarationTheClerkGuessesIsNeverApplied() {
        val clerk = ScriptedLlm(ScriptedLlm.ok("""{"kind":"rest_day"}"""))
        val b = Brain(store, clerk, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
        val r = runBlocking { b.onText("我今天好像該休息一下") }.last()
        assertEquals("要換成休息日，就打「今天休息」。", r.text)
        assertFalse(day("2026-12-07")?.restDay ?: false)
    }
}
