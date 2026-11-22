package app.agency.core.guard

import app.agency.core.persona.Roles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputGuardTest {
    private val facts = """{"today":{"kcal":{"eaten":629,"left":1071}},"rules":{"quota_kcal":1700}}"""
    private val ev = Evidence(listOf(facts, "我剛吃完"))

    private fun kept(text: String, e: Evidence = ev, mode: GuardMode = GuardMode.NORMAL) = OutputGuard.check(text, e, mode).kept
    private fun rules(text: String, e: Evidence = ev, mode: GuardMode = GuardMode.NORMAL) =
        OutputGuard.check(text, e, mode).violations.map { it.rule }.toSet()

    @Test fun anInventedNumberIsDroppedAndOneFromTheFactsKept() {
        assertEquals("現在去練。", kept("再吃 300 大卡。現在去練。"))
        assertEquals("你還有 1071 大卡。", kept("你還有 1071 大卡。"))
        assertEquals("現在去練。", kept("再吃兩百大卡。現在去練。"))
        assertEquals("今天只剩一千零七十一大卡。", kept("今天只剩一千零七十一大卡。"))
        val r = OutputGuard.check("再吃 300 大卡。", ev, GuardMode.NORMAL)
        assertEquals(listOf(GuardViolation(GuardRule.NUMBER, "數字「300」不在資料裡")), r.violations)
        assertTrue(r.needsRetry)
    }

    @Test fun deadlinesRepsAndEverydayWordsKeepTheirNumbers() {
        assertEquals("10 分鐘內傳截圖。", kept("10 分鐘內傳截圖。"))
        assertEquals("十分鐘內傳錄音。", kept("十分鐘內傳錄音。"))
        assertEquals("做 3 組，每組 15 下。", kept("做 3 組，每組 15 下。"))
        assertEquals("明天晚上還有三輪要做。", kept("明天晚上還有三輪要做。"))
        assertEquals("今天正常吃三餐。", kept("今天正常吃三餐。"))
        assertEquals("一天一份就好。", kept("一天一份就好。"))
        assertEquals("買一包雞胸，回來吃完就睡。", kept("買一包雞胸，回來吃完就睡。"))
    }

    @Test fun portedNumberCasesFromTheOldCoach() {
        val none = Evidence.NONE
        assertEquals("", kept("再吃兩百大卡。", none))
        assertEquals("", kept("延後二十一天。", none))
        assertEquals("", kept("再吃十一份。", none))
        assertEquals("", kept("十三餐。", none))
        assertEquals("現在去練。", kept("今天只吃八百卡。現在去練。", none))
        assertEquals("身體質量指數偏高。", kept("BMI偏高。", none))
        assertEquals("", kept("一公斤不算什麼。", none))
    }

    @Test fun idsInTheFactsAreNotEvidenceForNumbers() {
        val e = Evidence(listOf("""{"id":"meal-300a","other":"4f1c2d3e-0000-4000-8000-123456789abc"}"""))
        assertEquals("", kept("再吃 300 大卡。", e))
        assertEquals("", kept("再吃 123456789 大卡。", e))
    }

    @Test fun linksAreRemoved() {
        assertEquals("去吃。", kept("看這裡 https://example.com/x。去吃。"))
        assertEquals("去吃。", kept("上 www.example.tw 查。去吃。"))
        assertEquals("去吃。", kept("搜 example.com 就有。去吃。"))
        assertTrue(GuardRule.LINK in rules("上 www.example.tw 查。"))
    }

    @Test fun anUnknownShopNameIsDroppedButASearchResultTitleKept() {
        assertEquals("吃完拍給我。", kept("去阿明牛肉麵館吃。吃完拍給我。"))
        assertTrue(GuardRule.NAME in rules("去阿明牛肉麵館吃。"))
        val withResult = Evidence(listOf(facts), names = listOf("阿明牛肉麵館 永和店"))
        assertEquals("去阿明牛肉麵館吃。", kept("去阿明牛肉麵館吃。", withResult))
        // He named it himself: the words before the name are not part of it.
        val heSaid = Evidence(listOf("阿明牛肉麵館可以吃嗎"))
        assertEquals("附近阿明牛肉麵館可以。", kept("附近阿明牛肉麵館可以。", heSaid))
    }

    @Test fun genericPlacesAndChainsTheTurnShowedAreFine() {
        assertEquals("去便利商店買雞胸。", kept("去便利商店買雞胸。"))
        assertEquals("買個便當，去那家店也可以。", kept("買個便當，去那家店也可以。"))
        assertEquals("", kept("去麥當勞買。"))
        assertEquals("去全家買。", kept("去全家買。", Evidence(listOf("我在全家"))))
        assertEquals("", kept("吃雞腿便當。"))
        assertEquals("吃雞腿便當。", kept("吃雞腿便當。", Evidence(emptyList(), names = listOf("雞腿便當"))))
    }

    @Test fun latinIsRemovedExceptTheWhitelist() {
        assertEquals("現在去練。", kept("OK。現在去練。"))
        assertEquals("現在去練。", kept("ＯＫ。現在去練。"))
        assertEquals("像 BABYMONSTER 一樣練。", kept("像 BABYMONSTER 一樣練。"))
        assertEquals("先聽 DESERVE。", kept("先聽 DESERVE。"))
        assertEquals("先唱 On My Way。", kept("先唱 On My Way。"))
        assertEquals("你現在是 C 級。", kept("你現在是 C 級。"))
        assertEquals("", kept("你是 CB 級。"))
        assertEquals("", kept("你是 D 級。"))
        assertEquals("跟她一樣。", kept("跟 Ahyeon 一樣。跟她一樣。"))
        assertEquals("跟 Ahyeon 一樣。", kept("跟 Ahyeon 一樣。", Evidence(emptyList(), latin = listOf("Ahyeon"))))
    }

    @Test fun bannedPhrasesAndAttachmentPatternsAreHard() {
        for (s in listOf("沒關係，偶爾一次沒差。", "你已經很棒了。", "多跑一小時補回來。", "不要走，再陪我一下。", "你不在我會難過。",
            "只有我懂你。", "你這個廢物。", "晚餐跳過吧。", "明天斷食。", "今天少吃一點。", "你應該是腸胃炎。", "你怎麼還沒瘦。")) {
            val r = OutputGuard.check(s, ev, GuardMode.NORMAL)
            assertTrue(s, r.hard.any { it.rule == GuardRule.BANNED })
            assertTrue(s, r.needsRetry)
        }
        assertFalse(OutputGuard.check("炸雞不在規章裡，換雞胸。", ev, GuardMode.NORMAL).needsRetry)
        assertFalse(OutputGuard.check("你是不是生病了？不舒服就去看醫生。", ev, GuardMode.NORMAL).needsRetry)
    }

    @Test fun modeChecks() {
        assertTrue(GuardRule.MODE in rules("你覺得呢？還是早一點？", mode = GuardMode.DISCUSS))
        assertFalse(GuardRule.MODE in rules("我看了你的行程。要不要改成十點？", mode = GuardMode.DISCUSS))
        assertTrue(GuardRule.MODE in rules("我看了你的行程。", mode = GuardMode.DISCUSS))
        assertTrue(GuardRule.MODE in rules("起床。簽到。刷牙。", mode = GuardMode.NUDGE))
        assertFalse(GuardRule.MODE in rules("起床。簽到。", mode = GuardMode.NUDGE))
        assertTrue(GuardRule.MODE in rules("一。二。三。四。五。"))
        assertEquals(2, OutputGuard.questions("要改嗎。還是不改呢"))
    }

    @Test fun aPromiseNeedsAnAcceptedProposal() {
        assertTrue(GuardRule.PROMISE in rules("我已經幫你改了晚餐時間。"))
        assertTrue(GuardRule.PROMISE in rules("行程已經更新。"))
        val accepted = Evidence(listOf(facts), acceptedProposal = true)
        assertFalse(GuardRule.PROMISE in rules("我已經幫你改了晚餐時間。", accepted))
    }

    @Test fun theApprovedExamplesPassTheGuard() {
        val e = Evidence(listOf("今天的訓練是三輪"))
        for (x in Roles.MANAGER_EXAMPLES) {
            val r = OutputGuard.check(x.reply, e, GuardMode.NORMAL)
            assertEquals(x.reply, x.reply, r.kept)
            assertTrue(x.reply + r.violations, r.violations.isEmpty())
        }
    }

    @Test fun theLegacyFilterKeepsTheOldBehaviour() {
        assertEquals("現在去練。", OutputGuard.legacyFilter("再吃 300 大卡。現在去練。", "剩 1071 大卡", ""))
        assertEquals("你還有 1071 大卡。", OutputGuard.legacyFilter("你還有 1071 大卡。", "剩 1071 大卡", ""))
        assertEquals("一。二。三。四。", OutputGuard.legacyFilter("一。二。三。四。五。", "", ""))
    }
}
