package app.dshmobile.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class DockAttachmentsTest {
    @get:Rule val rule = createComposeRule()
    private var chips by mutableStateOf(listOf(AttachmentChip("a", "photo.jpg", true), AttachmentChip("b", "report.pdf", false)))
    private var text by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private val removed = mutableListOf<String>()
    private var canSend = false

    private fun show() = rule.setContent {
        FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
            Dock(
                ComposerUi(
                    text = text, onText = { text = it }, enabled = true, canSend = canSend, running = false, onSend = {}, onStop = {},
                    chips = chips, onRemoveChip = { id -> removed += id; chips = chips.filterNot { it.id == id } }, error = error,
                ),
                enabled = true, tab = ShellTab.CHAT, onSelect = {}, onToggleAttachMenu = {},
            )
        }
    }

    @Test fun chipsShowAndCanBeRemoved() {
        show()
        rule.onNodeWithTag(Tags.ATTACHMENT_CHIP + "a").assertExists()
        rule.onNodeWithTag(Tags.ATTACHMENT_CHIP + "b").assertExists()
        // the 24dp cross has a 48dp touch target
        rule.onNodeWithTag(Tags.ATTACHMENT_REMOVE + "a").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag(Tags.ATTACHMENT_REMOVE + "a").performClick()
        rule.waitForIdle()
        assertEquals(listOf("a"), removed)
        rule.onNodeWithTag(Tags.ATTACHMENT_CHIP + "a").assertDoesNotExist()
        rule.onNodeWithTag(Tags.ATTACHMENT_CHIP + "b").assertExists()
    }

    @Test fun anErrorStaysVisibleAndTheTextIsKept() {
        text = "留著的字"; error = "附件上傳失敗，訊息沒有送出（IOException）"
        show()
        rule.onNodeWithTag(Tags.COMPOSER_ERROR).assertTextEquals("附件上傳失敗，訊息沒有送出（IOException）")
        rule.onNodeWithTag(Tags.COMPOSER_INPUT).assertTextEquals("留著的字")
        rule.onNodeWithTag(Tags.ATTACHMENT_CHIP + "a").assertExists()
    }

    @Test fun sendIsEnabledWithOnlyAnAttachment() {
        canSend = true
        show()
        rule.onNodeWithTag(Tags.SEND).assertIsEnabled()
    }
}
