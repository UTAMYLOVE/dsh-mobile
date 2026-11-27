package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.engine.Links
import app.agency.core.engine.MoveIcon
import app.agency.core.engine.Progression
import app.agency.core.engine.Routine
import app.agency.core.engine.RoutineRunner
import app.agency.core.engine.RunnerEvent
import app.agency.core.engine.Signal
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.EffectKind
import app.agency.core.model.Message
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.TrainingKind
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.TrainingSpec
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

/** T18: the guided routines, the reports, the photocard and 撒嬌 of L11, and the progression proposals of L15. Invented data. */
class TrainingT18Test {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-11-23") // a Monday (class day)

    init {
        runBlocking { store.profile.put(Profile(joinDate = "2026-11-21", onboardingDone = true)) }
    }

    private fun brain(llm: ScriptedLlm = ScriptedLlm()) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }

    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }

    private fun t(hhmm: String) = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant().toEpochMilli()

    private fun lines(m: List<Message>) = m.filter { it.who == Who.COMPANY }.map { it.text }

    private fun say(hhmm: String, text: String, llm: ScriptedLlm = ScriptedLlm()): List<String> {
        at(hhmm)
        return lines(runBlocking { brain(llm).onText(text) })
    }

    // Every move of every step: a step's report reads only its own moves.
    private val full = mapOf("push_up" to 15, "squat" to 20, "plank" to 60, "row" to 12, "bridge" to 15)
    private fun rounds(hhmm: String, extra: Int?, last: Map<String, Int> = full, rounds: Int? = 3): List<String> {
        at(hhmm)
        return lines(runBlocking { brain().strengthDone(StrengthReport(rounds, last, extra, seconds = 900)) })
    }

    private fun protein(hhmm: String, g: Double) = runBlocking { at(hhmm); brain().signal(Signal.MealLogged("meal-$day-$hhmm", t(hhmm), SlotKey.DINNER, 700, g)) }

    private fun training() = runBlocking { store.training.onDay(day.toString()) }

    @Test fun theCoreIsAhyeonsSevenMovesAMinuteEachHalfSitUpsOnly() {
        assertEquals(listOf("半船式", "半仰臥起坐", "抱膝", "單車捲腹", "進階抬腿", "側棒式（左）", "側棒式（右）"), Routine.CORE.map { it.name })
        assertTrue(Routine.CORE.all { it.seconds == 60 && it.reps == null })
        assertEquals(420, Routine.timedSeconds(Routine.CORE))
        assertTrue(Routine.CORE.none { it.name == "仰臥起坐" })
        assertTrue(Routine.CORE[1].how.contains("不要整個坐起來"))
        assertTrue(Routine.CORE.all { it.how.none { c -> c in 'a'..'z' || c in 'A'..'Z' } })
    }

    @Test fun theStrengthRoundsFollowTheStepWithTwentySecondsBetweenMoves() {
        val base = Routine.strength(0, 3)
        assertEquals(3 * 3 + 8, base.size) // three moves a round, a rest between two moves, none after the last
        assertEquals(listOf("伏地挺身", "休息", "慢速深蹲", "休息", "棒式", "休息"), base.take(6).map { it.name })
        // Owner 2026-11-26: 伏地挺身 15, 慢深蹲 20 (3 seconds down, a second at the bottom), 棒式 60 秒.
        assertEquals(listOf(15, null, 20, null, null), base.take(5).map { it.reps })
        assertEquals(60, base[4].seconds)
        assertTrue(base[2].how.contains("三秒慢慢蹲下、最低點停一秒"))
        assertTrue(base.filter { it.rest }.all { it.seconds == 20 })
        assertEquals("棒式", base.last().name)
        assertEquals(listOf("伏地挺身", "慢速深蹲", "棒式", "背包划船"), Routine.moves(1).map { it.name })
        assertEquals(listOf("伏地挺身", "慢速深蹲", "棒式", "背包划船", "臀橋"), Routine.moves(2).map { it.name })
        assertEquals(4, Progression.rounds(TrainingSpec(progressionStep = 3)))
        assertEquals("3 輪：伏地挺身 15 下、慢速深蹲 20 下、棒式 60 秒", Progression.note(TrainingSpec()))
        assertEquals("4 輪：伏地挺身 15 下、慢速深蹲 20 下、棒式 60 秒、背包划船 12 下、臀橋 15 下", Progression.note(TrainingSpec(progressionStep = 3)))
        val stretch = Routine.stretch(10)
        assertEquals(listOf("髖部伸展", "腿後伸展", "胸口伸展", "背部伸展"), stretch.map { it.name })
        assertEquals(600, Routine.timedSeconds(stretch))
    }

    @Test fun theRunnerCountsDownTimedStepsWaitsOnRepsAndPauses() {
        val steps = Routine.strength(0, 1)
        val r = RoutineRunner(steps)
        assertEquals(listOf(RunnerEvent.StepStarted(0)), r.start())
        // 伏地挺身 15 下 waits for his tap, however long it takes.
        assertTrue(r.tick(30_000).isEmpty())
        assertEquals("伏地挺身", r.current.name)
        assertEquals("休息", r.upcoming!!.name)
        assertEquals(listOf(RunnerEvent.StepStarted(1)), r.next())
        // The rest counts down 20 seconds and moves on by itself.
        r.tick(19_500)
        assertEquals(1, r.remainingSeconds)
        assertEquals(listOf(RunnerEvent.StepStarted(2)), r.tick(500))
        assertEquals(MoveIcon.SQUAT, r.current.icon)
        r.next()
        r.tick(20_000)
        // The plank: paused time does not count.
        assertEquals("棒式", r.current.name)
        r.tick(10_000)
        r.pause()
        assertTrue(r.tick(60_000).isEmpty())
        assertEquals(50, r.remainingSeconds)
        r.resume()
        assertEquals(listOf(RunnerEvent.Finished), r.tick(50_000))
        assertTrue(r.finished)
        assertEquals(30_000L + 20_000 + 20_000 + 10_000 + 50_000, r.elapsedMs)
    }

    @Test fun aTimedCoreRecordsOnceWithTheBodyTeachersLineThePhotocardAndTheOneSweetLine() {
        // On a delivery day the block waits for 收工 (owner 2026-11-26).
        assertEquals(listOf(TrainingDesk.BEFORE_RIDE), say("17:08", "核心完成"))
        assertEquals(listOf("出門了。騎車小心，提醒只響一聲。21:00 記得吃雞胸。"), say("18:00", "出門跑單"))
        assertEquals(listOf(TrainingDesk.BEFORE_RIDE), lines(runBlocking { at("20:00"); brain().coreDone(seconds = 425) }))
        assertEquals(listOf("收工了。放下安全帽，接著訓練：核心、肌力、伸展。"), say("23:00", "收工"))
        at("23:20")
        val out = runBlocking { brain().coreDone(seconds = 425) }.filter { it.who == Who.COMPANY }
        assertEquals("記下：核心七分鐘（照計時做完）。", out[0].text)
        assertEquals(RoleKey.BODY, out[0].role)
        assertEquals(RoleKey.MANAGER, out[1].role)
        assertEquals(Links.L11Completion.sweetLine(TrainingKind.CORE7, null), out[1].text)
        assertTrue(training().single().verified)
        assertEquals(1, runBlocking { brain().photocards() }.size)
        assertEquals(1, runBlocking { brain().nerve.state(day) }.sweetToday)
        // The second big completion of the day earns neither a second 撒嬌 nor a second photocard.
        val second = rounds("23:50", extra = 1)
        assertTrue(second.single(), second.single().startsWith("記下：肌力 3 輪（最後一輪：伏地挺身 15 下、慢速深蹲 20 下、棒式 60 秒；還能多做 1 下；照計時做完）。"))
        assertEquals(1, runBlocking { brain().photocards() }.size)
        assertEquals(1, runBlocking { brain().card() }.photocards)
        // Again: already recorded.
        at("23:55")
        assertEquals(listOf("今天的核心已經記過了。"), lines(runBlocking { brain().coreDone(420) }))
        // A timer skipped through is no proof (the next day, after the latest departure without a run).
        day = day.plusDays(1)
        at("19:30")
        assertEquals("記下：核心七分鐘（計時只跑了 1 分鐘，未證實）。", lines(runBlocking { brain().coreDone(seconds = 70) }).first())
    }

    @Test fun onADayWithABreachTheCardIsAddedButThereIsNoSweetLine() {
        runBlocking { at("12:30"); brain().signal(Signal.MealLogged("meal-1", t("12:30"), SlotKey.LUNCH, 600, 20.0, breaches = listOf("PROCESSED"))) }
        at("23:08")
        val out = lines(runBlocking { brain().coreDone(420) })
        assertEquals(Links.L11Completion.PLAIN, out[1])
        assertEquals(0, runBlocking { brain().nerve.state(day) }.sweetToday)
        assertEquals(1, runBlocking { brain().photocards() }.size)
    }

    @Test fun theRoundsCountOnlyWhenAllAreDoneAndARestDayStillTrains() {
        assertEquals(listOf("今天照表：肌力要做滿 3 輪才算完成。做完再回報。"), rounds("23:40", extra = null, last = emptyMap(), rounds = 2))
        assertTrue(training().isEmpty())
        // Short reps are recorded as they were, with the plan's number.
        val short = rounds("23:50", extra = 0, last = mapOf("push_up" to 12, "squat" to 20, "plank" to 60))
        assertTrue(short.first().lines().contains("最後一輪伏地挺身 12 下，表定 15 下，下次做滿。"))
        assertTrue(short.first().lines().contains("接著伸展 10 分鐘。"))
        // A rest day is only a day without the run (owner 2026-11-26): the core, the rounds and the stretch are owed as always.
        day = day.plusDays(1)
        assertTrue(say("09:00", "今天休息").isNotEmpty())
        val rest = runBlocking { brain().nerve.plan(day) }
        assertTrue(rest.rest)
        assertNull(rest.slot(SlotKey.RIDE_OUT))
        // A Tuesday: a cardio day, so the block is the core, the rounds, the cardio, then the stretch (any time from 17:00).
        val block = listOf(SlotKey.CORE, SlotKey.STRENGTH, SlotKey.CARDIO, SlotKey.STRETCH)
        assertEquals(listOf("18:30", "18:40", "19:05", "19:20"), block.map { rest.slot(it)!!.time })
        assertTrue(block.all { rest.slot(it)!!.required && !rest.slot(it)!!.afterRide })
        at("18:31")
        assertTrue(lines(runBlocking { brain().coreDone(420) }).first().startsWith("記下：核心七分鐘（照計時做完）。"))
        assertTrue(rounds("18:50", extra = 3).first().endsWith("接著體能 10 分鐘。"))
        at("19:05")
        assertEquals("記下：體能 10 分鐘（照計時做完）。\n接著伸展 10 分鐘。", lines(runBlocking { brain().cardioDone(600) }).first())
        at("19:20")
        assertEquals("記下：伸展 10 分鐘（照計時做完）。\n今天的訓練都做完了。", lines(runBlocking { brain().stretchDone(600) }).first())
        assertEquals(listOf(TrainingKind.CORE7, TrainingKind.STRENGTH, TrainingKind.CARDIO, TrainingKind.STRETCH), training().map { it.kind })
        // A walk is a no-class day's: a class day has none to tap.
        day = LocalDate.parse("2026-11-26") // Thursday
        at("16:30")
        assertEquals(listOf("今天沒有排快走（上課日走路上學就算）。"), lines(runBlocking { brain().walkDone() }))
        day = LocalDate.parse("2026-11-25") // Wednesday
        at("16:50")
        assertEquals(listOf("記下：快走 30 分鐘。"), lines(runBlocking { brain().walkDone() }))
    }

    @Test fun typedReportsStillRecordAsUnprovenAndNegationIsNoReport() {
        fun intake(kind: String, rounds: String = "null") = ScriptedLlm(ScriptedLlm.ok("""{"kind":"training","training":{"kind":"$kind","rounds":$rounds,"last_round_extra":null}}"""))
        assertEquals(listOf("做完再回報。"), say("16:00", "核心還沒做", intake("core7")))
        assertEquals(listOf("今天照表：肌力要做滿 3 輪才算完成。做完再回報。"), say("23:00", "肌力今天做了兩輪", intake("strength")))
        assertTrue(training().isEmpty())
        val typed = say("23:30", "肌力三輪做完了", intake("strength"))
        assertEquals("記下：肌力 3 輪（打字回報，沒有次數，未證實）。\n接著伸展 10 分鐘。", typed.first())
        val log = training().single()
        assertFalse(log.verified)
        assertEquals(3, log.rounds)
        // The command words need no clerk.
        assertEquals("記下：伸展 10 分鐘（打字回報，未證實）。", say("23:40", "伸展完成").first())
        assertEquals(listOf(TrainingDesk.START), say("23:41", "核心開始"))
        assertEquals("記下：核心七分鐘（打字回報，未證實）。", say("23:42", "核心完成").first())
        assertEquals(3, TrainingWords.rounds("肌力三輪做完了"))
        assertEquals(2, TrainingWords.rounds("做了 2 輪"))
        assertEquals(3, TrainingWords.extra("最後一輪還能多做三下"))
    }

    @Test fun beingIllAfterTheCoreProposesToStopOnlyWhatIsStillOwed() {
        say("18:00", "出門跑單")
        say("22:40", "收工")
        at("22:58")
        runBlocking { brain().coreDone(420) }
        val ill = say("23:00", "我發燒了")
        assertTrue(ill.first(), ill.first().startsWith("先照顧身體。發燒的話，今天的訓練我提一個調整：今天肌力先停，伸展照做十分鐘。"))
        assertEquals(listOf("好，只改今天：今天肌力先停，伸展照做十分鐘。不用明天補做，好好休息。"), say("23:01", "好"))
        val plan = runBlocking { brain().nerve.plan(day) }
        assertNull(plan.slot(SlotKey.STRENGTH))
        assertTrue(plan.slot(SlotKey.STRETCH)!!.required)
        assertEquals(listOf("今天只改這一天：肌力停一天，伸展照做就好。不用補做。"), rounds("23:10", extra = 2))
    }

    /**
     * [days] days of rounds from [start] (every move in full, [extra] left on the last round), protein [proteinG] each day, 「輕鬆」
     * said on the days of [easyOn] (indexes); [rest] declared a rest day (it trains too). Returns what each evening said.
     */
    private fun easyDays(start: LocalDate, days: Int, extra: Int = 2, proteinG: Double = 140.0, easyOn: Set<Int> = setOf(2, 9), rest: Int? = null): List<List<String>> {
        val out = mutableListOf<List<String>>()
        for (i in 0 until days) {
            day = start.plusDays(i.toLong())
            if (i == rest) say("09:00", "今天休息")
            protein("17:30", proteinG)
            at(if (i == rest) "18:50" else "23:50")
            out += lines(runBlocking { brain().strengthDone(StrengthReport(3, full, extra, seconds = 900, easySaid = i in easyOn)) })
        }
        return out
    }

    private fun steps() = runBlocking { PlanStore(store).specs() }.training.let { Triple(it.progressionStep, it.coreStep, it.cardioStep) }

    @Test fun twoTrulyStableWeeksStepUpByThemselvesOneItemAtATime() {
        val start = LocalDate.parse("2026-11-23")
        val evenings = easyDays(start, 14, rest = 2)
        // Only on the fourteenth day (two whole weeks), with no question: the strength ladder first.
        assertEquals(listOf(13), evenings.indices.filter { i -> evenings[i].any { it.contains("自動加一級") } })
        assertTrue(evenings[13].any { it.contains("所以自動加一級（一次一項，加完兩週內不再加）：肌力：背包划船 12 下。") })
        assertEquals(Triple(1, 0, 0), steps())
        assertEquals(Links.L15Progression.id, runBlocking { store.rules.section(PlanSectionKey.TRAINING) }!!.changedBy)
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.none { it.kind == ProposalKind.PROGRESSION })
        assertTrue(runBlocking { brain().nerve.plan(day.plusDays(1)) }.slot(SlotKey.STRENGTH)!!.note!!.contains("背包划船 12 下"))
        // Two weeks of nothing more, however easy; then the next ladder in turn: the core.
        val next = easyDays(day.plusDays(1), 14, easyOn = setOf(1, 8))
        assertEquals(listOf(13), next.indices.filter { i -> next[i].any { it.contains("自動加一級") } })
        assertTrue(next[13].any { it.contains("核心：每個動作 75 秒（約 9 分鐘）。") })
        assertEquals(Triple(1, 1, 0), steps())
        assertEquals(75, runBlocking { brain().routine(TrainingKind.CORE7) }!!.first().seconds)
    }

    @Test fun noStepWithoutRoomOnTheLastRoundEasyTwiceProteinOrHealth() {
        easyDays(LocalDate.parse("2026-11-23"), 14, extra = 1)
        assertEquals(Triple(0, 0, 0), steps())
        easyDays(LocalDate.parse("2027-01-04"), 14, easyOn = setOf(5))
        assertEquals(Triple(0, 0, 0), steps())
        easyDays(LocalDate.parse("2027-03-01"), 14, proteinG = 120.0)
        assertEquals(Triple(0, 0, 0), steps())
        // Ill in the two weeks: no step.
        val start = LocalDate.parse("2027-05-03")
        easyDays(start, 7)
        day = start.plusDays(7)
        say("09:00", "我發燒了")
        easyDays(start.plusDays(8), 6)
        assertEquals(Triple(0, 0, 0), steps())
        assertTrue(Progression.next(Progression.MAX_STEP) == null)
    }

    @Test fun aStepItCannotFinishStaysAndNeverGoesBack() {
        runBlocking {
            store.rules.putSection(PlanSectionKey.TRAINING, app.agency.core.schedule.PlanSpecs.JSON.encodeToString(TrainingSpec.serializer(), TrainingSpec(progressionStep = 3, lastStepDay = "2026-11-22")), "test", 1)
        }
        at("23:00")
        assertEquals(4, runBlocking { brain().nerve.plan(day) }.strengthRounds)
        assertEquals(listOf("今天照表：肌力要做滿 4 輪才算完成。做完再回報。"), rounds("23:40", extra = null, rounds = 3))
        // Two sessions in a row not in full: it stays at this step.
        val short = mapOf("push_up" to 12, "squat" to 20, "plank" to 60, "row" to 12, "bridge" to 15)
        assertTrue(lines(runBlocking { at("23:45"); brain().strengthDone(StrengthReport(4, short, 0, seconds = 900)) }).none { it == Links.L15Progression.HOLD })
        day = day.plusDays(1)
        assertTrue(lines(runBlocking { at("23:45"); brain().strengthDone(StrengthReport(4, short, 0, seconds = 900)) }).contains(Links.L15Progression.HOLD))
        assertEquals(3, steps().first)
        // The ladders: strength 18, 20 push-ups, 75, 90 seconds of plank, the harder versions; core and cardio have theirs.
        assertEquals(18, Routine.moves(4).first().reps)
        assertEquals(90, Routine.moves(7).first { it.key == "plank" }.seconds)
        assertEquals("腳墊高伏地挺身", Routine.moves(8).first().name)
        assertEquals("分腿蹲", Routine.moves(9).first { it.key == "squat" }.name)
        assertEquals(86, Routine.core(2).first().seconds)
        assertEquals("船式", Routine.core(3).first().name)
        assertEquals(55, Routine.cardioWork(2))
        assertEquals(12, Routine.cardioMinutes(3, 10))
        assertEquals("肌力加到四輪（每輪的動作照現在）。", Progression.change(3))
        assertTrue(TrainingWords.saidEasy("肌力三輪做完了，很輕鬆") && !TrainingWords.saidEasy("一點都不輕鬆"))
        assertTrue(TrainingWords.feltEasy("今天肌力好輕鬆") && !TrainingWords.feltEasy("肌力三輪做完了"))
    }
}
