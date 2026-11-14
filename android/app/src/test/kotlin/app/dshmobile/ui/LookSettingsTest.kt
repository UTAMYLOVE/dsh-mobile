package app.dshmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.ui.fog.BackdropStyle
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.LookController
import app.dshmobile.ui.fog.LookStore
import app.dshmobile.ui.theme.SakuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LookSettingsTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun show(): LookController {
        val c = LookController(LookStore(InMemorySharedPreferences()), tmp.newFolder("look"))
        rule.setContent { SakuraTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LookSettings(c) } } }
        return c
    }

    @Test fun choosingTheKawaiiStyleShowsItsSwitchesAndAppliesAtOnce() {
        val c = show()
        rule.onNodeWithTag(LookTags.CHECK + "petals").assertDoesNotExist()
        rule.onNodeWithTag(LookTags.STYLE + "1").performClick()
        assertEquals(BackdropStyle.KAWAII, c.look.value.style)
        rule.onNodeWithTag(LookTags.CHECK + "hearts").performScrollTo().performClick()
        assertFalse(c.look.value.hearts)
    }

    @Test fun pictureSlidersShowOnlyWithAPicture() {
        show()
        rule.onNodeWithTag(LookTags.SLIDER + "six").assertDoesNotExist()
        rule.onNodeWithTag(LookTags.REMOVE).assertDoesNotExist()
        rule.onNodeWithText("選圖片").assertExists()
    }

    @Test fun theIntroIsSwitchedOnNamedAndPreviewed() {
        val c = show()
        rule.onNodeWithTag(LookTags.SWITCH + "intro").performScrollTo().performClick()
        assertTrue(c.look.value.intro.enabled)
        rule.onNodeWithTag(LookTags.FIELD + "intro-name").performScrollTo().performTextInput("小雨")
        assertEquals("小雨", c.look.value.intro.name)
        val tick = c.introTick.value
        rule.onNodeWithTag(LookTags.PREVIEW).performScrollTo().performClick()
        assertEquals(tick + 1, c.introTick.value)
    }

    @Test fun restoreDefaultsBringsEverythingBack() {
        val c = show()
        c.update { it.copy(style = BackdropStyle.KAWAII, amount = 5, intro = it.intro.copy(enabled = true)) }
        rule.onNodeWithTag(LookTags.RESTORE).performScrollTo().performClick()
        assertEquals(Look(), c.look.value)
    }
}
