package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.engine.Signal
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.metering.MutableClock
import app.agency.core.model.DayOverride
import app.agency.core.model.EffectKind
import app.agency.core.model.FoodEntry
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.rules.Breach
import app.agency.core.rules.MealBudget
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlanner
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T14: meal records and the meal rules, all by code (the clerk only sorts his words). Invented data only. */
class MealT14Test {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-11-23") // a Monday, a class day

    init {
        runBlocking { store.profile.put(Profile(heightCm = 168.0, startWeightKg = 80.0, onboardingDone = true)) }
    }

    private fun brain(llm: ScriptedLlm) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%04d".format(++n) }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun item(name: String, high: Int? = null, protein: Int? = null, fried: Boolean = false, zero: Boolean = false, portion: Double = 1.0) =
        """{"name":"$name","portion":$portion,"fried":$fried,"processed":false,"zero_cal":$zero,"kcal_low":${high?.let { it - 100 }},"kcal_high":$high,"protein_low":$protein,"protein_high":$protein}"""

    private fun meal(vararg items: String, kind: String = "meal") = ScriptedLlm.ok("""{"kind":"$kind","items":[${items.joinToString(",")}]}""")

    /** He says [text] at [hhmm] and the clerk sorts it as [intake]; the company's lines back. */
    private fun say(hhmm: String, text: String, vararg intake: app.agency.core.llm.ChatResult): List<Message> {
        at(hhmm)
        val llm = ScriptedLlm(*intake)
        return runBlocking { brain(llm).onText(text) }.filter { it.who == Who.COMPANY }
    }

    private fun meals(): List<Meal> = runBlocking { store.meals.onDay(day.toString()) }

    private fun food(name: String, kcal: Int, protein: Double? = null) = runBlocking {
        store.food.upsert(FoodEntry(name, emptyList(), kcal, protein, null, null, fried = false, estimated = false, seeded = false, addedAt = 0))
    }

    @Test fun aTableFoodIsCountedFromTheTableAtPlanV3sNumbersAndTheNextMealFollows() {
        val out = say("12:30", "午餐吃了給力盒子南加州烤雞腿", meal(item("給力盒子 南加州烤雞腿", 900)))
        val body = out.first()
        assertEquals(RoleKey.BODY, body.role)
        val lines = body.text.lines()
        assertEquals("記在午餐。", lines[0])
        assertEquals("記下：給力盒子 南加州烤雞腿 1份 714 大卡（食物表）。", lines[1])
        assertTrue(lines.contains(MealDesk.UNVERIFIED))
        assertTrue(lines.contains("今天吃了 714 大卡，剩 986 大卡，蛋白質還差 92 克。"))
        assertTrue(lines.contains("今天至少還要吃 486 大卡。"))
        // L5: 986 left less the 21:00 portion (310) is 676, less than a dinner usually is: dinner gets that budget.
        assertTrue(body.text, lines.last().startsWith("因為今天到現在吃了 714 大卡，所以晚餐建議 676 大卡以內、蛋白質至少 46 克"))
        val m = meals().single()
        assertEquals("LUNCH", m.slotKey)
        assertEquals(714, m.kcal)
        assertEquals(48.0, m.items.single().proteinG!!, 1e-9)
        assertFalse(m.verified)
        assertEquals(listOf(m.id), body.refs.records)
        assertEquals(676, runBlocking { brain(ScriptedLlm()).nerve.plan(day) }.budgets[SlotKey.DINNER])
        assertEquals(1, runBlocking { store.nerve.effects(day.toString()) }.count { it.link == "L5" && it.kind == EffectKind.ADJUST })
    }

