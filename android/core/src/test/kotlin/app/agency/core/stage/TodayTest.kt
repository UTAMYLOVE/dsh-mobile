package app.agency.core.stage

import app.agency.core.base.TestClock
import app.agency.core.day.DayState
import app.agency.core.day.Kit
import app.agency.core.lessons.LessonDay
import app.agency.core.lessons.Levels
import app.agency.core.plan.DayPlanner
import app.agency.core.plan.TaskId
import app.agency.core.plan.WeekSoFar
import app.agency.core.rules.Fixtures
import app.agency.core.store.Doc
import app.agency.core.store.MealSlot
import app.agency.core.store.MemStore
import app.agency.core.store.RidePhase
import app.agency.core.store.StatusType
import app.agency.core.store.writeDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayTest {
    private val rules = Fixtures.rules()
    private val wednesday = Kit.wednesday

    @Test fun planEqualsAHandAssembledPlannerCallForAStageTwoClassDay() {
        val store = MemStore()
        val stage = Kit.stage(2, wednesday)
        store.writeDoc(Doc.STAGE, stage)
        Kit.task(store, Kit.monday, "20:00", TaskId.CORE)
        Kit.checkIn(store, Kit.tuesday, "06:50")
        Kit.checkOut(store, Kit.tuesday, "23:30")
        Kit.checkIn(store, wednesday, "06:55")
        Kit.meal(store, wednesday, "11:50", MealSlot.FIRST, 700)
        Kit.water(store, wednesday, "09:00", 500)
        Kit.ride(store, wednesday, "19:40", RidePhase.OUT)
        val clock = TestClock().also { it.set(wednesday, "19:45") }

        val state = DayState.of(
            wednesday, store.entries(wednesday), store.entries(wednesday.minusDays(14), wednesday.minusDays(1)), rules, stage, clock.zone,
        )
        val expected = DayPlanner.plan(
            day = wednesday,
            now = clock.now(),
            rules = rules,
            stageState = stage,
            state = state,
            week = WeekSoFar(Kit.monday),
            lessons = LessonDay.of(wednesday, store, rules, false, Levels()),
            habits = emptyList(),
            training = rules.training,
        )
        val actual = Today.plan(store, clock, rules)
        assertEquals(expected, actual)
        assertTrue(actual.items.any { it.id == "meal:FIRST" } && actual.items.any { it.id.startsWith("lesson:") })
        assertTrue(actual.riding)
    }

    @Test fun planBeforeJoiningIsAStageOnePlanAndWritesNothing() {
        val store = MemStore()
        val clock = TestClock()
        val plan = Today.plan(store, clock, rules)
        assertTrue(plan.items.all { it.feature.stage <= 1 })
        assertTrue(plan.items.isNotEmpty())
        assertNull(store.doc(Doc.STAGE))
    }

    @Test fun aLongIllnessIsNotForgotten() {
        val store = MemStore()
        store.writeDoc(Doc.STAGE, Kit.stage(2, wednesday))
        Kit.status(store, wednesday.minusDays(40), "09:00", StatusType.SICK)
        val clock = TestClock().also { it.set(wednesday, "09:00") }
        val plan = Today.plan(store, clock, rules)
        assertTrue(plan.unwell)
        assertTrue(plan.adjustments.any { it.what.contains("看醫生") })
        assertTrue(Today.state(store, wednesday, rules, Kit.stage(2, wednesday), clock.zone).sickDays > 40)
    }

    @Test fun anIllnessRecoveredLongAgoIsOver() {
        val store = MemStore()
        store.writeDoc(Doc.STAGE, Kit.stage(2, wednesday))
        Kit.status(store, wednesday.minusDays(40), "09:00", StatusType.SICK)
        Kit.status(store, wednesday.minusDays(30), "09:00", StatusType.RECOVERED)
        val clock = TestClock().also { it.set(wednesday, "09:00") }
        val plan = Today.plan(store, clock, rules)
        assertFalse(plan.unwell)
    }

    @Test fun progressionOffsetsReachTheStrengthItem() {
        val store = MemStore()
        store.writeDoc(Doc.STAGE, Kit.stage(2, wednesday))
        store.writeDoc(Doc.LEVELS, Levels(trainingOffsets = mapOf("squats" to 2, "plankSeconds" to 10)))
        Kit.ride(store, wednesday, "19:40", RidePhase.OUT)
        Kit.ride(store, wednesday, "20:30", RidePhase.BACK, 900)
        val clock = TestClock().also { it.set(wednesday, "21:10") }
        val strength = Today.plan(store, clock, rules).items.first { it.id == "task:STRENGTH" }
        assertTrue(strength.detail, strength.detail.contains("深蹲 20 下") && strength.detail.contains("棒式 60 秒"))
    }
}
