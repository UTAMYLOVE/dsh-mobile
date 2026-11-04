package app.dshmobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.dshmobile.chat.ChatItem
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.Tags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class ChatDataPanelScrollTest {
    @get:Rule val rule = createComposeRule()

    /** Owner, 2026-11-04: with 數據 open the chat could not scroll at all; the messages must keep their space. */
    @Test fun anOpenDataPanelLeavesTheMessagesRoomAndScrollsItself() {
        val items = (1..40).flatMap { listOf(ChatItem.User("u$it", "問題 $it", emptyList()), ChatItem.Assistant("a$it", it.toLong(), null, "回答 $it\n\n" + "內容 ".repeat(40))) }
        val ui = ChatUiCache().apply { dataOpen = true }
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ChatScreen(
                    state = ChatHarness.chatState(items), onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {},
                    onLoadOlder = {}, onRetryFollow = {}, ui = ui,
                    dataPanel = { Box(Modifier.fillMaxWidth().height(2000.dp)) },
                )
            }
        }
        rule.waitForIdle()
        val panel = rule.onNodeWithTag(Tags.CHAT_DATA_SCROLL).getBoundsInRoot()
        val list = rule.onNodeWithTag(Tags.CHAT_MESSAGES).getBoundsInRoot()
        assertTrue("panel ${panel.bottom - panel.top} must stay under 46% of 915dp", (panel.bottom - panel.top).value <= 915 * .46f)
        assertTrue("the message list keeps room: ${list.bottom - list.top}", (list.bottom - list.top).value >= 200f)
        rule.onNodeWithTag(Tags.CHAT_DATA_SCROLL).performTouchInput { swipeUp() }
        rule.onNodeWithTag(Tags.CHAT_MESSAGES).performTouchInput { swipeUp() }
    }
}
