package app.dshmobile.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogMotion
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page-switch sweep line (plan: 2 dp white line with a pink glow, 620 ms, skewed): once per tab change, only with depth transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FogShellSweepTest {
    @get:Rule val rule = createComposeRule()
    private val nav = ShellNav()
    private var level by mutableStateOf(EffectLevel.FULL)
    private var reducedMotion by mutableStateOf(false)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FogTheme(effectsFor(level, reducedMotion)) {
                FogShell(
                    status = MainUiState.Ready, connection = MuxState.OPEN, nav = nav,
                    composer = ComposerUi(text = "", onText = {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                    onRetry = {}, onExportErrorLog = {}, page = { ShellHarness.DefaultPage(it) },
                )
            }
        }
        rule.mainClock.advanceTimeBy(100)
    }

    private fun sweeps(): Int = rule.onAllNodes(hasTestTag(Tags.PAGE_SWEEP), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun select(tab: ShellTab) {
        rule.onNodeWithTag(Tags.TAB + tab.name).performClick()
        rule.mainClock.advanceTimeBy(100)
    }

    @Test fun aTabChangeSweepsOneLineAcrossOnceAtFull() {
        show()
        assertEquals("no sweep when the shell first shows", 0, sweeps())
        select(ShellTab.CHAT)
        assertEquals("one sweep while the page switches", 1, sweeps())
        rule.mainClock.advanceTimeBy(FogMotion.SWEEP_MS.toLong() + 200)
        assertEquals("the sweep ends after ${FogMotion.SWEEP_MS} ms", 0, sweeps())
        select(ShellTab.AGENTS)
        assertEquals("every tab change sweeps again", 1, sweeps())
    }

    @Test fun balancedStillSweeps() {
        level = EffectLevel.BALANCED
        show()
        select(ShellTab.SETTINGS)
        assertEquals(1, sweeps())
    }

    @Test fun saverNeverSweeps() {
        level = EffectLevel.SAVER
        show()
        select(ShellTab.CHAT)
        assertEquals(0, sweeps())
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(0, sweeps())
    }

    @Test fun reducedMotionNeverSweepsEvenAtFull() {
        reducedMotion = true
        show()
        select(ShellTab.CHAT)
        assertEquals(0, sweeps())
    }

    @Test fun switchingToSaverMidSweepRemovesTheLine() {
        show()
        select(ShellTab.CHAT)
        assertEquals(1, sweeps())
        level = EffectLevel.SAVER
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, sweeps())
    }
}
