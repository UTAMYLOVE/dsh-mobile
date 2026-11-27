package app.agency.core.brain.handlers

import app.agency.core.brain.SnapshotReader
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Turn
import app.agency.core.model.FoodEntry
import app.agency.core.model.LedgerBucket
import app.agency.core.model.LedgerEntry
import app.agency.core.model.Media
import app.agency.core.model.PhotoRead
import app.agency.core.model.PhotoReadKind
import app.agency.core.model.PhotoReadStatus
import app.agency.core.rules.FoodTable
import app.agency.core.store.AgencyStore
import app.agency.core.structured.IntakeItem
import app.agency.core.time.AgencyClock
import app.agency.core.vision.PhotoReads
import app.agency.core.vision.ReadDraft
import app.agency.core.vision.VisionImage
import app.agency.core.vision.VisionRouter
import kotlinx.coroutines.withTimeoutOrNull

/** What a photo reading said: the line, the reading waiting for his confirm (null: none). */
data class PhotoOutcome(val line: String, val read: PhotoRead? = null, val body: Boolean = false)

/**
 * Photos the image-capable model reads (T15b/T15c): 菜單, 餐點照, 收據／訂單截圖 become a list he edits and confirms (nothing is a
 * fact before that); 身體照 gets the teacher's words through two checks (the rule guard here, then a judge on a fixed rubric).
 * The model's words about a photo never enter memory.
 */
