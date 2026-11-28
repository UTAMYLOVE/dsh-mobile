package app.agency.core.lessons

import app.agency.core.lessons.LessonRig.Companion.MATERIAL
import app.agency.core.lessons.LessonRig.Companion.QUIZ
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.rules.StudyRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import app.agency.core.base.J
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizCheckTest {
    private val study = StudyRule()

    private fun q(i: Int) = QUIZ.questions[i]

    @Test fun aGoodQuizIsValid() {
        assertTrue(QuizCheck.valid(QUIZ, MATERIAL, study))
    }

    @Test fun theWrongNumberOfQuestionsIsInvalid() {
        assertFalse(QuizCheck.valid(Quiz(QUIZ.questions.take(2)), MATERIAL, study))
        assertFalse(QuizCheck.valid(Quiz(QUIZ.questions + q(0).copy(q = "另一題？")), MATERIAL, study))
        assertTrue(QuizCheck.valid(Quiz(QUIZ.questions.take(2)), MATERIAL, study.copy(quizQuestions = 2)))
    }

    @Test fun aQuoteThatIsNotInTheMaterialIsInvalid() {
        val bad = Quiz(listOf(q(0), q(1), q(2).copy(quote = "這句話並不在教材裡面喔")))
        assertFalse(QuizCheck.valid(bad, MATERIAL, study))
        assertTrue(QuizCheck.problems(bad, MATERIAL, study).any { it.contains("不在教材裡") })
    }

    @Test fun aShortQuoteIsInvalid() {
        val bad = Quiz(listOf(q(0), q(1), q(2).copy(quote = "完整的句子裡")))
        assertFalse(QuizCheck.valid(bad, MATERIAL, study))
    }

    @Test fun whitespaceInTheQuoteOrMaterialDoesNotMatter() {
        val spaced = Quiz(listOf(q(0).copy(quote = "發音要先聽再 模仿，"), q(1), q(2)))
        assertTrue(QuizCheck.valid(spaced, MATERIAL.replace("，", "， \n"), study))
    }

    @Test fun duplicateOrWrongOptionsAreInvalid() {
        val dup = Quiz(listOf(q(0).copy(options = listOf("甲", "甲", "乙", "丙")), q(1), q(2)))
        assertFalse(QuizCheck.valid(dup, MATERIAL, study))
        val three = Quiz(listOf(q(0).copy(options = listOf("甲", "乙", "丙")), q(1), q(2)))
        assertFalse(QuizCheck.valid(three, MATERIAL, study))
        val blank = Quiz(listOf(q(0).copy(options = listOf("甲", "乙", "丙", " ")), q(1), q(2)))
        assertFalse(QuizCheck.valid(blank, MATERIAL, study))
        val badAnswer = Quiz(listOf(q(0).copy(answer = 4), q(1), q(2)))
        assertFalse(QuizCheck.valid(badAnswer, MATERIAL, study))
    }

    @Test fun gradingCountsRightAnswersAndTreatsMissingAsWrong() {
        assertEquals(3, QuizCheck.grade(QUIZ, listOf(0, 0, 1)))
        assertEquals(1, QuizCheck.grade(QUIZ, listOf(0, 3, 3)))
        assertEquals(1, QuizCheck.grade(QUIZ, listOf(0)))
        assertEquals(0, QuizCheck.grade(QUIZ, emptyList()))
    }

    @Test fun passMarkIsClamped() {
        assertEquals(2, QuizCheck.passMark(study.copy(quizPass = 2), 3))
        assertEquals(3, QuizCheck.passMark(study.copy(quizPass = 9), 3))
        assertEquals(1, QuizCheck.passMark(study.copy(quizPass = 0), 3))
    }
}

class QuizzerTest {
    private class FakeLlm(private val replies: List<ChatResult>) : LlmClient {
        val requests = ArrayList<ChatRequest>()
        override suspend fun chat(request: ChatRequest): ChatResult {
            requests += request
            return replies[(requests.size - 1).coerceAtMost(replies.lastIndex)]
        }
    }

    private fun ok(text: String): ChatResult =
        ChatResult.Ok(text, emptyList(), Turn.Assistant(Wire.OPENAI, JsonNull, text, emptyList()), null)

    private val good = J.encodeToString(QUIZ)
    private val invalid = J.encodeToString(Quiz(QUIZ.questions.take(2)))
    private val lesson = Lesson("語言", "影片", "https://example.test/v", material = MATERIAL)

    @Test fun aValidQuizIsReturnedAfterOneCall() = runBlocking {
        val llm = FakeLlm(listOf(ok(good)))
        val quiz = Quizzer(llm).make(lesson, "入門")
        assertEquals(QUIZ, quiz)
        assertEquals(1, llm.requests.size)
        val r = llm.requests.single()
        assertEquals(Purpose.QUIZ, r.purpose)
        assertTrue(r.output is Output.Json)
        val text = (r.turns.single() as Turn.User).text
        assertTrue(text.contains(MATERIAL))
        assertTrue(text.contains("入門"))
    }

    @Test fun anInvalidQuizIsRegeneratedOnceWithTheProblemsNamed() = runBlocking {
        val llm = FakeLlm(listOf(ok(invalid), ok(good)))
        val quiz = Quizzer(llm).make(lesson, "基礎")
        assertEquals(QUIZ, quiz)
        assertEquals(2, llm.requests.size)
        assertTrue((llm.requests[1].turns.single() as Turn.User).text.contains("題數"))
    }

    @Test fun twoInvalidQuizzesGiveNull() = runBlocking {
        val llm = FakeLlm(listOf(ok(invalid), ok("不是 JSON")))
        assertNull(Quizzer(llm).make(lesson, "入門"))
        assertEquals(2, llm.requests.size)
    }

    @Test fun aFailedCallGivesNullAtOnce() = runBlocking {
        val llm = FakeLlm(listOf(ChatResult.Failed(Fail.SERVER)))
        assertNull(Quizzer(llm).make(lesson, "入門"))
        assertEquals(1, llm.requests.size)
    }

    @Test fun noMaterialMeansNoCall() = runBlocking {
        val llm = FakeLlm(listOf(ok(good)))
        assertNull(Quizzer(llm).make(lesson.copy(material = " "), "入門"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun aFencedReplyIsStillRead() = runBlocking {
        val llm = FakeLlm(listOf(ok("```json\n$good\n```")))
        assertNotNull(Quizzer(llm).make(lesson, "入門"))
    }
}
