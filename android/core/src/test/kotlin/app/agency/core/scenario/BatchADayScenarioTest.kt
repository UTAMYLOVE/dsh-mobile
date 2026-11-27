package app.agency.core.scenario

import app.agency.core.practiceOff
import app.agency.core.brain.Brain
import app.agency.core.brain.handlers.IncomeDesk
import app.agency.core.brain.handlers.StrengthReport
import app.agency.core.brain.handlers.TrainingDesk
import app.agency.core.engine.Links
import app.agency.core.engine.NotifyKind
import app.agency.core.engine.Nudger
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.LlmClient
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.metering.MutableClock
import app.agency.core.model.PlanSection
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Profile
import app.agency.core.model.TrainingKind
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
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
 * 整天情境 under the owner's rules of 2026-11-26/27: an invented Saturday (no class, a cardio day) in rollout week 4 whose
 * earlier bedtime opened on the Monday (03:45 tonight). Rain switched on (a warning before the run); the first meal of 690
 * and dinner by what is left; the core before the run refused; the latest departure passed, so the 19:00 chase; the 21:00
 * swap (福樂蛋白飲 and 茶葉蛋, no breach); sleepy on the run means no riding; the day's delivery income; the home-time
 * warning on the run; the block after 收工 in order (core, rounds, cardio, stretch); a sign-out after 03:45 is a breach.
 */
