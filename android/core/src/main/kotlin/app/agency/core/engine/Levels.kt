package app.agency.core.engine

import app.agency.core.model.Lesson
import app.agency.core.model.LessonCategory
import app.agency.core.schedule.LevelSpec
import app.agency.core.schedule.SubjectLevel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 每科等級 (T19e, owner 2026-11-23): every subject climbs 入門 → 基礎 → 中級 → 進階 → 專業, computed by code, never down. A level
 * goes up when, at this level: at least [MIN_DAYS] days (2 weeks), at least [MIN_LESSONS] lessons passed, the quizzes' first
 * rounds average ≥ [MIN_AVERAGE_PCT]%, every 實作 handed in (where the subject has one), and every 兩天後複習考 that is due passed
 * (≥ 2 of 3). A lesson passed in a week of three breaches (L26: 「這週等級進度不算」) does not count. The search words and the
 * quiz's difficulty follow the level.
 */
object Levels {
    val NAMES = listOf("入門", "基礎", "中級", "進階", "專業")
    val TOP = NAMES.lastIndex
    const val MIN_DAYS = 14L
    const val MIN_LESSONS = 3
    const val MIN_AVERAGE_PCT = 80
    const val REVIEW_PASS = 2

    fun name(level: Int): String = NAMES[level.coerceIn(0, TOP)]

    /** The words the video search adds for [level] (harder material as it rises). */
    fun queryWords(level: Int): String = when (level.coerceIn(0, TOP)) {
        0 -> "入門 初學"
        1 -> "基礎"
        2 -> "中級 技巧"
        3 -> "進階"
        else -> "專業 深入"
    }

    /** What the quiz maker is told: the level, and how hard the questions are. */
    fun quizLine(level: Int): String = when (level.coerceIn(0, TOP)) {
        0 -> "他在這一科是「入門」：問最重要的基本觀念。"
        1 -> "他在這一科是「基礎」：問基本觀念和做法的步驟。"
        2 -> "他在這一科是「中級」：問做法的細節和為什麼要這樣做。"
        3 -> "他在這一科是「進階」：問細節、常見錯誤和怎麼分辨。"
        else -> "他在這一科是「專業」：問最細的細節、例外情況和判斷。"
    }

    /** The lessons of [category] that count at [at] (passed on or after the level's day, outside [blockedWeeks]). */
    fun counted(category: LessonCategory, at: SubjectLevel, lessons: List<Lesson>, blockedWeeks: Set<LocalDate>): List<Lesson> =
        lessons.filter { l ->
            val day = l.passedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@filter false
            l.category == category && (at.since == null || !day.isBefore(LocalDate.parse(at.since))) &&
                day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) !in blockedWeeks
        }

    /** Whether [category] goes up a level on [today] (never past 專業). */
    fun ready(category: LessonCategory, at: SubjectLevel, lessons: List<Lesson>, today: LocalDate, blockedWeeks: Set<LocalDate> = emptySet()): Boolean {
        if (at.level >= TOP) return false
        val mine = counted(category, at, lessons, blockedWeeks)
        if (mine.size < MIN_LESSONS) return false
        val start = at.since?.let(LocalDate::parse) ?: mine.minOf { LocalDate.parse(it.passedDay!!) }
        if (ChronoUnit.DAYS.between(start, today) < MIN_DAYS) return false
        val scored = mine.mapNotNull { it.score }
        if (scored.isEmpty() || scored.sum() * 100 < MIN_AVERAGE_PCT * 3 * scored.size) return false
        if (category.practical != null && mine.any { it.practicalMediaId == null }) return false
        val dueReviews = mine.filter { l -> l.reviewDay?.let { !LocalDate.parse(it).isAfter(today) } == true }
        if (dueReviews.any { (it.reviewScore ?: -1) < REVIEW_PASS }) return false
        return true
    }

    /** The subjects that go up on [today], with their new level. */
    fun ups(spec: LevelSpec, lessons: List<Lesson>, today: LocalDate, blockedWeeks: Set<LocalDate> = emptySet()): Map<LessonCategory, Int> =
        LessonCategory.entries.filter { ready(it, spec.of(it.name), lessons, today, blockedWeeks) }.associateWith { spec.of(it.name).level + 1 }

    /** [spec] with [ups] applied from [today] (never lower). */
    fun apply(spec: LevelSpec, ups: Map<LessonCategory, Int>, today: LocalDate): LevelSpec =
        spec.copy(subjects = spec.subjects + ups.map { (c, l) -> c.name to SubjectLevel(maxOf(l, spec.of(c.name).level).coerceAtMost(TOP), today.toString()) })

    /** The ceremony line (code's words). */
    fun ceremony(category: LessonCategory, level: Int): String =
        "升級典禮：${category.zh}升到「${name(level)}」！兩週的課、考試、實作和複習考都過了。從今天起影片、考題和實作都變難。升級小卡貼上照片牆了。"
}
