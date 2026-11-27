package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.DayOverride
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.PlanStore
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外送收入 (owner 2026-11-26): a day's amount by code (a second report replaces it), the week's total against his own weekly
 * target (set by command; the code ships none), and sleep first: a short week is made up on the weekend, never with less
 * sleep. Invented amounts only.
 */
class IncomeTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-12-07") // a Monday

    init {
        runBlocking { store.profile.put(Profile(onboardingDone = true)) }
    }

    private fun brain() = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private fun say(hhmm: String, text: String): List<String> {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
        return runBlocking { brain().onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    @Test fun theWordsAreReadByCode() {
        assertEquals(1200, IncomeWords.report("外送 1200 元"))
        assertEquals(850, IncomeWords.report("今天跑單賺了850"))
        assertEquals(990, IncomeWords.report("收入 990 元"))
        assertNull(IncomeWords.report("外送好累"))
        assertEquals(6000, IncomeWords.target("外送週目標 6000"))
    }

    @Test fun theWeekCountsTowardHisOwnTargetAndSleepComesFirst() {
        // No target yet: the code has none of his numbers.
        assertNull(runBlocking { PlanStore(store).specs() }.schedule.weeklyIncomeTarget)
        assertEquals(listOf("記下：今天外送 500 元。這週到現在 500 元。外送週目標還沒設，打「外送週目標 數字」設一次就好。"), say("23:30", "外送 500 元"))
        assertEquals(listOf("記下：外送週目標 6000 元（一週跑 6 天，看一週的總額）。"), say("23:31", "外送週目標 6000"))
        // A second report of the day replaces the first; behind the pace (1000 a day), the sleep line.
        assertEquals(listOf("記下：今天外送 800 元。這週到現在 800 元，目標 6000 元，還差 5200 元。" + IncomeDesk.SLEEP_FIRST), say("23:40", "外送 800 元"))
        day = day.plusDays(1)
        assertEquals(listOf("記下：今天外送 1300 元。這週到現在 2100 元，目標 6000 元，還差 3900 元。"), say("23:30", "外送 1300 元"))
        // The card shows the week.
        val card = runBlocking { brain().card() }
        assertEquals(2100 to 6000, card.incomeWeek to card.incomeTarget)
        // A rest day counts no delivery day; the next week starts at zero.
        day = LocalDate.parse("2026-12-14")
        runBlocking { store.days.put(DayOverride(day.toString(), restDay = true, declaredAt = 0)) }
        assertTrue(say("12:00", "外送 0 元").single().startsWith("記下：今天外送 0 元。這週到現在 0 元，目標 6000 元，還差 6000 元。"))
        assertEquals(0, runBlocking { brain().card() }.incomeWeek)
    }
}
