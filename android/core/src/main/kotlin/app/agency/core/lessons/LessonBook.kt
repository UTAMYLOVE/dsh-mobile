package app.agency.core.lessons

import app.agency.core.agent.Hit
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.rules.Rules
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.add
import app.agency.core.store.update
import java.time.Instant
import java.time.LocalDate

/**
 * The lesson lifecycle, one small operation per step over the store. Every operation checks the state it needs and refuses
 * (never throws) when the lesson is somewhere else: a lesson cannot be passed before it was watched, noted before it was
 * passed, and so on. Grading is code. `PROPOSED → APPROVED → WATCHED → PASSED → NOTED → REVIEW_DUE → REVIEWED`.
 */
class LessonBook(private val store: Store, private val clock: Clock, private val rules: Rules) {
    private val levels = SubjectLevels(store)

    private fun today(): LocalDate = Days.today(clock)

    fun get(id: Long): Lesson? = LessonStore.get(store, id)?.lesson

    /** A video 小葵 found goes in as PROPOSED. A wish for the same subject is used up by it and makes the lesson an extra. */
    fun propose(hit: Hit, subject: String, extra: Boolean = false): LessonResult {
        if (hit.title.isBlank() || hit.url.isBlank() || subject.isBlank()) return refuse(Refusal.BAD_HIT)
        val day = today()
        val all = LessonStore.all(store, day)
        if (all.any { it.lesson.url == hit.url && it.lesson.state != LessonState.DECLINED && it.lesson.state != LessonState.WISH }) {
            return refuse(Refusal.DUPLICATE)
        }
        val wish = all.firstOrNull { it.lesson.state == LessonState.WISH && it.lesson.subject == subject }
        if (wish != null) store.remove(wish.id)
        levels.ensureStarted(subject, day)
        val lesson = Lesson(
            subject = subject,
            title = hit.title.trim(),
            url = hit.url.trim(),
            material = hit.content.ifBlank { hit.summary },
            level = levels.level(subject),
            extra = extra || wish != null,
        )
        val e = store.add(day, clock.now(), Kind.LESSON, lesson)
        return LessonResult.Ok(e.id, lesson)
    }

    fun approve(id: Long): LessonResult = change(id, setOf(LessonState.PROPOSED)) {
        it.copy(state = LessonState.APPROVED, approvedOn = today().toString())
    }

    fun decline(id: Long): LessonResult = change(id, setOf(LessonState.PROPOSED)) { it.copy(state = LessonState.DECLINED) }

    /** He tapped 「去看」: records when, so `remind` can ask after him if he does not come back. */
    fun opened(id: Long): LessonResult = change(id, setOf(LessonState.APPROVED, LessonState.WATCHED)) {
        it.copy(openedAt = clock.now().toString(), returned = false)
    }

    /** He is back in the app. */
    fun returned(id: Long): LessonResult = change(id, setOf(LessonState.APPROVED, LessonState.WATCHED)) { it.copy(returned = true) }

    /** The lesson he opened and has not come back from, if any (the newest one). */
    fun away(): LessonAway? =
        LessonStore.all(store, today()).filter { it.lesson.active && it.lesson.openedAt != null && !it.lesson.returned }
            .mapNotNull { s -> runCatching { Instant.parse(s.lesson.openedAt) }.getOrNull()?.let { s to it } }
            .maxByOrNull { it.second }
            ?.let { (s, at) -> LessonAway(s.id, s.lesson.title, at, s.lesson.minutes) }

    fun watched(id: Long): LessonResult {
        val s = LessonStore.get(store, id) ?: return refuse(Refusal.NOT_FOUND)
        if (s.lesson.state != LessonState.APPROVED) return refuse(Refusal.WRONG_STATE)
        if (s.lesson.openedAt == null) return refuse(Refusal.NOT_OPENED)
        return save(s, s.lesson.copy(state = LessonState.WATCHED, returned = true))
    }

    /** Stores a quiz made from the lesson's own material; an invalid one is refused. */
    fun setQuiz(id: Long, quiz: Quiz): LessonResult {
        val s = LessonStore.get(store, id) ?: return refuse(Refusal.NOT_FOUND)
        if (s.lesson.state != LessonState.WATCHED) return refuse(Refusal.WRONG_STATE)
        if (!QuizCheck.valid(quiz, s.lesson.material, rules.study)) return refuse(Refusal.BAD_QUIZ)
        return save(s, s.lesson.copy(quiz = quiz, answers = null, notesOnly = false))
    }

    /** No valid quiz could be made: he writes notes straight after watching. */
    fun notesOnly(id: Long): LessonResult = change(id, setOf(LessonState.WATCHED)) { it.copy(quiz = null, notesOnly = true) }

    /**
     * Grades [answers] (one index per question) by code. Pass = `quizPass` right: the lesson becomes PASSED. A fail adds an
     * attempt and clears the quiz so a new one is made.
     */
    fun answer(id: Long, answers: List<Int>): QuizResult {
        val s = LessonStore.get(store, id) ?: return QuizResult.refused(Refusal.NOT_FOUND)
        val l = s.lesson
        if (l.state != LessonState.WATCHED) return QuizResult.refused(Refusal.WRONG_STATE)
        val quiz = l.quiz ?: return QuizResult.refused(Refusal.NO_QUIZ)
        if (answers.size != quiz.questions.size) return QuizResult.refused(Refusal.BAD_ANSWERS)
        val total = quiz.questions.size
        val right = QuizCheck.grade(quiz, answers)
        val passed = right >= QuizCheck.passMark(rules.study, total)
        val next = if (passed) {
            l.copy(state = LessonState.PASSED, answers = answers, score = right, total = total, attempts = l.attempts + 1,
                passedOn = today().toString())
        } else {
            l.copy(quiz = null, answers = answers, score = right, total = total, attempts = l.attempts + 1)
        }
        save(s, next)
        return QuizResult(right, total, passed)
    }

