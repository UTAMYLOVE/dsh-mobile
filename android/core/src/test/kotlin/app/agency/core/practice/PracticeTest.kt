package app.agency.core.practice

import app.agency.core.brain.Brain
import app.agency.core.brain.Route
import app.agency.core.brain.Router
import app.agency.core.engine.EvalItem
import app.agency.core.engine.EvalScore
import app.agency.core.engine.MonthlyEval
import app.agency.core.engine.MonthlyResult
import app.agency.core.engine.Practice
import app.agency.core.engine.RuleEngine
import app.agency.core.engine.ScoreBy
import app.agency.core.engine.SlotStatus
import app.agency.core.engine.SurpriseChecks
import app.agency.core.engine.Themes
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Media
import app.agency.core.model.MediaKind
import app.agency.core.model.MediaPurpose
import app.agency.core.model.Profile
import app.agency.core.model.TrainingKind
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayPlanner
import app.agency.core.schedule.DayType
import app.agency.core.schedule.PlanSpecs
import app.agency.core.schedule.RolloutSpec
import app.agency.core.schedule.ScheduleSpec
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.Timetable
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import app.agency.core.vision.EvalReads
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The practice items of rollout weeks 3 and 4 (定稿 §3, §6, §10, §11): planned only when open, handed in by timer, tap, photo
 * or the evaluation, chased like any item; 突擊檢查 closes after ten minutes; 心情 three days low → the manager asks; 月評's
 * scores by code where they can be computed. Invented data only.
 */
class PracticeTest {
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
    private fun keys(w: Int, day: LocalDate) = DayPlanner.build(day, PlanSpecs(rollout = RolloutSpec(week = w))).slots.map { it.key }.toSet()
    private fun company(list: List<app.agency.core.model.Message>) = list.filter { it.who == Who.COMPANY }.joinToString("\n") { it.text }

    @Test fun weekThreeAddsTheMorningItemsAndWeekFourTheDanceAndTheEvaluations() {
        val tue = monday.plusDays(1)
        val wed = monday.plusDays(2)
        val sun = monday.plusDays(6)
        assertTrue(keys(2, tue).none { it.kind == app.agency.core.schedule.SlotKind.PRACTICE })
        assertTrue(keys(3, tue).containsAll(listOf(SlotKey.MORNING_STRETCH, SlotKey.VOCAL_BASICS, SlotKey.POSTURE, SlotKey.SUNSCREEN)))
        assertFalse(SlotKey.DANCE in keys(3, wed))
        assertTrue(keys(4, wed).containsAll(listOf(SlotKey.DANCE, SlotKey.VOCAL_PRACTICE, SlotKey.EXPRESSION)))
        assertFalse(SlotKey.DANCE in keys(4, tue)) // a class day has no dance block
        assertTrue(keys(4, sun).containsAll(listOf(SlotKey.WEEKLY_EVAL, SlotKey.BODY_PHOTO)))
        // The last Sunday of the month is the 月評 instead.
        val last = LocalDate.parse("2026-12-27")
        assertTrue(Practice.monthEnd(last) && !Practice.monthEnd(sun))
        assertTrue(SlotKey.MONTHLY_EVAL in keys(4, last) && SlotKey.WEEKLY_EVAL !in keys(4, last))
        assertTrue(SlotKey.THEME_TASK in keys(4, monday.plusDays(5)))
        // A rest day has no run, so no 防曬.
        val rest = DayPlanner.build(tue, PlanSpecs(), override = app.agency.core.model.DayOverride(tue.toString(), restDay = true, declaredAt = 1))
        assertNull(rest.slot(SlotKey.SUNSCREEN))
    }

    @Test fun surpriseChecksAreTwoOrThreeAWeekOnlyOutsideClassAndTheRun() {
        val s = ScheduleSpec()
        for (w in 0L until 12L) {
            val m = monday.plusWeeks(w)
            val checks = SurpriseChecks.week(m, s)
            assertTrue(checks.size in 2..3)
            checks.forEach { (d, c) ->
                val (from, to) = if (Timetable.type(d, s) == DayType.CLASS) "17:05" to "17:45" else "13:30" to "15:50"
                assertTrue("$d ${DayClock.hhmm(c.minute)}", c.minute in DayClock.minute(from)..DayClock.minute(to))
                assertTrue(c.minute < DayClock.minute(s.rideOut)) // before any run
            }
            assertEquals(checks, SurpriseChecks.week(m, s)) // the same week gives the same checks
        }
    }

