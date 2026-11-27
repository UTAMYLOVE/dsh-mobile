package app.agency.core.guard

import app.agency.core.engine.Tone
import app.agency.core.persona.Roles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputGuardTest {
    private val facts = """{"today":{"day":"2026-11-20","kcal":{"eaten":629,"left":1071}},"rules":{"quota_kcal":1700}}"""
    private val ev = Evidence(listOf(facts), ownerText = "我剛吃完")

    private fun kept(text: String, e: Evidence = ev, mode: GuardMode = GuardMode.NORMAL) = OutputGuard.check(text, e, mode).kept
    private fun rules(text: String, e: Evidence = ev, mode: GuardMode = GuardMode.NORMAL, tone: Tone = Tone.COLD) =
        OutputGuard.check(text, e, mode, tone).violations.map { it.rule }.toSet()
    private fun hard(text: String, tone: Tone = Tone.COLD, e: Evidence = ev) = OutputGuard.check(text, e, GuardMode.NORMAL, tone).hard.isNotEmpty()

    @Test fun anInventedNumberIsDroppedAndOneFromTheFactsKept() {
        assertEquals("現在去練。", kept("再吃 300 大卡。現在去練。"))
        assertEquals("你還有 1071 大卡。", kept("你還有 1071 大卡。"))
        assertEquals("現在去練。", kept("再吃兩百大卡。現在去練。"))
        assertEquals("今天只剩一千零七十一大卡。", kept("今天只剩一千零七十一大卡。"))
        val r = OutputGuard.check("再吃 300 大卡。", ev, GuardMode.NORMAL)
        assertEquals(listOf(GuardViolation(GuardRule.NUMBER, "數字「300」不在資料裡")), r.violations)
        assertTrue(r.needsRetry)
    }

    @Test fun chineseFiguresFullWidthDigitsApproximationsAndRangesAreChecked() {
        for (s in listOf("還剩一千二。", "今天還能吃兩百多大卡。", "還剩八百左右。", "還剩１２００大卡。", "大概兩三百大卡。", "再吃二十克。")) {
            assertEquals(s, "", kept(s))
        }
        assertEquals("吃到 1,071 大卡就停。", kept("吃到 1,071 大卡就停。"))
        assertEquals("還剩１０７１大卡。", kept("還剩１０７１大卡。"))
        assertEquals("千萬別跳過晚餐。", kept("千萬別跳過晚餐。"))
        assertEquals("這十分重要。", kept("這十分重要。"))
        assertEquals("十幾分鐘就好。", kept("十幾分鐘就好。"))
        assertEquals("第二輪開始。", kept("第二輪開始。"))
    }

    @Test fun datesAndTimesInTheFactsAreNotEvidenceForOtherNumbers() {
        val withTimes = Evidence(listOf("""{"day":"2026-11-20","last_days":[{"day":"2026-11-15"}],"time":"12:10","slots":[{"time":"21:00"}]}"""))
        for (s in listOf("蛋白質還差 20 克。", "再吃 15 克蛋白質。", "還有 2026 大卡。", "再吃十九克。", "還差 12 克。")) assertEquals(s, "", kept(s, withTimes))
        // The same tokens as a whole are fine; another time is not.
        assertEquals("21:00 吃雞胸。", kept("21:00 吃雞胸。", withTimes))
        assertEquals("", kept("22:00 吃雞胸。", withTimes))
        assertEquals("11/20 考試加油。", kept("11/20 考試加油。", withTimes))
        // His own message is his words: its dates and numbers count.
        assertEquals("你 11/27 考完再說。", kept("你 11/27 考完再說。", Evidence(emptyList(), ownerText = "我 11/27 考完")))
        assertEquals("你說 20 號要考試。", kept("你說 20 號要考試。", Evidence(emptyList(), ownerText = "20號要考試")))
    }

    @Test fun deadlinesRepsAndEverydayWordsKeepTheirNumbers() {
        assertEquals("10 分鐘內傳截圖。", kept("10 分鐘內傳截圖。"))
        assertEquals("十分鐘內傳錄音。", kept("十分鐘內傳錄音。"))
        assertEquals("做 3 組，每組 15 下。", kept("做 3 組，每組 15 下。"))
        assertEquals("明天晚上還有三輪要做。", kept("明天晚上還有三輪要做。"))
        assertEquals("今天正常吃三餐。", kept("今天正常吃三餐。"))
        assertEquals("一天一份就好。", kept("一天一份就好。"))
        assertEquals("買一包雞胸，回來吃完就睡。", kept("買一包雞胸，回來吃完就睡。"))
        assertEquals("給你兩個選擇。", kept("給你兩個選擇。"))
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

    @Test fun linksAndFileNamesAreRemoved() {
        for (s in listOf("看這裡 https://example.com/x。", "上 www.example.tw 查。", "搜 example.com 就有。", "看 youtu.be/xyz。",
            "點 bit.ly/abc。", "照片是 IMG_20261120.jpg。")) {
            assertEquals(s, "去吃。", kept(s + "去吃。"))
        }
        assertTrue(GuardRule.LINK in rules("上 www.example.tw 查。"))
    }

    @Test fun dishNamesAndCategoryShopsPassButInventedShopNamesDoNot() {
        for (s in listOf("吃雞腿便當。", "吃烤雞腿便當。", "吃排骨便當也可以。", "附近的滷味店可以買燙青菜。", "去藥妝店買。", "這樣可不可以？",
            "跟全家人一起吃也行。", "晚餐吃牛肉麵。", "買個便當，去那家店也可以。", "去便利商店買雞胸。")) {
            assertEquals(s, s, kept(s))
        }
        for (s in listOf("吃阿明雞腿便當。", "吃池上便當。", "去老王滷味店。", "去阿明牛肉麵館吃。", "去麥當勞買。")) {
            assertEquals(s, "", kept(s))
            assertTrue(s, GuardRule.NAME in rules(s))
        }
        val withResult = Evidence(listOf(facts), names = listOf("阿明牛肉麵館 永和店"))
        assertEquals("去阿明牛肉麵館吃。", kept("去阿明牛肉麵館吃。", withResult))
        assertEquals("附近阿明牛肉麵館可以。", kept("附近阿明牛肉麵館可以。", Evidence(emptyList(), ownerText = "阿明牛肉麵館可以吃嗎")))
        assertEquals("去全家買。", kept("去全家買。", Evidence(emptyList(), ownerText = "我在全家")))
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

    @Test fun eatingLessSkippingAndBurningItOffAreBanned() {
        val mustFail = listOf(
            "今天吃超過了，明天晚餐吃一半。", "吃多了，下一餐減量。", "今天少吃一點。", "晚餐跳過吧。", "明天斷食。", "多跑一小時補回來。",
            "今天吃超過了。明天宵夜不吃。", "吃太多了，去騎車燒掉。", "不舒服就斷食。", "別吃晚餐了。",
        )
        mustFail.forEach { assertTrue(it, hard(it)) }
        val mustPass = listOf(
            "晚餐至少吃一份雞胸。", "不舒服也多少吃一點。", "休假餐以外少吃炸的。", "這餐少吃加工的。", "別空腹出門跑單。", "不要餓肚子騎車。",
            "千萬別跳過晚餐。", "晚餐別跳過。", "上課日不吃早餐。", "21:00 吃過雞胸了，就不要吃宵夜。", "午餐蛋白質不夠，晚餐補回來。", "下次可以加練背包划船。",
            "訓練完補充蛋白質。", "今天狀態差，肌力可以減量。", "今天超過了一點，明天回到 1700。",
        )
        val e = Evidence(listOf("""{"time":"21:00","quota_kcal":1700}"""))
        mustPass.forEach { assertFalse(it + OutputGuard.check(it, e, GuardMode.NORMAL).violations, OutputGuard.check(it, e, GuardMode.NORMAL).needsRetry) }
    }

    @Test fun attachmentInsultsPressureAndDiagnosisAreHard() {
        for (s in listOf("不要走，再陪我一下。", "你不在我會難過。", "只有我懂你。", "你這個廢物。", "你應該是腸胃炎。", "你怎麼還沒瘦。",
            "我幫你診斷一下。", "這樣很丟臉。")) {
            assertTrue(s, hard(s))
        }
        assertFalse(hard("炸雞不在規章裡，換雞胸。"))
        assertFalse(hard("你是不是生病了？不舒服就去看醫生。"))
        assertFalse(hard("你做到了，我好感動。"))
    }

    @Test fun comfortDependsOnTheTone() {
        // Excuses for a slip: banned in every tone.
        for (t in Tone.entries) {
            assertTrue(hard("偶爾一次沒差。", t))
            assertTrue(hard("你已經很棒了。", t))
            assertTrue(hard("吃炸的沒關係。", t))
        }
        val gentle = listOf("身體不舒服沒關係，先休息。", "別在意同學說的話，先去睡。", "沒辦法幫你診斷，去看醫生。", "去看醫生不丟臉。")
        gentle.forEach { assertFalse(it, hard(it, Tone.GENTLE)) }
        assertTrue(hard("身體不舒服沒關係，先休息。", Tone.COLD))
        // A breach today: GENTLE comfort only about his body or feelings.
        val breach = Evidence(listOf(facts), breachToday = true)
        assertTrue(hard("沒關係，明天再說。", Tone.GENTLE, breach))
        assertFalse(hard("頭暈沒關係，先躺著。", Tone.GENTLE, breach))
        assertFalse(hard("沒關係，明天再說。", Tone.GENTLE))
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
        assertTrue(GuardRule.PROMISE in rules("鬧鐘改好了。"))
        assertFalse(GuardRule.PROMISE in rules("你的深蹲姿勢改好了。"))
        val accepted = Evidence(listOf(facts), acceptedProposal = true)
        assertFalse(GuardRule.PROMISE in rules("我已經幫你改了晚餐時間。", accepted))
    }

    @Test fun theApprovedExamplesPassTheGuardInEveryTone() {
        val e = Evidence(listOf("今天的訓練是三輪"))
        for (x in Roles.MANAGER_EXAMPLES) for (t in Tone.entries) {
            val r = OutputGuard.check(x.reply, e, GuardMode.NORMAL, t)
            assertEquals(x.reply, x.reply, r.kept)
            assertTrue(x.reply + r.violations, r.violations.isEmpty())
        }
    }

    @Test fun theTemplatesPassTheGuard() {
        for ((_, byTone) in Roles.TEMPLATES) for ((t, text) in byTone) {
            val r = OutputGuard.check(text, Evidence.NONE, GuardMode.NORMAL, t)
            assertTrue(text + r.violations, r.violations.isEmpty())
        }
    }

    @Test fun memoryTextUsesTheToneFreeBans() {
        assertTrue(OutputGuard.bannedLabel("他說超過就叫他多跑步補回來")!!.contains("補"))
        assertEquals(null, OutputGuard.bannedLabel("他喜歡被直接糾正"))
    }

    @Test fun theLegacyFilterKeepsTheOldBehaviour() {
        assertEquals("現在去練。", OutputGuard.legacyFilter("再吃 300 大卡。現在去練。", "剩 1071 大卡", ""))
        assertEquals("你還有 1071 大卡。", OutputGuard.legacyFilter("你還有 1071 大卡。", "剩 1071 大卡", ""))
        assertEquals("一。二。三。四。", OutputGuard.legacyFilter("一。二。三。四。五。", "", ""))
        // The old facts never list his places, so the name rule stays out of the old coach.
        assertEquals("去全家買雞胸。", OutputGuard.legacyFilter("去全家買雞胸。", "", ""))
    }

    private val corpusFacts = """{"rules":{"quota_kcal":1700,"floor_kcal":1200,"meal_cap_kcal":800,"cheat_meals_per_week":1},
        "today":{"day":"2026-11-20","slots":[{"time":"12:00"},{"time":"18:30"},{"time":"21:00"}],"kcal":{"eaten":629,"left":1071,"floor_left":571},"protein":{"left":102}}}"""
    private val cev = Evidence(listOf(corpusFacts))
    private fun cflag(s: String, tone: Tone = Tone.COLD, e: Evidence = cev) = OutputGuard.check(s, e, GuardMode.NORMAL, tone).violations.isNotEmpty()

    @Test fun protectiveOrdersInANegatedClausePass() {
        for (s in listOf("晚餐不要少吃。", "吃超過了也不要少吃，明天回到 1700。", "吃多了，下一餐照常吃，別跳過。", "今天超過了，不用補，明天回到 1700。",
            "別因為忙就不吃午餐。", "不准不吃晚餐。", "跳過午餐不行。", "晚餐不吃不行，去買。", "這餐不要超過 800，炸的不要吃。", "吃多了，少喝含糖飲料。")) {
            assertFalse(s, cflag(s))
        }
        assertFalse(cflag("不舒服也別少吃，正常吃。", Tone.GENTLE))
    }

    @Test fun compensationAfterAnOverrunIsCaughtWhateverTheWords() {
        for (s in listOf("今天超過額度，明天補回來。", "今天超過太多，明天中午不吃。", "今天爆卡，明天清淡一點、少一點。", "熱量超標，晚餐只喝湯。",
            "多吃了一塊雞排，今天就不要再吃了。", "今天吃超過了，晚餐吃一點點就好。", "晚餐乾脆不要吃。", "明晚份量少一點。", "吃多了，下一頓減量。",
            "吃多了，晚上那份減量。", "今天吃太多了，晚餐份量砍半。", "今天超過了，晚上不要再吃東西。", "今天吃超過了，明天晚餐飯吃半碗。",
            "吃過頭了，明天一整天只喝豆漿。", "吃多了，晚上多做兩輪。")) {
            assertTrue(s, OutputGuard.check(s, cev, GuardMode.NORMAL).hard.isNotEmpty())
        }
        assertFalse(cflag("今天狀態差，肌力可以減量。"))
        assertFalse(cflag("吃牛肉湯麵，麵吃一半就好。"))
    }

    /** Owner 2026-11-26: the same day's next meal or dinner may be 「少一點」; skipping, none, tomorrow and exercise stay banned. */
    @Test fun aSameDaySmallerNextMealPassesAndEverythingElseStaysBanned() {
        for (s in listOf("午餐吃多了，晚餐少一點。", "今天吃超過了，下一餐少一點就好，不要跳過。", "晚餐份量小一點，蛋白質照吃。", "下一餐吃少一點，還是要吃。", "今晚份量少一點。")) {
            assertFalse(s, cflag(s))
        }
        for (s in listOf("明天晚餐少一點。", "今天吃超過了，晚餐少一點，明天再少吃一點。", "吃多了，晚餐不吃了。", "吃太多了，下一餐跳過。",
            "吃多了，去多跑兩圈把熱量燒掉。", "今天超標，明天少吃補回來。", "吃多了，下一餐只吃一半。")) {
            assertTrue(s, cflag(s))
        }
        assertTrue(SecondPassJudge.SYSTEM.contains("同一天的下一餐或晚餐「少一點」"))
        assertTrue(SecondPassJudge.SYSTEM.contains("明天或之後少吃來補回來"))
    }

    @Test fun dishNamesOutsideTheLexiconPass() {
        for (s in listOf("可以點番茄炒蛋飯。", "點一份蔥爆牛肉飯。", "晚餐吃三杯雞飯。", "午餐吃海南雞飯。", "吃宮保雞丁飯也行。", "吃味噌湯配烤魚定食。",
            "去水餃店買。", "健康餐盒店有賣。", "明天便當帶雞胸。", "回家煮麵加蛋。", "自己煮飯比較好。")) {
            assertFalse(s, cflag(s))
        }
        for (s in listOf("吃池上便當。", "吃阿明雞腿便當。", "去老王滷味店。", "去王記水餃店。", "去阿美早餐店。", "去老張牛肉麵。", "去阿華滷味買。",
            "推薦你去好食堂。", "去小七買茶葉蛋。", "去統一超商買雞胸。", "去悟饕便當買。")) {
            assertTrue(s, OutputGuard.check(s, cev, GuardMode.NORMAL).violations.any { it.rule == GuardRule.NAME })
        }
    }

    @Test fun gentleCareAndHonestDiagnosisLinesPass() {
        for (s in listOf("身體不舒服沒關係，晚餐正常吃。", "感冒沒關係，多喝溫水。", "發燒沒關係，先喝水、吃點東西再睡。", "心情不好沒關係，今天一輪就好。",
            "這不是我能診斷的，去看醫生。", "醫生才能診斷，去掛號。", "生病請假不用覺得丟臉。", "這沒什麼好丟臉的。", "不要緊，先睡。")) {
            assertFalse(s, cflag(s, Tone.GENTLE))
        }
        for (s in listOf("吃炸的沒關係。", "今天沒練沒關係。", "聽起來像腸胃炎，多喝水。", "你這應該是胃食道逆流。", "我幫你診斷一下。", "這樣很丟臉。")) {
            assertTrue(s, cflag(s, Tone.GENTLE))
        }
    }

    @Test fun excusesAndAttachmentInOtherWordsAreCaught() {
        for (s in listOf("不要緊，下次注意。", "這次就算了。", "吃一點炸的也還好啦。", "放鬆一下也好，吃吧。", "你已經很努力了，吃一點沒事。",
            "今天怎麼都不找我？", "想你了，快回來練。", "一次兩次不會怎樣。", "你都不跟我說話了。")) {
            assertTrue(s, OutputGuard.check(s, cev, GuardMode.NORMAL).hard.isNotEmpty())
        }
        assertFalse(cflag("我算了一下，今天還剩 1071 大卡。"))
    }

    @Test fun datesInToolTextAreNotEvidenceForOtherNumbers() {
        val tool = Evidence(listOf("""{"results":[{"title":"某店","summary":"11月20日公休，12/25 營業"}]}"""))
        for (s in listOf("再吃 20 克。", "還差 25 克。", "還差 12 克。")) assertEquals(s, "", kept(s, tool))
        assertEquals("12/25 有營業。", kept("12/25 有營業。", tool))
    }

    @Test fun aFullWidthCommaSeparatesNumbers() {
        assertEquals("上限是 800，1071 是今天剩的。", kept("上限是 800，1071 是今天剩的。", cev))
        assertEquals(listOf(800.0, 1071.0), CoachNumbers.all("上限是 800，1071"))
        assertEquals(listOf(1071.0), CoachNumbers.all("1,071"))
    }

    @Test fun clockTimesRuleCountsAndChineseDatesAreChecked() {
        assertEquals("晚上九點前吃完。", kept("晚上九點前吃完。", cev))
        assertEquals("", kept("晚上八點前吃完。", cev))
        assertEquals("六點半吃晚餐。", kept("六點半吃晚餐。", Evidence(listOf("""{"time":"18:30"}"""))))
        assertEquals("晚一點再睡也可以。", kept("晚一點再睡也可以。", cev))
        assertEquals("", kept("這週可以有兩次休假餐。", cev))
        assertEquals("這週還有一次休假餐。", kept("這週還有一次休假餐。", cev))
        assertEquals("", kept("十二月五號要考試對吧。", cev))
        assertEquals("十一月二十日考試加油。", kept("十一月二十日考試加油。", cev))
    }

    @Test fun aFigureSaidToBeLeftMustBeALeftValue() {
        // 1200 is the floor in the rules, not what is left today.
        assertEquals("", kept("還剩一千二。", cev))
        assertEquals("", kept("還剩千二。", cev))
        assertEquals("還剩 1071 大卡。", kept("還剩 1071 大卡。", cev))
        assertEquals("蛋白質還差 102 克。", kept("蛋白質還差 102 克。", cev))
        assertEquals("額度是 1200 起跳。", kept("額度是 1200 起跳。", cev))
    }

    @Test fun makingUpAPhotoOrASetIsNoCompensation() {
        assertFalse(cflag("訓練照片記得補拍。"))
        assertFalse(cflag("今天訓練沒做完，明天補做。"))
        assertTrue(cflag("訓練多做一點把熱量補回來。"))
    }

    @Test fun linksAreFoundAsTheDeviceReadsWordBoundaries() {
        // Android's regex counts Han as a word character; UNICODE_CHARACTER_CLASS gives the JVM the same view.
        for ((re, text) in listOf(Links.URL to "上網搜example.com就有", Links.FILE to "IMG_1.jpg的照片")) {
            val device = java.util.regex.Pattern.compile(re.pattern, java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
            assertTrue(text, device.matcher(text).find())
        }
    }
}
