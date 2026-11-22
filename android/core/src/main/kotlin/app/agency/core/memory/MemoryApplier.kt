package app.agency.core.memory

import app.agency.core.guard.OutputGuard
import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryItem
import app.agency.core.model.MemoryOp
import app.agency.core.model.Message
import app.agency.core.rules.CoachRules
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A memory change the model asked for (mem0-style event). [quote] is a short piece of his own words in this turn (two to
 * twenty characters) that the memory comes from. [itemRef] names an item by the reference the prompt showed (「記3」); it is
 * required for UPDATE and DELETE. [text] is required for ADD and UPDATE.
 */
data class MemoryRequest(
    val op: MemoryOp,
    val block: String,
    val itemRef: String? = null,
    val text: String? = null,
    val quote: String? = null,
)

data class MemoryRejection(val index: Int, val reason: String)

/** [applied] events were written (each is shown under her bubble with 撤銷); [rejected] go back to the model as reasons. */
data class MemoryOutcome(val applied: List<MemoryEvent>, val rejected: List<MemoryRejection>)

/** Whether an undo was done; [reason] says why not (shown on 她記得的事). */
data class UndoResult(val ok: Boolean, val reason: String? = null)

/**
 * Applies memory events in code: the model only proposes them.
 * - At most [MAX_PER_TURN] per turn, and only in a turn with a message from him; code stamps that message as the source.
 * - Each must quote his words of this turn, and an ADD or UPDATE must share words with the quote.
 * - Items are named by the prompt's per-call references, resolved through the [RenderedMemory.refs] of that call.
 * - Text ≤ [MemoryBlocks.ITEM_CHARS] characters, passing [PrivacyFilter] and the guard's tone-free bans.
 * - A block's cap is never exceeded (an ADD over it is rejected with the reason, nothing is evicted); an open loop whose day
 *   has passed no longer counts.
 * - An open loop must name a day code can work out ([DueDay]), stored as [MemoryItem.dueDay]; a habit goes to about_him.
 * Every applied change is a [MemoryEvent] holding the text and due day before and after, so [undo] can put it back.
 */
