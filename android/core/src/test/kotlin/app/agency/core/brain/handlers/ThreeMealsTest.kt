package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.CompanyRules
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.persona.Roles
import app.agency.core.rules.Breach
import app.agency.core.rules.FoodTable
import app.agency.core.rules.MealBudget
import app.agency.core.rules.ProcessedFood
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlanner
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's meal rules of 2026-11-26: three meals (12:10, 17:35, 21:00), no eating window and no snack; lunch up to the
 * single-meal cap with dinner following what is left; the 21:00 swaps of the same protein; 雞胸 one pack a day and the protein
 * rotated; his 「常吃的食物」 in the table. Invented data only.
 */
class ThreeMealsTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-11-23") // a Monday, a class day

    init {
        runBlocking { store.profile.put(Profile(onboardingDone = true)) }
    }

    private fun brain(llm: ScriptedLlm) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%04d".format(++n) }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun item(name: String, high: Int, protein: Int) =
        """{"name":"$name","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":${high - 50},"kcal_high":$high,"protein_low":$protein,"protein_high":$protein}"""

    private fun say(hhmm: String, text: String, kind: String, vararg items: String): List<Message> {
        at(hhmm)
        val llm = ScriptedLlm(ScriptedLlm.ok("""{"kind":"$kind","items":[${items.joinToString(",")}]}"""))
        return runBlocking { brain(llm).onText(text) }.filter { it.who == Who.COMPANY }
    }

    @Test fun threeMealsAtTheirTimesNoSnackAndNoEatingWindow() {
        val p = DayPlanner.build(day)
        assertEquals(listOf("12:10 LUNCH", "17:35 DINNER", "21:00 RIDE_MEAL"), p.slots.filter { it.key in SlotKey.MEALS }.map { "${it.time} ${it.key}" })
        assertTrue(p.slot(SlotKey.RIDE_MEAL)!!.note!!.contains("福樂蛋白飲加茶葉蛋、義美高蛋白奶加茶葉蛋或低脂鮮奶"))
        val body = Roles.BODY.voice.joinToString()
        assertTrue(body.contains("沒有點心") && body.contains("不做 168"))
    }

    @Test fun lunchMayGoUpToTheCapAndDinnerFollowsWhatIsLeft() {
        val p = DayPlanner.build(day)
        val rules = CompanyRules()
        // Nothing eaten yet: lunch may be 800 (it was 676 before: a lunch order of 700 was sent back).
        assertEquals(800, MealBudget.room(p, rules, SlotKey.LUNCH, 0, 0.0, emptySet(), DayClock.minute("12:00")))
        assertEquals(800, MealBudget.next(p, rules, 0, 0.0, emptySet(), DayClock.minute("11:00"))!!.kcal)
        // After an 800 lunch, dinner is what is left after the 21:00 portion: 1700 − 800 − 310 = 590.
        val dinner = MealBudget.next(p, rules, 800, 50.0, setOf(SlotKey.LUNCH), DayClock.minute("12:40"))!!
        assertEquals(SlotKey.DINNER to 590, dinner.slot to dinner.kcal)
        assertTrue(dinner.smaller)
        // A small lunch leaves dinner up to the cap, never more.
        assertEquals(800, MealBudget.next(p, rules, 300, 20.0, setOf(SlotKey.LUNCH), DayClock.minute("12:40"))!!.kcal)
    }

    @Test fun theUsualFoodsAreInTheTableWithoutLatinLettersInTheirNames() {
        val t = FoodTable.current(0)
        fun e(name: String) = t.single { it.name == name }
        assertEquals(605 to 43.0, e("給力盒子 椒鹽水煮雞胸").let { it.kcal to it.proteinG })
        assertEquals(690 to 44.0, e("給力盒子 大厚切烤鮭魚").let { it.kcal to it.proteinG })
        assertEquals(347 to 22.0, e("麥當勞 炭燒醬烤雞腿堡").let { it.kcal to it.proteinG })
        assertEquals(209 to 21.0, e("福樂 蛋白飲").let { it.kcal to it.proteinG }) // a range takes its high end
        assertEquals(195 to 20.0, e("義美 高蛋白奶").let { it.kcal to it.proteinG })
        assertEquals(178 to 12.8, e("低脂鮮奶").let { it.kcal to it.proteinG })
        assertEquals(117 to 23.6, e("即食雞胸").let { it.kcal to it.proteinG })
        assertTrue(e("茶葉蛋").estimated && e("麥當勞 大麥克").estimated)
        assertEquals(e("麥當勞 香烙烤鷄腿堡"), FoodTable.lookup(t, "BLT香烙烤鷄腿堡"))
        assertTrue(t.all { f -> f.name.none { it in 'a'..'z' || it in 'A'..'Z' } })
        // 福樂 has added sugar but is his allowed protein drink, not a banned sugary drink.
        assertTrue(ProcessedFood.categories("福樂蛋白飲").isEmpty())
    }

    @Test fun theTwentyOneOClockSwapCountsAndIsNoBreach() {
        val out = say("21:05", "吃了福樂蛋白飲和茶葉蛋", "meal", item("福樂蛋白飲", 230, 18), item("茶葉蛋", 90, 6)).first().text
        assertTrue(out, out.startsWith("記在21:00 那份蛋白質。"))
        assertTrue(out.contains("福樂 蛋白飲 1份 209 大卡（食物表）"))
        val m = runBlocking { store.meals.onDay(day.toString()) }.single()
        assertEquals("RIDE_MEAL", m.slotKey)
        assertEquals(284, m.kcal)
        assertTrue(m.breaches.isEmpty())
    }

    @Test fun oneChickenPackADayAndTheProteinRotates() {
        // Two days of 雞胸 at 21:00, then the third: rotate.
        for (d in 0..1) {
            day = LocalDate.parse("2026-11-23").plusDays(d.toLong())
            val out = say("21:05", "吃了即食雞胸和統一高纖豆漿", "meal", item("即食雞胸", 130, 20), item("統一高纖豆漿", 200, 15)).first().text
            assertTrue(out, !out.contains("輪著換"))
        }
        day = LocalDate.parse("2026-11-25")
        val third = say("21:05", "吃了即食雞胸和統一高纖豆漿", "meal", item("即食雞胸", 130, 20), item("統一高纖豆漿", 200, 15)).first().text
        assertTrue(third, third.contains("蛋白質來源要輪著換：21:00 已經連 3 天都是雞胸了，明天換成福樂蛋白飲加茶葉蛋、義美高蛋白奶加茶葉蛋或低脂鮮奶。"))
        // A second pack today: the note, never a breach.
        val second = say("22:30", "又吃了一包即食雞胸", "meal", item("即食雞胸", 130, 20)).first().text
        assertTrue(second, second.contains("雞胸一天最多一包（鈉高），今天已經 2 包了。"))
        assertTrue(runBlocking { store.meals.onDay(day.toString()) }.all { m -> m.breaches.none { it == Breach.PROCESSED.name } })
        // Wanting a third: refused by code, with the swaps.
        val want = say("23:00", "我想吃即食雞胸", "craving", item("即食雞胸", 130, 20)).first().text
        assertEquals("雞胸今天已經吃過一包了，一天最多一包（鈉高）。換成福樂蛋白飲加茶葉蛋、義美高蛋白奶加茶葉蛋或低脂鮮奶。", want)
    }

    @Test fun theSweetProteinDrinkIsNotedAfterThreeAWeek() {
        val monday = LocalDate.parse("2026-11-23")
        for (d in 0..3) {
            day = monday.plusDays(d.toLong())
            val out = say("21:05", "吃了福樂蛋白飲", "meal", item("福樂蛋白飲", 230, 18)).first().text
            assertEquals(d == 3, out.contains("福樂蛋白飲有加糖，一週最多 3 次，這週第 4 次了。"))
        }
    }
}