    /** The note that finishes a lesson (at least ten characters): NOTED, and the review is set for later. */
    fun note(id: Long, text: String): LessonResult {
        val s = LessonStore.get(store, id) ?: return refuse(Refusal.NOT_FOUND)
        val l = s.lesson
        val allowed = l.state == LessonState.PASSED || (l.state == LessonState.WATCHED && l.notesOnly)
        if (!allowed) return refuse(Refusal.WRONG_STATE)
        val t = text.trim()
        if (t.length < MIN_NOTE) return refuse(Refusal.NOTE_TOO_SHORT)
        val day = today()
        val passed = LessonStore.date(l.passedOn) ?: day
        val review = if (l.notesOnly) null else passed.plusDays(rules.study.reviewAfterDays.coerceAtLeast(1).toLong()).toString()
        return save(s, l.copy(state = LessonState.NOTED, note = t, passedOn = passed.toString(), notedOn = day.toString(), reviewOn = review))
    }

    /** Lessons whose review is due on [day]; the ones still NOTED become REVIEW_DUE (the one place that promotes them). */
    fun reviewDue(day: LocalDate): List<Pair<Long, Lesson>> =
        LessonStore.all(store, day).filter { s ->
            (s.lesson.state == LessonState.NOTED || s.lesson.state == LessonState.REVIEW_DUE) &&
                LessonStore.date(s.lesson.reviewOn)?.let { !it.isAfter(day) } == true
        }.sortedWith(compareBy({ it.lesson.reviewOn }, { it.id })).map { s ->
            if (s.lesson.state == LessonState.NOTED) {
                val up = s.lesson.copy(state = LessonState.REVIEW_DUE)
                store.update(s.id, up)
                s.id to up
            } else {
                s.id to s.lesson
            }
        }

    /** A fresh set of questions for the review (optional; without it the original quiz is asked again). */
    fun setReviewQuiz(id: Long, quiz: Quiz): LessonResult {
        val s = LessonStore.get(store, id) ?: return refuse(Refusal.NOT_FOUND)
        if (s.lesson.state != LessonState.NOTED && s.lesson.state != LessonState.REVIEW_DUE) return refuse(Refusal.WRONG_STATE)
        if (!QuizCheck.valid(quiz, s.lesson.material, rules.study)) return refuse(Refusal.BAD_QUIZ)
        return save(s, s.lesson.copy(reviewQuiz = quiz))
    }

    /** Grades a review. Passing makes the lesson REVIEWED; failing keeps it due. */
    fun answerReview(id: Long, answers: List<Int>): QuizResult {
        val s = LessonStore.get(store, id) ?: return QuizResult.refused(Refusal.NOT_FOUND)
        val l = s.lesson
        if (l.state != LessonState.NOTED && l.state != LessonState.REVIEW_DUE) return QuizResult.refused(Refusal.WRONG_STATE)
        val day = today()
        val due = LessonStore.date(l.reviewOn) ?: return QuizResult.refused(Refusal.NOT_DUE)
        if (due.isAfter(day)) return QuizResult.refused(Refusal.NOT_DUE)
        val quiz = l.reviewQuiz ?: l.quiz ?: return QuizResult.refused(Refusal.NO_QUIZ)
        if (answers.size != quiz.questions.size) return QuizResult.refused(Refusal.BAD_ANSWERS)
        val total = quiz.questions.size
        val right = QuizCheck.grade(quiz, answers)
        val passed = right >= QuizCheck.passMark(rules.study, total)
        val next = if (passed) {
            l.copy(state = LessonState.REVIEWED, reviewScore = right, reviewedOn = day.toString())
        } else {
            l.copy(state = LessonState.REVIEW_DUE, reviewScore = right)
        }
        save(s, next)
        return QuizResult(right, total, passed)
    }

    /** He wants to learn [topic]: kept so the day picks it up as its one extra slot (when the rules allow one). */
    fun wish(topic: String): LessonResult {
        val t = topic.trim()
        if (t.isEmpty()) return refuse(Refusal.EMPTY)
        val day = today()
        LessonStore.all(store, day).firstOrNull { it.lesson.state == LessonState.WISH && it.lesson.subject == t }
            ?.let { return LessonResult.Ok(it.id, it.lesson) }
        val lesson = Lesson(subject = t, title = t, url = "", state = LessonState.WISH, extra = true)
        val e: Entry = store.add(day, clock.now(), Kind.LESSON, lesson)
        return LessonResult.Ok(e.id, lesson)
    }

    private fun change(id: Long, from: Set<LessonState>, f: (Lesson) -> Lesson): LessonResult {
        val s = LessonStore.get(store, id) ?: return refuse(Refusal.NOT_FOUND)
        if (s.lesson.state !in from) return refuse(Refusal.WRONG_STATE)
        return save(s, f(s.lesson))
    }

    private fun save(s: Stored, next: Lesson): LessonResult {
        store.update(s.id, next)
        return LessonResult.Ok(s.id, next)
    }

    private fun refuse(why: Refusal): LessonResult = LessonResult.Refused(why)

    companion object {
        const val MIN_NOTE = 10
    }
}
