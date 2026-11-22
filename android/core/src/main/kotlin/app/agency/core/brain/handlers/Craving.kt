package app.agency.core.brain.handlers

import app.agency.core.brain.SnapshotReader
import app.agency.core.engine.Links
import app.agency.core.model.CompanyRules
import app.agency.core.model.FoodEntry
import app.agency.core.rules.BannedCategory
import app.agency.core.rules.Breaches
import app.agency.core.rules.FoodTable
import app.agency.core.rules.MealBudget
import app.agency.core.rules.ProcessedFood
import app.agency.core.rules.RulesBook
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.SlotKey
import app.agency.core.store.AgencyStore
import app.agency.core.structured.IntakeItem
import app.agency.core.time.AgencyClock

/** A food of the table that fits a budget (the suggestion's pick). */
data class Pick(val entry: FoodEntry, val kcal: Int)

/** What fits (code): table foods outside the banned list and within the budget, the most protein first. */
object MealPicker {
    fun fits(table: List<FoodEntry>, rules: CompanyRules, budgetKcal: Int, avoid: Set<String> = emptySet()): List<Pick> =
        table.filter { e ->
            e.kcal in 1..budgetKcal && e.name !in avoid && !e.fried &&
                (ProcessedFood.categories(e.name) intersect ProcessedFood.banned(rules)).isEmpty() && e.name !in FIXED
        }.sortedWith(compareByDescending<FoodEntry> { it.proteinG ?: -1.0 }.thenByDescending { it.kcal }).map { Pick(it, it.kcal) }

    /** The two fixed portions (the 21:00 chicken breast and the soy milk) are no meal suggestion of their own. */
    val FIXED = setOf("即食雞胸", "統一高纖豆漿")

    fun line(p: Pick): String = "${p.entry.name.replace(" ", "")}（${p.kcal} 大卡${p.entry.proteinG?.let { "、蛋白質 ${MealWords.portion(it)} 克" }.orEmpty()}${if (p.entry.estimated) "，估算" else ""}）"
}

/**
 * 「我想吃…」「可以吃…嗎」 (arch 1.5): code answers. Refused when it is on the banned list outside the declared 休假餐, the
 * fourth zero-calorie drink of the week, above the single-meal cap, or above what this meal may be today (L5's budget for a
 * planned meal; what is left today for an extra). A refusal names what fits instead when the table has one, otherwise asks
 * for the menu. Never 「少吃」: the planned meals stay, and a day already over is 「明天回到 1700」.
 */
