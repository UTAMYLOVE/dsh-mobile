package app.agency.core.rollout

import app.agency.core.brain.Brain
import app.agency.core.engine.Links
import app.agency.core.engine.Signal
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlanner
import app.agency.core.schedule.Feature
import app.agency.core.schedule.LessonDay
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.PlanStore
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

/**
 * 分週上線 (owner 2026-11-27), data-driven: week 1 only lessons, week 2 + sign-in, meals, water, core and strength (and the
 * run), week 3 + cardio, the walk, grooming…, week 4 + the rest. Not open: not planned, not chased, not counted, and the
 * command says when it opens. A week on target brings the card for the next week; his 好 opens it; 設定 can set it too.
 * Invented data only.
 */
class RolloutTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private val monday = LocalDate.parse("2026-12-07")

    init {
        runBlocking {
            store.profile.put(Profile(onboardingDone = true))
            PlanStore(store).seedIfMissing(0) // a new install: week 1
        }
    }

    private fun brain(llm: ScriptedLlm = ScriptedLlm()) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private fun at(hhmm: String, day: LocalDate = monday) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }
    private fun say(hhmm: String, text: String, llm: ScriptedLlm = ScriptedLlm()): List<String> {
        at(hhmm)
        return runBlocking { brain(llm).onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }
    private fun week(w: Int) = PlanSpecs(rollout = RolloutSpec(week = w))
    private fun keys(w: Int, day: LocalDate = monday.plusDays(1)) = DayPlanner.build(day, week(w), lessons = LessonDay(2)).slots.map { it.key }.toSet()

    @Test fun eachWeekPlansOnlyWhatIsOpen() {
        // Week 1: the lessons only — no meal, no training, no run, no sign-in.
        assertEquals(setOf(SlotKey.LESSONS), keys(1))
        // Week 2: + sign-in and sign-out, the three meals, the run, the core, the rounds and the stretch.
        assertEquals(
            setOf(SlotKey.WAKE, SlotKey.LUNCH, SlotKey.DINNER, SlotKey.RIDE_OUT, SlotKey.RIDE_MEAL, SlotKey.CORE, SlotKey.STRENGTH, SlotKey.STRETCH, SlotKey.SIGN_OUT, SlotKey.LESSONS),
            keys(2),
        )
        // Week 3: + the cardio (a Tuesday) and the walk (a no-class Wednesday).
        assertTrue(SlotKey.CARDIO in keys(3) && SlotKey.CARDIO !in keys(2))
        assertTrue(SlotKey.WALK in keys(3, monday.plusDays(2)) && SlotKey.WALK !in keys(2, monday.plusDays(2)))
        // The table is data: what each week opens, and 「第 N 週開放」.
        val r = RolloutSpec(week = 1)
        assertEquals(listOf(Feature.LESSONS, Feature.EXAM_WEEK), r.opensIn(1))
        assertTrue(Feature.EARLIER_BEDTIME in r.opensIn(4) && Feature.WEEKLY_EVAL in r.opensIn(4) && Feature.REAL_TASK in r.opensIn(4))
        assertEquals("三餐拍照第 2 週開放，現在是第 1 週。這週先把開放的做好。", r.closedLine(Feature.MEALS))
        val custom = RolloutSpec(week = 1, table = r.table + (Feature.WATER.name to 1))
        assertTrue(custom.open(Feature.WATER))
    }

    @Test fun inWeekOneNothingButLessonsIsChasedOrTaken() {
        at("07:00")
        val first = runBlocking { brain().tick() }
        // No real-person task yet (week 4), no meal or training reminder all day.
        assertTrue(runBlocking { store.messages.recent(10) }.none { it.text.startsWith("這週的真人任務") })
        assertTrue(first.posts.isEmpty())
        listOf("12:30", "17:40", "21:10", "23:59").forEach { hhmm ->
            at(hhmm)
            assertTrue(hhmm, runBlocking { brain().tick() }.posts.none { it.key == "gaps" })
        }
        // A command of a closed feature says when it opens; nothing is written.
        assertEquals(listOf(RolloutSpec(week = 1).closedLine(Feature.SIGN)), say("08:30", "簽到"))
        assertEquals(listOf(RolloutSpec(week = 1).closedLine(Feature.RIDE)), say("18:00", "出門跑單"))
        assertEquals(listOf(RolloutSpec(week = 1).closedLine(Feature.WATER)), say("10:00", "喝了半壺"))
        assertEquals(listOf(RolloutSpec(week = 1).closedLine(Feature.CORE)), say("20:00", "核心完成"))
        val meal = say("12:30", "午餐吃了雞腿便當", ScriptedLlm(ScriptedLlm.ok("""{"kind":"meal","items":[{"name":"雞腿便當","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":600,"kcal_high":700,"protein_low":30,"protein_high":30}]}""")))
        assertEquals(listOf(RolloutSpec(week = 1).closedLine(Feature.MEALS)), meal)
        runBlocking {
            assertTrue(store.signs.onDay(monday.toString()).isEmpty())
            assertTrue(store.meals.onDay(monday.toString()).isEmpty())
            assertTrue(store.training.onDay(monday.toString()).isEmpty())
            assertTrue(store.water.between("0000-01-01", "9999-12-31").isEmpty())
        }
        // The card says which week it is and what is open.
        val card = runBlocking { brain().card() }
        assertEquals(1, card.rolloutWeek)
        assertEquals(setOf(Feature.LESSONS, Feature.EXAM_WEEK), card.open)
    }

    @Test fun aWeekOnTargetBringsTheCardAndHisYesOpensTheNextWeek() {
        at("07:00")
        val b = brain()
        runBlocking { b.signal(Signal.WeekEnded("week:test", clock.millis(), monday.minusDays(7).toString(), due = 14, done = 14, onTarget = true, weeksOnTarget = 1)) }
        val card = runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single { it.kind == ProposalKind.ROLLOUT_NEXT }
        assertTrue(card.reason, card.reason.contains("第 2 週開始，新開放：簽到簽退、三餐拍照、喝水、核心、肌力和伸展"))
        assertEquals(1, runBlocking { b.rollout() }.week)
        // It waits across days (it changes no day), until his answer.
        at("09:00", monday.plusDays(1))
        val yes = runBlocking { b.decide(card.id, true) }!!.text
        assertTrue(yes, yes.startsWith("好，第 2 週開始。新開放："))
        val r = runBlocking { b.rollout() }
        assertEquals(2, r.week)
        assertEquals(monday.plusDays(1), r.openedDay(2))
        assertTrue(runBlocking { b.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.LUNCH) != null)
        // A week not on target brings no card.
        runBlocking { b.signal(Signal.WeekEnded("week:test2", clock.millis(), monday.toString(), due = 10, done = 5, onTarget = false)) }
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.none { it.kind == ProposalKind.ROLLOUT_NEXT })
    }

    @Test fun heSetsTheWeekInSettingsAndABrokenSectionIsWeekOne() {
        at("10:00")
        val b = brain()
        assertEquals(3, runBlocking { b.setRolloutWeek(3) }.week)
        assertEquals(PlanSection.BY_OWNER_SETTINGS, runBlocking { store.rules.section(PlanSectionKey.ROLLOUT) }!!.changedBy)
        assertEquals(RolloutSpec.LAST, runBlocking { b.setRolloutWeek(9) }.week)
        assertEquals(1, runBlocking { b.setRolloutWeek(1) }.week)
        // Going back forgets the opening days of the later weeks.
        assertNull(runBlocking { b.rollout() }.openedDay(3))
        runBlocking { store.rules.putSection(PlanSectionKey.ROLLOUT, "不是 JSON", PlanSection.BY_OWNER_SETTINGS, 1) }
        assertEquals(RolloutSpec.SEED, runBlocking { PlanStore(store).specs() }.rollout)
        // A store never seeded (no section at all) has everything open, as before the rollout.
        assertEquals(RolloutSpec.ALL_OPEN, PlanSpecs.decode(emptyMap()).rollout)
        assertFalse(RolloutSpec.SEED.open(Feature.MEALS))
        assertTrue(Links.L25Rollout.feature == null)
    }
}
