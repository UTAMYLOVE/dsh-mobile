package app.agency.core.week

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.lessons.Levels
import app.agency.core.rules.Fixtures
import app.agency.core.stage.Rollover
import app.agency.core.stage.StageState
import app.agency.core.stage.Today
import app.agency.core.store.Doc
import app.agency.core.store.Feel
import app.agency.core.store.FoodSource
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealItem
import app.agency.core.store.MealSlot
import app.agency.core.store.MemStore
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.TaskDone
import app.agency.core.store.add
import app.agency.core.store.readDoc
import app.agency.core.store.update
import app.agency.core.store.writeDoc
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {
    private val rules = Fixtures.rules() // 4 rounds, 12 push-ups, 18 squats, 50 s plank, 110 g protein
    private val day: LocalDate = TestClock.MONDAY.plusDays(60)
    private val zone = TestClock.ZONE
    private val clock = TestClock().also { it.set(day, "07:00") }

    private fun at(d: LocalDate, h: Int = 20) = Days.at(d, LocalTime.of(h, 0), zone)

    /** A store where every condition holds for the 14 days before [day] and [extraDays] after it. */
    private fun rig(
        rounds: Int = 4,
        easy: Int = 2,
        protein: Double = 110.0,
        skip: LocalDate? = null,
        reduced: LocalDate? = null,
        extraDays: Int = 0,
        stage: StageState = StageState(joinedOn = day.minusDays(50).toString(), stage = 2, startedOn = day.minusDays(30).toString()),
    ): MemStore {
        val s = MemStore()
        s.writeDoc(Doc.STAGE, stage)
        for (i in 14 downTo -extraDays) {
            val d = day.minusDays(i.toLong())
            if (d != skip) {
                val feel = if (Math.floorMod(d.toEpochDay(), 7L) == 3L) Feel.EASY else Feel.OK
                s.add(
                    d, at(d), Kind.TASK,
                    TaskDone("STRENGTH", rounds = rounds, feel = if (easy == 0) Feel.OK else feel, reduced = d == reduced),
                )
            }
            s.add(
                d, at(d, 13), Kind.MEAL,
                Meal(MealSlot.FIRST, listOf(MealItem("便當", 790, protein, FoodSource.TABLE)), "p"),
            )
        }
        return s
    }

    private fun raise(s: MemStore, on: LocalDate = day) = Progression.maybeRaise(s, clock, rules, on)

    private fun offsets(s: MemStore) = (s.readDoc<Levels>(Doc.LEVELS) ?: Levels()).trainingOffsets

    @Test fun effectiveIsRulesPlusOffsets() {
        val l = Levels(trainingOffsets = mapOf("pushUps" to 4, "plankSeconds" to 10))
        val t = Progression.effective(rules, l)
        assertEquals(16, t.pushUps)
        assertEquals(18, t.squats)
        assertEquals(60, t.plankSeconds)
        assertEquals(rules.training, Progression.effective(rules, Levels()))
    }

    @Test fun raisesPushUpsFirstAndSaysWhy() {
        val s = rig()
        val a = raise(s)
        assertNotNull(a)
        assertTrue(a!!.what.contains("伏地挺身") && a.what.contains("14"))
        assertTrue(a.why.isNotBlank())
        assertEquals(mapOf("pushUps" to 2), offsets(s))
        assertEquals(day.toString(), s.readDoc<StageState>(Doc.STAGE)!!.lastRaiseOn)
        assertEquals(14, Progression.effective(rules, s.readDoc<Levels>(Doc.LEVELS)!!).pushUps)
    }

    @Test fun holdsFourteenDaysThenRotates() {
        val s = rig(extraDays = 50)
        assertNotNull(raise(s, day))
        for (k in 1..13) assertNull("day +$k", raise(s, day.plusDays(k.toLong())))
        assertNotNull(raise(s, day.plusDays(14)))
        assertEquals(mapOf("pushUps" to 2, "squats" to 2), offsets(s))
        assertNull(raise(s, day.plusDays(20)))
        assertNotNull(raise(s, day.plusDays(28)))
        assertEquals(mapOf("pushUps" to 2, "squats" to 2, "plankSeconds" to 10), offsets(s))
        assertNotNull(raise(s, day.plusDays(42)))
        assertEquals(mapOf("pushUps" to 4, "squats" to 2, "plankSeconds" to 10), offsets(s))
    }

    @Test fun neverGoesDown() {
        val s = rig()
        raise(s)
        // everything gets worse afterwards: nothing is taken back
        s.add(day.plusDays(3), at(day.plusDays(3)), Kind.STATUS, Status(StatusType.SICK))
        assertNull(raise(s, day.plusDays(20)))
        assertEquals(mapOf("pushUps" to 2), offsets(s))
    }

    @Test fun aMissedStrengthDayBlocks() = assertNull(raise(rig(skip = day.minusDays(6))))

    @Test fun notFullRoundsBlocks() = assertNull(raise(rig(rounds = 3)))

    @Test fun aReducedDayBlocks() = assertNull(raise(rig(reduced = day.minusDays(2))))

    @Test fun fewerThanTwoEasyBlocks() {
        assertNull(raise(rig(easy = 0)))
        val one = rig()
        // keep a single EASY by turning the other one into OK
        val easies = one.entries(day.minusDays(14), day.minusDays(1), Kind.TASK).filter { it.read<TaskDone>().feel == Feel.EASY }
        assertEquals(2, easies.size)
        one.update(easies[0].id, TaskDone("STRENGTH", rounds = 4, feel = Feel.OK))
        assertNull(raise(one))
    }

    @Test fun sicknessInTheWindowBlocks() {
        val s = rig()
        s.add(day.minusDays(8), at(day.minusDays(8)), Kind.STATUS, Status(StatusType.SICK))
        s.add(day.minusDays(6), at(day.minusDays(6)), Kind.STATUS, Status(StatusType.RECOVERED))
        assertNull(raise(s))
    }

    @Test fun anInjuryInTheWindowBlocks() {
        val s = rig()
        s.add(day.minusDays(2), at(day.minusDays(2)), Kind.STATUS, Status(StatusType.INJURED))
        assertNull(raise(s))
    }

    @Test fun beingUnwellWhenTheWindowStartsBlocksEvenWithoutAnEntryInside() {
        val s = rig()
        s.add(day.minusDays(30), at(day.minusDays(30)), Kind.STATUS, Status(StatusType.SICK))
        assertNull(raise(s))
    }

    @Test fun anOldRecoveredIllnessDoesNotBlock() {
        val s = rig()
        s.add(day.minusDays(30), at(day.minusDays(30)), Kind.STATUS, Status(StatusType.SICK))
        s.add(day.minusDays(20), at(day.minusDays(20)), Kind.STATUS, Status(StatusType.RECOVERED))
        assertNotNull(raise(s))
    }

    @Test fun lowProteinBlocksAndNinetyPercentIsEnough() {
        assertNull(raise(rig(protein = 95.0)))
        assertNotNull(raise(rig(protein = 99.0)))
    }

    @Test fun strengthMustHaveBeenOpenForTheWholeWindow() {
        val recent = StageState(joinedOn = day.minusDays(50).toString(), stage = 2, startedOn = day.minusDays(5).toString())
        assertNull(raise(rig(stage = recent)))
        val stage1 = StageState(joinedOn = day.minusDays(50).toString(), stage = 1, startedOn = day.minusDays(5).toString())
        assertNull(raise(rig(stage = stage1)))
    }

    @Test fun rolloverRaisesAndTodaysPlanShowsTheNewLoad() {
        val s = rig()
        s.writeDoc(
            Doc.STAGE,
            StageState(
                joinedOn = day.minusDays(50).toString(), stage = 2, startedOn = day.minusDays(30).toString(),
                closedThrough = day.minusDays(1).toString(),
            ),
        )
        val res = Rollover.run(s, clock, rules)
        assertNotNull(res.adjustment)
        assertNull(Rollover.run(s, clock, rules).adjustment)
        val plan = Today.plan(s, clock, rules)
        assertTrue(plan.items.first { it.id == "task:STRENGTH" }.detail.contains("伏地挺身 14 下"))
        assertTrue(plan.adjustments.any { it.what.contains("訓練量提高") })
    }
}