class BatchADayScenarioTest {
    private val store = InMemoryAgencyStore().practiceOff()
    private val clock = MutableClock()
    private val saturday = LocalDate.parse("2026-12-12")
    private val meals = ArrayDeque<String>()
    private val requests = mutableListOf<ChatRequest>()
    private val llm = LlmClient { r ->
        requests += r
        when (r.purpose) {
            Purpose.INTAKE -> ScriptedLlm.ok(meals.removeFirst())
            Purpose.NUDGE -> ScriptedLlm.ok("""{"text":"做完了就去睡。"}""")
            Purpose.JUDGE -> ScriptedLlm.ok("""{"violation":false,"class":"none","reason":""}""")
            else -> error("unexpected ${r.purpose}")
        }
    }
    private var n = 0
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }

    init {
        runBlocking {
            store.profile.put(Profile(joinDate = "2026-11-01", onboardingDone = true))
            val r = app.agency.core.PracticeOff.ROLLOUT.copy(openedOn = mapOf("4" to "2026-12-07"))
            store.rules.putSection(PlanSectionKey.ROLLOUT, PlanSpecs.JSON.encodeToString(RolloutSpec.serializer(), r), PlanSection.BY_OWNER_SETTINGS, 0)
        }
    }

    private fun at(hhmm: String) {
        clock.now = saturday.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    }
    private fun say(hhmm: String, text: String): List<String> {
        at(hhmm)
        return runBlocking { brain.onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }
    private fun item(name: String, high: Int, protein: Int) =
        """{"name":"$name","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":${high - 50},"kcal_high":$high,"protein_low":$protein,"protein_high":$protein}"""
    private fun meal(hhmm: String, text: String, vararg items: String): List<String> {
        meals += """{"kind":"meal","items":[${items.joinToString(",")}]}"""
        return say(hhmm, text)
    }
    private fun tick(hhmm: String) = runBlocking { at(hhmm); brain.tick() }

    @Test fun aSaturdayUnderTheNewRules() {
        tick("07:00")
        // Tonight's plan: bed 15 minutes earlier (its first week), the block after 收工 with the cardio.
        val plan = runBlocking { brain.nerve.plan(saturday) }
        assertEquals("03:45", plan.slot(SlotKey.SIGN_OUT)!!.time)
        assertEquals(listOf("02:40", "02:50", "03:10", "03:25"), listOf(SlotKey.CORE, SlotKey.STRENGTH, SlotKey.CARDIO, SlotKey.STRETCH).map { plan.slot(it)!!.time })
        assertEquals("17:35", plan.slot(SlotKey.DINNER)!!.time)
        say("11:30", "簽到")
        say("11:31", "4")
        assertEquals(listOf("記下：今天下雨。出門前提醒你。"), say("11:40", "今天下雨"))
        // The first meal may be up to the cap: 690 fits; dinner keeps its usual size (1700 − 690 − 310 = 700).
        val first = meal("13:05", "第一餐吃了給力盒子大厚切烤鮭魚", item("給力盒子 大厚切烤鮭魚", 720, 44)).first()
        assertTrue(first, first.startsWith("記在第一餐。") && first.contains("690 大卡（食物表）"))
        assertTrue(first.lines().none { it.startsWith("因為今天到現在吃了") })
        runBlocking { at("16:50"); brain.walkDone() }
        meal("17:40", "晚餐吃了雞腿便當", item("雞腿便當", 545, 35))
        // Before the run: no core (sore legs ride badly).
        assertEquals(listOf(TrainingDesk.BEFORE_RIDE), say("17:50", "核心完成"))
        // 18:30 the rain warning before the latest departure; 19:00 not out yet: the chase.
        assertTrue(tick("18:30").posts.any { it.text == Links.L28Weather.RAIN })
        val late = tick("19:00").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals(Links.L12Chase.RIDE_LATE, late.text)
        say("19:10", "出門跑單")
        // 21:05 the 21:00 swap: the same protein, counted in the day, no breach.
        val swap = meal("21:05", "吃了福樂蛋白飲和茶葉蛋", item("福樂蛋白飲", 230, 18), item("茶葉蛋", 90, 6)).first()
        assertTrue(swap, swap.startsWith("記在21:00 那份蛋白質。"))
        assertTrue(runBlocking { store.meals.onDay(saturday.toString()) }.all { it.breaches.isEmpty() })
        // Sleepy on the run: no riding.
        assertEquals(listOf(Links.L22TiredRiding.SLEEPY), say("21:30", "好睏"))
        // The day's income (no target set yet: his own number, by command).
        assertTrue(say("23:59", "外送 1100 元").single().startsWith("記下：今天外送 1100 元。這週到現在 1100 元。外送週目標還沒設"))
        // 02:25 the home-time warning reaches him on the run (the block's latest start is 02:40 tonight).
        val home = tick("02:25").posts.single { it.key == "ride-home" }
        assertEquals("收工時間到了：02:40 前回家開始訓練，最晚 03:45 簽退。單少也不要拖，睡眠不能少。", home.text)
        assertEquals(listOf("收工了。放下安全帽，接著訓練：核心、肌力、體能、伸展。"), say("02:30", "收工"))
        runBlocking {
            at("02:38"); brain.coreDone(420)
            at("02:50"); brain.strengthDone(StrengthReport(3, mapOf("push_up" to 15, "squat" to 20, "plank" to 60), extra = 2, seconds = 900))
            at("03:03"); brain.cardioDone(590)
            at("03:15"); brain.stretchDone(600)
        }
        assertEquals(listOf(TrainingKind.WALK, TrainingKind.CORE7, TrainingKind.STRENGTH, TrainingKind.CARDIO, TrainingKind.STRETCH), runBlocking { store.training.onDay(saturday.toString()) }.map { it.kind })
        // 03:50 is after tonight's 03:45: a breach (only after the time).
        val out = say("03:50", "簽退")
        assertTrue(out.toString(), out.contains("記一次違規：超過最晚簽退的時間（03:45）。今天第 1 次，會算進評鑑。"))
        val st = runBlocking { brain.nerve.state(saturday) }
        assertEquals(1, st.breaches)
        assertEquals(1574, st.kcal)
        // The caffeine reminder (20:15) came while he rode and had nothing left to say by 20:30: never posted.
        val nudges = runBlocking { store.nudges.between(0, Long.MAX_VALUE) }.map { it.text }
        assertTrue(nudges.none { it == Links.L29Caffeine.LINE })
        assertTrue(nudges.contains(Links.L28Weather.RAIN) && nudges.contains(Links.L12Chase.RIDE_LATE))
        assertEquals(NotifyKind.WARNING, Links.L28Weather.let { NotifyKind.WARNING })
        assertTrue(IncomeDesk.SLEEP_FIRST.contains("睡眠不能因為補單變少"))
        // The model sorted his three meals only.
        assertEquals(3, requests.count { it.purpose == Purpose.INTAKE })
    }
}
