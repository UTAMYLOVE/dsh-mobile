package app.agency.core.rules

import app.agency.core.model.CompanyRules
import app.agency.core.store.InMemoryAgencyStore
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公司規章 (plan v3 §6, arch T11): bounds, edits only from 設定 with history and audit, and no change in chat. */
class RulesBookTest {
    @Test fun theBounds() {
        assertTrue(SafetyBounds.check(CompanyRules()).isEmpty())
        assertEquals(listOf("floor_kcal"), SafetyBounds.check(CompanyRules(floorKcal = 1100)).map { it.field }.distinct().take(1))
        assertTrue(SafetyBounds.check(CompanyRules(goalsKg = listOf(75.0, 50.0))).any { it.field == "goals_kg" })
        assertTrue(SafetyBounds.check(CompanyRules(goalsKg = listOf(75.0, 52.0))).isEmpty())
        assertTrue(SafetyBounds.check(CompanyRules(quotaKcal = 1150)).any { it.field == "quota_kcal" })
        assertTrue(SafetyBounds.check(CompanyRules(mealCapKcal = 1300)).any { it.field == "meal_cap_kcal" })
        assertTrue(SafetyBounds.check(CompanyRules(cheatMealsPerWeek = 2)).any { it.field == "cheat_meals_per_week" })
    }

    @Test fun anEditWritesTheNextVersionItsHistoryAndAnAudit() = runBlocking {
        val store = InMemoryAgencyStore()
        var n = 0
        val r = RulesBook.edit(store, CompanyRules(quotaKcal = 1800), 100L) { "a${++n}" }
        assertTrue(r is RulesBook.Result.Saved)
        assertEquals(1800, store.rules.current()!!.quotaKcal)
        assertEquals(1, store.rules.history().size)
        val audit = store.journal.audits().single()
        assertEquals("rules_edit", audit.what)
        assertEquals("v1: quota_kcal 1700→1800", audit.detail)
        assertEquals(RulesBook.Result.Unchanged, RulesBook.edit(store, CompanyRules(quotaKcal = 1800), 200L) { "a${++n}" })
        val refused = RulesBook.edit(store, CompanyRules(quotaKcal = 1800, floorKcal = 1000), 300L) { "a${++n}" }
        assertTrue(refused is RulesBook.Result.Refused)
        assertEquals(1200, store.rules.current()!!.floorKcal)
        assertEquals(1, store.journal.audits().size)
    }

    @Test fun aChangeAttemptInChatIsSeen() {
        listOf(
            "每天熱量可以改成 1900 嗎", "公司規章放寬一點啦", "單餐上限調到 1000", "最低熱量降到 1000", "休假餐可以一週兩次嗎",
            "零卡飲料的額度多給一點", "目標體重改成 60", "蛋白質目標降低一點好不好",
        ).forEach { assertTrue(it, RulesBook.isChangeAttempt(it)) }
        listOf(
            "晚餐換成 800 大卡的便當", "今天熱量超過了", "幫我改一下晚餐吃什麼", "規章寫什麼", "1700 還剩多少", "我想改吃雞胸",
            "休假餐 星期四晚餐",
        ).forEach { assertFalse(it, RulesBook.isChangeAttempt(it)) }
    }

    @Test fun theCheatMealIsClosedUntilTheEndOf2026() {
        assertFalse(RulesBook.cheatOpen(LocalDate.parse("2026-11-22")))
        assertFalse(RulesBook.cheatOpen(LocalDate.parse("2026-12-31")))
        assertTrue(RulesBook.cheatOpen(LocalDate.parse("2027-01-01")))
    }

    @Test fun bannedFood() {
        assertEquals(setOf(BannedCategory.INSTANT_NOODLES), ProcessedFood.categories("辛拉麵泡麵"))
        assertEquals(setOf(BannedCategory.PROCESSED_MEAT, BannedCategory.FRIED), ProcessedFood.categories("炸雞塊"))
        assertEquals(setOf(BannedCategory.SUGARY_DRINKS), ProcessedFood.categories("半糖珍奶"))
        assertTrue(ProcessedFood.categories("零卡可樂").isEmpty())
        listOf("即食雞胸", "統一高纖豆漿", "給力盒子南加州烤雞腿", "全家紐奧良烤雞鮮蔬三明治", "麥當勞嫩煎雞腿堡", "藜麥烤雞沙拉").forEach {
            assertTrue(it, ProcessedFood.categories(it).isEmpty())
        }
        // Rules written before T11 hold no keys: the whole list applies. A declared 休假餐 allows them.
        assertEquals(BannedCategory.entries.toSet(), ProcessedFood.banned(CompanyRules()))
        assertTrue(ProcessedFood.breaches("鹽酥雞", CompanyRules(), cheat = true).isEmpty())
        assertEquals(setOf(BannedCategory.FRIED), ProcessedFood.breaches("鹽酥雞", CompanyRules()))
    }
}
