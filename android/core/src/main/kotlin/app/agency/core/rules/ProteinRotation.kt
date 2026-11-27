package app.agency.core.rules

import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.schedule.SlotKey
import java.time.LocalDate
import kotlin.math.ceil

/**
 * The 21:00 portion's protein (owner 2026-11-26), pure: 雞胸 at most one pack a day (its sodium), the protein source rotated
 * (no three days in a row of 雞胸 at 21:00), and 福樂蛋白飲 (added sugar) at most [SWEET_DRINK_PER_WEEK] a week. These are the
 * body teacher's notes, not breaches of 公司規章 (its banned list is the only thing that makes one).
 */
object ProteinRotation {
    const val MAX_PACKS_PER_DAY = 1
    const val ROTATE_AFTER_DAYS = 3
    const val SWEET_DRINK_PER_WEEK = 3

    const val SWAPS = "福樂蛋白飲加茶葉蛋、義美高蛋白奶加茶葉蛋或低脂鮮奶"

    /** A pack of ready-to-eat chicken breast (not a 給力盒子 box with chicken breast in it). */
    fun isPack(name: String): Boolean = name.contains("即食雞胸") || name.contains("超商雞胸") || name.trim() in setOf("雞胸", "雞胸肉", "一包雞胸")

    fun packs(items: List<MealItem>): Int = items.filter { isPack(it.name) }.sumOf { ceil(it.portion - 1e-9).toInt() }

    fun isSweetDrink(name: String): Boolean = name.contains("福樂")

    /** The notes after a meal: [today] with the new meal, [days] the meals of the days before (most recent first is not needed). */
    fun notes(meal: Meal, today: List<Meal>, week: List<Meal>, byDay: Map<LocalDate, List<Meal>>, day: LocalDate): List<String> = buildList {
        val packs = packs(today.flatMap { it.items })
        if (packs(meal.items) > 0 && packs > MAX_PACKS_PER_DAY) {
            add("雞胸一天最多一包（鈉高），今天已經 $packs 包了。下次換成$SWAPS。")
        }
        val sweet = week.flatMap { it.items }.filter { isSweetDrink(it.name) }.sumOf { ceil(it.portion - 1e-9).toInt() }
        if (meal.items.any { isSweetDrink(it.name) } && sweet > SWEET_DRINK_PER_WEEK) {
            add("福樂蛋白飲有加糖，一週最多 $SWEET_DRINK_PER_WEEK 次，這週第 $sweet 次了。下次換義美高蛋白奶加茶葉蛋或低脂鮮奶。")
        }
        if (meal.slotKey == SlotKey.RIDE_MEAL.name && packs(meal.items) > 0) {
            val inRow = (1 until ROTATE_AFTER_DAYS).all { back ->
                byDay[day.minusDays(back.toLong())].orEmpty().any { it.slotKey == SlotKey.RIDE_MEAL.name && packs(it.items) > 0 }
            }
            if (inRow) add("蛋白質來源要輪著換：21:00 已經連 $ROTATE_AFTER_DAYS 天都是雞胸了，明天換成$SWAPS。")
        }
    }
}
