package app.agency.core.scenario

import app.agency.core.brain.Brain
import app.agency.core.engine.DayState
import app.agency.core.engine.Fire
import app.agency.core.engine.FireKind
import app.agency.core.engine.Nudger
import app.agency.core.engine.PostChannel
import app.agency.core.engine.Tick
import app.agency.core.engine.IllKind
import app.agency.core.engine.Signal
import app.agency.core.llm.ProviderChoice
import app.agency.core.metering.MutableClock
import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.Message
import app.agency.core.model.ProposalStatus
import app.agency.core.model.Who
import app.agency.core.schedule.DayClock
import app.agency.core.schedule.DayFlag
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.Reduction
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

/**
 * 整天情境 (agency-links §4): one invented Monday (a class day) played through the brain in order: he wakes late, shaves, has
 * a fever and says yes to the today-only change, misses lunch (the alarm's push and the 30-minute chase), rides (one sound),
 * eats the on-run portion, gets home under the floor (the held reminder goes out) and signs out (quiet until tomorrow). At the end: the day state, the plan, the proposal, the reminders and the lines. Later batches add
 * meals with photos, water, training and the bedtime review to this same day, and the emulator plays it last.
 */
class WholeDayScenarioTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private val llm = ScriptedLlm() // no model call is expected all day
    private var n = 0
    private val brain = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private val monday = LocalDate.parse("2026-12-07")

    init {
        // 入社建檔 is done (reminders start after it).
        runBlocking { store.profile.put(app.agency.core.model.Profile(onboardingDone = true)) }
    }

    private fun t(hhmm: String): Long = monday.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant().toEpochMilli()
    private fun at(hhmm: String) { clock.now = Instant.ofEpochMilli(t(hhmm)) }

    /** He types [text] at [hhmm]; the company's lines back. */
    private fun say(hhmm: String, text: String): List<String> {
        at(hhmm)
        return runBlocking { brain.onText(text) }.filter { it.who == Who.COMPANY }.map { it.text }
    }

    private fun signal(hhmm: String, s: (Long) -> Signal) = runBlocking { at(hhmm); brain.signal(s(t(hhmm))) }
    private fun state(): DayState = runBlocking { brain.nerve.state(monday) }
    private fun plan(): DayPlan = runBlocking { brain.nerve.plan(monday) }
    private fun effects() = runBlocking { store.nerve.effects(monday.toString()) }
    private fun tick(hhmm: String): Tick = runBlocking { at(hhmm); brain.tick() }

    @Test fun aClassDayWithALateWakeAFeverARideAndAnEarlyNight() {
        // 07:00 the morning alarm: the reminders are running (nothing is due yet).
        assertTrue(tick("07:00").posts.isEmpty())
        // 10:00, 90 minutes late: lunch follows (13:40), sleep stays; shaving is due at sign-in; the day starts (17:40 water).
        val wake = say("10:00", "簽到")
        assertEquals("簽到了，比表定晚 90 分鐘。", wake.first())
        assertEquals("13:40", plan().slot(SlotKey.LUNCH)!!.time)
        assertEquals("02:30", plan().slot(SlotKey.SIGN_OUT)!!.time)
        assertEquals(listOf("記下：刮鬍子。"), say("10:05", "刮好了"))

        // 11:00 a fever: one today-only proposal (code decides); 11:01 his 「好」 applies it to today only.
        val fever = say("11:00", "我好像發燒了")
        assertTrue(fever.single(), fever.single().startsWith("先照顧身體。發燒的話"))
        assertEquals(1, runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.size)
        assertEquals(listOf("好，只改今天：今天核心和肌力先停，伸展照做十分鐘。不用明天補做，好好休息。"), say("11:01", "好"))
        assertEquals(Reduction.SICK, plan().reduction)
        assertNull(plan().slot(SlotKey.STRENGTH))
        assertNull(plan().slot(SlotKey.CORE))
        assertTrue(plan().slot(SlotKey.STRETCH)!!.required)
        // Asking to skip the stretch as well: the stretch is the minimum of a sick day.
        assertEquals(listOf("今天已經調整過了：伸展照做，這是最少的。"), say("12:00", "伸展也可以不要做嗎"))

        // The alarm fires (T13): what was due goes out (the shave reminder), lunch is pushed at 13:40 and chased every
        // 30 minutes; a fire in between posts nothing; the same minute twice counts once.
        val shave = tick("10:06")
        assertEquals(listOf("刮鬍子（每兩天一次），刮好說「刮好了」。"), shave.posts.map { it.text })
        assertEquals(Fire(Instant.ofEpochMilli(t("13:40")), FireKind.SLOT), shave.next)
        val push = tick("13:40").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("午餐時間到了，做完回報。", push.text)
        assertEquals(PostChannel.SCHEDULE, push.channel)
        assertTrue(push.alert)
        assertTrue(tick("13:55").posts.isEmpty())
        assertEquals("還缺：午餐。", tick("14:10").posts.single().text)
        assertTrue(tick("14:10").posts.isEmpty())

        // 17:40 the water reminder before the ride; dinner (17:15) is missing too by then.
        val pre = tick("17:40")
        assertEquals(setOf("preride", Nudger.GAP_KEY), pre.posts.map { it.key }.toSet())
        assertEquals("還缺：午餐、晚餐。", pre.posts.single { it.key == Nudger.GAP_KEY }.text)

        // 18:05 out on the run: reminders sound once; awake 8 hours after a night we do not know: no tired-riding warning.
        val out = say("18:05", "出門跑單")
        assertEquals(listOf("出門了。騎車小心，提醒只響一聲。21:00 記得吃雞胸。"), out)
        assertTrue(state().riding)
        assertTrue(DayFlag.RIDING_ONE_SOUND in state().flags)
        val ride = tick("21:00").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals(PostChannel.RIDING, ride.channel)
        assertTrue(ride.alert)
        // 21:05 the on-run portion (the meal feature writes the record and emits the signal from T14 on).
        runBlocking { store.meals.insert(app.agency.core.model.Meal("meal-run", monday.toString(), t("21:05"), slotKey = "RIDE_MEAL", text = "雞胸")) }
        signal("21:05") { Signal.MealLogged("meal-run", it, SlotKey.RIDE_MEAL, 310, 40.0) }
        val silent = tick("21:35").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("還缺：午餐、晚餐。", silent.text)
        assertFalse(silent.alert) // the second push while riding is silent

        // 23:30 home: no 宵夜 tonight (L9), then the floor check (L6): 310 is under 1200.
        assertEquals(listOf("收工了。"), say("23:30", "收工"))
        assertTrue(DayFlag.LATE_SNACK_OFF in state().flags)
        assertFalse(state().riding)

        // Home: the floor reminder goes out now (it was held during the ride).
        assertTrue(tick("23:31").posts.any { it.key == "floor" && it.text == "今天才吃 310 大卡，不到 1200。要吃夠，不准少吃；吃了拍照回報。" })

        // 01:00 to bed early (fine): signed out; nothing more until tomorrow's wake slot.
        assertEquals(listOf("簽退了。晚安，明天見。"), say("01:00", "我要睡了"))
        val night = tick("01:30")
        assertTrue(night.posts.isEmpty())
        assertEquals(Fire(LocalDate.parse("2026-12-08").atStartOfDay(AgencyClock.TAIPEI).plusHours(8).plusMinutes(30).toInstant(), FireKind.SLOT), night.next)

        // The end of the day.
        val st = state()
        assertEquals(t("10:00"), st.wokeAt)
        assertEquals(t("01:00"), st.signedOutAt)
        assertEquals(IllKind.SICK, st.ill)
        assertTrue(st.gentle)
        assertEquals(310, st.kcal)
        val byLink = effects().groupBy { it.link }
        assertEquals(setOf("L1", "L2", "L3", "L3b", "L8", "L9", "L6", "L12", "L17"), byLink.keys)
        assertTrue(effects().none { it.kind == EffectKind.NOTIFY && it.status == EffectStatus.PENDING })
        val nudges = runBlocking { store.nudges.between(0, Long.MAX_VALUE) }.map { it.text }
        assertEquals(
            listOf(
                "刮鬍子（每兩天一次），刮好說「刮好了」。", "午餐時間到了，做完回報。", "還缺：午餐。", "還缺：午餐、晚餐。",
                "快出門了：出門前先喝水，路上只能小口喝。", "還缺：午餐、晚餐、跑單途中的雞胸。", "還缺：午餐、晚餐。",
                "還缺：午餐、晚餐。", // 23:31: still chased (home now, so it sounds again)
                "今天才吃 310 大卡，不到 1200。要吃夠，不准少吃；吃了拍照回報。",
            ),
            nudges,
        )
        assertEquals(1, runBlocking { store.proposals.withStatus(ProposalStatus.ACCEPTED) }.size)
        assertTrue(runBlocking { store.proposals.withStatus(ProposalStatus.PENDING) }.isEmpty())
        // 公司規章 were never touched, and no model was asked all day.
        assertNull(runBlocking { store.rules.current() })
        assertTrue(runBlocking { store.rules.history() }.isEmpty())
        assertTrue(llm.requests.isEmpty())
        val lines = runBlocking { store.messages.recent(100) }.filter { it.who == Who.COMPANY }.map(Message::text)
        assertTrue(lines.any { it.startsWith("先照顧身體。發燒的話，今天的訓練我提一個調整：今天核心和肌力先停，伸展照做十分鐘。你說「好」才改") })
        // Tomorrow is a normal day again.
        assertTrue(runBlocking { brain.nerve.plan(monday.plusDays(1)) }.slot(SlotKey.STRENGTH) != null)
    }
}
