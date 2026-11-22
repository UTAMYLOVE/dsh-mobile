package app.agency.core.memory

import app.agency.core.metering.MutableClock
import app.agency.core.model.MemoryItem
import app.agency.core.model.MemoryOp
import app.agency.core.model.Message
import app.agency.core.model.Who
import app.agency.core.rules.CoachRules
import app.agency.core.store.InMemoryAgencyStore
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

class MemoryApplierTest {
    private val store = InMemoryAgencyStore()
    // 2026-11-16 is a Monday; 12:00 in Taipei.
    private val time = MutableClock(Instant.parse("2026-11-16T04:00:00Z"))
    private val clock = AgencyClock.of(time)
    private var n = 0
    private val applier = MemoryApplier(store, clock) { "id${++n}" }
    private fun said(text: String) = Message("owner-msg-${++n}", clock.millis(), Who.OWNER, text = text)
    private val dayOf = { at: Long -> CoachRules.coachDay(Instant.ofEpochMilli(at), clock.zone, 5) }
    private fun today() = dayOf(clock.millis())

    private fun add(block: String, text: String, quote: String) = MemoryRequest(MemoryOp.ADD, block, text = text, quote = quote)

    /** The refs the prompt of this call would show. */
    private suspend fun refs() = MemoryBlocks.render(store.memory.items(), today(), dayOf).refs

    private suspend fun apply(vararg r: MemoryRequest, owner: Message?) = applier.apply(r.toList(), owner, refs())

    @Test fun anAddIsAppliedAndCodeStampsItsSource() = runBlocking {
        val m = said("我不吃地瓜啦")
        val out = apply(add("likes", "不吃地瓜", "不吃地瓜"), owner = m)
        assertEquals(emptyList<MemoryRejection>(), out.rejected)
        val e = out.applied.single()
        assertEquals(MemoryOp.ADD, e.op)
        assertNull(e.before)
        assertEquals("不吃地瓜", e.after)
        assertEquals(m.id, e.sourceMessageId)
        assertEquals(m.id, store.memory.items("likes").single().sourceMessageId)
    }

    @Test fun theQuoteMustBeHisWordsAndTheMemoryMustComeFromIt() = runBlocking {
        val m = said("我不吃地瓜，茶葉蛋也太鹹")
        assertEquals("要引用他這一輪說過的原話（二到二十字）", apply(add("likes", "不吃地瓜", "我討厭地瓜"), owner = m).rejected.single().reason)
        assertEquals("要引用他這一輪說過的原話（二到二十字）", apply(add("likes", "不吃地瓜", "瓜"), owner = m).rejected.single().reason)
        assertEquals("記的事要跟他引用的原話有關", apply(add("likes", "喜歡唱歌", "茶葉蛋也太鹹"), owner = m).rejected.single().reason)
        // Punctuation and spaces do not matter.
        assertTrue(apply(add("likes", "茶葉蛋太鹹", "茶葉蛋 也太鹹。"), owner = m).rejected.isEmpty())
        assertEquals("這一輪沒有他說的話，不能記", apply(add("likes", "不吃地瓜", "不吃地瓜"), owner = null).rejected.single().reason)
    }

    @Test fun digitsPrivateThingsAndBannedGuidanceAreRejected() = runBlocking {
        val m = said("體重 78 公斤，身高一百七十公分，超過就叫我多跑步補回來")
        val out = apply(add("about_him", "體重 78 公斤", "體重 78 公斤"), add("about_him", "身高一百七十公分", "身高一百七十公分"), owner = m)
        assertEquals(listOf("不能有數字，數字要記在紀錄裡", "不能有數字，數字要記在紀錄裡"), out.rejected.map { it.reason })
        val banned = apply(MemoryRequest(MemoryOp.ADD, "how_to_talk", text = "超過就叫他多跑步補回來", quote = "多跑步補回來"), owner = m)
        assertTrue(banned.rejected.single().reason.startsWith("不能記："))
        for (t in listOf("他的金鑰放在記事本", "住在中正路一段", "信箱 a@b", "密碼是生日")) assertTrue(t, PrivacyFilter.reason(t) != null)
        assertNull(PrivacyFilter.reason("他喜歡雞胸"))
        assertTrue(store.memory.items().isEmpty())
    }

    @Test fun atMostTwoPerTurnAndTextAtMostEightyCharacters() = runBlocking {
        val m = said("不吃地瓜，飲料要甜，茶葉蛋太鹹" + "辣".repeat(81))
        val out = apply(add("likes", "不吃地瓜", "不吃地瓜"), add("likes", "飲料要甜", "飲料要甜"), add("likes", "茶葉蛋太鹹", "茶葉蛋太鹹"), owner = m)
        assertEquals(2, out.applied.size)
        assertEquals(MemoryRejection(2, "一輪最多記 2 件事"), out.rejected.single())
        assertEquals("最多 80 個字", apply(add("likes", "辣".repeat(81), "辣辣辣"), owner = m).rejected.single().reason)
    }

