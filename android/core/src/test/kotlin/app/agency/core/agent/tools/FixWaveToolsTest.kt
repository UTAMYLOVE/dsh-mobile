package app.agency.core.agent.tools

import app.agency.core.agent.Card
import app.agency.core.agent.Company
import app.agency.core.agent.Context
import app.agency.core.agent.Providers
import app.agency.core.agent.QuizStatus
import app.agency.core.agent.ToolOut
import app.agency.core.base.TestClock
import app.agency.core.day.Violations
import app.agency.core.lessons.LessonBook
import app.agency.core.lessons.LessonState
import app.agency.core.llm.Fail
import app.agency.core.llm.ScriptedLlm
import app.agency.core.llm.ScriptedLlm.Companion.fails
import app.agency.core.llm.ScriptedLlm.Companion.says
import app.agency.core.plan.ItemStatus
import app.agency.core.rules.Fixtures
import app.agency.core.stage.Today
import app.agency.core.store.Kind
import app.agency.core.store.TaskDone
import app.agency.core.store.Violation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the fixes after the end-of-build review (tools, context, quiz). Invented data only. */
class FixWaveToolsTest {
    private val tuesday = TestClock.MONDAY.plusDays(1)

    // ---- B1: no search must not make stage 1 impossible ----

    @Test fun aPastedLinkIsAcceptedOnlyWhenItIsInHisMessage() {
        val r = ToolRig(stage = 1, search = null)
        val url = "https://video.example.test/pasted-1"
        val args = """{"url":"$url","title":"","subject":"語言"}"""
        val refused = r.run("add_video", args, owner = "幫我加一支影片")
        assertFalse(refused.ok)
        assertTrue(r.store.entries(r.monday, Kind.LESSON).isEmpty())
        val out = r.run("add_video", args, owner = "這支不錯 $url 幫我加")
        assertTrue(out.err().ifEmpty { "" }, out.ok)
        val video = (out.cards.single() as Card.Videos).items.single()
        val lesson = LessonBook(r.store, r.clock, r.rules).get(video.lessonId)!!
        assertEquals(LessonState.PROPOSED, lesson.state)
        assertEquals(url, lesson.url)
        assertEquals("", lesson.material)
        assertFalse("a second time is a duplicate", r.run("add_video", args, owner = url).ok)
    }

    private fun ToolOut.err(): String = facts["error"]?.toString().orEmpty()

    @Test fun withoutSearchTheContextAndTheErrorShowBothWaysOn() {
        val r = ToolRig(stage = 1, search = null)
        val b = Context(r.store, r.clock, r.rules, searchAvailable = false).block()
        assertTrue(b, b.contains("設定 → 小葵的模型") && b.contains("add_video"))
        assertFalse(Context(r.store, r.clock, r.rules).block().contains("搜尋現在不能用"))
        val err = r.run("find_videos", """{"subject":"語言","query":""}""").err()
        assertTrue(err, err.contains("設定 → 小葵的模型") && err.contains("add_video"))
    }

    @Test fun aPastedLessonEndsWithNotesWithoutAModelCall() = runBlocking {
        val r = ToolRig(stage = 1, search = null)
        val url = "https://video.example.test/pasted-2"
        val id = r.run("add_video", """{"url":"$url","title":"發音","subject":"語言"}""", owner = url).facts.s("lesson_id")!!.toLong()
        val book = LessonBook(r.store, r.clock, r.rules)
        book.approve(id); book.opened(id); book.watched(id)
        // An empty script: any model call would fail the quiz as "unavailable" instead of notes-only.
        val company = Company(r.store, r.clock, Providers(ScriptedLlm(), ScriptedLlm()))
        assertEquals(QuizStatus.NotesOnly, company.quizStatus(id))
        assertTrue(book.get(id)!!.notesOnly)
    }

    // ---- B7: a failed model call is not a bad quiz ----

    private fun watchedLesson(r: ToolRig): Long {
        val id = r.run("find_videos", """{"subject":"語言","query":""}""").let { (it.cards.single() as Card.Videos).items.first().lessonId }
        val book = LessonBook(r.store, r.clock, r.rules)
        book.approve(id); book.opened(id); book.watched(id)
        return id
    }

