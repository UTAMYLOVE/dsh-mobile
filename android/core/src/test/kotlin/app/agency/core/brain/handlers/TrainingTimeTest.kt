package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.engine.IllKind
import app.agency.core.engine.Links
import app.agency.core.engine.Routine
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Profile
import app.agency.core.model.TrainingKind
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
 * The owner's training times of 2026-11-26: on a delivery day the core, the rounds and the cardio wait for 收工 (never before
 * the ride, never while riding); on a day without a run any time; the 10-minute cardio three days a week; a sharp or joint pain
 * is an injury and he stops at once. Invented data only.
 */
class TrainingTimeTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-11-24") // a Tuesday: a class day with cardio

    init {
        runBlocking { store.profile.put(Profile(joinDate = "2026-11-21", onboardingDone = true)) }
    }

    private fun brain() = Brain(store, ScriptedLlm(), { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }
    private fun say(hhmm: String, text: String): List<String> {
        at(hhmm)
        return runBlocking { brain().onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    @Test fun theCardioIsTenMinutesOfThreeMovesInTurn() {
        val c = Routine.cardio(10)
        val moves = c.filter { !it.rest }
        assertEquals(10, moves.size)
        assertEquals(listOf("開合跳", "波比跳", "深蹲跳", "開合跳"), moves.take(4).map { it.name })
        assertTrue(moves.all { it.seconds == Routine.CARDIO_WORK_SECONDS })
        assertTrue(Routine.timedSeconds(c) in 540..600)
        assertTrue(c.all { s -> s.how.none { it in 'a'..'z' || it in 'A'..'Z' } })
    }

    @Test fun onADeliveryDayTheBlockWaitsForHomeAndThenRunsInOrder() {
        assertEquals(listOf(TrainingDesk.BEFORE_RIDE), say("17:10", "體能完成"))
        say("18:05", "出門跑單")
        // While riding: still no.
        assertEquals(listOf(TrainingDesk.BEFORE_RIDE), say("20:00", "核心完成"))
        assertEquals(listOf("收工了。放下安全帽，接著訓練：核心、肌力、體能、伸展。"), say("23:00", "收工"))
        // The stretch may be done any time; the block now in order.
        at("23:10")
        assertTrue(runBlocking { brain().coreDone(420) }.first { it.who == Who.COMPANY }.text.startsWith("記下：核心七分鐘"))
        at("23:30")
        val cardio = runBlocking { brain().cardioDone(590) }.first { it.who == Who.COMPANY }.text
        assertEquals("記下：體能 10 分鐘（照計時做完）。\n接著伸展 10 分鐘。", cardio)
        assertEquals(listOf(TrainingKind.CORE7, TrainingKind.CARDIO), runBlocking { store.training.onDay(day.toString()) }.map { it.kind })
        // The rounds are due after the core, the cardio after the rounds (in order from 收工).
        val plan = runBlocking { brain().nerve.plan(day) }
        assertTrue(plan.slot(SlotKey.CORE)!!.afterRideMin < plan.slot(SlotKey.STRENGTH)!!.afterRideMin)
        assertTrue(plan.slot(SlotKey.STRENGTH)!!.afterRideMin < plan.slot(SlotKey.CARDIO)!!.afterRideMin)
    }

    @Test fun pastTheLatestDepartureWithoutGoingOutTheBlockStillWaits() {
        // 19:10, still home (late): the rounds are not done before the ride; a day without the run is 「今天休息」.
        assertEquals(listOf(TrainingDesk.BEFORE_RIDE), say("19:10", "核心完成"))
        assertEquals(listOf(TrainingDesk.BEFORE_RIDE), say("21:30", "肌力完成"))
        assertTrue(runBlocking { store.training.onDay(day.toString()) }.isEmpty())
    }

    @Test fun aDayWithoutARunTrainsAnyTimeAfterFive() {
        say("09:00", "今天休息")
        at("17:10")
        assertTrue(runBlocking { brain().coreDone(420) }.first { it.who == Who.COMPANY }.text.startsWith("記下：核心七分鐘"))
        assertEquals("記下：體能 10 分鐘（打字回報，未證實）。", say("17:30", "體能完成").first().lines().first())
    }

    @Test fun aSharpOrJointPainIsAnInjuryAndHeStopsAtOnce() {
        assertEquals(IllKind.INJURY to "刺痛", BodyWords.illness("做深蹲的時候膝蓋刺痛")!!.let { it.first to it.second }.let { (k, w) -> k to (if (w.endsWith("刺痛")) "刺痛" else w) })
        assertEquals(IllKind.INJURY, BodyWords.illness("手腕關節痛")!!.first)
        // Sore is never one.
        assertEquals(null, BodyWords.illness("腿好痠"))
        val out = say("23:20", "做伏地挺身手腕關節痛")
        assertEquals(Links.L2Illness.PAIN_STOP, out.first())
        assertTrue(out[1], out[1].contains("避開受傷的地方"))
    }
}