    @Test fun aSurpriseCheckIsOpenForTenMinutesThenMissed() {
        val day = (0L..6L).map { monday.plusDays(it) }.first { SurpriseChecks.on(it, ScheduleSpec()) != null }
        val c = SurpriseChecks.on(day, ScheduleSpec())!!
        val plan = DayPlanner.build(day)
        val slot = plan.slot(SlotKey.SURPRISE_CHECK)!!
        assertEquals(c.minute + 10, slot.closesMinute)
        val records = app.agency.core.brain.TodayRecords(day = day.toString())
        fun status(min: Int) = RuleEngine.status(plan, records, RuleEngine.instant(day, min, AgencyClock.TAIPEI), AgencyClock.TAIPEI).first { it.key == SlotKey.SURPRISE_CHECK }.status
        assertEquals(SlotStatus.LATER, status(c.minute - 1))
        assertEquals(SlotStatus.OPEN, status(c.minute + 5))
        assertEquals(SlotStatus.MISSED, status(c.minute + 10))
        // Before it starts it is not on the card (a surprise); once it starts the photo counts; after ten minutes it is refused.
        at(DayClock.hhmm(c.minute - 30), day)
        assertTrue(runBlocking { brain().card() }.slots.none { it.key == SlotKey.SURPRISE_CHECK })
        at(DayClock.hhmm(c.minute + 11), day)
        val late = runBlocking { brain().handInPractice(TrainingKind.SURPRISE_CHECK, Media("m1", MediaKind.PHOTO, MediaPurpose.CHECK, "image/jpeg", 1, "x", 0)) }
        assertTrue(company(late).contains("超過十分鐘"))
        at(DayClock.hhmm(c.minute + 3), day)
        val ok = runBlocking { brain().handInPractice(TrainingKind.SURPRISE_CHECK, Media("m2", MediaKind.PHOTO, MediaPurpose.CHECK, "image/jpeg", 1, "x", 0)) }
        assertTrue(company(ok).contains("突擊檢查收到"))
    }

    @Test fun theTimersTheTapsAndTheWordsFillTheirSlots() {
        val tue = monday.plusDays(1)
        at("09:30", tue)
        val b = brain()
        val steps = runBlocking { b.routine(TrainingKind.VOCAL_BASICS) }!!
        assertEquals(600, steps.sumOf { it.seconds ?: 0 })
        assertEquals(listOf("彈唇", "警報滑音", "音階", "咬字"), steps.map { it.name })
        assertEquals(300, runBlocking { b.routine(TrainingKind.MORNING_STRETCH) }!!.sumOf { it.seconds ?: 0 })
        assertTrue(company(runBlocking { b.practiceDone(TrainingKind.VOCAL_BASICS, 600) }).contains("聲樂基本功 10 分鐘（照計時做完）"))
        assertTrue(company(runBlocking { b.practiceDone(TrainingKind.VOCAL_BASICS, 600) }).contains("已經記過了"))
        assertEquals(Route.Command(Router.PRACTICE + "SUNSCREEN", app.agency.core.model.RoleKey.MANAGER), Router.route("防曬擦好了"))
        at("18:10", tue)
        assertTrue(company(runBlocking { b.onText("防曬擦好了") }).contains("記下：擦防曬"))
        assertTrue(company(runBlocking { b.onText("站姿完成") }).contains("靠牆站姿 3 分鐘（打字回報，未證實）"))
        val card = runBlocking { b.card() }
        listOf(SlotKey.VOCAL_BASICS, SlotKey.SUNSCREEN, SlotKey.POSTURE).forEach { k -> assertEquals(SlotStatus.DONE, card.slots.first { it.key == k }.status) }
        // A Tuesday has no dance.
        assertTrue(company(runBlocking { b.onText("舞蹈完成") }).contains("今天沒有排舞蹈練習"))
    }

    @Test fun notOpenYetSaysTheWeek() {
        runBlocking {
            store.rules.putSection(app.agency.core.model.PlanSectionKey.ROLLOUT, PlanSpecs(rollout = RolloutSpec(week = 2)).json(app.agency.core.model.PlanSectionKey.ROLLOUT), "MIGRATION", 0)
        }
        at("10:00", monday.plusDays(1))
        assertTrue(company(runBlocking { brain().onText("站姿完成") }).contains("站姿第 3 週開放，現在是第 2 週"))
        assertNull(runBlocking { brain().routine(TrainingKind.POSTURE) })
    }

