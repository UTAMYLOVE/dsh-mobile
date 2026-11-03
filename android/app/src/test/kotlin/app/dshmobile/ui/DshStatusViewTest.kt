package app.dshmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import app.dshmobile.service.DshFailReason
import app.dshmobile.service.DshFailure
import app.dshmobile.service.DshPhase
import app.dshmobile.service.DshStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshStatusViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var forced = 0
    private var opened = 0

    private fun show(status: DshStatus, now: Long) = compose.setContent {
        DshStatusView(status, onForceRestart = { forced++ }, onOpenAppSettings = { opened++ }, now = { now })
    }

    @Test fun thePhaseSecondsAndWhatItWaitsOnAreShown() {
        show(DshStatus(DshPhase.HEALTH_CHECK, sinceMs = 0, port = 38123), now = 7_000)
        compose.onNodeWithTag(DshStatusTags.PHASE).assertTextContains("檢查健康 · 7 秒 · 等 dsh 回應 port 38123")
        compose.onNodeWithTag(DshStatusTags.SLOW).assertDoesNotExist()
        compose.onNodeWithTag(DshStatusTags.FORCE).assertDoesNotExist()
    }

    @Test fun stoppingCountsTheProcessesLeft() {
        show(DshStatus(DshPhase.STOPPING, sinceMs = 0, remaining = 2), now = 1_000)
        compose.onNodeWithTag(DshStatusTags.PHASE).assertTextContains("停止中 · 1 秒 · 還有 2 個舊程式沒關")
    }

    @Test fun after30SecondsItSaysSlowAndAfter90ItOffersTheHardRestart() {
        show(DshStatus(DshPhase.STARTING, sinceMs = 0), now = 31_000)
        compose.onNodeWithTag(DshStatusTags.SLOW).assertIsDisplayed()
        compose.onNodeWithTag(DshStatusTags.FORCE).assertDoesNotExist()
    }

    @Test fun at90SecondsTheHardRestartIsOffered() {
        show(DshStatus(DshPhase.STARTING, sinceMs = 0), now = 91_000)
        compose.onNodeWithTag(DshStatusTags.FORCE).performClick()
        assertEquals(1, forced)
    }

    @Test fun aFailedStopNamesTheStepAndOffersAndroidsForceStop() {
        show(DshStatus(DshPhase.STOPPED, sinceMs = 0, leftover = 2, failure = DshFailure(DshPhase.STOPPING, DshFailReason.STILL_RUNNING, 2)), now = 0)
        compose.onNodeWithTag(DshStatusTags.FAILURE).assertTextContains("卡在「停止中」", substring = true)
        compose.onNodeWithTag(DshStatusTags.FAILURE).assertTextContains("2 個舊的 dsh 程式關不掉", substring = true)
        compose.onNodeWithTag(DshStatusTags.APP_SETTINGS).performClick()
        assertEquals(1, opened)
    }

    @Test fun aHealthyRunningDshShowsNothing() {
        show(DshStatus(DshPhase.RUNNING, sinceMs = 0), now = 5_000)
        compose.onNodeWithTag(DshStatusTags.PANEL).assertDoesNotExist()
    }
}
