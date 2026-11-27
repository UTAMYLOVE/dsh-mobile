package app.agency.core.engine

import app.agency.core.brain.TodayRecords
import app.agency.core.model.KcalSource
import app.agency.core.model.Meal
import app.agency.core.model.MealItem
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.model.TrainingKind
import app.agency.core.model.TrainingLog
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.DayPlanner
import app.agency.core.schedule.SlotKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slot status from records (arch 1.5), on an invented Monday. */
class RuleEngineTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val day = LocalDate.parse("2026-12-07")
    private val d = day.toString()
    private val plan = DayPlanner.build(day, app.agency.core.PracticeOff.SPECS)
    private fun at(s: String) = Instant.parse(s) // UTC; Taipei is +8
    private fun status(r: TodayRecords, now: String) = RuleEngine.status(plan, r, at(now), zone).associate { it.key to it.status }
    private fun meal(id: String, slot: SlotKey) = Meal(id, d, at("2026-12-07T04:20:00Z").toEpochMilli(), slotKey = slot.name, text = "午餐",
        items = listOf(MealItem("雞胸", 1.0, 200, 30.0, null, null, false, KcalSource.TABLE)))

    @Test fun laterThenOpenThenDone() {
        val r = TodayRecords(d, signs = listOf(SignLog("in", d, at("2026-12-07T00:31:00Z").toEpochMilli(), SignKind.IN)))
        val morning = status(r, "2026-12-07T02:00:00Z") // 10:00
        assertEquals(SlotStatus.DONE, morning[SlotKey.WAKE])
        assertEquals(SlotStatus.LATER, morning[SlotKey.LUNCH])
        val afternoon = status(r, "2026-12-07T04:30:00Z") // 12:30
        assertEquals(SlotStatus.OPEN, afternoon[SlotKey.LUNCH])
        assertEquals(listOf(SlotKey.LUNCH), RuleEngine.gaps(RuleEngine.status(plan, r, at("2026-12-07T04:30:00Z"), zone)).map { it.key })
        val fed = status(r.copy(meals = listOf(meal("m1", SlotKey.LUNCH))), "2026-12-07T04:30:00Z")
        assertEquals(SlotStatus.DONE, fed[SlotKey.LUNCH])
    }

    @Test fun strengthIsDueWhenHeGetsHomeOrAtItsLatestStart() {
        val noRide = RuleEngine.status(plan, TodayRecords(d), at("2026-12-07T15:00:00Z"), zone).first { it.key == SlotKey.STRENGTH }
        assertEquals(at("2026-12-07T17:50:00Z"), noRide.dueAt) // 01:50 that night
        val home = TodayRecords(d, signs = listOf(SignLog("end", d, at("2026-12-07T15:30:00Z").toEpochMilli(), SignKind.RIDE_END)))
        val s = RuleEngine.status(plan, home, at("2026-12-07T15:40:00Z"), zone).first { it.key == SlotKey.STRENGTH }
        assertEquals(at("2026-12-07T15:55:00Z"), s.dueAt) // the core first (15 minutes to put the helmet down), then the rounds
        val core = RuleEngine.status(plan, home, at("2026-12-07T15:40:00Z"), zone).first { it.key == SlotKey.CORE }
        assertEquals(at("2026-12-07T15:45:00Z"), core.dueAt)
        assertEquals(SlotStatus.LATER, s.status)
        val done = home.copy(training = listOf(TrainingLog("t", d, at("2026-12-07T16:00:00Z").toEpochMilli(), TrainingKind.STRENGTH, rounds = 3)))
        val stretch = RuleEngine.status(plan, done, at("2026-12-07T16:10:00Z"), zone).first { it.key == SlotKey.STRETCH }
        assertEquals(at("2026-12-07T16:30:00Z"), stretch.dueAt)
    }

    @Test fun afterSignOutOrAfterTheDayNothingIsAGap() {
        // He signs out early at 23:30: early sleep is never a gap, what is left is missed (and never chased).
        val r = TodayRecords(d, signs = listOf(SignLog("out", d, at("2026-12-07T15:30:00Z").toEpochMilli(), SignKind.OUT)))
        val states = RuleEngine.status(plan, r, at("2026-12-07T16:00:00Z"), zone)
        assertTrue(RuleEngine.gaps(states).isEmpty())
        assertEquals(SlotStatus.MISSED, states.first { it.key == SlotKey.STRENGTH }.status)
        assertEquals(SlotStatus.DONE, states.first { it.key == SlotKey.SIGN_OUT }.status)
        // The next day at 05:00 the old day is over.
        assertTrue(RuleEngine.status(plan, TodayRecords(d), at("2026-12-07T21:00:00Z"), zone).all { it.status == SlotStatus.MISSED })
    }

    @Test fun aRecordAt0200BelongsToTheEveningBefore() {
        // 02:00 on Tuesday the 8th (Taipei) is still Monday's coach day.
        assertEquals(day, CoachRules.coachDay(at("2026-12-07T18:00:00Z"), zone, 5))
        assertEquals(day.plusDays(1), CoachRules.coachDay(at("2026-12-07T21:00:00Z"), zone, 5))
    }

    @Test fun aRestDaysTrainingIsOwedAtItsTime() {
        val rest = DayPlanner.build(day, app.agency.core.PracticeOff.SPECS, override = app.agency.core.model.DayOverride(d, restDay = true, declaredAt = 1))
        val early = RuleEngine.status(rest, TodayRecords(d), at("2026-12-07T10:00:00Z"), zone) // 18:00
        assertFalse(early.first { it.key == SlotKey.CORE }.isGap)
        val states = RuleEngine.status(rest, TodayRecords(d), at("2026-12-07T14:30:00Z"), zone) // 22:30
        assertTrue(listOf(SlotKey.CORE, SlotKey.STRENGTH, SlotKey.STRETCH).all { k -> states.first { it.key == k }.isGap })
    }

    @Test fun signInOnTimeIsTenMinutes() {
        val due = at("2026-12-07T00:30:00Z")
        assertTrue(SignPolicy.onTime(at("2026-12-07T00:40:00Z"), due))
        assertTrue(SignPolicy.onTime(at("2026-12-07T00:10:00Z"), due))
        assertFalse(SignPolicy.onTime(at("2026-12-07T00:41:00Z"), due))
        assertEquals(90, SignPolicy.minutesLate(at("2026-12-07T02:00:00Z"), due))
        assertTrue(SignPolicy.lateSignOut(at("2026-12-07T18:31:00Z"), at("2026-12-07T18:30:00Z")))
    }
}
