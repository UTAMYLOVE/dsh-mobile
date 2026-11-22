package app.dshmobile.coach

import app.agency.core.guard.CoachNumbers
import app.agency.core.guard.NumberGuard
import app.agency.core.model.FoodEntry
import app.agency.core.rules.FoodTable
import app.dshmobile.coach.CoachRig.Companion.item
import app.dshmobile.coach.CoachRig.Companion.parsed
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test (at least) per finding of review 1. */
class CoachReviewFixTest {
    private val rig = CoachRig()

    @After fun tearDown() = rig.close()

    private fun say(text: String, parse: String): String = runBlocking {
        rig.model.parse = { ModelReply.Ok(parse) }
        rig.engine.handle(text)
        rig.store.messages.value.last().text
    }

    private fun offline(text: String): String = runBlocking {
        rig.model.parse = { ModelReply.Failed(Fail.OFFLINE) }
        rig.engine.handle(text)
        rig.store.messages.value.last().text
    }

    // I1
    @Test fun aCravingTheModelCallsAMealIsStillRefused() {
        val r = say("我想吃鹽酥雞", parsed("meal", item("鹽酥雞", fried = true, low = 500, high = 700)))
        assertTrue(r, r.startsWith("今天不是休假餐。"))
        assertTrue(rig.store.state.value.meals.isEmpty())
    }