class CravingDesk(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val meals: MealDesk,
) {
    suspend fun answer(text: String, said: List<IntakeItem>): List<String> {
        if (said.isEmpty()) return listOf("說清楚想吃什麼、幾份。")
        val profile = snapshot.profile()
        val rules = snapshot.rules()
        val now = clock.millis()
        val day = snapshot.today(profile)
        val d = day.toString()
        val plan = snapshot.plan(day)
        val today = store.meals.onDay(d)
        val table = meals.table()
        val eaten = today.sumOf { it.kcal }
        val protein = today.sumOf { m -> m.items.sumOf { it.proteinG ?: 0.0 } }
        val minute = Links.minuteOf(day, now, clock.zone)
        val slot = MealSlots.upcoming(plan, text, minute, today)
        val cheat = slot != null && slot == plan.cheatSlot && RulesBook.cheatOpen(day)
        val zeroWeek = Breaches.zeroCalInWeek(store.meals.between(Breaches.weekStart(day).toString(), d), day)
        if (said.any { Breaches.isZeroCal(it.name, it.zeroCal) } && zeroWeek >= rules.zeroCalPerWeek) {
            return listOf("零卡飲料這週已經喝了 $zeroWeek 瓶，上限 ${rules.zeroCalPerWeek} 瓶，這週不能再喝。")
        }
        val budget = budget(plan, rules, slot, eaten, protein, minute, today)
        val banned = if (cheat) emptyList() else said.mapNotNull { s ->
            val fried = FoodTable.isFried(s.name, s.fried, FoodTable.lookup(table, s.name)) && ProcessedFood.ALLOWED.none { s.name.contains(it) }
            val cats = (ProcessedFood.categories(s.name) + if (fried) setOf(BannedCategory.FRIED) else emptySet()) intersect ProcessedFood.banned(rules)
            cats.takeIf { it.isNotEmpty() }?.let { s.name to it }
        }
        if (banned.isNotEmpty()) {
            val what = banned.joinToString("、") { (n, c) -> "${n}是${c.sortedBy { it.ordinal }.joinToString("、") { it.zh }}" }
            return listOf("$what，休假餐以外不准吃。" + instead(table, rules, budget, slot, plan))
        }
        var estimated = false
        val kcal = said.sumOf { s ->
            val e = FoodTable.lookup(table, s.name)
            when {
                e != null -> FoodTable.tableItem(e, s.name, s.portion, s.fried).kcal.also { if (e.estimated) estimated = true }
                Breaches.isZeroCal(s.name, s.zeroCal) && (s.kcalHigh ?: 0) <= MealDesk.ZERO_KCAL_MAX -> 0
                s.kcalHigh != null -> FoodTable.estimateKcal(s.kcalHigh, s.portion, rules.marginPct).also { estimated = true }
                else -> return listOf("估不出這是多少大卡。把菜單或包裝上的數字打給我，我幫你挑。")
            }
        }
        val est = if (estimated) "（估算）" else ""
        if (budget == null) {
            return listOf("今天已經吃了 $eaten 大卡，超過 ${rules.quotaKcal}，不加點。明天回到 ${rules.quotaKcal}，不准少吃來補。")
        }
        val what = slot?.let { plan.slot(it)?.label } ?: "這份加餐"
        if (kcal > budget.kcal) {
            val head = if (kcal > rules.mealCapKcal) "這份大約 $kcal 大卡$est，單餐上限 ${rules.mealCapKcal}，太多了。"
            else "這份大約 $kcal 大卡$est，${what}今天建議 ${budget.kcal} 大卡以內，太多了。"
            return listOf(head + instead(table, rules, budget, slot, plan))
        }
        return listOf("可以。這份 $kcal 大卡$est，吃了今天剩 ${rules.quotaKcal - eaten - kcal} 大卡。吃完拍照回報。")
    }

    /** The room for the meal he asks about: a planned meal's budget (its usual size, L5's when smaller); an extra gets what is left. */
    private fun budget(plan: DayPlan, rules: CompanyRules, slot: SlotKey?, eaten: Int, protein: Double, minute: Int, today: List<app.agency.core.model.Meal>): Budget? {
        val eatenSlots = today.mapNotNull { SlotKey.parse(it.slotKey) }.toSet()
        if (slot != null && slot !in eatenSlots && slot in SlotKey.MEALS) {
            val usual = MealBudget.usual(slot, plan)
            // A planned meal is eaten, whatever the day: never less than its own size when the day is over already.
            val b = plan.budgets[slot] ?: MealBudget.next(plan, rules, eaten, protein, eatenSlots, (plan.slot(slot)?.minute ?: minute) - 1)
                ?.takeIf { it.slot == slot }?.kcal ?: usual
            return Budget(minOf(b, rules.mealCapKcal))
        }
        val left = rules.quotaKcal - eaten
        return if (left <= 0) null else Budget(minOf(left, rules.mealCapKcal))
    }

    private data class Budget(val kcal: Int)

    private fun instead(table: List<FoodEntry>, rules: CompanyRules, budget: Budget?, slot: SlotKey?, plan: DayPlan): String {
        val b = budget?.kcal ?: return ""
        val pick = MealPicker.fits(table, rules, b).firstOrNull()
        val label = slot?.let { plan.slot(it)?.label } ?: "這一份"
        return if (pick != null) "${label}換成${MealPicker.line(pick)}。" else "${label}在 $b 大卡以內，先把菜單打給我，我幫你挑。"
    }
}
