package app.agency.core.brain

import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Profile
import app.agency.core.model.SignKind
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlanner
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.RolloutSpec
import app.agency.core.schedule.SlotKey
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

/** The fixes of the whole-branch logic review (REVIEW-logic S1, I1, I2). Invented data only. */
class ReviewLogicFixTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private val sunday = LocalDate.parse("2026-11-22")
    private val monday = sunday.plusDays(1)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)

    init {
        runBlocking { store.profile.put(Profile(joinDate = "2026-11-20", onboardingDone = true)) }
    }

    private val brain = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }

    private fun at(day: LocalDate, hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun say(day: LocalDate, hhmm: String, text: String): List<String> {
        at(day, hhmm)
        return runBlocking { brain.onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    @Test fun aSignOutAfterTheDayBoundaryClosesLastNightLateAndLeavesTheNewDayAlone() {
        say(tuesday, "08:30", "簽到")
        // 05:10 on Wednesday (the coach day has turned): last night's, late (Tuesday's latest is 02:30), no review asked.
        at(wednesday, "05:10")
        assertEquals(tuesday, runBlocking { brain.lateSignOutDay() })
        val out = say(wednesday, "05:10", "簽退")
        assertTrue(out.first(), out.first().startsWith("簽退了，比最晚的時間晚了。"))
        val tue = runBlocking { store.signs.onDay(tuesday.toString()) }.single { it.kind == SignKind.OUT }
        assertEquals(false, tue.onTime)
        assertTrue(runBlocking { store.signs.onDay(wednesday.toString()) }.isEmpty())
        assertNull(runBlocking { brain.reviewOpen() })
        assertEquals(1, runBlocking { brain.nerve.state(tuesday) }.breaches)
        // The new day is not signed out: it is planned and chased as usual, and his sign-in is a sign-in.
        assertNull(runBlocking { brain.nerve.state(wednesday) }.signedOutAt)
        assertEquals(listOf("簽到了，準時。"), say(wednesday, "11:00", "簽到").take(1))
        assertNull(runBlocking { brain.lateSignOutDay() })
    }

    @Test fun lateInTheMorningOrAfterTodaysSignInItIsTodays() {
        say(tuesday, "08:30", "簽到")
        at(wednesday, "12:30")
        assertNull(runBlocking { brain.lateSignOutDay() })
        // Signed out last night: nothing is owed.
        say(wednesday, "11:00", "簽到")
        say(wednesday, "23:50", "簽退")
        at(wednesday.plusDays(1), "05:30")
        assertNull(runBlocking { brain.lateSignOutDay() })
    }

    @Test fun noSignOutAtAllIsABreachLikeALateOne() {
        at(sunday, "12:00")
        runBlocking { brain.tick() }
        say(monday, "08:30", "簽到")
        // Monday ends without a sign-out; Tuesday's first tick reads the day that was missed.
        at(tuesday, "09:00")
        runBlocking { brain.tick() }
        assertEquals(1, runBlocking { brain.nerve.state(tuesday) }.weekBreaches)
    }

    @Test fun aWeekOpenedMidWeekNeverRewritesTheDaysBeforeIt() {
        val thursday = monday.plusDays(3)
        val rollout = RolloutSpec.SEED.at(2, thursday)
        val specs = PlanSpecs(rollout = rollout)
        assertFalse(rollout.openOn(app.agency.core.schedule.Feature.SIGN, monday))
        assertTrue(rollout.openOn(app.agency.core.schedule.Feature.SIGN, thursday))
        assertNull(DayPlanner.build(monday, specs).slot(SlotKey.SIGN_OUT))
        assertNull(DayPlanner.build(wednesday, specs).slot(SlotKey.LUNCH))
        assertTrue(DayPlanner.build(thursday, specs).slot(SlotKey.SIGN_OUT) != null)
        // A jump over a week opens the weeks passed over on the same day (none of them in the past).
        val jump = RolloutSpec.SEED.at(3, thursday)
        assertEquals(thursday, jump.openedDay(2))
        assertNull(DayPlanner.build(monday, PlanSpecs(rollout = jump)).slot(SlotKey.LUNCH))
    }

    @Test fun theRealPersonTasksStartWithAMealWithAFriendAndGetHarderAsTheyAreDone() {
        assertTrue(app.agency.core.engine.RealTasks.of(0).startsWith("約一個認識的朋友吃一頓飯"))
        assertTrue(app.agency.core.engine.RealTasks.ALL.none { it.contains("吃飯不算") })
        assertEquals(app.agency.core.engine.RealTasks.ALL[1], app.agency.core.engine.RealTasks.of(1))
        assertEquals(app.agency.core.engine.RealTasks.ALL.last(), app.agency.core.engine.RealTasks.of(99))
    }
}