    @Test fun anAddOverTheCapIsRejectedWithAReasonAndNothingIsEvicted() = runBlocking {
        val words = listOf("學生", "外送", "看不懂英文", "喜歡唱歌", "住家裡", "騎機車")
        words.forEach { store.memory.putItem(MemoryItem("seed-$it", "about_him", it, 0, 0, null)) }
        val out = apply(add("about_him", "很怕冷", "我很怕冷"), owner = said("我很怕冷"))
        assertEquals("關於他已經滿了（最多 6 條），先刪掉或改一條", out.rejected.single().reason)
        assertEquals(words.toSet(), store.memory.items("about_him").map { it.text }.toSet())
    }

    @Test fun itemsAreNamedByThePromptsShortReferences() = runBlocking {
        store.memory.putItem(MemoryItem("4f1c2d3e-0000-4000-8000-123456789abc", "likes", "不吃地瓜", 0, 0, null))
        val r = refs()
        assertEquals(mapOf("記1" to "4f1c2d3e-0000-4000-8000-123456789abc"), r)
        val m = said("地瓜我現在可以吃了，芋頭不行")
        val unknown = apply(MemoryRequest(MemoryOp.UPDATE, "likes", "記9", "不吃芋頭", "芋頭不行"), owner = m)
        assertEquals("沒有這一條（用記得的事前面的編號，例如記一）", unknown.rejected.single().reason)
        val byId = apply(MemoryRequest(MemoryOp.DELETE, "likes", "4f1c2d3e-0000-4000-8000-123456789abc", null, "地瓜我現在可以吃"), owner = m)
        assertEquals(1, byId.rejected.size)
        val wrongBlock = apply(MemoryRequest(MemoryOp.DELETE, "about_him", "記1", null, "地瓜我現在可以吃"), owner = m)
        assertEquals("關於他裡沒有這一條", wrongBlock.rejected.single().reason)
        val ok = apply(MemoryRequest(MemoryOp.UPDATE, "likes", "記1", "不吃芋頭", "芋頭不行"), owner = m)
        assertTrue(ok.rejected.isEmpty())
        assertEquals("不吃芋頭", store.memory.item("4f1c2d3e-0000-4000-8000-123456789abc")!!.text)
    }

    @Test fun undoRestoresBefore() = runBlocking {
        store.memory.putItem(MemoryItem("x", "likes", "不吃地瓜", 0, 0, null))
        val m = said("地瓜和芋頭都不吃")
        val up = apply(MemoryRequest(MemoryOp.UPDATE, "likes", "記1", "不吃地瓜和芋頭", "地瓜和芋頭都不吃"), owner = m).applied.single()
        assertEquals("不吃地瓜", up.before)
        assertEquals(UndoResult(true), applier.undo(up.id))
        assertEquals("不吃地瓜", store.memory.item("x")!!.text)
        assertTrue(store.memory.event(up.id)!!.undone)
        assertEquals(UndoResult(false, "已經撤銷過了"), applier.undo(up.id))

        val del = apply(MemoryRequest(MemoryOp.DELETE, "likes", "記1", null, "地瓜和芋頭"), owner = m).applied.single()
        assertTrue(store.memory.items("likes").isEmpty())
        assertEquals(UndoResult(true), applier.undo(del.id))
        assertEquals(listOf("不吃地瓜"), store.memory.items("likes").map { it.text })
    }

    @Test fun undoNeverBringsBackAnItemALaterChangeRemoved() = runBlocking {
        val m = said("我不吃地瓜，芋頭也不吃")
        val added = apply(add("likes", "不吃地瓜", "不吃地瓜"), owner = m).applied.single()
        val up = apply(MemoryRequest(MemoryOp.UPDATE, "likes", "記1", "不吃地瓜和芋頭", "芋頭也不吃"), owner = m).applied.single()
        // The ADD is older than the UPDATE: undoing it first is refused, so the UPDATE's undo cannot bring anything back.
        assertEquals(UndoResult(false, "先撤銷比較新的那一筆"), applier.undo(added.id))
        assertEquals(UndoResult(true), applier.undo(up.id))
        assertEquals(UndoResult(true), applier.undo(added.id))
        assertTrue(store.memory.items("likes").isEmpty())
        assertEquals(UndoResult(false, "已經撤銷過了"), applier.undo(up.id))
        assertTrue(store.memory.items("likes").isEmpty())
    }

