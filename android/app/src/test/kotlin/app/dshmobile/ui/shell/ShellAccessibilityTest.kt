package app.dshmobile.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.dshmobile.remote.MuxState
import app.dshmobile.remote.PendingPrompt
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The review's small accessibility and phone-width items (B1-M3, M4, M8, M9, M10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class ShellAccessibilityTest {
    @get:Rule val rule = createComposeRule()
    private val approval = PendingPrompt.Approval("e1", "s1", "write", "c1", "要寫入 notes.md")

    private fun shell(status: MainUiState = MainUiState.Ready, connection: MuxState = MuxState.OPEN, fontScale: Float? = null) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale ?: base.fontScale)) {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = true)) {
                FogShell(
                    status = status, connection = connection, nav = ShellNav().apply { more = true; tab = ShellTab.OVERVIEW },
                    composer = ComposerUi("", {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                    onRetry = {}, onExportErrorLog = {}, reconnectChipDelayMs = 0,
                ) {}
            }
        }
    }

    /** M4: the scrim says nothing to a screen reader (no unlabelled "button") yet still keeps touches from the page under it. */
    @Test fun theScrimIsSilentButStillEatsTouches() {
        var below = 0
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = true)) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("below").clickable { below++ })
                    PromptSheet(approval, "x", 0, false, null, {}, {}, {}, {})
                }
            }
        }
        val unlabelled = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter {
            it.config.getOrNull(SemanticsProperties.TestTag) != "below" &&
                it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() && it.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
        }
        assertEquals("clickable nodes with nothing to read: ${unlabelled.map { it.config }}", 0, unlabelled.size)
        rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 40f)) } // above the card: the scrim
        rule.waitForIdle()
        assertEquals("the scrim let a touch through", 0, below)
    }

    /** M3: a card that opens takes the keyboard's focus away from the composer, so typing cannot go on under it. */
    @Test fun aPromptClearsTheComposersFocus() {
        var prompt by mutableStateOf(false)
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = true)) {
                Box(Modifier.fillMaxSize()) {
                    Column { BasicTextField("", {}, Modifier.size(200.dp, 48.dp).testTag("composer")) }
                    if (prompt) PromptSheet(approval, "x", 0, false, null, {}, {}, {}, {})
                }
            }
        }
        rule.onNodeWithTag("composer").performClick()
        rule.onNodeWithTag("composer").assertIsFocused()
        prompt = true
        rule.waitForIdle()
        rule.onNodeWithTag("composer").assertIsNotFocused()
    }

    /** M8: the tab reads as its Chinese name only; the small English label (which clips at large font sizes) is decoration. */
    @Test fun aTabReadsOnlyItsChineseName() {
        shell()
        for (tab in ShellTab.entries) {
            val texts = rule.onNodeWithTag(Tags.TAB + tab.name).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertEquals(listOf(tab.zh), texts)
        }
    }

    /** M9: at 360 dp and a large font the disabled composer's hint still fits on its line (real text measuring: NATIVE graphics). */
    @Config(qualifiers = "w360dp-h800dp-port")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun theNotReadyHintFitsAt360dpWithALargeFont() {
        shell(status = MainUiState.Loading, fontScale = 1.3f)
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(Tags.COMPOSER_HINT, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        val layout = results.single()
        val fits = layout.lineCount == 1 && !layout.isLineEllipsized(0) && layout.getLineRight(0) <= layout.layoutInput.constraints.maxWidth
        assertTrue("the hint is cut: ${layout.layoutInput.text} (${layout.getLineRight(0)} of ${layout.layoutInput.constraints.maxWidth} px)", fits)
    }

    /** M10: the reconnecting chip is announced when it appears. */
    @Test fun theReconnectingChipIsALiveRegion() {
        shell(connection = MuxState.RECONNECTING)
        rule.waitForIdle()
        val mode = rule.onNodeWithTag(Tags.RECONNECTING).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertTrue("live region: $mode", mode == LiveRegionMode.Polite)
    }
}
