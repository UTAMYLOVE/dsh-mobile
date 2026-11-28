package app.agency.core.importing

import app.agency.core.agent.Card
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.day.Nutrition
import app.agency.core.rules.Rules
import app.agency.core.store.Body
import app.agency.core.store.Bucket
import app.agency.core.store.FoodSource
import app.agency.core.base.J
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealItem
import app.agency.core.store.MealSlot
import app.agency.core.store.Memo
import app.agency.core.store.Money
import app.agency.core.store.ProposalRec
import app.agency.core.store.Store
import app.agency.core.store.add
import app.agency.core.store.update
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** What a file he attached or pasted can be imported as. [word] is his word for it. */
enum class ImportKind(val wire: String, val word: String) {
    MONEY("money", "記帳"),
    MEAL("meal", "飲食"),
    BODY("body", "體重"),
    MEMO("memo", "備忘"),
    ;

    companion object {
        fun of(s: String): ImportKind? = values().firstOrNull { it.wire.equals(s.trim(), ignoreCase = true) }
    }
}

/** One entry to write on yes: on logical [date] (ISO), of entry [kind], with its ready payload [data]. */
@Serializable
data class Staged(val date: String, val kind: String, val data: JsonObject)

/**
 * The pending batch, kept in the import proposal's `payload` until he answers. After yes, [ids] are the entries written (the
 * batch → entry ids mapping [undo] removes); [undone] once they were removed.
 */
@Serializable
data class ImportBatch(
    val kind: String,
    val entries: List<Staged>,
    val ids: List<Long> = emptyList(),
    val undone: Boolean = false,
    /** The pending 「復原」 proposal of this batch (0 = none). */
    val undoId: Long = 0,
)

/** A row that was not taken: its 1-based number in what he gave, and why (his language). */
data class Rejected(val row: Int, val why: String)

/** The checked preview: the stored proposal [entryId], what will be written, what was refused. */
data class Preview(
    val entryId: Long,
    val kind: ImportKind,
    val rows: Int,
    val accepted: Int,
    val from: LocalDate?,
    val to: LocalDate?,
    val rejected: List<Rejected>,
    val card: Card.Proposal,
)

/** What a yes wrote: [count] entries of [kind] from [from] to [to], and the 「復原」 card for the confirmation line. */
data class Applied(val count: Int, val kind: ImportKind, val from: LocalDate?, val to: LocalDate?, val line: String, val undoCard: Card.Proposal)

/**
 * Importing his old records from a file (bookkeeping, meals, weights, memos). The model only turns the file into rows; code
 * checks every row, keeps the batch as a pending [Kind.PROPOSAL] of kind [KIND] and writes nothing until he says yes. On yes
 * each entry goes on its own logical day, tagged with the batch (the proposal's id), so it is history only: never a violation,
 * never counted as done for any day or round. [undo] removes exactly those entries.
 */
object Importer {
    const val KIND = "import"

    /** The pending 「復原」 card of a confirmed batch; its `payload` is the import proposal's id. */
    const val UNDO_KIND = "import_undo"

    const val MAX_ROWS = 500
    private const val PREVIEW_LINES = 5
    private const val OLDEST_YEARS = 10L

    /** Checks [rows] and stores the batch as a pending proposal. Nothing else is written. */
    fun preview(store: Store, clock: Clock, rules: Rules, kind: ImportKind, rows: List<JsonObject>): Preview {
        val today = Days.today(clock)
        val rejected = ArrayList<Rejected>()
        val good = ArrayList<Pair<LocalDate, JsonObject>>()
        rows.take(MAX_ROWS).forEachIndexed { i, o ->
            val why = check(o, kind, today, rules)
            if (why != null) rejected += Rejected(i + 1, why) else good += date(o)!! to o
        }
        if (rows.size > MAX_ROWS) rejected += Rejected(MAX_ROWS + 1, "一次最多 $MAX_ROWS 筆，後面的 ${rows.size - MAX_ROWS} 筆沒有收")
        val staged = stage(kind, good, rules).sortedBy { it.date }
        val from = staged.firstOrNull()?.let { LocalDate.parse(it.date) }
        val to = staged.lastOrNull()?.let { LocalDate.parse(it.date) }
        val title = "匯入 ${staged.size} 筆${kind.word}"
        val body = body(kind, staged, from, to, rejected)
        val batch = ImportBatch(kind.wire, staged)
        val now = clock.now()
        val e = store.add(today, now, Kind.PROPOSAL, ProposalRec(KIND, title, body, payload = J.encodeToString(ImportBatch.serializer(), batch)))
        return Preview(e.id, kind, rows.size, staged.size, from, to, rejected, Card.Proposal(e.id, title, body))
    }

