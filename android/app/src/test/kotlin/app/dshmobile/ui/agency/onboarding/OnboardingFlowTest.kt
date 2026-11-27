package app.dshmobile.ui.agency.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performScrollTo
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
 * 入社建檔 as its own page (redesign 03), Robolectric with the test camera and a fake microphone: it opens by itself on a
 * fresh install, every card saves at once (nothing asked in the conversation), photos and the recording reach only the vault,
 * 「還差：…」 names what is missing, 完成建檔 waits until every item is done or skipped, and the page survives a restart.
 * Every picture, sound and number is invented.
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
        // It opens by itself on a fresh install (no tabs while it is open).
        rule.waitForTag(OnboardingTags.SCREEN)
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready
    private fun join(): Baseline? = runBlocking { ready().store.baselines.get(Baseline.JOIN) }
    private fun vault() = MediaVault(File(tmp, "media")) { error("unused") }
    private fun tap(tag: String) {
        rule.waitForTag(tag)
        runCatching { rule.onNodeWithTag(tag).performScrollTo() } // the footer does not scroll
        rule.onNodeWithTag(tag).performClick()
    }

    private fun body(h: String, w: String) {
        rule.onNodeWithTag(OnboardingTags.HEIGHT).performScrollTo().performTextInput(h)
        rule.onNodeWithTag(OnboardingTags.WEIGHT).performScrollTo().performTextInput(w)
        tap(OnboardingTags.BODY_SAVE)
        rule.waitUntil(5_000) { join()?.heightCm != null }
    }

    private fun status(step: OnboardingStep): String =
        rule.onNodeWithTag(OnboardingTags.STATUS + step.name).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")

    @Test fun theWholeFileIsFilledOnItsOwnPageAndOpensAgain() {
        launch()
        val said = runBlocking { ready().store.messages.count() }
        rule.waitForText("已完成 0／7 項")
        body("186", "94")
        rule.waitForText("已完成 1／7 項")
        assertEquals("已完成", status(OnboardingStep.BODY))
        for (step in OnboardingStep.PHOTOS) {
            tap(OnboardingTags.TAKE_PHOTO)
            val got = runCatching { rule.waitUntil(5_000) { join()?.let { BaselineSteps.has(it, step) } == true } }
            assertTrue("notice=${app.agency.mediaNotice.value} log=${app.errorLog.file().takeIf { it.isFile }?.readText()?.takeLast(600)}", got.isSuccess)
        }
        tap(OnboardingTags.REC_START)
        rule.waitForTag(OnboardingTags.REC_STOP)
        tap(OnboardingTags.REC_STOP)
        rule.waitForTag(OnboardingTags.REC_PLAY)
        tap(OnboardingTags.REC_PLAY)
        assertEquals(listOf(2048), played) // played back from the plaintext before handing in
        tap(OnboardingTags.REC_HAND_IN)
        rule.waitUntil(5_000) { join()?.recording != null }
        rule.onNodeWithTag(OnboardingTags.PUSH_FIELD).performScrollTo().performTextInput("30")
        tap(OnboardingTags.PUSH_SAVE)
        rule.waitUntil(5_000) { join()?.pushupsMax == 30 }
        // The footer names what is left; 完成建檔 waits for it.
        rule.waitForText("還差：棒式")
        rule.onNodeWithTag(OnboardingTags.FINISH).assertIsNotEnabled()
        // Nothing was said in the conversation while he filled it in.
        assertEquals(said, runBlocking { ready().store.messages.count() })
        tap(OnboardingTags.SKIP + OnboardingStep.PLANK.name)
        rule.waitUntil(5_000) { OnboardingStep.PLANK.name in join()!!.skipped }
        rule.waitForText("已跳過")
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(OnboardingTags.FINISH).assertIsEnabled() }.isSuccess }
        tap(OnboardingTags.FINISH)
        rule.waitForTag(AgencyTags.HOME)
        rule.waitUntil(10_000) { runBlocking { ready().store.messages.recent(20) }.any { it.text.startsWith("資料收好了，還差：棒式。") } }

        val b = join()!!
        assertNotNull(b.completedAt)
        assertEquals(listOf(186.0, 94.0), listOf(b.heightCm, b.weightKg))
        assertEquals(30, b.pushupsMax)
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
        assertTrue(MediaVault.cameraDir(app).list().orEmpty().isEmpty())
        assertTrue(File(app.cacheDir, "record").list().orEmpty().isEmpty())
        File(tmp, "media").listFiles()!!.forEach { f -> assertFalse(f.readBytes().copyOfRange(0, 64).contentEquals(shot.copyOfRange(0, 64))) }

        // Home says what is still missing by name, and 去補 opens the same page (read mode) with the recording to play.
        rule.waitForTag(OnboardingTags.BANNER)
        rule.waitForText("入社建檔 還差：棒式")
        tap(OnboardingTags.HAND_IN_LATER)
        rule.waitForTag(OnboardingTags.FILE_DIALOG)
        OnboardingStep.PHOTOS.forEach { step ->
            val tag = OnboardingTags.THUMB + BaselineSteps.mediaOf(b, step)!!
            val found = runCatching { rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            assertTrue("no $tag; thumbs: " + rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.TestTag), useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }.filter { it.startsWith("ob-") }, found.isSuccess)
        }
        tap(OnboardingTags.FILE_PLAY)
        val until = System.currentTimeMillis() + 5_000
        while (played.size < 2 && System.currentTimeMillis() < until) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        assertEquals(listOf(2048, 2048), played)
    }

    private fun decrypt(id: String): ByteArray = (app.agency.mediaVault() ?: vault()).get(id)

    @Test fun skippedItemsAreListedByNameAndFinishOpensTheCompany() {
        launch()
        OnboardingStep.entries.filter { it !in OnboardingStep.PHOTOS.drop(1) }.forEach { step ->
            tap(OnboardingTags.SKIP + step.name)
            rule.waitUntil(5_000) { step.name in join()!!.skipped }
        }
        rule.waitForText("還差：身高體重、正面照、側面照、背面照、清唱錄音、伏地挺身、棒式")
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(OnboardingTags.FINISH).assertIsEnabled() }.isSuccess }
        tap(OnboardingTags.FINISH)
        rule.waitForTag(AgencyTags.HOME)
        assertTrue(runBlocking { ready().store.profile.get() }!!.onboardingDone)
        rule.waitForText("入社建檔 還差：")
    }

    @Test fun leavingKeepsWhatWasDoneAndItDoesNotOpenAgainUntilTheNextStart() {
        launch()
        body("186", "94")
        tap(OnboardingTags.LATER)
        rule.waitForTag(AgencyTags.HOME)
        assertFalse(runBlocking { ready().store.profile.get() }?.onboardingDone == true)
        scenario!!.close()
        runBlocking { app.agency.stopForTest() }
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(OnboardingTags.SCREEN)
        rule.waitForText("已完成 1／7 項")
        // The welcome was said once only.
        val welcomes = runBlocking { ready().store.messages.recent(50) }.count { it.text.contains("入社第一件事") }
        assertEquals(1, welcomes)
    }

    @Test fun thePlankStopwatchKeepsAShortHoldOut() {
        launch()
        tap(OnboardingTags.PLANK_START)
        // Stopped at once: a hold under five seconds is not offered to hand in, the stopwatch starts over.
        tap(OnboardingTags.PLANK_STOP)
        rule.onNodeWithTag(OnboardingTags.PLANK_START).assertExists()
        assertNull(join()!!.plankMaxSec)
    }
}