    @Test fun threeLowEveningsInARowAndTheManagerAsksFirst() {
        fun evening(day: LocalDate, level: Int): String {
            at("23:00", day)
            runBlocking { brain().onText("簽退") }
            return company(runBlocking { brain().mood(level) })
        }
        assertFalse(evening(monday, 1).contains("第三天"))
        assertFalse(evening(monday.plusDays(1), 1).contains("第三天"))
        assertTrue(evening(monday.plusDays(2), 1).contains("第三天心情低落"))
        // Once a day; a good day breaks the streak.
        at("23:30", monday.plusDays(2))
        assertTrue(runBlocking { brain().mood(1) }.isEmpty())
        evening(monday.plusDays(3), 3)
        assertFalse(evening(monday.plusDays(4), 1).contains("第三天"))
    }

    @Test fun monthlyScoresComparisonInterviewAndPersonalTask() {
        assertEquals(5, MonthlyEval.attitude(96, 0))
        assertEquals(4, MonthlyEval.attitude(96, 3))
        assertEquals(1, MonthlyEval.attitude(40, 5))
        assertEquals(3, MonthlyEval.vocal(70))
        fun month(m: String, v: Int, d: Int) = MonthlyResult(m, scores = listOf(EvalScore(EvalItem.VOCAL, v, ScoreBy.CODE), EvalScore(EvalItem.DANCE, d, ScoreBy.SELF)))
        assertFalse(MonthlyEval.interviewDue(listOf(month("2027-01", 2, 2), month("2027-02", 3, 2), month("2027-03", 3, 2))))
        assertTrue(MonthlyEval.interviewDue(listOf(month("2027-01", 3, 2), month("2027-02", 3, 2), month("2027-03", 2, 2))))
        assertEquals(EvalItem.DANCE, MonthlyEval.weakest(month("x", 3, 2).scores))
        // The last Sunday: the hand-in sets the personal task (from tomorrow, 10 minutes) and computes 態度 by code.
        val last = LocalDate.parse("2026-12-27")
        at("15:40", last)
        val b = brain()
        val media = listOf("s", "d", "r").map { Media(it, MediaKind.AUDIO, MediaPurpose.EVAL, "audio/mp4", 1, it, 0) }
        val said = company(runBlocking {
            b.monthlyEval(MonthlyResult("2026-12", song = "s", dance = "d", rotating = "r", weightKg = 86.0), media, mapOf(EvalItem.VOCAL to 3, EvalItem.DANCE to 1, EvalItem.VISUAL to 4, EvalItem.STAR to 4), null, emptyList())
        })
        assertTrue(said, said.contains("態度") && said.contains("程式算") && said.contains("舞蹈 1（自評）"))
        assertTrue(said.contains("這個月的個人任務：舞蹈"))
        at("23:00", last.plusDays(1))
        assertNotNull(runBlocking { brain().card() }.slots.firstOrNull { it.key == SlotKey.PERSONAL_TASK })
        assertEquals(1, runBlocking { b.months() }.size)
    }

    @Test fun theLookedAtScoresKeepOnlyAGuardedBasis() {
        val ok = EvalReads.parse("""{"dance":{"score":3,"basis":"手臂的線條有出來，拍子穩。"},"visual":{"score":4,"basis":"表情自然。"},"star":{"score":9,"basis":"很有氣勢"}}""")
        assertEquals(listOf(EvalItem.DANCE, EvalItem.VISUAL), ok.map { it.item }) // 9 is out of range
        val numbered = EvalReads.parse("""{"dance":{"score":3,"basis":"看起來有 86 公斤。"},"visual":{"score":4,"basis":"表情自然。"},"star":{"score":3,"basis":"眼神有焦點。"}}""")
        assertTrue(numbered.none { it.item == EvalItem.DANCE })
    }

    @Test fun aThemeEachWeekInTurn() {
        val names = (0L until 6L).map { Themes.of(monday.plusWeeks(it)).name }.toSet()
        assertEquals(6, names.size)
        assertEquals(Themes.of(monday), Themes.of(monday.plusDays(6)))
    }
}
