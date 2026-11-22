package app.dshmobile.coach

import app.agency.core.brain.handlers.BaselineAnswers
import app.dshmobile.coach.CoachRig.Companion.item
import app.dshmobile.coach.CoachRig.Companion.parsed
import app.dshmobile.gateway.InMemorySharedPreferences
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agency stage 1: 公司規章, the schedule, the roles, training, 入社建檔 and the update from the coach (v0.8.3). */
class AgencyTest {
    private val rig = CoachRig()

    @After fun tearDown() = rig.close()

    private fun say(text: String, parse: String = """{"kind":"chat"}"""): String = runBlocking {
        rig.model.parse = { ModelReply.Ok(parse) }
        rig.engine.handle(text)
        rig.store.messages.value.last().text
    }

    private fun zeroItem(name: String = "零卡可樂") =
        """{"name":"$name","portion":1,"fried":false,"zero_cal":true,"kcal_low":0,"kcal_high":1,"protein_low":0,"protein_high":0}"""

    // ---- 公司規章 ----

    @Test fun aFourthZeroCalDrinkInAWeekBreaksTheRule() {
        repeat(3) { say("喝了零卡可樂", parsed("meal", zeroItem())) }
        assertEquals(0, AgencyRules.violationsOn(rig.store.state.value.meals, "2026-11-21"))
        val r = say("喝了零卡可樂", parsed("meal", zeroItem()))
        assertTrue(r, r.contains("零卡飲料這週第 4 瓶，超過 3 瓶，違反公司規章。"))
        val last = rig.store.state.value.meals.last()
        assertTrue(last.offPlan)
        assertEquals(listOf("ZEROCAL"), last.breaches)
        assertEquals(1, rig.engine.ui.value.card.violations)
    }

    @Test fun theZeroCalCountStartsOverOnMonday() {
        // Sunday 2026-11-22 evening: three drinks.
        rig.clock.now = Instant.parse("2026-11-22T11:00:00Z")
        repeat(3) { say("喝了零卡可樂", parsed("meal", zeroItem())) }
        // Monday: a new company week.
        rig.clock.now = Instant.parse("2026-11-23T11:00:00Z")
        val r = say("喝了零卡可樂", parsed("meal", zeroItem()))
        assertFalse(r, r.contains("違反公司規章"))
    }

    @Test fun aZeroCalCravingIsRefusedOnceThreeAreDrunk() {
        repeat(3) { say("喝了零卡可樂", parsed("meal", zeroItem())) }
        val r = say("想喝零卡可樂", parsed("craving", zeroItem()))
        assertEquals("零卡飲料這週已經喝了 3 瓶，上限 3 瓶，不准再喝。", r)
    }

    @Test fun theNameAloneMarksAZeroCalDrinkEvenWhenTheModelForgets() {
        repeat(3) { say("喝了零卡可樂", parsed("meal", item("零卡可樂", low = 0, high = 1))) }
        assertTrue(say("喝了零卡可樂", parsed("meal", item("零卡可樂", low = 0, high = 1))).contains("違反公司規章"))
    }

    @Test fun aSingleMealOver700BreaksTheRuleEvenWhenTheDayFits() {
        val r = say("晚餐吃了牛肉麵加滷味", parsed("meal", item("牛肉麵", low = 600, high = 700)))
        // 700 + 10% = 770 kcal: one meal above 700.
        assertTrue(r, r.contains("單餐超過 700 大卡，違反公司規章。下一餐照計畫。"))
        assertEquals(listOf("OVER700"), rig.store.state.value.meals.last().breaches)
        assertFalse(r, r.contains("少吃"))
    }

    @Test fun exactly700IsAllowed() {
        val r = say("吃了便當", parsed("meal", item("便當", low = 600, high = 636)))
        // 636 × 1.1 = 699.6 → 700.
        assertTrue(r, r.contains("700 大卡"))
        assertFalse(r, r.contains("違反公司規章"))
    }

    @Test fun the700RuleHoldsOnTheFridayCheatMealToo() {
        rig.clock.now = Instant.parse("2026-11-20T11:00:00Z") // Friday 19:00
        val r = say("吃了炸臭豆腐兩盤", parsed("meal", item("炸臭豆腐一盤", 2.0, fried = true)))
        assertTrue(r, r.contains("單餐超過 700 大卡"))
        assertFalse(r, r.contains("炸物，今天不是休假餐"))
        assertEquals(listOf("OVER700"), rig.store.state.value.meals.last().breaches)
    }

