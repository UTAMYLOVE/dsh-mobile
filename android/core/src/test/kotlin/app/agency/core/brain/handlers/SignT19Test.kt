package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.engine.ConditionPolicy
import app.agency.core.engine.IllKind
import app.agency.core.engine.RealTasks
import app.agency.core.engine.SignPolicy
import app.agency.core.engine.SlotStatus
import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import app.agency.core.model.SignKind
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.SlotKey
import app.agency.core.store.InMemoryAgencyStore
import app.agency.core.structured.ScriptedLlm
import app.agency.core.time.AgencyClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T19: sign-in and its score, sign-out with the bedtime review (L19), the 「提早起床」 run, the week's end (L20). Invented data. */
class SignT19Test {
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

    private fun say(hhmm: String, text: String, llm: ScriptedLlm = ScriptedLlm()): List<Message> {
        at(hhmm)
        return runBlocking { brain(llm).onText(text) }.filter { it.who == Who.COMPANY }
    }

    private fun lines(hhmm: String, text: String, llm: ScriptedLlm = ScriptedLlm()) = say(hhmm, text, llm).map { it.text }

    private fun nudge(text: String) = ScriptedLlm.ok("""{"text":"$text"}""")

    @Test fun signInIsOnTimeUpToTenMinutesAfterTheWakeSlotAndAsksTheScore() {
        val wake = day.atStartOfDay(AgencyClock.TAIPEI).plusHours(8).plusMinutes(30).toInstant()
        assertTrue(SignPolicy.onTime(wake.plusSeconds(600), wake))
        assertFalse(SignPolicy.onTime(wake.plusSeconds(601), wake))
        assertTrue(SignPolicy.onTime(wake.minusSeconds(3600), wake))
        val out = lines("08:40", "簽到")
        assertEquals(listOf("簽到了，準時。", ConditionPolicy.ASK), out.filter { it != RealTasks.line(day) })
        assertTrue(runBlocking { brain().conditionDue() })
        // The typed score answers it (code, no model).
        assertEquals(listOf("記下：今天 4 分。很好，照表走。"), lines("08:41", "4 分"))
        assertFalse(runBlocking { brain().conditionDue() })
        assertEquals(4, runBlocking { store.conditions.get(day.toString()) }!!.score)
        assertEquals(listOf("今天已經簽到了。"), lines("08:42", "簽到"))
    }

    @Test fun aLowScoreIsGentleButTheTrainingStaysAsPlanned() {
        lines("08:30", "簽到")
        val two = runBlocking { at("08:31"); brain().condition(2, sleep = 1, energy = 2, ill = null) }.filter { it.who == Who.COMPANY }
        assertEquals("記下：今天 2 分。哪裡不舒服要說；只是累或沒睡好的話，今天照表，晚上早點睡。", two.single().text)
        val state = runBlocking { brain().nerve.state(day) }
        assertTrue(state.gentle)
        val plan = runBlocking { brain().nerve.plan(day) }
        assertTrue(plan.slot(SlotKey.STRENGTH) != null && plan.slot(SlotKey.CORE) != null)
        assertNull(plan.reduction)
        // Score 1 the next day: still the rounds, due and chased like any day.
        day = day.plusDays(1)
        lines("08:30", "簽到")
        assertEquals(listOf("記下：今天 1 分。哪裡不舒服要說；只是累或沒睡好的話，今天照表，晚上早點睡。"), lines("08:31", "1"))
        at("02:00")
        val slots = runBlocking { brain().card() }.slots
        assertEquals(SlotStatus.OPEN, slots.single { it.key == SlotKey.STRENGTH }.status)
        // Asking for less is 「今天照表」, whatever the score.
        assertEquals(listOf(app.agency.core.engine.Links.NoReduction.LINE), lines("02:01", "今天可以少做一輪嗎"))
    }

