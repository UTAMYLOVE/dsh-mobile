package app.agency.core.extras

import app.agency.core.base.TestClock
import app.agency.core.day.StageDays
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.Screen
import app.agency.core.plan.TaskId
import app.agency.core.plan.Tap
import app.agency.core.stage.Feature
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.RealTask
import app.agency.core.store.TaskDone
import app.agency.core.store.add
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeWeeksTest {
    private val opened = LocalDate.of(2030, 1, 9)
    private val stage = Kit.stage(4, startedOn = opened)

    @Test fun rotationStartsWhenTheFeatureOpensAndWrapsAround() {
        assertEquals("表情週", ThemeWeeks.current(opened, stage)!!.name)
        assertEquals("表情週", ThemeWeeks.current(LocalDate.of(2030, 1, 13), stage)!!.name)
        assertEquals("體態週", ThemeWeeks.current(LocalDate.of(2030, 1, 14), stage)!!.name)
        assertEquals("英文週", ThemeWeeks.current(LocalDate.of(2030, 1, 21), stage)!!.name)
        val n = ThemeWeeks.THEMES.size
        assertEquals("表情週", ThemeWeeks.current(LocalDate.of(2030, 1, 7).plusWeeks(n.toLong()), stage)!!.name)
        assertEquals(opened, StageDays.openedOn(stage, 4))
        assertNull(ThemeWeeks.current(opened, Kit.stage(3)))
        assertTrue(ThemeWeeks.THEMES.map { it.name }.containsAll(listOf("表情週", "體態週", "英文週", "聲樂週", "舞蹈週", "禮儀週")))
        assertTrue(ThemeWeeks.THEMES.all { t -> (t.name + t.practice).none { it in 'A'..'Z' || it in 'a'..'z' } })
    }

    @Test fun practiceIsRecordedPerDay() {
        val store = MemStore()
        assertFalse(ThemeWeeks.doneOn(store, opened))
        ThemeWeeks.markDone(store, opened, TestClock().now(), 5)
        assertTrue(ThemeWeeks.doneOn(store, opened))
        assertFalse(ThemeWeeks.doneOn(store, opened.plusDays(1)))
    }
}

class RealTasksTest {
    private val store = MemStore()
    private val clock = TestClock().also { it.set(TestClock.MONDAY, "09:00") }
    private val tasks = RealTasks(store, clock)

    @Test fun ladderStartsEasyAndOnlyNamesPeopleHeKnows() {
        assertTrue(RealTasks.LADDER.first().contains("吃") && RealTasks.LADDER.first().contains("認識"))
        RealTasks.LADDER.forEach { t ->
            assertTrue(t, t.contains("認識") || t.contains("朋友"))
            listOf("陌生", "路人", "不認識", "搭訕", "要電話").forEach { bad -> assertFalse("$t $bad", t.contains(bad)) }
            assertTrue(t, t.none { it in 'A'..'Z' || it in 'a'..'z' })
        }
    }

    @Test fun advancesOnlyWhenDone() {
        val w1 = TestClock.MONDAY
        assertEquals(RealTask(RealTasks.LADDER[0]), tasks.peek(w1))
        assertTrue(store.entries(w1, w1.plusDays(6), Kind.REAL_TASK).isEmpty())
        assertEquals(RealTask(RealTasks.LADDER[0]), tasks.current(w1))
        assertEquals(1, store.entries(w1, w1.plusDays(6), Kind.REAL_TASK).size)
        tasks.current(w1.plusDays(3))
        assertEquals(1, store.entries(w1, w1.plusDays(6), Kind.REAL_TASK).size)
        // Not done: next week repeats the rung.
        assertEquals(RealTasks.LADDER[0], tasks.current(w1.plusWeeks(1)).text)
        assertFalse(tasks.weekDone(w1))
        // Done in week 2.
        clock.set(w1.plusWeeks(1).plusDays(2), "20:00")
        val done = tasks.done()!!
        assertTrue(done.done)
        assertTrue(tasks.weekDone(w1.plusWeeks(1)))
        assertEquals(done, tasks.done())
        assertEquals(RealTasks.LADDER[1], tasks.current(w1.plusWeeks(2)).text)
        // Finishing the old week 1 task by its words also counts.
        assertEquals(RealTasks.LADDER[0], tasks.done(RealTasks.LADDER[0])!!.text)
        assertNull(tasks.done("沒有這個任務"))
        assertEquals(RealTasks.LADDER[2], tasks.peek(w1.plusWeeks(3)).text)
        // The ladder ends on its last rung.
        repeat(RealTasks.LADDER.size + 2) { i ->
            val wk = w1.plusWeeks(10L + i)
            clock.set(wk, "09:00")
            tasks.current(wk)
            tasks.done()
        }
        assertEquals(RealTasks.LADDER.last(), tasks.peek(w1.plusWeeks(40)).text)
    }
}

