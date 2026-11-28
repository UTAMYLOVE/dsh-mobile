package app.agency.core.day

import app.agency.core.rules.Food
import app.agency.core.rules.Rules
import app.agency.core.store.FoodSource
import app.agency.core.store.MealItem

/**
 * Turns what he ate into numbers the code trusts: his own table first, then a label he confirmed, then a strict estimate.
 * Strict on purpose: a model's guess is raised so that a wrong guess never lets him eat more than planned.
 */
object Nutrition {
    private fun norm(s: String): String = s.filterNot { it.isWhitespace() }.lowercase()

    /** The row of his table [name] refers to (name or alias contained either way), the closest match first. */
    fun tableFood(rules: Rules, name: String): Food? {
        val n = norm(name)
        if (n.isEmpty()) return null
        var best: Food? = null
        var bestScore = 0
        for (food in rules.foods) {
            for (term in listOf(food.name) + food.aliases) {
                val t = norm(term)
                if (t.isEmpty()) continue
                if (n.contains(t) || t.contains(n)) {
                    val score = if (n == t) Int.MAX_VALUE else t.length
                    if (score > bestScore) {
                        best = food
                        bestScore = score
                    }
                }
            }
        }
        return best
    }

    /** The first banned category one of whose words is in [name], unless [name] is a table food or an allowed entry. */
    fun banned(rules: Rules, name: String): String? {
        val n = norm(name)
        if (n.isEmpty()) return null
        if (tableFood(rules, name) != null) return null
        if (rules.diet.allowed.any { norm(it).let { a -> a.isNotEmpty() && (n.contains(a) || a.contains(n)) } }) return null
        return rules.diet.banned.firstOrNull { cat -> cat.words.any { w -> norm(w).let { it.isNotEmpty() && n.contains(it) } } }?.category
    }

    /**
     * [kcalGuess] / [proteinGuess] are what a model or he said. [source] says where they came from: [FoodSource.LABEL] numbers are
     * kept as given; anything else is looked up in his table, else treated as an estimate (kcal raised by the margin and rounded up
     * to the next ten, protein rounded down).
     */
    fun resolve(rules: Rules, name: String, kcalGuess: Int, proteinGuess: Double, source: FoodSource = FoodSource.ESTIMATE): MealItem {
        val food = tableFood(rules, name)
        if (food != null) return MealItem(name, food.kcal, food.proteinG, FoodSource.TABLE, null)
        val banned = banned(rules, name)
        if (source == FoodSource.LABEL) {
            return MealItem(name, kcalGuess.coerceAtLeast(0), proteinGuess.coerceAtLeast(0.0), FoodSource.LABEL, banned)
        }
        val margin = rules.diet.estimateMarginPct.coerceAtLeast(0)
        val kcal = (kcalGuess.coerceAtLeast(0).toLong() * (100 + margin) + 999) / 1000 * 10
        val protein = Math.floor(proteinGuess.coerceAtLeast(0.0))
        return MealItem(name, kcal.toInt(), protein, FoodSource.ESTIMATE, banned)
    }
}
