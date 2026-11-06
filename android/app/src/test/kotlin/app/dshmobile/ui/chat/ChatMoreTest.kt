package app.dshmobile.ui.chat

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import app.dshmobile.chat.ChatItem
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.Tags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-port", fontScale = 1.3f)
class ChatMoreTest {
    @get:Rule val rule = createComposeRule()

    /** The header's ⋮ opens 加入書架・數據, and 數據 opens the panel (and the ⋮ again closes it). */
    @Test fun moreOpensTheDataPanel() {
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ChatScreen(
                    ChatUiState("t", listOf(ChatItem.User("e1", "hi", emptyList()), ChatItem.Assistant("e2", 2, null, "yo")), null, false, emptyList(), emptyList(), false, null),
                    onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {},
                    dataPanel = { Text("panel") },
                    onAddToShelf = {},
                    toc = listOf(TocEntry(1, "e1", "hi")),
                )
            }
        }
        // the ⋮ must be inside the screen (a click is a touch at its centre)
        val b = rule.onNodeWithTag(Tags.CHAT_MORE).fetchSemanticsNode().boundsInRoot
        val w = rule.onRoot().fetchSemanticsNode().boundsInRoot.right
        org.junit.Assert.assertTrue("⋮ at ${b.right} within $w", b.right <= w + 0.5f)
        rule.onNodeWithTag(Tags.CHAT_MORE).performClick()
        rule.onNodeWithTag(TocTags.OPEN).assertExists()
        rule.onNodeWithTag(Tags.CHAT_DATA).performClick()
        rule.onNodeWithTag(Tags.CHAT_DATA_SCROLL).assertExists()
        rule.onNodeWithTag(Tags.CHAT_MORE).performClick()
        rule.onNodeWithTag(Tags.CHAT_DATA).performClick()
        rule.onNodeWithTag(Tags.CHAT_DATA_SCROLL).assertDoesNotExist()
    }
}