    @Test fun illOnThePickerGoesToTheTodayOnlyProposalWithTheDoctorLine() {
        lines("08:30", "簽到")
        val out = runBlocking { at("08:32"); brain().condition(2, null, null, IllKind.SICK) }.filter { it.who == Who.COMPANY }.map { it.text }
        assertEquals("記下：今天 2 分，生病。先照顧身體：正常吃東西、多喝水；一直不好就去看醫生。", out[0])
        assertTrue(out[1], out[1].startsWith("先照顧身體。生病的話，今天的訓練我提一個調整：今天核心和肌力先停，伸展照做十分鐘。"))
        assertEquals(1, runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.count { it.kind == ProposalKind.TODAY_CHANGE })
        assertTrue(runBlocking { store.conditions.get(day.toString()) }!!.ill)
        assertTrue(runBlocking { brain().nerve.state(day) }.gentle)
    }

    @Test fun signOutAnyTimeLateOnlyAfterTheLatestAndQuietUntilTheWake() {
        lines("08:30", "簽到")
        // Early sleep is fine and no gap: what was not done is missed, nothing is chased.
        val out = lines("23:00", "我要睡了")
        assertEquals(listOf("簽退了。", ReviewDesk.ASK_GOOD), out)
        assertTrue(runBlocking { store.signs.onDay(day.toString()) }.single { it.kind == SignKind.OUT }.onTime!!)
        at("23:30")
        val tick = runBlocking { brain().tick() }
        assertTrue(tick.posts.isEmpty())
        assertTrue(runBlocking { brain().card() }.gaps.isEmpty())
        // The next night after 02:30: late.
        day = day.plusDays(1)
        lines("08:30", "簽到")
        assertEquals("簽退了，比最晚的時間晚了。明天早點睡。", lines("02:45", "簽退").first())
        assertFalse(runBlocking { store.signs.onDay(day.toString()) }.single { it.kind == SignKind.OUT }.onTime!!)
    }

