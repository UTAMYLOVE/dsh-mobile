package app.agency.core.rollout

import app.agency.core.brain.Brain
import app.agency.core.engine.Effect
import app.agency.core.engine.Nerve
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 睡覺時間往前調 (owner 2026-11-26, a week-4 feature): 15 minutes earlier each week, class days 02:30 → 01:30, no-class days
 * 04:00 → 02:30; the training block and the 收工 reminder follow; only a sign-out after the time is a breach. Invented dates.
 */
class BedtimeTest {
    private val monday = LocalDate.parse("2026-12-07")
    private fun specs(week: Int, opened: LocalDate = monday) = PlanSpecs(rollout = RolloutSpec(week = week, openedOn = mapOf("4" to opened.toString())))
    private fun sleep(day: LocalDate, s: PlanSpecs = specs(4)) = DayPlanner.build(day, s).slot(SlotKey.SIGN_OUT)!!.time

    @Test fun fifteenMinutesEarlierEachWeekDownToTheTargets() {
        assertEquals("02:15", sleep(monday)) // its first week
        assertEquals("02:00", sleep(monday.plusDays(7)))
        assertEquals("01:30", sleep(monday.plusDays(28)))
        assertEquals("01:30", sleep(monday.plusDays(70))) // never past the target
        assertEquals("03:45", sleep(monday.plusDays(2))) // a no-class Wednesday
        assertEquals("02:30", sleep(monday.plusDays(2 + 7 * 7)))
        // Not open yet (week 3), or before the day it opened: the time stays.
        assertEquals("02:30", sleep(monday, specs(3)))
        assertEquals("02:30", sleep(monday.minusDays(7)))
        // The training block moves with it.
        val p = DayPlanner.build(monday, specs(4))
        assertEquals(listOf("01:25", "01:35", "01:55"), listOf(SlotKey.CORE, SlotKey.STRENGTH, SlotKey.STRETCH).map { p.slot(it)!!.time })
    }

    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private fun brain() = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private fun at(hhmm: String) {
        clock.now = monday.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }
    private fun say(hhmm: String, text: String): List<String> {
        at(hhmm)
        return runBlocking { brain().onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    @Test fun onlyASignOutAfterTheTimeIsABreachAndTheHomeReminderFollows() {
        runBlocking {
            store.profile.put(Profile(onboardingDone = true))
            store.rules.putSection(PlanSectionKey.ROLLOUT, PlanSpecs.JSON.encodeToString(RolloutSpec.serializer(), specs(4).rollout), PlanSection.BY_OWNER_SETTINGS, 0)
        }
        say("08:30", "簽到")
        // 收工時間 follows the earlier bed: 15 minutes before the block's latest start (01:25 tonight).
        val home = runBlocking { store.nerve.effects(monday.toString()) }.mapNotNull { Nerve.decodeEffect(it.json) as? Effect.Notify }.single { it.key == "ride-home" }
        assertEquals("收工時間到了：01:25 前回家開始訓練，最晚 02:15 簽退。單少也不要拖，睡眠不能少。", home.text)
        // 02:20 is after tonight's 02:15: a breach, said and counted.
        val out = say("02:20", "簽退")
        assertTrue(out.toString(), out.any { it == "記一次違規：超過最晚簽退的時間（02:15）。今天第 1 次，會算進評鑑。" })
        assertEquals(1, runBlocking { brain().nerve.state(monday) }.breaches)
    }

    @Test fun signingOutBeforeTheTimeIsNoBreach() {
        runBlocking {
            store.profile.put(Profile(onboardingDone = true))
            store.rules.putSection(PlanSectionKey.ROLLOUT, PlanSpecs.JSON.encodeToString(RolloutSpec.serializer(), specs(4).rollout), PlanSection.BY_OWNER_SETTINGS, 0)
        }
        say("08:30", "簽到")
        assertTrue(say("02:10", "簽退").none { it.startsWith("記一次違規") })
        assertEquals(0, runBlocking { brain().nerve.state(monday) }.breaches)
    }
}
