package app.agency.core.stage

import app.agency.core.day.StageDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixWaveStageTest {
    private fun stageFour(r: StageRig, passedWeeks: Int, streak: Int) = r.setStage(
        StageState(
            joinedOn = r.monday.toString(), stage = 4, startedOn = r.monday.toString(), passedWeeks = passedWeeks, passStreak = streak,
        ),
    )

    /** B8: the level follows passed weeks in a row, not the total. */
    @Test fun theLevelNeedsConsecutivePassedWeeks() {
        val r = StageRig()
        stageFour(r, passedWeeks = 3, streak = 0)
        r.perfectDays(1, 7)
        r.realTask(r.day(3))
        r.run(r.day(8))
        val s = r.stage()
        assertEquals(4, s.passedWeeks)
        assertEquals(1, s.passStreak)
        assertEquals("C", s.level)

        val r2 = StageRig()
        stageFour(r2, passedWeeks = 3, streak = 3)
        r2.perfectDays(1, 7)
        r2.realTask(r2.day(3))
        r2.run(r2.day(8))
        assertEquals("B", r2.stage().level)
        assertTrue(r2.ceremonies("level").single().text.contains("連續通過 4 週"))
    }

    /** B11: a past day keeps the stage it had, even when a later stage is open now. */
    @Test fun aPastDayIsSeenWithTheStageOpenOnIt() {
        val r = StageRig()
        val s = StageState(
            joinedOn = r.monday.toString(), stage = 2, startedOn = r.day(8).toString(),
            rounds = listOf(StageRound(1, r.day(1).toString(), r.day(7).toString(), 100, 0, true)),
        )
        r.setStage(s)
        assertEquals(1, StageDays.asOf(s, r.day(3)).stage)
        assertEquals(s, StageDays.asOf(s, r.day(9)))
        assertEquals(1, Today.stage(r.store, r.day(5)).stage)
        assertEquals(2, Today.stage(r.store, r.day(8)).stage)
    }

    /** B11: catching up several missed days never plans an old day with a stage opened later in the same run. */
    @Test fun aCatchUpClosesEachDayWithItsOwnStage() {
        val r = StageRig()
        // The document already says stage 2 from day 8, but days 5–7 were never closed.
        r.setStage(
            StageState(
                joinedOn = r.monday.toString(), stage = 2, startedOn = r.day(8).toString(), closedThrough = r.day(4).toString(),
                rounds = listOf(StageRound(1, r.day(1).toString(), r.day(7).toString(), 100, 0, true)),
            ),
        )
        r.perfectDays(5, 7)
        r.run(r.day(9))
        val closes = r.closes()
        // Stage-1 days: only the lesson counts, and it was done.
        assertTrue(closes.toString(), closes.take(3).all { it.perfect })
    }

    /** B14: the weekly card also names the real-person task, the level and the photocards. */
    @Test fun theWeeklyCardListsTheRealTaskLevelAndPhotocards() {
        val r = StageRig()
        stageFour(r, passedWeeks = 0, streak = 0)
        r.perfectDays(1, 7)
        r.realTask(r.day(2))
        r.run(r.day(8))
        val lines = r.summaries().single { it.title == "上週成績" }.lines
        assertTrue(lines.toString(), lines.any { it.contains("真人任務：完成了") })
        assertTrue(lines.any { it.contains("練習生等級：C 級") })
        assertTrue(lines.any { it.contains("張小卡") })
    }
}
