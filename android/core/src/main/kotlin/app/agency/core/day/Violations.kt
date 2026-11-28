package app.agency.core.day

import app.agency.core.base.Days
import app.agency.core.rules.Rules
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.Drink
import app.agency.core.store.DrinkType
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealSlot
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Store
import app.agency.core.store.Violation
import app.agency.core.store.add
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The rules a trainee can break. [label] is what he reads. Stored by name in [Violation.code]. */
enum class ViolationCode(val label: String) {
    BANNED_FOOD("吃了禁止的食物"),
    MEAL_OVER_CAP("單餐超過上限"),
    OVER_KCAL("今天熱量超標"),
    UNDER_MIN("今天吃太少"),
    MISSED_NIGHT_MEAL("跑單後沒有吃宵夜"),
    LATE_CHECK_OUT("簽退太晚"),
    CAFFEINE_LATE("太晚喝咖啡因"),
    ZERO_CAL_OVER("無熱量飲料超過本週額度"),

    /** Never written: a second rest day in a week is refused by the tool instead. */
    REST_DAY_OVER("本週已休息過"),
}

/**
 * Broken rules, found and written by code only (小葵 can neither add nor remove one). Both entry points are idempotent: running
 * them again on the same entries writes nothing new. A rule counts only when its feature is open and its values are set.
 */
object Violations {
    /** Checks that can be decided the moment something is written (a meal, a drink, a check-out). Returns what it newly wrote. */
    fun onWrite(store: Store, rules: Rules, stage: StageState, day: LocalDate, now: Instant, zone: ZoneId): List<Entry> =
        store.tx { Run(store, rules, stage, day, now, zone).also { it.writeChecks() }.written }

    /**
     * After an entry of [day] was corrected or removed: write-time violations whose condition no longer holds are removed, new
     * ones are written. Day-close violations (too little eaten, no night meal, never signed out) are left alone. Returns what
     * it newly wrote.
     */
    fun resync(store: Store, rules: Rules, stage: StageState, day: LocalDate, now: Instant, zone: ZoneId): List<Entry> =
        store.tx {
            val wanted = Run(store, rules, stage, day, now, zone, dry = true).also { it.writeChecks() }.wanted
            for (e in store.entries(day, Kind.VIOLATION)) {
                val v = runCatching { e.read<Violation>() }.getOrNull() ?: continue
                if (!writeTime(v)) continue
                if (wanted.none { it(v) }) store.remove(e.id)
            }
            Run(store, rules, stage, day, now, zone).also { it.writeChecks() }.written
        }

    /** Violations decided by [onWrite] (the late-check-out one only in its "after bedtime" form). */
    private fun writeTime(v: Violation): Boolean = when (v.code) {
        ViolationCode.BANNED_FOOD.name, ViolationCode.MEAL_OVER_CAP.name, ViolationCode.OVER_KCAL.name,
        ViolationCode.CAFFEINE_LATE.name, ViolationCode.ZERO_CAL_OVER.name -> true
        ViolationCode.LATE_CHECK_OUT.name -> v.note.startsWith(LATE_OUT_NOTE)
        else -> false
    }

    private const val LATE_OUT_NOTE = "簽退晚於"

    /** The same checks plus the ones that need the whole day (too little eaten, no night meal, never signed out). */
    fun onClose(store: Store, rules: Rules, stage: StageState, day: LocalDate, now: Instant, zone: ZoneId): List<Entry> =
        store.tx { Run(store, rules, stage, day, now, zone).also { it.writeChecks(); it.closeChecks() }.written }

