package app.agency.core.stage

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.lessons.Lesson
import app.agency.core.lessons.LessonState
import app.agency.core.rules.LevelRule
import app.agency.core.rules.Rules
import app.agency.core.rules.StudyRule
import app.agency.core.store.DayClose
import app.agency.core.store.Doc
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.Message
import app.agency.core.store.RealTask
import app.agency.core.store.Role
import app.agency.core.store.Violation
import app.agency.core.store.add
import app.agency.core.store.writeDoc
import app.agency.core.agent.Card
import java.time.LocalDate
import java.time.LocalTime

/**
 * A store, a clock and rules wired together for round tests. The default rules open only lessons (one subject a day), so a
 * "perfect day" is one noted lesson; everything here is invented data.
 */
internal class StageRig(
    val subjects: List<String> = listOf("語言"),
    val level: LevelRule = LevelRule(),
    extra: (Rules) -> Rules = { it },
) {
    val store = MemStore()
    val clock = TestClock()
    val rules: Rules = extra(
        Rules(
            study = StudyRule(daily = subjects.first(), rotation = subjects.drop(1), maxPerDay = subjects.size),
            level = level,
        ),
    )
    val monday: LocalDate = TestClock.MONDAY

    fun day(n: Int): LocalDate = monday.plusDays((n - 1).toLong())

    fun goTo(day: LocalDate, time: String = "07:00") = clock.set(day, time)

    fun join(): StageState = Stages.join(store, monday)

    fun stage(): StageState = Stages.get(store)!!

    fun setStage(s: StageState) = store.writeDoc(Doc.STAGE, s)

    /** Notes the lesson of [subject] on [day]: that slot is done. */
    fun note(day: LocalDate, subject: String = subjects.first()): Entry = store.add(
        day, Days.at(day, LocalTime.of(20, 0), clock.zone), Kind.LESSON,
        Lesson(subject = subject, title = "課", url = "https://example.test/v", state = LessonState.NOTED, notedOn = day.toString()),
    )

    /** Every slot of [day] done. */
    fun perfect(day: LocalDate) = subjects.forEach { note(day, it) }

    fun perfectDays(from: Int, to: Int) = (from..to).forEach { perfect(day(it)) }

    fun violation(day: LocalDate, code: String = "OVER_KCAL") =
        store.add(day, Days.at(day, LocalTime.of(21, 0), clock.zone), Kind.VIOLATION, Violation(code))

    fun realTask(day: LocalDate, done: Boolean = true) =
        store.add(day, Days.at(day, LocalTime.of(19, 0), clock.zone), Kind.REAL_TASK, RealTask("和認識的人吃一頓飯", done))

    fun run(day: LocalDate, time: String = "07:00"): RolloverResult {
        goTo(day, time)
        return Rollover.run(store, clock, rules)
    }

    fun closes(): List<DayClose> = store.entries(monday, monday.plusDays(400), Kind.DAY_CLOSE).map { it.read<DayClose>() }

    fun notes(): List<Message> = store.messages(500).filter { it.role == Role.NOTE }

    fun ceremonies(kind: String): List<Card.Ceremony> =
        notes().flatMap { it.cards }.filterIsInstance<Card.Ceremony>().filter { it.kind == kind }

    fun summaries(): List<Card.Summary> = notes().flatMap { it.cards }.filterIsInstance<Card.Summary>()
}
