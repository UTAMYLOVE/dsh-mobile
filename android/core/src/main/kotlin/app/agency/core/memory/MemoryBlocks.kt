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

object MemoryBlocks {
    const val ITEM_CHARS = 80

    /**
     * The memory section of a prompt, in block order. An open loop shows the day code resolved from its words (relative to
     * the day it was written); one that is over is left out.
     */
    fun render(items: List<MemoryItem>, today: LocalDate, dayOf: (Long) -> LocalDate): String {
        val live = items.filter { !it.deleted }
        return MemoryBlock.entries.joinToString("\n") { b ->
            val lines = live.filter { it.block == b.key }.mapNotNull { i ->
                if (b != MemoryBlock.OPEN_LOOPS) return@mapNotNull "- [${i.id}] ${i.text}"
                val due = DueDay.resolve(i.text, dayOf(i.createdAt)) ?: return@mapNotNull null
                if (due.isBefore(today)) null else "- [${i.id}] ${i.text}（$due）"
            }
            "${b.zh}（${b.key}，最多 ${b.cap} 條）：" + if (lines.isEmpty()) "無" else "\n" + lines.joinToString("\n")
        }
    }
}

/**
 * The day an open loop is about, worked out by code from his words (the model never computes a date): 今天、明天、後天、
 * 大後天; 這週五、星期五、禮拜五 (the next such day, today included); 下週五 (that day of next week); 下週 (next Monday); 週末
 * (the coming Saturday); 下個月 (the first of next month); 月底 (the last day of this month). Null when no such word is there.
 */
object DueDay {
    private const val WEEK = "(?:週|星期|禮拜|周)"
    private val DAY_CHARS = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    private val NEXT_WEEKDAY = Regex("下(?:個)?$WEEK([一二三四五六日天])(?![次個])")
    private val THIS_WEEKDAY = Regex("(?:這|本)?$WEEK([一二三四五六日天])(?![次個])")

    fun resolve(text: String, from: LocalDate): LocalDate? {
        NEXT_WEEKDAY.find(text)?.let { m ->
            val monday = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
            return monday.plusDays(DAY_CHARS.getValue(m.groupValues[1][0]) - 1L)
        }
        THIS_WEEKDAY.find(text)?.let { m ->
            return from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(DAY_CHARS.getValue(m.groupValues[1][0]))))
        }
        return when {
            text.contains("大後天") -> from.plusDays(3)
            text.contains("後天") -> from.plusDays(2)
            text.contains("明天") -> from.plusDays(1)
            text.contains("今天") || text.contains("今晚") -> from
            Regex("下(?:個)?$WEEK").containsMatchIn(text) -> from.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            text.contains("週末") || text.contains("周末") -> from.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            text.contains("下個月") -> from.with(TemporalAdjusters.firstDayOfNextMonth())
            text.contains("月底") -> from.with(TemporalAdjusters.lastDayOfMonth())
            else -> null
        }
    }
}
