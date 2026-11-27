package app.agency.core.engine

import app.agency.core.brain.Brain
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run (owner 2026-11-27): out by 19:00 at the latest, chased every 30 minutes from then until 「出門跑單」; a short night
 * or a long day only warns when he leaves (L22, through the brain); sleepy means no riding. Invented data only.
 */
class DepartureTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private val monday = LocalDate.parse("2026-12-07")

    init {
        runBlocking { store.profile.put(Profile(onboardingDone = true)) }
    }

    private fun brain() = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private fun at(hhmm: String, day: LocalDate = monday) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }
    private fun say(hhmm: String, text: String, day: LocalDate = monday): List<String> {
        at(hhmm, day)
        return runBlocking { brain().onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }
    private fun gap(hhmm: String) = runBlocking { at(hhmm); brain().tick() }.posts.filter { it.key == Nudger.GAP_KEY }

    @Test fun byNineteenHeIsOutOrTheChaseStarts() {
        // Everything else of the day is in, so only the run can be missing.
        runBlocking { brain().tick() }
        say("08:30", "簽到")
        listOf("12:15" to "LUNCH", "17:40" to "DINNER").forEach { (t, k) ->
            runBlocking {
                at(t)
                brain().signal(Signal.MealLogged("m-$k", clock.millis(), SlotKey.valueOf(k), 650, 40.0))
                store.meals.insert(app.agency.core.model.Meal("meal-$k", monday.toString(), clock.millis(), k, "飯", listOf(app.agency.core.model.MealItem("便當", 1.0, 650, 40.0, null, null, false, app.agency.core.model.KcalSource.TABLE))))
            }
        }
        // 18:30: inside the window, nothing to chase; the next fire is 19:00.
        assertTrue(gap("18:30").isEmpty())
        assertEquals(DayClock.minute("19:00"), Links.minuteOf(monday, runBlocking { brain().nextFire() }!!.at.toEpochMilli(), AgencyClock.TAIPEI))
        val late = gap("19:00").single()
        assertEquals(Links.L12Chase.RIDE_LATE, late.text)
        assertEquals(listOf(SlotKey.RIDE_OUT), late.gapKeys)
        // 30 minutes later, again; then 出門跑單 ends it.
        assertTrue(gap("19:15").isEmpty())
        assertEquals(1, gap("19:30").size)
        say("19:35", "出門跑單")
        assertTrue(gap("20:05").none { SlotKey.RIDE_OUT in it.gapKeys })
    }

    @Test fun aShortNightWarnsWhenHeLeavesThroughTheBrain() {
        val sunday = monday.minusDays(1)
        say("12:00", "簽到", sunday)
        say("03:30", "簽退", sunday) // 03:30 Monday morning, still Sunday's coach day
        say("08:30", "簽到")
        val out = say("18:10", "出門跑單")
        assertTrue(out.toString(), out.any { it.startsWith("疲勞騎車很危險：昨晚只睡 5 小時。") })
    }

    @Test fun sleepyOnADeliveryDayMeansNoRiding() {
        say("08:30", "簽到")
        say("18:10", "出門跑單")
        assertEquals(listOf(Links.L22TiredRiding.SLEEPY), say("21:30", "好睏喔"))
        // Home: sleepy is no riding question any more; 「我要睡了」 is the sign-out, never this.
        say("23:00", "收工")
        assertTrue(runBlocking { at("23:10"); brain().signal(Signal.Sleepy("s2", clock.millis())) }.messages.isEmpty())
        assertTrue(!app.agency.core.brain.handlers.BodyWords.sleepy("我要睡了"))
    }
}