    private class Run(
        val store: Store,
        val rules: Rules,
        val stage: StageState,
        val day: LocalDate,
        val now: Instant,
        val zone: ZoneId,
        /** Only collect what should exist ([wanted]); write nothing. */
        val dry: Boolean = false,
    ) {
        val written = ArrayList<Entry>()
        val wanted = ArrayList<(Violation) -> Boolean>()
        private val existing = store.entries(day, Kind.VIOLATION).mapNotNull { runCatching { it.read<Violation>() }.getOrNull() }.toMutableList()
        private val diet = rules.diet
        private fun open(f: Feature) = f.openAt(stage.stage)

        private fun emit(code: ViolationCode, note: String, same: (Violation) -> Boolean = { it.code == code.name }) {
            wanted += same
            if (dry || existing.any(same)) return
            val v = Violation(code.name, note)
            existing += v
            written += store.add(day, now, Kind.VIOLATION, v)
        }

        /** The day's own meals (an imported one is history only and never judged). */
        private fun meals(): List<Pair<Entry, Meal>> =
            store.entries(day, Kind.MEAL).mapNotNull { e -> runCatching { e.read<Meal>() }.getOrNull()?.let { e to it } }
                .filter { it.second.batch == null }

        fun writeChecks() {
            if (open(Feature.MEALS)) {
                val meals = meals()
                for ((e, m) in meals) {
                    if (m.free) continue
                    val bad = m.items.firstOrNull { it.banned != null || Nutrition.banned(rules, it.name) != null }
                    if (bad != null) {
                        val cat = bad.banned ?: Nutrition.banned(rules, bad.name).orEmpty()
                        emit(ViolationCode.BANNED_FOOD, "餐點#${e.id} 含禁止食物：${bad.name}（$cat）") {
                            it.code == ViolationCode.BANNED_FOOD.name && it.note.startsWith("餐點#${e.id} ")
                        }
                    }
                }
                // The free meal is in the day's kcal he sees, but it is never over the cap or over the target.
                val kcals = meals.filter { !it.second.free }.map { (_, m) -> m.items.sumOf { it.kcal } }
                if (diet.mealCapKcal > 0 && kcals.any { it > diet.mealCapKcal }) {
                    emit(ViolationCode.MEAL_OVER_CAP, "單餐 ${kcals.max()} 大卡，上限 ${diet.mealCapKcal} 大卡")
                }
                if (diet.kcal > 0 && kcals.sum() > diet.kcal) {
                    emit(ViolationCode.OVER_KCAL, "今天共 ${kcals.sum()} 大卡，上限 ${diet.kcal} 大卡")
                }
                drinkChecks()
            }
            if (open(Feature.CHECK_IN)) {
                val bedtime = Bedtime.of(rules, stage, day)
                val out = store.entries(day, Kind.CHECK_OUT).lastOrNull()
                if (bedtime != null && out != null && out.at.isAfter(Days.at(day, bedtime, zone))) {
                    emit(ViolationCode.LATE_CHECK_OUT, "${LATE_OUT_NOTE}睡覺時間 ${bedtime.hour}:${bedtime.minute.toString().padStart(2, '0')}")
                }
            }
        }

        private fun drinkChecks() {
            val drinks = store.entries(day, Kind.DRINK).mapNotNull { e -> runCatching { e.read<Drink>() }.getOrNull()?.let { e to it.type } }
            val cutoff = Days.parse(diet.caffeineCutoff)
            if (cutoff != null) {
                val limit = Days.at(day, cutoff, zone)
                if (drinks.any { (e, t) -> t == DrinkType.CAFFEINE && e.at.isAfter(limit) }) {
                    emit(ViolationCode.CAFFEINE_LATE, "咖啡因飲料喝在 ${diet.caffeineCutoff} 之後")
                }
            }
            val allowed = diet.zeroCalDrinksPerWeek
            if (allowed > 0 && drinks.any { it.second == DrinkType.ZERO_CAL }) {
                val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
                val week = store.entries(monday, day, Kind.DRINK).count {
                    runCatching { it.read<Drink>().type }.getOrNull() == DrinkType.ZERO_CAL
                }
                if (week > allowed) emit(ViolationCode.ZERO_CAL_OVER, "本週無熱量飲料 $week 次，額度 $allowed 次")
            }
        }

        fun closeChecks() {
            if (open(Feature.MEALS)) {
                val meals = meals()
                val total = meals.sumOf { (_, m) -> m.items.sumOf { it.kcal } }
                if (meals.isNotEmpty() && diet.minKcal > 0 && total < diet.minKcal) {
                    emit(ViolationCode.UNDER_MIN, "今天只吃 $total 大卡，最低 ${diet.minKcal} 大卡")
                }
                if (open(Feature.RIDE)) {
                    val rode = store.entries(day, Kind.RIDE).any { runCatching { it.read<Ride>().phase }.getOrNull() == RidePhase.OUT }
                    if (rode && meals.none { (_, m) -> m.slot == MealSlot.NIGHT }) {
                        emit(ViolationCode.MISSED_NIGHT_MEAL, "跑單的日子沒有吃宵夜")
                    }
                }
            }
            if (open(Feature.CHECK_IN) && store.entries(day, Kind.CHECK_IN).isNotEmpty() &&
                store.entries(day, Kind.CHECK_OUT).isEmpty()
            ) {
                emit(ViolationCode.LATE_CHECK_OUT, "到一天結束都沒有簽退")
            }
        }
    }
}
