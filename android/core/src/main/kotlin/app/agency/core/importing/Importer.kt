package app.agency.core.importing

import app.agency.core.model.ImportBatch
import app.agency.core.model.ImportTarget
import app.agency.core.model.KcalSource
import app.agency.core.model.LedgerBucket
import app.agency.core.model.LedgerEntry
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Memo
import app.agency.core.model.WeightLog
import app.agency.core.store.AgencyStore
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which column is what (T15c), picked by him in the mapper (code guesses from the headers first) and remembered per source.
 * [fun] are the categories he put in 娛樂費 (形象用品 and the like); every other category is 生活費.
 */
@Serializable
data class Mapping(
    val date: Int? = null,
    val amount: Int? = null,
    val item: Int? = null,
    val category: Int? = null,
    val kcal: Int? = null,
    val weight: Int? = null,
    val text: Int? = null,
    val funCategories: Set<String> = emptySet(),
) {
    fun json(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun of(json: String?): Mapping? = json?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** One row of the preview: what would be written, or why not. */
data class Planned(val line: Int, val status: Status, val day: String?, val amount: Int?, val item: String?, val category: String?, val kcal: Int?, val kg: Double?, val reason: String? = null) {
    enum class Status { NEW, DUPLICATE, BAD }
}

data class ImportPlan(val target: ImportTarget, val sourceKey: String, val mapping: Mapping, val rows: List<Planned>) {
    val fresh: List<Planned> get() = rows.filter { it.status == Planned.Status.NEW }
    val duplicates: Int get() = rows.count { it.status == Planned.Status.DUPLICATE }
    val bad: Int get() = rows.count { it.status == Planned.Status.BAD }

    /** The categories of the fresh rows (the bucket choice lists them). */
    val categories: List<String> get() = fresh.mapNotNull { it.category }.distinct()
}

/** The cells he gives, read by code: dates, amounts, kilograms. */
object Cells {
    private val ISO = Regex("^(20[0-9]{2})[-/.年]\\s*([0-9]{1,2})[-/.月]\\s*([0-9]{1,2})日?")
    private val ROC = Regex("^(1[0-9]{2})[-/.年]\\s*([0-9]{1,2})[-/.月]\\s*([0-9]{1,2})日?")
    private val MD = Regex("^([0-9]{1,2})[/月.]\\s*([0-9]{1,2})[日號]?")
    private val AMOUNT = Regex("(-?)\\s*(?:NT\\$|\\$|＄)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:元|塊|圓)?")

    /** A date cell: 2026-12-01, 2026/12/1, 2026年12月1日, 民國 115/12/1, 12/1 (this year, or last year when that is later than [today]), an Excel serial. */
    fun date(s: String, today: LocalDate): LocalDate? {
        val t = s.trim().removePrefix("民國").trim()
        if (t.isEmpty()) return null
        ISO.find(t)?.let { m -> return safe(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        ROC.find(t)?.let { m -> return safe(m.groupValues[1].toInt() + 1911, m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        MD.find(t)?.let { m ->
            val d = safe(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()) ?: return null
            return if (d.isAfter(today)) d.minusYears(1) else d
        }
        t.toDoubleOrNull()?.takeIf { it in 30000.0..60000.0 }?.let { return LocalDate.of(1899, 12, 30).plusDays(it.toLong()) }
        return null
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** A money cell in whole NT dollars (spending as a positive number; 「-120」 is spending too). */
    fun amount(s: String): Int? {
        val m = AMOUNT.find(s.trim()) ?: return null
        val v = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        return Math.round(v).toInt().takeIf { it in 1..200_000 }
    }

    fun kg(s: String): Double? = Regex("([0-9]{2,3}(?:\\.[0-9]+)?)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 30.0..250.0 }

    fun kcal(s: String): Int? = Regex("([0-9]{1,4})").find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..5000 }

    fun norm(s: String): String = s.lowercase().filter { !it.isWhitespace() && it !in "，,。.、" }
}

/**
 * The mapper and the import (T15c): code guesses the columns from the headers, he fixes them, [plan] shows what would be
 * written (new rows, duplicates of what is stored or of earlier rows, rows that cannot be read) and nothing is saved until
 * [commit] (his 「匯入」). [undo] takes the whole import back.
 */
class Importer(private val store: AgencyStore, private val zone: ZoneId, private val newId: () -> String) {
    companion object {
        private val DATE_H = Regex("日期|時間|date|day|日子", RegexOption.IGNORE_CASE)
        private val AMOUNT_H = Regex("金額|價格|價錢|花費|支出|費用|amount|price|cost|元", RegexOption.IGNORE_CASE)
        private val ITEM_H = Regex("項目|品項|名稱|內容|說明|食物|餐點|吃了|item|name|description|note|memo|備忘", RegexOption.IGNORE_CASE)
        private val CATEGORY_H = Regex("分類|類別|種類|category|type", RegexOption.IGNORE_CASE)
        private val KCAL_H = Regex("大卡|熱量|卡路里|kcal|calorie", RegexOption.IGNORE_CASE)
        private val WEIGHT_H = Regex("體重|公斤|kg|weight", RegexOption.IGNORE_CASE)
        private val FUN = Regex("娛樂|形象|治裝|服裝|衣服|保養|化妝|美妝|髮|遊戲|電影|演唱會|追星|周邊|小卡")
        const val PASTE = "貼上"
        const val CLAUDE = "對話匯出"

        /** Code's guess of the columns from the header row (and the first rows when a header says nothing). */
        fun guess(t: Table): Mapping {
            fun find(re: Regex, not: Set<Int> = emptySet()) = t.headers.indices.firstOrNull { it !in not && re.containsMatchIn(t.headers[it]) }
            val date = find(DATE_H) ?: t.headers.indices.firstOrNull { c -> t.rows.take(5).count { Cells.date(it[c], LocalDate.now()) != null } >= minOf(3, t.rows.size).coerceAtLeast(1) }
            val amount = find(AMOUNT_H, setOfNotNull(date))
            val kcal = find(KCAL_H, setOfNotNull(date, amount))
            val weight = find(WEIGHT_H, setOfNotNull(date, amount, kcal))
            val category = find(CATEGORY_H, setOfNotNull(date, amount, kcal, weight))
            val item = find(ITEM_H, setOfNotNull(date, amount, kcal, weight, category))
                ?: t.headers.indices.firstOrNull { it !in setOfNotNull(date, amount, kcal, weight, category) }
            val cats = category?.let { c -> t.rows.map { it[c].trim() }.filter { FUN.containsMatchIn(it) }.toSet() }.orEmpty()
            return Mapping(date, amount, item, category, kcal, weight, text = item, funCategories = cats)
        }
    }

    /** The mapping he used last time for [sourceKey], if any. */
    suspend fun remembered(sourceKey: String, target: ImportTarget): Mapping? =
        store.imports.all().firstOrNull { it.sourceKey == sourceKey && it.target == target }?.let { Mapping.of(it.mappingJson) }

    suspend fun plan(target: ImportTarget, table: Table, mapping: Mapping, sourceKey: String, today: LocalDate): ImportPlan {
        val seen = mutableSetOf<String>()
        val stored: Set<String> = when (target) {
            ImportTarget.LEDGER -> store.ledger.between("0000-01-01", "9999-12-31").map { key(it.day, it.amount, it.item) }.toSet()
            ImportTarget.MEALS -> store.meals.between("0000-01-01", "9999-12-31").map { "${it.day}|${Cells.norm(it.text)}" }.toSet()
            ImportTarget.WEIGHTS -> store.weights.between("0000-01-01", "9999-12-31").map { "${it.day}|${it.kg}" }.toSet()
            ImportTarget.MEMOS -> store.memos.all(deleted = false).map { Cells.norm(it.text) }.toSet()
        }
        val rows = table.rows.mapIndexed { i, r ->
            val line = i + 2
            val day = table.cell(r, mapping.date).let { if (it.isEmpty()) null else Cells.date(it, today) }
            val dateText = table.cell(r, mapping.date)
            fun bad(why: String) = Planned(line, Planned.Status.BAD, day?.toString(), null, null, null, null, null, why)
            if (dateText.isNotEmpty() && day == null) return@mapIndexed bad("日期看不懂")
            if (day != null && day.isAfter(today)) {
                if (target != ImportTarget.MEMOS) return@mapIndexed bad("日期在未來")
            }
            when (target) {
                ImportTarget.LEDGER -> {
                    val amount = Cells.amount(table.cell(r, mapping.amount)) ?: return@mapIndexed bad("金額看不懂")
                    val item = table.cell(r, mapping.item).take(60).ifEmpty { return@mapIndexed bad("沒有項目") }
                    val d = day ?: return@mapIndexed bad("沒有日期")
                    val cat = table.cell(r, mapping.category).take(20).ifEmpty { null }
                    val k = key(d.toString(), amount, item)
                    Planned(line, if (k in stored || !seen.add(k)) Planned.Status.DUPLICATE else Planned.Status.NEW, d.toString(), amount, item, cat, null, null)
                }
                ImportTarget.MEALS -> {
                    val item = table.cell(r, mapping.item).take(60).ifEmpty { return@mapIndexed bad("沒有吃了什麼") }
                    val d = day ?: return@mapIndexed bad("沒有日期")
                    val kcal = table.cell(r, mapping.kcal).let { if (it.isEmpty()) null else Cells.kcal(it) ?: return@mapIndexed bad("熱量看不懂") }
                    val k = "$d|${Cells.norm(item)}"
                    Planned(line, if (k in stored || !seen.add(k)) Planned.Status.DUPLICATE else Planned.Status.NEW, d.toString(), null, item, null, kcal, null)
                }
                ImportTarget.WEIGHTS -> {
                    val kg = Cells.kg(table.cell(r, mapping.weight ?: mapping.amount ?: mapping.item)) ?: return@mapIndexed bad("體重看不懂")
                    val d = day ?: return@mapIndexed bad("沒有日期")
                    val k = "$d|$kg"
                    Planned(line, if (k in stored || !seen.add(k)) Planned.Status.DUPLICATE else Planned.Status.NEW, d.toString(), null, null, null, null, kg)
                }
                ImportTarget.MEMOS -> {
                    val text = table.cell(r, mapping.text ?: mapping.item).take(200).ifEmpty { return@mapIndexed bad("沒有內容") }
                    val k = Cells.norm(text)
                    Planned(line, if (k in stored || !seen.add(k)) Planned.Status.DUPLICATE else Planned.Status.NEW, day?.toString(), null, text, null, null, null)
                }
            }
        }
        return ImportPlan(target, sourceKey, mapping, rows)
    }

    private fun key(day: String, amount: Int, item: String) = "$day|$amount|${Cells.norm(item)}"

    /** 匯入: the fresh rows of [plan] in one transaction, tied to one batch (its ids kept for 復原). */
    suspend fun commit(plan: ImportPlan, at: Long): ImportBatch {
        val batchId = newId()
        val ids = mutableListOf<String>()
        store.transaction {
            plan.fresh.forEach { p ->
                val id = newId()
                val dayAt = { d: String -> LocalDate.parse(d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }
                when (plan.target) {
                    ImportTarget.LEDGER -> store.ledger.insert(
                        LedgerEntry(id, p.day!!, dayAt(p.day), p.amount!!, p.item!!, p.category, if (p.category != null && p.category in plan.mapping.funCategories) LedgerBucket.FUN else LedgerBucket.LIFE, LedgerEntry.SOURCE_IMPORT, batchId),
                    )
                    ImportTarget.MEALS -> store.meals.insert(
                        Meal(id, p.day!!, dayAt(p.day), null, p.item!!, items = p.kcal?.let { listOf(MealItem(p.item, 1.0, it, null, null, null, fried = false, source = KcalSource.ESTIMATE)) }.orEmpty()),
                    )
                    ImportTarget.WEIGHTS -> store.weights.insert(WeightLog(id, p.day!!, dayAt(p.day), p.kg!!))
                    ImportTarget.MEMOS -> {
                        val due = p.day?.let { LocalDate.parse(it).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }?.takeIf { it > at }
                        store.memos.upsert(Memo(id, at, p.item!!, dueAt = due, batchId = batchId))
                    }
                }
                ids += id
            }
            store.imports.upsert(ImportBatch(batchId, at, plan.target, plan.sourceKey, plan.mapping.json(), ids, plan.duplicates))
        }
        return store.imports.get(batchId)!!
    }

    /** 復原 of a whole import: every row it wrote goes; the batch stays, marked undone. False when already undone. */
    suspend fun undo(batchId: String, at: Long): Boolean {
        val b = store.imports.get(batchId)?.takeIf { it.undoneAt == null } ?: return false
        store.transaction {
            b.ids.forEach { id ->
                when (b.target) {
                    ImportTarget.LEDGER -> store.ledger.delete(id)
                    ImportTarget.MEALS -> store.meals.delete(id)
                    ImportTarget.WEIGHTS -> store.weights.delete(id)
                    ImportTarget.MEMOS -> store.memos.remove(id)
                }
            }
            store.imports.upsert(b.copy(undoneAt = at))
        }
        return true
    }
}
