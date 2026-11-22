package app.agency.core.rules

import app.agency.core.model.CompanyRules
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

/**
 * What a meal can break (公司規章, plan v3 §5–§6), stored on the meal by name and never recomputed:
 * - [PROCESSED]: food of the banned list (fried food included) outside a declared 休假餐;
 * - [MEAL_CAP]: one sitting above the single-meal cap (800: a ceiling, not a target);
 * - [ZERO_CAL]: a zero-calorie drink above the weekly limit (3).
 */
enum class Breach(val zh: String) {
    PROCESSED("休假餐以外吃了禁止的東西"),
    MEAL_CAP("單餐超過上限"),
    ZERO_CAL("零卡飲料超過一週的額度"),
    ;

    companion object {
        fun parse(s: String): Breach? = entries.firstOrNull { it.name == s }

        /** How a stored label reads (imported ones included: 「FRIED」, 「ZEROCAL」, the old 700 cap). */
        fun zh(label: String): String = parse(label)?.zh ?: when (label) {
            "FRIED" -> "炸物"
            "ZEROCAL" -> ZERO_CAL.zh
            else -> MEAL_CAP.zh
        }
    }
}

/** One meal's check: the breaches and what they are about (the lines say it). */
data class BreachCheck(
    val breaches: List<Breach>,
    /** Item name → the banned categories it falls in (only when [Breach.PROCESSED]). */
    val banned: List<Pair<String, Set<BannedCategory>>> = emptyList(),
    /** The sitting's kcal with this meal (parts reported within [Breaches.SITTING_MINUTES] count as one meal). */
    val sittingKcal: Int = 0,
    /** Zero-calorie drinks of the week with this meal. */
    val zeroAfter: Int = 0,
)

/**
 * The meal rules of arch 1.5 (ported from the coach, with plan v3's numbers), pure. The 21:00 portion on the run is never a
 * single-meal breach (riding safety: he eats it at the time, whatever else he ate); a declared 休假餐 may hold banned food and
 * still counts in the day and under the single-meal cap (L7; plan v3 「單餐上限照算」).
 */
object Breaches {
    const val SITTING_MINUTES = 45L

    private val ZERO_WORDS = listOf("零卡", "零熱量", "0卡", "0 卡", "零糖", "零度", "zero", "Zero", "ZERO")

    /** A zero-calorie drink: the clerk says so, or its name does. 「無糖」 alone is not one (無糖豆漿 has calories). */
    fun isZeroCal(name: String, flagged: Boolean): Boolean = flagged || ZERO_WORDS.any { name.contains(it) }

    /** The banned categories of [item] under [rules] (none inside a declared 休假餐). Fried counts even when only the clerk saw it. */
    fun categories(item: MealItem, rules: CompanyRules, cheat: Boolean): Set<BannedCategory> {
        if (cheat || ProcessedFood.ALLOWED.any { item.name.contains(it) }) return emptySet()
        val c = ProcessedFood.categories(item.name).toMutableSet()
        if (item.fried) c += BannedCategory.FRIED
        return c intersect ProcessedFood.banned(rules)
    }

    /** Zero-calorie drinks (portions rounded up) of the Monday–Sunday week of [day] among [meals]. */
    fun zeroCalInWeek(meals: List<Meal>, day: LocalDate): Int {
        val start = weekStart(day)
        val end = start.plusDays(6)
        return meals.filter { m -> runCatching { LocalDate.parse(m.day) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true }
            .flatMap { it.items }.filter { it.zeroCal }.sumOf { ceil(it.portion - 1e-9).toInt() }
    }

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Checks one meal of [items] at [at]: [sitting] are today's other meals (the cap sums those of the last
     * [SITTING_MINUTES]); [zeroBefore] the week's zero-calorie drinks before it; [onRun] the 21:00 portion; [cheat] a declared
     * 休假餐.
     */
    fun check(
        items: List<MealItem>,
        rules: CompanyRules,
        at: Long,
        sitting: List<Meal>,
        zeroBefore: Int,
        cheat: Boolean,
        onRun: Boolean,
    ): BreachCheck {
        val banned = items.map { it.name to categories(it, rules, cheat) }.filter { it.second.isNotEmpty() }
        val together = sitting.filter { at - it.at in 0..SITTING_MINUTES * 60_000 }
        val sittingKcal = together.sumOf { it.kcal } + items.sumOf { it.kcal }
        // A sitting already flagged is not flagged twice.
        val capFlagged = together.any { m -> m.breaches.any { it == Breach.MEAL_CAP.name } }
        val zeroAfter = zeroBefore + items.filter { it.zeroCal }.sumOf { ceil(it.portion - 1e-9).toInt() }
        val out = buildList {
            if (banned.isNotEmpty()) add(Breach.PROCESSED)
            if (!onRun && !capFlagged && sittingKcal > rules.mealCapKcal) add(Breach.MEAL_CAP)
            if (zeroAfter > rules.zeroCalPerWeek && zeroAfter > zeroBefore) add(Breach.ZERO_CAL)
        }
        return BreachCheck(out, banned, sittingKcal, zeroAfter)
    }

    /** The body teacher's line for [b]: what he did, by the rule; never 「少吃補回來」. */
    fun line(b: Breach, c: BreachCheck, rules: CompanyRules): String = when (b) {
        Breach.PROCESSED -> c.banned.joinToString("、") { (name, cats) -> "${name}是${cats.sortedBy { it.ordinal }.joinToString("、") { it.zh }}" } +
            "，休假餐以外不准吃。"
        Breach.MEAL_CAP -> "這一餐一共 ${c.sittingKcal} 大卡，超過單餐上限 ${rules.mealCapKcal}。一次不要吃到撐。"
        Breach.ZERO_CAL -> "零卡飲料這週第 ${c.zeroAfter} 瓶，一週最多 ${rules.zeroCalPerWeek} 瓶。"
    }
}
