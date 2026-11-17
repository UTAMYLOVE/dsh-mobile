package app.dshmobile.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.LocalLook
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * core C7, the structural-stability guard (§1.2 E3, slots §2.5 rule 7): switching through every theme and back keeps the
 * composition. A `remember` sentinel made once and a `rememberSaveable` counter must survive each switch, at the top of FogTheme
 * and on a page inside the FogShell scene. A slice that branched structurally on the theme (around a page, the dock, a dialog,
 * the intro or anything holding saved state) would make the sentinel again or reset the counter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeStructureTest {
    @get:Rule val rule = createComposeRule()

    private var created = 0
    private var kept: Int = -1
    private var primed = false

    /**
     * Remembers itself once and keeps a saveable number, set to 7 only on the very first composition: a sentinel made again
     * (its state lost) reads 0.
     */
    @Composable
    private fun Sentinel() {
        remember { ++created }
        var saved by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            if (!primed) {
                primed = true
                saved = 7
            }
        }
        kept = saved
        Text("sentinel $saved", Modifier.testTag("sentinel"))
    }

    private fun stepThroughEveryTheme(set: (Theme) -> Unit) {
        val order = Themes.all().filter { !it.classic } + Themes.all().filter { !it.classic }.reversed() + Themes.Classic
        for (t in order) {
            set(t)
            rule.waitForIdle()
            assertEquals("made again after switching to ${t.id} ${t.mode}", 1, created)
            assertEquals("the saved number after switching to ${t.id} ${t.mode}", 7, kept)
        }
    }

    @Test fun fogThemeKeepsTheCompositionThroughEveryTheme() {
        var theme by mutableStateOf(Themes.Classic)
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, false), theme) { Sentinel() }
        }
        rule.waitForIdle()
        assertEquals(1, created)
        assertEquals(7, kept)
        stepThroughEveryTheme { theme = it }
    }

    @Test fun theShellAndItsPageKeepTheirStateThroughEveryTheme() {
        var theme by mutableStateOf(Themes.Classic)
        // Floats on too, so the background's float branch is in the tree under every theme.
        val look = Look(style = BackdropStyle.KAWAII)
        val nav = ShellNav().apply { more = true; tab = ShellTab.OVERVIEW }
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, false), theme) {
                CompositionLocalProvider(LocalLook provides look) {
                    FogShell(
                        status = MainUiState.Ready, connection = MuxState.OPEN, nav = nav,
                        composer = ComposerUi("", {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                        onRetry = {}, onExportErrorLog = {},
                    ) { _ -> Box(Modifier.fillMaxSize().padding(LocalPagePadding.current)) { Sentinel() } }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("sentinel").assertExists()
        assertEquals(1, created)
        assertEquals(7, kept)
        stepThroughEveryTheme { theme = it }
    }

    /** The saveable number really is saved state: it comes back after a save and restore, as across a theme switch it stays. */
    @Test fun theSentinelsNumberIsRealSavedState() {
        val restore = StateRestorationTester(rule)
        var theme by mutableStateOf(Themes.of(ThemeId.WEB_NEON, ColorMode.ORIGINAL))
        restore.setContent { FogTheme(effectsFor(EffectLevel.SAVER, false), theme) { Sentinel() } }
        rule.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("restored, not set again", 7, kept)
        theme = Themes.Classic
        rule.waitForIdle()
        assertEquals(7, kept)
    }
}
