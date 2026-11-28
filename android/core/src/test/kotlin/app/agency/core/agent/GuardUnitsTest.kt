package app.agency.core.agent

import app.agency.core.agent.tools.ToolRig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The guard's unit families and its closed-feature check, with natural sentences (all data invented). */
class GuardUnitsTest {
    private fun ok(reply: String, facts: String, owner: String = "", flags: GuardFlags = GuardFlags()) {
        val r = Guard.check(reply, facts, owner, flags)
        if (r is GuardResult.Fix) fail("expected ok for 「$reply」 but got ${r.problems}\nfacts: $facts")
    }

    private fun bad(reply: String, facts: String, owner: String = "", flags: GuardFlags = GuardFlags()) {
        if (Guard.check(reply, facts, owner, flags) !is GuardResult.Fix) fail("expected a problem in 「$reply」\nfacts: $facts")
    }

    // ---- numbers: the same unit family only ----

    @Test fun aKcalFigureThatIsOnlyAnEntryIdOrGramsOrMoneyFails() {
        val tool = """{"entry_id":12,"kcal":300,"protein_g":35,"date":"2030-01-07"}"""
        ok("這餐 300 大卡，蛋白質 35 克。", tool)
        bad("這餐 12 大卡。", tool) // the entry id
        bad("這餐 35 大卡。", tool) // grams
        bad("蛋白質 300 克。", tool) // kcal
        bad("今天 7 大卡。", tool) // a date
        bad("記好了，2030 大卡。", tool)
        val context = "今天的紀錄：\n・#12 12:30 午餐：便當 300 大卡，共 300 大卡、蛋白質 35 克，有照片\n本週收入 3000 元／目標 6000 元"
        ok("午餐 300 大卡。", context)
        bad("午餐 12 大卡。", context)
        bad("今天 3000 大卡。", context)
        bad("今天 30 大卡。", context) // only a time
        ok("本週收入 3000 元，目標 6000 元。", context)
    }

    @Test fun numbersOfHisOwnMessagePassInAnyUnit() {
        ok("好，這餐 450 大卡記下了。", "{}", owner = "午餐便當450")
        bad("好，這餐 460 大卡記下了。", "{}", owner = "午餐便當450")
    }

    @Test fun eachFamilyMatchesItsOwnKeys() {
        val f = """{"kcal_left":-120,"protein_left_g":12.5,"kg":70.5,"waist_cm":75,"water_left_ml":500,"water_bottles":1.5,""" +
            """"late_minutes":15,"plank_seconds":40,"round_pct":80,"score":4,"rounds":3,"days_ridden":2,""" +
            """"week_income":{"income":900,"target":2000,"left":1100,"remaining_days":3,"per_remaining_day":367},""" +
            """"next_meal_budget_kcal":{"晚餐":650},"month":{"living":300,"fun":50,"spent":350}}"""
        listOf(
            "今天超過 120 大卡。", "蛋白質還差 12.5 克。", "體重 70.5 公斤，腰圍 75 公分。", "還差 500 毫升，喝了 1.5 壺。",
            "晚了 15 分鐘。", "棒式 40 秒。", "完成 80%。", "狀態 4 分。", "做了 3 輪。", "跑了 2 天。", "本週收入 900 元，還差 1100 元。",
            "剩 3 天，平均每天 367 元。", "晚餐 650 大卡。", "這個月生活 300 元、娛樂 50 元，共 350 元。",
        ).forEach { ok(it, f) }
        listOf(
            "還差 1100 大卡。", "跑了 3 天又 900 大卡。", "晚餐 650 元。", "腰圍 70.5 公分。", "棒式 15 秒。", "完成 3%。",
            "狀態 3 分。", "還剩 367 大卡。", "還差 12.5 毫升。",
        ).forEach { bad(it, f) }
    }

    /** Every number of every tool's facts, said back with its family's unit, passes; said with a kcal unit it does not. */
    @Test fun everyToolNumberPassesWithItsOwnUnit() {
        val r = ToolRig(stage = 4)
        r.at("07:10")
        val outs = listOf(
            r.run("check_in", """{"score":4}"""),
            r.run("log_meal", """{"slot":"first","items":[{"name":"拉麵","kcal":510,"protein_g":20.7}],"source":"estimate","attach_photo":false}"""),
            r.run("log_water", """{"bottles":1,"ml":null}"""),
            r.run("log_task", """{"task":"STRENGTH","rounds":2,"minutes":null,"feel":"hard","note":""}"""),
            r.run("log_body", """{"kg":70.5,"waist_cm":75}"""),
            r.run("log_mood", """{"score":3,"note":"還好"}"""),
            r.run("log_money", """{"amount":125,"bucket":"living","note":"午餐"}"""),
            r.run("memo_add", """{"text":"交作業","due_at":"18:30"}"""),
            r.run("ride_out", "{}"),
            r.run("ride_back", """{"income":1150}"""),
            r.run("get_day", """{"date":"${r.monday}"}"""),
            r.run("get_week", "{}"),
            r.run("lessons_today", "{}"),
        )
        var checked = 0
        for (out in outs) {
            assertTrue(out.facts.toString(), out.ok)
            val facts = out.facts.toString()
            numbers(out.facts, null, null) { key, parent, v ->
                val family = Guard.familyOfKey(key, parent) ?: return@numbers
                val n = kotlin.math.abs(v).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
                ok("記好了，是 $n ${UNIT.getValue(family)}。", facts)
                checked++
            }
            // Text values (notes, item lines) are quoted as they are.
            texts(out.facts) { t -> Guard.sentences(t).filter { it.isNotBlank() }.forEach { ok(it, facts) } }
        }
        assertTrue(checked > 30)
        // A money amount is never a kcal figure, a check-in score never minutes.
        bad("這餐 125 大卡。", outs[6].facts.toString())
        bad("晚了 4 分鐘。", outs[0].facts.toString())
    }

