package app.agency.core.engine

import app.agency.core.metering.MutableClock
import app.agency.core.model.CompanyRules
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.NerveEffect
import app.agency.core.model.ProposalStatus
import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayFlag
import app.agency.core.schedule.Reduction
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 連動中樞 (agency-links): the hub's rules (de-dup, depth, caps, bounds, cancel, proposals) and every link of batch 2. */
class NerveTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock(Instant.parse("2026-12-07T00:00:00Z")) // Monday 08:00 Taipei
    private var n = 0
    private fun id() = "x-${++n}"
    private fun nerve(links: List<Link> = Links.all()) = Nerve(store, AgencyClock.of(clock), ::id, links)
    private val monday = LocalDate.parse("2026-12-07")

    /** Taipei wall time on [day] (hours past 24 are the night after). */
    private fun t(hhmm: String, day: LocalDate = monday): Long = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant().toEpochMilli()
    private fun at(hhmm: String, day: LocalDate = monday) { clock.now = Instant.ofEpochMilli(t(hhmm, day)) }
    private fun emit(nv: Nerve, s: Signal) = runBlocking { nv.emit(s) }
    private fun effects(day: LocalDate = monday) = runBlocking { store.nerve.effects(day.toString()) }
    private fun of(link: String, day: LocalDate = monday) = effects(day).filter { it.link == link }
    private fun effect(e: NerveEffect) = Nerve.decodeEffect(e.json)!!

    // ---- the hub ----

    @Test fun aSignalIsHandledOnce() {
        val nv = nerve()
        at("10:00")
        val s = Signal.WokeUp("in-1", t("10:00"))
        assertEquals(1, emit(nv, s).effects.count { it.link == "L1" })
        assertTrue(emit(nv, s).effects.isEmpty())
        assertEquals(1, of("L1").size)
    }

    private object Echo : Link {
        override val id = "T-echo"
        override val zh = "測試：一直連鎖"
        override val dailyCap = 100
        override fun fire(signal: Signal, state: DayState, ctx: LinkContext) =
            listOf(Effect.Record("echo"), Effect.Chain(Signal.FloorCheck(signal.id + "+", signal.at)))
    }

    @Test fun chainsStopAtThreeLevels() {
        val nv = nerve(listOf(Echo))
        emit(nv, Signal.FloorCheck("f", t("12:00")))
        val signals = runBlocking { store.nerve.signals(monday.toString(), monday.toString()) }
        assertEquals(listOf("f" to 0, "f+" to 1, "f++" to 2), signals.map { it.id to it.depth })
        assertEquals("f+", signals.last().causeId)
    }

    private class Counter(override val dailyCap: Int) : Link {
        override val id = "T-cap"
        override val zh = "測試：每天上限"
        override fun fire(signal: Signal, state: DayState, ctx: LinkContext) = listOf(Effect.Record("hit"), Effect.Record("hit-2"))
    }

    @Test fun eachLinkAnswersAtMostItsDailyCapOfSignals() {
        val nv = nerve(listOf(Counter(2)))
        repeat(4) { emit(nv, Signal.FloorCheck("c$it", t("12:00"))) }
        assertEquals(listOf("c0", "c0", "c1", "c1"), of("T-cap").map { it.signalId })
        // A new day starts a new count.
        emit(nv, Signal.FloorCheck("next", t("12:00", monday.plusDays(1))))
        assertEquals(2, of("T-cap", monday.plusDays(1)).size)
    }

    private class Mover(val change: DayAdjust) : Link {
        override val id = "T-move"
        override val zh = "測試：亂改"
        override val dailyCap = 10
        override fun fire(signal: Signal, state: DayState, ctx: LinkContext) = listOf(Effect.AdjustToday(change, "測試"))
    }

    @Test fun aTodayChangeOutsideTheBoundsIsDropped() {
        for (bad in listOf(
            DayAdjust.Move(SlotKey.SIGN_OUT, DayClock.minute("04:00")), // sleep time never moves
            DayAdjust.Move(SlotKey.RIDE_MEAL, DayClock.minute("23:00")), // the on-run chicken breast stays at 21:00
            DayAdjust.Move(SlotKey.WAKE, DayClock.minute("10:00")),
            DayAdjust.Move(SlotKey.LUNCH, DayClock.minute("15:00")), // later than 3 h before dinner
            DayAdjust.Move(SlotKey.LUNCH, DayClock.minute("11:00")), // earlier than planned
            DayAdjust.Move(SlotKey.STRENGTH, DayClock.minute("03:00")),
        )) {
            val nv = nerve(listOf(Mover(bad)))
            emit(nv, Signal.FloorCheck("m-$bad", t("12:00")))
        }
        assertTrue(of("T-move").isEmpty())
        val ok = nerve(listOf(Mover(DayAdjust.Move(SlotKey.LUNCH, DayClock.minute("14:15")))))
        emit(ok, Signal.FloorCheck("ok", t("12:00")))
        assertEquals(1, of("T-move").size)
    }

    @Test fun noEffectCanCarryTheCompanyRules() {
        val kinds = Effect::class.java.permittedSubclasses
        assertEquals(6, kinds.size)
        kinds.forEach { k -> k.declaredFields.forEach { f -> assertFalse("${k.simpleName}.${f.name}", f.type == CompanyRules::class.java) } }
    }

    @Test fun everyLinkOfTheTableIsRegistered() {
        val ids = Links.all().map { it.id }
        assertEquals((1..23).map { "L$it" }.toSet(), ids.filter { it.matches(Regex("L\\d+")) }.toSet())
        assertEquals(listOf("L19", "L20"), Links.Pending.all.map { it.id })
        Links.Pending.all.forEach { assertTrue(it.id, it.batch.isNotBlank()) }
    }

    /** A later batch hangs its link on a signal type that exists now: the hub carries it through. */
    private object LaterMealLink : Link {
        override val id = "L13-test"
        override val zh = "測試：違規記錄"
        override val dailyCap = 5
        override fun fire(signal: Signal, state: DayState, ctx: LinkContext) =
            if (signal is Signal.MealLogged && signal.breaches.isNotEmpty()) listOf(Effect.Record("breach", signal.breaches.joinToString())) else emptyList()
    }

    @Test fun aLaterBatchsLinkHooksOnWithoutTouchingTheHub() {
        val nv = nerve(Links.all() + LaterMealLink)
        emit(nv, Signal.MealLogged("meal-1", t("12:20"), SlotKey.LUNCH, 650, 30.0, listOf("PROCESSED")))
        emit(nv, Signal.WaterLogged("w-1", t("13:00"), 1))
        emit(nv, Signal.TrainingDone("tr-1", t("17:08"), app.agency.core.model.TrainingKind.CORE7))
        emit(nv, Signal.SignedOut("out-1", t("02:00")))
        val st = runBlocking { nv.state(monday) }
        assertEquals(650, st.kcal)
        assertEquals(1, st.waterHalves)
        assertEquals(setOf(app.agency.core.model.TrainingKind.CORE7), st.training)
        assertEquals("PROCESSED", (effect(of("L13-test").single()) as Effect.Record).detail)
    }

    // ---- L1 ----

    @Test fun l1ALateWakeMovesTheFirstMealOnly() {
        val nv = nerve()
        at("10:00")
        emit(nv, Signal.WokeUp("in", t("10:00"))) // 90 minutes late
        val e = effect(of("L1").single()) as Effect.AdjustToday
        assertEquals(DayAdjust.Move(SlotKey.LUNCH, DayClock.minute("13:40")), e.change)
        assertEquals("因為你晚起 90 分鐘，所以午餐延到 13:40；睡覺時間不變。", e.because)
        val plan = runBlocking { nv.plan(monday) }
        assertEquals("13:40", plan.slot(SlotKey.LUNCH)!!.time)
        assertEquals("02:30", plan.slot(SlotKey.SIGN_OUT)!!.time)
    }

    @Test fun l1TheFirstMealMovesAtMostToThreeHoursBeforeDinner() {
        val nv = nerve()
        emit(nv, Signal.WokeUp("in", t("13:30")))
        assertEquals(DayAdjust.Move(SlotKey.LUNCH, DayClock.minute("14:15")), (effect(of("L1").single()) as Effect.AdjustToday).change)
    }

    @Test fun l1UnderAnHourLateChangesNothing() {
        val nv = nerve()
        emit(nv, Signal.WokeUp("in", t("09:20")))
        assertTrue(of("L1").isEmpty())
    }

    // ---- L2 / L3 / §3b ----

    @Test fun l2AnUnclearIllnessGetsOneQuestion() {
        val nv = nerve()
        val r = emit(nv, Signal.Unwell("u", t("11:00"), IllKind.UNKNOWN, "不舒服"))
        assertEquals(listOf(Links.L2Illness.ASK), r.messages.map { it.text })
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.isEmpty())
        assertTrue(runBlocking { nv.state(monday) }.illAsked)
    }

    @Test fun l2SickProposesTodayOnlyAndOnlyHisYesApplies() {
        val nv = nerve()
        at("11:00")
        val r = emit(nv, Signal.Unwell("u", t("11:00"), IllKind.SICK, "發燒"))
        assertTrue(r.messages.single().text.startsWith("先照顧身體。發燒的話，今天的訓練我提一個調整：今天核心和肌力先停，伸展照做十分鐘。"))
        val p = of("L2").single { it.kind == EffectKind.PROPOSAL }
        assertEquals(EffectStatus.PROPOSED, p.status)
        // Not applied before his yes.
        assertNotNullSlot(nv, SlotKey.STRENGTH)
        assertEquals(1, runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.size)
        // A second message about it does not propose again.
        emit(nv, Signal.Unwell("u2", t("11:30"), IllKind.SICK, "發燒"))
        assertEquals(1, of("L2").count { it.kind == EffectKind.PROPOSAL })
        val line = runBlocking { nv.decide(p.id, accept = true) }!!
        assertEquals("好，只改今天：今天核心和肌力先停，伸展照做十分鐘。不用明天補做，好好休息。", line)
        val plan = runBlocking { nv.plan(monday) }
        assertEquals(Reduction.SICK, plan.reduction)
        assertNull(plan.slot(SlotKey.STRENGTH))
        assertNull(plan.slot(SlotKey.CORE))
        assertTrue(plan.slot(SlotKey.STRETCH)!!.required)
        assertEquals(ProposalStatus.ACCEPTED, runBlocking { store.proposals.get(p.proposalId!!) }!!.status)
        // Tomorrow is a normal day again.
        assertNotNullSlot(nv, SlotKey.STRENGTH, monday.plusDays(1))
        assertTrue(runBlocking { nv.state(monday) }.gentle)
    }

    private fun assertNotNullSlot(nv: Nerve, key: SlotKey, day: LocalDate = monday) = assertTrue(runBlocking { nv.plan(day) }.slot(key) != null)

    @Test fun l2NoAnswerKeepsThePlanAndTheProposalLapsesTheNextDay() {
        val nv = nerve()
        at("11:00")
        emit(nv, Signal.Unwell("u", t("11:00"), IllKind.INJURY, "扭到"))
        val p = of("L2").single { it.kind == EffectKind.PROPOSAL }
        assertEquals("因為你說扭到，所以只改今天：今天核心和肌力先停，伸展照做，避開受傷的地方。", p.because)
        at("12:00", monday.plusDays(1))
        assertTrue(runBlocking { nv.pending() }.isEmpty())
        assertEquals(EffectStatus.EXPIRED, runBlocking { store.nerve.effect(p.id) }!!.status)
        assertNull(runBlocking { nv.decide(p.id, true) })
        assertNotNullSlot(nv, SlotKey.STRENGTH)
    }

    @Test fun l2ARejectedChangeKeepsThePlan() {
        val nv = nerve()
        emit(nv, Signal.Unwell("u", t("11:00"), IllKind.SICK, "感冒"))
        val p = of("L2").single { it.kind == EffectKind.PROPOSAL }
        assertEquals("好，今天照原本的表。", runBlocking { nv.decide(p.id, false) })
        assertNotNullSlot(nv, SlotKey.STRENGTH)
    }

    /** §3b: a lighter day only through L2 for an illness or injury; a proposal row from anywhere else cannot do it. */
    @Test fun onlyAnIllnessProposalOfL2CanLightenTraining() {
        val nv = nerve()
        at("11:00")
        emit(nv, Signal.MoodLow("mood", t("11:00")))
        val fake = Effect.Proposal(todayOnly = true, change = "今天不練", because = "因為心情不好", reduction = Reduction.SICK)
        runBlocking {
            store.nerve.insertEffect(
                NerveEffect("fake", monday.toString(), 1, "L3", "mood", EffectKind.PROPOSAL, Nerve.encodeEffect(fake), fake.because, EffectStatus.PROPOSED),
            )
        }
        assertEquals("這個調整已經不能用了，今天照表。", runBlocking { nv.decide("fake", true) })
        assertNotNullSlot(nv, SlotKey.STRENGTH)
        assertNull(runBlocking { nv.plan(monday) }.reduction)
    }

    @Test fun l2ThreeDaysInARowMeansADoctor() {
        val nv = nerve()
        for (d in 0L..2L) emit(nv, Signal.Unwell("u$d", t("11:00", monday.plusDays(d)), IllKind.SICK, "感冒"))
        assertTrue(of("L2", monday.plusDays(1)).none { it.because == "see_doctor" })
        val day3 = of("L2", monday.plusDays(2))
        assertTrue(day3.any { it.kind == EffectKind.SAY && it.because == "已經連三天不舒服了，去看醫生。" })
        assertTrue(day3.any { it.kind == EffectKind.RECORD && it.because == "see_doctor" })
    }

    @Test fun l3LowMoodIsAGentleDayButTrainingStays() {
        val nv = nerve()
        emit(nv, Signal.MoodLow("m", t("15:00")))
        assertTrue(runBlocking { nv.state(monday) }.gentle)
        assertEquals("gentle", of("L3").single().because)
        assertNotNullSlot(nv, SlotKey.STRENGTH)
    }

    @Test fun askingToDoLessWithoutIllnessIsAlwaysTodayAsPlanned() {
        val nv = nerve()
        repeat(5) { i ->
            val r = emit(nv, Signal.ReduceAsked("r$i", t("22:00")))
            assertEquals(listOf(Links.NoReduction.LINE), r.messages.map { it.text })
        }
        assertNotNullSlot(nv, SlotKey.STRENGTH)
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.isEmpty())
    }

    // ---- L4 ----

    @Test fun l4HungryMorningSwapsTheSoyAndCanBeCancelledForTheDay() {
        val nv = nerve()
        at("08:40")
        emit(nv, Signal.HungryMorning("h", t("08:40")))
        val e = of("L4").single()
        assertEquals("因為你說早上很餓，所以豆漿挪到早上喝；雞胸照樣 21:00 跑單途中吃。", e.because)
        assertTrue(runBlocking { store.days.get(monday.toString()) }!!.soyMorning)
        assertEquals("21:00", runBlocking { nv.plan(monday) }.slot(SlotKey.RIDE_MEAL)!!.time)
        assertTrue(runBlocking { nv.cancel(e.id) })
        assertEquals(EffectStatus.CANCELLED, runBlocking { store.nerve.effect(e.id) }!!.status)
        assertNull(runBlocking { nv.plan(monday) }.slot(SlotKey.MORNING_SOY))
        assertFalse(runBlocking { store.days.get(monday.toString()) }!!.soyMorning)
        assertFalse(runBlocking { nv.cancel(e.id) })
    }

    @Test fun onlyTodaysAdjustmentCanBeCancelled() {
        val nv = nerve()
        at("10:00")
        emit(nv, Signal.WokeUp("in", t("10:00")))
        val e = of("L1").single()
        at("10:00", monday.plusDays(1))
        assertFalse(runBlocking { nv.cancel(e.id) })
    }

    // ---- L6 / L8 / L9 ----

    @Test fun l6UnderTheFloorAtDinnerAsksHimToEatEnough() {
        val nv = nerve()
        emit(nv, Signal.MealLogged("lunch", t("12:20"), SlotKey.LUNCH, 400, 30.0))
        emit(nv, Signal.MealLogged("dinner", t("17:30"), SlotKey.DINNER, 300, 25.0))
        val e = effect(of("L6").single()) as Effect.Notify
        assertEquals("今天才吃 700 大卡，不到 1200。要吃夠，不准少吃；吃了拍照回報。", e.text)
        val enough = nerve()
        emit(enough, Signal.MealLogged("dinner2", t("17:30", monday.plusDays(1)), SlotKey.DINNER, 1300, 60.0))
        assertTrue(of("L6", monday.plusDays(1)).isEmpty())
    }

    @Test fun l8RidingSoundsOnceAndADeliveryDayRemindsToDrinkBefore() {
        val nv = nerve()
        emit(nv, Signal.DayStarted("day:$monday", t("08:00"), monday.toString()))
        val pre = of("L8").single { it.kind == EffectKind.NOTIFY }
        assertEquals(t("17:40"), pre.dueAt)
        emit(nv, Signal.RideStart("ride", t("18:05")))
        val st = runBlocking { nv.state(monday) }
        assertTrue(st.riding)
        assertTrue(DayFlag.RIDING_ONE_SOUND in st.flags)
        // A rest day has no delivery: no reminder to drink before it.
        runBlocking { store.days.put(app.agency.core.model.DayOverride(monday.plusDays(1).toString(), restDay = true, declaredAt = 1)) }
        emit(nv, Signal.DayStarted("day:tue", t("08:00", monday.plusDays(1)), monday.plusDays(1).toString()))
        assertTrue(of("L8", monday.plusDays(1)).isEmpty())
    }

    @Test fun l9HomeAfterTheOnRunPortionDropsTheLateSnackThenChecksTheFloor() {
        val nv = nerve()
        emit(nv, Signal.MealLogged("lunch", t("12:20"), SlotKey.LUNCH, 500, 40.0))
        emit(nv, Signal.RideStart("ride", t("18:00")))
        emit(nv, Signal.MealLogged("run", t("21:05"), SlotKey.RIDE_MEAL, 310, 40.0))
        val r = emit(nv, Signal.RideEnd("end", t("23:40")))
        assertEquals("因為 21:00 那份吃了，所以今天不吃宵夜。", of("L9").single().because)
        assertTrue(DayFlag.LATE_SNACK_OFF in runBlocking { nv.state(monday) }.flags)
        // The chained floor check (depth 1) runs L6: 810 is under 1200.
        assertEquals("floor:end", runBlocking { store.nerve.signal("floor:end") }!!.id)
        assertTrue(r.notifies.any { it.link == "L6" })
    }

    @Test fun l9WithoutTheOnRunPortionKeepsEverything() {
        val nv = nerve()
        emit(nv, Signal.RideEnd("end", t("23:40")))
        assertTrue(of("L9").isEmpty())
    }

    // ---- L12 ----

    @Test fun l12TheChaseNamesWhatIsMissingAndRidingSoundsOnce() {
        val nv = nerve()
        val first = emit(nv, Signal.GapDue("g0", t("12:10"), listOf(SlotKey.LUNCH), 0)).notifies.single()
        assertEquals("午餐時間到了，做完回報。", first.because)
        assertFalse((effect(first) as Effect.Notify).soundOnce)
        val chase = emit(nv, Signal.GapDue("g1", t("12:40"), listOf(SlotKey.LUNCH, SlotKey.WAKE, SlotKey.CORE, SlotKey.DINNER), 1)).notifies.single()
        assertEquals("還缺：午餐、起床簽到、核心七分鐘。還有 1 項。", chase.because)
        emit(nv, Signal.RideStart("ride", t("18:00")))
        val riding = emit(nv, Signal.GapDue("g2", t("21:00"), listOf(SlotKey.RIDE_MEAL), 0)).notifies.single()
        assertTrue((effect(riding) as Effect.Notify).soundOnce)
    }

    // ---- L17 / L18 ----

    @Test fun l17ShaveEveryTwoDaysAtSignIn() {
        val nv = nerve()
        assertEquals("刮鬍子（每兩天一次），刮好說「刮好了」。", emit(nv, Signal.WokeUp("in0", t("08:30"))).notifies.single { it.link == "L17" }.because)
        emit(nv, Signal.GroomingDone("shave", t("08:40"), GroomingItem.SHAVE))
        assertTrue(emit(nv, Signal.WokeUp("in1", t("08:30", monday.plusDays(1)))).notifies.none { it.link == "L17" })
        assertTrue(emit(nv, Signal.WokeUp("in2", t("08:30", monday.plusDays(2)))).notifies.any { it.link == "L17" })
    }

    @Test fun l17SundayNailsAndBrowsAndTheHaircutBooking() {
        val nv = nerve()
        val sunday = monday.plusDays(6)
        emit(nv, Signal.GroomingDone("hair", t("15:00", sunday.minusDays(25)), GroomingItem.HAIR))
        val e = emit(nv, Signal.DayStarted("day:$sunday", t("08:00", sunday), sunday.toString())).notifies.single { it.link == "L17" }
        assertEquals("儀容：剪指甲（手和腳）、修眉毛、三天後該剪頭髮了，先預約。做好拍照回報。", e.because)
        assertEquals(t("14:00", sunday), e.dueAt) // an hour after the first meal
    }

    @Test fun l18ANoClassDayPlacesTheWalk() {
        val nv = nerve()
        val wed = monday.plusDays(2)
        emit(nv, Signal.DayStarted("day:$wed", t("08:00", wed), wed.toString()))
        assertEquals("因為今天沒課，所以 16:20 快走 30 分鐘。", of("L18", wed).single().because)
        emit(nv, Signal.DayStarted("day:$monday", t("08:00"), monday.toString()))
        assertTrue(of("L18").isEmpty())
    }

    // ---- L21 / L22 / L23 ----

    @Test fun l21UnderSixtyFiveStopsTheTarget() {
        val nv = nerve()
        emit(nv, Signal.WeightLogged("w1", t("07:00"), 65.0))
        assertTrue(of("L21").isEmpty())
        emit(nv, Signal.WeightLogged("w2", t("07:00", monday.plusDays(7)), 64.8))
        assertTrue(of("L21", monday.plusDays(7)).any { it.kind == EffectKind.NOTIFY && it.because.startsWith("體重到 65 公斤以下了") })
    }

    @Test fun l22TiredRidingIsWarnedAndTrainingStays() {
        val nv = nerve()
        emit(nv, Signal.SignedOut("out", t("02:30", monday.minusDays(1))))
        emit(nv, Signal.WokeUp("in", t("08:00"))) // 5.5 hours
        val r = emit(nv, Signal.RideStart("ride", t("18:00")))
        assertEquals("疲勞騎車很危險：昨晚只睡 5.5 小時。今天少跑幾單、早點收工。訓練照表。", r.messages.single().text)
        assertNotNullSlot(nv, SlotKey.STRENGTH)
        val awake = nerve()
        val tue = monday.plusDays(1)
        emit(awake, Signal.SignedOut("out2", t("23:00", monday)))
        emit(awake, Signal.WokeUp("in2", t("06:30", tue)))
        assertEquals("疲勞騎車很危險：已經醒了 17 小時。今天少跑幾單、早點收工。訓練照表。", emit(awake, Signal.RideStart("ride2", t("23:30", tue))).messages.single().text)
        val rested = nerve()
        val wed = monday.plusDays(2)
        emit(rested, Signal.SignedOut("out3", t("01:00", tue)))
        emit(rested, Signal.WokeUp("in3", t("09:00", wed)))
        assertTrue(emit(rested, Signal.RideStart("ride3", t("18:00", wed))).messages.isEmpty())
    }

    @Test fun l23LosingTooFastProposesMoreKcalButOnlySettingsChangesTheRules() {
        val nv = nerve()
        val d0 = monday.minusDays(14)
        for (i in 0..14) emit(nv, Signal.MealLogged("m$i", t("12:30", d0.plusDays(i.toLong())), SlotKey.LUNCH, 1700, 140.0))
        emit(nv, Signal.WeightLogged("w0", t("07:00", d0), 82.0))
        emit(nv, Signal.WeightLogged("w1", t("07:00", d0.plusDays(7)), 80.6))
        at("07:00")
        emit(nv, Signal.WeightLogged("w2", t("07:00"), 79.2))
        val p = of("L23").single()
        val e = effect(p) as Effect.Proposal
        // TDEE = 1700 + 2.8 × 7700 ÷ 14 = 3240; minus 500 → 2740, rounded up to 2750.
        assertEquals(2750, e.suggestQuota)
        assertFalse(e.todayOnly)
        assertEquals("好。公司規章要你自己改：到設定的公司規章，把每天的熱量改成 2750，確認兩次。", runBlocking { nv.decide(p.id, true) })
        assertNull(runBlocking { store.rules.current() })
        assertEquals(CompanyRules().quotaKcal, 1700)
    }

    @Test fun l23ASlowLossProposesNothing() {
        val nv = nerve()
        val d0 = monday.minusDays(14)
        emit(nv, Signal.WeightLogged("w0", t("07:00", d0), 82.0))
        emit(nv, Signal.WeightLogged("w1", t("07:00", d0.plusDays(7)), 81.3))
        emit(nv, Signal.WeightLogged("w2", t("07:00"), 80.5))
        assertTrue(of("L23").isEmpty())
    }

    @Test fun l14OneItemUndoneThreeDaysInARowOpensADiscussionOnce() {
        val state = DayState(
            java.time.LocalDate.parse("2026-11-23"), app.agency.core.schedule.DayPlanner.build(java.time.LocalDate.parse("2026-11-23")),
            missedInRow = mapOf(app.agency.core.schedule.SlotKey.STRENGTH to 3, app.agency.core.schedule.SlotKey.LUNCH to 2),
        )
        val ctx = LinkContext(Instant.parse("2026-11-23T04:00:00Z"), AgencyClock.TAIPEI, app.agency.core.model.CompanyRules(), app.agency.core.schedule.PlanSpecs())
        val keys = listOf(app.agency.core.schedule.SlotKey.LUNCH, app.agency.core.schedule.SlotKey.STRENGTH)
        val fx = Links.L14Discuss.fire(Signal.DayMissed("missed:2026-11-22", 0, "2026-11-22", keys), state, ctx)
        val say = fx.filterIsInstance<Effect.Say>().single()
        assertEquals("肌力三輪已經連三天沒做到了。我要先弄清楚原因：是哪裡卡住了？", say.text)
        assertTrue(say.discuss)
        assertEquals(4, say.options.size)
        // An older day's report, or a two-day streak, opens nothing.
        assertTrue(Links.L14Discuss.fire(Signal.DayMissed("missed:2026-11-21", 0, "2026-11-21", keys), state, ctx).isEmpty())
        assertTrue(Links.L14Discuss.fire(Signal.DayMissed("missed:2026-11-22", 0, "2026-11-22", keys.take(1)), state, ctx).isEmpty())
    }
}
