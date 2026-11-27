package app.agency.core.brain.handlers

import app.agency.core.brain.Brain
import app.agency.core.engine.Effect
import app.agency.core.engine.Links
import app.agency.core.engine.Nerve
import app.agency.core.engine.Signal
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.EffectStatus
import app.agency.core.model.Lesson
import app.agency.core.model.LessonCategory
import app.agency.core.model.LessonStatus
import app.agency.core.model.MessageMode
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.Who
import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.Feature
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's rules of 2026-11-26 that run on the days and the week: the first day after an illness has two rounds, the third
 * breach of a week brings a 面談, 全勤小卡 at 7 and 30 days, 考試週 (one lesson a day, twice a semester), the manual weather
 * switch, and no coffee or tea after 20:30. Invented data only.
 */
class WeekRulesTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private var day = LocalDate.parse("2026-12-07") // a Monday

    init {
        runBlocking { store.profile.put(Profile(onboardingDone = true)) }
    }

    private fun brain(llm: ScriptedLlm = ScriptedLlm()) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private fun at(hhmm: String) {
        clock.now = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }
    private fun t(hhmm: String) = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant().toEpochMilli()
    private fun say(hhmm: String, text: String, llm: ScriptedLlm = ScriptedLlm()): List<String> {
        at(hhmm)
        return runBlocking { brain(llm).onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    @Test fun theFirstDayAfterAnIllnessHasTwoRoundsThenFull() {
        say("09:00", "我發燒了")
        day = day.plusDays(1)
        at("08:00")
        runBlocking { brain().tick() }
        val plan = runBlocking { brain().nerve.plan(day) }
        assertEquals(2, plan.strengthRounds)
        assertEquals("2 輪（病好第一天，明天全量）", plan.slot(SlotKey.STRENGTH)!!.note)
        assertTrue(runBlocking { store.nerve.effects(day.toString()) }.any { it.because == "因為昨天生病或受傷，所以今天是病好第一天：肌力先做 2 輪，明天全量。" })
        // Two rounds are the whole of today's (home from the run: the rounds may be done).
        say("18:10", "出門跑單")
        say("22:40", "收工")
        at("23:00")
        val out = runBlocking { brain().strengthDone(StrengthReport(2, mapOf("push_up" to 15, "squat" to 20, "plank" to 60), extra = 1, seconds = 600)) }
        assertTrue(out.first { it.who == Who.COMPANY }.text.startsWith("記下：肌力 2 輪"))
        day = day.plusDays(1)
        at("08:00")
        runBlocking { brain().tick() }
        assertEquals(3, runBlocking { brain().nerve.plan(day) }.strengthRounds)
    }

    @Test fun theThirdBreachOfTheWeekBringsAnInterview() {
        fun breach(d: Long, hhmm: String) = runBlocking {
            day = LocalDate.parse("2026-12-07").plusDays(d)
            at(hhmm)
            brain().signal(Signal.MealLogged("m-$d-$hhmm", t(hhmm), SlotKey.DINNER, 700, 30.0, breaches = listOf("PROCESSED")))
        }
        assertTrue(breach(0, "17:40").messages.none { it.mode == MessageMode.DISCUSS })
        assertTrue(breach(2, "17:40").messages.none { it.mode == MessageMode.DISCUSS })
        val third = breach(3, "17:40").messages.single { it.mode == MessageMode.DISCUSS }
        assertEquals("這週已經違規 3 次了。照公司規章要面談，這週的等級進度不算。先說原因：是哪裡卡住了？", third.text)
        assertEquals(Links.L26Interview.OPTIONS, third.refs.options)
        // The fourth one asks no more.
        assertTrue(breach(4, "12:30").messages.none { it.mode == MessageMode.DISCUSS })
        // A new week starts again.
        assertTrue(breach(7, "17:40").messages.none { it.mode == MessageMode.DISCUSS })
    }

    @Test fun sevenDaysWithEverythingDoneEarnTheAttendanceCard() {
        // Only the sign-in and the sign-out open (a custom table, data): each day owes those two.
        val table = Feature.entries.associate { it.name to 4 } + mapOf(Feature.SIGN.name to 1, Feature.ATTENDANCE.name to 1)
        runBlocking {
            store.rules.putSection(PlanSectionKey.ROLLOUT, PlanSpecs.JSON.encodeToString(RolloutSpec.serializer(), RolloutSpec(week = 1, table = table)), PlanSection.BY_OWNER_SETTINGS, 0)
        }
        at("07:00")
        runBlocking { brain().tick() }
        val start = day
        for (i in 0 until 7) {
            day = start.plusDays(i.toLong())
            say("08:30", "簽到")
            say("23:00", "簽退")
        }
        day = start.plusDays(7)
        val lines = say("08:30", "簽到")
        assertTrue(lines.toString(), lines.contains("全勤小卡：連續 7 天，該做的全部做到。小卡貼上照片牆了。"))
        assertEquals(listOf(day.toString() to 7), runBlocking { brain().attendanceCards() })
    }

    @Test fun anExamWeekHasOneLessonADayTwiceASemester() {
        runBlocking {
            store.lessons.upsert(Lesson("l1", "r1", LessonCategory.VOCAL, title = "發聲", url = "https://example.org/v", source = "example.org", status = LessonStatus.LISTED, foundAt = 0, listedAt = 1))
        }
        at("09:00")
        assertEquals(2, runBlocking { brain().nerve.plan(day) }.slot(SlotKey.LESSONS)!!.count)
        assertEquals(listOf("好，這週是考試週：理論課每天 1 支，訓練照做。這學期第 1 次（最多 2 次）。"), say("09:01", "這週考試"))
        assertEquals(1, runBlocking { brain().nerve.plan(day) }.slot(SlotKey.LESSONS)!!.count)
        assertEquals(1, runBlocking { PlanStore(store).plan(day.plusDays(6)) }.slot(SlotKey.LESSONS)!!.count) // the whole week
        assertEquals(2, runBlocking { PlanStore(store).plan(day.plusDays(7)) }.slot(SlotKey.LESSONS)!!.count) // not the next
        assertEquals(listOf("這週已經是考試週了：理論課每天 1 支，訓練照做。"), say("09:02", "這週考試"))
        day = day.plusDays(14)
        assertTrue(say("09:00", "這週考試").single().contains("第 2 次"))
        day = day.plusDays(14)
        assertEquals(listOf("這學期的考試週已經用了 2 次（最多 2 次），理論課照每天 2 支。"), say("09:00", "這週考試"))
    }

    @Test fun theWeatherIsAManualSwitchBeforeTheRun() {
        assertEquals(listOf("記下：今天下雨。出門前提醒你。"), say("10:00", "今天下雨"))
        val e = runBlocking { store.nerve.effects(day.toString()) }.single { it.link == Links.L28Weather.id }
        assertEquals(t("18:30"), e.dueAt) // 30 minutes before the latest departure (19:00)
        assertEquals(Links.L28Weather.RAIN, (Nerve.decodeEffect(e.json) as Effect.Notify).text)
        assertEquals(listOf("好，今天的「下雨」提醒取消了。"), say("11:00", "雨停了"))
        assertEquals(EffectStatus.CANCELLED, runBlocking { store.nerve.effect(e.id) }!!.status)
        say("12:00", "今天很熱")
        assertTrue(runBlocking { store.nerve.effects(day.toString()) }.any { (Nerve.decodeEffect(it.json) as? Effect.Notify)?.text == Links.L28Weather.HOT })
    }

    @Test fun noCoffeeOrTeaAfterHalfPastEight() {
        at("08:00")
        runBlocking { brain().tick() }
        val e = runBlocking { store.nerve.effects(day.toString()) }.single { it.link == Links.L29Caffeine.id }
        assertEquals(t("20:15"), e.dueAt)
        val item = """{"name":"拿鐵","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":100,"kcal_high":150,"protein_low":6,"protein_high":6}"""
        val late = say("21:10", "喝了一杯拿鐵", ScriptedLlm(ScriptedLlm.ok("""{"kind":"meal","items":[$item]}""")))
        assertTrue(late.first(), late.first().contains(Links.L29Caffeine.LINE))
        val egg = """{"name":"茶葉蛋","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":70,"kcal_high":80,"protein_low":7,"protein_high":7}"""
        assertTrue(say("21:20", "吃了茶葉蛋", ScriptedLlm(ScriptedLlm.ok("""{"kind":"meal","items":[$egg]}"""))).none { it.contains(Links.L29Caffeine.LINE) })
        assertTrue(Links.L29Caffeine.CUTOFF == DayClock.minute("20:30"))
        assertTrue(MealDesk.CAFFEINE.containsMatchIn("無糖綠茶") && !MealDesk.CAFFEINE.containsMatchIn("無咖啡因飲料"))
        assertTrue(DayAdjust.Recovery(2).rounds == 2)
    }
}
