package app.dshmobile.ui.fog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FogComponentsTest {
    @get:Rule val rule = createComposeRule()

    private fun blurIs(v: Boolean) = SemanticsMatcher.expectValue(FogSemantics.Blur, v)
    private fun animatingIs(v: Boolean) = SemanticsMatcher.expectValue(FogSemantics.Animating, v)

    @Composable private fun GlassProbe(effects: Effects, withBackdrop: Boolean) {
        FogTheme(effects) {
            val backdrop = rememberBackdrop()
            CompositionLocalProvider(LocalBackdrop provides if (withBackdrop) backdrop else null) {
                Box(Modifier.size(200.dp)) {
                    if (withBackdrop) FogBackground(backdrop)
                    Box(Modifier.size(100.dp).glass(RoundedCornerShape(22.dp)))
                }
            }
        }
    }

    @Test fun panelShowsTitleEnglishAndMeta() {
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                FogPanel(title = "今日花費", en = "SPEND", meta = "7 日") { Text("body") }
            }
        }
        rule.onNodeWithText("今日花費").assertIsDisplayed()
        rule.onNodeWithText("SPEND").assertIsDisplayed()
        rule.onNodeWithText("7 日").assertIsDisplayed()
        rule.onNodeWithText("body").assertIsDisplayed()
    }

    @Test fun glassBlursOnlyWhenEffectsAllow() {
        rule.setContent { GlassProbe(effectsFor(EffectLevel.FULL, false), withBackdrop = true) }
        rule.onNode(blurIs(true), useUnmergedTree = true).assertExists()
    }

    @Test fun glassDoesNotBlurWhenBalanced() {
        rule.setContent { GlassProbe(effectsFor(EffectLevel.BALANCED, false), withBackdrop = true) }
        rule.onNode(blurIs(false), useUnmergedTree = true).assertExists()
        rule.onNode(blurIs(true), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun glassDoesNotBlurWithoutABackdrop() {
        rule.setContent { GlassProbe(effectsFor(EffectLevel.FULL, false), withBackdrop = false) }
        rule.onNode(blurIs(false), useUnmergedTree = true).assertExists()
        rule.onNode(blurIs(true), useUnmergedTree = true).assertDoesNotExist()
    }

    @Composable private fun BackgroundProbe(effects: Effects, pageActive: Boolean) {
        FogTheme(effects) {
            CompositionLocalProvider(LocalPageActive provides pageActive) {
                FogBackground(rememberBackdrop(), Modifier.fillMaxSize())
            }
        }
    }

    @Test fun backgroundAnimatesOnlyOnTheActivePageWithAmbient() {
        rule.setContent { BackgroundProbe(effectsFor(EffectLevel.FULL, false), pageActive = true) }
        rule.onNode(animatingIs(true), useUnmergedTree = true).assertExists()
    }

    @Test fun backgroundStaysStillOnAnInactivePage() {
        rule.setContent { BackgroundProbe(effectsFor(EffectLevel.FULL, false), pageActive = false) }
        rule.onNode(animatingIs(false), useUnmergedTree = true).assertExists()
    }

    @Test fun backgroundStaysStillInSaver() {
        rule.setContent { BackgroundProbe(effectsFor(EffectLevel.SAVER, false), pageActive = true) }
        rule.onNode(animatingIs(false), useUnmergedTree = true).assertExists()
    }

    @Test fun backgroundStaysStillWithReducedMotion() {
        rule.setContent { BackgroundProbe(effectsFor(EffectLevel.FULL, true), pageActive = true) }
        rule.onNode(animatingIs(false), useUnmergedTree = true).assertExists()
    }

    @Test fun segmentedSelectsAndReports() {
        val picked = mutableListOf<Int>()
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                var selected by remember { mutableIntStateOf(0) }
                Segmented(listOf("全開", "平衡", "省電"), selected, { picked += it; selected = it }, "seg-")
            }
        }
        rule.onNodeWithTag("seg-0").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithTag("seg-2").performClick()
        assertEquals(listOf(2), picked)
        rule.onNodeWithTag("seg-2").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithTag("seg-0").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test fun countUpShowsTheFinalValueWithoutCountUp() {
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, false)) {
                CountUpText(1234.0, { "US$%.2f".format(it) }, TextStyle.Default)
            }
        }
        rule.onNodeWithText("US$1234.00").assertIsDisplayed()
    }
}
