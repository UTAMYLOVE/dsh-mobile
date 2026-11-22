package app.agency.core.engine

import app.agency.core.brain.Brain
import app.agency.core.llm.Fail
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.metering.MutableClock
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.model.WordedBy
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

/** Reminders (arch 1.6, T13, links L8/L12): when the next alarm fires and what one fire posts. Invented Monday. */
class NudgerTest {
    private val store = InMemoryAgencyStore()
    private val clock = MutableClock()
    private var n = 0
    private val monday = LocalDate.parse("2026-12-07")

    init {
        // 入社建檔 is done (reminders start after it).
        runBlocking { store.profile.put(app.agency.core.model.Profile(onboardingDone = true)) }
    }
    private fun brain(llm: ScriptedLlm = ScriptedLlm()) = Brain(store, llm, { ProviderChoice.DEEPSEEK }, AgencyClock.of(clock)) { "id-%05d".format(++n) }
    private fun t(hhmm: String, day: LocalDate = monday) = day.atStartOfDay(AgencyClock.TAIPEI).plusMinutes(DayClock.minute(hhmm).toLong()).toInstant()
    private fun at(hhmm: String, day: LocalDate = monday) { clock.now = t(hhmm, day) }
    private fun tick(b: Brain, hhmm: String, day: LocalDate = monday): Tick { at(hhmm, day); return runBlocking { b.tick() } }
    private fun signIn(b: Brain, hhmm: String) { at(hhmm); runBlocking { b.onText("簽到") } }

    @Test fun theNextFireIsTheEarliestSlotOrTomorrowsWake() {
        val b = brain()
        signIn(b, "08:25")
        at("08:30")
        assertEquals(Fire(t("12:10"), FireKind.SLOT), runBlocking { b.nextFire() })
        // After sign-out nothing until tomorrow's wake slot (Tuesday 08:30), even with gaps and reminders left.
        at("23:00")
        runBlocking { b.onText("我要睡了") }
        assertEquals(Fire(t("08:30", monday.plusDays(1)), FireKind.SLOT), runBlocking { b.nextFire() })
        assertTrue(tick(b, "23:30").posts.isEmpty())
    }

    @Test fun missedSlotsAreMergedIntoOnePushWithOneSound() {
        val b = brain()
        tick(b, "07:00") // the reminders ran this morning
        // Then the phone was off all day: at 18:30 five items are missing, one notification says so.
        val tk = tick(b, "18:30")
        val gap = tk.posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("還缺：起床簽到、午餐、核心七分鐘。還有 2 項。", gap.text)
        assertEquals(listOf(SlotKey.WAKE, SlotKey.LUNCH, SlotKey.CORE, SlotKey.DINNER, SlotKey.RIDE_OUT), gap.gapKeys)
        assertTrue(gap.alert)
        assertEquals(PostChannel.SCHEDULE, gap.channel)
        assertEquals(1, runBlocking { store.nudges.between(0, Long.MAX_VALUE) }.count { it.gapKeys.isNotEmpty() })
        // The water reminder before the ride (17:40) is past and goes out with it.
        assertTrue(tk.posts.any { it.key == "preride" })
        assertEquals(Fire(t("19:00"), FireKind.CHASE), tk.next)
    }

    @Test fun aGapIsChasedEvery30MinutesUntilItIsReported() {
        val b = brain()
        signIn(b, "08:30")
        val push = tick(b, "12:10").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("午餐時間到了，做完回報。", push.text)
        assertTrue(push.word)
        assertTrue(tick(b, "12:20").posts.none { it.key == Nudger.GAP_KEY })
        val chase = tick(b, "12:40").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("還缺：午餐。", chase.text)
        assertFalse(chase.word) // model wording for the first push and every second chase
        assertTrue(tick(b, "13:10").posts.single { it.key == Nudger.GAP_KEY }.word)
        // Reported: the gap is gone and the notification can go.
        runBlocking { store.meals.insert(app.agency.core.model.Meal("lunch", monday.toString(), t("13:15").toEpochMilli(), slotKey = "LUNCH", text = "雞胸便當")) }
        val after = tick(b, "13:40")
        assertTrue(after.gapsCleared)
        assertTrue(after.posts.isEmpty())
        // A chat line only when something new is missing, not at every chase.
        assertEquals(1, runBlocking { store.messages.recent(50) }.count { it.kind == Nudger.KIND && it.text.contains("午餐") })
    }

