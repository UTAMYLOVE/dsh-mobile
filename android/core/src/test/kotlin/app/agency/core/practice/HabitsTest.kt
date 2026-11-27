package app.agency.core.practice

import app.agency.core.brain.Brain
import app.agency.core.engine.Habits
import app.agency.core.engine.Signal
import app.agency.core.engine.SlotStatus
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.LessonCategory
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.Habit
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 學了就用: one habit at a time, the next only after two steady weeks, added only on his yes, then a slot like any. Invented data. */
class HabitsTest {
    private val today = LocalDate.parse("2026-12-20")

    @Test fun oneAtATimeAndOnlyAfterTwoSteadyWeeks() {
        assertTrue(Habits.mayOffer(LessonCategory.SKIN, emptyList(), today, emptySet()))
        assertFalse(Habits.mayOffer(LessonCategory.VOCAL, emptyList(), today, emptySet())) // no daily-life habit
        val skin = Habit("h1", "保養", LessonCategory.SKIN.name, today.minusDays(10).toString(), "00:30")
        assertFalse(Habits.mayOffer(LessonCategory.SKIN, listOf(skin), today, emptySet())) // already one
        val all = (1..14).map { today.minusDays(it.toLong()) }.toSet()
        assertFalse(Habits.mayOffer(LessonCategory.OUTFIT, listOf(skin), today, all)) // added only 10 days ago
        val old = skin.copy(addedOn = today.minusDays(14).toString())
        assertTrue(Habits.mayOffer(LessonCategory.OUTFIT, listOf(old), today, all.drop(2).toSet())) // 12 of 14
        assertFalse(Habits.mayOffer(LessonCategory.OUTFIT, listOf(old), today, all.drop(3).toSet())) // 11 of 14
    }

    @Test fun hisYesAddsItFromTomorrowAndItIsHandedInLikeAnyItem() {
        val store = InMemoryAgencyStore()
        val clock = MutableClock()
        var n = 0
        runBlocking {
            store.profile.put(Profile(onboardingDone = true))
            PlanStore(store).seedIfMissing(0, app.agency.core.schedule.RolloutSpec(week = 4))
        }
        fun at(hhmm: String, day: LocalDate) {
            clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
        }
        val b = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
        at("12:00", today)
        runBlocking { b.signal(Signal.HabitOffer("habit:SKIN", clock.millis(), LessonCategory.SKIN.name)) }
        val card = runBlocking { b.pendingProposals() }.single()
        assertTrue(card.lines.joinToString { it.after }.contains("洗澡後保養") || card.title.contains("洗澡後保養") || card.reason.contains("皮膚保養"))
        assertTrue(runBlocking { b.decide(card.id, true) }!!.text.contains("明天起每天：洗澡後保養"))
        assertTrue(runBlocking { b.card() }.slots.none { it.key == SlotKey.HABIT }) // from tomorrow
        at("12:00", today.plusDays(1))
        assertEquals(SlotStatus.LATER, runBlocking { b.card() }.slots.first { it.key == SlotKey.HABIT }.status)
        val said = runBlocking { b.onText("小習慣完成") }.filter { it.who == Who.COMPANY }.joinToString { it.text }
        assertTrue(said, said.contains("記下：小習慣"))
        assertEquals(SlotStatus.DONE, runBlocking { b.card() }.slots.first { it.key == SlotKey.HABIT }.status)
    }
}