class BodyPhotosTest {
    @Test fun sundayPhotoAndTimeline() {
        val store = MemStore()
        val clock = TestClock().also { it.set(LocalDate.of(2030, 1, 13), "10:00") }
        val photos = BodyPhotos(store, clock)
        assertTrue(photos.isPhotoDay(LocalDate.of(2030, 1, 13)))
        assertFalse(photos.isPhotoDay(LocalDate.of(2030, 1, 14)))
        assertFalse(photos.doneOn(LocalDate.of(2030, 1, 13)))
        photos.record("p1")
        clock.set(LocalDate.of(2030, 1, 20), "10:00")
        photos.record("p2")
        assertTrue(photos.doneOn(LocalDate.of(2030, 1, 20)))
        assertEquals(listOf("p1", "p2"), photos.timeline().map { it.photoId })
        assertEquals(listOf("p2"), photos.timeline(LocalDate.of(2030, 1, 14)).map { it.photoId })
    }
}

class ExtrasTest {
    private val store = MemStore()
    private val clock = TestClock()
    private val rules = Kit.rules

    private fun items(day: LocalDate, stage: app.agency.core.stage.StageState, time: String = "10:00"): List<app.agency.core.plan.PlanItem> {
        clock.set(day, time)
        val state = Kit.stateOf(store, day, stage, clock)
        return Extras.items(day, clock.now(), store, rules, stage, state)
    }

    @Test fun nothingBeforeStageFour() {
        for (s in 1..3) {
            val stage = Kit.stage(s)
            for (d in 0L..30L) {
                assertTrue("$s $d", items(TestClock.MONDAY.plusDays(d), stage).isEmpty())
            }
        }
        assertTrue(
            Extras.notices(clock.now(), store, rules, Kit.stage(3), Kit.stateOf(store, TestClock.MONDAY, Kit.stage(3), clock)).isEmpty(),
        )
    }

    @Test fun anOrdinarySundayInStageFour() {
        val stage = Kit.stage(4, startedOn = LocalDate.of(2030, 1, 9))
        val sunday = LocalDate.of(2030, 1, 13)
        val list = items(sunday, stage)
        assertEquals(listOf("eval", "real_task", "body_photo", "theme"), list.map { it.id })
        assertTrue(list.all { it.feature.openAt(4) && it.status == ItemStatus.PENDING })
        assertEquals(Tap.Open(Screen.EVAL), list.first { it.id == "eval" }.tap)
        assertEquals(Tap.Open(Screen.BODY, "photo"), list.first { it.id == "body_photo" }.tap)
        assertTrue(list.first { it.id == "real_task" }.tap is Tap.Say)
        assertFalse(list.first { it.id == "real_task" }.counts)
        assertEquals(Feature.WEEKLY_EVAL, list.first { it.id == "eval" }.feature)
        // A weekday has only the real-person task and the theme practice.
        assertEquals(listOf("real_task", "theme"), items(sunday.plusDays(2), stage).map { it.id })
    }

