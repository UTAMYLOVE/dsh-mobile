package app.agency.core.brain

import app.agency.core.model.CompanyRules
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.model.Who
import app.agency.core.time.AgencyClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented data only. */
object Fixtures {
    val profile = Profile(heightCm = 172.0, startWeightKg = 81.0, stageName = "小雨", areaText = "某某區", idolNames = listOf("Ahyeon"))
    val meal = Meal(
        id = "meal-a", day = "2026-11-20", at = 1_763_611_200_000, slotKey = "lunch", text = "我吃了雞胸便當 https://shop.example.com/x",
        items = listOf(MealItem("雞胸便當", 1.0, 629, 40.0, null, null, false, KcalSource.TABLE)),
        verified = true, photoMediaId = "media-photo-77",
    )
    val sign = SignLog("sign-a", "2026-11-20", 1_763_598_000_000, SignKind.OUT, onTime = true, reviewGood = "很私人的回顧", reviewFix = "別的私事")

    fun messages(n: Int) = (1..n).map { i ->
        Message("m$i", i.toLong(), if (i % 2 == 0) Who.COMPANY else Who.OWNER, role = if (i % 2 == 0) RoleKey.MANAGER else null, text = "第${i}則")
    }

    fun input(history: List<Message> = messages(4), summaries: Int = 3) = FactsInput(
        rules = CompanyRules(),
        profile = profile,
        currentWeightKg = 79.0,
        today = TodayRecords("2026-11-20", meals = listOf(meal), signs = listOf(sign)),
        summaries = (1..summaries).map { DaySummary("2026-11-${20 - it}", true, 1600, 120.0, 0, true) },
        history = history,
        zone = AgencyClock.TAIPEI,
    )
}

class FactsBuilderTest {
    @Test fun theFactsCarryOnlyWhatACallMay() {
        val json = FactsBuilder.facts(Fixtures.input())
        val s = json.toString()
        assertTrue(s.contains("\"quota_kcal\":1700"))
        assertTrue(s.contains("\"next_goal_kg\":75.0"))
        assertTrue(s.contains("\"left\":1071"))
        assertTrue(s.contains("\"stage_name\":\"小雨\""))
        for (never in listOf("media-photo-77", "photo", "某某區", "Ahyeon", "http", "很私人的回顧", "別的私事", "我吃了", "81.0")) {
            assertFalse(never, s.contains(never))
        }
    }

    @Test fun neverMoreThanSevenSummariesOrTenMessages() {
        val f = FactsBuilder.build(Fixtures.input(history = Fixtures.messages(30), summaries = 12))
        assertEquals(FactsBuilder.MAX_SUMMARIES, (f.json["last_days"] as JsonArray).size)
        assertEquals(FactsBuilder.MAX_MESSAGES - 1, f.window.size)
        assertEquals("m30", f.window.last().id)
        assertEquals(FactsBuilder.MAX_MESSAGES, FactsBuilder.build(Fixtures.input(history = Fixtures.messages(30)), hasCurrentMessage = false).window.size)
        assertEquals("2026-11-19", (f.json["last_days"] as JsonArray)[0].jsonObject["day"]!!.jsonPrimitive.content)
    }

    @Test fun messagesAreCutAndLoseLinksAndPaths() {
        val long = Message("x", 1, Who.OWNER, text = "看 https://maps.example.com/abc 和 /storage/emulated/0/DCIM/a.jpg " + "字".repeat(900))
        val w = FactsBuilder.window(listOf(long, Message("s", 2, Who.SYSTEM, text = "系統")), hasCurrentMessage = true)
        assertEquals(1, w.size)
        val t = w.single().text
        assertEquals(FactsBuilder.MESSAGE_CHARS, t.length)
        assertFalse(t.contains("http"))
        assertFalse(t.contains("storage"))
        assertFalse(t.contains(".jpg"))
    }

    @Test fun linksWithoutASchemeAndFileNamesAreTakenOut() {
        for (t in listOf("youtu.be/xyz123", "bit.ly/3abc", "line.me/ti/p/xyz", "shop.example.co.jp/menu", "IMG_20261120.jpg", "錄音 a1.m4a")) {
            val c = FactsBuilder.clean("看 $t 這個")
            assertFalse("$t → $c", c.contains(t))
        }
        assertEquals("早上七十九點二，79.2 公斤", FactsBuilder.clean("早上七十九點二，79.2 公斤"))
    }

    @Test fun todaysListsAreCappedAndSlotsCarryTheirTimes() {
        val meals = (1..15).map { Fixtures.meal.copy(id = "meal-$it", at = Fixtures.meal.at + it) }
        val signs = (1..14).map { Fixtures.sign.copy(id = "sign-$it") }
        val base = Fixtures.input()
        val json = FactsBuilder.facts(
            base.copy(today = base.today.copy(meals = meals, signs = signs), slots = listOf(SlotFact("dinner", "晚餐", "17:15", "open"))),
        )
        val today = json["today"]!!.jsonObject
        assertEquals(FactsBuilder.MAX_MEALS, (today["meals"] as JsonArray).size)
        assertEquals("meal-15", (today["meals"] as JsonArray).last().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(3, today["meals_more"]!!.jsonPrimitive.content.toInt())
        assertEquals(FactsBuilder.MAX_LIST, (today["signs"] as JsonArray).size)
        assertTrue(today["slots"].toString().contains("\"time\":\"17:15\""))
        // Totals still count every meal.
        assertEquals(15 * 629, today["kcal"]!!.jsonObject["eaten"]!!.jsonPrimitive.content.toInt())
        assertTrue(TodayRecords("d", meals = listOf(Fixtures.meal.copy(breaches = listOf("PROCESSED")))).breachToday)
    }
}