    @Test fun aQuizThatCouldNotBeAskedForLeavesTheLessonAsItIs() = runBlocking {
        val r = ToolRig(stage = 1)
        val id = watchedLesson(r)
        val offline = Company(r.store, r.clock, Providers(ScriptedLlm(fails(Fail.OFFLINE)), ScriptedLlm()))
        val s = offline.quizStatus(id)
        assertTrue(s is QuizStatus.Unavailable)
        assertEquals("現在出不了題，等一下再試", (s as QuizStatus.Unavailable).message)
        val l = LessonBook(r.store, r.clock, r.rules).get(id)!!
        assertEquals(LessonState.WATCHED, l.state)
        assertFalse(l.notesOnly)
        assertNull(offline.quizFor(id))
        // Twice an unusable quiz: notes only.
        val bad = Company(r.store, r.clock, Providers(ScriptedLlm(says("不是題目"), says("還是不是")), ScriptedLlm()))
        assertEquals(QuizStatus.NotesOnly, bad.quizStatus(id))
        assertTrue(LessonBook(r.store, r.clock, r.rules).get(id)!!.notesOnly)
    }

    // ---- B2: a check-out after 06:00 belongs to the night before; a 03:00–06:00 check-in to the morning after ----

    @Test fun aCheckOutAtHalfPastSixIsLastNightsLateCheckOut() {
        val r = ToolRig(stage = 2)
        r.at("06:50")
        r.run("check_in", """{"score":4}""")
        // The day was already closed by the rollover: "never signed out".
        r.clock.set(tuesday, "06:30")
        Violations.onClose(r.store, r.rules, r.stage(), r.monday, r.clock.now(), r.clock.zone)
        val out = r.run("check_out", """{"good":"","improve":"","mood":null}""")
        assertTrue(out.ok)
        assertEquals(r.monday.toString(), out.facts.s("for_day"))
        assertEquals(1, r.store.entries(r.monday, Kind.CHECK_OUT).size)
        assertTrue(r.store.entries(tuesday, Kind.CHECK_OUT).isEmpty())
        // One late check-out for Monday, never two.
        assertEquals(listOf("LATE_CHECK_OUT"), r.store.entries(r.monday, Kind.VIOLATION).map { it.read<Violation>().code })
        // Tuesday is still open: its check-out is not done, so reminders go on.
        assertEquals(ItemStatus.PENDING, Today.plan(r.store, r.clock, r.rules).items.first { it.id == "check_out" }.status)
    }

    @Test fun aCheckOutBeforeNoonWithATodayCheckInIsToday() {
        val r = ToolRig(stage = 2)
        r.at("06:50")
        r.run("check_in", """{"score":4}""")
        r.clock.set(tuesday, "06:45")
        r.run("check_in", """{"score":4}""")
        r.clock.set(tuesday, "11:00")
        val out = r.run("check_out", """{"good":"","improve":"","mood":null}""")
        assertNull(out.facts.s("for_day"))
        assertEquals(1, r.store.entries(tuesday, Kind.CHECK_OUT).size)
    }

    @Test fun aCheckInAtFourInTheMorningOpensTheDayThatStartsAtSix() {
        val r = ToolRig(stage = 2)
        r.clock.set(r.monday, "04:10") // calendar Tuesday, still Monday's logical day
        val out = r.run("check_in", """{"score":3}""")
        assertEquals(tuesday.toString(), out.facts.s("for_day"))
        assertEquals(1, r.store.entries(tuesday, Kind.CHECK_IN).size)
        assertTrue(r.store.entries(r.monday, Kind.CHECK_IN).isEmpty())
        r.clock.set(tuesday, "07:00")
        assertEquals(ItemStatus.DONE, Today.plan(r.store, r.clock, r.rules).items.first { it.id == "check_in" }.status)
        assertEquals("true", r.run("check_in", """{"score":4}""").facts.s("already"))
    }

    // ---- B4: violations follow corrections ----

    @Test fun fixingAMealRemovesTheViolationsItNoLongerHas() {
        val r = ToolRig(stage = 2)
        r.at("11:50")
        val id = r.run("log_meal", """{"slot":"first","items":[{"name":"炸雞","kcal":980,"protein_g":30}],"source":"label","attach_photo":false}""")
            .facts.s("entry_id")!!
        assertTrue("MEAL_OVER_CAP" in r.violations() && "BANNED_FOOD" in r.violations())
        r.run("fix_meal", """{"entry_id":$id,"items":[{"name":"雞胸","kcal":1,"protein_g":1}],"source":"estimate"}""")
        assertTrue(r.violations().toString(), r.violations().isEmpty())
    }

