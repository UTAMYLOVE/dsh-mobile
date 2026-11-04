package app.dshmobile.ui.chat

import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.chat.ChatItem
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.Tags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner, 2026-11-04: 「做對話可以複製就好」 — every reply has a 複製 button and any message copies on a long press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class CopyMessageTest {
    @get:Rule val rule = createComposeRule()

    private val reply = "# 台股研究\n\n重點一：**半導體**\n\n- 台積電\n- 聯發科"
    private val long = (1..60).joinToString("\n\n") { "第 $it 段：" + "長回覆內容 ".repeat(20) }

    private fun show(items: List<ChatItem>) {
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ChatScreen(state = ChatHarness.chatState(items), onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {})
            }
        }
        rule.waitForIdle()
    }

    private fun clip(): String {
        val cm = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }

    @Test fun theCopyButtonCopiesTheWholeReplyAsWritten() {
        show(listOf(ChatItem.User("u1", "幫我整理台股", emptyList()), ChatItem.Assistant("a1", 1, null, reply)))
        rule.onNodeWithTag(Tags.COPY_REPLY + "stats:a1").performClick()
        assertEquals(reply, clip())
    }

    @Test fun aLongPressCopiesTheUserMessage() {
        show(listOf(ChatItem.User("u1", "幫我整理台股", emptyList()), ChatItem.Assistant("a1", 1, null, reply)))
        rule.onNodeWithTag(Tags.USER_BUBBLE).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals("幫我整理台股", clip())
    }

    @Test fun aLongPressOnAnyPieceOfALongReplyCopiesAllOfIt() {
        show(listOf(ChatItem.User("u1", "寫長一點", emptyList()), ChatItem.Assistant("a1", 1, null, long)))
        rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(long, clip())
    }
}