    private val UNIT = mapOf(
        Guard.Family.KCAL to "大卡", Guard.Family.GRAM to "克", Guard.Family.KG to "公斤", Guard.Family.CM to "公分",
        Guard.Family.WATER to "毫升", Guard.Family.MINUTE to "分鐘", Guard.Family.SECOND to "秒", Guard.Family.MONEY to "元",
        Guard.Family.PERCENT to "%", Guard.Family.COUNT to "次", Guard.Family.DAY to "天", Guard.Family.SCORE to "分",
    )

    private fun numbers(e: JsonElement, key: String?, parent: String?, f: (String, String?, Double) -> Unit) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> numbers(v, k, key, f) }
            is JsonArray -> e.forEach { numbers(it, key, parent, f) }
            is JsonPrimitive -> if (!e.isString && key != null) e.content.toDoubleOrNull()?.let { f(key, parent, it) }
        }
    }

    private fun texts(e: JsonElement, f: (String) -> Unit) {
        when (e) {
            is JsonObject -> e.values.forEach { texts(it, f) }
            is JsonArray -> e.forEach { texts(it, f) }
            is JsonPrimitive -> if (e.isString) f(e.content)
        }
    }

    @Test fun aToolResultLineIsReadAsJson() {
        val facts = "現在：2030-01-07 7:10\n" + Json.parseToJsonElement("""{"entry_id":9,"meal_kcal":9}""").toString()
        ok("這餐 9 大卡。", facts)
        bad("這餐 7 大卡。", facts)
    }

    // ---- closed features: natural sentences per stage ----

    private fun closed(stage: Int) = GuardFlags(closedFeatureWords = Context.closedWords(stage))

    @Test fun closedFeatureSentencesThatMustPass() {
        val cases = mapOf(
            1 to listOf(
                "記得今天把舞蹈的課看完，再寫三行筆記。",
                "心情不好的話先跟我說說，再去做別的。",
                "你要不要先休息一下，心情好一點再說？",
                "應該先把核心觀念那一課看完。",
                "記得看完舞蹈影片，明天考試前再複習一次。",
                "今天先上舞蹈，記得看完寫心得。",
                "喝水的部分之後才開始，我先記下了。",
                "你自己去跑單了？我先記下來。",
            ),
            2 to listOf(
                "心情不好也記得把水喝完。",
                "今天心情怎麼樣？記得先吃晚餐。",
                "記得看完舞蹈課的影片。",
                "體能那堂課的筆記記得寫。",
                "先去喝水，再去跑單。",
                "儀態管理的教材記得看。",
            ),
            3 to listOf(
                "記得今天把舞蹈這一課看完。",
                "今天先看舞蹈的教學影片，再寫筆記。",
                "記得今天要上舞蹈。",
                "記得點心情分數給我。",
                "先去快走，再做體能。",
                "表情與鏡頭那一課記得做測驗。",
            ),
        )
        for ((stage, list) in cases) list.forEach { ok(it, "", flags = closed(stage)) }
    }

    @Test fun closedFeatureSentencesThatMustFail() {
        val cases = mapOf(
            1 to listOf("記得多喝水。", "現在去跑單吧。", "記得先簽到。", "先去做核心訓練。", "晚餐記得拍餐點照片給我。"),
            2 to listOf("記得給我心情分數。", "記得去快走二十分鐘。", "出門前記得擦防曬。", "先去做起床伸展。", "睡前記得點心情。"),
            3 to listOf("記得今天練舞三十分鐘。", "現在去做表情練習。", "記得修眉。", "週日記得拍全身照。", "記得先完成真人任務。"),
        )
        for ((stage, list) in cases) list.forEach { bad(it, "", flags = closed(stage)) }
    }

    @Test fun theEverydayWordForMoodAndSubjectNamesAreNotKeywords() {
        for (stage in 1..3) {
            val w = Context.closedWords(stage)
            assertTrue("心情" !in w && "舞蹈" !in w)
        }
        assertTrue("心情分數" in Context.closedWords(2) && "點心情" in Context.closedWords(2))
        assertEquals(emptyList<String>(), Context.closedWords(2).filter { w -> app.agency.core.lessons.Subjects.all.any { it.contains(w) } })
    }
}
