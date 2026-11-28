package app.agency.core.day

import app.agency.core.rules.Rules
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.Store
import java.time.LocalDate

/**
 * The weekly free meal (`diet.freeMealFrom`, `diet.freeMealsPerWeek`). He asks for it; code decides whether it is allowed: from
 * the start date on, at most the quota per calendar week (Monday to Sunday). A free meal still counts in the day's kcal he
 * sees but is never a banned-food, meal-cap or over-target violation.
 */
object FreeMeals {
    sealed interface Status {
        /** Not set up in the rules (no start date or no quota). */
        data object Off : Status

        data class NotYet(val from: LocalDate) : Status

        data class UsedUp(val perWeek: Int) : Status

        data class Available(val left: Int, val perWeek: Int) : Status
    }

    fun status(store: Store, rules: Rules, day: LocalDate): Status {
        val per = rules.diet.freeMealsPerWeek
        val from = runCatching { LocalDate.parse(rules.diet.freeMealFrom) }.getOrNull()
        if (per <= 0 || from == null) return Status.Off
        if (day.isBefore(from)) return Status.NotYet(from)
        val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
        val used = store.entries(monday, monday.plusDays(6), Kind.MEAL).count { e ->
            runCatching { e.read<Meal>().free }.getOrDefault(false)
        }
        return if (used >= per) Status.UsedUp(per) else Status.Available(per - used, per)
    }

    /** Why a free meal cannot be used today, in words for 小葵, or null when it can. */
    fun refusal(s: Status): String? = when (s) {
        Status.Off -> "規章沒有自由餐，這餐照一般的餐記（free_meal 傳 false）。"
        is Status.NotYet -> "自由餐 ${s.from} 才開始，這餐照一般的餐記（free_meal 傳 false）。"
        is Status.UsedUp -> "這週的自由餐（每週 ${s.perWeek} 次）已經用掉了，這餐照一般的餐記（free_meal 傳 false）。"
        is Status.Available -> null
    }

    /** One line for the context block, or null when free meals are not set up. */
    fun line(s: Status): String? = when (s) {
        Status.Off -> null
        is Status.NotYet -> "自由餐：${s.from} 才開始，現在還不能用"
        is Status.UsedUp -> "自由餐：這週已經用掉了（每週 ${s.perWeek} 次）"
        is Status.Available -> "自由餐：這週還可以用 ${s.left} 次（他說這餐要當自由餐才算，log_meal 的 free_meal 傳 true）"
    }
}