    @Test fun theBedtimeReviewAsksTwoQuestionsKeepsTheAnswersAndProposesTomorrowsFix() {
        lines("08:30", "簽到")
        lines("08:31", "3")
        lines("23:00", "簽退")
        assertEquals(listOf(ReviewDesk.ASK_FIX), lines("23:01", "午餐有照點餐卡點"))
        // It talks about the order card: food talk, so the second-pass judge reads it too.
        val llm = ScriptedLlm(nudge("今天照點餐卡點，很好。明天早點出門，現在去睡。"), ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}"""))
        val out = say("23:02", "明天早點出門", llm)
        val card = out.first { it.kind == "link" }.text.lines()
        assertEquals("今天的檢討卡", card[0])
        assertEquals("做到：起床簽到、簽退", card[1])
        assertTrue(card[2], card[2].startsWith("沒做到：午餐、晚餐、出門跑單、21:00 那份蛋白質、核心七分鐘、肌力三輪、伸展"))
        assertTrue(card.contains("沒做到的不用補，明天照表。"))
        assertTrue(card.contains("你說做得好：午餐有照點餐卡點"))
        assertEquals("明天要改：明天早點出門", card.last())
        // Her one sentence, guarded (the model was asked once, for a reminder-style line).
        assertEquals(ReviewDesk.LINE_KIND, out.last().kind)
        assertEquals("今天照點餐卡點，很好。明天早點出門，現在去睡。", out.last().text)
        assertEquals(listOf(app.agency.core.llm.Purpose.NUDGE, app.agency.core.llm.Purpose.JUDGE), llm.requests.map { it.purpose })
        val row = runBlocking { store.signs.onDay(day.toString()) }.single { it.kind == SignKind.OUT }
        assertEquals("午餐有照點餐卡點" to "明天早點出門", row.reviewGood to row.reviewFix)
        // The fix is a proposal: on his yes, tomorrow's sign-in reminds him of it.
        val p = runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        assertEquals(ProposalKind.TOMORROW_FIX, p.kind)
        assertEquals("明天要改", app.agency.core.brain.ProposalApplier.view(p).title)
        assertEquals(listOf("好，明天起床簽到的時候我提醒你：明天早點出門"), lines("23:03", "好"))
        // Nothing more tonight (no question waiting): his next line goes to the model as usual.
        assertNull(runBlocking { brain().reviewOpen() })
        day = day.plusDays(1)
        val morning = lines("08:30", "簽到")
        assertTrue(morning.toString(), morning.contains("昨晚你說今天要改：明天早點出門。今天就做到。"))
    }

    @Test fun theReviewSheetAnswersBothAtOnceAndAFailedModelKeepsTheGoodNight() {
        lines("08:30", "簽到")
        lines("23:00", "簽退")
        assertEquals(ReviewDesk.Open.GOOD, runBlocking { brain().reviewOpen() })
        val out = runBlocking { at("23:01"); brain(ScriptedLlm(ScriptedLlm.failed(Fail.NO_KEY))).review("準時起床", "早點睡") }
        assertEquals(Who.OWNER, out.first().who)
        assertEquals(ReviewDesk.GOOD_NIGHT, out.last().text)
        assertEquals(RoleKey.MANAGER, out.last().role)
        val row = runBlocking { store.signs.onDay(day.toString()) }.single { it.kind == SignKind.OUT }
        assertEquals("準時起床" to "早點睡", row.reviewGood to row.reviewFix)
        assertTrue(runBlocking { brain().review("又一次", "再一次") }.isEmpty())
    }

    @Test fun twoWeeksOfOnTimeClassDaySignInsAskOnceToWakeTenMinutesEarlier() {
        val classDays = listOf("2026-11-23", "2026-11-24", "2026-11-26", "2026-11-27", "2026-11-30", "2026-12-01", "2026-12-03", "2026-12-04")
        val asked = mutableListOf<String>()
        classDays.forEach { d ->
            day = LocalDate.parse(d)
            val out = lines("08:35", "簽到")
            out.filter { it.startsWith("連續兩週上課日都準時簽到了") }.forEach { asked += d }
        }
        // The no-class days between (三、六、日) are never counted.
        assertEquals(listOf("2026-12-04"), asked)
        val p = runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.single { it.kind == ProposalKind.WAKE_EARLIER }
        assertEquals("提早起床", app.agency.core.brain.ProposalApplier.view(p).title)
        // His yes (that day) moves the class-day wake.
        assertEquals("好，改了：上課日起床 08:30 → 08:20。", runBlocking { at("08:40"); brain().decide(p.id, true) }!!.text)
        assertEquals("08:20", runBlocking { brain().nerve.plan(LocalDate.parse("2026-12-07")) }.slot(SlotKey.WAKE)!!.time)
        // The next on-time class day does not ask again.
        day = LocalDate.parse("2026-12-07")
        assertTrue(lines("08:25", "簽到").none { it.startsWith("連續兩週") })
    }

    @Test fun theWeekEndsWithItsNumbersAndTheRealPersonTask() {
        // The reminders start on Monday: the week's real-person task comes with them.
        at("07:00")
        runBlocking { brain().tick() }
        val first = runBlocking { store.messages.recent(10) }.map { it.text }
        assertTrue(first.contains(RealTasks.line(day)))
        assertEquals(listOf("記下：這週的真人任務做到了。跟真的人相處，就是這樣。"), lines("19:00", "真人任務完成"))
        assertEquals(listOf("這週的真人任務已經記過了。"), lines("19:01", "真人任務完成"))
        // The next Monday's start counts the week: almost nothing done, so not on target.
        day = LocalDate.parse("2026-11-30")
        val out = lines("08:30", "簽到")
        val summary = out.single { it.startsWith("一週結果") }.lines()
        assertEquals("一週結果（11/23–11/29）", summary[0])
        assertTrue(summary[1], summary[1].startsWith("完成率 "))
        assertEquals("真人任務：做到了", summary[5])
        assertTrue(summary.last().contains("這週沒達標"))
        assertTrue(summary.last().contains("等級 C，達標週數 0 週"))
        assertTrue(out.contains(RealTasks.line(day)))
        // Once.
        assertTrue(lines("09:00", "簽到").none { it.startsWith("一週結果") })
    }
}