    @Test fun anUnknownFoodIsTheStrictEstimateAndItsLabelReplacesItAndEntersTheTable() {
        val out = say("12:30", "吃了雞腿便當", meal(item("雞腿便當", 650, 30)))
        // 650 × 1.1 = 715, rounded up; protein takes the low end.
        assertTrue(out.first().text.contains("記下：雞腿便當 1份 715 大卡（估算，取高值加一成）。"))
        assertTrue(out.first().text.contains(MealDesk.askLabel("雞腿便當")))
        assertEquals(1, runBlocking { store.meals.pending() }.size)
        val label = say(
            "12:40", "雞腿便當 標示 680 大卡 蛋白質 35 克",
            ScriptedLlm.ok("""{"kind":"label","label":{"name":"雞腿便當","kcal":680,"protein_g":35}}"""),
        ).first().text
        assertTrue(label, label.startsWith("存進食物表：雞腿便當 680 大卡、蛋白質 35 克（標示）。"))
        assertTrue(label.contains("雞腿便當 1份改成 680 大卡（原本估算 715）。"))
        val item = meals().single().items.single()
        assertEquals(680, item.kcal)
        assertEquals(KcalSource.LABEL, item.source)
        assertTrue(runBlocking { store.meals.pending() }.isEmpty())
        assertFalse(runBlocking { store.food.get("雞腿便當") }!!.estimated)
        // The day state follows the label (it reads the meal signals).
        assertEquals(680, runBlocking { brain(ScriptedLlm()).nerve.state(day) }.kcal)
        // The next time it is a table food.
        val again = say("17:20", "晚餐又吃了雞腿便當", meal(item("雞腿便當", 900))).first().text
        assertTrue(again.contains("記下：雞腿便當 1份 680 大卡（食物表）。"))
    }

    @Test fun theSingleMealCapIsACeiling800IsFine801IsABreachAndASittingAddsUp() {
        food("測試便當甲", 800)
        food("測試便當乙", 801)
        food("測試小菜", 401)
        say("12:20", "吃了測試便當甲", meal(item("測試便當甲")))
        assertTrue(meals().single().breaches.isEmpty())
        val over = say("17:20", "晚餐吃了測試便當乙", meal(item("測試便當乙")))
        assertEquals(listOf(Breach.MEAL_CAP.name), meals().last().breaches)
        assertTrue(over.first().text.contains("這一餐一共 801 大卡，超過單餐上限 800。一次不要吃到撐。"))
        assertFalse(over.first().text.contains("少吃"))
    }

    @Test fun partsWithin45MinutesAreOneSittingAndFiftyMinutesApartAreNot() {
        food("測試飯", 400)
        food("測試湯", 401)
        say("12:10", "吃了測試飯", meal(item("測試飯")))
        say("12:50", "又吃了測試湯", meal(item("測試湯")))
        assertEquals(listOf(Breach.MEAL_CAP.name), meals().last().breaches)
        // Another day: 50 minutes apart are two meals.
        day = day.plusDays(1)
        say("12:10", "吃了測試飯", meal(item("測試飯")))
        say("13:00", "又吃了測試湯", meal(item("測試湯")))
        assertTrue(meals().all { it.breaches.isEmpty() })
    }

    @Test fun bannedFoodOutsideTheCheatMealIsABreachTheManagerRecordsAndHisStaplesNeverAre() {
        val out = say("12:30", "午餐吃了炸雞排", meal(item("炸雞排", 600, fried = true)))
        assertEquals(listOf(Breach.PROCESSED.name), meals().single().breaches)
        assertTrue(out[0].text.contains("炸雞排是炸物，休假餐以外不准吃。"))
        // L13: recorded for the evaluation, and the manager says so, coolly, never 「少吃補回來」.
        assertEquals(RoleKey.MANAGER, out[1].role)
        assertEquals("記一次違規：休假餐以外吃了禁止的東西。今天第 1 次，會算進評鑑。下一餐照計畫。", out[1].text)
        assertTrue(runBlocking { store.nerve.effects(day.toString()) }.any { it.link == "L13" && it.kind == EffectKind.RECORD })
        // 即食雞胸, 統一高纖豆漿, 全家烤雞三明治, 麥當勞嫩煎雞腿堡 (even called fried by the clerk), 藜麥烤雞沙拉: none is a breach.
        day = day.plusDays(1)
        say("12:30", "吃了全家烤雞三明治和麥當勞嫩煎雞腿堡", meal(item("全家烤雞三明治", 200), item("麥當勞嫩煎雞腿堡", 400, fried = true)))
        say("17:20", "晚餐藜麥烤雞沙拉", meal(item("麥當勞藜麥烤雞沙拉", 350)))
        assertTrue(meals().all { it.breaches.isEmpty() })
        // Sausage is processed meat, chips are snacks.
        say("21:30", "吃了香腸和洋芋片", meal(item("香腸", 300), item("洋芋片", 300)))
        assertEquals(listOf(Breach.PROCESSED.name), meals().last().breaches)
    }