    /** He said yes to the import proposal [entryId]: writes every staged entry; null when it is not an open import. */
    fun apply(store: Store, clock: Clock, entryId: Long): Applied? = store.tx {
        val e = store.entry(entryId)?.takeIf { it.kind == Kind.PROPOSAL } ?: return@tx null
        val p = runCatching { e.read<ProposalRec>() }.getOrNull()?.takeIf { it.kind == KIND && it.answer == Card.Answer.OPEN } ?: return@tx null
        val batch = read(p) ?: return@tx null
        val kind = ImportKind.of(batch.kind) ?: return@tx null
        if (batch.entries.isEmpty()) return@tx null
        val id = entryId.toString()
        val ids = batch.entries.map { s ->
            val day = LocalDate.parse(s.date)
            val at = day.atTime(LocalTime.NOON).atZone(clock.zone).toInstant()
            store.add(day, at, s.kind, tag(s.data, id)).id
        }
        val from = LocalDate.parse(batch.entries.first().date)
        val to = LocalDate.parse(batch.entries.last().date)
        val undoTitle = "復原這次匯入？"
        val undoBody = "復原會刪掉這次匯入的 ${ids.size} 筆${kind.word}，其他紀錄不動。"
        val undo = store.add(
            Days.today(clock), clock.now(), Kind.PROPOSAL,
            ProposalRec(UNDO_KIND, undoTitle, undoBody, payload = entryId.toString()),
        )
        val done = batch.copy(ids = ids, undoId = undo.id)
        store.update(entryId, p.copy(answer = Card.Answer.YES, payload = J.encodeToString(ImportBatch.serializer(), done)))
        Applied(
            ids.size, kind, from, to,
            "✓ 已匯入 ${ids.size} 筆${kind.word}（${range(from, to)}），只當歷史紀錄：不算完成，也不算違規",
            Card.Proposal(undo.id, undoTitle, undoBody, yesLabel = UNDO_LABEL),
        )
    }

    /** The word on a 「復原」 card's yes button. */
    const val UNDO_LABEL = "復原"

    /**
     * Removes exactly the entries batch [batchId] (the import proposal's id) wrote. Returns how many went, or null when that
     * batch was never confirmed or is already undone. The pending 「復原」 card of the batch is closed too.
     */
    fun undo(store: Store, batchId: Long): Int? = store.tx {
        val e = store.entry(batchId)?.takeIf { it.kind == Kind.PROPOSAL } ?: return@tx null
        val p = runCatching { e.read<ProposalRec>() }.getOrNull()?.takeIf { it.kind == KIND && it.answer == Card.Answer.YES } ?: return@tx null
        val batch = read(p)?.takeIf { !it.undone } ?: return@tx null
        val tag = batchId.toString()
        var n = 0
        for (id in batch.ids) {
            val x = store.entry(id) ?: continue
            if ((x.data["batch"] as? JsonPrimitive)?.content != tag) continue
            if (store.remove(id)) n++
        }
        store.update(batchId, p.copy(payload = J.encodeToString(ImportBatch.serializer(), batch.copy(undone = true))))
        // Close the 「復原」 card that belongs to this batch, so it cannot run a second time.
        store.entry(batch.undoId)?.let { u ->
            val up = runCatching { u.read<ProposalRec>() }.getOrNull()
            if (up != null && up.kind == UNDO_KIND && up.payload == tag && up.answer == Card.Answer.OPEN) {
                store.update(u.id, up.copy(answer = Card.Answer.YES))
            }
        }
        n
    }

