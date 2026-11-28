package app.agency.core.plan

import app.agency.core.day.Kit
import app.agency.core.day.Kit.monday
import app.agency.core.day.Kit.saturday
import app.agency.core.day.Kit.sunday
import app.agency.core.day.Kit.tuesday
import app.agency.core.day.Kit.wednesday
import app.agency.core.rules.Rules
import app.agency.core.rules.RulesCheck
import app.agency.core.stage.Feature
import app.agency.core.stage.StageRound
import app.agency.core.stage.StageState
import app.agency.core.store.MealSlot
import app.agency.core.store.RidePhase
import app.agency.core.store.StatusType
import app.agency.core.store.add
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPlannerTest {
    // ---- which items exist --------------------------------------------------------------------------------------------

    @Test fun stageOneShowsOnlyLessons() {
        val sc = Scene(stageN = 1)
        val lessons = listOf(
            LessonSlot(1, "語言", 11L, "問候語", done = false),
            LessonSlot(2, "禮儀", null, "", done = false),
        )
        val plan = sc.plan("09:00", lessons = lessons)
        assertEquals(listOf("lesson:1", "lesson:2"), plan.ids())
        assertTrue(plan.items.all { it.feature == Feature.LESSONS })
        assertEquals(Tap.Open(Screen.LESSONS), plan.item("lesson:1").tap)
        assertEquals(Tap.Say("幫我找今天的禮儀影片"), plan.item("lesson:2").tap)
        assertEquals("lesson:1", plan.next?.id)
    }

    @Test fun stageTwoOpensTheDailyRoutineButNotLaterThings() {
        val plan = Scene(stageN = 2).plan("06:30")
        val expected = setOf(
            "check_in", "check_out", "meal:FIRST", "meal:DINNER", "meal:NIGHT", "water", "ride:out", "ride:back",
            "task:CORE", "task:STRENGTH", "task:STRETCH", "body",
        )
        assertEquals(expected, plan.ids().toSet())
    }

    @Test fun stageThreeAddsItsFamiliesOnAClassDay() {
        val plan = Scene(stageN = 3).plan("06:30")
        for (id in listOf("task:MORNING_STRETCH", "task:VOCAL_BASICS", "task:SUNSCREEN", "task:POSTURE", "task:CARDIO")) {
            assertTrue(id, plan.has(id))
        }
        for (id in listOf("task:WALK", "task:DANCE", "task:VOCAL", "task:EXPRESSION", "task:GROOMING")) {
            assertFalse(id, plan.has(id))
        }
    }

    @Test fun stageFourAddsPracticeAndGroomingOnAFreeDay() {
        val plan = Scene(day = saturday, stageN = 4).plan("09:00")
        for (id in listOf("task:WALK", "task:DANCE", "task:VOCAL", "task:EXPRESSION", "task:GROOMING")) {
            assertTrue(id, plan.has(id))
        }
    }

    @Test fun everyItemBelongsToAnOpenFeature() {
        for (n in 1..4) {
            val plan = Scene(day = saturday, stageN = n).plan("09:00", lessons = listOf(LessonSlot(1, "語言", 1L, "x", false)))
            assertTrue("stage $n", plan.items.all { it.feature.openAt(n) })
        }
    }

    @Test fun anItemNeedsItsRuleValues() {
        val base = Kit.rules
        val noCore = base.copy(training = base.training.copy(coreMinutes = 0))
        assertFalse(Scene(rules = noCore).plan("09:00").has("task:CORE"))
        assertTrue(Scene(rules = noCore).plan("09:00").has("task:STRENGTH"))
        assertEquals(listOf("核心分鐘數"), RulesCheck.missing(noCore, Feature.CORE))

        val noWater = base.copy(water = base.water.copy(bottleMl = 0))
        assertFalse(Scene(rules = noWater).plan("09:00").has("water"))

        val noDiet = base.copy(diet = base.diet.copy(kcal = 0))
        assertFalse(Scene(rules = noDiet).plan("09:00").ids().any { it.startsWith("meal:") })

        val noLeave = base.copy(classDay = base.classDay.copy(leaveBy = ""))
        val plan = Scene(rules = noLeave, stageN = 3).plan("09:00")
        assertFalse(plan.has("ride:out"))
        assertFalse(plan.has("water"))
        assertFalse(plan.has("task:SUNSCREEN"))
    }

    @Test fun anEmptyRulesDocumentLeavesOnlyLessons() {
        val plan = Scene(rules = Rules(), stageN = 4).plan("09:00", lessons = listOf(LessonSlot(1, "語言", 1L, "x", false)))
        assertEquals(listOf("lesson:1"), plan.ids())
    }

    @Test fun missingNamesAreInChineseAndEmptyWhenReady() {
        assertTrue(RulesCheck.missing(Kit.rules, Feature.MEALS).isEmpty())
        val missing = RulesCheck.missing(Rules(), Feature.MEALS)
        assertTrue(missing.isNotEmpty())
        assertTrue(missing.all { m -> m.none { it in 'a'..'z' || it in 'A'..'Z' } })
        assertTrue(RulesCheck.missing(Rules(), Feature.LESSONS).isEmpty())
        assertTrue(RulesCheck.missing(Rules(), Feature.MEMO).isEmpty())
    }

    @Test fun aMalformedTimeCountsAsMissing() {
        val bad = Kit.rules.copy(freeDay = Kit.rules.freeDay.copy(walk = "下午"))
        assertEquals(listOf("假日快走時間"), RulesCheck.missing(bad, Feature.WALK))
    }

    @Test fun extraItemsOfAClosedFeatureAreDropped() {
        val e = PlanItem("eval", Feature.WEEKLY_EVAL, "每週評鑑", null, null, ItemStatus.PENDING, true, Tap.Open(Screen.EVAL))
        assertFalse(Scene(day = sunday, stageN = 3).plan("09:00", extra = listOf(e)).has("eval"))
        assertTrue(Scene(day = sunday, stageN = 4).plan("09:00", extra = listOf(e)).has("eval"))
    }

    @Test fun habitsBecomeCountedItems() {
        val plan = Scene(stageN = 1).plan("09:00", habits = listOf(HabitToday(7, "睡前閱讀", false)))
        val h = plan.item("habit:7")
        assertTrue(h.counts)
        assertEquals(ItemStatus.PENDING, h.status)
        assertEquals(0, plan.done)
        assertEquals(1, plan.total)
    }

    @Test fun weighInOnlyOnItsWeekday() {
        assertTrue(Scene(day = monday).plan("09:00").has("body"))
        assertFalse(Scene(day = tuesday).plan("09:00").has("body"))
    }

    @Test fun groomingOnlyWhenSomethingIsDue() {
        val sc = Scene(day = saturday, stageN = 4)
        val fresh = WeekSoFar(sc.monday, groomed = mapOf("指甲" to saturday.minusDays(2), "修眉" to saturday.minusDays(3)))
        assertFalse(sc.plan("09:00", week = fresh).has("task:GROOMING"))
        val due = WeekSoFar(sc.monday, groomed = mapOf("指甲" to saturday.minusDays(7), "修眉" to saturday.minusDays(3)))
        assertEquals("指甲", sc.plan("09:00", week = due).item("task:GROOMING").detail)
        assertEquals("指甲、修眉", sc.plan("09:00").item("task:GROOMING").detail)
    }

    // ---- statuses ---------------------------------------------------------------------------------------------------

    @Test fun checkInGoesPendingLateMissed() {
        val sc = Scene()
        assertEquals(ItemStatus.PENDING, sc.plan("06:30").item("check_in").status)
        assertEquals(ItemStatus.LATE, sc.plan("07:30").item("check_in").status)
        val closed = sc.plan("07:30", now = Kit.at(tuesday, "06:30"))
        assertEquals(ItemStatus.MISSED, closed.item("check_in").status)
    }

    @Test fun recordedItemsAreDoneEvenWhenClosed() {
        val sc = Scene()
        Kit.checkIn(sc.store, monday, "07:20")
        assertEquals(ItemStatus.DONE, sc.plan("08:00").item("check_in").status)
        assertEquals("7:20", sc.plan("08:00").item("check_in").detail)
        assertEquals(ItemStatus.DONE, sc.plan("08:00", now = Kit.at(tuesday, "06:30")).item("check_in").status)
    }

    @Test fun deadlineItemsAreLateOnlyAfterTheirDeadline() {
        val sc = Scene()
        assertEquals(ItemStatus.PENDING, sc.plan("19:00").item("water").status)
        assertEquals(ItemStatus.LATE, sc.plan("19:45").item("water").status)
        assertEquals(ItemStatus.PENDING, sc.plan("19:00").item("ride:out").status)
        assertEquals(ItemStatus.LATE, sc.plan("19:45").item("ride:out").status)
    }

    @Test fun checkOutIsDueHalfAnHourBeforeBedtimeAndLateAfterIt() {
        val sc = Scene()
        val p = sc.plan("23:00")
        assertEquals(ItemStatus.PENDING, p.item("check_out").status)
        assertEquals(java.time.LocalTime.of(0, 30), p.item("check_out").at)
        assertEquals(java.time.LocalTime.of(1, 0), p.item("check_out").until)
        assertEquals(ItemStatus.LATE, sc.plan("01:10").item("check_out").status)
    }

    @Test fun aMealIsDoneOnlyWithAPhoto() {
        val sc = Scene()
        Kit.meal(sc.store, monday, "12:05", MealSlot.FIRST, 650, photo = false)
        val noPhoto = sc.plan("13:00").item("meal:FIRST")
        assertEquals(ItemStatus.PENDING, noPhoto.status)
        assertEquals(PlanDetail.NO_PHOTO, noPhoto.detail)
        Kit.meal(sc.store, monday, "13:10", MealSlot.FIRST, 0, photo = true, late = true)
        val late = sc.plan("14:00").item("meal:FIRST")
        assertEquals(ItemStatus.DONE, late.status)
        assertTrue(late.detail.contains(PlanDetail.LATE_PHOTO))
    }

    @Test fun anUnloggedMealShowsItsBudgetAndAfterwardsItsKcal() {
        val sc = Scene()
        assertEquals("預算 800 大卡", sc.plan("11:00").item("meal:FIRST").detail)
        Kit.meal(sc.store, monday, "12:00", MealSlot.FIRST, 650)
        assertEquals("650 大卡", sc.plan("12:30").item("meal:FIRST").detail)
        assertEquals("預算 900 大卡", sc.plan("12:30").item("meal:DINNER").detail)
    }

    @Test fun waterIsDoneAtTheTarget() {
        val sc = Scene()
        Kit.water(sc.store, monday, "09:00", 500)
        assertEquals(ItemStatus.PENDING, sc.plan("10:00").item("water").status)
        Kit.water(sc.store, monday, "10:00", 500)
        assertEquals(ItemStatus.DONE, sc.plan("10:30").item("water").status)
    }

    @Test fun waterDetailNamesTheGoalAndTheDeadline() {
        val p = Scene().plan("09:00")
        assertEquals("跑單前喝完 · 1 壺", p.item("water").detail)
        assertEquals("log_water", (p.item("water").tap as Tap.Direct).tool)
    }

    @Test fun waterTargetGrowsWithTheDaysSinceJoining() {
        val st = StageState(joinedOn = monday.minusDays(22).toString(), stage = 2, startedOn = monday.minusDays(22).toString())
        val p = Scene(stageN = 2, stageState = st).plan("09:00")
        assertEquals("跑單前喝完 · 1.5 壺", p.item("water").detail)
    }

    // ---- training ---------------------------------------------------------------------------------------------------

    @Test fun trainingWaitsForTheRideBackOnARideDay() {
        val sc = Scene()
        val p = sc.plan("20:00")
        for (id in listOf("task:CORE", "task:STRENGTH", "task:STRETCH")) {
            assertEquals(PlanDetail.AFTER_RIDE, p.item(id).detail)
            assertEquals(ItemStatus.PENDING, p.item(id).status)
            assertTrue(p.item(id).waiting)
            assertNull(p.item(id).at)
        }
        Kit.ride(sc.store, monday, "19:30", RidePhase.OUT)
        assertEquals(PlanDetail.AFTER_RIDE, sc.plan("22:00").item("task:CORE").detail)
        Kit.ride(sc.store, monday, "23:00", RidePhase.BACK, income = 1000)
        val back = sc.plan("23:10")
        assertEquals("5 分鐘", back.item("task:CORE").detail)
        assertFalse(back.item("task:CORE").waiting)
        assertTrue(back.item("task:STRENGTH").detail.startsWith("3 輪"))
    }

    @Test fun waitingTrainingIsNeverLateAfterTheDayEnds() {
        val sc = Scene()
        assertEquals(ItemStatus.PENDING, sc.plan("23:59").item("task:CORE").status)
        assertEquals(ItemStatus.MISSED, sc.plan("23:59", now = Kit.at(tuesday, "07:00")).item("task:CORE").status)
    }

    @Test fun trainingIsFreeAllDayOnARestDay() {
        val sc = Scene()
        Kit.status(sc.store, monday, "08:00", StatusType.REST_DAY)
        val p = sc.plan("09:00")
        for (id in listOf("task:CORE", "task:STRENGTH", "task:STRETCH")) {
            assertFalse(id, p.item(id).waiting)
            assertTrue(id, p.item(id).counts)
            assertEquals(ItemStatus.PENDING, p.item(id).status)
        }
    }

    @Test fun cardioIsSpreadAcrossTheWeek() {
        fun has(day: LocalDate, done: Int) = Scene(day = day, stageN = 3).let { it.plan("06:30", week = WeekSoFar(it.monday, cardioDone = done)).has("task:CARDIO") }
        assertTrue(has(monday, 0))
        assertFalse(has(tuesday, 0))
        assertTrue(has(wednesday, 0))
        assertFalse(has(monday.plusDays(3), 0))
        assertTrue(has(monday.plusDays(4), 0))
    }

    @Test fun cardioIsCaughtUpWhenTheWeekRunsShort() {
        fun has(day: LocalDate, done: Int) = Scene(day = day, stageN = 3).let { it.plan("06:30", week = WeekSoFar(it.monday, cardioDone = done)).has("task:CARDIO") }
        assertTrue(has(saturday, 1))
        assertFalse(has(saturday, 2))
        assertTrue(has(sunday, 2))
        assertFalse(has(sunday, 3))
        assertFalse(has(monday, 3))
    }

    @Test fun cardioDoneTodayStaysAsADoneItem() {
        val sc = Scene(day = tuesday, stageN = 3)
        Kit.task(sc.store, tuesday, "20:00", TaskId.CARDIO)
        assertEquals(ItemStatus.DONE, sc.plan("21:00", week = WeekSoFar(sc.monday, cardioDone = 1)).item("task:CARDIO").status)
    }

    @Test fun expressionRunsOnFreeDaysTheRightNumberOfTimes() {
        val sat = Scene(day = saturday, stageN = 4)
        assertTrue(sat.plan("09:00").has("task:EXPRESSION"))
        assertFalse(sat.plan("09:00", week = WeekSoFar(sat.monday, expressionDone = 2)).has("task:EXPRESSION"))
        assertFalse(Scene(day = monday, stageN = 4).plan("09:00").has("task:EXPRESSION"))
        val one = Kit.rules.copy(training = Kit.rules.training.copy(expressionPerWeek = 1))
        val s1 = Scene(day = sunday, stageN = 4, rules = one)
        assertTrue(s1.plan("09:00", week = WeekSoFar(s1.monday, expressionDone = 0)).has("task:EXPRESSION"))
        assertTrue(Scene(day = saturday, stageN = 4, rules = one).plan("09:00").has("task:EXPRESSION"))
        assertFalse(s1.plan("09:00", week = WeekSoFar(s1.monday, expressionDone = 1)).has("task:EXPRESSION"))
    }

    @Test fun freeDayPracticeComesBeforeTheFirstMeal() {
        val p = Scene(day = saturday, stageN = 4).plan("09:00")
        assertEquals(java.time.LocalTime.of(12, 30), p.item("task:DANCE").until)
        assertEquals(java.time.LocalTime.of(15, 0), p.item("task:WALK").at)
    }

    // ---- illness ---------------------------------------------------------------------------------------------------

    private fun backFromTheRide(sc: Scene) {
        Kit.ride(sc.store, sc.day, "19:30", RidePhase.OUT)
        Kit.ride(sc.store, sc.day, "23:00", RidePhase.BACK, 500)
    }

    @Test fun sickDropsCardioAndOneStrengthRound() {
        val sc = Scene(stageN = 3)
        Kit.status(sc.store, monday, "08:00", StatusType.SICK)
        backFromTheRide(sc)
        val p = sc.plan("23:30", week = WeekSoFar(sc.monday, cardioDone = 0))
        assertEquals(ItemStatus.OFF, p.item("task:CARDIO").status)
        assertTrue(p.item("task:STRENGTH").detail.startsWith("2 輪"))
        assertTrue(p.unwell)
        assertEquals(ItemStatus.PENDING, p.item("task:CORE").status)
    }

    @Test fun injuredTurnsEveryTrainingTaskOff() {
        val sc = Scene(stageN = 3)
        Kit.status(sc.store, monday, "08:00", StatusType.INJURED)
        val p = sc.plan("12:00")
        for (id in listOf("task:CORE", "task:STRENGTH", "task:CARDIO", "task:STRETCH")) {
            assertEquals(id, ItemStatus.OFF, p.item(id).status)
        }
        assertTrue(p.unwell)
        assertEquals(ItemStatus.PENDING, p.item("task:POSTURE").status)
    }

    @Test fun firstDayBackTakesOneRoundOffStrengthOnly() {
        val sc = Scene(stageN = 3)
        Kit.status(sc.store, monday.minusDays(3), "08:00", StatusType.SICK)
        Kit.status(sc.store, monday, "07:00", StatusType.RECOVERED)
        backFromTheRide(sc)
        val p = sc.plan("23:30")
        assertTrue(p.item("task:STRENGTH").detail.startsWith("2 輪"))
        assertEquals(ItemStatus.PENDING, p.item("task:CARDIO").status)
        assertFalse(p.unwell)
    }

    @Test fun aSingleRoundIsNeverReducedBelowOne() {
        val one = Kit.rules.copy(training = Kit.rules.training.copy(strengthRounds = 1))
        val sc = Scene(rules = one, stageN = 2)
        Kit.status(sc.store, monday, "08:00", StatusType.SICK)
        backFromTheRide(sc)
        assertTrue(sc.plan("23:30").item("task:STRENGTH").detail.startsWith("1 輪"))
    }

    // ---- rest day ---------------------------------------------------------------------------------------------------

    @Test fun restDayTurnsDeliveryOffAndStopsChasingTheEvening() {
        val sc = Scene(stageN = 3)
        Kit.status(sc.store, monday, "08:00", StatusType.REST_DAY)
        val p = sc.plan("19:45")
        assertTrue(p.restDay)
        assertEquals(ItemStatus.OFF, p.item("ride:out").status)
        assertEquals(ItemStatus.OFF, p.item("ride:back").status)
        assertEquals(ItemStatus.OFF, p.item("task:SUNSCREEN").status)
        assertEquals("1 壺", p.item("water").detail)
        assertFalse(p.item("meal:NIGHT").chase)
        assertFalse(p.item("check_out").chase)
        assertTrue(p.item("check_in").chase)
        assertTrue(p.item("meal:FIRST").chase)
        assertTrue(p.item("meal:DINNER").chase)
    }

    @Test fun restDayItemsDoNotCountButTrainingStillDoes() {
        val sc = Scene(stageN = 2)
        val normal = sc.plan("09:00").total
        Kit.status(sc.store, monday, "08:00", StatusType.REST_DAY)
        val rest = sc.plan("09:00")
        assertEquals(normal - 1, rest.total)
        assertTrue(rest.item("task:CORE").counts)
    }

    // ---- adjustments --------------------------------------------------------------------------------------------------

    private fun whats(p: DayPlan) = p.adjustments.map { it.what }

    @Test fun noAdjustmentsOnAnOrdinaryDay() {
        assertTrue(Scene().plan("09:00").adjustments.isEmpty())
    }

    @Test fun lateCheckInOnAFreeDayMovesTheFirstMealLater() {
        val sc = Scene(day = saturday, stageN = 2)
        Kit.checkIn(sc.store, saturday, "11:30")
        val p = sc.plan("11:40")
        assertEquals(java.time.LocalTime.of(14, 0), p.item("meal:FIRST").at)
        assertEquals(1, p.adjustments.size)
        assertTrue(p.adjustments[0].what.contains("90"))
        assertTrue(p.adjustments[0].what.contains("14:00"))
    }

    @Test fun theFirstMealIsMovedByAtMostTwoHours() {
        val sc = Scene(day = saturday, stageN = 2)
        Kit.checkIn(sc.store, saturday, "15:00")
        assertEquals(java.time.LocalTime.of(14, 30), sc.plan("15:10").item("meal:FIRST").at)
    }

    @Test fun lateCheckInOnAClassDayMovesNothing() {
        val sc = Scene(stageN = 2)
        Kit.checkIn(sc.store, monday, "09:00")
        assertEquals(java.time.LocalTime.of(12, 0), sc.plan("09:10").item("meal:FIRST").at)
        assertTrue(sc.plan("09:10").adjustments.isEmpty())
    }

    @Test fun anOnTimeCheckInOnAFreeDayChangesNothing() {
        val sc = Scene(day = saturday, stageN = 2)
        Kit.checkIn(sc.store, saturday, "09:50")
        assertTrue(sc.plan("10:00").adjustments.isEmpty())
    }

    @Test fun aBigFirstMealShrinksTheDinnerBudgetAndSaysSo() {
        val sc = Scene()
        Kit.meal(sc.store, monday, "12:00", MealSlot.FIRST, 900)
        val p = sc.plan("13:00")
        assertEquals("預算 700 大卡", p.item("meal:DINNER").detail)
        assertTrue(whats(p).any { it.contains("700") })
    }

    @Test fun aFirstMealWithinPlanChangesNothing() {
        val sc = Scene()
        Kit.meal(sc.store, monday, "12:00", MealSlot.FIRST, 800)
        assertTrue(sc.plan("13:00").adjustments.isEmpty())
    }

    @Test fun restDayIsAnAdjustment() {
        val sc = Scene()
        Kit.status(sc.store, monday, "08:00", StatusType.REST_DAY)
        assertTrue(whats(sc.plan("09:00")).any { it.contains("休息日") })
    }

    @Test fun illnessAdjustmentsSayWhatWasReduced() {
        val sick = Scene(stageN = 3)
        Kit.status(sick.store, monday, "08:00", StatusType.SICK)
        assertTrue(whats(sick.plan("09:00")).any { it.contains("體能") && it.contains("肌力") })
        val hurt = Scene(stageN = 3)
        Kit.status(hurt.store, monday, "08:00", StatusType.INJURED)
        assertTrue(whats(hurt.plan("09:00")).any { it.contains("訓練暫停") })
        val back = Scene(stageN = 3)
        Kit.status(back.store, monday.minusDays(2), "08:00", StatusType.SICK)
        Kit.status(back.store, monday, "07:00", StatusType.RECOVERED)
        assertTrue(whats(back.plan("09:00")).any { it.contains("肌力") })
    }

    @Test fun threeSickDaysSendHimToTheDoctor() {
        val sc = Scene(stageN = 2)
        Kit.status(sc.store, monday.minusDays(1), "08:00", StatusType.SICK)
        assertFalse(whats(sc.plan("09:00")).any { it.contains("醫生") })
        val sc3 = Scene(stageN = 2)
        Kit.status(sc3.store, monday.minusDays(2), "08:00", StatusType.SICK)
        assertTrue(whats(sc3.plan("09:00")).any { it.contains("醫生") })
    }

    @Test fun shortSleepOnARideDayLightensTheRide() {
        val sc = Scene(stageN = 2)
        sc.store.add(
            app.agency.core.day.Kit.monday.minusDays(1), Kit.at(monday.minusDays(1), "02:00"), app.agency.core.store.Kind.CHECK_OUT,
            app.agency.core.store.CheckOut(),
        )
        Kit.checkIn(sc.store, monday, "07:00")
        val p = sc.plan("08:00")
        assertTrue(whats(p).any { it.contains("少跑") && it.contains("訓練照舊") })
        assertTrue(p.adjustments.first().why.contains("5"))
    }

    @Test fun shortSleepOnARestDayLightensNothing() {
        val sc = Scene(stageN = 2)
        sc.store.add(
            monday.minusDays(1), Kit.at(monday.minusDays(1), "02:00"), app.agency.core.store.Kind.CHECK_OUT, app.agency.core.store.CheckOut(),
        )
        Kit.checkIn(sc.store, monday, "07:00")
        Kit.status(sc.store, monday, "07:05", StatusType.REST_DAY)
        assertFalse(whats(sc.plan("08:00")).any { it.contains("少跑") })
    }

    @Test fun aRaisedTrainingLevelIsAnnounced() {
        val sc = Scene()
        val raised = Kit.rules.training.copy(pushUps = 12, plankSeconds = 50)
        val p = sc.plan("09:00", training = raised)
        val line = whats(p).single { it.contains("訓練量提高") }
        assertTrue(line.contains("伏地挺身 12 下"))
        assertTrue(line.contains("棒式 50 秒"))
        assertFalse(line.contains("深蹲"))
        sc.store.let { Kit.ride(it, monday, "19:30", RidePhase.OUT); Kit.ride(it, monday, "23:00", RidePhase.BACK) }
        assertTrue(sc.plan("23:30", training = raised).item("task:STRENGTH").detail.contains("伏地挺身 12 下"))
    }

    @Test fun examWeekReducesLessons() {
        val sc = Scene(stageN = 1)
        Kit.status(sc.store, monday, "08:00", StatusType.EXAM_WEEK)
        assertTrue(whats(sc.plan("09:00")).any { it.contains("理論課") })
    }

    @Test fun theDayAStageOpensSaysWhatIsNew() {
        val st = StageState(
            joinedOn = monday.minusDays(7).toString(), stage = 2, startedOn = monday.toString(),
            rounds = listOf(StageRound(1, monday.minusDays(7).toString(), monday.minusDays(1).toString(), 95, 0, true)),
        )
        val p = Scene(stageN = 2, stageState = st).plan("06:30")
        assertTrue(whats(p).any { it.contains("新開放") && it.contains("簽到簽退") })
        assertTrue(whats(Scene(stageN = 2).plan("06:30")).none { it.contains("新開放") })
        assertTrue(whats(Scene(stageN = 1).plan("06:30")).none { it.contains("新開放") })
    }

    @Test fun earlierBedtimeShowsInTheCheckOutDeadline() {
        val st = StageState(
            joinedOn = monday.minusDays(40).toString(), stage = 4, startedOn = monday.minusDays(9).toString(),
            rounds = listOf(StageRound(3, monday.minusDays(16).toString(), monday.minusDays(10).toString(), 95, 0, true)),
        )
        val p = Scene(stageN = 4, stageState = st).plan("09:00")
        assertEquals(java.time.LocalTime.of(0, 45), p.item("check_out").until)
        assertEquals("0:45 前", p.item("check_out").detail)
    }

    // ---- next -------------------------------------------------------------------------------------------------------

    @Test fun nextPrefersTheOldestLateItem() {
        val sc = Scene()
        assertEquals("check_in", sc.plan("13:00").next?.id)
        Kit.checkIn(sc.store, monday, "07:00")
        assertEquals("meal:FIRST", sc.plan("13:00").next?.id)
    }

    @Test fun nextTakesWhatIsDueWithinHalfAnHourBeforeAnUntimedOne() {
        val sc = Scene(day = tuesday)
        Kit.checkIn(sc.store, tuesday, "07:00")
        assertEquals("water", sc.plan("09:00").next?.id)
        assertEquals("meal:FIRST", sc.plan("11:45").next?.id)
    }

    @Test fun nextTakesTheFirstUntimedActionableItem() {
        val sc = Scene(day = tuesday)
        Kit.checkIn(sc.store, tuesday, "07:00")
        val p = sc.plan("09:00")
        assertEquals("water", p.next?.id)
        Kit.water(sc.store, tuesday, "09:30", 1000)
        // Leaving for work only has a deadline: it is not the next thing all morning…
        assertEquals("meal:FIRST", sc.plan("09:40").next?.id)
    }

    @Test fun leavingForWorkBecomesNextOnlyInTheLastHourBeforeItsDeadline() {
        val sc = Scene(day = tuesday)
        Kit.checkIn(sc.store, tuesday, "07:00")
        Kit.water(sc.store, tuesday, "09:30", 1000)
        Kit.meal(sc.store, tuesday, "12:05", app.agency.core.store.MealSlot.FIRST, 700, photo = true)
        Kit.meal(sc.store, tuesday, "18:05", app.agency.core.store.MealSlot.DINNER, 700, photo = true)
        // leaveBy is 19:30 in the invented rules: inside the last hour it is the next thing.
        assertEquals("ride:out", sc.plan("18:40").next?.id)
    }

    @Test fun nextAfterTheRideOutIsTheRideBackThenTraining() {
        val sc = Scene(day = tuesday)
        Kit.checkIn(sc.store, tuesday, "07:00")
        Kit.water(sc.store, tuesday, "09:30", 1000)
        Kit.meal(sc.store, tuesday, "12:00", MealSlot.FIRST, 700)
        Kit.meal(sc.store, tuesday, "18:00", MealSlot.DINNER, 700)
        Kit.ride(sc.store, tuesday, "19:30", RidePhase.OUT)
        assertEquals("ride:back", sc.plan("20:00").next?.id)
        Kit.meal(sc.store, tuesday, "21:30", MealSlot.NIGHT, 400)
        Kit.ride(sc.store, tuesday, "23:00", RidePhase.BACK)
        assertEquals("task:CORE", sc.plan("23:10").next?.id)
    }

    @Test fun nextFallsBackToTheNextTimedItem() {
        val sc = Scene(day = tuesday)
        Kit.status(sc.store, tuesday, "06:00", StatusType.REST_DAY)
        Kit.checkIn(sc.store, tuesday, "07:00")
        Kit.water(sc.store, tuesday, "09:30", 1000)
        for (t in listOf(TaskId.CORE, TaskId.STRENGTH, TaskId.STRETCH)) Kit.task(sc.store, tuesday, "09:40", t)
        assertEquals("meal:FIRST", sc.plan("10:00").next?.id)
    }

    @Test fun nextNeverOffersAnOffItemOrOneWaitingForTheRide() {
        val sc = Scene(day = tuesday)
        Kit.checkIn(sc.store, tuesday, "07:00")
        Kit.water(sc.store, tuesday, "09:30", 1000)
        Kit.ride(sc.store, tuesday, "19:30", RidePhase.OUT)
        Kit.meal(sc.store, tuesday, "12:00", MealSlot.FIRST, 700)
        val next = sc.plan("20:00").next
        assertFalse(next!!.waiting)
        assertNotNull(next)
        assertFalse(next.id.startsWith("task:"))
    }

    @Test fun nextIsNullWhenEverythingIsDone() {
        val sc = Scene(day = tuesday)
        Kit.checkIn(sc.store, tuesday, "07:00")
        Kit.meal(sc.store, tuesday, "12:00", MealSlot.FIRST, 700)
        Kit.meal(sc.store, tuesday, "18:00", MealSlot.DINNER, 700)
        Kit.meal(sc.store, tuesday, "21:30", MealSlot.NIGHT, 400)
        Kit.water(sc.store, tuesday, "09:30", 1000)
        Kit.ride(sc.store, tuesday, "19:30", RidePhase.OUT)
        Kit.ride(sc.store, tuesday, "23:00", RidePhase.BACK, 1000)
        for (t in listOf(TaskId.CORE, TaskId.STRENGTH, TaskId.STRETCH)) Kit.task(sc.store, tuesday, "23:10", t)
        Kit.checkOut(sc.store, tuesday, "00:20")
        val p = sc.plan("23:50", now = Kit.at(tuesday, "00:25"))
        assertNull(p.next)
        assertEquals(p.total, p.done)
        assertTrue(p.items.filter { it.counts }.all { it.status == ItemStatus.DONE })
    }

    @Test fun nextIsNullWhenNothingIsOpen() {
        assertNull(Scene(rules = Rules(), stageN = 4).plan("09:00").next)
    }

    // ---- done / total ----------------------------------------------------------------------------------------------

    @Test fun doneAndTotalCountOnlyCountedItemsThatAreNotOff() {
        val sc = Scene()
        val before = sc.plan("06:30")
        assertEquals(11, before.total)
        assertEquals(0, before.done)
        assertFalse(before.item("ride:back").counts)
        Kit.checkIn(sc.store, monday, "07:00")
        Kit.ride(sc.store, monday, "19:30", RidePhase.OUT)
        val after = sc.plan("20:00")
        assertEquals(11, after.total)
        assertEquals(2, after.done)
    }

    @Test fun offItemsLeaveTheTotal() {
        val sc = Scene(stageN = 3)
        val base = sc.plan("06:30").total
        Kit.status(sc.store, monday, "06:10", StatusType.INJURED)
        val hurt = sc.plan("06:30")
        assertEquals(base - 4, hurt.total)
    }

    // ---- class day vs free day -----------------------------------------------------------------------------------------

    @Test fun aClassDayAndAFreeDayUseTheirOwnClock() {
        val cls = Scene(day = monday, stageN = 2).plan("06:00")
        val free = Scene(day = saturday, stageN = 2).plan("06:00")
        assertEquals(DayType.CLASS, cls.type)
        assertEquals(DayType.FREE, free.type)
        assertEquals(java.time.LocalTime.of(7, 0), cls.item("check_in").at)
        assertEquals(java.time.LocalTime.of(10, 0), free.item("check_in").at)
        assertEquals(java.time.LocalTime.of(12, 0), cls.item("meal:FIRST").at)
        assertEquals(java.time.LocalTime.of(12, 30), free.item("meal:FIRST").at)
        assertEquals(java.time.LocalTime.of(1, 0), cls.item("check_out").until)
        assertEquals(java.time.LocalTime.of(2, 0), free.item("check_out").until)
    }

    @Test fun itemsAreInTimeOrder() {
        val p = Scene(day = saturday, stageN = 3).plan("06:00")
        val timed = p.items.filter { it.at != null }.map { Kit.at(saturday, it.at!!.toString()) }
        assertEquals(timed.sorted(), timed)
        assertEquals("check_in", p.items.first().id)
    }

    // ---- tap and wording ----------------------------------------------------------------------------------------------

    @Test fun tapsMatchTheContract() {
        val p = Scene(stageN = 3).plan("06:30")
        assertEquals(Tap.Open(Screen.CHECK_IN), p.item("check_in").tap)
        assertEquals(Tap.Open(Screen.CHECK_OUT), p.item("check_out").tap)
        assertEquals(Tap.Open(Screen.CAMERA, "DINNER"), p.item("meal:DINNER").tap)
        assertEquals(Tap.Open(Screen.TIMER, "CORE"), p.item("task:CORE").tap)
        assertEquals(Tap.Open(Screen.BODY), p.item("body").tap)
        assertEquals("ride_out", (p.item("ride:out").tap as Tap.Direct).tool)
        assertEquals("ride_back", (p.item("ride:back").tap as Tap.Direct).tool)
        assertEquals("log_task", (p.item("task:SUNSCREEN").tap as Tap.Direct).tool)
    }

    @Test fun everyWordShownToHimHasNoLatinLetters() {
        val sc = Scene(day = saturday, stageN = 4)
        Kit.status(sc.store, saturday, "08:00", StatusType.SICK)
        Kit.checkIn(sc.store, saturday, "11:00")
        Kit.meal(sc.store, saturday, "13:00", MealSlot.FIRST, 900)
        val p = sc.plan("15:00", lessons = listOf(LessonSlot(1, "語言", 3L, "問候", false)), habits = listOf(HabitToday(1, "閱讀", false)))
        val words = p.items.flatMap { listOf(it.title, it.detail) } + p.adjustments.flatMap { listOf(it.what, it.why) } +
            sc.chips("15:00", p)
        for (w in words) assertTrue(w, w.none { it in 'a'..'z' || it in 'A'..'Z' })
    }
}
