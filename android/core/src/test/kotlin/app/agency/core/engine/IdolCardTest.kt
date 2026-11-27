package app.agency.core.engine

import app.agency.core.brain.TodayRecords
import app.agency.core.model.CompanyRules
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.Profile
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.model.TrainingKind
import app.agency.core.model.TrainingLog
import app.agency.core.model.WaterLog
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The 練習生證's numbers (arch T10), from invented records. */
class IdolCardTest {
    private val day = LocalDate.parse("2026-12-07")
    private val d = day.toString()

    private fun meal(id: String, kcal: Int, protein: Double, breaches: List<String> = emptyList()) = Meal(
        id, d, 1L, text = "一餐", breaches = breaches,
        items = listOf(MealItem("雞胸", 1.0, kcal, protein, null, null, false, KcalSource.TABLE)),
    )

    @Test fun theJoinDayIsDayOne() {
        assertEquals(1L, IdolCard.joinDay(Profile(joinDate = d), day))
        assertEquals(31L, IdolCard.joinDay(Profile(joinDate = "2026-11-07"), day))
        assertNull(IdolCard.joinDay(Profile(joinDate = "2026-12-08"), day))
        assertNull(IdolCard.joinDay(Profile(joinDate = "不是日期"), day))
        assertNull(IdolCard.joinDay(Profile(), day))
    }

    @Test fun theNumberIsThreeDigits() {
        assertEquals("001", IdolCard.traineeNo(Profile()))
        assertEquals("007", IdolCard.traineeNo(Profile(traineeNo = "7")))
        assertEquals("001", IdolCard.traineeNo(Profile(traineeNo = "")))
    }

    @Test fun onlyTheNextGoalAndNeverOneUnder52() {
        val rules = CompanyRules(goalsKg = listOf(91.0, 84.0, 50.0))
        assertEquals(91.0, IdolCard.nextGoal(rules, 98.0)!!, 0.0)
        assertEquals(84.0, IdolCard.nextGoal(rules, 90.5)!!, 0.0)
        assertNull(IdolCard.nextGoal(rules, 83.0)) // 50 is his wish, never a goal on the card
        assertEquals(91.0, IdolCard.nextGoal(rules, null)!!, 0.0)
    }

    @Test fun whatIsLeftAndTheBreachesAreCountedByCode() {
        val today = TodayRecords(d, meals = listOf(meal("m1", 714, 48.0), meal("m2", 310, 30.5, listOf("PROCESSED"))))
        val f = IdolCard.build(Profile(stageName = "小雨", memberColor = "#8E2440"), CompanyRules(), today, day, 96.0, 2, "photo-1")
        assertEquals(1700 - 1024, f.kcalLeft)
        assertEquals(140 - 79, f.proteinLeftG) // 61.5 left, rounded down
        assertEquals(1, f.breachesToday)
        assertEquals(2, f.baselineMissing)
        assertEquals("小雨娛樂", f.company)
        assertEquals("小雨", f.stageName)
        assertEquals("photo-1", f.photoMediaId)
        val over = IdolCard.build(Profile(), CompanyRules(), TodayRecords(d, meals = listOf(meal("m", 1900, 10.0))), day, null, 0, null)
        assertEquals(-200, over.kcalLeft)
        assertNull(over.stageName)
    }

    @Test fun oneStickerPerDoneItem() {
        val today = TodayRecords(
            d,
            meals = listOf(meal("m1", 300, 20.0), meal("m2", 300, 20.0)),
            water = listOf(WaterLog("w1", d, 2L, 2), WaterLog("w2", d, 3L, 1)),
            training = listOf(
                TrainingLog("t1", d, 4L, TrainingKind.CORE7), TrainingLog("t2", d, 5L, TrainingKind.CORE7),
                TrainingLog("t3", d, 6L, TrainingKind.STRENGTH, rounds = 3),
            ),
            signs = listOf(SignLog("s1", d, 1L, SignKind.IN), SignLog("s2", d, 1L, SignKind.IN), SignLog("s3", d, 7L, SignKind.RIDE_START)),
        )
        val kinds = Stickers.fromRecords(today).map { it.kind }
        assertEquals(
            listOf(StickerKind.SIGN_IN, StickerKind.MEAL, StickerKind.MEAL, StickerKind.WATER, StickerKind.WATER, StickerKind.CORE, StickerKind.STRENGTH, StickerKind.RIDE),
            kinds,
        )
        assertEquals(emptyList<Sticker>(), Stickers.fromRecords(TodayRecords(d)))
    }
}
