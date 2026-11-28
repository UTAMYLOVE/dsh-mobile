package app.agency.core.lessons

import app.agency.core.agent.Hit
import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import app.agency.core.store.MemStore
import java.time.LocalDate

/** A store, a clock and a book wired together, with helpers that walk a lesson through its life. */
internal class LessonRig(val rules: Rules = Fixtures.rules(), val clock: TestClock = TestClock()) {
    val store = MemStore()
    val book = LessonBook(store, clock, rules)
    val levels = SubjectLevels(store)

    fun day(): LocalDate = Days.today(clock)

    fun goTo(day: LocalDate, time: String = "09:00") = clock.set(day, time)

    fun hit(n: Int, content: String = MATERIAL) = Hit("影片$n", "https://example.test/v$n", "簡介", content)

    fun id(r: LessonResult): Long = (r as LessonResult.Ok).id

    fun propose(n: Int, subject: String): Long = id(book.propose(hit(n), subject))

    fun approved(n: Int, subject: String): Long = propose(n, subject).also { book.approve(it) }

    fun watched(n: Int, subject: String): Long = approved(n, subject).also { book.opened(it); book.watched(it) }

    /** Walks a lesson to PASSED with [right] correct answers out of three (needs at least two to pass). */
    fun passed(n: Int, subject: String, right: Int = 3): Long {
        val id = watched(n, subject)
        book.setQuiz(id, QUIZ)
        book.answer(id, answersWith(right))
        return id
    }

    fun noted(n: Int, subject: String, right: Int = 3): Long = passed(n, subject, right).also { book.note(it, NOTE) }

    fun lesson(id: Long): Lesson = book.get(id)!!

    companion object {
        const val MATERIAL = "語言學習最重要的是每天持續練習，不要一次練太久。\n發音要先聽再模仿，慢慢地把每個音節說清楚。\n" +
            "學新單字時要放進完整的句子裡，才容易記住。"
        const val NOTE = "今天學到要每天練習，也要把單字放進句子。"

        val QUIZ = Quiz(
            listOf(
                Question("學習語言最重要的是什麼？", listOf("每天持續練習", "一次練很久", "只看不說", "等有空再說"), 0, "語言學習最重要的是每天持續練習"),
                Question("發音應該怎麼練？", listOf("先聽再模仿", "直接背字母", "不用開口", "越快越好"), 0, "發音要先聽再模仿"),
                Question("新單字要怎麼記？", listOf("單獨抄十遍", "放進完整句子", "只背中文", "不用複習"), 1, "學新單字時要放進完整的句子裡"),
            ),
        )

        /** Answers for [QUIZ] with exactly [right] correct (the first ones are right). */
        fun answersWith(right: Int): List<Int> = QUIZ.questions.mapIndexed { i, q -> if (i < right) q.answer else (q.answer + 1) % 4 }
    }
}