class MemoryApplier(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    private val dayStartHour: Int = 5,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun apply(requests: List<MemoryRequest>, owner: Message?, refs: Map<String, String>): MemoryOutcome = store.transaction {
        val applied = mutableListOf<MemoryEvent>()
        val rejected = mutableListOf<MemoryRejection>()
        requests.forEachIndexed { i, r ->
            if (i >= MAX_PER_TURN) {
                rejected += MemoryRejection(i, "一輪最多記 $MAX_PER_TURN 件事")
                return@forEachIndexed
            }
            try {
                applied += applyOne(r, owner, refs)
            } catch (e: Reject) {
                rejected += MemoryRejection(i, e.message!!)
            }
        }
        MemoryOutcome(applied, rejected)
    }

    private class Reject(reason: String) : Exception(reason)

    private fun reject(reason: String): Nothing = throw Reject(reason)

    private suspend fun applyOne(r: MemoryRequest, owner: Message?, refs: Map<String, String>): MemoryEvent {
        if (owner == null) reject("這一輪沒有他說的話，不能記")
        val quote = plain(r.quote.orEmpty())
        if (quote.length !in 2..20 || !plain(owner.text).contains(quote)) reject("要引用他這一輪說過的原話（二到二十字）")
        val block = MemoryBlock.of(r.block) ?: reject("沒有這個區塊")
        val now = clock.millis()
        val today = today()
        val live = store.memory.items(block.key)
        fun named(): MemoryItem {
            val id = r.itemRef?.trim()?.let { refs[it] } ?: reject("沒有這一條（用記得的事前面的編號，例如記一）")
            return live.firstOrNull { it.id == id } ?: reject("${block.zh}裡沒有這一條")
        }
        return when (r.op) {
            MemoryOp.ADD -> {
                val (text, due) = validText(r.text, quote, block, today)
                if (live.any { it.text == text }) reject("已經記過了")
                if (active(live, today).size >= block.cap) reject("${block.zh}已經滿了（最多 ${block.cap} 條），先刪掉或改一條")
                val item = MemoryItem(newId(), block.key, text, now, now, owner.id, dueDay = due)
                store.memory.putItem(item)
                event(MemoryOp.ADD, item, null, text, null, due, owner.id, now)
            }
            MemoryOp.UPDATE -> {
                val item = named()
                val (text, due) = validText(r.text, quote, block, today)
                store.memory.putItem(item.copy(text = text, dueDay = due, updatedAt = now, sourceMessageId = owner.id))
                event(MemoryOp.UPDATE, item, item.text, text, item.dueDay, due, owner.id, now)
            }
            MemoryOp.DELETE -> {
                val item = named()
                store.memory.putItem(item.copy(deleted = true, updatedAt = now))
                event(MemoryOp.DELETE, item, item.text, null, item.dueDay, null, owner.id, now)
            }
        }
    }

    /** The items that count against the block's cap: an open loop whose day has passed does not. */
    private fun active(items: List<MemoryItem>, today: LocalDate) = items.filter { !MemoryBlocks.expired(it, today, ::dayOf) }

    private fun validText(raw: String?, quote: String, block: MemoryBlock, today: LocalDate): Pair<String, String?> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) reject("要寫要記的內容")
        if (text.codePointCount(0, text.length) > MemoryBlocks.ITEM_CHARS) reject("最多 ${MemoryBlocks.ITEM_CHARS} 個字")
        PrivacyFilter.reason(text)?.let { reject(it) }
        OutputGuard.bannedLabel(text)?.let { reject("不能記：$it") }
        if (bigrams(text).intersect(bigrams(quote)).isEmpty()) reject("記的事要跟他引用的原話有關")
        if (block != MemoryBlock.OPEN_LOOPS) return text to null
        if (DueDay.RECURRING.containsMatchIn(text)) reject("固定的事放 about_him")
        val due = DueDay.resolve(text, today) ?: reject("要說是哪一天（例如明天、星期五、下週）")
        return text to due.toString()
    }

    private suspend fun event(
        op: MemoryOp, item: MemoryItem, before: String?, after: String?, beforeDue: String?, afterDue: String?, source: String, at: Long,
    ): MemoryEvent {
        val e = MemoryEvent(newId(), at, op, item.block, item.id, before, after, source, beforeDue = beforeDue, afterDue = afterDue)
        store.memory.putEvent(e)
        return e
    }

    private fun dayOf(at: Long): LocalDate = CoachRules.coachDay(Instant.ofEpochMilli(at), clock.zone, dayStartHour)

    private fun today(): LocalDate = dayOf(clock.millis())

    /**
     * Puts back what event [eventId] changed (設定 › 她記得的事, or 撤銷 under her bubble): an ADD removes the item, an UPDATE
     * restores the old text and due day, a DELETE brings the item back. Only while the item is still as that event left it;
     * a later change must be undone first, and a DELETE cannot come back into a full block.
     */
    suspend fun undo(eventId: String): UndoResult = store.transaction {
        val e = store.memory.event(eventId) ?: return@transaction UndoResult(false, "找不到這一筆")
        if (e.undone) return@transaction UndoResult(false, "已經撤銷過了")
        val item = store.memory.item(e.itemId) ?: return@transaction UndoResult(false, "找不到這一筆")
        val now = clock.millis()
        val asLeft = when (e.op) {
            MemoryOp.ADD -> !item.deleted && item.text == e.after
            MemoryOp.UPDATE -> !item.deleted && item.text == e.after && item.dueDay == e.afterDue
            MemoryOp.DELETE -> item.deleted && item.text == e.before
        }
        if (!asLeft) return@transaction UndoResult(false, "先撤銷比較新的那一筆")
        if (e.op == MemoryOp.DELETE) {
            val block = MemoryBlock.of(item.block)
            val restored = item.copy(deleted = false)
            if (block != null && !MemoryBlocks.expired(restored, today(), ::dayOf) &&
                active(store.memory.items(block.key), today()).size >= block.cap
            ) return@transaction UndoResult(false, "${block.zh}已經滿了，先刪掉一條")
        }
        val restored = when (e.op) {
            MemoryOp.ADD -> item.copy(deleted = true, updatedAt = now)
            MemoryOp.UPDATE -> item.copy(text = e.before ?: item.text, dueDay = e.beforeDue, updatedAt = now)
            MemoryOp.DELETE -> item.copy(deleted = false, updatedAt = now)
        }
        store.memory.putItem(restored)
        store.memory.putEvent(e.copy(undone = true))
        UndoResult(true)
    }

    companion object {
        const val MAX_PER_TURN = 2
        private val NOT_WORD = Regex("[\\s\\p{P}\\p{S}]")

        /** [s] without spaces and punctuation (a quote matches his words whatever the commas). */
        fun plain(s: String): String = NOT_WORD.replace(s, "")

        private fun bigrams(s: String): Set<String> {
            val han = s.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            return (0 until han.length - 1).map { han.substring(it, it + 2) }.toSet()
        }
    }
}
