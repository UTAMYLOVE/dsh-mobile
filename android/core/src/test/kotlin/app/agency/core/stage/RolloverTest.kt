package app.agency.core.stage

import app.agency.core.base.Days
import app.agency.core.rules.LevelRule
import app.agency.core.store.Kind
import app.agency.core.store.Photocard
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Body
import app.agency.core.store.WeekResult
import app.agency.core.store.add
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolloverTest {
    // ---- rounds -------------------------------------------------------------------------------------------------------

    @Test fun aPerfectStageOneWeekOpensStageTwoOnDayEightWithACeremony() {
        val r = StageRig().apply { join(); perfectDays(1, 7) }
        val before = r.run(r.day(7))
        assertEquals(6, before.closedDays.size)
        assertEquals(1, r.stage().stage)
        assertTrue(r.ceremonies("stage").isEmpty())

        val res = r.run(r.day(8))
        assertEquals(listOf(2), res.stagesOpened)
        assertEquals(2, r.stage().stage)
        assertEquals(r.day(8).toString(), r.stage().startedOn)
        val round = r.stage().rounds.single()
        assertTrue(round.passed)
        assertEquals(100, round.completionPct)
        assertEquals(r.day(1).toString(), round.from)
        assertEquals(r.day(7).toString(), round.to)
        val ceremony = r.ceremonies("stage").single()
        assertEquals("進入第 2 階段", ceremony.title)
        assertTrue(Feature.MEALS.label in ceremony.text)
        assertEquals("第 2 階段 · 第 1 天", Stages.label(r.stage(), r.day(8)))
    }

    @Test fun aWeekAtEightyPercentRepeatsTheStageWithPlainReasons() {
        val r = StageRig(subjects = listOf("語言", "禮儀")).apply {
            join()
            (1..7).forEach { note(day(it), "語言") }
            (1..4).forEach { note(day(it), "禮儀") }
        }
        val res = r.run(r.day(8))
        assertEquals(1, r.stage().stage)
        assertEquals(r.day(8).toString(), r.stage().startedOn)
        val round = res.roundsFailed.single()
        assertFalse(round.passed)
        assertEquals(78, round.completionPct)
        assertEquals(listOf("完成率 78%，要 90%"), round.short)
        assertTrue(res.stagesOpened.isEmpty())
        assertTrue(r.ceremonies("stage").isEmpty())
        assertEquals(1, r.stage().rounds.size)
    }

    @Test fun aWeekWithOneViolationRepeats() {
        val r = StageRig().apply { join(); perfectDays(1, 7); violation(day(3)) }
        val res = r.run(r.day(8))
        val round = res.roundsFailed.single()
        assertEquals(100, round.completionPct)
        assertEquals(1, round.violations)
        assertEquals(listOf("有 1 次違規"), round.short)
        assertEquals(1, r.stage().stage)
        assertEquals(r.day(8).toString(), r.stage().startedOn)
    }

    @Test fun anEmptyRoundCountsAsAHundredPercent() {
        // exam week with no lessons allowed: nothing is counted for seven days
        val r = StageRig(extra = { it.copy(study = it.study.copy(examWeekPerDay = 0)) }).apply { join() }
        r.store.add(
            r.day(1), Days.at(r.day(1), LocalTime.of(8, 0), r.clock.zone), Kind.STATUS,
            app.agency.core.store.Status(app.agency.core.store.StatusType.EXAM_WEEK),
        )
        val res = r.run(r.day(8))
        assertTrue(r.closes().all { it.total == 0 })
        val round = res.rounds.single()
        assertEquals(100, round.completionPct)
        assertTrue(round.passed)
        assertEquals(0, cards(r).size)
    }

    // ---- idempotence and catching up ------------------------------------------------------------------------------------

    @Test fun runningTwiceInTheSameMinuteChangesNothing() {
        val r = StageRig().apply { join(); perfectDays(1, 7) }
        r.run(r.day(8))
        val entries = r.store.entries(r.monday, r.monday.plusDays(60)).size
        val messages = r.store.messages(500).size
        val stage = r.stage()
        val second = Rollover.run(r.store, r.clock, r.rules)
        assertEquals(entries, r.store.entries(r.monday, r.monday.plusDays(60)).size)
        assertEquals(messages, r.store.messages(500).size)
        assertEquals(stage, r.stage())
        assertTrue(second.closedDays.isEmpty() && second.rounds.isEmpty() && !second.changed)
    }

    @Test fun missedDaysAreClosedInOrder() {
        val r = StageRig().apply { join(); perfectDays(1, 2) }
        r.run(r.day(3))
        assertEquals(r.day(2).toString(), r.stage().closedThrough)
        val res = r.run(r.day(6))
        assertEquals(listOf(r.day(3), r.day(4), r.day(5)), res.closedDays)
        assertEquals(r.day(5).toString(), r.stage().closedThrough)
        val closes = r.store.entries(r.monday, r.day(7), Kind.DAY_CLOSE)
        assertEquals((1..5).map { r.day(it) }, closes.map { it.day })
        assertTrue(r.closes().drop(2).all { it.done == 0 && it.total == 1 && !it.perfect })
        assertEquals(mapOf("lesson:1" to "語言：等小葵找影片"), r.closes().last().missed)
    }

    @Test fun comingBackAfterTwoRoundsJudgesBothInOrder() {
        val r = StageRig().apply { join(); perfectDays(1, 7) }
        val res = r.run(r.day(15))
        assertEquals(2, res.rounds.size)
        assertTrue(res.rounds[0].passed)
        assertFalse(res.rounds[1].passed)
        assertEquals(2, res.rounds[1].stage)
        assertEquals(0, res.rounds[1].completionPct)
        assertEquals(2, r.stage().stage)
        assertEquals(r.day(15).toString(), r.stage().startedOn)
    }

    @Test fun nothingHappensBeforeHeHasJoined() {
        val r = StageRig()
        val res = r.run(r.day(3))
        assertFalse(res.changed)
        assertNull(Stages.get(r.store))
    }

    // ---- stage 4: real task, level, talk ------------------------------------------------------------------------------

    private fun stage4(r: StageRig) = r.setStage(StageState(joinedOn = r.monday.toString(), stage = 4, startedOn = r.monday.toString()))

    @Test fun theRealPersonTaskIsRequiredOnlyOnceItWasOpenForTheWholeRound() {
        val three = StageRig().apply {
            setStage(StageState(joinedOn = monday.toString(), stage = 3, startedOn = monday.toString()))
            perfectDays(1, 7)
        }
        assertTrue(three.run(three.day(8)).rounds.single().passed)

        val four = StageRig().apply { stage4(this); perfectDays(1, 7) }
        val res = four.run(four.day(8))
        val round = res.roundsFailed.single()
        assertEquals(listOf("這一輪的真人任務還沒完成"), round.short)

        val done = StageRig().apply { stage4(this); perfectDays(1, 7); realTask(day(5)) }
        assertTrue(done.run(done.day(8)).rounds.single().passed)

        val notDone = StageRig().apply { stage4(this); perfectDays(1, 7); realTask(day(5), done = false) }
        assertFalse(notDone.run(notDone.day(8)).rounds.single().passed)
    }

    @Test fun levelGoesUpWithPassedWeeksAndNeverDown() {
        val r = StageRig(level = LevelRule(passPct = 90, weeksToB = 2, weeksToA = 3, talkAtViolations = 3))
        stage4(r)
        for (w in 0..2) {
            r.perfectDays(w * 7 + 1, w * 7 + 7)
            r.realTask(r.day(w * 7 + 2))
        }
        val first = r.run(r.day(8))
        assertEquals("C", r.stage().level)
        assertEquals(1, r.stage().passStreak)
        assertTrue(first.levelUps.isEmpty())

        val second = r.run(r.day(15))
        assertEquals(listOf("B"), second.levelUps)
        assertEquals("B", r.stage().level)
        assertEquals(2, r.stage().passedWeeks)
        assertEquals("level", r.ceremonies("level").single().kind)

        val third = r.run(r.day(22))
        assertEquals(listOf("A"), third.levelUps)
        assertEquals("A", r.stage().level)
        assertEquals(2, r.ceremonies("level").size)
        assertTrue(r.ceremonies("level").last().title.contains("A"))

        // a failed week: streak resets, passed weeks and level stay
        val bad = r.run(r.day(29))
        assertFalse(bad.rounds.single().passed)
        assertEquals("A", r.stage().level)
        assertEquals(0, r.stage().passStreak)
        assertEquals(3, r.stage().passedWeeks)
        assertEquals(4, r.stage().stage)
    }

    @Test fun manyViolationsDoNotCountAndSetTheTalkFlag() {
        val r = StageRig()
        stage4(r)
        r.perfectDays(1, 14)
        r.realTask(r.day(2))
        r.realTask(r.day(9))
        repeat(3) { r.violation(r.day(4), "OVER_KCAL") }
        val res = r.run(r.day(8))
        assertTrue(res.talk)
        assertTrue(r.stage().talk)
        assertEquals(0, r.stage().passStreak)
        assertEquals(0, r.stage().passedWeeks)
        assertFalse(res.rounds.single().passed)

        val next = r.run(r.day(15))
        assertTrue(next.rounds.single().passed)
        assertFalse(r.stage().talk)
        assertEquals(1, r.stage().passStreak)
    }

    @Test fun oneOrTwoViolationsFailTheRoundButDoNotSetTalk() {
        val r = StageRig()
        stage4(r)
        r.perfectDays(1, 7)
        r.realTask(r.day(2))
        repeat(2) { r.violation(r.day(4)) }
        r.run(r.day(8))
        assertFalse(r.stage().talk)
        assertEquals(0, r.stage().passStreak)
    }

    // ---- photocards -----------------------------------------------------------------------------------------------------

    private fun cards(r: StageRig) = r.store.entries(r.monday, r.monday.plusDays(200), Kind.CARD).map { it.read<Photocard>() }

    @Test fun photocardsAtSevenAndThirtyPerfectDaysOncePerStreak() {
        val r = StageRig().apply { join(); perfectDays(1, 7) }
        val res = r.run(r.day(8))
        assertEquals(listOf(Photocard("7", r.day(7).toString())), res.photocards)
        assertEquals(1, r.ceremonies("photocard").size)

        r.perfectDays(8, 30)
        r.run(r.day(31))
        assertEquals(listOf("7", "30"), cards(r).map { it.kind })
        assertEquals(r.day(30).toString(), cards(r).last().earnedOn)
        r.run(r.day(31), "09:00")
        assertEquals(2, cards(r).size)
        assertEquals(2, r.ceremonies("photocard").size)

        // day 31 missed: the streak is broken; seven more perfect days earn a new seven-day card
        r.perfectDays(32, 38)
        r.run(r.day(39))
        assertEquals(listOf("7", "30", "7"), cards(r).map { it.kind })
    }

    @Test fun aViolationOrAMissedItemKeepsTheCardAway() {
        val r = StageRig().apply { join(); perfectDays(1, 7); violation(day(4)) }
        val res = r.run(r.day(8))
        assertTrue(res.photocards.isEmpty())
        assertEquals(0, cards(r).size)
    }

    // ---- weekly result --------------------------------------------------------------------------------------------------

    @Test fun theWeeklyResultIsWrittenOnMondayForTheWeekBefore() {
        val r = StageRig(extra = { it.copy(ride = app.agency.core.rules.RideRule(weeklyIncome = 6000)) })
        r.join()
        r.perfectDays(1, 7)
        fun ride(d: LocalDate, phase: RidePhase, income: Int? = null) =
            r.store.add(d, Days.at(d, LocalTime.of(20, 0), r.clock.zone), Kind.RIDE, Ride(phase, income))
        ride(r.day(2), RidePhase.OUT); ride(r.day(2), RidePhase.BACK, 1000)
        ride(r.day(3), RidePhase.OUT); ride(r.day(3), RidePhase.BACK, 1200)
        r.store.add(r.day(1).minusDays(3), Days.at(r.day(1), LocalTime.of(8, 0), r.clock.zone), Kind.BODY, Body(kg = 80.0))
        r.store.add(r.day(5), Days.at(r.day(5), LocalTime.of(8, 0), r.clock.zone), Kind.BODY, Body(kg = 79.2))

        r.run(r.day(7))
        assertTrue(r.store.entries(r.monday, r.day(7), Kind.WEEK).isEmpty())
        val res = r.run(r.day(8))
        val e = r.store.entries(r.day(8), Kind.WEEK).single()
        val w = e.read<WeekResult>()
        assertEquals(listOf(w), res.weeks)
        assertEquals(r.monday.toString(), w.weekStart)
        assertEquals(100, w.completionPct)
        assertEquals(0, w.violations)
        assertEquals(2200, w.rideIncome)
        assertEquals(6000, w.rideTarget)
        assertEquals(2, w.rideDays)
        assertEquals(79.2, w.weightKg!!, 0.001)
        assertEquals(-0.8, w.weightChangeKg!!, 0.001)
        assertEquals(7, w.perfectDays)
        val summary = r.summaries().single { it.title == "上週成績" }
        assertTrue("完成率 100%" in summary.lines)
        assertTrue(summary.lines.any { it.contains("2200／6000") })

        r.run(r.day(8), "12:00")
        assertEquals(1, r.store.entries(r.day(8), Kind.WEEK).size)
    }

    // ---- special days ---------------------------------------------------------------------------------------------------

    @Test fun dayThirtyIsCelebratedOnce() {
        val r = StageRig().apply { join() }
        val early = r.run(r.day(29))
        assertTrue(early.specials.none { it == "day30" })
        val res = r.run(r.day(30))
        assertEquals(listOf("day30"), res.specials)
        val c = r.ceremonies("special").single()
        assertEquals("第 30 天", c.title)
        r.run(r.day(30), "21:00")
        r.run(r.day(31))
        assertEquals(1, r.ceremonies("special").size)
        assertTrue("day30" in r.stage().celebrated)
    }

    @Test fun aPublicHolidayIsCelebratedOnce() {
        val r = StageRig()
        r.setStage(StageState(joinedOn = LocalDate.of(2029, 12, 28).toString()))
        val res = r.run(LocalDate.of(2030, 1, 1))
        assertEquals(listOf("hol:2030-01-01"), res.specials)
        assertEquals("元旦", r.ceremonies("special").single().title)
        r.run(LocalDate.of(2030, 1, 1), "22:00")
        assertEquals(1, r.ceremonies("special").size)
    }
}