    @Test fun lastSundayAddsTheMonthlyEvalAndStatusesFollowTheRecords() {
        val stage = Kit.stage(4, startedOn = LocalDate.of(2030, 1, 9))
        val last = LocalDate.of(2030, 1, 27)
        assertEquals(listOf("eval", "eval_monthly", "real_task", "body_photo", "theme"), items(last, stage).map { it.id })
        assertEquals(Tap.Open(Screen.EVAL, "monthly"), items(last, stage).first { it.id == "eval_monthly" }.tap)
        BodyPhotos(store, clock).record("p")
        ThemeWeeks.markDone(store, last, clock.now())
        RealTasks(store, clock).current(last)
        RealTasks(store, clock).done()
        val list = items(last, stage).associateBy { it.id }
        assertEquals(ItemStatus.DONE, list.getValue("body_photo").status)
        assertEquals(ItemStatus.DONE, list.getValue("theme").status)
        assertEquals(ItemStatus.DONE, list.getValue("real_task").status)
        assertEquals(ItemStatus.PENDING, list.getValue("eval").status)
        assertTrue(store.entries(last, Kind.REAL_TASK).isEmpty())
    }

    @Test fun personalTaskAppearsInTheMonthAfterTheEvaluation() {
        val stage = Kit.stage(4, startedOn = LocalDate.of(2030, 1, 9))
        val evals = Evaluations(store, clock, rules)
        val jan = EvalRec(
            EvalType.MONTHLY, "2030-01", emptyList(),
            listOf(EvalScore(EvalDim.VOCAL, 4, ScoreSource.CODE, ""), EvalScore(EvalDim.DANCE, 2, ScoreSource.SUGGESTED, "")),
        )
        store.add(LocalDate.of(2030, 1, 27), clock.now(), Kind.EVAL, jan)
        assertNotNull(evals.personalTask(YearMonth.of(2030, 2)))
        val feb = LocalDate.of(2030, 2, 5)
        val p = items(feb, stage).first { it.id == "task:PERSONAL_TASK" }
        assertEquals(Feature.MONTHLY_EVAL, p.feature)
        assertEquals(Tap.Open(Screen.TIMER, "PERSONAL_TASK"), p.tap)
        assertTrue(p.title.contains("舞蹈"))
        assertEquals(ItemStatus.PENDING, p.status)
        store.add(feb, clock.now(), Kind.TASK, TaskDone(TaskId.PERSONAL_TASK.name, minutes = 10))
        assertEquals(ItemStatus.DONE, items(feb, stage).first { it.id == "task:PERSONAL_TASK" }.status)
        assertTrue(items(LocalDate.of(2030, 3, 5), stage).none { it.id == "task:PERSONAL_TASK" })
    }

    @Test fun spotItemAppearsOnlyOnceAskedAndNoticeComesFromTheChecks() {
        val stage = Kit.stage(4, startedOn = LocalDate.of(2030, 1, 9))
        store.putDoc(SpotChecks.SALT_DOC, "7")
        val checks = SpotChecks(store, clock, rules)
        val slot = checks.plan(TestClock.MONDAY).first { it.day.dayOfWeek.value in rules.classWeekdays }
        val day = slot.day
        assertTrue(items(day, stage).none { it.id == "spot" })
        store.add(day, clock.now(), Kind.CHECK_IN, app.agency.core.store.CheckIn(4))
        clock.set(day, slot.at)
        val state = Kit.stateOf(store, day, stage, clock)
        val notices = Extras.notices(clock.now(), store, rules, stage, state)
        assertEquals(1, notices.size)
        assertTrue(notices.single().loud)
        assertTrue(Extras.notices(clock.now(), store, rules, stage, state).isEmpty())
        val spot = Extras.items(day, clock.now(), store, rules, stage, state).first { it.id == "spot" }
        assertEquals(ItemStatus.PENDING, spot.status)
        assertEquals(Tap.Open(Screen.CAMERA, "spot"), spot.tap)
        assertFalse(spot.counts)
        clock.plus(java.time.Duration.ofMinutes(11))
        val later = Extras.items(day, clock.now(), store, rules, stage, state).first { it.id == "spot" }
        assertEquals(ItemStatus.MISSED, later.status)
        SpotChecks(store, clock, rules).answer("late-photo")
        assertEquals(SpotStatus.MISSED, checks.todays(day).single().second.status)
    }
}
