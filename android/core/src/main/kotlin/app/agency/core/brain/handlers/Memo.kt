package app.agency.core.brain.handlers

import app.agency.core.engine.Post
import app.agency.core.engine.PostChannel
import app.agency.core.model.Memo
import app.agency.core.store.AgencyStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 備忘錄's quick path (T12b), code only: 「幫我記…」「記一下…」「提醒我…」. [text] is his words after the command word, kept as he
 * wrote them; [dueAt] is the time code read in them (null: none, the memo is only listed).
 */
data class MemoParse(val text: String, val dueAt: Instant?, val remind: Boolean)

object MemoWords {
    private val PREFIXES = listOf(
        "幫我記一下", "幫我記住", "幫我記下來", "幫我記下", "幫我記", "記一下", "記得提醒我", "幫我提醒", "提醒我一下", "提醒我",
    )
    private val LEAD = Regex("^[\\s　：:，,、]+")
    private val TRAIL = Regex("[\\s　。！!]+$")
    const val MAX_TEXT = 200

    /** His memo, or null when the message is not one (the command word must start it and something must follow). */
    fun parse(text: String, now: Instant, zone: ZoneId, dayStartHour: Int): MemoParse? {
        val t = text.trim()
        val prefix = PREFIXES.firstOrNull { t.startsWith(it) } ?: return null
        val body = TRAIL.replace(LEAD.replace(t.removePrefix(prefix), ""), "").take(MAX_TEXT)
        if (body.isEmpty()) return null
        return MemoParse(body, due(body, now, zone, dayStartHour), remind = prefix.contains("提醒"))
    }

    private val CN = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    /** 0–59 in digits or Chinese (「九」「十二」「三十」「四十五」). */
    fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty() || s.any { it != '十' && it !in CN }) return null
        val ten = s.indexOf('十')
        if (ten < 0) return if (s.length == 1) CN[s[0]] else null
        val tens = if (ten == 0) 1 else CN[s[0]]?.takeIf { ten == 1 } ?: return null
        val ones = s.substring(ten + 1).let { if (it.isEmpty()) 0 else if (it.length == 1) CN[it[0]] ?: return null else return null }
        return tens * 10 + ones
    }

    private const val NUM = "([0-9]{1,2}|[零〇一二兩三四五六七八九十]{1,3})"
    private val CLOCK = Regex("([0-9]{1,2})[:：]([0-9]{2})")
    private val HOUR = Regex("(早上|上午|中午|下午|晚上|傍晚|半夜|凌晨)?\\s*$NUM\\s*[點点時](半|$NUM\\s*分?)?")
    private val PERIOD = Regex("(早上|上午|中午|下午|晚上|傍晚|半夜|凌晨)")
    private val IN_MIN = Regex("$NUM\\s*分鐘(?:之|以)?後")
    private val IN_HOUR = Regex("$NUM\\s*(?:個)?小時(?:之|以)?後")
    private val WEEKDAY = Regex("(下下|下)?(?:個)?(?:星期|禮拜|週|周)([一二三四五六日天])")
    private val DATE = Regex("([0-9]{1,2}|[一二三四五六七八九十]{1,3})\\s*月\\s*$NUM\\s*[日號号]")
    private val SLASH = Regex("(?<![0-9])([0-9]{1,2})/([0-9]{1,2})(?![0-9])")

    /** The time in [body], read against [now] on his coach day; null when it names none. */
    fun due(body: String, now: Instant, zone: ZoneId, dayStartHour: Int): Instant? {
        val here = now.atZone(zone)
        IN_MIN.find(body)?.let { m -> number(m.groupValues[1])?.let { return now.plusSeconds(it * 60L) } }
        IN_HOUR.find(body)?.let { m -> number(m.groupValues[1])?.let { return now.plusSeconds(it * 3600L) } }
        val coachToday = (if (here.hour < dayStartHour) here.minusDays(1) else here).toLocalDate()
        var explicitDay = true
        val date: LocalDate = when {
            body.contains("大後天") -> coachToday.plusDays(3)
            body.contains("後天") -> coachToday.plusDays(2)
            body.contains("明天") || body.contains("明早") || body.contains("明晚") -> coachToday.plusDays(1)
            body.contains("今天") || body.contains("今晚") -> coachToday
            else -> {
                val wd = WEEKDAY.find(body)
                val dm = DATE.find(body)
                val sl = SLASH.find(body)
                when {
                    wd != null -> {
                        val target = "一二三四五六".indexOf(wd.groupValues[2]).let { if (it < 0) DayOfWeek.SUNDAY else DayOfWeek.of(it + 1) }
                        val weeks = when (wd.groupValues[1]) { "下下" -> 2L; "下" -> 1L; else -> 0L }
                        val monday = coachToday.minusDays((coachToday.dayOfWeek.value - 1).toLong())
                        var d = monday.plusWeeks(weeks).plusDays((target.value - 1).toLong())
                        if (weeks == 0L && d.isBefore(coachToday)) d = d.plusWeeks(1)
                        d
                    }
                    dm != null -> monthDay(coachToday, number(dm.groupValues[1]), number(dm.groupValues[2])) ?: return null
                    sl != null -> monthDay(coachToday, sl.groupValues[1].toInt(), sl.groupValues[2].toInt()) ?: return null
                    else -> { explicitDay = false; coachToday }
                }
            }
        }
        val time: LocalTime? = clock(body)
        if (time == null && !explicitDay) return null
        // A day without a time: 09:00 that day (the morning, after he is up).
        val at = date.atTime(time ?: DEFAULT_TIME)
        var z: ZonedDateTime = at.atZone(zone)
        // Hours before his day start belong to the coach day before them: 「明天凌晨兩點」 is the night after tomorrow's day.
        if (time != null && time.hour < dayStartHour) z = z.plusDays(1)
        if (!z.toInstant().isAfter(now)) {
            if (explicitDay && !body.contains("今天") && !body.contains("今晚")) return null
            z = z.plusDays(1)
        }
        return z.toInstant()
    }

    private fun monthDay(today: LocalDate, month: Int?, day: Int?): LocalDate? {
        if (month == null || day == null || month !in 1..12 || day !in 1..31) return null
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today)) runCatching { LocalDate.of(today.year + 1, month, day) }.getOrNull() else thisYear
    }

    private fun clock(body: String): LocalTime? {
        CLOCK.find(body)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return withPeriod(PERIOD.find(body)?.value, h, min)
        }
        val m = HOUR.find(body) ?: return null
        val h = number(m.groupValues[2]) ?: return null
        val tail = m.groupValues[3]
        val min = when {
            tail == "半" -> 30
            tail.isEmpty() -> 0
            else -> number(m.groupValues[4]) ?: return null
        }
        if (h !in 0..24 || min !in 0..59) return null
        return withPeriod(m.groupValues[1].ifEmpty { null } ?: PERIOD.find(body)?.value, h % 24, min)
    }

    private fun withPeriod(period: String?, h: Int, min: Int): LocalTime? {
        val hour = when (period) {
            "下午", "晚上", "傍晚" -> if (h in 1..11) h + 12 else h
            "中午" -> if (h in 1..2) h + 12 else h
            "半夜", "凌晨" -> if (h == 12) 0 else h
            else -> h
        }
        return if (hour in 0..23) LocalTime.of(hour, min) else null
    }

    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
}

