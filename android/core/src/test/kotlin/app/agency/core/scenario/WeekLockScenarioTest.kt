package app.agency.core.scenario

import app.agency.core.brain.Brain
import app.agency.core.engine.Signal
import app.agency.core.lessons.LessonKit
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.LlmClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.metering.MutableClock
import app.agency.core.model.Profile
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.Feature
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.time.AgencyClock
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 週數鎖定情境 (分週上線, owner 2026-11-27): a new install in week 1 lives one invented week — only 理論課 (two lessons a day,
 * quizzes and notes). Not one meal or training slot is planned, nothing of them is chased, a closed command says when it
 * opens, nothing counts as a breach. The next Monday the week is on target (everything due was done, no breach; the
 * real-person task is not open yet), the card for week 2 comes, his 好 opens it: from then on the meals, the run, the core
 * and the rounds are planned, the cardio is not (week 3).
 */
class WeekLockScenarioTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private val monday = LocalDate.parse("2026-12-14")
    private var day = monday
    private val requests = mutableListOf<ChatRequest>()
    private val llm = LlmClient { r ->
        requests += r
        when (r.purpose) {
            Purpose.QUIZ -> LessonKit.quiz(LessonKit.VOCAL_QS)
            else -> error("unexpected ${r.purpose}")
        }
    }
    private var n = 0
    private val search = LessonKit.FakeSearch()
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock), search = search) { "id-%05d".format(++n) }

    init {
        runBlocking {
            store.profile.put(Profile(joinDate = "2026-12-13", onboardingDone = true))
            PlanStore(store).seedIfMissing(0) // a new install: week 1
        }
    }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun say(hhmm: String, text: String): List<String> {
        at(hhmm)
        return runBlocking { brain.onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    private val note = "腹式呼吸要讓肚子鼓起來，肩膀不能抬，吐氣維持十五秒，高音時氣往下沉不要擠喉嚨。"

    @Test fun weekOneIsLessonsOnlyThenItsTargetOpensWeekTwo() {
        at("07:00")
        runBlocking { brain.tick() }
        // No real-person task in week 1.
        assertTrue(runBlocking { store.messages.recent(10) }.none { it.text.startsWith("這週的真人任務") })
        val vocal = LessonKit.VOCAL
        listOf(0 until 5, 5 until 10, 10 until 14).forEach { range ->
            search.hits = range.map { LessonKit.hit("https://www.youtube.com/watch?v=lock-$it", "發聲練習第 ${it + 1} 課", vocal) }
            at("07:10")
            runBlocking { brain.findVideos(app.agency.core.model.LessonCategory.VOCAL) }.single { it.kind == Brain.LESSON_FOUND_KIND }.refs.records
                .forEach { runBlocking { brain.approveLesson(it) } }
        }
        val closed = app.agency.core.schedule.RolloutSpec(week = 1)
        for (i in 0 until 7) {
            day = monday.plusDays(i.toLong())
            val plan = runBlocking { brain.nerve.plan(day) }
            // Only the lessons: no meal, no training, no run, no sign-in.
            assertTrue(plan.slots.map { it.key }.toString(), plan.slots.all { it.key == SlotKey.LESSONS || it.key == SlotKey.LESSON_REVIEW })
            assertTrue(plan.slots.none { it.key.kind == SlotKind.MEAL || it.key.kind == SlotKind.TRAINING })
            // A closed command says when it opens and writes nothing.
            assertEquals(listOf(closed.closedLine(Feature.SIGN)), say("09:00", "簽到"))
            runBlocking {
                listOf("14:10", "14:20").forEach { hhmm ->
                    at(hhmm)
                    val id = brain.lessonBoard().list.first { !it.locked }.id
                    brain.openLesson(id)
                    brain.lessonWatched(id)
                    assertTrue(brain.answerLesson(id, listOf(2, 1, 1)).last().text.contains("考過了"))
                    assertTrue(brain.lessonNote(id, note).first { it.who == Who.COMPANY }.text.startsWith("通過："))
                }
            }
            // All day long nothing of a closed feature is chased.
            listOf("12:30", "17:40", "21:10", "23:59").forEach { hhmm ->
                at(hhmm)
                assertTrue(hhmm, runBlocking { brain.tick() }.posts.none { p -> p.gapKeys.any { it != SlotKey.LESSONS } })
            }
        }
        runBlocking {
            assertTrue(store.meals.between(monday.toString(), monday.plusDays(6).toString()).isEmpty())
            assertTrue(store.training.between(monday.toString(), monday.plusDays(6).toString()).isEmpty())
            assertTrue(store.signs.between(monday.toString(), monday.plusDays(6).toString()).isEmpty())
        }
        // The next Monday: the week's result counts only what was open (the lessons), and it is on target.
        day = monday.plusDays(7)
        at("07:00")
        runBlocking { brain.tick() }
        val week = runBlocking { store.nerve.signal("week:$monday") }!!.let { Signal.decode(it.json) as Signal.WeekEnded }
        assertEquals(7 to 7, week.due to week.done)
        assertEquals(0, week.breaches)
        assertTrue(week.onTarget && !week.realTaskRequired)
        val summary = runBlocking { store.messages.recent(10) }.single { it.text.startsWith("一週結果") }.text
        assertTrue(summary, summary.contains("這週達標。") && !summary.contains("真人任務"))
        val card = runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single { it.kind == ProposalKind.ROLLOUT_NEXT }
        assertTrue(say("07:05", "好").single().startsWith("好，第 2 週開始。新開放：簽到簽退、三餐拍照、喝水、核心、肌力和伸展、出門跑單"))
        assertEquals(ProposalStatus.ACCEPTED, runBlocking { store.proposals.get(card.id) }!!.status)
        // Week 2: the meals, the run, the core and the rounds are planned (a Tuesday: still no cardio, week 3).
        val tuesday = runBlocking { brain.nerve.plan(day.plusDays(1)) }.slots.map { it.key }.toSet()
        assertTrue(tuesday.containsAll(listOf(SlotKey.WAKE, SlotKey.LUNCH, SlotKey.DINNER, SlotKey.RIDE_OUT, SlotKey.RIDE_MEAL, SlotKey.CORE, SlotKey.STRENGTH, SlotKey.STRETCH, SlotKey.SIGN_OUT)))
        assertTrue(SlotKey.CARDIO !in tuesday)
        assertEquals(2, runBlocking { brain.rollout() }.week)
        // The model was asked only for the quizzes.
        assertEquals(setOf(Purpose.QUIZ), requests.map { it.purpose }.toSet())
    }
}
