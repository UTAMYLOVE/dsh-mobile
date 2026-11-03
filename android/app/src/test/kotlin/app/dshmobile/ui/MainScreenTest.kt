package app.dshmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertTrue
import app.dshmobile.R
import app.dshmobile.ui.theme.SakuraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// SDK 36, not the default 37: Compose UI tests idle through Espresso, which reflects on InputManager.getInstance(), absent from the Android 17 jar.
@Config(sdk = [36])
class MainScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var retries = 0
    private var exports = 0
    private var settings = 0

    private fun str(id: Int) = rule.activity.getString(id)

    private fun show(state: MainUiState) = rule.setContent {
        SakuraTheme {
            MainOverlay(state, onRetry = { retries++ }, onExportErrorLog = { exports++ }, onOpenSettings = { settings++ })
        }
    }

    @Test fun loadingShowsTheProgressText() {
        show(MainUiState.Loading)
        rule.onNodeWithText(str(R.string.main_loading)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.main_retry)).assertDoesNotExist()
    }

    @Test fun errorOffersRetryAndExport() {
        show(MainUiState.Error)
        rule.onNodeWithText(str(R.string.main_error_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.main_retry)).performClick()
        rule.onNodeWithText(str(R.string.main_export_log)).performClick()
        assertEquals(1, retries)
        assertEquals(1, exports)
    }

    @Test fun readyShowsNoScreenOnlyTheSettingsButton() {
        show(MainUiState.Ready)
        rule.onNodeWithText(str(R.string.main_loading)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.main_error_title)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.main_settings_button)).assertIsDisplayed()
    }

    private fun settingsClicks(state: MainUiState) {
        show(state)
        rule.onNodeWithText(str(R.string.main_settings_button)).performClick()
        assertEquals(1, settings)
    }

    @Test fun settingsButtonWhileLoading() = settingsClicks(MainUiState.Loading)
    @Test fun settingsButtonOnError() = settingsClicks(MainUiState.Error)
    @Test fun settingsButtonWhenReady() = settingsClicks(MainUiState.Ready)

    /** Follow-up 1 (lag): when dsh is shown the overlay is only as big as the settings button, not a full-screen layer. */
    @Test fun readyTakesOnlyTheSettingsButtonsSpace() {
        show(MainUiState.Ready)
        val bounds = rule.onNodeWithTag(MAIN_OVERLAY_TAG).getUnclippedBoundsInRoot()
        assertTrue("ready overlay is ${bounds.width} x ${bounds.height}", bounds.width < 64.dp && bounds.height < 64.dp)
    }

    private fun coversTheScreen(state: MainUiState) {
        show(state)
        val bounds = rule.onNodeWithTag(MAIN_OVERLAY_TAG).getUnclippedBoundsInRoot()
        assertTrue("$state overlay is ${bounds.width} x ${bounds.height}", bounds.width > 200.dp && bounds.height > 200.dp)
    }

    @Test fun loadingCoversTheScreen() = coversTheScreen(MainUiState.Loading)
    @Test fun errorCoversTheScreen() = coversTheScreen(MainUiState.Error)
}