    @Test fun nothingBeforeOnboardingAndNoSlotFromBeforeTheRemindersStarted() {
        runBlocking { store.profile.put(app.agency.core.model.Profile(onboardingDone = false)) }
        val b = brain()
        val before = tick(b, "13:00")
        assertTrue(before.posts.isEmpty())
        assertNull(before.next)
        runBlocking { store.profile.put(app.agency.core.model.Profile(onboardingDone = true)) }
        // He finished 入社建檔 at 13:00: the wake slot and lunch before it are not chased; dinner at 17:15 is.
        val first = tick(b, "13:05")
        assertTrue(first.posts.none { it.key == Nudger.GAP_KEY })
        // The next fires: the water check at 15:00 (L10, T17), the body teacher's suggestion before dinner (T16, 16:45), then
        // the core at 17:00.
        assertEquals(Fire(t("15:00"), FireKind.NOTE), first.next)
        assertEquals(Fire(t("16:45"), FireKind.NOTE), tick(b, "15:00").next)
        assertEquals(Fire(t("17:00"), FireKind.SLOT), tick(b, "16:45").next)
        assertEquals("核心七分鐘時間到了，做完回報。", tick(b, "17:00").posts.single { it.key == Nudger.GAP_KEY }.text)
    }

    @Test fun yesterdaysGapsAreNeverNotified() {
        val b = brain()
        tick(b, "23:00")
        val next = tick(b, "06:00", monday.plusDays(1))
        assertTrue(next.posts.none { it.key == Nudger.GAP_KEY })
        assertTrue(next.gapsCleared)
    }

    @Test fun ridingSoundsOnceAndHoldsOtherRemindersUntilHome() {
        val b = brain()
        signIn(b, "08:30")
        runBlocking { store.signs.insert(SignLog("x", monday.toString(), t("08:31").toEpochMilli(), SignKind.IN)) }
        at("17:50")
        runBlocking { b.onText("出門跑單") }
        val first = tick(b, "21:00").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals(PostChannel.RIDING, first.channel)
        assertTrue(first.alert)
        val second = tick(b, "21:30").posts.single { it.key == Nudger.GAP_KEY }
        assertFalse(second.alert)
        // A reminder that comes due while riding waits; at 收工 it goes.
        runBlocking { b.signal(Signal.MealLogged("dinner", t("21:31").toEpochMilli(), SlotKey.DINNER, 300, 20.0)) } // L6 under the floor
        assertTrue(tick(b, "21:35").posts.none { it.key == "floor" })
        at("23:30")
        runBlocking { b.onText("收工") }
        assertTrue(tick(b, "23:31").posts.any { it.key == "floor" })
    }

    @Test fun theModelWordsThePushAndTheTemplateStaysWhenItCannot() {
        val llm = ScriptedLlm(ScriptedLlm.ok("""{"text":"午餐時間到了，吃完拍照給我。"}"""), ScriptedLlm.failed(Fail.OFFLINE))
        val b = brain(llm)
        signIn(b, "08:30")
        val push = tick(b, "12:10").posts.single { it.key == Nudger.GAP_KEY }
        assertEquals("午餐時間到了，吃完拍照給我。", runBlocking { b.wordNudge(push.nudgeId) })
        assertEquals(Purpose.NUDGE, llm.requests.single().purpose)
        val row = runBlocking { store.nudges.get(push.nudgeId) }!!
        assertEquals(WordedBy.MODEL, row.wordedBy)
        assertEquals("午餐時間到了，吃完拍照給我。", runBlocking { store.messages.get(Nudger.messageId(push.nudgeId)) }!!.text)
        assertNull(runBlocking { b.wordNudge(push.nudgeId) }) // once
        val chase = tick(b, "13:10").posts.single { it.key == Nudger.GAP_KEY }
        assertNull(runBlocking { b.wordNudge(chase.nudgeId) })
        assertEquals(WordedBy.TEMPLATE, runBlocking { store.nudges.get(chase.nudgeId) }!!.wordedBy)
    }

    @Test fun theDailyWordingCapKeepsTheTemplate() {
        val b = brain(ScriptedLlm())
        at("12:00")
        runBlocking {
            repeat(Nudger.DAILY_WORDING_CAP) { i ->
                store.nudges.insert(app.agency.core.model.Nudge("w$i", t("09:00").toEpochMilli() + i, listOf("WAKE"), "SCHEDULE", null, "起床", WordedBy.MODEL))
            }
            store.nudges.insert(app.agency.core.model.Nudge("next", t("11:00").toEpochMilli(), listOf("LUNCH"), "SCHEDULE", null, "午餐", WordedBy.TEMPLATE))
        }
        assertNull(runBlocking { b.wordNudge("next") }) // no call: the scripted model would throw
    }
}
