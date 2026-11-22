package app.agency.core.structured

import app.agency.core.guard.Evidence
import app.agency.core.memory.MemoryRequest
import app.agency.core.model.MemoryOp
import app.agency.core.model.MessageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceTest {
    private val ev = Evidence(listOf("""{"today":{"kcal":{"left":1071}}}"""))

    private fun decode(json: String) = Utterance.decode(LocalRepair.parse(json, Utterance.DEFAULTS)!!)

    private fun reasons(c: Checked<Utterance>) = (c as Checked.Reject).violations.joinToString { "${it.field}：${it.reason}" }

    @Test fun decodesMemoryEventsAndOptions() {
        val u = decode(
            """{"text":"好。","ask":"要改到幾點？","options":["十點","十一點"],"recommended":0,
               "memory":[{"op":"add","block":"likes","item_id":null,"text":"不吃地瓜","evidence":"m1"}]}""",
        ).value!!
        assertEquals(listOf("十點", "十一點"), u.options)
        assertEquals(0, u.recommended)
        assertEquals(listOf(MemoryRequest(MemoryOp.ADD, "likes", null, "不吃地瓜", "m1")), u.memory)
    }

    @Test fun shapeLimitsAreViolations() {
        val long = "字".repeat(161)
        val d = decode(
            """{"text":"$long","options":["一二三四五六七八九十一二三四五六七八九十一"],"recommended":3,
               "memory":[{"op":"add","block":"x","item_id":null,"text":"a","evidence":null},{"op":"add","block":"likes"},{"op":"add","block":"likes"}]}""",
        )
        assertNull(d.value)
        assertEquals(setOf("text", "options[0]", "recommended", "memory", "memory[0]"), d.violations.map { it.field }.toSet())
    }

    @Test fun normalRepliesAreGuardedAndTheGoodSentencesKept() {
        val check = Utterance.checker(ev, MessageMode.NORMAL)
        val c = check(Utterance("你還有 1071 大卡。再吃 300 大卡。現在去吃雞胸。"))
        assertEquals(Checked.Accept(Utterance("你還有 1071 大卡。現在去吃雞胸。")), c)
        assertTrue(reasons(check(Utterance("再吃 300 大卡。"))).contains("數字「300」不在資料裡"))
        assertTrue(reasons(check(Utterance("沒關係。現在去睡。"))).contains("沒關係"))
        assertTrue(reasons(check(Utterance("去睡。", ask = "好嗎？"))).contains("ask："))
    }

    @Test fun aDiscussionReplyAsksExactlyOneQuestion() {
        val check = Utterance.checker(ev, MessageMode.DISCUSS)
        val ok = Utterance("十點太早的話可以改。", ask = "你想改到幾點？", options = listOf("十點半", "十一點"), recommended = 0)
        assertEquals(Checked.Accept(ok), check(ok))
        val two = Utterance("你覺得太早嗎？", ask = "你想改到幾點？", options = listOf("十點半"), recommended = 0)
        assertTrue(reasons(check(two)).contains("只能問一個問題"))
        assertTrue(reasons(check(Utterance("十點太早的話可以改。"))).contains("要問他一個問題"))
        assertTrue(reasons(check(ok.copy(recommended = null))).contains("推薦"))
        assertTrue(reasons(check(ok.copy(options = listOf("去阿明牛肉麵館")))).contains("options[0]"))
    }

    @Test fun nudgeLinesAreAtMostTwoSentencesAndGuarded() {
        val check = NudgeLine.checker(ev)
        assertEquals(Checked.Accept(NudgeLine("午餐時間到了，拍給我。")), check(NudgeLine("午餐時間到了，拍給我。")))
        assertTrue(check(NudgeLine("起床。簽到。刷牙。")) is Checked.Reject)
        assertEquals(Checked.Accept(NudgeLine("拍給我。")), check(NudgeLine("再吃 300 大卡。拍給我。")))
        assertTrue(NudgeLine.SPEC.decode(LocalRepair.parse("""{"text":"${"字".repeat(61)}"}""", emptyMap())!!).value == null)
    }
}
