package app.agency.core.stage

import app.agency.core.base.TestClock
import app.agency.core.store.MemStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagesTest {
    private val monday = TestClock.MONDAY

    @Test fun labelCountsTheDayInsideTheRound() {
        val s = StageState(joinedOn = monday.minusDays(10).toString(), stage = 2, startedOn = monday.toString())
        assertEquals("第 2 階段 · 第 1 天", Stages.label(s, monday))
        assertEquals("第 2 階段 · 第 3 天", Stages.label(s, monday.plusDays(2)))
        assertEquals("第 2 階段 · 第 7 天", Stages.label(s, monday.plusDays(6)))
    }

    @Test fun labelNeverShowsDayEightBeforeTheRoundIsJudged() {
        val s = StageState(joinedOn = monday.toString())
        assertEquals("第 1 階段 · 第 7 天", Stages.label(s, monday.plusDays(7)))
    }

    @Test fun openAndOpensNext() {
        val s = StageState(joinedOn = monday.toString(), stage = 2)
        val open = Stages.open(s)
        assertTrue(Feature.LESSONS in open && Feature.MEALS in open && Feature.MEMO in open)
        assertTrue(Feature.CARDIO !in open)
        assertEquals(Feature.values().filter { it.stage == 3 }, Stages.opensNext(s))
        assertEquals(emptyList<Feature>(), Stages.opensNext(StageState(joinedOn = monday.toString(), stage = 4)))
    }

    @Test fun joinCreatesTheStageDocumentOnceOnDayOne() {
        val store = MemStore()
        assertEquals(null, Stages.get(store))
        val s = Stages.join(store, monday)
        assertEquals(1, s.stage)
        assertEquals(monday.toString(), s.joinedOn)
        assertEquals(monday.toString(), s.startedOn)
        val again = Stages.join(store, monday.plusDays(5))
        assertEquals(monday.toString(), again.joinedOn)
    }
}