    @Test fun theDeclaredCheatMealAllowsBannedFoodFrom2027StillCountsAndStillHasTheCap() {
        // Before 2027 the 休假餐 is closed: a declared slot changes nothing.
        runBlocking { store.days.put(DayOverride(day.toString(), cheatSlot = "DINNER", declaredAt = 0)) }
        say("17:20", "晚餐吃了炸雞排", meal(item("炸雞排", 600, fried = true)))
        assertEquals(listOf(Breach.PROCESSED.name), meals().single().breaches)
        // From 2027-01-01 it is open: a Friday with dinner declared.
        day = LocalDate.parse("2027-01-08")
        runBlocking { store.days.put(DayOverride(day.toString(), cheatSlot = "DINNER", declaredAt = 0)) }
        val out = say("17:20", "晚餐吃了炸雞排和珍奶", meal(item("炸雞排", 600, fried = true), item("珍奶", 150)))
        val m = meals().single()
        assertTrue(m.cheat)
        // 660 + 165 = 825: the banned food is fine in the 休假餐, the single-meal cap is not.
        assertEquals(listOf(Breach.MEAL_CAP.name), m.breaches)
        assertTrue(out[0].text.contains("這是你宣告的休假餐，禁止的東西這餐不算違規；熱量照算。"))
        assertTrue(runBlocking { store.nerve.effects(day.toString()) }.any { it.link == "L7" && it.because == "cheat_meal" })
        // The other meals of that day are as always.
        say("21:40", "吃了雞塊", meal(item("雞塊", 300)))
        assertEquals(listOf(Breach.PROCESSED.name), meals().last().breaches)
    }

    @Test fun theNinePmPortionOnTheRunIsNeverASingleMealBreach() {
        food("測試大便當", 790)
        say("21:00", "跑單路上吃了即食雞胸和統一高纖豆漿", meal(item("即食雞胸"), item("統一高纖豆漿")))
        val first = meals().single()
        assertEquals("RIDE_MEAL", first.slotKey)
        assertEquals(310, first.kcal)
        // Eating more within the same sitting on the run is still no cap breach (he eats the portion at its time, for safety).
        say("21:20", "路上又吃了測試大便當", meal(item("測試大便當")))
        assertTrue(meals().all { it.breaches.isEmpty() })
    }

    @Test fun theFourthZeroCalorieDrinkOfTheWeekIsABreach() {
        repeat(3) { i ->
            day = LocalDate.parse("2026-11-23").plusDays(i.toLong())
            say("12:30", "喝了零卡可樂", meal(item("零卡可樂", zero = true)))
        }
        assertTrue(runBlocking { store.meals.between("2026-11-23", "2026-11-29") }.all { it.breaches.isEmpty() })
        day = LocalDate.parse("2026-11-26")
        val out = say("12:30", "又喝了零卡可樂", meal(item("零卡可樂", zero = true)))
        assertEquals(listOf(Breach.ZERO_CAL.name), meals().single().breaches)
        assertTrue(out[0].text.contains("零卡飲料這週第 4 瓶，一週最多 3 瓶。"))
        assertEquals(0, meals().single().kcal)
        // The next week starts over.
        day = LocalDate.parse("2026-11-30")
        say("12:30", "喝了零卡可樂", meal(item("零卡可樂", zero = true)))
        assertTrue(meals().single().breaches.isEmpty())
    }

    @Test fun aCravingIsAnsweredByCodeAndNothingIsRecorded() {
        // The clerk calls it a meal; 「想吃」 without 「吃了」 is a craving.
        val no = say("16:00", "我想吃炸雞排", meal(item("炸雞排", 600, fried = true)))
        assertEquals("炸雞排是炸物，休假餐以外不准吃。晚餐換成給力盒子南加州烤雞腿（714 大卡、蛋白質 48 克）。", no.single().text)
        assertEquals(RoleKey.BODY, no.single().role)
        val yes = say("16:05", "晚餐可以吃雞腿便當嗎", meal(item("雞腿便當", 600), kind = "craving"))
        assertEquals("可以。這份 660 大卡（估算），吃了今天剩 1040 大卡。吃完拍照回報。", yes.single().text)
        val big = say("16:10", "晚餐想吃牛肉麵", meal(item("牛肉麵", 900), kind = "craving"))
        assertTrue(big.single().text, big.single().text.startsWith("這份大約 990 大卡（估算），單餐上限 800，太多了。"))
        assertTrue(meals().isEmpty())
    }

