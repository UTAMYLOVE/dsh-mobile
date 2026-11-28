package app.agency.core.lessons

import app.agency.core.store.Doc
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/** A subject's level index into [SubjectLevels.NAMES] and the ISO day it was reached ("" = not started). */
@Serializable
data class SubjectLevel(val level: Int = 0, val since: String = "")

/**
 * The `levels` document. [trainingOffsets] belongs to other packages (training progression); this package keeps the map as
 * it is when it writes the document back.
 */
@Serializable
data class Levels(
    val subjects: Map<String, SubjectLevel> = emptyMap(),
    val trainingOffsets: Map<String, Int> = emptyMap(),
)

/** One lesson entry with its decoded payload. */
internal data class Stored(val entry: Entry, val lesson: Lesson) {
    val id: Long get() = entry.id
}

internal object LessonStore {
    /** How far back lessons are looked up: far enough for the oldest lesson still waiting. */
    const val WINDOW_DAYS = 400L

    fun all(store: Store, upTo: LocalDate): List<Stored> =
        store.entries(upTo.minusDays(WINDOW_DAYS), upTo, Kind.LESSON).mapNotNull { e ->
            runCatching { Stored(e, e.read<Lesson>()) }.getOrNull()
        }

    fun get(store: Store, id: Long): Stored? {
        val e = store.entry(id) ?: return null
        if (e.kind != Kind.LESSON) return null
        return runCatching { Stored(e, e.read<Lesson>()) }.getOrNull()
    }

    fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/**
 * Per-subject level: 入門 → 基礎 → 中級 → 進階 → 專業, kept in doc `levels`. A level goes up when it has earned it and never
 * goes down. The level words shape the video search and the quiz difficulty.
 */
class SubjectLevels(private val store: Store) {
    fun levels(): Levels = store.readDoc<Levels>(Doc.LEVELS) ?: Levels()

    fun index(subject: String): Int = levels().subjects[subject]?.level?.coerceIn(0, NAMES.lastIndex) ?: 0

    fun level(subject: String): String = NAMES[index(subject)]

    /** Records the day a subject started, once, so the "14 days at the level" clock has a beginning. */
    fun ensureStarted(subject: String, day: LocalDate) {
        val l = levels()
        val cur = l.subjects[subject]
        if (cur != null && cur.since.isNotBlank()) return
        store.writeDoc(Doc.LEVELS, l.copy(subjects = l.subjects + (subject to SubjectLevel(cur?.level ?: 0, day.toString()))))
    }

    /**
     * Raises [subject] one level when it has earned it, and returns the new level name; null when nothing changed. Earned =
     * at least [MIN_DAYS] days at the level, average quiz score at least [MIN_AVG_PCT] percent, no review overdue, and every
     * lesson of the level (that was approved) is finished with its note.
     */
    fun levelUpIfEarned(subject: String, day: LocalDate): String? {
        val l = levels()
        val cur = l.subjects[subject] ?: SubjectLevel()
        val idx = cur.level.coerceIn(0, NAMES.lastIndex)
        if (idx >= NAMES.lastIndex) return null
        val mine = LessonStore.all(store, day).filter { it.lesson.subject == subject }
        val name = NAMES[idx]
        val atLevel = mine.filter {
            it.lesson.level == name && it.lesson.state != LessonState.PROPOSED &&
                it.lesson.state != LessonState.DECLINED && it.lesson.state != LessonState.WISH
        }
        val since = LessonStore.date(cur.since) ?: atLevel.minOfOrNull { it.entry.day } ?: return null
        if (ChronoUnit.DAYS.between(since, day) < MIN_DAYS) return null
        if (atLevel.isEmpty() || !atLevel.all { it.lesson.finished }) return null
        val scored = atLevel.filter { it.lesson.score != null && it.lesson.total > 0 }
        if (scored.isEmpty()) return null
        val avg = scored.map { it.lesson.score!! * 100.0 / it.lesson.total }.average()
        if (avg < MIN_AVG_PCT) return null
        val overdue = mine.any { s ->
            s.lesson.reviewOn?.let { LessonStore.date(it) }?.isBefore(day) == true &&
                s.lesson.state != LessonState.REVIEWED
        }
        if (overdue) return null
        store.writeDoc(
            Doc.LEVELS,
            l.copy(subjects = l.subjects + (subject to SubjectLevel(idx + 1, day.toString()))),
        )
        return NAMES[idx + 1]
    }

    /** The level words for a video search query of [subject], e.g. 「入門 新手 教學」. */
    fun searchWords(subject: String): String = WORDS[index(subject)]

    /** The whole query: subject plus level words. */
    fun searchQuery(subject: String): String = "$subject ${searchWords(subject)}"

    companion object {
        val NAMES: List<String> = listOf("入門", "基礎", "中級", "進階", "專業")
        private val WORDS = listOf("入門 新手 教學", "基礎 練習 教學", "中級 實戰 技巧", "進階 深入 技巧", "專業 大師課")
        const val MIN_DAYS = 14L
        const val MIN_AVG_PCT = 80

    }
}
