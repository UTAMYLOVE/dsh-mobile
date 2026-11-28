package app.agency.core.agent.tools

import app.agency.core.store.Bucket
import app.agency.core.store.Kind
import app.agency.core.store.Money
import app.agency.core.store.Store
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Totals per bucket for one period. */
data class MoneyTotals(val living: Int, val fun_: Int, val income: Int) {
    val spent: Int get() = living + fun_
}

/** One money entry as the money screen lists it. */
data class MoneyRow(val entryId: Long, val day: LocalDate, val amount: Int, val bucket: Bucket, val note: String)

/** What the money screen and the `log_money` facts show: this calendar week, this month, and the month's entries. */
data class MoneyView(val week: MoneyTotals, val month: MoneyTotals, val rows: List<MoneyRow>, val livingPct: Int, val funPct: Int)

/** Money sums by code, Monday-to-Sunday weeks and calendar months. */
object MoneyMath {
    fun of(store: Store, day: LocalDate, livingPct: Int = 0, funPct: Int = 0): MoneyView {
        val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
        val first = day.withDayOfMonth(1)
        val from = if (monday.isBefore(first)) monday else first
        val rows = store.entries(from, day, Kind.MONEY).mapNotNull { e ->
            runCatching { e.read<Money>() }.getOrNull()?.let { MoneyRow(e.id, e.day, it.amount, it.bucket, it.note) }
        }
        fun totals(list: List<MoneyRow>) = MoneyTotals(
            list.filter { it.bucket == Bucket.LIVING }.sumOf { it.amount },
            list.filter { it.bucket == Bucket.FUN }.sumOf { it.amount },
            list.filter { it.bucket == Bucket.INCOME }.sumOf { it.amount },
        )
        return MoneyView(
            week = totals(rows.filter { !it.day.isBefore(monday) }),
            month = totals(rows.filter { !it.day.isBefore(first) }),
            rows = rows.filter { !it.day.isBefore(first) },
            livingPct = livingPct,
            funPct = funPct,
        )
    }

    fun word(b: Bucket): String = when (b) {
        Bucket.LIVING -> "生活"
        Bucket.FUN -> "娛樂"
        Bucket.INCOME -> "收入"
    }

    internal fun json(t: MoneyTotals): JsonObject = F.obj {
        put("living", t.living)
        put("fun", t.fun_)
        put("income", t.income)
        put("spent", t.spent)
    }
}
