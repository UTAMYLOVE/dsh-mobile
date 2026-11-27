package app.agency.core.brain.handlers

import app.agency.core.model.DeliveryIncome
import app.agency.core.store.AgencyStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

/** His words for a day's delivery income and for the weekly target (code, no model). */
object IncomeWords {
    private val REPORT = Regex("^(?:今天)?(?:外送|跑單|跑外送)(?:收入|賺了|賺|跑了)?\\s*([0-9]{1,6})\\s*(?:元|塊)?$|^(?:今天)?(?:收入|賺了)\\s*([0-9]{1,6})\\s*(?:元|塊)$")
    private val TARGET = Regex("^外送(?:週|周|每週|每周)目標\\s*([0-9]{1,6})\\s*(?:元)?$")

    fun report(text: String): Int? = REPORT.find(text.trim())?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

    fun target(text: String): Int? = TARGET.find(text.trim())?.groupValues?.get(1)?.toIntOrNull()
}

/** One week of delivery income (Mon–Sun): what is in, the target and the days that count toward it. */
data class IncomeWeek(val monday: LocalDate, val total: Int, val target: Int?, val days: Int) {
    val left: Int? get() = target?.let { (it - total).coerceAtLeast(0) }
}

/**
 * 外送收入 (owner 2026-11-26): a day's amount (a second report of the day replaces it) and the week's progress against the weekly
 * target, which is his own number (set by 「外送週目標 N」; the code ships none). The week's total counts, not one day; a short
 * day is made up on the weekend, never with less sleep: the latest sign-out stays.
 */
class IncomeDesk(private val store: AgencyStore) {
    suspend fun week(day: LocalDate, target: Int?, deliveryDays: Int): IncomeWeek {
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val rows = store.income.between(monday.toString(), monday.plusDays(6).toString())
        return IncomeWeek(monday, rows.sumOf { it.amount }, target, deliveryDays)
    }

    suspend fun record(day: LocalDate, amount: Int, at: Long, target: Int?, deliveryDays: Int, daysSoFar: Int): String {
        store.income.put(DeliveryIncome(day.toString(), amount, at))
        val w = week(day, target, deliveryDays)
        val head = "記下：今天外送 $amount 元。這週到現在 ${w.total} 元"
        if (target == null) return "$head。外送週目標還沒設，打「外送週目標 數字」設一次就好。"
        val left = w.left ?: 0
        if (left == 0) return "$head，目標 $target 元，這週達到了。照樣準時收工、準時簽退。"
        val pace = ceil(target.toDouble() / deliveryDays).toInt() * daysSoFar.coerceAtMost(deliveryDays)
        val behind = w.total < pace
        return "$head，目標 $target 元，還差 $left 元。" + if (behind) SLEEP_FIRST else ""
    }

    companion object {
        const val SLEEP_FIRST = "單少的話，週六、週日多跑一點補回來；收工和簽退時間照舊，睡眠不能因為補單變少。"
        const val MAX_AMOUNT = 99_999
    }
}
