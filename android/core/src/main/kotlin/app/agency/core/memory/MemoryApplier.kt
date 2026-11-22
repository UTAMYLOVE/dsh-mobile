package app.agency.core.memory

import app.agency.core.model.MemoryEvent
import app.agency.core.model.MemoryItem
import app.agency.core.model.MemoryOp
import app.agency.core.store.AgencyStore
import app.agency.core.rules.CoachRules
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import java.util.UUID

/**
 * A memory change the model asked for (mem0-style event): [evidence] must be the id of his message in this turn. [itemId] is
 * required for UPDATE and DELETE, [text] for ADD and UPDATE.
 */
data class MemoryRequest(
    val op: MemoryOp,
    val block: String,
    val itemId: String? = null,
    val text: String? = null,
    val evidence: String? = null,
)

data class MemoryRejection(val index: Int, val reason: String)

/** [applied] events were written (each is shown under her bubble with 撤銷); [rejected] go back to the model as reasons. */
data class MemoryOutcome(val applied: List<MemoryEvent>, val rejected: List<MemoryRejection>)

/**
 * Applies memory events in code: the model only proposes them. At most [MAX_PER_TURN] per turn; each must cite this turn's
 * message from him; text ≤ [MemoryBlocks.ITEM_CHARS] characters and passing [PrivacyFilter]; UPDATE and DELETE must name a
 * live item of that block; a block's cap is never exceeded (an ADD over it is rejected with the reason, nothing is evicted);
 * an open loop must name a day code can work out ([DueDay]). Every applied change is a [MemoryEvent] holding `before` and
 * `after`, so [undo] can put it back.
 */
class MemoryApplier(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    private val dayStartHour: Int = 5,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun apply(requests: List<MemoryRequest>, ownerMessageId: String?): MemoryOutcome = store.transaction {
        val applied = mutableListOf<MemoryEvent>()
        val rejected = mutableListOf<MemoryRejection>()
        requests.forEachIndexed { i, r ->
            if (i >= MAX_PER_TURN) {
                rejected += MemoryRejection(i, "一輪最多記 $MAX_PER_TURN 件事")
                return@forEachIndexed
            }
            try {
                applied += applyOne(r, ownerMessageId)
            } catch (e: Reject) {
                rejected += MemoryRejection(i, e.message!!)
            }
        }
        MemoryOutcome(applied, rejected)
    }

    private class Reject(reason: String) : Exception(reason)

    private fun reject(reason: String): Nothing = throw Reject(reason)

    private suspend fun applyOne(r: MemoryRequest, ownerMessageId: String?): MemoryEvent {
        if (ownerMessageId == null) reject("這一輪沒有他說的話，不能記")
        if (r.evidence != ownerMessageId) reject("要引用他這一輪訊息的編號")
        val block = MemoryBlock.of(r.block) ?: reject("沒有這個區塊")
        val now = clock.millis()
        val live = store.memory.items(block.key)
        return when (r.op) {
            MemoryOp.ADD -> {
                val text = validText(r.text, block)
                if (live.any { it.text == text }) reject("已經記過了")
                if (live.size >= block.cap) reject("${block.zh}已經滿了（最多 ${block.cap} 條），先刪掉或改一條")
                val item = MemoryItem(newId(), block.key, text, now, now, ownerMessageId)
                store.memory.putItem(item)
                event(MemoryOp.ADD, block, item.id, null, text, ownerMessageId, now)
            }
            MemoryOp.UPDATE -> {
                val item = live.firstOrNull { it.id == r.itemId } ?: reject("${block.zh}裡沒有這一條")
                val text = validText(r.text, block)
                store.memory.putItem(item.copy(text = text, updatedAt = now, sourceMessageId = ownerMessageId))
                event(MemoryOp.UPDATE, block, item.id, item.text, text, ownerMessageId, now)
            }
            MemoryOp.DELETE -> {
                val item = live.firstOrNull { it.id == r.itemId } ?: reject("${block.zh}裡沒有這一條")
                store.memory.putItem(item.copy(deleted = true, updatedAt = now))
                event(MemoryOp.DELETE, block, item.id, item.text, null, ownerMessageId, now)
            }
        }
    }

    private fun validText(raw: String?, block: MemoryBlock): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) reject("要寫要記的內容")
        if (text.codePointCount(0, text.length) > MemoryBlocks.ITEM_CHARS) reject("最多 ${MemoryBlocks.ITEM_CHARS} 個字")
        PrivacyFilter.reason(text)?.let { reject(it) }
        if (block == MemoryBlock.OPEN_LOOPS && DueDay.resolve(text, today()) == null) reject("要說是哪一天（例如明天、星期五、下週）")
        return text
    }

    private suspend fun event(op: MemoryOp, block: MemoryBlock, itemId: String, before: String?, after: String?, source: String, at: Long): MemoryEvent {
        val e = MemoryEvent(newId(), at, op, block.key, itemId, before, after, source)
        store.memory.putEvent(e)
        return e
    }

    private fun today(): LocalDate = CoachRules.coachDay(clock.now(), clock.zone, dayStartHour)

    /**
     * Puts back what event [eventId] changed (設定 › 她記得的事, or 撤銷 under her bubble): an ADD removes the item, an UPDATE
     * restores the old text, a DELETE brings the item back. False when the event is unknown or already undone.
     */
    suspend fun undo(eventId: String): Boolean = store.transaction {
        val e = store.memory.event(eventId)?.takeIf { !it.undone } ?: return@transaction false
        val item = store.memory.item(e.itemId) ?: return@transaction false
        val now = clock.millis()
        val restored = when (e.op) {
            MemoryOp.ADD -> item.copy(deleted = true, updatedAt = now)
            MemoryOp.UPDATE, MemoryOp.DELETE -> item.copy(text = e.before ?: item.text, deleted = false, updatedAt = now)
        }
        store.memory.putItem(restored)
        store.memory.putEvent(e.copy(undone = true))
        true
    }

    companion object {
        const val MAX_PER_TURN = 2
    }
}
