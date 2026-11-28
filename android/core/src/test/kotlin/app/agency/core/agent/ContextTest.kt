package app.agency.core.agent

import app.agency.core.agent.tools.ToolRig
import app.agency.core.base.Days
import app.agency.core.rules.RulesText
import app.agency.core.store.DayClose
import app.agency.core.store.Kind
import app.agency.core.store.Role
import app.agency.core.store.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTest {
    /** A stage-2 Monday with a bit of everything. */
    private fun busyDay(): ToolRig {
        val r = ToolRig(stage = 2)
        r.at("07:10")
        r.run("check_in", """{"score":4}""")
        r.run("set_status", """{"type":"SICK","note":"喉嚨痛"}""")
        r.at("12:10")
        r.run("log_meal", """{"slot":"first","items":[{"name":"雞腿便當","kcal":1,"protein_g":1},{"name":"炸物拼盤","kcal":420,"protein_g":12.5}],"source":"estimate","attach_photo":false}""")
        r.run("log_water", """{"bottles":0.5,"ml":null}""")
        r.run("memo_add", """{"text":"繳電話費","due_at":"20:15"}""")
        r.run("propose", """{"title":"明天起早十分鐘出門","body":"","kind":"note","value":null}""")
        r.run("find_videos", """{"subject":"語言","query":""}""")
        r.at("19:25")
        r.run("ride_out", "{}")
        val yesterday = r.monday.minusDays(1)
        r.store.add(yesterday, r.clock.now(), Kind.DAY_CLOSE, DayClose(9, 10, 1, false, mapOf("water" to "喝水")))
        return r
    }

    @Test fun theBlockHasEveryFactFamily() {
        val r = busyDay()
        val b = Context(r.store, r.clock, r.rules).block()
        listOf(
            "現在：", "上課日", "階段：第 2 階段 · 第 1 天", "開放的功能：", "還沒開放：", "不要叫他做", "今天的計畫（完成", "下一件事：",
            "今天的紀錄：", "吃：今天", "下一餐預算", "喝水：500 毫升（0.5 壺）", "跑單：跑單中", "本週收入", "今天改了什麼", "狀態：生病中",
            "等他回覆的提案：", "今天到期的備忘：", "今天的課", "最近七天：", "規章數字", "今天的訓練量", "今天的睡覺（簽退）時間：1:00",
        ).forEach { assertTrue("missing 「$it」 in\n$b", b.contains(it)) }
        val meal = r.store.entries(r.monday, Kind.MEAL).single()
        assertTrue(b.contains("#${meal.id} 12:10 第一餐"))
        assertTrue(b.contains("禁止：炸物"))
    }

    /** Every number with a unit and every time in the block passes the guard when she says it back. */
    @Test fun theGuardAcceptsEveryNumberPrintedInTheBlock() {
        for (stage in 1..4) {
            val r = if (stage == 2) busyDay() else ToolRig(stage = stage)
            val c = Context(r.store, r.clock, r.rules)
            val text = c.block()
            val unit = Guard.UNITS.joinToString("|") { Regex.escape(it) }
            val said = Regex("""(?<![0-9.:])([0-9]+(?:\.[0-9]+)?)\s*($unit)""").findAll(text).map { "記住，是 ${it.value}。" }.toList() +
                Regex("""(?<![0-9])\d{1,2}:\d{2}""").findAll(text).map { "時間是 ${it.value}。" }.toList()
            assertTrue(said.size > 10)
            for (s in said) {
                val v = Guard.check(s, c.facts(), "", GuardFlags.of(c))
                assertEquals("stage $stage: 「$s」", GuardResult.Ok, v)
            }
        }
    }

    @Test fun theRulesNumbersPassToo() {
        val r = ToolRig(stage = 4)
        val c = Context(r.store, r.clock, r.rules)
        val unit = Guard.UNITS.joinToString("|") { Regex.escape(it) }
        Regex("""([0-9]+(?:\.[0-9]+)?)\s*($unit)""").findAll(RulesText.render(r.rules)).forEach {
            assertEquals(GuardResult.Ok, Guard.check("規定是 ${it.value}。", c.facts(), "", GuardFlags.of(c)))
        }
    }

    @Test fun closedWordsFollowTheStage() {
        val one = Context.closedWords(1)
        assertTrue("喝水" in one && "跑單" in one && "簽到" in one)
        val two = Context.closedWords(2)
        assertFalse("喝水" in two || "跑單" in two)
        assertTrue("起床伸展" in two && "體能" in two)
        assertTrue(Context.closedWords(4).isEmpty())
        val r = ToolRig(stage = 1)
        val c = Context(r.store, r.clock, r.rules)
        val bad = Guard.check("記得先喝水再出門。", c.facts(), "", GuardFlags.of(c))
        assertTrue(bad is GuardResult.Fix)
        assertEquals(GuardResult.Ok, Guard.check("喝水之後才開始算，先記下來。", c.facts(), "", GuardFlags.of(c)))
    }

    @Test fun flagsForTheGuard() {
        val r = ToolRig(stage = 2)
        assertFalse(Context(r.store, r.clock, r.rules).unwellReducedByCode)
        r.run("set_status", """{"type":"INJURED","note":"膝蓋"}""")
        val c = Context(r.store, r.clock, r.rules)
        assertTrue(c.unwellReducedByCode)
        assertEquals(60.0, c.neverBelowKg, 0.0)
    }

    @Test fun aSceneOnlyBeforeTheFirstReplyOfTheDay() {
        val r = ToolRig(stage = 2)
        assertTrue(Context(r.store, r.clock, r.rules).mayPlayScene)
        r.store.addMessage(r.clock.now(), Role.MANAGER, "早。")
        assertFalse(Context(r.store, r.clock, r.rules).mayPlayScene)
        r.clock.set(r.monday.plusDays(1), "07:00")
        assertTrue(Context(r.store, r.clock, r.rules).mayPlayScene)
    }

    @Test fun sweetOnlyAfterABigItemWithoutViolationsAndOnceADay() {
        val r = ToolRig(stage = 2)
        assertFalse(Context(r.store, r.clock, r.rules).mayBeSweet)
        r.at("22:30")
        for (t in listOf("CORE", "STRENGTH", "STRETCH")) {
            r.run("log_task", """{"task":"$t","rounds":3,"minutes":null,"feel":null,"note":""}""")
        }
        r.run("set_status", """{"type":"REST_DAY","note":""}""")
        assertTrue(Context(r.store, r.clock, r.rules).mayBeSweet)
        r.store.putDoc(Context.SWEET_DOC, Days.today(r.clock).toString())
        assertFalse(Context(r.store, r.clock, r.rules).mayBeSweet)
    }

    @Test fun aViolationTakesSweetAway() {
        val r = ToolRig(stage = 2)
        r.at("22:30")
        for (t in listOf("CORE", "STRENGTH", "STRETCH")) {
            r.run("log_task", """{"task":"$t","rounds":3,"minutes":null,"feel":null,"note":""}""")
        }
        r.run("set_status", """{"type":"REST_DAY","note":""}""")
        r.run("log_drink", """{"type":"caffeine"}""")
        assertFalse(Context(r.store, r.clock, r.rules).mayBeSweet)
    }

    @Test fun stageOneSaysWhatIsNotOpenAndNeverListsItsNumbersAsToday() {
        val r = ToolRig(stage = 1)
        val b = Context(r.store, r.clock, r.rules).block()
        assertTrue(b.contains("還沒開放：簽到簽退、三餐"))
        assertFalse(b.contains("吃：今天"))
        assertFalse(b.contains("喝水："))
        assertTrue(b.contains("下一階段會開放：簽到簽退"))
    }
}
