package app.agency.core.lessons

import app.agency.core.plan.LessonSlot
import app.agency.core.rules.Rules
import app.agency.core.store.Store
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Today's lesson slots, from the study rules and what is in the store. Pure: it reads and never writes.
 *
 * Order and numbering (`n`, 1-based, stable through the day): the scheduled subjects first (the daily one, then the
 * twice-weekly ones still owed this calendar week, then the one rotating subject of the day), then reviews due today, then the extras he asked for.
 * A subject keeps its slot after its lesson was finished today, shown as done.
 */
object LessonDay {
    fun of(day: LocalDate, store: Store, rules: Rules, examWeek: Boolean, levels: Levels = Levels()): List<LessonSlot> {
        val study = rules.study
        val blank = study.daily.isBlank() && study.twiceWeekly.isEmpty() && study.rotation.isEmpty()
        val daily = if (blank) Subjects.all.first() else study.daily
        val twice = if (blank) emptyList() else study.twiceWeekly
        val rotation = if (blank) Subjects.all.drop(1) else study.rotation
        val maxPerDay = if (blank) 1 else study.maxPerDay.coerceAtLeast(1)
        val extraMax = if (blank) 1 else study.extraPerDay.coerceAtLeast(0)
        val cap = if (examWeek) study.examWeekPerDay.coerceAtLeast(0) else maxPerDay

        val lessons = LessonStore.all(store, day)
        val regular = lessons.filter { !it.lesson.extra }
        val dayText = day.toString()

        // The subjects scheduled for today, in priority order, cut to the day's limit.
        val subjects = ArrayList<String>()
        if (daily.isNotBlank()) subjects += daily
        val weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val daysLeft = 7 - day.dayOfWeek.value + 1
        twice.filter { it.isNotBlank() && it !in subjects }.distinct().forEachIndexed { i, s ->
            val doneBefore = regular.count {
                it.lesson.subject == s && it.lesson.finished &&
                    LessonStore.date(it.lesson.notedOn)?.let { d -> !d.isBefore(weekStart) && d.isBefore(day) } == true
            }
            val owed = (2 - doneBefore).coerceAtLeast(0)
            val base = 1 + i % 3
            val preferred = day.dayOfWeek.value == base || day.dayOfWeek.value == base + 3
            val doneToday = regular.any { it.lesson.subject == s && it.lesson.notedOn == dayText }
            if (doneToday || (owed > 0 && (preferred || owed >= daysLeft))) subjects += s
        }
        val rot = rotation.filter { it.isNotBlank() && it !in subjects && it !in twice }.distinct()
        if (rot.isNotEmpty()) {
            val start = Math.floorMod(day.toEpochDay(), rot.size.toLong()).toInt()
            subjects += rot[start]
        }

        val slots = ArrayList<LessonSlot>()
        fun add(subject: String, id: Long?, title: String, done: Boolean, review: Boolean = false, proposed: Boolean = false) {
            slots += LessonSlot(slots.size + 1, subject, id, title, done, review, proposed)
        }

        for (s in subjects.take(cap)) {
            val mine = regular.filter { it.lesson.subject == s }
            val finishedToday = mine.firstOrNull { it.lesson.finished && it.lesson.notedOn == dayText }
            val active = mine.filter { it.lesson.active }.minWithOrNull(compareBy({ it.entry.day }, { it.id }))
            val proposed = mine.firstOrNull { it.lesson.state == LessonState.PROPOSED }
            when {
                finishedToday != null -> add(s, finishedToday.id, finishedToday.lesson.title, true)
                active != null -> add(s, active.id, active.lesson.title, false)
                proposed != null -> add(s, null, "$s：${proposed.lesson.title}（等你決定）", false, proposed = true)
                else -> add(s, null, "$s（${levelName(levels, s)}）：請小葵找影片", false)
            }
        }

        // Reviews due today (and those finished today stay as done slots).
        lessons.filter { s ->
            (s.lesson.state == LessonState.NOTED || s.lesson.state == LessonState.REVIEW_DUE) &&
                LessonStore.date(s.lesson.reviewOn)?.let { !it.isAfter(day) } == true
        }.sortedWith(compareBy({ it.lesson.reviewOn }, { it.id })).forEach {
            add(it.lesson.subject, it.id, "複習：${it.lesson.title}", false, review = true)
        }
        lessons.filter { it.lesson.state == LessonState.REVIEWED && it.lesson.reviewedOn == dayText }.forEach {
            add(it.lesson.subject, it.id, "複習：${it.lesson.title}", true, review = true)
        }

        // Extras on request: lessons taken as extras, then wishes, at most extraPerDay.
        val extras = ArrayList<Triple<String, Long?, Pair<String, Boolean>>>()
        val proposedExtras = HashSet<String>()
        val ex = lessons.filter { it.lesson.extra }
        ex.filter { it.lesson.finished && it.lesson.notedOn == dayText }.forEach {
            extras += Triple(it.lesson.subject, it.id, it.lesson.title to true)
        }
        ex.filter { it.lesson.active }.sortedBy { it.id }.forEach {
            extras += Triple(it.lesson.subject, it.id, it.lesson.title to false)
        }
        ex.filter { it.lesson.state == LessonState.PROPOSED }.sortedBy { it.id }.forEach {
            extras += Triple(it.lesson.subject, null, "${it.lesson.subject}：${it.lesson.title}（等你決定）" to false)
            proposedExtras += it.lesson.subject
        }
        ex.filter { it.lesson.state == LessonState.WISH }.sortedBy { it.id }.forEach {
            extras += Triple(it.lesson.subject, null, "${it.lesson.subject}（想學）：請小葵找影片" to false)
        }
        extras.take(extraMax).forEach { (subject, id, td) ->
            add(subject, id, td.first, td.second, proposed = id == null && subject in proposedExtras && td.first.endsWith("（等你決定）"))
        }
        return slots
    }

    private fun levelName(levels: Levels, subject: String): String =
        SubjectLevels.NAMES[(levels.subjects[subject]?.level ?: 0).coerceIn(0, SubjectLevels.NAMES.lastIndex)]
}