/**
 * The memos (T12b): saved with his words, checked off, deleted with 復原, and 小葵's reminder when a time comes (once, also
 * after he signed out: he asked for it). Memos are not company rules and never change the plan.
 */
class MemoDesk(private val store: AgencyStore, private val zone: ZoneId, private val newId: () -> String) {
    suspend fun save(p: MemoParse, at: Long): Pair<Memo, String> {
        val memo = Memo(newId(), at, p.text, dueAt = p.dueAt?.toEpochMilli())
        store.memos.upsert(memo)
        val line = when {
            memo.dueAt != null -> "記下了：「${memo.text}」。${when_(memo.dueAt)}提醒你。"
            p.remind -> "記下了：「${memo.text}」。沒聽到時間，先放在備忘錄；要提醒就說「提醒我明天九點…」。"
            else -> "記下了：「${memo.text}」，放在備忘錄。"
        }
        return memo to line
    }

    /** 「12/3（三）09:00」. */
    fun when_(at: Long): String {
        val z = Instant.ofEpochMilli(at).atZone(zone)
        val wd = "一二三四五六日"[z.dayOfWeek.value - 1]
        return "%d/%d（%s）%02d:%02d ".format(z.monthValue, z.dayOfMonth, wd, z.hour, z.minute)
    }

    suspend fun list(): List<Memo> = store.memos.all()

    /** 打勾 (or taking it back). */
    suspend fun check(id: String, done: Boolean, at: Long): Boolean {
        val m = store.memos.get(id)?.takeIf { it.deletedAt == null } ?: return false
        store.memos.upsert(m.copy(doneAt = if (done) at else null))
        return true
    }

    suspend fun delete(id: String, at: Long): Boolean {
        val m = store.memos.get(id)?.takeIf { it.deletedAt == null } ?: return false
        store.memos.upsert(m.copy(deletedAt = at))
        return true
    }

    /** 復原 after a delete. */
    suspend fun restore(id: String): Boolean {
        val m = store.memos.get(id)?.takeIf { it.deletedAt != null } ?: return false
        store.memos.upsert(m.copy(deletedAt = null))
        return true
    }

    /** The memos whose time has come and that were not reminded yet, each marked reminded now; their posts. */
    suspend fun due(now: Long, riding: Boolean): List<Pair<Memo, Post>> = store.memos.all().filter { it.open && it.remindedAt == null && (it.dueAt ?: Long.MAX_VALUE) <= now }
        .map { m ->
            store.memos.upsert(m.copy(remindedAt = now))
            m to Post(KEY + m.id, NUDGE_PREFIX + m.id, reminder(m), if (riding) PostChannel.RIDING else PostChannel.SCHEDULE, alert = true)
        }

    /** The next memo time still to come. */
    suspend fun next(now: Long): Instant? = store.memos.all().filter { it.open && it.remindedAt == null }.mapNotNull { it.dueAt }
        .filter { it > now }.minOrNull()?.let(Instant::ofEpochMilli)

    companion object {
        const val KIND = "memo"
        const val KEY = "memo:"
        const val NUDGE_PREFIX = "memo:"
        fun reminder(m: Memo) = "小雨，你要我提醒的：「${m.text}」。"
    }
}