    @Test fun aCravingOver700IsRefusedEvenWhenItFits() {
        val r = say("想吃牛肉麵", parsed("craving", item("牛肉麵", low = 600, high = 700)))
        // M4: still below the floor, so it also says how much he must eat and what (the planned 629 fits under 700).
        assertEquals("這份大約 770 大卡，單餐不准超過 700 大卡。今天至少還要吃 1200 大卡。晚餐吃給力盒子南加州烤雞腿。", r)
    }

    @Test fun friedOnANormalDayIsABreachNamedAsTheHolidayMeal() {
        val r = say("吃了炸臭豆腐", parsed("meal", item("炸臭豆腐一盤", fried = true)))
        assertTrue(r, r.contains("炸物，今天不是休假餐，記「沒照計畫」。"))
        assertEquals(listOf("FRIED"), rig.store.state.value.meals.last().breaches)
    }

    @Test fun theRulesCannotBeChangedThroughSettings() {
        rig.settings.profile = CoachProfile(quotaKcal = 2500, proteinG = 60, cheatDay = DayOfWeek.MONDAY)
        val p = rig.settings.profile
        assertEquals(1700, p.quotaKcal)
        assertEquals(140, p.proteinG)
        assertEquals(DayOfWeek.FRIDAY, p.cheatDay)
    }

    // ---- schedule ----

    @Test fun weekdaysHaveLunchAndWeekendsDoNot() {
        val t = SlotTimes()
        val fri = AgencySchedule.slots(LocalDate.of(2026, 11, 20), t).map { it.key }
        val sat = AgencySchedule.slots(LocalDate.of(2026, 11, 21), t).map { it.key }
        assertEquals(listOf("lunch", "dinner", "chicken", "training", "night"), fri)
        assertEquals(listOf("dinner", "chicken", "training", "night"), sat)
        assertEquals(12 * 60, AgencySchedule.slots(LocalDate.of(2026, 11, 20), t).first().minute)
    }

