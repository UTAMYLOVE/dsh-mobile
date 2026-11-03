package app.dshmobile.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.LocalPageActive
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B1-M6: the "starting dsh" spinner obeys the effect level like the rings do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatusPanelTest {
    @get:Rule val rule = createComposeRule()

    private fun range(level: EffectLevel, reducedMotion: Boolean = false, pageActive: Boolean = true): ProgressBarRangeInfo {
        rule.setContent {
            FogTheme(effectsFor(level, reducedMotion)) {
                CompositionLocalProvider(LocalPageActive provides pageActive) { StatusPanel(MainUiState.Loading, {}, {}) }
            }
        }
        return rule.onNodeWithTag(Tags.STATUS_SPINNER, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
    }

    @Test fun itSpinsAtFull() = assertEquals(ProgressBarRangeInfo.Indeterminate, range(EffectLevel.FULL))

    @Test fun itStandsStillInSaver() = assertNotEquals(ProgressBarRangeInfo.Indeterminate, range(EffectLevel.SAVER))

    @Test fun itStandsStillWithReducedMotion() = assertNotEquals(ProgressBarRangeInfo.Indeterminate, range(EffectLevel.FULL, reducedMotion = true))

    @Test fun itStandsStillOnAPageThatIsNotCurrent() = assertNotEquals(ProgressBarRangeInfo.Indeterminate, range(EffectLevel.FULL, pageActive = false))
}