    /** The batch id a 「復原」 proposal points at, or null. */
    fun undoTarget(p: ProposalRec): Long? = if (p.kind == UNDO_KIND) p.payload.toLongOrNull() else null

    /** The batch stored in an import proposal. */
    fun read(p: ProposalRec): ImportBatch? =
        if (p.kind != KIND) null else runCatching { J.decodeFromString(ImportBatch.serializer(), p.payload) }.getOrNull()

    // ---------------------------------------------------------------- checking

    private fun date(o: JsonObject): LocalDate? = str(o, "date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun check(o: JsonObject, kind: ImportKind, today: LocalDate, rules: Rules): String? {
        val raw = str(o, "date") ?: return "沒有日期"
        val d = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return "日期看不懂（要像 2030-01-07 這樣）"
        if (d.isAfter(today)) return "日期在未來"
        if (d.isBefore(today.minusYears(OLDEST_YEARS))) return "日期太久以前"
        return when (kind) {
            ImportKind.MONEY -> {
                val amount = num(o, "amount") ?: return "沒有金額"
                if (amount != Math.floor(amount) || amount <= 0 || amount > 1_000_000) return "金額不合理"
                bucket(o) ?: return "不知道是生活、娛樂還是收入"
                null
            }
            ImportKind.MEAL -> {
                val name = str(o, "name") ?: return "沒有食物名稱"
                val kcal = num(o, "kcal")
                if (kcal == null && Nutrition.tableFood(rules, name) == null) return "沒有熱量，食物表裡也沒有這一樣"
                kcal?.let { if (it < 0 || it > 5000) return "熱量不合理" }
                num(o, "protein_g")?.let { if (it < 0 || it > 300) return "蛋白質不合理" }
                val slot = str(o, "slot")
                if (slot != null && MealSlot.values().none { it.name.equals(slot, ignoreCase = true) }) return "餐別看不懂"
                null
            }
            ImportKind.BODY -> {
                val kg = num(o, "kg")
                val waist = num(o, "waist_cm")
                if (kg == null && waist == null) return "沒有體重也沒有腰圍"
                if (kg != null && (kg < 20 || kg > 300)) return "體重不合理"
                if (waist != null && (waist < 30 || waist > 250)) return "腰圍不合理"
                null
            }
            ImportKind.MEMO -> if (str(o, "text") == null) "沒有內容" else null
        }
    }

    /** The valid rows as entries; meals of one day and slot become one meal, each item resolved through [Nutrition]. */
    private fun stage(kind: ImportKind, rows: List<Pair<LocalDate, JsonObject>>, rules: Rules): List<Staged> = when (kind) {
        ImportKind.MONEY -> rows.map { (d, o) ->
            Staged(d.toString(), Kind.MONEY, J.encodeToJsonElement(Money(num(o, "amount")!!.toInt(), bucket(o)!!, str(o, "note").orEmpty())).jsonObject)
        }
        ImportKind.BODY -> rows.map { (d, o) ->
            Staged(d.toString(), Kind.BODY, J.encodeToJsonElement(Body(kg = num(o, "kg"), waistCm = num(o, "waist_cm"))).jsonObject)
        }
        ImportKind.MEMO -> rows.map { (d, o) ->
            Staged(d.toString(), Kind.MEMO, J.encodeToJsonElement(Memo(str(o, "text")!!, done = bool(o, "done") ?: false)).jsonObject)
        }
        ImportKind.MEAL -> rows.groupBy { (d, o) -> d to slot(o) }.map { (key, list) ->
            val items = list.map { (_, o) -> item(rules, o) }
            Staged(key.first.toString(), Kind.MEAL, J.encodeToJsonElement(Meal(key.second, items)).jsonObject)
        }
    }

