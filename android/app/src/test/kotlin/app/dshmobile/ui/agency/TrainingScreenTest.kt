package app.dshmobile.ui.agency

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.model.Baseline
import app.agency.core.model.Profile
import app.agency.core.model.TrainingKind
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.training.TrainingTags
import app.dshmobile.ui.warroom.PhotoWallTags
import java.io.File
import java.nio.file.Files
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

/**
 * 訓練 on screen (T18): the guided timer shows each move, its time or reps and what comes next, and reports by itself when it
 * is over; the rounds ask for the last round; the day's first big completion puts a polaroid on the wall.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class TrainingScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("training").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        tmp.deleteRecursively()
    }

    private fun ready() = app.agency.start.value as StartState.Ready

    private fun open() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        runBlocking {
            ready().store.profile.put((ready().store.profile.get() ?: Profile()).copy(onboardingDone = true))
            ready().store.baselines.upsert(Baseline(Baseline.JOIN, heightCm = 186.0, weightKg = 94.0, completedAt = 1))
        }
        // A day without a run (the clock is the real one): the block may be done any time (on a delivery day it waits for 收工).
        app.agency.send("今天休息")
        app.agency.send("刮好了")
        // Both answered and the brain idle (a button is disabled while it answers).
        rule.waitUntil(30_000) { runBlocking { ready().store.messages.recent(10) }.any { it.text == "記下：刮鬍子。" } && !ready().brain.busy.value }
        rule.waitForTag(TrainingTags.STRETCH, 30_000)
        rule.waitForIdle()
    }

    private fun logs() = runBlocking { ready().store.training.between("0000-01-01", "9999-12-31") }

    @Test fun theCoreTimerShowsEachMoveAndWhatComesNextAndReportsWhenItIsOver() {
        open()
        rule.onNodeWithTag(TrainingTags.CORE).performScrollTo().performClick()
        rule.waitForTag(TrainingTags.START)
        rule.onNodeWithTag(TrainingTags.START).performClick()
        rule.onNodeWithTag(TrainingTags.STEP).assertTextEquals("半船式")
        rule.onNodeWithTag(TrainingTags.UP_NEXT).assertTextEquals("下一個：半仰臥起坐 60 秒")
        // A timed move can be paused and resumed.
        rule.onNodeWithTag(TrainingTags.PAUSE).performClick()
        rule.onNodeWithTag(TrainingTags.RESUME).performClick()
        val names = mutableListOf<String>()
        repeat(7) { i ->
            if (i > 0) names += rule.onNodeWithTag(TrainingTags.STEP).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
            rule.onNodeWithTag(TrainingTags.NEXT).performClick()
        }
        assertEquals(listOf("半仰臥起坐", "抱膝", "單車捲腹", "進階抬腿", "側棒式（左）", "側棒式（右）"), names)
        rule.waitUntil(10_000) { logs().any { it.kind == TrainingKind.CORE7 } }
        // Skipped through in seconds: recorded, but no proof.
        assertFalse(logs().single().verified)
        rule.waitForText("記下：核心七分鐘（計時只跑了")
        rule.waitForText("姐姐都看到了喔")
        // The polaroid of the day is on the wall in the war room.
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.WAR_ROOM.name).performClick()
        rule.waitForTag(PhotoWallTags.ITEM + logs().single().day)
    }

    @Test fun theRoundsAskForTheLastRoundAndRecordIt() {
        open()
        rule.onNodeWithTag(TrainingTags.STRENGTH).performScrollTo().performClick()
        rule.waitForTag(TrainingTags.START)
        rule.onNodeWithTag(TrainingTags.START).performClick()
        rule.onNodeWithTag(TrainingTags.STEP).assertTextEquals("伏地挺身")
        rule.onNodeWithTag(TrainingTags.TIME).assertTextEquals("15 下")
        rule.onNodeWithTag(TrainingTags.UP_NEXT).assertTextEquals("下一個：休息 20 秒")
        // Three rounds of three moves with the rests between them: 17 steps.
        repeat(17) { rule.onNodeWithTag(TrainingTags.NEXT).performClick() }
        rule.waitForTag(TrainingTags.REPORT)
        rule.onNodeWithTag(TrainingTags.REPS + "push_up").performTextClearance()
        rule.onNodeWithTag(TrainingTags.REPS + "push_up").performTextInput("16")
        rule.onNodeWithTag(TrainingTags.EXTRA + "2").performScrollTo().performClick()
        rule.onNodeWithTag(TrainingTags.SEND).performScrollTo().performClick()
        rule.waitUntil(10_000) { logs().any { it.kind == TrainingKind.STRENGTH } }
        val log = logs().single()
        assertEquals(3, log.rounds)
        assertTrue(log.detailJson!!, log.detailJson!!.contains("\"push_up\":16") && log.detailJson!!.contains("\"extra\":2"))
        rule.waitForText("記下：肌力 3 輪（最後一輪：伏地挺身 16 下、慢速深蹲 20 下、棒式 60 秒；還能多做 2 下")
    }
}
