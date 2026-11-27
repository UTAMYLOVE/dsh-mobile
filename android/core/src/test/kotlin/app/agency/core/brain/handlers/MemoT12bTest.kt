package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Memo
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import app.agency.core.tools.ReadMemosTool
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T12b: 「幫我記」「記一下」「提醒我」 by code, times by code, the reminder once, check-off, delete and 復原. Invented data. */
class MemoT12bTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private val day = LocalDate.parse("2026-11-25") // a Wednesday
    private val zone = AgencyClock.TAIPEI

    init {
        runBlocking { store.profile.put(Profile(joinDate = "2026-11-21", onboardingDone = true)) }
    }

    private fun brain(llm: ScriptedLlm = ScriptedLlm()) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }

    private fun at(hhmm: String, d: LocalDate = day) {
        clock.now = d.atStartOfDay(zone).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun say(hhmm: String, text: String): List<Message> {
        at(hhmm)
        return runBlocking { brain().onText(text) }.filter { it.who == Who.COMPANY }
    }

    private fun due(text: String, hhmm: String = "10:00"): LocalDateTime? {
        at(hhmm)
        return MemoWords.due(text, clock.now, zone, 5)?.atZone(zone)?.toLocalDateTime()
    }

    @Test fun theThreePhrasingsKeepHisWords() {
        val now = Instant.parse("2026-11-25T02:00:00Z")
        assertEquals("買軟尺", MemoWords.parse("幫我記：買軟尺", now, zone, 5)!!.text)
        assertEquals("明天九點繳電話費", MemoWords.parse("記一下 明天九點繳電話費。", now, zone, 5)!!.text)
        val r = MemoWords.parse("提醒我下午三點拿包裹", now, zone, 5)!!
        assertEquals("下午三點拿包裹", r.text)
        assertTrue(r.remind)
        assertNull(MemoWords.parse("幫我記", now, zone, 5))
        assertNull(MemoWords.parse("我有記得喝水", now, zone, 5))
    }

    @Test fun timesAreReadByCode() {
        assertEquals(LocalDateTime.parse("2026-11-26T09:00"), due("明天 9 點繳費"))
        assertEquals(LocalDateTime.parse("2026-11-26T09:30"), due("明天九點半繳費"))
        assertEquals(LocalDateTime.parse("2026-11-25T15:00"), due("下午三點拿包裹"))
        // Past today's 15:00: the next one.
        assertEquals(LocalDateTime.parse("2026-11-26T15:00"), due("下午三點拿包裹", "16:00"))
        assertEquals(LocalDateTime.parse("2026-11-27T09:00"), due("星期五交報告")) // a day without a time: 09:00
        assertEquals(LocalDateTime.parse("2026-12-02T20:15"), due("下星期三晚上8點15分打電話"))
        assertEquals(LocalDateTime.parse("2026-11-25T18:45"), due("18:45 收衣服"))
        assertEquals(LocalDateTime.parse("2026-11-25T10:20"), due("二十分鐘後關火"))
        assertEquals(LocalDateTime.parse("2026-12-03T09:00"), due("12/3 繳學費"))
        assertNull(due("買軟尺"))
        // After midnight it is still yesterday's coach day: 「明天」 is the calendar day that starts in the morning.
        at("01:00") // the planner's clock: 01:00 after the 25th, i.e. the 26th
        assertEquals(Instant.parse("2026-11-25T17:00:00Z"), clock.now)
        assertEquals(LocalDateTime.parse("2026-11-26T09:00"), MemoWords.due("明天九點繳費", clock.now, zone, 5)?.atZone(zone)?.toLocalDateTime())
    }

    @Test fun aMemoIsSavedByCodeAndRemindedOnce() {
        val line = say("10:00", "提醒我明天九點繳電話費").single()
        assertEquals(RoleKey.MANAGER, line.role)
        assertEquals("記下了：「明天九點繳電話費」。11/26（四）09:00 提醒你。", line.text)
        val memo = runBlocking { store.memos.all() }.single()
        assertEquals(memo.id, line.refs.memo)
        assertEquals("明天九點繳電話費", memo.text)
        // The alarm wakes for it, and at its time 小葵 reminds him once.
        at("23:00")
        assertTrue(!runBlocking { brain().nextFire() }!!.at.isAfter(Instant.parse("2026-11-26T01:00:00Z")))
        at("09:00", day.plusDays(1))
        val posts = runBlocking { brain().tick() }.posts
        assertEquals(listOf("小雨，你要我提醒的：「明天九點繳電話費」。"), posts.filter { it.key.startsWith(MemoDesk.KEY) }.map { it.text })
        at("09:31", day.plusDays(1))
        assertTrue(runBlocking { brain().tick() }.posts.none { it.key.startsWith(MemoDesk.KEY) })
        assertTrue(runBlocking { store.memos.get(memo.id)!!.remindedAt } != null)
    }

    @Test fun withoutATimeItIsOnlyListed() {
        assertEquals("記下了：「買軟尺」，放在備忘錄。", say("10:00", "幫我記買軟尺").single().text)
        assertTrue(say("10:01", "提醒我買洗面乳").single().text.contains("沒聽到時間"))
        assertEquals(listOf(null, null), runBlocking { store.memos.all() }.map { it.dueAt })
    }

    @Test fun checkOffDeleteAndUndo() = runBlocking {
        say("10:00", "幫我記買軟尺")
        val id = store.memos.all().single().id
        val b = brain()
        assertTrue(b.checkMemo(id, true))
        assertTrue(store.memos.get(id)!!.doneAt != null)
        assertTrue(b.checkMemo(id, false))
        assertNull(store.memos.get(id)!!.doneAt)
        assertTrue(b.deleteMemo(id))
        assertTrue(b.memos().isEmpty())
        assertTrue(b.restoreMemo(id))
        assertEquals(listOf("買軟尺"), b.memos().map { it.text })
    }

    @Test fun theToolReturnsOnlyStoredRowsInHisWords() = runBlocking {
        store.memos.upsert(Memo("a", 1, "買軟尺"))
        store.memos.upsert(Memo("b", 2, "繳電話費", dueAt = Instant.parse("2026-11-26T01:00:00Z").toEpochMilli(), doneAt = 3))
        store.memos.upsert(Memo("c", 3, "刪掉的", deletedAt = 4))
        val out = ReadMemosTool(store, zone).run(JsonObject(emptyMap())).jsonObject
        val rows = out["memos"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("買軟尺", "繳電話費"), rows.map { it["text"]!!.jsonPrimitive.content })
        assertEquals("2026-11-26T09:00", rows[1]["remind_at"]!!.jsonPrimitive.content)
        assertEquals(1, out["count_open"]!!.jsonPrimitive.content.toInt())
        // No tool can write one.
        val tools = app.agency.core.tools.chatTools(
            store, app.agency.core.brain.SnapshotReader(store, AgencyClock.of(clock)), AgencyClock.of(clock), { "x" },
            app.agency.core.memory.MemoryApplier(store, AgencyClock.of(clock), 5) { "x" }, app.agency.core.tools.RoundContext(Message("o", 1, Who.OWNER, text = "嗨"), emptyMap(), day, RoleKey.MANAGER),
        ).map { it.spec.name }
        assertTrue(ReadMemosTool.NAME in tools)
        assertTrue(tools.none { it.contains("memo") && it != ReadMemosTool.NAME })
    }

    @Test fun theManagerIsXiaoKuiInTheReminder() {
        assertTrue(MemoDesk.reminder(Memo("a", 1, "買軟尺")).startsWith("小雨，"))
    }
}
