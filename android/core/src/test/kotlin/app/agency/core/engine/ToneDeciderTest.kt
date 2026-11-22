package app.agency.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToneDeciderTest {
    @Test fun gentleIsUnconditionalForFeelingsIllnessAndRedFlags() {
        // Even on a day with a breach and an open gap, or right after a major completion.
        val badDay = ToneInputs(breachToday = true, openGapDue = true)
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(ownerText = "我今天頭暈")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(ownerText = "心情很差")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(intakeKind = "unwell")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(intakeKind = "mood")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(redFlag = true)))
        assertEquals(Tone.GENTLE, ToneDecider.decide(ToneInputs(ownerText = "我發燒了", completion = Completion.DAY_DONE)))
    }

    @Test fun sweetOnlyAfterAMajorCompletionAndOnceADay() {
        val dayDone = ToneInputs(ownerText = "肌力做完了", completion = ToneDecider.dayDone(allMealsReported = true, strengthDone = true))
        assertEquals(Tone.SWEET, ToneDecider.decide(dayDone))
        assertEquals(Tone.SWEET, ToneDecider.decide(ToneInputs(completion = Completion.WEEK_MILESTONE)))
        assertEquals(Tone.SWEET, ToneDecider.decide(ToneInputs(completion = Completion.MONTH_MILESTONE)))
        // A second one on the same day is COLD.
        assertEquals(Tone.COLD, ToneDecider.decide(dayDone.copy(sweetToday = 1)))
        assertEquals(Tone.COLD, ToneDecider.decide(dayDone.copy(breachToday = true)))
        assertEquals(Tone.COLD, ToneDecider.decide(dayDone.copy(openGapDue = true)))
    }

    @Test fun singleItemsDoNotEarnSweet() {
        // Water alone, one meal, or the rounds with a meal still unreported: no major completion.
        assertNull(ToneDecider.dayDone(allMealsReported = false, strengthDone = true))
        assertNull(ToneDecider.dayDone(allMealsReported = true, strengthDone = false))
        assertEquals(Tone.COLD, ToneDecider.decide(ToneInputs(ownerText = "喝完一壺", completion = null)))
        assertEquals(Tone.COLD, ToneDecider.decide(ToneInputs(ownerText = "肌力做完了", completion = ToneDecider.dayDone(false, true))))
    }

    @Test fun otherwiseCold() {
        assertEquals(Tone.COLD, ToneDecider.decide(ToneInputs(ownerText = "姐姐你好兇欸")))
        assertEquals(Tone.COLD, ToneDecider.decide(ToneInputs()))
    }
}