    @Test fun notEatenYetIsNoMealAndGoesToTheConversation() {
        val out = say(
            "13:00", "我還沒吃午餐",
            meal(item("午餐", 600)),
            ScriptedLlm.ok("""{"text":"現在去吃。","ask":null,"options":[],"recommended":null,"memory":[]}"""),
            ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}"""),
        )
        assertEquals("現在去吃。", out.single().text)
        assertTrue(meals().isEmpty())
    }

    @Test fun aDayOverTheQuotaGetsTheFourFixedLinesAndNoSmallerMeal() {
        runBlocking {
            store.meals.insert(Meal("m-0", day.toString(), clock.millis(), "LUNCH", "之前", listOf(MealItem("之前的", 1.0, 1600, 40.0, null, null, false, KcalSource.TABLE))))
        }
        signalMeal("m-0", 1600)
        val out = say("17:20", "晚餐吃了雞腿便當", meal(item("雞腿便當", 650)))
        val lines = out.first().text.lines()
        val i = lines.indexOf("超過 615 大卡。")
        assertTrue(out.first().text, i > 0)
        assertEquals("身材：每天都這樣，一個月多 2.6 公斤，赤字變成 -115，等於在變胖。", lines[i + 1])
        assertEquals("身體：留在肥胖區的時間多 1.2 天，75 公斤延後 1.2 天。", lines[i + 2])
        assertEquals("明天回到 1700。不准少吃來補。", lines[i + 3])
        assertTrue(runBlocking { store.nerve.effects(day.toString()) }.none { it.link == "L5" })
    }

    private fun signalMeal(id: String, kcal: Int) = runBlocking {
        brain(ScriptedLlm()).signal(Signal.MealLogged("meal:$id", clock.millis(), SlotKey.LUNCH, kcal, 40.0))
    }

    @Test fun aPhotoFirstWaitsForHisWordsAndAPhotoAfterVerifiesTheMeal() {
        at("12:20")
        val b = brain(ScriptedLlm())
        val first = runBlocking { b.attachMealPhoto(media("p-1")) }
        assertEquals("照片收到，記在午餐。吃了什麼、幾份打給我，熱量才算得出來。", first.text)
        val waiting = meals().single()
        assertTrue(waiting.verified)
        assertTrue(waiting.items.isEmpty())
        val out = say("12:30", "吃了雞腿便當", meal(item("雞腿便當", 650)))
        val m = meals().single()
        assertEquals(waiting.id, m.id)
        assertEquals("p-1", m.photoMediaId)
        assertTrue(out.first().text.contains("照片有了，這餐已證實。"))
        // Dinner: words first, the photo after; until then the card lists it as 「未證實」.
        say("17:20", "晚餐吃了雞腿便當", meal(item("雞腿便當", 650)))
        assertEquals(listOf("晚餐"), runBlocking { brain(ScriptedLlm()).card() }.unverified)
        at("17:25")
        assertEquals("照片收到，晚餐已證實。", runBlocking { brain(ScriptedLlm()).attachMealPhoto(media("p-2")) }.text)
        assertTrue(meals().all { it.verified })
        assertTrue(runBlocking { brain(ScriptedLlm()).card() }.unverified.isEmpty())
        // Deleting a photo (after his confirm) makes it 「未證實」 again; the media row goes, the app deletes the file.
        assertEquals("p-2", runBlocking { brain(ScriptedLlm()).deleteMealPhoto(meals().last().id) })
        assertFalse(meals().last().verified)
        assertNull(runBlocking { store.media.get("p-2") })
    }

    private fun media(id: String) = Media(id, MediaKind.PHOTO, MediaPurpose.MEAL, "image/jpeg", 10, "00", clock.millis())

    @Test fun aPriceHeWroteIsFoodSpendingFromTheLivingAllowance() {
        say("12:30", "午餐吃了雞腿便當 120 元", meal(item("雞腿便當", 650)))
        val spend = runBlocking { store.nerve.effects(day.toString()) }.single { it.link == "L16" }
        assertEquals("spend", spend.because)
        assertTrue(spend.json.contains("生活費 120"))
    }

    @Test fun aWeightHeTypedIsRecordedWithItsZoneAndTheNextGoalOnly() {
        val out = say("09:00", "今天體重 79.5", ScriptedLlm.ok("""{"kind":"weight","weight_kg":79.5}"""))
        assertEquals("記下：79.5 公斤。身體質量指數 28.2，肥胖區。下一個目標 75 公斤，還差 4.5 公斤。", out.single().text)
        assertEquals(79.5, runBlocking { store.weights.onDay(day.toString()) }.single().kg, 1e-9)
        assertEquals(listOf(day to 79.5), runBlocking { brain(ScriptedLlm()).nerve.state(day) }.weights)
        // A number the clerk made up (he wrote none) is not a weight.
        val none = say("09:05", "我今天量了體重", ScriptedLlm.ok("""{"kind":"weight","weight_kg":81}"""))
        assertEquals("沒聽到體重。說「今天體重 79.5」。", none.single().text)
        assertEquals(1, runBlocking { store.weights.count() })
    }

    @Test fun theSlotIsHisWordsOrTheLatestDueMealNotReportedYet() {
        val plan = DayPlanner.build(day)
        fun m(h: String) = DayClock.minute(h)
        fun pick(h: String, text: String = "", eaten: List<String> = emptyList()) =
            MealSlots.pick(plan, text, m(h), eaten.map { Meal(it, day.toString(), 0, it, "x") }, 10 * 3_600_000L)
        assertEquals(SlotKey.LUNCH, pick("12:30"))
        // Lunch reported late is still lunch, not dinner.
        assertEquals(SlotKey.LUNCH, pick("15:00"))
        assertEquals(SlotKey.DINNER, pick("16:40", eaten = listOf("LUNCH")))
        assertNull(pick("15:00", eaten = listOf("LUNCH")))
        assertEquals(SlotKey.RIDE_MEAL, pick("21:05", eaten = listOf("LUNCH", "DINNER")))
        // After midnight the minutes run on (00:30 is 24:30): an extra.
        assertNull(pick("00:30", eaten = listOf("LUNCH", "DINNER", "RIDE_MEAL")))
        assertEquals(SlotKey.DINNER, pick("13:00", "晚餐先吃了"))
        assertNull(pick("21:30", "宵夜吃了"))
    }

    @Test fun aBudgetNeverLeavesTheDayUnderTheFloorNorSkipsAMeal() {
        val plan = DayPlanner.build(day)
        val rules = app.agency.core.model.CompanyRules()
        val dinner = DayClock.minute("17:15")
        // Nothing eaten by dinner: the floor needs 890 more besides the 21:00 portion, the cap allows 800.
        assertEquals(800, MealBudget.next(plan, rules, 0, 0.0, setOf(SlotKey.LUNCH), dinner - 1)!!.kcal)
        // 1300 by dinner: 90 would be left; a meal is never smaller than 200.
        assertEquals(200, MealBudget.next(plan, rules, 1300, 80.0, setOf(SlotKey.LUNCH), dinner - 1)!!.kcal)
        // Over already: no budget at all (明天回到 1700, not less food).
        assertNull(MealBudget.next(plan, rules, 1750, 80.0, setOf(SlotKey.LUNCH), dinner - 1))
        // A normal lunch leaves dinner as usual.
        assertFalse(MealBudget.next(plan, rules, 600, 40.0, setOf(SlotKey.LUNCH), dinner - 1)!!.smaller)
    }

    @Test fun recordingAMealCallsOnlyTheClerk() {
        at("12:30")
        val llm = ScriptedLlm(meal(item("雞腿便當", 650)))
        runBlocking { brain(llm).onText("吃了雞腿便當") }
        assertEquals(listOf(Purpose.INTAKE), llm.requests.map { it.purpose })
    }
}
