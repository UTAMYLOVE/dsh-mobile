package app.dshmobile.ui.agency

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Lesson
import app.agency.core.model.LessonCategory
import app.agency.core.model.LessonStatus
import app.agency.core.model.Quiz
import app.agency.core.model.QuizKind
import app.agency.core.model.QuizQuestion
import app.agency.core.model.SearchKind
import app.agency.core.model.SearchResult
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.lessons.LessonTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 理論課 on screen (T19c): a candidate joins 「課程清單」 only on 好; 去看 hands the stored link (and nothing else) to the browser or
 * YouTube app; the three questions are picked and handed in, graded by the brain; the note unlocks the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h2400dp-mdpi")
class LessonsScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("lessons").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    private fun open() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
    }

    // More than 200 Han characters: enough to quiz on (a thinner text would ask for three key points first).
    private val material = (1..5).joinToString("") { "吐氣的時候用嘴巴發出嘶的聲音，盡量維持十五秒以上。唱高音的時候不要用喉嚨擠，要把氣往下沉。吸氣的時候肩膀不要抬起來。" }
    private val questions = listOf(
        QuizQuestion("嘶的聲音要維持多久以上？", listOf("五秒", "十秒", "十五秒", "三十秒"), 2, "盡量維持十五秒以上"),
        QuizQuestion("唱高音時氣要怎樣？", listOf("往上提", "往下沉", "憋住", "用喉嚨擠"), 1, "要把氣往下沉"),
        QuizQuestion("吸氣時肩膀要怎樣？", listOf("抬起來", "不要抬起來", "往前縮", "往後夾"), 1, "吸氣的時候肩膀不要抬起來"),
    )

    @Test fun aCandidateIsListedOnlyOnHisYesAndGoHandsOverItsStoredLink() {
        open()
        val now = System.currentTimeMillis()
        runBlocking {
            ready().store.places.putResult(SearchResult("r-1", SearchKind.VIDEO, "發聲 影片", "https://www.youtube.com/watch?v=abc", "腹式呼吸練習", "呼吸的基本功", null, 2.0, 2.0, now))
            ready().store.lessons.insert(Lesson("l-1", "r-1", LessonCategory.VOCAL, null, "腹式呼吸練習", "https://www.youtube.com/watch?v=abc", material, foundAt = now))
        }
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.LESSONS.name).performClick()
        app.agency.send("刮好了") // any event reloads the tab
        rule.waitForTag(LessonTags.CANDIDATE + "l-1", 30_000)
        rule.waitForText("呼吸的基本功")
        assertEquals(LessonStatus.CANDIDATE, runBlocking { ready().store.lessons.get("l-1") }!!.status)
        rule.onNodeWithTag(LessonTags.YES + "l-1").performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.lessons.get("l-1") }!!.status == LessonStatus.LISTED }
        rule.waitForTag(LessonTags.GO + "l-1", 10_000)
        rule.onNodeWithTag(LessonTags.GO + "l-1").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            var started: Intent? = null
            scenario!!.onActivity { started = shadowOf(it).peekNextStartedActivity() }
            started != null
        }
        var opened: Intent? = null
        scenario!!.onActivity { opened = shadowOf(it).nextStartedActivity }
        assertEquals(Intent.ACTION_VIEW, opened!!.action)
        assertEquals("https://www.youtube.com/watch?v=abc", opened!!.dataString)
        assertTrue(opened!!.extras == null || opened!!.extras!!.isEmpty)
    }

    @Test fun theQuestionsAreHandedInGradedByTheBrainAndTheNoteUnlocksTheNext() {
        open()
        val now = System.currentTimeMillis()
        runBlocking {
            val s = ready().store
            s.lessons.insert(Lesson("l-1", "r-1", LessonCategory.VOCAL, null, "腹式呼吸練習", "https://www.youtube.com/watch?v=abc", material, LessonStatus.LISTED, foundAt = now, listedAt = now, watchedAt = now))
            s.lessons.insert(Lesson("l-2", "r-2", LessonCategory.EXPRESSION, null, "鏡頭前的笑容", "https://www.youtube.com/watch?v=def", material, LessonStatus.LISTED, foundAt = now + 1, listedAt = now + 1))
            s.lessons.putQuiz(Quiz("q-1", "l-1", QuizKind.MAIN, now, questions, shown = 3))
        }
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.LESSONS.name).performClick()
        app.agency.send("刮好了")
        rule.waitForText("嘶的聲音要維持多久以上？", 30_000)
        rule.waitForText("前一支通過才開")
        rule.onNodeWithTag(LessonTags.OPTION + "0-2").performScrollTo().performClick()
        rule.onNodeWithTag(LessonTags.OPTION + "1-1").performScrollTo().performClick()
        rule.onNodeWithTag(LessonTags.OPTION + "2-0").performScrollTo().performClick()
        rule.onNodeWithTag(LessonTags.SUBMIT).performScrollTo().performClick()
        rule.waitForText("答對 2 題，考過了。", 10_000)
        rule.waitForText("正確答案：不要抬起來。教材原文：「吸氣的時候肩膀不要抬起來」")
        assertEquals(2, runBlocking { ready().store.lessons.get("l-1") }!!.score)
        rule.waitForTag(LessonTags.NOTE)
        rule.onNodeWithTag(LessonTags.NOTE).performScrollTo().performTextInput("嘶的聲音要維持十五秒以上，高音時氣往下沉，吸氣肩膀不要抬起來，每天早上練五分鐘。")
        rule.onNodeWithTag(LessonTags.NOTE_SEND).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { ready().store.lessons.get("l-1") }!!.passed }
        rule.waitForTag(LessonTags.GO + "l-2", 10_000)
        rule.waitForText("今天的理論課（通過 1／2 支）")
        assertNull(runBlocking { ready().store.lessons.get("l-2") }!!.passedAt)
    }
}