    @Test fun aMealTicksTheNearestSlotAndAfterMidnightIsTheNightMeal() {
        // Saturday 19:00 → 晚餐.
        say("吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        var slots = rig.engine.ui.value.card.slots.associate { it.key to it.done }
        assertEquals(true, slots["dinner"])
        assertEquals(false, slots["night"])
        // Sunday 01:30 is still Saturday's coach day: 宵夜.
        rig.clock.now = Instant.parse("2026-11-21T17:30:00Z")
        say("吃了蚵仔麵線", parsed("meal", item("蚵仔麵線一碗")))
        slots = rig.engine.ui.value.card.slots.associate { it.key to it.done }
        assertEquals(true, slots["night"])
        assertEquals(false, slots["chicken"])
    }

    @Test fun aTrainingReportTicksTrainingAndIsRecordedWithItsRounds() {
        val r = say("肌力三輪做完了")
        assertTrue(r, r.contains("記下：肌力 3 輪。"))
        assertTrue(r, r.contains("今天的肌力訓練完成。"))
        assertEquals(3, rig.store.state.value.trainings.single().rounds)
        assertEquals(true, rig.engine.ui.value.card.slots.first { it.key == "training" }.done)
        assertEquals(AgencyRole.BODY, rig.store.messages.value.last().speaker)
    }

    @Test fun twoRoundsDoNotCompleteTheTraining() {
        val r = say("肌力做了兩輪")
        assertTrue(r, r.contains("計畫是 3 輪，今天沒做完。"))
        assertEquals(false, rig.engine.ui.value.card.slots.first { it.key == "training" }.done)
    }

    @Test fun theModelsRoundsCountOnlyIfTheOwnerWroteThem() {
        say("訓練做完了", """{"kind":"training","rounds":5}""")
        assertNull(rig.store.state.value.trainings.single().rounds)
    }

    // ---- roles ----

    @Test fun bodyMattersAreTheBodyTeachersAndTalkIsTheManagers() {
        say("吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertEquals(AgencyRole.BODY, rig.store.messages.value.last().role)
        say("生存都這麼難了還練什麼")
        assertEquals(AgencyRole.AGENT, rig.store.messages.value.last().role)
        // The manager's reply went out under his own role prompt, with §5 word for word and the company rules.
        val system = rig.model.calls.last().first
        assertTrue(system.startsWith(COACH_SYSTEM_VERBATIM))
        assertTrue(system, system.contains("你是「小雨娛樂」的經紀人"))
        assertTrue(system, system.contains("零卡飲料一週最多 3 瓶"))
        assertTrue(system, system.contains("單餐不超過 700 大卡"))
        assertTrue(system, system.contains("「休假餐」，固定星期五"))
    }

    @Test fun anIllCravingGoesToTheBodyTeacherWithTheToneDropped() {
        say("我頭暈但想吃鹽酥雞", parsed("craving", item("鹽酥雞", fried = true)).replace("\"illness\":false", "\"illness\":true"))
        val m = rig.store.messages.value.last()
        assertEquals(AgencyRole.BODY, m.role)
        assertTrue(m.text, m.text.contains("看醫生"))
        assertTrue(rig.model.calls.last().first.contains("體態管理老師"))
    }

    @Test fun theCompanyNameReachesTheCardAndThePrompt() {
        rig.settings.companyName = "雨夜娛樂"
        rig.engine.refresh()
        assertEquals("雨夜娛樂", rig.engine.ui.value.card.company)
        say("今天好累")
        assertTrue(rig.model.calls.last().first.contains("你是「雨夜娛樂」的經紀人"))
    }

    // ---- 練習生證 ----

    @Test fun theCardCountsDaysFromTheJoinDay() {
        val c = rig.engine.ui.value.card
        assertEquals("001", c.number)
        assertEquals("練習生 C", c.level)
        assertEquals(1L, c.dayNumber) // 2026-11-21 is day 1
        rig.clock.now = Instant.parse("2026-11-30T11:00:00Z")
        rig.engine.refresh()
        assertEquals(10L, rig.engine.ui.value.card.dayNumber)
    }

    // ---- 入社建檔 ----

    @Test fun onboardingAsksOneThingAtATimeAndKeepsTheBaseline() {
        rig.engine.startOnboarding()
        val welcome = rig.store.messages.value.single()
        assertEquals(AgencyRole.AGENT, welcome.role)
        assertTrue(welcome.text, welcome.text.contains("歡迎加入小雨娛樂"))
        assertTrue(welcome.text, welcome.text.contains("身高 168 公分、體重 80 公斤"))
        assertEquals(BaselineStep.BODY, rig.engine.ui.value.card.asking)

        var r = say("身高 170 體重 79.5")
        assertTrue(r, r.contains("記下：身高 170 公分、體重 79.5 公斤。"))
        assertTrue(r, r.contains("全身照"))
        assertEquals(170.0, rig.settings.profile.heightCm, 0.0)
        assertEquals(79.5, rig.store.state.value.weights.last().kg, 0.0)

        r = say("跳過")
        assertTrue(r, r.contains("清唱錄音"))
        r = say("先跳過")
        assertTrue(r, r.contains("伏地挺身"))
        r = say("伏地挺身 25 下")
        assertTrue(r, r.contains("記下：伏地挺身 25 下。"))
        r = say("棒式一分半")
        assertTrue(r, r.contains("記下：棒式 90 秒。"))
        assertTrue(r, r.contains("還缺：全身照、清唱錄音"))
        // No model call was needed for any answer.
        assertTrue(rig.model.calls.isEmpty())

        val b = rig.store.state.value.baseline!!
        assertEquals(25, b.pushups)
        assertEquals(90, b.plankSec)
        assertNull(b.step)
        assertEquals(listOf(BaselineStep.PHOTO, BaselineStep.RECORDING), rig.engine.ui.value.card.baselineMissing)

        // It survives a restart.
        val again = CoachStore(rig.dir) { rig.clock.millis() }
        assertEquals(b, again.state.value.baseline)
    }

    @Test fun confirmingKeepsTheSetNumbers() {
        rig.engine.startOnboarding()
        val r = say("對")
        assertTrue(r, r.contains("記下：身高 168 公分、體重 80 公斤。"))
    }

    @Test fun onboardingStartsOnlyOnce() {
        rig.engine.startOnboarding()
        rig.engine.startOnboarding()
        assertEquals(1, rig.store.messages.value.size)
    }

    @Test fun aMealDuringOnboardingIsStillAMeal() {
        rig.engine.startOnboarding()
        val r = say("我吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertTrue(r, r.contains("629 大卡"))
        assertEquals(BaselineStep.BODY, rig.store.state.value.baseline!!.step)
    }

    @Test fun aPhotoHandedInAdvancesItsStepAndReopeningAsksTheRestAgain() {
        rig.engine.startOnboarding()
        say("對")
        File(rig.engine.baselineDir, "photo-1.jpg").writeText("invented")
        rig.engine.onBaselineFile(BaselineStep.PHOTO, "photo-1.jpg")
        assertTrue(rig.store.messages.value.last().text.contains("記下：全身照收到了。"))
        assertEquals(BaselineStep.RECORDING, rig.store.state.value.baseline!!.step)
        say("跳過"); say("跳過"); say("跳過")
        assertNull(rig.store.state.value.baseline!!.step)
        rig.engine.reopenBaseline()
        assertEquals(BaselineStep.RECORDING, rig.store.state.value.baseline!!.step)
        assertTrue(rig.store.messages.value.last().text.contains("清唱錄音"))
        assertEquals("photo-1.jpg", rig.store.state.value.baseline!!.photo)
    }

    // ---- the update from v0.8.3 ----

    @Test fun theCoachsRecordsSurviveTheUpdate() {
        val dir = Files.createTempDirectory("agency-migrate").toFile()
        try {
            // Exactly the shape v0.8.3 wrote (no trainings, no baseline, no breaches, no roles).
            File(dir, "state.json").writeText(
                """{"version":1,"seeded":true,"meals":[{"id":"m1","at":1763722800000,"day":"2026-11-21","text":"吃了臭豆腐","cheat":false,"offPlan":true,"verified":false,"items":[{"name":"炸臭豆腐一盤","portion":1.0,"kcal":565,"fried":true,"source":"TABLE","tableEstimated":true}]},{"id":"m2","at":1763726400000,"day":"2026-11-21","text":"吃了雞腿","cheat":false,"offPlan":false,"verified":false,"items":[{"name":"給力盒子 南加州烤雞腿","portion":1.0,"kcal":629,"protein":51.0,"fried":false,"source":"TABLE","tableEstimated":false}]}],"weights":[{"id":"w1","at":1763722800000,"day":"2026-11-21","kg":79.6}],"food":[{"name":"牛肉麵","aliases":[],"kcal":700,"protein":35.0,"fried":false,"estimated":false,"seeded":false,"addedAt":1}],"pending":[]}""",
            )
            File(dir, "messages.jsonl").writeText(
                """{"id":"a","at":1,"who":"OWNER","text":"吃了臭豆腐"}""" + "\n" +
                    """{"id":"b","at":2,"who":"COACH","text":"記下","kind":"meal"}""" + "\n" +
                    """{"id":"c","at":3,"who":"COACH","text":"這是藉口","kind":"chat"}""" + "\n",
            )
            val prefs = InMemorySharedPreferences()
            // v0.8.3 let him change the quota and the cheat day.
            prefs.edit().putInt("quota", 2000).putInt("cheat_day", 1).apply()
            val store = CoachStore(dir)
            val s = store.state.value
            assertEquals(2, s.meals.size)
            assertEquals(listOf("FRIED"), s.meals[0].breaches)
            assertEquals(emptyList<String>(), s.meals[1].breaches)
            assertEquals(79.6, s.weights.single().kg, 0.0)
            assertEquals("牛肉麵", s.food.single().name) // never re-seeded over his table
            assertNull(s.baseline)
            assertTrue(s.trainings.isEmpty())
            val msgs = store.messages.value
            assertEquals(3, msgs.size)
            assertNull(msgs[0].speaker)
            assertEquals(AgencyRole.BODY, msgs[1].speaker)
            assertEquals(AgencyRole.AGENT, msgs[2].speaker)
            // The company rules replace what the old version saved.
            val p = CoachSettings(prefs).profile
            assertEquals(1700, p.quotaKcal)
            assertEquals(DayOfWeek.FRIDAY, p.cheatDay)
            // The old meals count as today's violations on the card.
            assertEquals(1, AgencyRules.violationsOn(s.meals, "2026-11-21"))
            // Saving again keeps every old record.
            store.update { it.copy(trainings = it.trainings + TrainingEntry("t", 1, "2026-11-21", 3)) }
            val reread = CoachStore(dir).state.value
            assertEquals(2, reread.meals.size)
            assertEquals(1, reread.trainings.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- review 1 fixes ----

    private fun atStep(step: BaselineStep) {
        rig.store.update { it.copy(baseline = Baseline(step = step)) }
    }

    @Test fun i1AMealWithNumbersDuringTheBodyQuestionIsAMeal() {
        atStep(BaselineStep.BODY)
        val r = say("晚餐吃了 2 個雞腿 150 元", parsed("meal", item("雞腿", 2.0, low = 200, high = 250)))
        assertTrue(r, r.contains("記下：雞腿"))
        assertNull(rig.store.state.value.baseline!!.heightCm)
        assertEquals(168.0, rig.settings.profile.heightCm, 0.0)
        assertEquals(BaselineStep.BODY, rig.store.state.value.baseline!!.step)
    }

    @Test fun i1ATrainingReportDuringTheBodyQuestionIsTraining() {
        atStep(BaselineStep.BODY)
        say("肌力 3 輪做完了，伏地挺身 30 下")
        assertTrue(rig.store.state.value.weights.isEmpty())
        assertEquals(1, rig.store.state.value.trainings.size)
        assertNull(rig.store.state.value.baseline!!.weightKg)
    }

    @Test fun i1NotRightIsNotAConfirm() {
        atStep(BaselineStep.BODY)
        say("不對")
        assertNull(rig.store.state.value.baseline!!.heightCm)
        assertEquals(BaselineStep.BODY, rig.store.state.value.baseline!!.step)
    }

    @Test fun i1AMealDuringThePlankQuestionIsAMeal() {
        atStep(BaselineStep.PLANK)
        val r = say("晚餐吃了給力盒子 629", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertTrue(r, r.contains("629 大卡"))
        assertNull(rig.store.state.value.baseline!!.plankSec)
        assertEquals(1, rig.store.state.value.meals.size)
    }

    @Test fun i1WaitAMinuteIsNotPushUps() {
        atStep(BaselineStep.PUSHUPS)
        say("等一下 10 分鐘")
        assertNull(rig.store.state.value.baseline!!.pushups)
        assertEquals(BaselineStep.PUSHUPS, rig.store.state.value.baseline!!.step)
    }

    @Test fun i1LaterLateNightIsNotASkip() {
        atStep(BaselineStep.PUSHUPS)
        say("晚點再吃宵夜")
        assertEquals(BaselineStep.PUSHUPS, rig.store.state.value.baseline!!.step)
        assertTrue(rig.store.state.value.baseline!!.skipped.isEmpty())
    }

    @Test fun i1StrictAnswersStillWork() {
        assertEquals(170.0 to 79.5, BaselineAnswers.body("身高 170 體重 79.5"))
        assertEquals(170.0 to 79.5, BaselineAnswers.body("170 79.5"))
        assertEquals(170.0 to 79.5, BaselineAnswers.body("170公分 79.5公斤"))
        assertEquals(null to 79.5, BaselineAnswers.body("體重 79.5"))
        assertNull(BaselineAnswers.body("晚餐 150 元"))
        assertNull(BaselineAnswers.body("150 2"))
        assertEquals(25, BaselineAnswers.pushups("伏地挺身 25 下"))
        assertEquals(25, BaselineAnswers.pushups("25"))
        assertEquals(20, BaselineAnswers.pushups("二十下"))
        assertNull(BaselineAnswers.pushups("等一下 10 分鐘"))
        assertEquals(90, BaselineAnswers.plank("棒式 90 秒"))
        assertEquals(90, BaselineAnswers.plank("1 分 30 秒"))
        assertEquals(90, BaselineAnswers.plank("一分半"))
        assertEquals(60, BaselineAnswers.plank("60"))
        assertNull(BaselineAnswers.plank("晚餐吃了給力盒子 629"))
    }

    @Test fun i2AConfirmKeepsHisLatestWeight() {
        rig.store.update { it.copy(weights = listOf(WeightEntry("w1", rig.clock.millis() - 3_600_000, "2026-11-21", 79.6, null))) }
        val goalBefore = rig.engine.ui.value.strip.nextGoalKg
        rig.engine.startOnboarding()
        assertTrue(rig.store.messages.value.last().text.contains("體重 79.6 公斤"))
        say("對")
        assertEquals(79.6, rig.store.state.value.baseline!!.weightKg!!, 0.0)
        assertEquals(1, rig.store.state.value.weights.size)
        assertEquals(goalBefore, rig.engine.ui.value.strip.nextGoalKg)
        assertEquals(80.0, rig.settings.profile.startWeightKg, 0.0)
    }

    @Test fun i3AMealThatAlsoReportsTrainingRecordsBoth() {
        say("吃了雞胸，肌力三輪做完了", parsed("meal", item("雞胸", low = 150, high = 200)))
        assertEquals(1, rig.store.state.value.meals.size)
        assertEquals(3, rig.store.state.value.trainings.single().rounds)
    }

    @Test fun i3NegatedTrainingIsNeverDone() {
        listOf("訓練還沒做完", "肌力做了一半", "今天沒做完訓練").forEach { t ->
            say(t)
            say(t, """{"kind":"training"}""")
        }
        assertTrue(rig.store.state.value.trainings.isEmpty())
        assertEquals(false, rig.engine.ui.value.card.slots.first { it.key == "training" }.done)
    }

    @Test fun m1TheParsePromptListsTraining() {
        assertTrue(MealParse.SYSTEM.contains("food_info 或 training 或 chat"))
    }

    @Test fun m2AZeroCalDrinkWithoutAnEstimateStillCounts() {
        repeat(3) { say("喝了零卡可樂", parsed("meal", item("零卡可樂"))) }
        assertEquals(3, AgencyRules.zeroCalThisWeek(rig.store.state.value.meals, LocalDate.of(2026, 11, 21)))
        assertTrue(say("喝了零卡可樂", parsed("meal", item("零卡可樂"))).contains("第 4 瓶"))
    }

    @Test fun m3OneSittingReportedInTwoPartsIsOneMeal() {
        say("吃了便當", parsed("meal", item("便當", low = 400, high = 454)))
        rig.clock.advance(java.time.Duration.ofMinutes(10))
        val r = say("又吃了滷味", parsed("meal", item("滷味", low = 300, high = 363)))
        assertTrue(r, r.contains("單餐超過 700 大卡"))
    }

    @Test fun m3TwoMealsAnHourApartAreTwoMeals() {
        say("吃了便當", parsed("meal", item("便當", low = 400, high = 454)))
        rig.clock.advance(java.time.Duration.ofMinutes(60))
        val r = say("吃了滷味", parsed("meal", item("滷味", low = 300, high = 363)))
        assertFalse(r, r.contains("單餐超過 700 大卡"))
    }

    @Test fun m5NoStringSaysTheOldCheatMealWord() {
        val xml = File("src/main/res/values/strings.xml").readText()
        assertFalse(xml.contains("放縱餐"))
    }

    @Test fun m7TheWelcomeSaysTheCompanyWasFounded() {
        rig.engine.startOnboarding()
        assertTrue(rig.store.messages.value.single().text.startsWith("公司成立了："))
    }

    // ---- review 2 ----

    @Test fun a1APronounBeforeTheAnswerStillAnswers() {
        assertEquals(170.0 to 78.0, BaselineAnswers.body("我身高 170 體重 78"))
        assertEquals(null to 78.0, BaselineAnswers.body("我的體重 78"))
        atStep(BaselineStep.BODY)
        say("我身高 170 體重 78")
        assertEquals(170.0, rig.store.state.value.baseline!!.heightCm!!, 0.0)
    }

    @Test fun a2OnlyANegatedActionStopsATrainingReport() {
        say("肌力三輪做完了，累到不行")
        say("沒問題，訓練做完了")
        assertEquals(2, rig.store.state.value.trainings.size)
        say("今天沒做完訓練")
        say("訓練還沒做完")
        assertEquals(2, rig.store.state.value.trainings.size)
    }

    @Test fun a3APlanToTrainIsTalk() {
        val r = say("等等要去訓練", """{"kind":"training"}""")
        assertTrue(rig.store.state.value.trainings.isEmpty())
        assertEquals(AgencyRole.AGENT, rig.store.messages.value.last().role)
        assertFalse(r, r.contains("記下：肌力"))
    }

    @Test fun a5ABareOneIsNotAPushUpCount() {
        assertNull(BaselineAnswers.pushups("一下"))
        assertEquals(10, BaselineAnswers.pushups("十下"))
        assertEquals(25, BaselineAnswers.pushups("二十五下"))
        assertEquals(1, BaselineAnswers.pushups("1"))
        atStep(BaselineStep.PUSHUPS)
        say("一下")
        assertNull(rig.store.state.value.baseline!!.pushups)
    }
}
