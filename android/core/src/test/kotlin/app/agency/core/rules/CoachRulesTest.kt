package app.agency.core.rules

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoachRulesTest {
    @Test fun theSpecsExampleBlockComesOutWordForWord() {
        val block = CoachRules.overQuota(380, 1700, 91.0, 96.0, 184.0).joinToString("\n")
        assertEquals(
            "超過 380 大卡。\n" +
                "身材：每天都這樣，一個月多 1.6 公斤，赤字只剩 120，等於不會瘦。\n" +
                "身體：留在肥胖區的時間多 0.8 天，91 公斤延後 0.8 天。\n" +
                "明天回到 1700。不准少吃來補。",
            block,
        )
    }

    @Test fun formulasRoundHalfUpToOneDecimal() {
        assertEquals("0.4", CoachRules.monthKg(100))
        assertEquals("0.5", CoachRules.monthKg(117))
        assertEquals("1.7", CoachRules.monthKg(388))
        assertEquals("0.1", CoachRules.delayDays(25))
        assertEquals("0.5", CoachRules.delayDays(250))
        assertEquals(120, CoachRules.deficitLeft(380))
    }

    @Test fun theSecondLineTailFollowsTheDeficitLeft() {
        fun line2(x: Int) = CoachRules.overQuota(x, 1700, 91.0, 96.0, 184.0)[1]
        assertEquals("身材：每天都這樣，一個月多 2.1 公斤，赤字變成 0，等於在變胖。", line2(500))
        assertEquals("身材：每天都這樣，一個月多 1.1 公斤，赤字只剩 250。", line2(250))
        assertEquals("身材：每天都這樣，一個月多 2.6 公斤，赤字變成 -100，等於在變胖。", line2(600))
    }

    @Test fun belowTheObeseLineTheObeseClauseGoes() {
        assertEquals("身體：84 公斤延後 0.8 天。", CoachRules.overQuota(380, 1700, 84.0, 90.0, 184.0)[2])
        assertEquals("身體：留在肥胖區的時間多 0.8 天。", CoachRules.overQuota(380, 1700, null, 96.0, 184.0)[2])
    }

    @Test fun bmiLinesAndZonesAt184() {
        assertEquals("91.4", CoachRules.obeseLineKg(184.0).toPlainString())
        assertEquals("81.3", CoachRules.healthyLineKg(184.0).toPlainString())
        assertEquals(CoachRules.Zone.OBESE, CoachRules.zone(91.4, 184.0))
        assertEquals(CoachRules.Zone.HEALTHY, CoachRules.zone(81.3, 184.0))
        assertEquals(CoachRules.Zone.OVER, CoachRules.zone(86.0, 184.0))
        assertEquals("28.1", CoachRules.bmi(95.2, 184.0))
    }

    @Test fun theNextGoalIsTheFirstStepBelow() {
        val g = listOf(96.0, 91.0, 84.0)
        assertEquals(96.0, CoachRules.nextGoal(g, 96.4)!!, 0.0)
        assertEquals(91.0, CoachRules.nextGoal(g, 96.0)!!, 0.0)
        assertEquals(84.0, CoachRules.nextGoal(g, 91.0)!!, 0.0)
        assertNull(CoachRules.nextGoal(g, 83.9))
    }

    @Test fun floorAndDayAndQuota() {
        assertEquals(571, CoachRules.mustStillEat(629))
        assertEquals(0, CoachRules.mustStillEat(1300))
        val zone = ZoneId.of("Asia/Taipei")
        // Saturday 03:00 in Taipei belongs to Friday.
        val sat3 = LocalDate.of(2026, 11, 21).atTime(3, 0).atZone(zone).toInstant()
        assertEquals(DayOfWeek.FRIDAY, CoachRules.coachDay(sat3, zone, 5).dayOfWeek)
        assertEquals(1200, CoachRules.quotaClamped(900))
    }
}
