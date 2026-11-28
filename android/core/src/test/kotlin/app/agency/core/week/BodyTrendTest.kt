package app.agency.core.week

import app.agency.core.agent.Card
import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.rules.Fixtures
import app.agency.core.store.Body
import app.agency.core.store.Kind
import app.agency.core.store.MemStore
import app.agency.core.store.ProposalRec
import app.agency.core.store.add
import app.agency.core.store.update
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyTrendTest {
    private val rules = Fixtures.rules() // fast loss above 0.8 kg a week, pause below 71, never below 61
    private val thisMonday: LocalDate = TestClock.MONDAY.plusDays(14)
    private val day = thisMonday.plusDays(2)

    private fun weigh(s: MemStore, weeksAgo: Int, kg: Double) {
        val d = thisMonday.minusWeeks(weeksAgo.toLong()).plusDays(1)
        s.add(d, Days.at(d, LocalTime.of(8, 0), TestClock.ZONE), Kind.BODY, Body(kg = kg))
    }

    private fun proposals(s: MemStore) =
        s.entries(day.minusDays(200), day.plusDays(30), Kind.PROPOSAL).map { it.id to it.read<ProposalRec>() }

    @Test fun twoFastWeeksInARowProposeMoreKcalExactlyOnce() {
        val s = MemStore()
        weigh(s, 2, 80.0); weigh(s, 1, 78.5); weigh(s, 0, 77.0)
        val first = BodyTrend.check(s, day, rules)
        assertTrue(first.fastLoss)
        assertTrue(first.proposed)
        val p = proposals(s).single().second
        assertEquals("kcal_up", p.kind)
        assertEquals(100, p.value)
        assertEquals(Card.Answer.OPEN, p.answer)
        assertTrue(p.title.isNotBlank() && p.body.isNotBlank())

        val second = BodyTrend.check(s, day.plusDays(1), rules)
        assertTrue(second.fastLoss)
        assertFalse(second.proposed)
        assertEquals(1, proposals(s).size)
    }

    @Test fun aRefusedProposalIsNotAskedAgainTomorrow() {
        val s = MemStore()
        weigh(s, 2, 80.0); weigh(s, 1, 78.5); weigh(s, 0, 77.0)
        BodyTrend.check(s, day, rules)
        val (id, rec) = proposals(s).single()
        s.update(id, rec.copy(answer = Card.Answer.NO))
        assertFalse(BodyTrend.check(s, day.plusDays(1), rules).proposed)
        assertEquals(1, proposals(s).size)
    }

    @Test fun oneFastWeekOrASlowLossProposesNothing() {
        val one = MemStore()
        weigh(one, 2, 80.0); weigh(one, 1, 79.5); weigh(one, 0, 78.0)
        assertFalse(BodyTrend.check(one, day, rules).fastLoss)
        val exact = MemStore()
        weigh(exact, 2, 80.0); weigh(exact, 1, 79.2); weigh(exact, 0, 78.4)
        assertFalse(BodyTrend.check(exact, day, rules).fastLoss)
        assertTrue(proposals(exact).isEmpty())
    }

    @Test fun beforeThisWeeksWeighInThePreviousTwoWeeksAreJudged() {
        val s = MemStore()
        weigh(s, 3, 80.0); weigh(s, 2, 78.5); weigh(s, 1, 77.0)
        assertTrue(BodyTrend.check(s, day, rules).fastLoss)
    }

    @Test fun aGapWeekBreaksTheRun() {
        val s = MemStore()
        weigh(s, 3, 80.0); weigh(s, 1, 77.0); weigh(s, 0, 75.6)
        assertFalse(BodyTrend.check(s, day, rules).fastLoss)
    }

    @Test fun pauseAndFloorFlagsFollowTheLatestWeight() {
        val s = MemStore()
        weigh(s, 0, 70.5)
        val f = BodyTrend.check(s, day, rules)
        assertEquals(70.5, f.latestKg!!, 0.0001)
        assertTrue(f.pause)
        assertFalse(f.floor)

        val low = MemStore()
        weigh(low, 0, 60.6)
        val g = BodyTrend.check(low, day, rules)
        assertTrue(g.pause && g.floor)

        val fine = MemStore()
        weigh(fine, 0, 76.4)
        val h = BodyTrend.check(fine, day, rules)
        assertFalse(h.pause || h.floor)
        assertNull(BodyTrend.check(MemStore(), day, rules).latestKg)
    }
}