class PhotoDesk(
    private val store: AgencyStore,
    private val snapshot: SnapshotReader,
    private val clock: AgencyClock,
    private val newId: () -> String,
    private val vision: VisionRouter?,
    private val judgeLlm: LlmClient,
) {
    fun supported(): Boolean = vision?.supported() == true

    /** Reads [media] (already sealed and stored by the caller) from its cleaned [images] (the photo, then a baseline photo). */
    suspend fun read(kind: PhotoReadKind, media: Media, images: List<VisionImage>): PhotoOutcome {
        val v = vision?.takeIf { it.supported() } ?: return PhotoOutcome(PhotoReads.NO_VISION)
        if (images.isEmpty()) return PhotoOutcome(PhotoReads.failLine(null))
        val result = withTimeoutOrNull(TIMEOUT_MS) { v.see(PhotoReads.request(kind, images.take(if (kind == PhotoReadKind.BODY) 2 else 1))) }
        val ok = result as? ChatResult.Ok ?: return PhotoOutcome(PhotoReads.failLine((result as? ChatResult.Failed)?.fail))
        if (kind == PhotoReadKind.BODY) return PhotoOutcome(bodyLine(ok.text.orEmpty()), body = true)
        val draft = PhotoReads.parse(kind, ok.text.orEmpty()) ?: return PhotoOutcome(PhotoReads.failLine(null))
        if (draft.rows.isEmpty()) return PhotoOutcome(PhotoReads.summary(kind, draft))
        val read = PhotoRead(newId(), clock.millis(), media.id, kind, draft.json())
        store.photoReads.upsert(read)
        return PhotoOutcome(PhotoReads.summary(kind, draft), read)
    }

    /** The body teacher's words: the model's text, cut by the rule guard, then the judge; a template when either says no. */
    private suspend fun bodyLine(raw: String): String {
        val said = app.agency.core.structured.LocalRepair.parse(raw, emptyMap())?.get("text")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val first = said?.let(PhotoReads::guardBody) ?: return BODY_HELD
        val verdict = withTimeoutOrNull(JUDGE_TIMEOUT_MS) {
            judgeLlm.chat(
                ChatRequest(
                    PhotoReads.BODY_JUDGE_SYSTEM, listOf(Turn.User("評語：\n$first")), Purpose.JUDGE, JUDGE_TOKENS,
                    output = Output.Json(PhotoReads.BODY_JUDGE_SCHEMA), thinking = Thinking.OFF,
                ),
            )
        }?.let(PhotoReads::bodyVerdict)
        return if (verdict == false) BODY_HELD else first
    }

    /**
     * His confirm of reading [id] with the rows he kept or edited ([draft]): 菜單 rows with a number go into the food table
     * (標「菜單」 when printed, else 「估算」); 餐點照 rows become the meal, counted by code; 收據 rows with an amount become 記帳
     * entries, and its food rows the meal of that day (today only). Null when the reading is not waiting any more.
     */
    suspend fun confirm(id: String, draft: ReadDraft, ill: Boolean, meals: MealDesk, mealsOpen: Boolean): MealOutcome? {
        val r = store.photoReads.get(id)?.takeIf { it.status == PhotoReadStatus.WAITING } ?: return null
        val rows = draft.rows.mapNotNull { row ->
            PhotoReads.clean(row.name)?.let { n ->
                row.copy(
                    name = n, kcal = row.kcal?.takeIf { it in PhotoReads.KCAL }, amount = row.amount?.takeIf { it in PhotoReads.AMOUNT },
                    proteinG = row.proteinG?.takeIf { it in PhotoReads.PROTEIN }, portion = row.portion.takeIf { it in PhotoReads.PORTION } ?: 1.0,
                )
            }
        }.take(PhotoReads.MAX_ROWS)
        val kept = ReadDraft(rows, draft.date, draft.total)
        val now = clock.millis()
        val out: MealOutcome = when (r.kind) {
            PhotoReadKind.MENU -> menu(rows, now)
            PhotoReadKind.MEAL -> if (!mealsOpen) MealOutcome(null, listOf(MEALS_CLOSED)) else meal(rows, ill, meals)
            PhotoReadKind.RECEIPT -> receipt(kept, now, ill, meals, mealsOpen)
            PhotoReadKind.BODY -> MealOutcome(null, emptyList())
        }
        store.photoReads.upsert(r.copy(json = kept.json(), status = PhotoReadStatus.CONFIRMED, decidedAt = now))
        return out
    }

    suspend fun drop(id: String): Boolean {
        val r = store.photoReads.get(id)?.takeIf { it.status == PhotoReadStatus.WAITING } ?: return false
        store.photoReads.upsert(r.copy(status = PhotoReadStatus.DROPPED, decidedAt = clock.millis()))
        return true
    }

    private suspend fun menu(rows: List<app.agency.core.vision.ReadRow>, now: Long): MealOutcome {
        val lines = mutableListOf<String>()
        val numbered = rows.filter { it.kcal != null }
        store.transaction {
            numbered.forEach { row ->
                val old = store.food.all().firstOrNull { FoodTable.normalize(it.name) == FoodTable.normalize(row.name) }
                // A label he gave keeps its numbers over a menu estimate.
                if (old != null && !old.estimated && !row.printed) return@forEach
                store.food.upsert(
                    FoodEntry(
                        old?.name ?: row.name, old?.aliases.orEmpty(), row.kcal!!, row.proteinG ?: old?.proteinG, null, null, fried = row.fried,
                        estimated = !row.printed, seeded = false, addedAt = old?.addedAt ?: now, processed = row.processed, updatedAt = now,
                    ),
                )
            }
        }
        if (numbered.isEmpty()) return MealOutcome(null, listOf("沒有數字的菜沒有存。點了哪一樣、吃了多少再跟我說。"))
        lines += "存進食物表：" + numbered.joinToString("、") { "${it.name} ${it.kcal} 大卡（${if (it.printed) "菜單" else "估算"}）" } + "。"
        val skipped = rows.size - numbered.size
        if (skipped > 0) lines += "沒有數字的 $skipped 樣沒有存。"
        lines += "吃了哪一樣再跟我說，照這個算。"
        return MealOutcome(null, lines)
    }

    private suspend fun meal(rows: List<app.agency.core.vision.ReadRow>, ill: Boolean, meals: MealDesk): MealOutcome {
        if (rows.isEmpty()) return MealOutcome(null, listOf(MealDesk.NOT_UNDERSTOOD))
        val items = rows.map { IntakeItem(it.name, it.portion, it.fried, it.processed, kcalHigh = it.kcal, proteinLow = it.proteinG) }
        return meals.record(PHOTO_TEXT + rows.joinToString("、") { it.name }, items, ill)
    }

    private suspend fun receipt(d: ReadDraft, now: Long, ill: Boolean, meals: MealDesk, mealsOpen: Boolean): MealOutcome {
        val today = snapshot.today(snapshot.profile())
        val day = d.date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isAfter(today) } ?: today
        val paid = d.rows.filter { it.amount != null }
        val lines = mutableListOf<String>()
        store.transaction {
            paid.forEach { row ->
                store.ledger.insert(
                    LedgerEntry(newId(), day.toString(), now, row.amount!!, row.name, null, if (row.entertainment) LedgerBucket.FUN else LedgerBucket.LIFE, LedgerEntry.SOURCE_RECEIPT),
                )
            }
        }
        if (paid.isNotEmpty()) {
            lines += "記帳（$day）：" + paid.joinToString("、") { "${it.name} ${it.amount} 元（${if (it.entertainment) LedgerBucket.FUN.zh else LedgerBucket.LIFE.zh}）" } + "。"
        }
        val food = d.rows.filter { it.food }
        if (food.isNotEmpty() && day == today && mealsOpen) {
            val m = meal(food, ill, meals)
            return MealOutcome(m.meal, lines + m.lines, m.signals)
        }
        if (food.isNotEmpty() && day != today) lines += "這張是 $day 的，吃的東西沒有記成今天的餐點。"
        if (lines.isEmpty()) lines += "沒有金額的項目沒有記。"
        return MealOutcome(null, lines)
    }

    companion object {
        const val READ_KIND = "photo_read"
        const val BODY_KIND = "body_feedback"
        const val PHOTO_TEXT = "照片："
        const val TIMEOUT_MS = 45_000L
        const val JUDGE_TIMEOUT_MS = 8_000L
        const val JUDGE_TOKENS = 150
        const val BODY_HELD = "照片收好了。這次的評語沒有過審稿，先不給；下次拍再看。"
        const val MEALS_CLOSED = "三餐拍照還沒開放，這張先當證據收著。"
    }
}