    /** A meal row as an item: his table wins; otherwise the number in his own record is kept (a label-like source). */
    private fun item(rules: Rules, o: JsonObject): MealItem {
        val name = str(o, "name")!!
        val kcal = num(o, "kcal")
        val protein = num(o, "protein_g") ?: 0.0
        val table = Nutrition.tableFood(rules, name)
        return when {
            table != null || kcal == null -> Nutrition.resolve(rules, name, kcal?.toInt() ?: 0, protein, FoodSource.TABLE)
            else -> Nutrition.resolve(rules, name, kcal.toInt(), protein, FoodSource.LABEL)
        }
    }

    private fun slot(o: JsonObject): MealSlot =
        str(o, "slot")?.let { s -> MealSlot.values().firstOrNull { it.name.equals(s, ignoreCase = true) } } ?: MealSlot.EXTRA

    private fun bucket(o: JsonObject): Bucket? =
        str(o, "bucket")?.let { s -> Bucket.values().firstOrNull { it.name.equals(s, ignoreCase = true) } }

    private fun tag(data: JsonObject, batch: String) = JsonObject(data + ("batch" to JsonPrimitive(batch)))

    // ---------------------------------------------------------------- the preview text

    private fun body(kind: ImportKind, staged: List<Staged>, from: LocalDate?, to: LocalDate?, rejected: List<Rejected>): String = buildString {
        if (staged.isEmpty()) append("沒有可以匯入的資料。") else {
            append("共 ${staged.size} 筆${kind.word}，${range(from, to)}。")
            staged.take(PREVIEW_LINES).forEach { append("\n・").append(line(it)) }
            if (staged.size > PREVIEW_LINES) append("\n・……還有 ${staged.size - PREVIEW_LINES} 筆")
        }
        if (rejected.isNotEmpty()) {
            append("\n沒有收的 ${rejected.size} 列：")
            rejected.take(PREVIEW_LINES).forEach { append("\n・第 ${it.row} 列：${it.why}") }
            if (rejected.size > PREVIEW_LINES) append("\n・……還有 ${rejected.size - PREVIEW_LINES} 列")
        }
        if (staged.isNotEmpty()) append("\n匯入的只當歷史紀錄：不算進任何一天的完成，也不算違規。")
    }

    private fun range(from: LocalDate?, to: LocalDate?): String = when {
        from == null || to == null -> ""
        from == to -> "$from"
        else -> "$from 到 $to"
    }

    private fun line(s: Staged): String {
        val d = LocalDate.parse(s.date).let { "${it.monthValue}/${it.dayOfMonth}" }
        fun <T> r(f: () -> T): T? = runCatching { f() }.getOrNull()
        val what = when (s.kind) {
            Kind.MONEY -> r { J.decodeFromJsonElement(Money.serializer(), s.data).let { "${word(it.bucket)} ${it.amount} 元" + if (it.note.isNotBlank()) " ${it.note}" else "" } }
            Kind.MEAL -> r {
                J.decodeFromJsonElement(Meal.serializer(), s.data).let { m ->
                    m.items.joinToString("、") { "${it.name} ${it.kcal} 大卡" } + "，共 ${m.items.sumOf { it.kcal }} 大卡"
                }
            }
            Kind.BODY -> r {
                J.decodeFromJsonElement(Body.serializer(), s.data).let { b ->
                    listOfNotNull(b.kg?.let { "體重 ${n(it)} 公斤" }, b.waistCm?.let { "腰圍 ${n(it)} 公分" }).joinToString("、")
                }
            }
            Kind.MEMO -> r { J.decodeFromJsonElement(Memo.serializer(), s.data).let { it.text + if (it.done) "（完成）" else "" } }
            else -> null
        }
        return "$d ${what.orEmpty()}"
    }

    private fun word(b: Bucket) = when (b) {
        Bucket.LIVING -> "生活"
        Bucket.FUN -> "娛樂"
        Bucket.INCOME -> "收入"
    }

    private fun n(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun prim(o: JsonObject, k: String): JsonPrimitive? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun str(o: JsonObject, k: String): String? = prim(o, k)?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun num(o: JsonObject, k: String): Double? = prim(o, k)?.let { it.doubleOrNull ?: it.content.trim().replace(",", "").toDoubleOrNull() }

    private fun bool(o: JsonObject, k: String): Boolean? = prim(o, k)?.booleanOrNull
}
