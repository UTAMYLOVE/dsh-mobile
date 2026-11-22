package app.agency.core.memory

import app.agency.core.model.MemoryItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The fixed memory blocks (Letta-style): each has a key the model uses, a title, and a cap on items. */
enum class MemoryBlock(val key: String, val zh: String, val cap: Int) {
    ABOUT_HIM("about_him", "關於他", 6),
    LIKES("likes", "口味", 10),
    OPEN_LOOPS("open_loops", "他說快到的事", 8),
    HOW_TO_TALK("how_to_talk", "跟他說話的方式", 6);

    companion object {
        fun of(key: String?): MemoryBlock? = entries.firstOrNull { it.key == key }
        val KEYS: List<String> = entries.map { it.key }
    }
}

/**
 * The memory section of one call: [text] for the prompt, and [refs], the short per-call reference (「記1」…) of every item
 * shown → its id. The model names items by reference; [MemoryApplier] resolves them through [refs], so no id is ever copied.
 */
data class RenderedMemory(val text: String, val refs: Map<String, String>) {
    companion object {
        val EMPTY = RenderedMemory("", emptyMap())
    }
}

object MemoryBlocks {
    const val ITEM_CHARS = 80
    const val REF_PREFIX = "記"

    /** The due day of an open loop: its stored [MemoryItem.dueDay], or (for an item written before that field) its words. */
    fun dueOf(item: MemoryItem, dayOf: (Long) -> LocalDate): LocalDate? =
        item.dueDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: DueDay.resolve(item.text, dayOf(item.createdAt))

    /** An open loop whose day has passed: not shown in prompts and not counted against the cap. */
    fun expired(item: MemoryItem, today: LocalDate, dayOf: (Long) -> LocalDate): Boolean =
        item.block == MemoryBlock.OPEN_LOOPS.key && dueOf(item, dayOf)?.isBefore(today) != false

    /** The memory section of a prompt, in block order. An open loop shows its due day; one that is over is left out. */
    fun render(items: List<MemoryItem>, today: LocalDate, dayOf: (Long) -> LocalDate): RenderedMemory {
        val refs = linkedMapOf<String, String>()
        val live = items.filter { !it.deleted }
        val text = MemoryBlock.entries.joinToString("\n") { b ->
            val lines = live.filter { it.block == b.key && !expired(it, today, dayOf) }.map { i ->
                val ref = REF_PREFIX + (refs.size + 1)
                refs[ref] = i.id
                if (b == MemoryBlock.OPEN_LOOPS) "- [$ref] ${i.text}（${dueOf(i, dayOf)}）" else "- [$ref] ${i.text}"
            }
            "${b.zh}（${b.key}，最多 ${b.cap} 條）：" + if (lines.isEmpty()) "無" else "\n" + lines.joinToString("\n")
        }
        return RenderedMemory(text, refs)
    }
}

/**
 * The day an open loop is about, worked out by code from his words (the model never computes a date): 今天、明天、後天、
 * 大後天; 這週五、星期五、禮拜五 (the next such day, today included); 下週五 (that day of next week), 下下週五 (the week
 * after). A range is due on its last day, so it stays in the prompt while it can still happen: 下週 (Sunday of next week),
 * 下下週 (the Sunday after), 下週末 (Saturday of next week), 週末 (the coming Saturday), 下個月 (the last day of next month),
 * 月底 (the last day of this month). Null when no such word is there.
 */
object DueDay {
    private const val WEEK = "(?:週|星期|禮拜|周)"
    private val DAY_CHARS = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    private val NEXT_WEEKDAY = Regex("(下下|下)(?:個)?$WEEK([一二三四五六日天])(?![次個])")
    private val THIS_WEEKDAY = Regex("(?:這|本)?$WEEK([一二三四五六日天])(?![次個])")

    /** A habit, not a coming event: it belongs in about_him. */
    val RECURRING = Regex("每$WEEK|每個$WEEK|每天|每晚|每個月|每次")

    private fun nextWeekMonday(from: LocalDate): LocalDate = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)

    fun resolve(text: String, from: LocalDate): LocalDate? {
        NEXT_WEEKDAY.find(text)?.let { m ->
            val weeks = if (m.groupValues[1] == "下下") 2L else 1L
            val monday = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weeks)
            return monday.plusDays(DAY_CHARS.getValue(m.groupValues[2][0]) - 1L)
        }
        THIS_WEEKDAY.find(text)?.let { m ->
            return from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(DAY_CHARS.getValue(m.groupValues[1][0]))))
        }
        return when {
            text.contains("大後天") -> from.plusDays(3)
            text.contains("後天") -> from.plusDays(2)
            text.contains("明天") -> from.plusDays(1)
            text.contains("今天") || text.contains("今晚") -> from
            Regex("下(?:個)?${WEEK}末|下(?:個)?周末").containsMatchIn(text) -> nextWeekMonday(from).plusDays(5)
            Regex("下下(?:個)?$WEEK").containsMatchIn(text) -> nextWeekMonday(from).plusWeeks(1).plusDays(6)
            Regex("下(?:個)?$WEEK").containsMatchIn(text) -> nextWeekMonday(from).plusDays(6)
            text.contains("週末") || text.contains("周末") -> from.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            text.contains("下個月") -> from.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())
            text.contains("月底") -> from.with(TemporalAdjusters.lastDayOfMonth())
            else -> null
        }
    }
}
