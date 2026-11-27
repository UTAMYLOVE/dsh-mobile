package app.dshmobile.ui.agency.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.handlers.BaselineSteps
import app.agency.core.brain.handlers.OnboardingStep
import app.agency.core.model.Baseline
import app.agency.core.model.MediaKind
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.agency.TestOverrides
import app.dshmobile.agency.media.AudioRecorderTest
import app.dshmobile.agency.media.PlayerEngine
import app.dshmobile.agency.media.MediaVault
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 入社建檔 on the screen (arch T07), Robolectric with the test camera and a fake microphone: the panel walks the seven items
 * in order, photos and the recording reach only the vault, skips leave 「入社資料缺 N 項」 with 補交, and an open question
 * survives a restart. Every picture, sound and number is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class OnboardingFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("onboarding-flow").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private val shot = ByteArray(5000) { (it * 13 % 241).toByte() }
    private val mic = AudioRecorderTest.FakeMic()
    private val played = mutableListOf<Int>()
    private val speaker = object : PlayerEngine {
        override fun play(file: File, onDone: () -> Unit) { played += file.readBytes().size; onDone() }
        override fun play(bytes: ByteArray, onDone: () -> Unit) { played += bytes.size; onDone() }
        override fun stop() = Unit
    }

    private fun launch() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides().let { o ->
            TestOverrides(
                o.modelBase, o.openDatabase, o.mediaVault, o.store,
                camera = { f -> f.writeBytes(shot); true }, recorder = { mic }, player = { speaker }, rollout = o.rollout,
            )
        }
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready
    private fun join(): Baseline? = runBlocking { ready().store.baselines.get(Baseline.JOIN) }
    private fun waitStep(step: OnboardingStep) = rule.waitForTag(OnboardingTags.STEP + step.name)
    private fun vault() = MediaVault(File(tmp, "media")) { error("unused") }

    private fun type(text: String) {
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput(text)
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
    }

    @Test fun theWholeFileCanBeHandedInAndOpenedAgain() {
        launch()
        rule.waitForText("入社第一件事：建檔")
        waitStep(OnboardingStep.BODY)
        type("身高 186 體重 94")
        for (step in OnboardingStep.PHOTOS) {
            waitStep(step)
            rule.onNodeWithTag(OnboardingTags.TAKE_PHOTO).performClick()
        }
        waitStep(OnboardingStep.RECORDING)
        rule.onNodeWithTag(OnboardingTags.REC_START).performClick()
        rule.waitForTag(OnboardingTags.REC_STOP)
        rule.onNodeWithTag(OnboardingTags.REC_STOP).performClick()
        rule.waitForTag(OnboardingTags.REC_PLAY)
        rule.onNodeWithTag(OnboardingTags.REC_PLAY).performClick()
        assertEquals(listOf(2048), played) // played back from the plaintext before handing in
        rule.onNodeWithTag(OnboardingTags.REC_HAND_IN).performClick()
        waitStep(OnboardingStep.PUSHUPS)
        type("伏地挺身 30 下")
        waitStep(OnboardingStep.PLANK)
        type("棒式 120 秒")
        // The line is written (the conversation may already show the day's later lines below it).
        rule.waitUntil(10_000) { runBlocking { ready().store.messages.recent(20) }.any { it.text.contains("入社建檔完成") } }

        val b = join()!!
        assertNull(b.step)
        assertNotNull(b.completedAt)
        assertEquals(listOf(186.0, 94.0), listOf(b.heightCm, b.weightKg))
        assertEquals(30, b.pushupsMax)
        assertEquals(120, b.plankMaxSec)
        val media = runBlocking { ready().store.media.all() }.associateBy { it.id }
        assertEquals(4, media.size)
        // The photos reached the vault only, as JPEG (re-encoded by the downsizing).
        OnboardingStep.PHOTOS.forEach { step ->
            val id = BaselineSteps.mediaOf(b, step)!!
            assertEquals(MediaKind.PHOTO, media.getValue(id).kind)
            val plain = decrypt(id)
            assertTrue(plain.size > 2 && plain[0] == 0xFF.toByte() && plain[1] == 0xD8.toByte())
            assertEquals(media.getValue(id).sha256, app.agency.mediaVault()!!.digest(id).sha256)
        }
        assertEquals(MediaKind.AUDIO, media.getValue(b.recording!!).kind)
        assertArrayEquals(ByteArray(2048) { 9 }, decrypt(b.recording!!))
        // No plaintext anywhere: the camera and recorder caches are empty, the vault files are not the bytes.
        assertTrue(MediaVault.cameraDir(app).list().orEmpty().isEmpty())
        assertTrue(File(app.cacheDir, "record").list().orEmpty().isEmpty())
        File(tmp, "media").listFiles()!!.forEach { f -> assertFalse(f.readBytes().copyOfRange(0, 64).contentEquals(shot.copyOfRange(0, 64))) }

        // 入社資料 opens what was handed in, and the recording plays again from the vault.
        rule.onNodeWithTag(OnboardingTags.FILE).performClick()
        rule.waitForTag(OnboardingTags.FILE_DIALOG)
        rule.waitForText("伏地挺身 30 下")
        rule.onNodeWithTag(OnboardingTags.FILE_PLAY).performClick()
        // The vault is read on an IO thread and the speaker started on the main one: let the main looper run until it has.
        val until = System.currentTimeMillis() + 5_000
        while (played.size < 2 && System.currentTimeMillis() < until) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        assertEquals(listOf(2048, 2048), played)
    }

    private fun decrypt(id: String): ByteArray = (app.agency.mediaVault() ?: vault()).get(id)

    @Test fun skippedItemsShowAsMissingAndCanBeHandedInLater() {
        launch()
        waitStep(OnboardingStep.BODY)
        repeat(OnboardingStep.entries.size) {
            rule.onNodeWithTag(OnboardingTags.SKIP).performClick()
            rule.waitUntil(5_000) { ready().brain.busy.value.not() }
        }
        rule.waitForTag(OnboardingTags.MISSING)
        rule.waitForText("入社資料缺 7 項")
        rule.onNodeWithTag(OnboardingTags.HAND_IN_LATER).performClick()
        waitStep(OnboardingStep.BODY)
        assertTrue(join()!!.skipped.isEmpty())
    }

    @Test fun anOpenQuestionSurvivesARestart() {
        launch()
        waitStep(OnboardingStep.BODY)
        type("身高 186 體重 94")
        waitStep(OnboardingStep.PHOTO_FRONT)
        scenario!!.close()
        runBlocking { app.agency.stopForTest() }
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        waitStep(OnboardingStep.PHOTO_FRONT)
        // The welcome was said once only.
        val welcomes = runBlocking { ready().store.messages.recent(50) }.count { it.text.contains("入社第一件事") }
        assertEquals(1, welcomes)
    }

    @Test fun thePlankStopwatchAnswersInHisWords() {
        launch()
        waitStep(OnboardingStep.BODY)
        type("身高 186 體重 94")
        repeat(5) {
            rule.onNodeWithTag(OnboardingTags.SKIP).performClick()
            rule.waitUntil(5_000) { ready().brain.busy.value.not() }
        }
        waitStep(OnboardingStep.PLANK)
        rule.onNodeWithTag(OnboardingTags.PLANK_START).performClick()
        // Stopped at once: a hold under five seconds is not offered to hand in, the stopwatch starts over.
        rule.onNodeWithTag(OnboardingTags.PLANK_STOP).performClick()
        rule.onNodeWithTag(OnboardingTags.PLANK_START).performClick()
        assertEquals(OnboardingStep.PLANK, join()!!.let(BaselineSteps::open))
    }
}