    @Test fun removingALateCoffeeRemovesItsViolation() {
        val r = ToolRig(stage = 2)
        r.at("20:10")
        r.run("log_drink", """{"type":"caffeine"}""")
        assertEquals(listOf("CAFFEINE_LATE"), r.violations())
        val id = r.store.entries(r.monday, Kind.DRINK).single().id
        assertTrue(r.run("remove_entry", """{"entry_id":$id}""").ok)
        assertTrue(r.violations().isEmpty())
    }

    @Test fun closingTwiceAndResyncingNeverDuplicates() {
        val r = ToolRig(stage = 2)
        r.at("06:50")
        r.run("check_in", """{"score":4}""")
        r.clock.set(tuesday, "06:20")
        repeat(2) { Violations.onClose(r.store, r.rules, r.stage(), r.monday, r.clock.now(), r.clock.zone) }
        Violations.resync(r.store, r.rules, r.stage(), r.monday, r.clock.now(), r.clock.zone)
        assertEquals(1, r.store.entries(r.monday, Kind.VIOLATION).size)
    }

    // ---- B5: the free meal ----

    private fun freeRules(from: java.time.LocalDate = TestClock.MONDAY) =
        Fixtures.rules().let { it.copy(diet = it.diet.copy(freeMealFrom = from.toString(), freeMealsPerWeek = 1)) }

    private val freeMeal = """{"slot":"dinner","items":[{"name":"炸雞","kcal":990,"protein_g":30}],"source":"label","attach_photo":false,"free_meal":true}"""

    @Test fun aFreeMealCountsButIsNeverAViolationAndOnlyOnceAWeek() {
        val r = ToolRig(stage = 2, rules = freeRules())
        r.at("18:25")
        assertTrue(Context(r.store, r.clock, r.rules).block().contains("自由餐：這週還可以用 1 次"))
        val out = r.run("log_meal", freeMeal)
        assertTrue(out.ok)
        assertTrue(r.violations().isEmpty())
        assertEquals("990", out.facts.o("today").s("kcal"))
        assertTrue((out.cards.single() as Card.MealCard).warnings.isEmpty())
        assertTrue(Context(r.store, r.clock, r.rules).block().contains("自由餐：這週已經用掉了"))
        val again = r.run("log_meal", freeMeal)
        assertFalse(again.ok)
        assertTrue(again.err().contains("已經用掉"))
        assertEquals(1, r.store.entries(r.monday, Kind.MEAL).size)
    }

    @Test fun aFreeMealBeforeItsStartIsRefused() {
        val r = ToolRig(stage = 2, rules = freeRules(TestClock.MONDAY.plusDays(7)))
        r.at("18:25")
        val out = r.run("log_meal", freeMeal)
        assertFalse(out.ok)
        assertTrue(out.err().contains("才開始"))
        assertFalse(ToolRig(stage = 2).run("log_meal", freeMeal).ok)
    }

    // ---- B10: strength without a number of rounds ----

    @Test fun strengthWithoutRoundsIsTheFullPlanUnlessCodeReducedIt() {
        val r = ToolRig(stage = 2)
        r.at("22:30")
        val out = r.run("log_task", """{"task":"STRENGTH","rounds":null,"minutes":null,"feel":"easy","note":""}""")
        assertNull(out.facts.s("short_rounds"))
        assertEquals(4, r.store.entries(r.monday, Kind.TASK).last().read<TaskDone>().rounds)
        r.run("set_status", """{"type":"SICK","note":""}""")
        r.run("log_task", """{"task":"STRENGTH","rounds":null,"minutes":null,"feel":null,"note":""}""")
        val sick = r.store.entries(r.monday, Kind.TASK).last().read<TaskDone>()
        assertEquals(3, sick.rounds)
        assertTrue(sick.reduced)
    }

    // ---- B14: weight flags reach her ----

    @Test fun pauseAndFloorWeightsAreThingsToTalkAbout() {
        val r = ToolRig(stage = 2)
        r.at("08:00")
        r.run("log_body", """{"kg":70.5,"waist_cm":null}""")
        val b = Context(r.store, r.clock, r.rules).block()
        assertTrue(b, b.contains("該主動談的事") && b.contains("先停止推減重"))
        r.run("log_body", """{"kg":60.6,"waist_cm":null}""")
        assertTrue(Context(r.store, r.clock, r.rules).block().contains("最低底線"))
    }
}