    @Test fun aDeleteCannotBeUndoneIntoAFullBlock() = runBlocking {
        val words = listOf("學生", "外送", "看不懂英文", "喜歡唱歌", "住家裡", "騎機車")
        words.forEachIndexed { i, w -> store.memory.putItem(MemoryItem("s$i", "about_him", w, i.toLong(), 0, null)) }
        val m = said("我不是學生了，我很怕冷")
        val del = apply(MemoryRequest(MemoryOp.DELETE, "about_him", "記1", null, "我不是學生了"), owner = m).applied.single()
        assertTrue(apply(add("about_him", "很怕冷", "我很怕冷"), owner = m).rejected.isEmpty())
        assertEquals(UndoResult(false, "關於他已經滿了，先刪掉一條"), applier.undo(del.id))
        assertTrue(store.memory.item("s0")!!.deleted)
    }

    @Test fun anOpenLoopStoresItsDayAndAnUpdateResolvesItAgainstToday() = runBlocking {
        val bad = apply(add("open_loops", "之後要考試", "之後要考試"), owner = said("之後要考試"))
        assertEquals("要說是哪一天（例如明天、星期五、下週）", bad.rejected.single().reason)
        val habit = apply(add("open_loops", "每週三要練團", "每週三要練團"), owner = said("每週三要練團"))
        assertEquals("固定的事放 about_him", habit.rejected.single().reason)

        val e = apply(add("open_loops", "星期五考試", "星期五考試"), owner = said("星期五考試")).applied.single()
        assertEquals("2026-11-20", e.afterDue)
        // Saturday: he says the make-up exam is tomorrow.
        time.advance(Duration.ofDays(5))
        val up = apply(MemoryRequest(MemoryOp.UPDATE, "open_loops", "記1", "明天要補考", "明天要補考"), owner = said("明天要補考")).applied
        // Friday's loop has passed, so the prompt no longer shows 記1: the update names nothing.
        assertTrue(up.isEmpty())
        time.advance(Duration.ofDays(-1))
        val fri = apply(MemoryRequest(MemoryOp.UPDATE, "open_loops", "記1", "明天要補考", "明天要補考"), owner = said("明天要補考")).applied.single()
        assertEquals("2026-11-20", fri.beforeDue)
        assertEquals("2026-11-21", fri.afterDue)
        assertEquals("2026-11-21", store.memory.item(e.itemId)!!.dueDay)
        val text = MemoryBlocks.render(store.memory.items(), today(), dayOf).text
        assertTrue(text, text.contains("明天要補考（2026-11-21）"))
        assertEquals(UndoResult(true), applier.undo(fri.id))
        assertEquals("2026-11-20", store.memory.item(e.itemId)!!.dueDay)
    }

    @Test fun pastOpenLoopsDoNotFillTheCap() = runBlocking {
        (1..8).forEach { store.memory.putItem(MemoryItem("old$it", "open_loops", "星期一的事$it", 0, 0, null, dueDay = "2026-11-0$it")) }
        val out = apply(add("open_loops", "下週要去健檢", "下週要去健檢"), owner = said("下週要去健檢"))
        assertTrue(out.rejected.toString(), out.rejected.isEmpty())
        val shown = MemoryBlocks.render(store.memory.items(), today(), dayOf)
        assertFalse(shown.text.contains("星期一的事"))
        assertEquals(1, shown.refs.size)
    }

    @Test fun dueDaysAreResolvedByCode() {
        val fri = LocalDate.of(2026, 11, 20)
        assertEquals(LocalDate.of(2026, 11, 21), DueDay.resolve("明天要考試", fri))
        assertEquals(LocalDate.of(2026, 11, 22), DueDay.resolve("後天去看牙醫", fri))
        assertEquals(fri, DueDay.resolve("星期五考試", fri))
        assertEquals(LocalDate.of(2026, 11, 24), DueDay.resolve("禮拜二交報告", fri))
        assertEquals(LocalDate.of(2026, 11, 27), DueDay.resolve("下週五面試", fri))
        assertEquals(LocalDate.of(2026, 12, 4), DueDay.resolve("下下週五面試", fri))
        assertEquals(LocalDate.of(2026, 11, 23), DueDay.resolve("下週要去健檢", fri))
        assertEquals(LocalDate.of(2026, 11, 30), DueDay.resolve("下下週要搬家", fri))
        assertEquals(LocalDate.of(2026, 11, 30), DueDay.resolve("月底要繳報告", fri))
        assertEquals(LocalDate.of(2026, 12, 1), DueDay.resolve("下個月開始上課", fri))
        assertNull(DueDay.resolve("一週一次", fri))
    }
}
