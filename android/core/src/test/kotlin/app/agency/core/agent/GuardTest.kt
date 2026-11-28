package app.agency.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GuardTest {
    /** Invented facts, in the shape a context block has. */
    private val facts = """
        現在：2030-01-07 星期一 18:40 上課日。第 2 階段 · 第 3 天。
        今天吃了 1200 大卡（目標 2000），還剩 800 大卡；蛋白質 60 克（目標 100 克）。晚餐預算 700 大卡。每餐上限 900 大卡。
        喝水 1 壺（目標 2 壺，1000 毫升一壺）。跑單前 19:30 要喝完。晚餐 18:00，宵夜 21:30。
        肌力 3 輪：伏地挺身 10 下、深蹲 15 下、棒式 40 秒。核心 5 分鐘。完成 60%。
        本週跑單收入 3,000 元，目標 6000 元，還差 3000 元，剩 4 天，每天約 750 元。
        影片搜尋結果：「測試發聲入門」 https://www.youtube.com/watch?v=abc123
        地點搜尋結果：「測試健康餐盒」
    """.trimIndent()

    private val flags = GuardFlags(closedFeatureWords = listOf("快走", "量體重"), neverBelowKg = 60.0)

    private fun ok(reply: String, owner: String = "", f: GuardFlags = flags) {
        val r = Guard.check(reply, facts, owner, f)
        if (r is GuardResult.Fix) fail("expected ok for 「$reply」 but got ${r.problems}")
    }

    private fun bad(reply: String, owner: String = "", f: GuardFlags = flags) {
        val r = Guard.check(reply, facts, owner, f)
        if (r !is GuardResult.Fix) fail("expected a problem in 「$reply」")
    }

    @Test fun numbersThatAreFactsPass() = listOf(
        "記好了，今天 1200 大卡，還剩 800 大卡。",
        "晚餐預算 700 大卡，蛋白質還差一些，目前 60 克。",
        "水喝了 1 壺，19:30 前要喝完第 2 壺。",
        "肌力 3 輪，每輪伏地挺身 10 下、深蹲 15 下，棒式 40 秒。",
        "本週收入 3000 元，還差 3,000 元。",
        "這週還差３０００元，平均每天７５０元。",
        "完成 60%，繼續。",
        "宵夜 21:30，別太晚。",
        "晚餐 18:00 吃了嗎？",
        "記好了，一共 1,200大卡。",
        "目前第 3 天。",
        "還剩八百大卡。",
        "這餐份量 700–800 大卡之間都可以。",
        "再撐一下，你可以的。",
        "等一下下午再說。",
        "三分之一的路了。",
        "你剛說吃了 2 個便當，我記好了。",
    ).forEach { ok(it) }

    @Test fun numbersFromHisOwnMessagePass() {
        ok("好，跑了 1500 元，記下了。", owner = "今天跑了1500元")
        ok("體重 78.5 公斤，記好了。", owner = "今天量78.5")
    }

    @Test fun numbersNotInTheFactsFail() = listOf(
        "記好了，今天 1300 大卡。",
        "還剩 720 大卡，晚餐小一點。",
        "蛋白質還差 35 克。",
        "這份大概 650 卡。",
        "喝水 1.5 壺了。",
        "棒式 45 秒就好。",
        "這週還差 2500 元。",
        "完成 70%，很好。",
        "晚餐 300–400 大卡就好。",
        "晚餐 700–850 大卡就好。",
        "晚餐 19:00 前吃完。",
        "你今天已經走了 8000 下。",
    ).forEach { bad(it) }

    @Test fun skippingAMealOrFastingFails() = listOf(
        "今天晚餐就不要吃了。",
        "宵夜別吃了吧。",
        "乾脆跳過這餐。",
        "跳過晚餐也沒差。",
        "不吃宵夜就好。",
        "可以試試斷食。",
        "這餐免了。",
    ).forEach { bad(it) }

    @Test fun mealAdviceThatIsAllowedPasses() = listOf(
        "不要跳餐，晚餐照吃，份量小一點。",
        "千萬別斷食。",
        "不能不吃晚餐。",
        "宵夜不要吃炸的，選雞胸。",
        "下一餐小一點。",
        "晚餐吃清淡一點。",
        "不要讓自己餓肚子。",
        "吃太多會讓身體囤積脂肪，腰會變粗。",
    ).forEach { ok(it) }

    @Test fun makingItUpTomorrowFails() = listOf(
        "明天少吃一點就好。",
        "明天再扣回來。",
        "隔天吃少一點抵消掉。",
        "明天的晚餐份量少一點。",
    ).forEach { bad(it) }

    @Test fun tomorrowSentencesThatAreFinePass() = listOf(
        "明天不用少吃，照計畫吃。",
        "明天早上記得簽到。",
        "明天見。",
        "今天下一餐小一點，明天照常。",
    ).forEach { ok(it) }

    @Test fun grantingALighterTrainingHerselfFails() = listOf(
        "今天不用練了。",
        "累的話就別練了。",
        "核心今天跳過吧。",
        "肌力少做一輪沒關係。",
        "訓練減半就好。",
        "放你一天假。",
    ).forEach { bad(it) }

    @Test fun trainingSentencesThatAreFinePass() = listOf(
        "累是真的，但計畫不變，先做第一輪。",
        "核心不能跳過。",
        "不能少做一輪。",
        "練完記得伸展。",
        "別練太晚，十二點前收。",
        "今天想睡就不要去跑單。",
    ).forEach { ok(it) }

    @Test fun aReductionCodeMadeMaySpokenOf() {
        ok("你生病了，今天肌力少做一輪，程式已經調好了。", f = flags.copy(unwellReducedByCode = true))
        ok("受傷了就別練了，先休息。", f = flags.copy(unwellReducedByCode = true))
    }

    @Test fun shamingFails() = listOf("你真的很懶。", "你太胖了。", "這樣很丟臉。", "廢物才會這樣。").forEach { bad(it) }

    @Test fun plainWordsThatLookCloseToShamingPass() = listOf(
        "這不丟臉，誰都會這樣。",
        "沒什麼好丟臉的。",
        "你懶得動也先做第一輪。",
        "你很棒，繼續。",
        "吃太多會變胖，這是事實。",
    ).forEach { ok(it) }

    @Test fun pushingWeightUnderTheFloorFails() {
        bad("目標瘦到 55 公斤。", owner = "我想要55公斤")
        ok("目標 72 公斤，慢慢來。", owner = "目標72公斤")
        ok("目標瘦到 55 公斤不行，那太低了。", owner = "我想要55公斤", f = flags.copy(neverBelowKg = 0.0))
    }

    @Test fun linksAndNamesMustComeFromTheSearch() {
        ok("我找到「測試發聲入門」這支影片，卡片上可以直接看。")
        ok("「測試健康餐盒」這家可以。")
        bad("去看 https://example.com/video 這支。")
        ok("連結在這：https://www.youtube.com/watch?v=abc123")
        bad("推薦你看《每天十分鐘練聲》這支影片。")
        bad("附近那家「好吃牛肉麵」不錯。")
        ok("按「下一件事」就好。")
        ok("你說的「好吃牛肉麵」那家，我先查一下。", owner = "我想吃好吃牛肉麵")
    }

    @Test fun closedFeaturesAreNotAskedFor() {
        bad("記得去快走。")
        bad("現在去量體重。")
        ok("快走的部分之後才開始，我先記下了。")
        ok("量體重還沒開放，等下一階段。")
        ok("你自己去快走了？很好，我先記下來。", f = flags.copy(closedFeatureWords = listOf("跑步")))
    }

    @Test fun stripRemovesOnlyTheOffendingSentences() {
        val reply = "記好了。今天 1300 大卡。晚餐小一點，記得喝水！"
        val r = Guard.check(reply, facts, "", flags) as GuardResult.Fix
        assertEquals(listOf("今天 1300 大卡。"), r.problems.map { it.sentence })
        assertEquals("記好了。晚餐小一點，記得喝水！", Guard.strip(reply, r.problems))
    }

    @Test fun stripKeepsLinesAndCanLeaveNothing() {
        val reply = "記好了。\n明天少吃一點。\n先去喝水。"
        val r = Guard.check(reply, facts, "", flags) as GuardResult.Fix
        assertEquals("記好了。\n先去喝水。", Guard.strip(reply, r.problems))
        val only = "明天少吃一點。"
        assertEquals("", Guard.strip(only, (Guard.check(only, facts, "", flags) as GuardResult.Fix).problems))
    }

    @Test fun reasonsAreInTraditionalChineseWithoutLatinLetters() {
        val r = Guard.check("今天 1300 大卡。明天少吃。今天不用練了。你很懶。記得去快走。", facts, "", flags) as GuardResult.Fix
        assertEquals(5, r.problems.size)
        r.problems.forEach { p -> assertTrue(p.reason, p.reason.none { it in 'A'..'Z' || it in 'a'..'z' }) }
    }

    @Test fun sentencesJoinBackExactly() {
        val t = "好。「你說的對」！真的嗎？？\n下一行～沒有句號"
        assertEquals(t, Guard.sentences(t).joinToString(""))
    }
}