    @Test fun aReportedMealWithTheWordWantStaysAMeal() {
        say("剛剛想吃就吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertEquals(1, rig.store.state.value.meals.size)
    }

    // I2
    @Test fun offlineCountsQuestionsAndDenialsAreNeverRecorded() {
        assertEquals(CoachReplies.cannotReach(Fail.OFFLINE), offline("兩盤蚵仔煎"))
        assertEquals(CoachReplies.cannotReach(Fail.OFFLINE), offline("蚵仔煎幾大卡"))
        assertEquals(CoachReplies.cannotReach(Fail.OFFLINE), offline("我沒吃蚵仔煎"))
        assertTrue(rig.store.state.value.meals.isEmpty())
        assertTrue(offline("蚵仔煎一盤").contains("516 大卡"))
    }

    // I3
    @Test fun aGenericNameIsNeverMappedOntoOneShopsFood() {
        val t = FoodTable.seed(0)
        assertNull(FoodTable.lookup(t, "三明治"))
        assertNull(FoodTable.lookup(t, "烤雞腿"))
        assertNull(FoodTable.lookup(t, "南加州"))
        assertEquals("給力盒子 南加州烤雞腿", FoodTable.lookup(t, "兩個給力盒子南加州烤雞腿")?.name)
    }

    // I4
    @Test fun aRefusalBelowTheFloorStillSaysWhatToEat() {
        say("吃了便當", parsed("meal", item("便當", low = 700, high = 818))) // 818 + 10% = 900
        assertEquals(800, rig.engine.ui.value.strip.remainingKcal)
        val r = say("想吃牛肉麵", parsed("craving", item("牛肉麵", low = 800, high = 910))) // 1001
        assertEquals("這份大約 1001 大卡，今天剩 800 大卡，不准再點。今天至少還要吃 300 大卡。宵夜吃給力盒子南加州烤雞腿。", r)
    }

    @Test fun theFriedRefusalAlsoKeepsTheFloor() {
        say("吃了便當", parsed("meal", item("便當", low = 900, high = 1000))) // 1100: 600 left, planned 629 does not fit
        val r = say("想吃臭豆腐", parsed("craving", item("臭豆腐", fried = true)))
        // N1: the planned meal (629) does not fit the 600 left, so it is not named.
        assertTrue(r, r.contains("今天剩 600 大卡，不准再點炸的。今天至少還要吃 100 大卡。今天剩 600 大卡。點之前先把菜單傳來，我來選。"))
        assertFalse(r, r.contains("南加州"))
    }

    // I5
    @Test fun aModelGuessNeverEntersTheTableAsALabel() {
        say("吃了牛肉麵", parsed("meal", item("牛肉麵", low = 550, high = 650)))
        val r = say("那是大碗的", parsed("food_info", foodInfo = """{"name":"牛肉麵","kcal":700,"protein_g":35,"confirm":false}"""))
        assertEquals(CoachReplies.FOOD_INFO_UNCLEAR, r)
        assertNull(FoodTable.lookup(rig.store.state.value.food, "牛肉麵"))
        val r2 = say("標示寫七百大卡", parsed("food_info", foodInfo = """{"name":"牛肉麵","kcal":700,"protein_g":35,"confirm":false}"""))
        assertTrue(r2, r2.startsWith("存進食物表：牛肉麵 700 大卡（標示）。"))
    }

    @Test fun aWeightTheOwnerDidNotSayIsNotRecorded() {
        assertEquals("沒聽到體重。說「今天體重 79.5」。", say("量了體重", parsed("weight", weight = 79.2)))
        assertTrue(rig.store.state.value.weights.isEmpty())
        assertTrue(say("今天七十九點二", parsed("weight", weight = 79.2)).startsWith("記下：79.2 公斤。"))
    }

    @Test fun chineseNumbersAreRead() {
        assertEquals(79.2, CoachNumbers.chinese("七十九點二")!!, 1e-9)
        assertEquals(250.0, CoachNumbers.chinese("兩百五十")!!, 1e-9)
        assertEquals(700.0, CoachNumbers.chinese("七百")!!, 1e-9)
        assertEquals(10.0, CoachNumbers.chinese("十")!!, 1e-9)
    }

    // I6
    @Test fun aDamagedStateFileIsKeptAsideNotOverwritten() {
        val dir = rig.dir
        File(dir, "state.json").writeText("{ broken")
        val store = CoachStore(dir) { 42L }
        assertTrue(File(dir, "state.json.bad-42").readText() == "{ broken")
        assertEquals(5, store.state.value.food.size)
    }

    @Test fun anInterruptedSaveIsRecoveredFromTheTempFile() {
        say("吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        val dir = rig.dir
        File(dir, "state.json").renameTo(File(dir, "state.json.tmp"))
        assertEquals(1, CoachStore(dir) { 1L }.state.value.meals.size)
    }

    @Test fun aFailedSaveIsNeverCalledRecorded() {
        File(rig.dir, "state.json.tmp").mkdirs() // the save cannot write its temp file
        val r = say("吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertEquals(CoachReplies.NOT_SAVED, r)
        assertTrue(rig.store.state.value.meals.isEmpty())
    }

    // I7
    @Test fun theGuardDropsChineseAmountsAndLatinLetters() {
        assertEquals("現在去練。", NumberGuard.filter("再吃兩百大卡。現在去練。", "剩 1071 大卡", ""))
        assertEquals("現在去練。", NumberGuard.filter("今天只吃八百卡。現在去練。", "剩 1071 大卡", ""))
        assertEquals("現在去練。", NumberGuard.filter("OK。現在去練。", "", ""))
        assertEquals("十分鐘內傳錄音。", NumberGuard.filter("十分鐘內傳錄音。", "", ""))
        assertEquals("身體質量指數偏高。", NumberGuard.filter("BMI偏高。", "", ""))
    }

    @Test fun aReplyTheGuardEmptiesFallsBackToTheCodesLine() {
        rig.model.chat = { ModelReply.Ok("OK, let's go!") }
        assertEquals(CoachReplies.CHAT_FALLBACK, say("今天好累", parsed("chat")))
    }

    // M1
    @Test fun aNullOrNumberNameIsNoFood() {
        assertTrue(MealParse.parse("""{"kind":"meal","items":[{"name":null},{"name":123}]}""")!!.items.isEmpty())
    }

    // M2
    @Test fun theStatusLineSurvivesTheCapAndPendingIsCappedAndExpires() {
        repeat(8) { i -> say("吃了食物$i", parsed("meal", item("奇怪食物$i", low = 10, high = 20))) }
        val facts = (rig.engine.promptFor("嗯").second.last().parts.first() as Part.Text).text
        assertTrue(facts.lines()[1].startsWith("狀態："))
        assertTrue(facts, facts.contains("另外 3 項"))
        rig.clock.advance(Duration.ofDays(8))
        val r = say("對", parsed("food_info", foodInfo = """{"name":null,"kcal":null,"protein_g":null,"confirm":true}"""))
        assertEquals(CoachReplies.NO_PENDING, r)
    }

    // M3
    @Test fun theParseRetryKeepsTheTenMessageLimit() {
        runBlocking {
            rig.model.parse = { ModelReply.Ok(parsed("chat")) }
            repeat(10) { rig.engine.handle("嗨$it") }
            rig.model.calls.clear()
            rig.model.parse = { ModelReply.Ok("不是 JSON") }
            rig.engine.handle("嗯")
        }
        val parseCalls = rig.model.calls.filter { it.first == MealParse.SYSTEM }
        assertEquals(2, parseCalls.size)
        parseCalls.forEach { assertTrue("turns ${it.second.size}", it.second.size <= 10) }
    }

    // M4
    @Test fun theNextDayStartsAtFiveInTheMorning() {
        // Saturday 19:00 Taipei: the next coach day starts Sunday 05:00, 10 hours later.
        assertEquals(Duration.ofHours(10).toMillis(), rig.engine.millisToNextDay())
        rig.clock.now = Instant.parse("2026-11-21T19:00:00Z") // Sunday 03:00: still Saturday's day, 2 hours to go
        assertEquals(Duration.ofHours(2).toMillis(), rig.engine.millisToNextDay())
    }

    // M9
    @Test fun aModelThatThrowsNeverCrashes() {
        rig.model.parse = { throw IllegalStateException("boom") }
        val r = runBlocking { rig.engine.handle("嗨"); rig.store.messages.value.last().text }
        assertEquals(CoachReplies.CHAT_DOWN, r)
        assertFalse(rig.engine.ui.value.busy)
    }

    // M10
    @Test fun exactlyZeroLeftIsNotOver() {
        say("吃了大餐", parsed("meal", item("大餐", low = 1500, high = 1545))) // 1545 + 10% → 1700
        assertEquals(0, rig.engine.ui.value.strip.remainingKcal)
        assertEquals("今天剩 0 大卡，不准再點。", say("想吃牛肉麵", parsed("craving", item("牛肉麵", low = 500, high = 600))))
    }

    // M11
    @Test fun theTablesUnitIsShownOnce() {
        assertEquals("蚵仔麵線 2碗", CoachReplies.amount("蚵仔麵線一碗", 2.0))
        assertEquals("牛肉麵 1.5份", CoachReplies.amount("牛肉麵", 1.5))
    }

    // M14 (engine side): the food table's 刪除 goes through the engine's scope.
    @Test fun deletingAFoodRunsThroughTheEngine() {
        rig.engine.deleteFood("蚵仔煎一盤")
        assertNull(FoodTable.lookup(rig.store.state.value.food, "蚵仔煎"))
    }

    // N1 (non-fried branch): over-quota food is never named either.
    @Test fun theNonFriedRefusalNamesNothingThatDoesNotFit() {
        say("吃了便當", parsed("meal", item("便當", low = 900, high = 1000))) // 1100 eaten, 600 left
        val r = say("想吃牛肉麵", parsed("craving", item("牛肉麵", low = 700, high = 800)))
        assertEquals("這份大約 880 大卡，今天剩 600 大卡，不准再點。今天至少還要吃 100 大卡。今天剩 600 大卡。點之前先把菜單傳來，我來選。", r)
    }

    // N2: the page never waits more than a minute for its next refresh.
    @Test fun theStripRefreshWaitIsCapped() {
        assertEquals(60_000L, rig.engine.nextRefreshDelay())
        rig.clock.now = Instant.parse("2026-11-21T20:59:30Z") // Sunday 04:59:30 Taipei: 30 s to the day start
        assertEquals(30_000L, rig.engine.nextRefreshDelay())
    }

    // N4: deleting the planned meal moves the plan to a food that is still in the table.
    @Test fun deletingThePlannedMealPicksAnotherTableFood() {
        rig.engine.deleteFood("給力盒子 南加州烤雞腿")
        val planned = rig.settings.profile.plannedMeal
        assertTrue(planned, FoodTable.lookup(rig.store.state.value.food, planned) != null)
        rig.engine.deleteFood("蚵仔煎一盤") // not the plan: it stays
        assertEquals(planned, rig.settings.profile.plannedMeal)
    }

    // N5: an unreadable file that cannot be moved is copied aside; if it cannot be kept at all, nothing writes over it.
    @Test fun anUnmovableDamagedFileIsCopiedAside() {
        val dir = rig.dir
        File(dir, "state.json").writeText("{ broken")
        File(dir, "state.json.bad-7").mkdirs()
        File(dir, "state.json.bad-7/keep").writeText("x") // the rename target is a full directory: rename fails
        val store = CoachStore(dir) { 7L }
        assertEquals("{ broken", File(dir, "state.json.bad-7-copy").readText())
        assertFalse(store.frozen)
    }

    @Test fun aDamagedFileThatCannotBeKeptIsNeverOverwritten() {
        val dir = rig.dir
        File(dir, "state.json").writeText("{ broken")
        File(dir, "state.json.bad-8/keep").apply { parentFile!!.mkdirs() }.writeText("x")
        File(dir, "state.json.bad-8-copy/keep").apply { parentFile!!.mkdirs() }.writeText("x")
        val store = CoachStore(dir) { 8L }
        assertTrue(store.frozen)
        assertEquals("{ broken", File(dir, "state.json").readText())
        assertNull(store.update { it.copy(weights = it.weights + WeightEntry("w", 1, "2026-11-21", 79.0, null)) })
        assertEquals("{ broken", File(dir, "state.json").readText())
    }

    // N6: full-width Latin letters are Latin letters too.
    @Test fun fullWidthLatinIsDropped() {
        assertEquals("現在去練。", NumberGuard.filter("ＯＫ。現在去練。", "", ""))
    }

    // N7: everyday words are not invented amounts.
    @Test fun everydayCountWordsAreKept() {
        assertEquals("今天正常吃三餐。", NumberGuard.filter("今天正常吃三餐。", "", ""))
        assertEquals("一天一份就好。", NumberGuard.filter("一天一份就好。", "", ""))
        assertEquals("", NumberGuard.filter("再吃兩百大卡。", "", ""))
        // I7 (review 3): an everyday word at the end of a longer numeral is still an amount.
        assertEquals("", NumberGuard.filter("延後二十一天。", "", ""))
        assertEquals("", NumberGuard.filter("再吃十一份。", "", ""))
        assertEquals("", NumberGuard.filter("十三餐。", "", ""))
    }

    // N8: 兩百五 is 250 and 一千二 is 1200; a table food's own numeral is not a count offline.
    @Test fun colloquialChineseNumbersAndTableNamesWithNumerals() {
        assertEquals(250.0, CoachNumbers.chinese("兩百五")!!, 1e-9)
        assertEquals(1200.0, CoachNumbers.chinese("一千二")!!, 1e-9)
        assertEquals(205.0, CoachNumbers.chinese("兩百零五")!!, 1e-9)
        rig.store.update { it.copy(food = it.food + FoodEntry("三杯雞便當", listOf("三杯雞"), 800, null, null, null, false, false, false, 0)) }
        assertTrue(offline("吃了三杯雞便當").contains("800 大卡"))
        assertTrue(offline("全家紐奧良風味烤雞鮮蔬三明治").contains("205 大卡"))
        assertEquals(CoachReplies.cannotReach(Fail.OFFLINE), offline("兩個三杯雞便當"))
    }

    // M-a (review 3): no options; the coach picks from the menu.
    @Test fun whenNothingKnownFitsTheOwnerSendsTheMenu() {
        say("吃了便當", parsed("meal", item("便當", low = 900, high = 1000))) // 1100 eaten, 600 left
        val r = say("想吃牛肉麵", parsed("craving", item("牛肉麵", low = 700, high = 800)))
        assertTrue(r, r.endsWith("今天剩 600 大卡。點之前先把菜單傳來，我來選。"))
        assertFalse(r, r.contains("挑"))
    }

    // M-b (review 3): a planned meal missing from the table is never named.
    @Test fun aPlannedMealMissingFromTheTableIsNeverNamed() {
        rig.store.update { it.copy(food = it.food.filter { f -> f.name != "給力盒子 南加州烤雞腿" }) }
        val r = say("我想吃臭豆腐", parsed("craving", item("臭豆腐", fried = true)))
        assertFalse(r, r.contains("南加州"))
        assertTrue(r, r.startsWith("今天不是休假餐。今天至少還要吃 1200 大卡。今天剩 1700 大卡。點之前先把菜單傳來，我來選。"))
    }

    // M-c (review 3): 百 or 千 with no digit before it means one.
    @Test fun aBareHundredOrThousandMeansOne() {
        assertEquals(150.0, CoachNumbers.chinese("百五")!!, 1e-9)
        assertEquals(1200.0, CoachNumbers.chinese("千二")!!, 1e-9)
        assertEquals(100.0, CoachNumbers.chinese("百")!!, 1e-9)
        assertEquals(205.0, CoachNumbers.chinese("兩百零五")!!, 1e-9)
    }
}
