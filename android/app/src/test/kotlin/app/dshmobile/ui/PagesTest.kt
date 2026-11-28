package app.dshmobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.dshmobile.ui.pages.EvalPage
import app.dshmobile.ui.pages.LessonsPage
import app.dshmobile.ui.pages.MemoPage
import app.dshmobile.ui.pages.QuizPage
import app.dshmobile.ui.pages.SingingPage
import app.dshmobile.ui.pages.TimerEngine
import app.dshmobile.ui.pages.TimerPage
import app.dshmobile.ui.pages.TraineePage
import app.dshmobile.ui.sheets.BodySheet
import app.dshmobile.ui.sheets.CheckInSheet
import app.dshmobile.ui.sheets.CheckOutSheet
import app.dshmobile.ui.sheets.MoneySheet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every page renders with the demo data and its main path works. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h828dp-xhdpi")
class PagesTest {
    @get:Rule val rule = createComposeRule()

    private fun ComposeContentTestRule.count(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun ComposeContentTestRule.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun checkInDotRecordsAndCloses() {
        val company = DemoCompany()
        val done = AtomicBoolean()
        rule.show { CheckInSheet(company, {}, { done.set(true) }) }
        rule.onNodeWithText("今天精神幾分？").assertIsDisplayed()
        rule.onNodeWithText("4").performClick()
        rule.until { done.get() }
        assertTrue(company.calls.any { it.startsWith("direct:check_in") && it.contains("4") })
    }

    @Test fun checkOutTakesTwoFieldsAndMoodOnlyWhenOpen() {
        val company = DemoCompany()
        val done = AtomicBoolean()
        rule.show { CheckOutSheet(company, moodOpen = true, onClose = {}, onDone = { done.set(true) }) }
        rule.onNodeWithTag("checkout_good").performTextInput("練完了")
        rule.onNodeWithTag("checkout_improve").performTextInput("早點睡")
        rule.onNodeWithText("5").performClick()
        rule.onNodeWithTag("checkout_save").performClick()
        rule.until { done.get() }
        val call = company.calls.first { it.startsWith("direct:check_out") }
        assertTrue(call, call.contains("練完了") && call.contains("早點睡") && call.contains("\"mood\":5"))
    }

    @Test fun checkOutWithoutMoodHasNoMoodDots() {
        rule.show { CheckOutSheet(DemoCompany(), moodOpen = false, onClose = {}, onDone = {}) }
        assertTrue(!rule.has("今天心情"))
    }

    @Test fun bodySheetLogsWeightWaistAndPhoto() {
        val body = DemoBody()
        val done = AtomicBoolean()
        rule.show { BodySheet(body, DemoMedia(), {}, { done.set(true) }) }
        rule.until { rule.has("上次體重 68.4 公斤") }
        rule.onNodeWithTag("body_kg").performTextInput("68.1")
        rule.onNodeWithTag("body_waist").performTextInput("73")
        rule.onNodeWithTag("body_photo").performClick()
        rule.until { rule.has("已附照片") }
        rule.onNodeWithTag("body_save").performClick()
        rule.until { done.get() }
        assertEquals(listOf("body:68.1:73.0:true"), body.log.toList())
    }

    @Test fun moneySheetRecordsAmountBucketAndNote() {
        val money = DemoMoney()
        val done = AtomicBoolean()
        rule.show { MoneySheet(money, {}, { done.set(true) }) }
        rule.onNodeWithTag("money_amount").performTextInput("120")
        rule.onNodeWithTag("bucket_FUN").performClick()
        rule.onNodeWithTag("money_note").performTextInput("奶茶以外的飲料")
        rule.onNodeWithTag("money_save").performClick()
        rule.until { done.get() }
        assertEquals(listOf("money:120:FUN:奶茶以外的飲料"), money.log.toList())
    }

    @Test fun lessonsListShowsEveryStateAndApproves() {
        val lessons = DemoLessons()
        val said = AtomicBoolean()
        rule.show { LessonsPage(lessons, DemoCompany(), {}, { _, _ -> }, { said.set(true) }) }
        rule.until { rule.count("lesson_1") > 0 }
        rule.onNodeWithTag("lesson_add_1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("lesson_watch_2").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("lesson_quiz_3").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("lesson_note_4").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("lesson_review_6").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("lesson_add_1").performClick()
        rule.until { lessons.log.contains("approve:1:true") }
        rule.onNodeWithTag("lesson_watch_2").performClick()
        rule.until { lessons.log.contains("watch:2") }
    }

    @Test fun lessonWishGoesToTheManager() {
        val company = DemoCompany()
        val said = AtomicBoolean()
        rule.show { LessonsPage(DemoLessons(), company, {}, { _, _ -> }, { said.set(true) }) }
        rule.onNodeWithTag("wish").performTextInput("韓文發音")
        rule.onNodeWithTag("wish_send").performClick()
        rule.until { said.get() && company.calls.contains("say:我想學韓文發音:0") }
    }

    @Test fun quizFailThenRetryThenPassThenNote() {
        val lessons = DemoLessons()
        val finished = AtomicBoolean()
        rule.show { QuizPage(lessons, 3, startWithNote = false, onBack = {}, onFinished = { finished.set(true) }) }
        rule.until { rule.count("question_0") > 0 }
        // wrong answers: 0, 1, 1
        rule.onNodeWithTag("option_0_0").performClick()
        rule.onNodeWithTag("option_1_1").performClick()
        rule.onNodeWithTag("option_2_1").performClick()
        rule.onNodeWithTag("quiz_submit").performClick()
        rule.until { rule.count("quiz_result") > 0 }
        rule.onNodeWithText("答對 0／3").assertIsDisplayed()
        rule.onNodeWithTag("quiz_retry").performClick()
        rule.until { rule.count("quiz_submit") > 0 }
        // right answers: 1, 0, 0
        rule.onNodeWithTag("option_0_1").performClick()
        rule.onNodeWithTag("option_1_0").performClick()
        rule.onNodeWithTag("option_2_0").performClick()
        rule.onNodeWithTag("quiz_submit").performClick()
        rule.until { rule.count("note_field") > 0 }
        rule.onNodeWithText("通過了！").assertIsDisplayed()
        rule.onNodeWithTag("note_field").performTextInput("太短")
        rule.onNodeWithTag("note_save").performClick()
        rule.until { rule.has("再多寫一點，至少十個字。") }
        rule.onNodeWithTag("note_field").performTextInput("今天學到換氣要用肚子，不是用肩膀。")
        rule.onNodeWithTag("note_save").performClick()
        rule.until { finished.get() }
        assertTrue(lessons.log.contains("note:3"))
    }

    @Test fun noteOnlyStartSkipsTheQuestions() {
        rule.show { QuizPage(DemoLessons(), 4, startWithNote = true, onBack = {}, onFinished = {}) }
        rule.until { rule.count("note_field") > 0 }
        assertEquals(0, rule.count("question_0"))
    }

    @Test fun timerEngineWalksTheMovesAndRestsAndFinishes() {
        val plan = DemoTimer().plan("CORE")
        val e = TimerEngine(plan)
        assertEquals(30 + 20 + 20 + 15 + 30 + 20 + 20, e.totalSeconds)
        assertEquals("棒式", e.at(0).segment?.name)
        assertEquals(30, e.at(0).remaining)
        assertEquals("側棒式", e.at(30).segment?.name)
        assertTrue(e.at(70).segment!!.rest)
        assertEquals(10, e.at(75).remaining)
        assertTrue(e.at(e.totalSeconds).finished)
        assertEquals(30, e.endOf(0))
    }

    @Test fun timerRunsSkipsToTheEndAndRecordsHowItFelt() {
        val timer = DemoTimer()
        val finished = AtomicBoolean()
        val clock = AtomicLong(1_000)
        rule.mainClock.autoAdvance = false
        rule.show { TimerPage(timer, "CORE", {}, { finished.set(true) }, tickMs = 100_000, clock = { clock.get() }) }
        rule.mainClock.advanceTimeBy(100)
        rule.until { rule.count("timer_start") > 0 }
        rule.onNodeWithTag("timer_start").performClick()
        rule.mainClock.advanceTimeBy(100)
        repeat(10) {
            if (rule.count("timer_skip") > 0) {
                rule.onNodeWithTag("timer_skip").performClick()
                rule.mainClock.advanceTimeBy(100)
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("the timer never finished:\n" + rule.onRoot(useUnmergedTree = true).printToString(), rule.count("timer_done") > 0)
        rule.onNodeWithTag("feel_HARD").performClick()
        rule.until { finished.get() }
        assertEquals(listOf("CORE:2:3:HARD:0"), timer.finished.toList())
    }

    @Test fun memoTicksDeletesAndAdds() {
        val memo = DemoMemo()
        rule.show { MemoPage(memo, {}) }
        rule.until { rule.count("memo_1") > 0 }
        rule.onNodeWithTag("memo_tick_1").performClick()
        rule.until { memo.rows.first { it.id == 1L }.done }
        rule.onNodeWithTag("memo_del_2").performClick()
        rule.until { memo.rows.none { it.id == 2L } }
        rule.onNodeWithTag("memo_field").performTextInput("訂票")
        rule.onNodeWithTag("memo_add").performClick()
        rule.until { memo.rows.any { it.text == "訂票" } }
    }

    @Test fun traineeCardShowsProfileWallAndDiary() {
        rule.show { TraineePage(DemoTrainee(), DemoMedia(), {}) }
        rule.until { rule.count("trainee_card") > 0 }
        rule.onNodeWithText("阿星").assertIsDisplayed()
        rule.onNodeWithText("C").assertIsDisplayed()
        rule.onNodeWithText("入社第 17 天").assertIsDisplayed()
        assertEquals(3, rule.count("photocard"))
    }

    @Test fun singingRecordsAndShowsNumbers() {
        rule.show { SingingPage(DemoSinging(), {}) }
        rule.onNodeWithTag("singing_toggle").performClick()
        rule.until { rule.count("singing_recording") > 0 }
        rule.onNodeWithTag("singing_toggle").performClick()
        rule.until { rule.count("singing_result") > 0 }
        rule.onNodeWithText("音準 82%").assertIsDisplayed()
    }

    @Test fun evaluationRecordsAndConfirms() {
        val eval = DemoEval()
        val done = AtomicBoolean()
        rule.show { EvalPage(eval, {}, { done.set(true) }) }
        rule.until { rule.count("eval_song") > 0 }
        rule.onNodeWithTag("eval_record_dance").assertIsDisplayed()
        rule.onNodeWithTag("eval_confirm").performClick()
        rule.until { done.get() }
        assertEquals(1, eval.confirmed.size)
    }
}
