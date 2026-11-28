package app.agency.core.lessons

import app.agency.core.agent.Hit
import app.agency.core.lessons.LessonRig.Companion.MATERIAL
import app.agency.core.lessons.LessonRig.Companion.NOTE
import app.agency.core.lessons.LessonRig.Companion.QUIZ
import app.agency.core.lessons.LessonRig.Companion.answersWith
import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class LessonBookTest {
    private fun refusal(r: LessonResult) = (r as LessonResult.Refused).why

    @Test fun happyPathFromProposalToNoteSetsTheReview() {
        val rig = LessonRig()
        val id = rig.propose(1, "語言")
        assertEquals(LessonState.PROPOSED, rig.lesson(id).state)
        assertEquals("入門", rig.lesson(id).level)
        rig.book.approve(id)
        assertEquals(rig.day().toString(), rig.lesson(id).approvedOn)
        rig.book.opened(id)
        assertNotNull(rig.lesson(id).openedAt)
        rig.book.watched(id)
        assertEquals(LessonState.WATCHED, rig.lesson(id).state)
        assertTrue(rig.book.setQuiz(id, QUIZ) is LessonResult.Ok)
        val result = rig.book.answer(id, answersWith(3))
        assertTrue(result.passed)
        assertEquals(3, result.right)
        assertEquals(LessonState.PASSED, rig.lesson(id).state)
        assertTrue(rig.book.note(id, NOTE) is LessonResult.Ok)
        val l = rig.lesson(id)
        assertEquals(LessonState.NOTED, l.state)
        assertEquals(rig.day().plusDays(2).toString(), l.reviewOn)
        assertEquals(NOTE, l.note)
    }

    @Test fun passMarkComesFromTheRules() {
        val rig = LessonRig()
        val id = rig.watched(1, "語言")
        rig.book.setQuiz(id, QUIZ)
        val two = rig.book.answer(id, answersWith(2))
        assertTrue(two.passed)
        val id2 = rig.watched(2, "語言")
        rig.book.setQuiz(id2, QUIZ)
        val one = rig.book.answer(id2, answersWith(1))
        assertFalse(one.passed)
    }

    @Test fun failedQuizClearsItCountsTheAttemptAndAllowsANewOne() {
        val rig = LessonRig()
        val id = rig.watched(1, "語言")
        rig.book.setQuiz(id, QUIZ)
        val r = rig.book.answer(id, answersWith(0))
        assertFalse(r.passed)
        val l = rig.lesson(id)
        assertEquals(LessonState.WATCHED, l.state)
        assertNull(l.quiz)
        assertEquals(1, l.attempts)
        assertEquals(QuizResult.refused(Refusal.NO_QUIZ), rig.book.answer(id, answersWith(3)))
        rig.book.setQuiz(id, QUIZ)
        assertTrue(rig.book.answer(id, answersWith(3)).passed)
        assertEquals(2, rig.lesson(id).attempts)
    }

    @Test fun declineOnlyWorksOnAProposal() {
        val rig = LessonRig()
        val id = rig.propose(1, "語言")
        assertTrue(rig.book.decline(id) is LessonResult.Ok)
        assertEquals(LessonState.DECLINED, rig.lesson(id).state)
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.approve(id)))
        val id2 = rig.approved(2, "語言")
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.decline(id2)))
    }

    @Test fun refusalsForTheWrongStateOrMissingPieces() {
        val rig = LessonRig()
        val id = rig.propose(1, "語言")
        assertEquals(Refusal.NOT_FOUND, refusal(rig.book.approve(999)))
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.opened(id)))
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.watched(id)))
        rig.book.approve(id)
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.approve(id)))
        assertEquals(Refusal.NOT_OPENED, refusal(rig.book.watched(id)))
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.setQuiz(id, QUIZ)))
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.note(id, NOTE)))
        assertEquals(Refusal.WRONG_STATE, rig.book.answer(id, listOf(0, 0, 0)).refusal)
        assertEquals(Refusal.NOT_FOUND, rig.book.answer(999, listOf(0, 0, 0)).refusal)
        assertEquals(Refusal.NOT_FOUND, refusal(rig.book.note(999, NOTE)))
    }

    @Test fun answersNeedAQuizAndTheRightCount() {
        val rig = LessonRig()
        val id = rig.watched(1, "語言")
        assertEquals(Refusal.NO_QUIZ, rig.book.answer(id, listOf(0, 0, 0)).refusal)
        rig.book.setQuiz(id, QUIZ)
        assertEquals(Refusal.BAD_ANSWERS, rig.book.answer(id, listOf(0, 0)).refusal)
        assertEquals(LessonState.WATCHED, rig.lesson(id).state)
        assertEquals(0, rig.lesson(id).attempts)
    }

    @Test fun anInvalidQuizIsRefused() {
        val rig = LessonRig()
        val id = rig.watched(1, "語言")
        val bad = Quiz(QUIZ.questions.map { it.copy(quote = "教材裡根本沒有這句話的內容啊") })
        assertEquals(Refusal.BAD_QUIZ, refusal(rig.book.setQuiz(id, bad)))
        assertNull(rig.lesson(id).quiz)
    }

    @Test fun aNoteNeedsTenCharactersAndAPassedLesson() {
        val rig = LessonRig()
        val id = rig.passed(1, "語言")
        assertEquals(Refusal.NOTE_TOO_SHORT, refusal(rig.book.note(id, "太短了")))
        assertEquals(Refusal.NOTE_TOO_SHORT, refusal(rig.book.note(id, "   123456789   ")))
        assertEquals(LessonState.PASSED, rig.lesson(id).state)
        assertTrue(rig.book.note(id, "1234567890") is LessonResult.Ok)
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.note(id, NOTE)))
    }

    @Test fun notesOnlyFallbackSkipsTheQuizAndTheReview() {
        val rig = LessonRig()
        val id = rig.watched(1, "語言")
        assertEquals(Refusal.WRONG_STATE, refusal(rig.book.note(id, NOTE)))
        rig.book.notesOnly(id)
        assertTrue(rig.book.note(id, NOTE) is LessonResult.Ok)
        val l = rig.lesson(id)
        assertEquals(LessonState.NOTED, l.state)
        assertTrue(l.notesOnly)
        assertNull(l.reviewOn)
    }

    @Test fun theSameVideoCannotBeProposedTwiceAndBadHitsAreRefused() {
        val rig = LessonRig()
        rig.propose(1, "語言")
        assertEquals(Refusal.DUPLICATE, refusal(rig.book.propose(rig.hit(1), "語言")))
        assertEquals(Refusal.BAD_HIT, refusal(rig.book.propose(Hit("", "https://x.test/a"), "語言")))
        assertEquals(Refusal.BAD_HIT, refusal(rig.book.propose(Hit("標題", ""), "語言")))
        assertEquals(Refusal.BAD_HIT, refusal(rig.book.propose(rig.hit(5), " ")))
        val declined = rig.propose(2, "語言").also { rig.book.decline(it) }
        assertTrue(rig.book.propose(rig.hit(2), "語言") is LessonResult.Ok)
        assertEquals(LessonState.DECLINED, rig.lesson(declined).state)
    }

    @Test fun reviewIsDueAfterTheConfiguredDays() {
        val rig = LessonRig()
        val start = rig.day()
        val id = rig.noted(1, "語言")
        assertTrue(rig.book.reviewDue(start).isEmpty())
        assertTrue(rig.book.reviewDue(start.plusDays(1)).isEmpty())
        val due = rig.book.reviewDue(start.plusDays(2))
        assertEquals(listOf(id), due.map { it.first })
        assertEquals(LessonState.REVIEW_DUE, rig.lesson(id).state)
        // too early on the clock
        assertEquals(Refusal.NOT_DUE, rig.book.answerReview(id, answersWith(3)).refusal)
        rig.goTo(start.plusDays(2))
        val bad = rig.book.answerReview(id, answersWith(0))
        assertFalse(bad.passed)
        assertEquals(LessonState.REVIEW_DUE, rig.lesson(id).state)
        val ok = rig.book.answerReview(id, answersWith(3))
        assertTrue(ok.passed)
        assertEquals(LessonState.REVIEWED, rig.lesson(id).state)
        assertEquals(3, rig.lesson(id).reviewScore)
        assertTrue(rig.book.reviewDue(start.plusDays(5)).isEmpty())
        assertEquals(Refusal.WRONG_STATE, rig.book.answerReview(id, answersWith(3)).refusal)
    }

    @Test fun reviewDaysFollowTheRule() {
        val rules = LessonRig().rules.let { it.copy(study = it.study.copy(reviewAfterDays = 5)) }
        val rig = LessonRig(rules)
        val start = rig.day()
        rig.noted(1, "語言")
        assertTrue(rig.book.reviewDue(start.plusDays(4)).isEmpty())
        assertEquals(1, rig.book.reviewDue(start.plusDays(5)).size)
    }

    @Test fun aWishIsStoredOnceAndUsedUpByTheVideoFoundForIt() {
        val rig = LessonRig()
        assertEquals(Refusal.EMPTY, refusal(rig.book.wish("  ")))
        val w = rig.id(rig.book.wish("理財"))
        assertEquals(w, rig.id(rig.book.wish("理財")))
        assertEquals(LessonState.WISH, rig.lesson(w).state)
        val id = rig.propose(1, "理財")
        assertNull(rig.book.get(w))
        assertTrue(rig.lesson(id).extra)
    }

    @Test fun awayIsTheOpenedLessonHeHasNotReturnedFrom() {
        val rig = LessonRig()
        val id = rig.approved(1, "語言")
        assertNull(rig.book.away())
        rig.book.opened(id)
        val away = rig.book.away()!!
        assertEquals(id, away.lessonId)
        assertEquals(Instant.parse(rig.lesson(id).openedAt), away.openedAt)
        rig.book.returned(id)
        assertNull(rig.book.away())
        assertEquals(MATERIAL, rig.lesson(id).material)
    }
}
