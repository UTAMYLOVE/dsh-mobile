package app.agency.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ToneDeciderTest {
    @Test fun gentleIsUnconditionalForFeelingsIllnessAndRedFlags() {
        // Even on a day with a breach and an open gap.
        val badDay = ToneInputs(breachToday = true, openGapDue = true)
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(ownerText = "我今天頭暈")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(ownerText = "心情很差")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(intakeKind = "unwell")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(intakeKind = "mood")))
        assertEquals(Tone.GENTLE, ToneDecider.decide(badDay.copy(redFlag = true)))
        assertEquals(Tone.GENTLE, ToneDecider.decide(ToneInputs(ownerText = "我發燒了", doneSinceLastSweet = 3)))
    }

    @Test fun sweetOnlyAsAReward() {
        val good = ToneInputs(ownerText = "肌力做完了", doneSinceLastSweet = 1)
        assertEquals(Tone.SWEET, ToneDecider.decide(good))
        assertEquals(Tone.COLD, ToneDecider.decide(good.copy(breachToday = true)))
        assertEquals(Tone.COLD, ToneDecider.decide(good.copy(openGapDue = true)))
        assertEquals(Tone.COLD, ToneDecider.decide(good.copy(doneSinceLastSweet = 0)))
    }

    @Test fun otherwiseCold() {
        assertEquals(Tone.COLD, ToneDecider.decide(ToneInputs(ownerText = "姐姐你好兇欸")))
        assertEquals(Tone.COLD, ToneDecider.decide(ToneInputs()))
    }
}
