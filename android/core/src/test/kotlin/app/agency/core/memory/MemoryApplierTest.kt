package app.agency.core.memory

import app.agency.core.metering.MutableClock
import app.agency.core.model.MemoryItem
import app.agency.core.model.MemoryOp
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.time.AgencyClock
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
    // 2026-11-20 is a Friday; 12:00 in Taipei.
    private val clock = AgencyClock.of(MutableClock(Instant.parse("2026-11-20T04:00:00Z")))
    private var n = 0
    private val applier = MemoryApplier(store, clock) { "id${++n}" }
    private val msg = "owner-msg-1"

    private fun add(block: String, text: String, evidence: String? = msg) = MemoryRequest(MemoryOp.ADD, block, text = text, evidence = evidence)

    @Test fun anAddIsAppliedWithItsEventAndSource() = runBlocking {
        val out = applier.apply(listOf(add("likes", "不吃地瓜")), msg)
        assertEquals(emptyList<MemoryRejection>(), out.rejected)
        val e = out.applied.single()
        assertEquals(MemoryOp.ADD, e.op)
        assertNull(e.before)
        assertEquals("不吃地瓜", e.after)
        assertEquals(msg, e.sourceMessageId)
        assertEquals(listOf("不吃地瓜"), store.memory.items("likes").map { it.text })
        assertEquals(msg, store.memory.items("likes").single().sourceMessageId)
    }

    @Test fun theEvidenceIdIsRequired() = runBlocking {
        val noCite = applier.apply(listOf(add("likes", "不吃地瓜", evidence = null)), msg)
        assertEquals("要引用他這一輪訊息的編號", noCite.rejected.single().reason)
        val wrong = applier.apply(listOf(add("likes", "不吃地瓜", evidence = "other")), msg)
        assertEquals(1, wrong.rejected.size)
        val nudge = applier.apply(listOf(add("likes", "不吃地瓜")), null)
        assertEquals("這一輪沒有他說的話，不能記", nudge.rejected.single().reason)
        assertTrue(store.memory.items().isEmpty())
    }

    @Test fun digitsAndPrivateThingsAreRejected() = runBlocking {
        val out = applier.apply(listOf(add("about_him", "體重 78 公斤"), add("about_him", "身高一百七十公分")), msg)
        assertEquals(listOf("不能有數字，數字要記在紀錄裡", "不能有數字，數字要記在紀錄裡"), out.rejected.map { it.reason })
        for (t in listOf("他的金鑰放在記事本", "住在中正路一段", "信箱 a@b", "密碼是生日")) {
            assertNull(t, PrivacyFilter.reason("他喜歡雞胸"))
            assertTrue(t, PrivacyFilter.reason(t) != null)
        }
        assertTrue(store.memory.items().isEmpty())
    }

    @Test fun atMostTwoPerTurnAndTextAtMostEightyCharacters() = runBlocking {
        val out = applier.apply(listOf(add("likes", "不吃地瓜"), add("likes", "飲料要甜"), add("likes", "茶葉蛋太鹹")), msg)
        assertEquals(2, out.applied.size)
        assertEquals(MemoryRejection(2, "一輪最多記 2 件事"), out.rejected.single())
        val long = applier.apply(listOf(add("likes", "辣".repeat(81))), msg)
        assertEquals("最多 80 個字", long.rejected.single().reason)
    }

    @Test fun anAddOverTheCapIsRejectedWithAReasonAndNothingIsEvicted() = runBlocking {
        val words = listOf("學生", "外送", "看不懂英文", "喜歡唱歌", "住家裡", "騎機車")
        words.forEach { store.memory.putItem(MemoryItem("seed-$it", "about_him", it, 0, 0, null)) }
        val out = applier.apply(listOf(add("about_him", "怕冷")), msg)
        assertEquals("關於他已經滿了（最多 6 條），先刪掉或改一條", out.rejected.single().reason)
        assertEquals(words.toSet(), store.memory.items("about_him").map { it.text }.toSet())
    }

    @Test fun updateAndDeleteMustNameALiveItemOfThatBlock() = runBlocking {
        store.memory.putItem(MemoryItem("x", "likes", "不吃地瓜", 0, 0, null))
        val r = applier.apply(
            listOf(
                MemoryRequest(MemoryOp.UPDATE, "likes", "nope", "不吃地瓜也不吃芋頭", msg),
                MemoryRequest(MemoryOp.DELETE, "about_him", "x", null, msg),
            ),
            msg,
        )
        assertEquals(listOf("口味裡沒有這一條", "關於他裡沒有這一條"), r.rejected.map { it.reason })
    }

    @Test fun undoRestoresBefore() = runBlocking {
        store.memory.putItem(MemoryItem("x", "likes", "不吃地瓜", 0, 0, null))
        val up = applier.apply(listOf(MemoryRequest(MemoryOp.UPDATE, "likes", "x", "不吃地瓜和芋頭", msg)), msg).applied.single()
        assertEquals("不吃地瓜", up.before)
        assertEquals("不吃地瓜和芋頭", store.memory.item("x")!!.text)
        assertTrue(applier.undo(up.id))
        assertEquals("不吃地瓜", store.memory.item("x")!!.text)
        assertTrue(store.memory.event(up.id)!!.undone)
        assertFalse(applier.undo(up.id))

        val del = applier.apply(listOf(MemoryRequest(MemoryOp.DELETE, "likes", "x", null, msg)), msg).applied.single()
        assertTrue(store.memory.items("likes").isEmpty())
        assertTrue(applier.undo(del.id))
        assertEquals(listOf("不吃地瓜"), store.memory.items("likes").map { it.text })

        val added = applier.apply(listOf(add("likes", "飲料要甜")), msg).applied.single()
        assertTrue(applier.undo(added.id))
        assertEquals(listOf("不吃地瓜"), store.memory.items("likes").map { it.text })
    }

    @Test fun anOpenLoopNeedsADayCodeCanWorkOut() = runBlocking {
        val bad = applier.apply(listOf(add("open_loops", "之後要考試")), msg)
        assertEquals(1, bad.rejected.size)
        val ok = applier.apply(listOf(add("open_loops", "下週三要去健檢")), msg)
        assertTrue(ok.rejected.isEmpty())
        val item = store.memory.items("open_loops").single()
        val day = { _: Long -> LocalDate.of(2026, 11, 20) }
        assertTrue(MemoryBlocks.render(listOf(item), LocalDate.of(2026, 11, 20), day).contains("下週三要去健檢（2026-11-25）"))
        assertFalse(MemoryBlocks.render(listOf(item), LocalDate.of(2026, 11, 26), day).contains("健檢"))
    }

    @Test fun dueDaysAreResolvedByCode() {
        val fri = LocalDate.of(2026, 11, 20)
        assertEquals(LocalDate.of(2026, 11, 21), DueDay.resolve("明天要考試", fri))
        assertEquals(LocalDate.of(2026, 11, 22), DueDay.resolve("後天去看牙醫", fri))
        assertEquals(fri, DueDay.resolve("星期五考試", fri))
        assertEquals(LocalDate.of(2026, 11, 24), DueDay.resolve("禮拜二交報告", fri))
        assertEquals(LocalDate.of(2026, 11, 27), DueDay.resolve("下週五面試", fri))
        assertEquals(LocalDate.of(2026, 11, 23), DueDay.resolve("下週要去健檢", fri))
        assertEquals(LocalDate.of(2026, 11, 30), DueDay.resolve("月底要繳報告", fri))
        assertEquals(LocalDate.of(2026, 12, 1), DueDay.resolve("下個月開始上課", fri))
        assertNull(DueDay.resolve("一週一次", fri))
    }
}
