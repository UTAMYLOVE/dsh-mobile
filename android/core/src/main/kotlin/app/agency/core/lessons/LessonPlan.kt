package app.agency.core.lessons

import app.agency.core.model.Lesson
import app.agency.core.model.LessonCategory
import app.agency.core.model.LessonStatus
import app.agency.core.model.StudyLanguage

/** Where a lesson of 「課程清單」 is (T19c): 待看, 先寫三個重點, 待考, 寫筆記, 通過. */
enum class LessonStage(val zh: String) {
    TO_WATCH("待看"),
    KEY_POINTS("先寫三個重點"),
    TO_QUIZ("待考"),
    NOTE("寫筆記"),
    PASSED("通過"),
}

/**
 * 理論課 rules (T19c, plan v3 §8), pure. Every day [DAILY] lessons are owed (the 理論課 slot is done at two; a third and more
 * whenever he wants); the next one opens only after the one before passed its quiz and has his note. A lesson whose 教材 (the
 * stored search text) has fewer than [THIN_HAN] Han characters asks for [POINTS] key points in his own words first, each at
 * least [POINT_HAN] of them; those become the 教材. A note needs at least [NOTE_HAN] Han characters. [REVIEW_AFTER_DAYS] after
 * it passed comes the 複習考 (recorded only).
 */
object LessonPlan {
    const val DAILY = 2

    /** 「這週考試」: one lesson a day that week, at most twice a semester (owner 2026-11-26). */
    const val EXAM_DAILY = 1
    const val EXAM_WEEKS_PER_TERM = 2

