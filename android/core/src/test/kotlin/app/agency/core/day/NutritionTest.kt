package app.agency.core.day

import app.agency.core.store.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NutritionTest {
    private val rules = Kit.rules

    @Test fun tableFoodIsExactAndBeatsTheGuess() {
        val item = Nutrition.resolve(rules, "測試雞腿便當", 999, 1.0)
        assertEquals(700, item.kcal)
        assertEquals(40.0, item.proteinG, 0.0)
        assertEquals(FoodSource.TABLE, item.source)
    }

    @Test fun aliasAndContainmentBothWaysMatchTheTable() {
        assertEquals(FoodSource.TABLE, Nutrition.resolve(rules, "一個雞腿便當加湯", 100, 1.0).source)
        assertEquals(FoodSource.TABLE, Nutrition.resolve(rules, " 豆漿 ", 100, 1.0).source)
        assertEquals(200, Nutrition.resolve(rules, "豆漿", 5, 0.0).kcal)
    }

    @Test fun longestTableMatchWins() {
        val r = rules.copy(foods = rules.foods + app.agency.core.rules.Food("雞腿便當加蛋", 790, 45.0))
        assertEquals(790, Nutrition.resolve(r, "雞腿便當加蛋", 0, 0.0).kcal)
    }

    @Test fun labelNumbersAreKeptAsGiven() {
        val item = Nutrition.resolve(rules, "超商御飯糰", 233, 7.5, FoodSource.LABEL)
        assertEquals(233, item.kcal)
        assertEquals(7.5, item.proteinG, 0.0)
        assertEquals(FoodSource.LABEL, item.source)
    }

    @Test fun estimateIsRaisedByTheMarginAndRoundedUpToTen() {
        assertEquals(560, Nutrition.resolve(rules, "不知名麵", 500, 20.0).kcal)
        assertEquals(380, Nutrition.resolve(rules, "不知名麵", 333, 20.0).kcal)
        assertEquals(10, Nutrition.resolve(rules, "不知名麵", 1, 20.0).kcal)
        assertEquals(0, Nutrition.resolve(rules, "不知名麵", 0, 20.0).kcal)
    }

    @Test fun estimatedProteinIsRoundedDown() {
        assertEquals(23.0, Nutrition.resolve(rules, "不知名麵", 400, 23.9).proteinG, 0.0)
        assertEquals(FoodSource.ESTIMATE, Nutrition.resolve(rules, "不知名麵", 400, 23.9).source)
    }

    @Test fun marginComesFromTheRules() {
        val strict = rules.copy(diet = rules.diet.copy(estimateMarginPct = 50))
        assertEquals(600, Nutrition.resolve(strict, "不知名麵", 400, 0.0).kcal)
    }

    @Test fun bannedCategoryByWord() {
        assertEquals("炸物", Nutrition.banned(rules, "炸雞排"))
        assertEquals("含糖飲料", Nutrition.banned(rules, "珍珠奶茶"))
        assertNull(Nutrition.banned(rules, "烤雞胸沙拉"))
    }

    @Test fun allowedEntryAndTableFoodAreNeverBanned() {
        assertNull(Nutrition.banned(rules, "氣炸地瓜"))
        val r = rules.copy(foods = rules.foods + app.agency.core.rules.Food("酥脆雞胸", 200, 30.0))
        assertNull(Nutrition.banned(r, "酥脆雞胸"))
    }

    @Test fun resolvedItemCarriesItsBannedCategory() {
        assertEquals("炸物", Nutrition.resolve(rules, "炸雞排", 450, 25.0).banned)
        assertNull(Nutrition.resolve(rules, "測試雞胸", 450, 25.0).banned)
    }
}
