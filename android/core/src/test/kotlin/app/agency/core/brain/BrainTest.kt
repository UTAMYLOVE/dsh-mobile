package app.agency.core.brain

import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import app.agency.core.metering.MutableClock
import app.agency.core.model.FoodEntry
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
        val llm = ScriptedLlm(intake("chat"), utter("你今天還能吃 1234 大卡。現在去吃午餐。"))
        val b = brain(llm)
        val out = runBlocking { b.onText("姐姐在嗎") }
        val reply = out[1]
        assertEquals(RoleKey.MANAGER, reply.role)
        assertEquals(WordedBy.MODEL, reply.wordedBy)
        assertEquals("現在去吃午餐。", reply.text)
        assertFalse(stored().any { it.text.contains("1234") })
        val chat = llm.requests[1]
        assertEquals(Purpose.CHAT, chat.purpose)
        assertEquals(listOf("get_today", "query_records", "get_plan"), chat.tools.map { it.name })
        assertTrue(chat.system.contains("【你的角色：經紀人】"))
        assertNull(b.lastFail.value)
        assertFalse(b.busy.value)
    }

    @Test fun aFoodTableNameIsEvidenceForTheGuard() {
        runBlocking { store.food.insert(FoodEntry("悟饕池上便當", emptyList(), 760, 30.0, null, null, false, false, false, 0)) }
        val llm = ScriptedLlm(intake("chat"), utter("晚餐可以吃悟饕池上便當。吃完拍給我。"))
        val out = runBlocking { brain(llm).onText("晚餐吃什麼好") }
        assertEquals("晚餐可以吃悟饕池上便當。吃完拍給我。", out[1].text)
    }

    @Test fun legacyLinesGoToTheModelWithTheirRoleLabels() {
        runBlocking {
            store.messages.insert(Message("old-1", 1, Who.OWNER, text = "早安", legacy = true))
            store.messages.insert(Message("old-2", 2, Who.COMPANY, role = RoleKey.BODY, text = "記得吃早餐。", legacy = true))
        }
        val llm = ScriptedLlm(intake("chat"), utter("去吃早餐。"))
        runBlocking { brain(llm).onText("好") }
        val turns = llm.requests[1].turns
        assertEquals(Turn.User("早安"), turns[0])
        assertEquals(Turn.AssistantText("【體態管理老師】記得吃早餐。"), turns[1])
    }

    @Test fun noAnswerFromTheProviderIsTheTemplateAndSaysWhy() {
        val llm = ScriptedLlm(ScriptedLlm.failed(Fail.KEY_REJECTED))
        val b = brain(llm)
        val out = runBlocking { b.onText("我今天頭暈") }
        assertEquals(1, llm.requests.size)
        assertEquals(Roles.template(RoleKey.MANAGER, Tone.GENTLE) + Brain.KEY_REJECTED, out[1].text)
        assertEquals(WordedBy.TEMPLATE, out[1].wordedBy)
        assertEquals(Fail.KEY_REJECTED, b.lastFail.value)
        assertEquals(2, stored().size)
    }

    @Test fun theLoopFailingAfterTheClerkAlsoSaysWhy() {
        val llm = ScriptedLlm(intake("mood"), ScriptedLlm.failed(Fail.OFFLINE))
        val out = runBlocking { brain(llm).onText("今天心情不好") }
        assertEquals(Roles.template(RoleKey.MANAGER, Tone.GENTLE) + Brain.failLine(Fail.OFFLINE), out[1].text)
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
}
