package app.agency.core.day

import app.agency.core.day.Kit.monday
import app.agency.core.store.DrinkType
import app.agency.core.store.Kind
import app.agency.core.store.MealSlot
import app.agency.core.store.MemStore
import app.agency.core.store.RidePhase
import app.agency.core.store.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViolationsTest {
    private val rules = Kit.rules
    private val stage = Kit.stage(2)
    private val now = Kit.at(monday, "22:00")

    private fun codes(s: MemStore, day: java.time.LocalDate = monday) =
        s.entries(day, Kind.VIOLATION).map { it.read<Violation>().code }

    private fun write(s: MemStore, st: app.agency.core.stage.StageState = stage, day: java.time.LocalDate = monday) =
        Violations.onWrite(s, rules, st, day, now, Kit.zone)

    private fun close(s: MemStore, st: app.agency.core.stage.StageState = stage, day: java.time.LocalDate = monday) =
        Violations.onClose(s, rules, st, day, now, Kit.zone)

    @Test fun bannedFoodIsWrittenOncePerMealEntry() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 500, name = "炸雞排", banned = "炸物")
        write(s)
        write(s)
        assertEquals(listOf("BANNED_FOOD"), codes(s))
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 500, name = "酥炸魚", banned = "炸物")
        write(s)
        assertEquals(listOf("BANNED_FOOD", "BANNED_FOOD"), codes(s))
    }

    @Test fun bannedIsAlsoFoundByTheRulesWhenTheItemWasNotMarked() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 500, name = "珍珠奶茶")
        assertEquals(1, write(s).size)
    }

    @Test fun allowedAndTableFoodsAreNotViolations() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 300, name = "氣炸地瓜")
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 300, name = "測試雞腿便當")
        assertTrue(write(s).isEmpty())
    }

    @Test fun mealOverCap() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 950)
        write(s)
        write(s)
        assertEquals(listOf("MEAL_OVER_CAP"), codes(s))
    }

    @Test fun mealExactlyAtTheCapIsFine() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 900)
        assertTrue(write(s).isEmpty())
    }

    @Test fun overKcalForTheDay() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 800)
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 800)
        Kit.meal(s, monday, "21:00", MealSlot.NIGHT, 500)
        write(s)
        write(s)
        assertEquals(listOf("OVER_KCAL"), codes(s))
    }

    @Test fun underMinOnlyAtClose() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 600)
        write(s)
        assertTrue(codes(s).isEmpty())
        close(s)
        close(s)
        assertEquals(listOf("UNDER_MIN"), codes(s))
    }

    @Test fun underMinNeedsAtLeastOneMeal() {
        val s = MemStore()
        close(s)
        assertTrue(codes(s).isEmpty())
    }

    @Test fun missedNightMealAfterRiding() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 800)
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 800)
        Kit.ride(s, monday, "19:30", RidePhase.OUT)
        close(s)
        close(s)
        assertEquals(listOf("MISSED_NIGHT_MEAL"), codes(s))
    }

    @Test fun nightMealPresentOrNoRideMeansNoMissedNightMeal() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 800)
        Kit.meal(s, monday, "18:00", MealSlot.DINNER, 800)
        Kit.meal(s, monday, "21:30", MealSlot.NIGHT, 400)
        Kit.ride(s, monday, "19:30", RidePhase.OUT)
        close(s)
        assertTrue(codes(s).isEmpty())
        val s2 = MemStore()
        Kit.meal(s2, monday, "12:00", MealSlot.FIRST, 800)
        Kit.meal(s2, monday, "18:00", MealSlot.DINNER, 800)
        close(s2)
        assertTrue(codes(s2).none { it == "MISSED_NIGHT_MEAL" })
    }

    @Test fun lateCheckOutAfterBedtime() {
        val s = MemStore()
        Kit.checkIn(s, monday, "07:00")
        Kit.checkOut(s, monday, "01:30")
        write(s)
        write(s)
        assertEquals(listOf("LATE_CHECK_OUT"), codes(s))
    }

    @Test fun checkOutOnTimeIsFine() {
        val s = MemStore()
        Kit.checkIn(s, monday, "07:00")
        Kit.checkOut(s, monday, "00:50")
        close(s)
        assertTrue(codes(s).none { it == "LATE_CHECK_OUT" })
    }

    @Test fun noCheckOutByCloseIsLateOnlyWhenHeCheckedIn() {
        val s = MemStore()
        close(s)
        assertTrue(codes(s).isEmpty())
        Kit.checkIn(s, monday, "07:00")
        close(s)
        close(s)
        assertEquals(listOf("LATE_CHECK_OUT"), codes(s))
    }

    @Test fun lateCheckOutAndCloseNeverDoubleCount() {
        val s = MemStore()
        Kit.checkIn(s, monday, "07:00")
        Kit.checkOut(s, monday, "02:00")
        write(s)
        close(s)
        assertEquals(listOf("LATE_CHECK_OUT"), codes(s))
    }

    @Test fun earlierBedtimeMovesTheDeadline() {
        val joined = monday.minusDays(40)
        val rounds = listOf(
            app.agency.core.stage.StageRound(3, monday.minusDays(16).toString(), monday.minusDays(10).toString(), 95, 0, true),
        )
        val st = app.agency.core.stage.StageState(joinedOn = joined.toString(), stage = 4, startedOn = monday.minusDays(9).toString(), rounds = rounds)
        // Opened 9 days ago: one full week, so 15 minutes earlier: 00:45 instead of 01:00.
        assertEquals(java.time.LocalTime.of(0, 45), Bedtime.of(rules, st, monday))
        val s = MemStore()
        Kit.checkIn(s, monday, "07:00")
        Kit.checkOut(s, monday, "00:50")
        write(s, st)
        assertEquals(listOf("LATE_CHECK_OUT"), codes(s))
    }

    @Test fun bedtimeNeverPassesTheGoal() {
        val st = app.agency.core.stage.StageState(
            joinedOn = monday.minusDays(200).toString(), stage = 4, startedOn = monday.minusDays(190).toString(),
            rounds = listOf(app.agency.core.stage.StageRound(3, monday.minusDays(197).toString(), monday.minusDays(191).toString(), 95, 0, true)),
        )
        assertEquals(java.time.LocalTime.of(0, 0), Bedtime.of(rules, st, monday))
    }

    @Test fun caffeineAfterTheCutoff() {
        val s = MemStore()
        Kit.drink(s, monday, "19:59", DrinkType.CAFFEINE)
        write(s)
        assertTrue(codes(s).isEmpty())
        Kit.drink(s, monday, "20:30", DrinkType.CAFFEINE)
        write(s)
        write(s)
        assertEquals(listOf("CAFFEINE_LATE"), codes(s))
    }

    @Test fun zeroCalOverTheWeeklyAllowance() {
        val s = MemStore()
        Kit.drink(s, monday, "10:00", DrinkType.ZERO_CAL)
        Kit.drink(s, monday.plusDays(1), "10:00", DrinkType.ZERO_CAL)
        Violations.onWrite(s, rules, stage, monday.plusDays(1), now, Kit.zone)
        assertTrue(codes(s, monday.plusDays(1)).isEmpty())
        Kit.drink(s, monday.plusDays(2), "10:00", DrinkType.ZERO_CAL)
        Violations.onWrite(s, rules, stage, monday.plusDays(2), now, Kit.zone)
        Violations.onWrite(s, rules, stage, monday.plusDays(2), now, Kit.zone)
        assertEquals(listOf("ZERO_CAL_OVER"), codes(s, monday.plusDays(2)))
    }

    @Test fun zeroCalWeekStartsOnMonday() {
        val s = MemStore()
        val sunday = monday.minusDays(1)
        Kit.drink(s, sunday, "10:00", DrinkType.ZERO_CAL)
        Kit.drink(s, sunday, "11:00", DrinkType.ZERO_CAL)
        Kit.drink(s, monday, "10:00", DrinkType.ZERO_CAL)
        write(s)
        assertTrue(codes(s).isEmpty())
    }

    @Test fun restDayOverIsNeverWritten() {
        val s = MemStore()
        Kit.status(s, monday, "09:00", app.agency.core.store.StatusType.REST_DAY)
        Kit.status(s, monday.plusDays(1), "09:00", app.agency.core.store.StatusType.REST_DAY)
        close(s)
        assertTrue(codes(s).none { it == "REST_DAY_OVER" })
    }

    @Test fun featuresThatAreNotOpenWriteNothing() {
        val s = MemStore()
        val stage1 = Kit.stage(1)
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 1500, name = "炸雞排", banned = "炸物")
        Kit.drink(s, monday, "22:00", DrinkType.CAFFEINE)
        Kit.checkIn(s, monday, "07:00")
        Kit.checkOut(s, monday, "03:00")
        assertTrue(write(s, stage1).isEmpty())
        assertTrue(close(s, stage1).isEmpty())
    }

    @Test fun writeReturnsOnlyWhatIsNew() {
        val s = MemStore()
        Kit.meal(s, monday, "12:00", MealSlot.FIRST, 950)
        assertEquals(1, write(s).size)
        assertEquals(0, write(s).size)
    }

    @Test fun violationCodesHaveWordsForHim() {
        for (c in ViolationCode.values()) {
            assertTrue(c.name, c.label.isNotBlank())
            assertTrue(c.name, c.label.none { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }
}
