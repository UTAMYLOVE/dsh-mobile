package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.engine.Effect
import app.agency.core.engine.Nerve
import app.agency.core.engine.WaterPlan
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.DayOverride
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlanner
import app.agency.core.schedule.WaterSpec
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T17: the 1 L bottle plan, the reports, and the reminders that stay quiet once the water is in. Invented data. */
class WaterT17Test {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-11-23") // a Monday (class day)

    init {
        runBlocking { store.profile.put(Profile(joinDate = "2026-11-21", onboardingDone = true)) }
    }

    private fun brain(llm: ScriptedLlm = ScriptedLlm()) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun say(hhmm: String, text: String, llm: ScriptedLlm = ScriptedLlm()): List<Message> {
        at(hhmm)
        return runBlocking { brain(llm).onText(text) }.filter { it.who == Who.COMPANY }
    }

    private fun posts(hhmm: String) = runBlocking { at(hhmm); brain().tick() }.posts

    @Test fun theTargetGrowsHalfABottleEveryTwoWeeksUpToTwoBottles() {
        val spec = WaterSpec(startDay = "2026-11-21")
        fun t(d: String) = WaterPlan.target(LocalDate.parse(d), spec)
        assertEquals(2, t("2026-11-21"))
        assertEquals(2, t("2026-12-04")) // day 14: still weeks 1–2
        assertEquals(3, t("2026-12-05")) // week 3
        assertEquals(3, t("2026-12-18"))
        assertEquals(4, t("2026-12-19")) // week 5
        assertEquals(4, t("2027-03-01")) // never more than two bottles
        assertEquals(2, t("2026-11-01")) // before the start
        // No start set: it counts from the day he joined.
        assertEquals(3, WaterPlan.target(LocalDate.parse("2026-12-05"), WaterSpec(), "2026-11-21"))
        assertEquals("1 壺半", WaterPlan.bottles(3))
        assertEquals("半壺", WaterPlan.bottles(1))
    }

    @Test fun theButtonsAndHisWordsRecordPlainWaterOnly() {
        val one = say("10:00", "喝完一壺").single()
        assertEquals(RoleKey.BODY, one.role)
        assertEquals("記下：喝了 1 壺。今天 1 壺，目標 1 壺。\n今天的水達標了。", one.text)
        // Words the clerk sorts: 「喝完一壺了」 is two halves.
        say("11:00", "剛剛喝完一壺了", ScriptedLlm(ScriptedLlm.ok("""{"kind":"water","water_half":2}""")))
        assertEquals(4, runBlocking { store.water.onDay(day.toString()) }.sumOf { it.halfBottles })
        // 「喝了豆漿」 is a meal, whatever the clerk says.
        assertEquals("meal", MealWords.kindOf("water", "喝了豆漿"))
        assertEquals("water", MealWords.kindOf("water", "喝了半壺水"))
        assertEquals(1, WaterDesk.halvesIn("喝了半壺"))
        assertEquals(3, WaterDesk.halvesIn("今天喝了一壺半"))
        assertEquals(4, WaterDesk.halvesIn("喝了兩壺"))
        assertNull(WaterDesk.halvesIn("喝水了"))
        assertTrue(runBlocking { brain().nerve.state(day) }.waterHalves == 4)
    }

    @Test fun beforeTheRunWhatIsLeftIsSaidAndOnTheRunOnlySips() {
        // Week 3 (joined 2026-11-21): one and a half bottles.
        day = LocalDate.parse("2026-12-07")
        assertEquals("記下：喝了半壺。今天 半壺，目標 1 壺半。\n出門跑單前還要喝 1 壺，分幾次喝，不要一次灌。", say("10:00", "喝了半壺").single().text)
        say("18:05", "出門跑單")
        assertEquals("記下：喝了半壺。今天 1 壺，目標 1 壺半。\n路上小口喝就好，不要一次喝太多。", say("19:00", "喝了半壺").single().text)
    }

    @Test fun theAfternoonCheckAndThePreRideReminderSpeakOnlyWhenTheWaterIsBehind() {
        // A class day: 15:00 with nothing drunk → the check goes out; 17:40 before the 18:00 run → the pre-ride reminder.
        assertTrue(posts("07:00").isEmpty())
        assertEquals(
            "下午三點了，水還不到一半（今天要喝 1 壺）。現在分幾口喝，出門前喝完，不要一次灌。",
            posts("15:00").single { it.key == "water-half" }.text,
        )
        assertTrue(posts("17:40").any { it.key == "preride" })
        // The next day he drinks in time: both stay quiet.
        day = day.plusDays(1)
        posts("07:00")
        say("11:00", "喝了半壺")
        assertTrue(posts("15:00").none { it.key == "water-half" })
        say("17:00", "喝了半壺")
        assertTrue(posts("17:40").none { it.key == "preride" })
    }

    @Test fun aNoClassDayChecksAtThreeAndARestDayStillHasItsWaterByEight() {
        // Wednesday, no class (wake by 12:00): the same 15:00 check and the run at 18:00.
        day = LocalDate.parse("2026-11-25")
        posts("12:30")
        assertTrue(posts("15:00").any { it.key == "water-half" })
        assertTrue(posts("17:40").any { it.key == "preride" })
        // A rest day: no run, so no pre-ride reminder, but the bottle is still due, by 20:00.
        day = LocalDate.parse("2026-11-26")
        runBlocking { store.days.put(DayOverride(day.toString(), restDay = true, declaredAt = 0)) }
        posts("09:00")
        assertTrue(posts("17:40").none { it.key == "preride" })
        assertEquals("今天的水（1 壺）還沒喝完，睡前不要猛喝，現在分幾次喝。", posts("20:00").single { it.key == "water-goal" }.text)
        val rest = DayPlanner.build(day, override = DayOverride(day.toString(), restDay = true, declaredAt = 0))
        assertEquals(WaterPlan.NO_RIDE_DEADLINE_MINUTE, WaterPlan.deadline(rest))
        // The reminders carry what would make them unnecessary.
        val notes = runBlocking { store.nerve.effects("2026-11-25") }.mapNotNull { Nerve.decodeEffect(it.json) as? Effect.Notify }
        assertEquals(1, notes.single { it.key == "water-half" }.unlessWaterHalves)
        assertEquals(2, notes.single { it.key == "preride" }.unlessWaterHalves)
        assertFalse(notes.any { it.key == "water-goal" })
    }

    @Test fun theCardAndTodaysFactsShowTheBottle() {
        say("10:00", "喝了半壺")
        val card = runBlocking { brain().card() }
        assertEquals(1, card.waterHalves)
        assertEquals(2, card.waterTargetHalves)
    }
}
