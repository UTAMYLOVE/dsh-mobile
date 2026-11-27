package app.agency.core.scenario

import app.agency.core.brain.Brain
import app.agency.core.brain.handlers.ReviewDesk
import app.agency.core.brain.handlers.StrengthReport
import app.agency.core.engine.Links
import app.agency.core.engine.RealTasks
import app.agency.core.engine.Signal
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.LlmClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.metering.MutableClock
import app.agency.core.model.EffectKind
import app.agency.core.model.Profile
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.TrainingKind
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayType
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import app.agency.core.lessons.LessonKit
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一週情境 (agency-links L15, L19, L20): one invented Mon–Sun week played through the brain, every day from the sign-in and
 * its score to the bedtime review: the meals (protein 143 g a day), the core by the timer, the strength rounds (easy every
 * time: 2 more on the last round) and the stretch, the rest day on Wednesday (only no run: the walk, the core, the rounds and the
 * stretch still, in the no-run block), the real-person task on Thursday, tomorrow's fix said yes to every night. Sunday's rounds earn the one progression
 * proposal (背包划船, his yes changes the training plan); the next Monday's start brings the week's evaluation: on target, the
 * first week on target, the photocard wall, and the next real-person task.
 * Batch 5b (T19c): 理論課 every day (fourteen videos found by search and said 好 to on Monday morning; two lessons a day,
 * each quiz passed and its note written), and Monday's two 複習考 on Wednesday (day +2), recorded only.
 */
class WholeWeekScenarioTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private val monday = LocalDate.parse("2026-12-14")
    private var day = monday

    /** The data clerk's answers, one per meal report, in order; her bedtime sentence; the judge. */
    private val meals = ArrayDeque<String>()
    private val requests = mutableListOf<ChatRequest>()
    private val llm = LlmClient { r ->
        requests += r
        when (r.purpose) {
            Purpose.INTAKE -> ScriptedLlm.ok(meals.removeFirst())
            Purpose.NUDGE -> ScriptedLlm.ok("""{"text":"今天做得很好，現在去睡。"}""")
            Purpose.JUDGE -> ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}""")
            // A lesson's quiz from its 教材; a 複習考 (it lists the questions asked before) gets three new ones.
            Purpose.QUIZ -> if ((r.turns.single() as app.agency.core.llm.Turn.User).text.contains("【之前考過的題目")) LessonKit.quiz(LessonKit.VOCAL_REVIEW)
            else LessonKit.quiz(LessonKit.VOCAL_QS)
            else -> error("unexpected ${r.purpose}")
        }
    }
    private var n = 0
    private val search = LessonKit.FakeSearch()
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock), search = search) { "id-%05d".format(++n) }

    init {
        runBlocking { store.profile.put(Profile(joinDate = "2026-12-01", onboardingDone = true)) }
    }

    private fun item(name: String, high: Int, protein: Int) =
        """{"name":"$name","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":${high - 50},"kcal_high":$high,"protein_low":$protein,"protein_high":$protein}"""

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun say(hhmm: String, text: String): List<String> {
        at(hhmm)
        return runBlocking { brain.onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    private fun meal(hhmm: String, text: String, vararg items: String): List<String> {
        meals += """{"kind":"meal","items":[${items.joinToString(",")}]}"""
        return say(hhmm, text)
    }

    private val note = "腹式呼吸要讓肚子鼓起來，肩膀不能抬，吐氣維持十五秒，高音時氣往下沉不要擠喉嚨。"

    /** The day's two lessons after the first meal (in class on a class day): watch, three questions, the note. */
    private fun lessons() = runBlocking {
        listOf("14:10", "14:20").forEach { hhmm ->
            at(hhmm)
            val id = brain.lessonBoard().list.first { !it.locked }.id
            brain.openLesson(id)
            brain.lessonWatched(id)
            assertTrue(brain.answerLesson(id, listOf(2, 1, 1)).last().text.contains("答對 3 題，考過了。"))
            assertTrue(brain.lessonNote(id, note).first { it.who == Who.COMPANY }.text.startsWith("通過："))
        }
    }

    private val full = mapOf("push_up" to 15, "squat" to 20, "plank" to 60, "row" to 12, "bridge" to 15)

    /** One day as he lives it; [rest] declares 「今天休息」. Returns what the evening's rounds said. */
    private fun live(rest: Boolean = false): List<String> {
        val plan0 = runBlocking { brain.nerve.plan(day) }
        val cls = plan0.type == DayType.CLASS
        say(if (cls) "08:30" else "11:30", "簽到")
        assertTrue(say(if (cls) "08:31" else "11:31", "4").single().startsWith("記下：今天 4 分"))
        if (rest) say("11:40", "今天休息")
        meal(if (cls) "12:15" else "13:05", if (cls) "午餐吃了南加州烤雞腿" else "第一餐吃了南加州烤雞腿", item("南加州烤雞腿", 714, 48))
        lessons()
        if (!cls) runBlocking { at("16:50"); brain.walkDone() }
        meal("17:40", "晚餐吃了雞胸沙拉", item("雞胸沙拉", 500, 80))
        val cardio = plan0.slot(SlotKey.CARDIO) != null
        var rounds = emptyList<String>()
        if (!rest) {
            say("18:05", "出門跑單")
            meal("21:05", "跑單路上吃了即食雞胸和統一高纖豆漿", item("即食雞胸", 130, 20), item("統一高纖豆漿", 200, 15))
            say("23:30", "收工")
            // The block after 收工 (owner 2026-11-26): core, rounds, the cardio on its days, the stretch.
            runBlocking { at("23:40"); brain.coreDone(seconds = 425) }
            rounds = runBlocking { at("23:52"); brain.strengthDone(StrengthReport(null, full, extra = 2, seconds = 1150)) }.filter { it.who == Who.COMPANY }.map { it.text }
            if (rounds.any { it.contains("可以加量了") }) assertTrue(say("23:55", "好").single().startsWith("好，下一次肌力開始："))
            if (cardio) runBlocking { at("00:06"); brain.cardioDone(600) }
            runBlocking { at("00:18"); brain.stretchDone(600) }
        } else {
            // The rest day trains as always, in the no-run block after dinner; the evening after it is free.
            runBlocking { at("18:31"); brain.coreDone(seconds = 425) }
            rounds = runBlocking { at("18:50"); brain.strengthDone(StrengthReport(null, full, extra = 2, seconds = 1150)) }.filter { it.who == Who.COMPANY }.map { it.text }
            runBlocking { at("19:10"); brain.stretchDone(600) }
            meal("21:05", "吃了即食雞胸和統一高纖豆漿", item("即食雞胸", 130, 20), item("統一高纖豆漿", 200, 15))
        }
        assertEquals(listOf("簽退了。", ReviewDesk.ASK_GOOD), say("00:30", "簽退"))
        assertEquals(listOf(ReviewDesk.ASK_FIX), say("00:31", "訓練都照計時做完"))
        val review = say("00:32", "明天水早點喝完")
        assertTrue(review.first(), review.first().startsWith("今天的檢討卡"))
        assertTrue(review.first().contains("沒做到：沒有，全部做到"))
        assertEquals("今天做得很好，現在去睡。", review.last())
        assertEquals(listOf("好，明天起床簽到的時候我提醒你：明天水早點喝完"), say("00:33", "好"))
        return rounds
    }

    @Test fun aWeekWithItsRestDayEndsOnTargetWithOneProgressionStep() {
        // Monday 07:00: the reminders start, with the week's real-person task.
        at("07:00")
        runBlocking { brain.tick() }
        assertTrue(runBlocking { store.messages.recent(5) }.any { it.text == RealTasks.line(monday) })
        // 07:10–07:20 理論課: three searches (five, five, four real results), all fourteen said 好 to.
        val vocal = LessonKit.VOCAL
        listOf(0 until 5, 5 until 10, 10 until 14).forEach { range ->
            search.hits = range.map { LessonKit.hit("https://www.youtube.com/watch?v=week-$it", "發聲練習第 ${it + 1} 課", vocal) }
            at("07:10")
            runBlocking { brain.findVideos(app.agency.core.model.LessonCategory.VOCAL) }.single { it.kind == Brain.LESSON_FOUND_KIND }.refs.records
                .forEach { runBlocking { brain.approveLesson(it) } }
        }
        assertEquals(14, runBlocking { brain.lessonBoard() }.list.size)
        val evenings = mutableListOf<List<String>>()
        for (i in 0 until 7) {
            day = monday.plusDays(i.toLong())
            evenings += live(rest = i == 2)
            // Wednesday: Monday's two lessons have their 複習考 (day +2): three new questions each, recorded only.
            if (i == 2) runBlocking {
                at("14:40")
                val due = brain.lessonBoard().reviews
                assertEquals(2, due.size)
                due.forEach { r ->
                    brain.startReview(r.id)
                    assertTrue(brain.answerReview(r.id, listOf(1, 1, 1)).single { it.who == Who.COMPANY }.text.endsWith("複習考答對 3 題，記下了。"))
                }
            }
            // Thursday: the real-person task, reported before bed.
            if (i == 3) assertEquals(listOf("記下：這週的真人任務做到了。跟真的人相處，就是這樣。"), say("00:40", "真人任務完成"))
            // Each day's meals: 1574 kcal, 166.6 g protein (the 即食雞胸 by its label, 23.6 g).
            assertEquals(166.6, runBlocking { brain.nerve.state(day) }.proteinG, 0.01)
        }
        // The progression proposal came once, on Sunday (a week of easy rounds and the day before at 140 g), for the next step
        // only; his yes made the training plan's next version.
        assertEquals(listOf(6), evenings.indices.filter { i -> evenings[i].any { it.contains("可以加量了") } })
        assertTrue(evenings[6].any { it.contains("我提一個：肌力每輪加一個動作：背包划船 12 下。") })
        val progression = runBlocking { store.proposals.withStatus(ProposalStatus.ACCEPTED) }.filter { it.kind == ProposalKind.PROGRESSION }
        assertEquals(1, progression.size)
        assertEquals(1, runBlocking { PlanStore(store).specs() }.training.progressionStep)
        // The rest day had everything but the run; every day had all three, each by the timer.
        val wed = monday.plusDays(2).toString()
        assertEquals(listOf(TrainingKind.WALK, TrainingKind.CORE7, TrainingKind.STRENGTH, TrainingKind.STRETCH), runBlocking { store.training.onDay(wed) }.map { it.kind })
        assertEquals(7, runBlocking { store.training.between(monday.toString(), monday.plusDays(6).toString()) }.count { it.kind == TrainingKind.STRENGTH })
        // The photocard wall: one polaroid for each day with the core (the first big completion), the rest day too.
        assertEquals(7, runBlocking { brain.photocards() }.size)

        // The next Monday's first alarm: the week's evaluation (L20), once.
        day = monday.plusDays(7)
        at("07:00")
        runBlocking { brain.tick() }
        val lines = runBlocking { store.messages.recent(10) }.filter { it.who == Who.COMPANY }.map { it.text }
        val summary = lines.single { it.startsWith("一週結果") }.lines()
        assertEquals("一週結果（12/14–12/20）", summary[0])
        // 理論課 is one of each day's items (seven more than before T19c).
        // The rest day owes its core, rounds and stretch too (three more than before 2026-11-26).
        // 體能 three days a week (Tuesday, Thursday, Saturday) since 2026-11-26.
        assertEquals("完成率 100%（該做 75 項，做到 75 項）", summary[1])
        assertEquals("訓練 27/27", summary[2])
        assertEquals("違規 0 次", summary[3])
        assertEquals("照片牆加了 7 張", summary[4])
        assertEquals("真人任務：做到了", summary[5])
        assertEquals("這週達標。等級 C，連續達標 1 週；連續達標才累積，升級看月末評鑑。", summary[6])
        assertTrue(lines.contains(RealTasks.line(day)))
        val week = runBlocking { store.nerve.signal("week:$monday") }!!.let { Signal.decode(it.json) as Signal.WeekEnded }
        assertTrue(week.onTarget && week.realTask)
        assertEquals(1, runBlocking { store.nerve.effects(day.toString()) }.count { it.link == Links.L20Week.id && it.kind == EffectKind.RECORD })
        at("07:30")
        runBlocking { brain.tick() }
        assertEquals(1, runBlocking { store.messages.recent(30) }.count { it.text.startsWith("一週結果") })
        // The new step is in the plan: the rounds now have the backpack row.
        assertTrue(runBlocking { brain.nerve.plan(day) }.slot(SlotKey.STRENGTH)!!.note!!.contains("背包划船 12 下"))
        // 理論課 of the week: fourteen passed, two a day, every score recorded (L24); Monday's two reviewed on Wednesday.
        val all = runBlocking { store.lessons.all() }
        assertEquals((0..6).associate { monday.plusDays(it.toLong()).toString() to 2 }, all.groupingBy { it.passedDay!! }.eachCount())
        assertEquals(listOf(3, 3), all.filter { it.reviewScore != null }.map { it.reviewScore })
        assertTrue(all.filter { it.reviewScore != null }.all { it.passedDay == monday.toString() })
        assertEquals(2, runBlocking { store.nerve.effects(monday.plusDays(2).toString()) }.count { it.link == Links.L24Lessons.id && it.because == Links.L24Lessons.REVIEW })
        // The model was asked only to sort his meals (21), for her bedtime sentence (7) and for the quizzes (14 lessons, 2 reviews).
        assertEquals(21, requests.count { it.purpose == Purpose.INTAKE })
        assertEquals(7, requests.count { it.purpose == Purpose.NUDGE })
        assertEquals(16, requests.count { it.purpose == Purpose.QUIZ })
        assertEquals(44, requests.size)
    }
}
