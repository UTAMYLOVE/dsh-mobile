package app.dshmobile.ui.chat

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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

/** 0.5.0, mockup 1: the long-press sheet of a message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class MessageMenuTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show() {
        val items = listOf(ChatItem.User("e3", "台股呢？", emptyList()), ChatItem.Assistant("e9", 9, null, "加權指數上漲"))
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ChatScreen(
                    state = ChatHarness.chatState(items), onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {},
                    onEdit = { k, t -> calls += "edit $k $t" }, onReanswer = { calls += "reanswer $it" }, onBranch = { calls += "branch $it" },
                    onSpeak = { k, _ -> calls += "speak $k" },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun menuShowsEditOnlyForUserMessages() {
        show()
        rule.onNodeWithTag(Tags.USER_BUBBLE).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithTag(Tags.MSG_REANSWER).assertDoesNotExist()
        rule.onNodeWithTag(Tags.MSG_EDIT).performClick()
        assertEquals(listOf("edit e3 台股呢？"), calls)
        rule.onNodeWithTag(Tags.MSG_MENU).assertDoesNotExist()
    }

    @Test fun aReplyOffersReanswerBranchAndReadAloud() {
        show()
        rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithTag(Tags.MSG_EDIT).assertDoesNotExist()
        rule.onNodeWithTag(Tags.MSG_REANSWER).performClick()
        rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithTag(Tags.MSG_BRANCH).performClick()
        rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithTag(Tags.MSG_SPEAK).performClick()
        assertEquals(listOf("reanswer e9", "branch e9", "speak e9"), calls)
    }

    @Test fun chipTapSendsIt() {
        val sent = mutableListOf<String>()
        val items = listOf(ChatItem.User("e3", "台股呢？", emptyList()), ChatItem.Assistant("e9", 9, null, "加權指數上漲", listOf("外資呢？", "下週呢？")))
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ChatScreen(state = ChatHarness.chatState(items), onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {}, onLoadOlder = {}, onFollowUp = { sent += it })
            }
        }
        rule.onNodeWithTag(Tags.FOLLOWUP_CHIP + 1).performClick()
        assertEquals(listOf("下週呢？"), sent)
    }
}
