package app.agency.core.plan

import app.agency.core.day.Kit
import app.agency.core.day.Kit.monday
import app.agency.core.day.Kit.saturday
import app.agency.core.store.MealSlot
import app.agency.core.store.RidePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipsTest {
    @Test fun nothingBeforeWakeTime() {
        assertFalse(Scene().chips("06:00").contains("早安"))
    }

    @Test fun goodMorningAfterWakeTimeUntilCheckedIn() {
        val sc = Scene()
        assertTrue(sc.chips("07:05").contains("早安"))
        Kit.checkIn(sc.store, monday, "07:06")
        assertFalse(sc.chips("07:10").contains("早安"))
    }

    @Test fun mealChipNearAMealTime() {
        val sc = Scene()
        Kit.checkIn(sc.store, monday, "07:00")
        assertFalse(sc.chips("10:00").contains("我吃完了"))
        assertTrue(sc.chips("11:45").contains("我吃完了"))
        Kit.meal(sc.store, monday, "12:10", MealSlot.FIRST, 600)
        assertFalse(sc.chips("12:30").contains("我吃完了"))
    }

    @Test fun mealWithoutPhotoStillOffersTheChipBecauseItIsNotDone() {
        val sc = Scene()
        Kit.meal(sc.store, monday, "12:10", MealSlot.FIRST, 600, photo = false)
        assertTrue(sc.chips("12:30").contains("我吃完了"))
    }

    @Test fun leavingChipBeforeTheDeparture() {
        val sc = Scene()
        assertFalse(sc.chips("15:00").contains("我出門跑單了"))
        assertTrue(sc.chips("19:00").contains("我出門跑單了"))
        Kit.ride(sc.store, monday, "19:20", RidePhase.OUT)
        assertFalse(sc.chips("19:25").contains("我出門跑單了"))
    }

    @Test fun ridingOffersTheFinishChip() {
        val sc = Scene()
        Kit.ride(sc.store, monday, "19:20", RidePhase.OUT)
        assertTrue(sc.chips("21:00").contains("收工了"))
        Kit.ride(sc.store, monday, "23:00", RidePhase.BACK)
        assertFalse(sc.chips("23:05").contains("收工了"))
    }

    @Test fun waterChipWhileNotDone() {
        val sc = Scene()
        assertTrue(sc.chips("09:00").contains("喝完一壺了"))
        Kit.water(sc.store, monday, "09:10", 1000)
        assertFalse(sc.chips("09:20").contains("喝完一壺了"))
    }

    @Test fun trainingChipOnlyOnceTrainingCanStart() {
        val sc = Scene()
        assertFalse(sc.chips("20:00").contains("開始訓練"))
        Kit.ride(sc.store, monday, "19:20", RidePhase.OUT)
        Kit.ride(sc.store, monday, "23:00", RidePhase.BACK)
        assertTrue(sc.chips("23:05").contains("開始訓練"))
    }

    @Test fun bedtimeChipNearTheEnd() {
        val sc = Scene()
        assertFalse(sc.chips("23:00").contains("我要睡了"))
        assertEquals("我要睡了", sc.chips("00:40").first())
        Kit.checkOut(sc.store, monday, "00:45")
        assertFalse(sc.chips("00:50").contains("我要睡了"))
    }

    @Test fun neverMoreThanFourAndMostUrgentFirst() {
        val sc = Scene()
        Kit.ride(sc.store, monday, "19:20", RidePhase.OUT)
        val chips = sc.chips("00:40")
        assertTrue(chips.size <= Chips.MAX)
        assertEquals("我要睡了", chips.first())
        assertTrue(chips.contains("收工了"))
    }

    @Test fun stageOneOffersNothingButTheLessons() {
        val sc = Scene(stageN = 1)
        val lessons = listOf(LessonSlot(1, "語言", 1L, "問候", false))
        assertEquals(listOf("今天上什麼課"), sc.chips("09:00", sc.plan("09:00", lessons = lessons)))
        assertTrue(sc.chips("09:00", sc.plan("09:00")).isEmpty())
    }

    @Test fun closedFeaturesNeverProduceChips() {
        val sc = Scene(stageN = 1)
        val chips = sc.chips("19:00") + sc.chips("11:45") + sc.chips("07:30")
        assertTrue(chips.isEmpty())
    }

    @Test fun aFreeDayFollowsItsOwnClock() {
        val sc = Scene(day = saturday)
        assertFalse(sc.chips("07:30").contains("早安"))
        assertTrue(sc.chips("10:05").contains("早安"))
    }

    @Test fun everyChipHasNoLatinLetters() {
        val sc = Scene()
        Kit.ride(sc.store, monday, "19:20", RidePhase.OUT)
        for (t in listOf("07:05", "11:45", "19:00", "21:00", "23:30", "00:40")) {
            for (c in sc.chips(t)) assertTrue(c, c.none { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }
}
