package app.dshmobile.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectsController
import app.dshmobile.ui.fog.UiPrefs
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogSemantics
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.HudRing
import app.dshmobile.ui.fog.LocalBackdrop
import app.dshmobile.ui.fog.LocalIdle
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What FogShell hands the pages: the backdrop for real blur, which page animates, and when the screen counts as idle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FogShellEffectsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val h = ShellHarness()
    private val prefs = UiPrefs(InMemorySharedPreferences())
    private val controller = EffectsController(prefs)

    /** MainActivity's wiring: the shell's effects come live from the controller. */
    private fun showLive(page: @androidx.compose.runtime.Composable (ShellTab) -> Unit = { ShellHarness.DefaultPage(it) }) = rule.setContent {
        val level by controller.effective.collectAsState()
        FogTheme(effectsFor(level, reducedMotion = false)) {
            FogShell(
                status = MainUiState.Ready, connection = MuxState.OPEN, nav = h.nav,
                composer = ComposerUi(text = "", onText = {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                onRetry = {}, onExportErrorLog = {}, page = page,
            )
        }
    }

    private fun backgroundAnimating() = rule.onNodeWithTag(Tags.SHELL_BACKGROUND, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(FogSemantics.Animating)
    private fun blurFlags() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(FogSemantics.Blur), useUnmergedTree = true).fetchSemanticsNodes().map { it.config[FogSemantics.Blur] }

    private fun ring(tab: ShellTab) = rule.onNodeWithTag("ring-" + tab.name, useUnmergedTree = true)
    private fun animating(tab: ShellTab): Boolean? = ring(tab).fetchSemanticsNode().config.getOrNull(FogSemantics.Animating)

    private fun show() = h.show(rule) { tab ->
        Column {
            HudRing(.5f, 80.dp, Modifier.testTag("ring-" + tab.name)) {}
            Text(if (LocalIdle.current) "idle" else "busy", Modifier.testTag("idle-" + tab.name))
            Text(if (LocalBackdrop.current != null) "backdrop" else "none", Modifier.testTag("backdrop-" + tab.name))
        }
    }

    @Test fun onlyTheCurrentPageAnimates() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(true, animating(ShellTab.OVERVIEW))
        rule.onNodeWithTag(Tags.TAB + ShellTab.CHAT.name).performClick()
        rule.mainClock.advanceTimeBy(100) // mid-transition: both pages are on screen
        assertEquals("the page sliding out is already still", false, animating(ShellTab.OVERVIEW))
        assertEquals(true, animating(ShellTab.CHAT))
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(true, animating(ShellTab.CHAT))
    }


    @Test fun pagesGetTheBackdropSoTheirGlassBlursForReal() {
        show()
        rule.onNodeWithTag("backdrop-OVERVIEW").assertTextEquals("backdrop")
    }

    @Test fun eightQuietSecondsMeanIdleAndATouchEndsIt() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithTag("idle-OVERVIEW").assertTextEquals("busy")
        rule.mainClock.advanceTimeBy(4_000)
        rule.onNodeWithTag("idle-OVERVIEW").assertTextEquals("idle")
        rule.onNodeWithTag("idle-OVERVIEW").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("idle-OVERVIEW").assertTextEquals("busy")
        rule.mainClock.advanceTimeBy(9_000)
        rule.onNodeWithTag("idle-OVERVIEW").assertTextEquals("idle")
    }

    @Test fun degradingTurnsBlurOffInTheShell() {
        showLive()
        rule.waitForIdle()
        assertTrue("the dock's glass blurs at FULL: ${blurFlags()}", blurFlags().let { it.isNotEmpty() && it.all { on -> on } })
        repeat(240) { controller.governor.onFrame(it % 120 < 24) } // two bad windows
        rule.waitForIdle()
        assertEquals(EffectLevel.BALANCED, controller.effective.value)
        assertTrue("no glass blurs after the step down: ${blurFlags()}", blurFlags().let { it.isNotEmpty() && it.none { on -> on } })
    }

    @Test fun effectStrengthAppliesLiveWithoutRebuildingTheShell() {
        showLive()
        rule.waitForIdle()
        assertEquals(true, backgroundAnimating())
        controller.set(EffectLevel.SAVER)
        rule.waitForIdle()
        assertEquals(false, backgroundAnimating())
        assertEquals(EffectLevel.SAVER, prefs.effectLevel)
        controller.set(EffectLevel.FULL)
        rule.waitForIdle()
        assertEquals(true, backgroundAnimating())
    }

    /** The shell follows the lifecycle it is shown in: below STARTED (the activity stopped) nothing animates. */
    @Test fun backgroundAnimationStopsWhenTheActivityStops() {
        class Owner : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val owner = Owner()
        val registry = owner.registry
        rule.runOnUiThread { registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
                    FogShell(
                        status = MainUiState.Ready, connection = MuxState.OPEN, nav = h.nav,
                        composer = ComposerUi(text = "", onText = {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                        onRetry = {}, onExportErrorLog = {}, page = { ShellHarness.DefaultPage(it) },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(true, backgroundAnimating())
        rule.runOnUiThread { registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertEquals(false, backgroundAnimating())
        rule.runOnUiThread { registry.currentState = Lifecycle.State.STARTED }
        rule.waitForIdle()
        assertEquals(true, backgroundAnimating())
    }
}
