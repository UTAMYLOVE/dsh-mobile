package app.agency.core.lessons

import app.agency.core.base.TestClock
import app.agency.core.rules.StudyRule
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonDayTest {
    private val monday: LocalDate = TestClock.MONDAY
    private val rotation = listOf("禮儀", "理財", "保養")

    private fun rotating(day: LocalDate) = rotation[Math.floorMod(day.toEpochDay(), 3L).toInt()]

    private fun slots(rig: LessonRig, day: LocalDate, exam: Boolean = false) =
        LessonDay.of(day, rig.store, rig.rules, exam, rig.levels.levels())

    @Test fun aClassWeekHasDailyTwiceWeeklyAndRotation() {
        val rig = LessonRig()
        val expected = mapOf(
            0 to listOf("語言", "聲樂", rotating(monday)),
            1 to listOf("語言", "舞蹈", rotating(monday.plusDays(1))),
            2 to listOf("語言", rotating(monday.plusDays(2))),
            3 to listOf("語言", "聲樂", rotating(monday.plusDays(3))),
            4 to listOf("語言", "舞蹈", rotating(monday.plusDays(4))),
        )
        for ((offset, subjects) in expected) {
            val s = slots(rig, monday.plusDays(offset.toLong()))
            assertEquals("day $offset", subjects, s.map { it.subject })
            assertEquals((1..s.size).toList(), s.map { it.n })
            assertTrue(s.all { it.lessonId == null && !it.done && !it.review })
        }
    }

    @Test fun whatTheWeekStillOwesIsForcedAtTheWeekend() {
        val rig = LessonRig()
        assertEquals(listOf("語言", "聲樂", "舞蹈"), slots(rig, monday.plusDays(5)).map { it.subject })
        assertEquals(listOf("語言", "聲樂", "舞蹈"), slots(rig, monday.plusDays(6)).map { it.subject })
    }

    @Test fun twiceWeeklyLessonsAlreadyDoneAreNotOwedAgain() {
        val rig = LessonRig()
        rig.noted(1, "聲樂")
        rig.goTo(monday.plusDays(3))
        rig.noted(2, "聲樂")
        val sat = slots(rig, monday.plusDays(5)).filter { !it.review }.map { it.subject }
        assertFalse("聲樂" in sat)
        assertTrue("舞蹈" in sat)
        // and on Thursday itself the finished lesson stays as a done slot
        val thu = slots(rig, monday.plusDays(3))
        assertTrue(thu.first { it.subject == "聲樂" }.done)
    }

    @Test fun aSlotTakesTheOldestUsableLessonOfItsSubject() {
        val rig = LessonRig()
        val first = rig.approved(1, "語言")
        rig.approved(2, "語言")
        rig.propose(3, "語言")
        val s = slots(rig, monday).first { it.subject == "語言" }
        assertEquals(first, s.lessonId)
        assertEquals("影片1", s.title)
        assertFalse(s.done)
    }

    @Test fun aProposalAloneLeavesTheSlotEmptyButSaysSo() {
        val rig = LessonRig()
        rig.propose(1, "語言")
        val s = slots(rig, monday).first { it.subject == "語言" }
        assertNull(s.lessonId)
        assertTrue(s.title.contains("等你決定"))
    }

    @Test fun watchedAndPassedLessonsStillFillTheirSlot() {
        val rig = LessonRig()
        val w = rig.watched(1, "語言")
        assertEquals(w, slots(rig, monday).first { it.subject == "語言" }.lessonId)
        val rig2 = LessonRig()
        val p = rig2.passed(1, "語言")
        val s = slots(rig2, monday).first { it.subject == "語言" }
        assertEquals(p, s.lessonId)
        assertFalse(s.done)
    }

    @Test fun aSlotIsDoneWhenItsLessonIsNoted() {
        val rig = LessonRig()
        val id = rig.noted(1, "語言")
        val s = slots(rig, monday).first { it.subject == "語言" }
        assertTrue(s.done)
        assertEquals(id, s.lessonId)
    }

    @Test fun examWeekKeepsOnlyTheDailySubject() {
        val rig = LessonRig()
        val s = slots(rig, monday, exam = true)
        assertEquals(listOf("語言"), s.map { it.subject })
    }

    @Test fun reviewsDueTodayAreExtraSlotsAfterTheScheduledOnes() {
        val rig = LessonRig()
        val id = rig.noted(1, "禮儀")
        val day = monday.plusDays(2)
        val s = slots(rig, day)
        val review = s.last()
        assertTrue(review.review)
        assertEquals(id, review.lessonId)
        assertEquals("禮儀", review.subject)
        assertFalse(review.done)
        assertEquals(s.size, review.n)
        // not due the day before
        assertTrue(slots(rig, monday.plusDays(1)).none { it.review })
        // exam week still shows the review
        assertTrue(slots(rig, day, exam = true).any { it.review })
    }

    @Test fun aReviewDoneTodayStaysAsADoneSlot() {
        val rig = LessonRig()
        val id = rig.noted(1, "禮儀")
        rig.goTo(monday.plusDays(2))
        rig.book.answerReview(id, LessonRig.answersWith(3))
        val s = slots(rig, monday.plusDays(2)).last()
        assertTrue(s.review)
        assertTrue(s.done)
    }

    @Test fun oneExtraOnRequestUpToTheLimit() {
        val rig = LessonRig()
        val base = slots(rig, monday).size
        rig.book.wish("心理學")
        rig.book.wish("站姿")
        val s = slots(rig, monday)
        assertEquals(base + 1, s.size)
        assertEquals("心理學", s.last().subject)
        assertNull(s.last().lessonId)
    }

    @Test fun noExtraWhenTheRulesAllowNone() {
        val rules = LessonRig().rules.let { it.copy(study = it.study.copy(extraPerDay = 0)) }
        val rig = LessonRig(rules)
        val base = slots(rig, monday).size
        rig.book.wish("心理學")
        assertEquals(base, slots(rig, monday).size)
    }

    @Test fun anExtraLessonFillsItsSlotAndCountsDoneWhenNoted() {
        val rig = LessonRig()
        rig.book.wish("心理學")
        val id = rig.propose(1, "心理學")
        rig.book.approve(id)
        val s = slots(rig, monday).last()
        assertEquals(id, s.lessonId)
        assertFalse(s.done)
        rig.book.opened(id); rig.book.watched(id); rig.book.notesOnly(id); rig.book.note(id, LessonRig.NOTE)
        assertTrue(slots(rig, monday).last().done)
    }

    @Test fun maxPerDayLimitsTheScheduledSlots() {
        val rules = LessonRig().rules.let { it.copy(study = it.study.copy(maxPerDay = 2)) }
        val rig = LessonRig(rules)
        assertEquals(listOf("語言", "聲樂"), slots(rig, monday).map { it.subject })
    }

    @Test fun blankStudyRulesStillGiveOneDailyLesson() {
        val rules = LessonRig().rules.copy(study = StudyRule())
        val rig = LessonRig(rules)
        val s = slots(rig, monday)
        assertEquals(listOf(Subjects.all.first()), s.map { it.subject })
        rig.book.wish("心理學")
        assertEquals(listOf(Subjects.all.first(), "心理學"), slots(rig, monday).map { it.subject })
    }

    @Test fun anEmptySlotNamesTheSubjectAndLevel() {
        val rig = LessonRig()
        val s = slots(rig, monday).first()
        assertTrue(s.title.contains("語言"))
        assertTrue(s.title.contains("入門"))
    }
}
