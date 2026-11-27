package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.handlers.StrengthReport
import app.agency.core.llm.Fail
import app.agency.core.metering.Provider
import app.agency.core.model.Baseline
import app.agency.core.model.Profile
import app.agency.core.model.ProposalStatus
import app.agency.core.model.TrainingKind
import app.agency.core.model.Who
import app.agency.core.time.AgencyClock
import app.agency.core.schedule.SlotKey
import app.dshmobile.ui.kit.MemberColour
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.JvmFakeModel
import app.dshmobile.agency.AgencyTestKit.Companion.tapTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.StartState
import app.dshmobile.agency.TestOverrides
import app.dshmobile.ui.agency.meal.MealTags
import app.dshmobile.ui.agency.sign.SignTags
import app.dshmobile.ui.agency.training.TrainingTags
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The company's clock in this test: the JVM's own until [fixed] is set. */
class TestClock : java.time.Clock() {
    @Volatile var fixed: Instant? = null
    override fun getZone(): ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): java.time.Clock = this
    override fun instant(): Instant = fixed ?: Instant.now()
}

/**
 * The emulator's whole-day test (AgencyE2eTest.e15) on Robolectric, on the emulator's small screen: every rollout week open, an
 * invented Saturday on a test clock, the fake model on 127.0.0.1. Runs with the unit tests, so a layout or flow break shows
 * before the emulator job. Every number and word is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class WholeDayScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("whole-day").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private val fake = JvmFakeModel().also { it.start() }
    private val clock = TestClock()
    private val shot = ByteArray(5000) { (it * 13 % 241).toByte() }

    private fun open() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-e2e-fake-key-0123456789"))
        val o = kit.overrides(modelBase = fake.base)
        AgencyApp.testOverrides = TestOverrides(o.modelBase, o.openDatabase, o.mediaVault, o.store, camera = { f -> f.writeBytes(shot); true }, rollout = o.rollout, clock = AgencyClock.of(clock))
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME, 30_000)
    }
    private fun ready() = app.agency.start.value as StartState.Ready
    private fun tap(tag: String) = rule.tapTag(tag, 30_000)
    private fun say(t: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(AgencyTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
        val before = runBlocking { ready().store.messages.recent(80) }.count { it.who == Who.OWNER && it.text == t }
        repeat(3) {
            rule.onNodeWithTag(AgencyTags.COMPOSER).performTextReplacement(t)
            rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag(AgencyTags.SEND).assertIsEnabled() }.isSuccess }
            rule.onNodeWithTag(AgencyTags.SEND).performClick()
            if (runCatching { rule.waitUntil(10_000) { runBlocking { ready().store.messages.recent(80) }.count { it.who == Who.OWNER && it.text == t } > before } }.isSuccess) return
        }
        throw AssertionError("not sent: $t")
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        fake.shutdown()
        tmp.deleteRecursively()
    }

    @Test fun aWholeDayAcrossTheFeatures() {
        try { wholeDay() } catch (e: Throwable) {
            val msgs = runCatching { runBlocking { ready().store.messages.recent(60) } }.getOrDefault(emptyList())
            throw AssertionError("lines at the end:\n" + msgs.joinToString("\n") { "${it.who} ${it.kind} unsent=${it.unsent} ${it.text.take(200)}" }, e)
        }
    }

    /**
     * 整天情境 on the phone, every rollout week open (week 4): an invented Saturday (no class, a delivery day) on the company's
     * test clock. 簽到 with 生病 on the score picker (L2's today-only proposal; he keeps the plan), the first meal reported in
     * words and then photographed, his line kept as 「沒送出」 while offline and answered after 重送, dinner, 出門跑單, 收工,
     * the core on its timer and the rest of the block reported, and a 簽退 after tonight's latest time: one breach (the bedtime review sheet is e13's).
     */
    fun wholeDay() {
        val day = java.time.LocalDate.parse("2026-12-12")
        fun at(hhmm: String) {
            val (h, m) = hhmm.split(":").map { it.toInt() }
            val d = if (h < 5) day.plusDays(1) else day
            clock.fixed = d.atTime(h, m).atZone(AgencyClock.TAIPEI).toInstant()
        }
        fun company() = runBlocking { ready().store.messages.recent(60) }.filter { it.who == Who.COMPANY }.map { it.text }
        fun meal(kcal: Int, protein: Int, name: String) {
            fake.intakeJson = """{"kind":"meal","items":[{"name":"$name","portion":1,"fried":false,"processed":false,"zero_cal":false,"kcal_low":${kcal - 50},"kcal_high":$kcal,"protein_low":$protein,"protein_high":$protein}]}"""
        }
        at("10:50")
        open()
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true, joinDate = "2026-11-01"))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 170.0, weightKg = 78.0, pushupsMax = 18, plankMaxSec = 65, completedAt = 1))
            ready().brain.setIdol("小雨", MemberColour.PRESETS[0].second)
        }
        val plan = runBlocking { ready().brain.nerve.plan(day) }
        val signOutBy = plan.slot(SlotKey.SIGN_OUT)!!.time
        // 11:30 簽到, then the score picker: 3 and 生病. L2 proposes today's change; he keeps the plan (不要).
        at("11:30")
        say("簽到")
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(SignTags.PICKER).fetchSemanticsNodes().isNotEmpty() }
        tap(SignTags.SCORE + "2")
        tap(SignTags.BODY + "1")
        tap(SignTags.SEND)
        rule.waitUntil(30_000) { runBlocking { ready().store.conditions.count() } == 1 }
        rule.waitUntil(30_000) { runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.isNotEmpty() }
        val sick = runBlocking { ready().store.proposals.withStatus(ProposalStatus.PENDING) }.single()
        tap(ProposalTags.NO + sick.id)
        rule.waitUntil(30_000) { runBlocking { ready().store.proposals.get(sick.id) }!!.status == ProposalStatus.REJECTED }
        // 13:05 the first meal in words (「未證實」), then 拍這餐 puts the photo on it.
        at("13:05")
        meal(690, 44, "烤鮭魚便當")
        say("第一餐吃了烤鮭魚便當")
        rule.waitUntil(60_000) { runBlocking { ready().store.meals.count() } == 1 }
        val first = runBlocking { ready().store.meals.between("0000-01-01", "9999-12-31") }.single()
        assertFalse(first.verified)
        tap(MealTags.PHOTO)
        rule.waitUntil(30_000) { runBlocking { ready().store.meals.get(first.id) }!!.verified }
        // 15:00 no network: his line is kept as 「沒送出」; back online, 重送 gets the answer on the same row.
        at("15:00")
        fake.intakeJson = null
        fake.online = false
        say("姐姐在嗎")
        rule.waitUntil(60_000) { runBlocking { ready().store.messages.recent(60) }.any { it.who == Who.OWNER && it.text == "姐姐在嗎" && it.unsent } }
        val unsent = runBlocking { ready().store.messages.recent(60) }.last { it.who == Who.OWNER && it.text == "姐姐在嗎" }
        rule.waitUntil(10_000) { ready().brain.lastFail.value == Fail.OFFLINE }
        fake.online = true
        tap(ChatTags.RESEND + unsent.id)
        rule.waitUntil(60_000) { runBlocking { ready().store.messages.get(unsent.id) }!!.unsent.not() }
        rule.waitUntil(30_000) { company().any { it.contains("現在去喝水。") } }
        // 17:40 dinner.
        at("17:40")
        meal(545, 35, "雞腿便當")
        say("晚餐吃了雞腿便當")
        rule.waitUntil(60_000) { runBlocking { ready().store.meals.count() } == 2 }
        // 19:10 out on the run; 02:30 收工.
        at("19:10")
        tap(AgencyTags.RIDE_START)
        rule.waitUntil(30_000) { runBlocking { ready().brain.nerve.state(day) }.riding }
        at("02:30")
        tap(AgencyTags.RIDE_END)
        rule.waitUntil(30_000) { !runBlocking { ready().brain.nerve.state(day) }.riding }
        // The block after 收工: the core on its timer, the rounds, the cardio and the stretch reported.
        at("02:38")
        tap(TrainingTags.CORE)
        tap(TrainingTags.START)
        repeat(7) { tap(TrainingTags.NEXT) }
        rule.waitUntil(30_000) { runBlocking { ready().store.training.onDay(day.toString()) }.any { it.kind == TrainingKind.CORE7 } }
        runBlocking {
            at("02:50"); ready().brain.strengthDone(StrengthReport(3, mapOf("push_up" to 15, "squat" to 20, "plank" to 60), extra = 2, seconds = 900))
            at("03:03"); ready().brain.cardioDone(590)
            at("03:15"); ready().brain.stretchDone(600)
        }
        val kinds = runBlocking { ready().store.training.onDay(day.toString()) }.map { it.kind }.toSet()
        assertTrue(kinds.toString(), kinds.containsAll(listOf(TrainingKind.CORE7, TrainingKind.STRENGTH, TrainingKind.STRETCH)))
        // 簽退 ten minutes after tonight's latest time: one breach.
        val (h, m) = signOutBy.split(":").map { it.toInt() }
        val late = java.time.LocalTime.of(h, m).plusMinutes(10)
        at("%02d:%02d".format(late.hour, late.minute))
        tap(SignTags.SIGN_OUT)
        rule.waitUntil(30_000) { company().any { it.contains("記一次違規：超過最晚簽退的時間（$signOutBy）") } }
        assertEquals(1, runBlocking { ready().brain.nerve.state(day) }.breaches)
        assertEquals(2, runBlocking { ready().store.meals.count() })
    }
}
