package app.dshmobile.coach

import app.agency.core.rules.FoodTable
import app.dshmobile.coach.CoachRig.Companion.item
import app.dshmobile.coach.CoachRig.Companion.parsed
import java.time.DayOfWeek
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachEngineTest {
    private val rig = CoachRig()

    @After fun tearDown() = rig.close()

    private fun say(text: String, parse: String): String = runBlocking {
        rig.model.parse = { ModelReply.Ok(parse) }
        rig.engine.handle(text)
        rig.store.messages.value.last().text
    }

    @Test fun aReportedMealIsCountedFromTheTableAndSaved() {
        val reply = say("我吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertTrue(reply, reply.contains("記下：給力盒子 南加州烤雞腿 1份 629 大卡（食物表）。"))
        assertTrue(reply, reply.contains("這餐先記「未證實」。"))
        assertTrue(reply, reply.contains("今天剩 1071 大卡，蛋白質還差 89 克。"))
        assertTrue(reply, reply.contains("今天至少還要吃 571 大卡。"))
        assertEquals(1, rig.store.state.value.meals.size)
        assertEquals(1071, rig.engine.ui.value.strip.remainingKcal)
    }

    @Test fun goingOverGivesTheFourLineBlock() {
        say("晚餐吃了兩個給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿", 2.0)))
        val reply = say("宵夜吃了兩碗蚵仔麵線", parsed("meal", item("蚵仔麵線", 2.0)))
        assertTrue(reply, reply.contains("記下：蚵仔麵線 2碗 830 大卡（食物表・估算）。"))
        assertTrue(
            reply,
            reply.endsWith(
                "超過 388 大卡。\n" +
                    "身材：每天都這樣，一個月多 1.7 公斤，赤字只剩 112，等於不會瘦。\n" +
                    "身體：留在肥胖區的時間多 0.8 天，75 公斤延後 0.8 天。\n" +
                    "明天回到 1700。不准少吃來補。",
            ),
        )
        assertEquals(388, rig.engine.ui.value.strip.overBy)
    }

    @Test fun aFriedCravingOnANormalDayIsRefusedWordForWord() {
        val reply = say("我想吃臭豆腐跟蚵仔煎", parsed("craving", item("臭豆腐", fried = true), item("蚵仔煎")))
        assertEquals("今天不是休假餐。晚餐吃給力盒子南加州烤雞腿。臭豆腐排到星期五。30 分鐘內把訂單截圖傳來。", reply)
        assertTrue(rig.store.state.value.meals.isEmpty())
    }

    @Test fun aFriedWordIsRefusedEvenIfTheModelSaysNotFried() {
        val reply = say("我想吃鹽酥雞", parsed("craving", item("鹽酥雞", fried = false, low = 500, high = 700)))
        assertTrue(reply, reply.startsWith("今天不是休假餐。"))
    }

    @Test fun onTheCheatDayAFriedCravingIsAllowedWhenItFits() {
        // 休假餐 is fixed on Friday (公司規章): Friday 2026-11-20 19:00 in Taipei.
        rig.clock.now = java.time.Instant.parse("2026-11-20T11:00:00Z")
        val reply = say("我想吃臭豆腐", parsed("craving", item("臭豆腐", fried = true)))
        assertTrue(reply, reply.startsWith("可以。吃了今天會剩 1135 大卡。"))
    }

    @Test fun afterAMealTheRefusalSaysLateNightAndWhenOverItSaysNoMore() {
        say("吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertEquals(
            "今天不是休假餐。宵夜吃給力盒子南加州烤雞腿。臭豆腐排到星期五。30 分鐘內把訂單截圖傳來。",
            say("想吃臭豆腐", parsed("craving", item("臭豆腐", fried = true))),
        )
        say("吃了兩個給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿", 2.0)))
        assertTrue(say("想吃臭豆腐", parsed("craving", item("臭豆腐", fried = true))).contains("今天已經超過，不准再點。"))
    }

    @Test fun lateAtNightItIsStillTheEveningBefore() {
        rig.clock.now = java.time.Instant.parse("2026-11-20T18:00:00Z") // Saturday 02:00 in Taipei: Friday's coach day
        val reply = say("想吃臭豆腐", parsed("craving", item("臭豆腐", fried = true)))
        assertTrue(reply, reply.startsWith("可以。"))
    }

    @Test fun aNonFriedCravingThatDoesNotFitIsRefused() {
        say("吃了兩個給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿", 2.0)))
        val reply = say("想吃牛肉麵", parsed("craving", item("牛肉麵", low = 600, high = 700)))
        assertEquals("這份大約 770 大卡，今天剩 442 大卡，不准再點。", reply)
    }

    @Test fun anEstimateIsAskedAboutAndOnlyStoredAfterTheLabel() {
        val r1 = say("我還吃了一碗牛肉麵", parsed("meal", item("牛肉麵", low = 550, high = 650, pLow = 25)))
        assertTrue(r1, r1.contains("記下：牛肉麵 1份 715 大卡（估算，取高值加一成）。"))
        assertTrue(r1, r1.contains("牛肉麵不在食物表。把包裝標示或份量傳來，確認了才存進表。"))
        assertEquals(5, rig.store.state.value.food.size)
        val r2 = say("標示寫 700 大卡 蛋白質 35 克", parsed("food_info", foodInfo = """{"name":"牛肉麵","kcal":700,"protein_g":35,"confirm":false}"""))
        assertTrue(r2, r2.startsWith("存進食物表：牛肉麵 700 大卡、蛋白質 35 克（標示）。"))
        assertTrue(r2, r2.contains("今天剩 1000 大卡"))
        val e = FoodTable.lookup(rig.store.state.value.food, "牛肉麵")!!
        assertFalse(e.estimated)
        assertTrue(rig.store.state.value.pending.isEmpty())
    }

    @Test fun confirmingWithoutALabelStoresTheStrictEstimate() {
        say("吃了牛肉麵", parsed("meal", item("牛肉麵", low = 550, high = 650)))
        val r = say("對", parsed("food_info", foodInfo = """{"name":null,"kcal":null,"protein_g":null,"confirm":true}"""))
        assertTrue(r, r.startsWith("存進食物表：牛肉麵 715 大卡（估算）。"))
        assertTrue(FoodTable.lookup(rig.store.state.value.food, "牛肉麵")!!.estimated)
    }

    @Test fun aWeightIsRecordedWithTheNextGoal() {
        val r = say("今天體重 79.2", parsed("weight", weight = 79.2))
        assertEquals("記下：79.2 公斤。身體質量指數 28.1，肥胖區。下一個目標 75 公斤，還差 4.2 公斤。", r)
        assertEquals(75.0, rig.engine.ui.value.strip.nextGoalKg!!, 0.0)
    }

    @Test fun feelingIllStopsThePressure() {
        say("吃了三個給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿", 3.0)))
        val r = say("我今天頭暈，又吃了蚵仔煎", parsed("meal", item("蚵仔煎")))
        assertFalse(r, r.contains("超過"))
        assertTrue(r, r.contains("今天先正常吃，不用管額度。"))
        assertTrue(r, r.contains("看醫生"))
    }

    @Test fun aTalkMessageGoesThroughTheModelUnderTheGuard() {
        rig.model.chat = { ModelReply.Ok("這是藉口，第二種。10 分鐘，現在練，練完傳錄音。再吃 300 大卡沒差。") }
        val r = say("生存都這麼難了還練什麼", parsed("chat"))
        assertEquals("這是藉口，第二種。10 分鐘，現在練，練完傳錄音。", r)
        val (system, _) = rig.model.calls.last()
        assertTrue(system.startsWith(COACH_SYSTEM_VERBATIM))
    }

    @Test fun anUnreadableParseTwiceSavesNothing() {
        val r = say("嗯嗯", "不是 JSON")
        assertEquals(CoachReplies.NOT_UNDERSTOOD, r)
        assertTrue(rig.store.state.value.meals.isEmpty())
    }

    @Test fun offlineATableFoodIsStillRecorded() {
        val r = runBlocking {
            rig.model.parse = { ModelReply.Failed(Fail.OFFLINE) }
            rig.engine.handle("蚵仔煎一盤")
            rig.store.messages.value.last().text
        }
        assertTrue(r, r.contains("記下：蚵仔煎 1盤 516 大卡（食物表・估算）。"))
        assertEquals(1, rig.store.state.value.meals.size)
        val r2 = runBlocking { rig.engine.handle("我想吃蚵仔煎"); rig.store.messages.value.last().text }
        assertEquals(CoachReplies.cannotReach(Fail.OFFLINE), r2)
    }

    @Test fun aFailedReplyCallKeepsTheRecords() {
        rig.model.chat = { ModelReply.Failed(Fail.SERVER) }
        say("吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        assertEquals(CoachReplies.CHAT_DOWN, say("今天好累", parsed("chat")))
        assertEquals(1, rig.store.state.value.meals.size)
    }

    @Test fun theNextDayStartsAt1700Again() {
        say("吃了兩個給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿", 2.0)))
        rig.clock.advance(Duration.ofHours(24))
        rig.engine.refresh()
        assertEquals(1700, rig.engine.ui.value.strip.remainingKcal)
    }

    @Test fun theRecordsSurviveARestart() {
        say("吃了給力盒子南加州烤雞腿", parsed("meal", item("給力盒子南加州烤雞腿")))
        say("今天體重 79.2", parsed("weight", weight = 79.2))
        val again = CoachStore(rig.dir) { rig.clock.millis() }
        assertEquals(rig.store.state.value, again.state.value)
        assertEquals(rig.store.messages.value, again.messages.value)
        assertEquals(4, again.messages.value.size)
    }
}