    /** The semester of [day]: February to July, or August to January (the first and last day). */
    fun semester(day: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate> {
        val y = day.year
        return when (day.monthValue) {
            in 2..7 -> java.time.LocalDate.of(y, 2, 1) to java.time.LocalDate.of(y, 7, 31)
            1 -> java.time.LocalDate.of(y - 1, 8, 1) to java.time.LocalDate.of(y, 1, 31)
            else -> java.time.LocalDate.of(y, 8, 1) to java.time.LocalDate.of(y + 1, 1, 31)
        }
    }
    const val DAILY_SHOWN = 3
    const val THIN_HAN = 200
    const val NOTE_HAN = 30
    const val POINTS = 3
    const val POINT_HAN = 10
    const val REVIEW_AFTER_DAYS = 2L
    const val QUESTIONS = 3
    const val SPARES = 3
    const val PASS = 2

    /** Fewer listed lessons waiting than this: the manager asks him to add more (plan v3 §8 「清單快沒影片時提醒我補」). */
    const val LOW_LIST = 3

    /** How much of a search result's text is kept as 教材. */
    const val SOURCE_CHARS = 4000

    /** The 理論課 slot's latest time: class days in class (classes end 17:00, Fridays 16:00); no-class days before the walk. */
    const val CLASS_DUE = "16:00"
    const val FRIDAY_DUE = "15:30"
    const val NO_CLASS_DUE = "16:00"

    /** The 複習考's time: in class on a class day, after the first meal on a no-class day. */
    const val REVIEW_CLASS = "10:00"
    const val REVIEW_NO_CLASS = "14:00"

    /** The language studied now. One at a time, in [StudyLanguage]'s order; moving on is decided by stage 4's evaluation. */
    fun language(): StudyLanguage = StudyLanguage.entries.first()

    /** Han characters in [s] (the CJK ideograph blocks), the unit every length rule of a lesson counts. */
    fun han(s: String): Int = s.codePoints().filter { cp ->
        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN && Character.isIdeographic(cp)
    }.count().toInt()

    fun thin(source: String): Boolean = han(source) < THIN_HAN

    fun stage(l: Lesson): LessonStage = when {
        l.passed -> LessonStage.PASSED
        l.watchedAt == null -> LessonStage.TO_WATCH
        l.quizPassedAt == null && (thin(l.source) || l.fallback) && l.keyPoints == null -> LessonStage.KEY_POINTS
        l.quizPassedAt == null -> LessonStage.TO_QUIZ
        else -> LessonStage.NOTE
    }

    /** What the quiz is made from: his key points when the search text is too thin (or the quiz could not be made), else that. */
    fun material(l: Lesson): String = l.keyPoints?.takeIf { thin(l.source) || l.fallback } ?: l.source

    /** The 複習考's material: the same 教材 and his note. */
    fun reviewMaterial(l: Lesson): String = material(l) + (l.note?.let { "\n$it" } ?: "")

    /** 「課程清單」 still to pass, in the order he said 好 to them. */
    fun queue(all: List<Lesson>): List<Lesson> =
        all.filter { it.status == LessonStatus.LISTED && !it.passed }.sortedWith(compareBy({ it.listedAt ?: 0L }, { it.id }))

    /** The one lesson open now (the next waits until it passes), or null when the list is empty. */
    fun current(all: List<Lesson>): Lesson? = queue(all).firstOrNull()

    /** The course has started on or before [dayEndAt]: he said 好 to a video by then. */
    fun started(all: List<Lesson>, dayEndAt: Long): Boolean = all.any { it.listedAt != null && it.listedAt < dayEndAt }

    /**
     * The category to search next when he does not name one: the one with the fewest videos so far (found or listed, dropped
     * ones not counted), in the order of [LessonCategory], so every category comes round.
     */
    fun nextCategory(all: List<Lesson>): LessonCategory {
        val counts = all.filter { it.status != LessonStatus.DROPPED }.groupingBy { it.category }.eachCount()
        return LessonCategory.entries.minBy { counts[it] ?: 0 }
    }

    /** The words code searches with: the category's words (the current language first for 外語), and 影片. */
    fun query(category: LessonCategory, language: StudyLanguage? = null): String =
        listOfNotNull(language?.zh?.takeIf { category == LessonCategory.LANGUAGE }, category.query, "影片").joinToString(" ")

    /** A category named in his words (「找聲樂影片」), or null. */
    fun categoryIn(text: String): LessonCategory? = LessonCategory.entries.firstOrNull { text.contains(it.zh) }
        ?: ALIASES.entries.firstOrNull { (w, _) -> text.contains(w) }?.value

    private val ALIASES = mapOf(
        "唱歌" to LessonCategory.VOCAL, "跳舞" to LessonCategory.DANCE, "英文" to LessonCategory.LANGUAGE, "韓文" to LessonCategory.LANGUAGE,
        "日文" to LessonCategory.LANGUAGE, "語言" to LessonCategory.LANGUAGE, "表情" to LessonCategory.EXPRESSION, "鏡頭" to LessonCategory.CAMERA,
        "演戲" to LessonCategory.ACTING, "綜藝" to LessonCategory.INTERVIEW, "受訪" to LessonCategory.INTERVIEW, "化妝" to LessonCategory.STYLING,
        "造型" to LessonCategory.STYLING, "作曲" to LessonCategory.SONGWRITING, "作詞" to LessonCategory.SONGWRITING, "樂器" to LessonCategory.SONGWRITING,
        "社交" to LessonCategory.SOCIAL, "聊天" to LessonCategory.SOCIAL, "保養" to LessonCategory.SKIN, "皮膚" to LessonCategory.SKIN,
        "穿搭" to LessonCategory.OUTFIT, "髮型" to LessonCategory.OUTFIT, "心理" to LessonCategory.MIND, "料理" to LessonCategory.COOKING,
        "煮飯" to LessonCategory.COOKING, "體態" to LessonCategory.BODY,
    )

    /** His three key points: each at least [POINT_HAN] Han characters. The problem as he reads it, or null. */
    fun pointsProblem(points: List<String>): String? {
        val p = points.map { it.trim() }.filter { it.isNotEmpty() }
        if (p.size < POINTS) return "要寫三個重點，每個一行。"
        val short = p.take(POINTS).indexOfFirst { han(it) < POINT_HAN }
        if (short >= 0) return "第 ${short + 1} 個重點太短，每個至少 $POINT_HAN 個字，用自己的話寫。"
        return null
    }

    /** His note: at least [NOTE_HAN] Han characters. The problem as he reads it, or null. */
    fun noteProblem(note: String): String? {
        val n = han(note)
        return if (n < NOTE_HAN) "筆記至少 $NOTE_HAN 個字，現在 $n 個。寫你記住了什麼、要怎麼用。" else null
    }
}
