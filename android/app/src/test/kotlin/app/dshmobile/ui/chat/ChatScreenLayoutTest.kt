package app.dshmobile.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.PendingSend
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.WebSource
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.ComposerUi
import app.dshmobile.ui.shell.FogShell
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellTab
import app.dshmobile.ui.shell.Tags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat page inside the real shell at 412 dp portrait, with text measured for real (native graphics): a title far too long
 * for one line, a reply holding a long unbroken URL, a search card with long source titles and a queued bubble. Nothing may
 * leave the screen or sit on another control, and the newest message must end above the dock's fade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val h = ChatHarness()
    private val url = "https://example.com/" + "averyveryverylongpathsegmentwithoutanybreaks".repeat(4) + "?query=" + "x".repeat(40)

    private fun show() {
        h.state = ChatHarness.chatState(
            items = listOf(
                ChatItem.User("e1", "幫我查一下這個網址：$url", emptyList()),
                ChatItem.Tool(
                    "c1", "web_search", "台北 週末 天氣 市集", ToolStatus.DONE,
                    sources = listOf(
                        WebSource(url, "一個標題非常非常長的來源網頁，長到一個晶片絕對放不下".repeat(2)),
                        WebSource("https://b.example.org/x", "台北旅遊網"),
                    ),
                ),
                ChatItem.Tool("c2", "write", "novels/" + "第十二章-林夏離開霧城".repeat(6) + ".md", ToolStatus.DONE, sharePath = "novels/ch12.md"),
                ChatItem.Assistant("e2", 2, "先看看網址", "看這裡：$url\n\n還有 [這個連結]($url) 也是。"),
            ),
            title = "一個非常非常長的對話標題，長到一行絕對放不下".repeat(3),
            models = modelChoices(hasClaudeKey = true, current = "claude-sonnet-5-5"),
            running = true,
            pending = listOf(PendingSend("r1", "下一句", emptyList(), queued = true)),
        )
        val nav = ShellNav().apply { tab = ShellTab.CHAT }
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                FogShell(
                    status = MainUiState.Ready, connection = MuxState.OPEN, nav = nav,
                    composer = ComposerUi("", {}, enabled = true, canSend = false, running = true, onSend = {}, onStop = {}),
                    onRetry = {}, onExportErrorLog = {},
                ) { h.Screen() }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private val dp get() = rule.density.density
    private val screenWidth get() = 412 * dp

    private fun assertOnScreen(what: String, r: Rect) =
        assertTrue("$what $r is inside the 412 dp width", r.left >= -0.5f && r.right <= screenWidth + 0.5f)

    private fun assertApart(a: String, ra: Rect, b: String, rb: Rect) {
        val overlap = ra.left < rb.right - 0.5f && rb.left < ra.right - 0.5f && ra.top < rb.bottom - 0.5f && rb.top < ra.bottom - 0.5f
        assertTrue("$a $ra overlaps $b $rb", !overlap)
    }

    @Test fun at412dpNothingOverflowsOrOverlaps() {
        show()
        val list = bounds(Tags.CHAT_LIST_BUTTON)
        val title = bounds(Tags.CHAT_TITLE)
        val chip = bounds(Tags.MODEL_CHIP)
        listOf("list button" to list, "title" to title, "model chip" to chip).forEach { (n, r) -> assertOnScreen(n, r) }
        assertApart("list button", list, "title", title)
        assertApart("title", title, "model chip", chip)
        assertTrue("the long title stays on one line: ${title.height / dp} dp", title.height < 34 * dp)
        assertTrue("the header sits below the shell header", list.top >= bounds(Tags.SHELL_HEADER).bottom - 0.5f)

        // The reply with the unbroken URL wraps inside its bubble, which is at most 88 % of the column.
        val reply = bounds(Tags.ASSISTANT_BUBBLE)
        assertOnScreen("reply", reply)
        assertTrue("reply width ${reply.width / dp} dp is at most 88 % of the column", reply.width <= (412 - 32) * dp * .88f + 1)
        assertTrue("the URL wraps onto several lines: ${reply.height / dp} dp", reply.height > 80 * dp)

        // The newest message ends above the band where the page fades out before the dock.
        val newest = bounds(Tags.PENDING_BUBBLE + "r1")
        val dock = bounds(Tags.DOCK)
        assertTrue("newest message ends at ${newest.bottom / dp} dp, dock top ${dock.top / dp} dp", newest.bottom <= dock.top - 24 * dp)

        // Older messages, scrolled into view.
        rule.onNodeWithTag(Tags.CHAT_MESSAGES).performScrollToNode(hasTestTag(Tags.USER_BUBBLE))
        rule.waitForIdle()
        val user = bounds(Tags.USER_BUBBLE)
        assertOnScreen("user bubble", user)
        assertTrue("user bubble keeps the right page margin", user.right <= (412 - 16) * dp + 1)
        assertTrue("user bubble is at most 88 % of the column", user.width <= (412 - 32) * dp * .88f + 1)

        rule.onNodeWithTag(Tags.CHAT_MESSAGES).performScrollToNode(hasTestTag(Tags.SEARCH_CARD))
        rule.waitForIdle()
        val card = bounds(Tags.SEARCH_CARD)
        assertOnScreen("search card", card)
        listOf("1", "2").forEach { n ->
            val c = bounds(Tags.SOURCE_CHIP + n)
            assertTrue("chip $n $c stays inside the card $card", c.left >= card.left - 0.5f && c.right <= card.right + 0.5f)
        }
        rule.onNodeWithTag(Tags.CHAT_MESSAGES).performScrollToNode(hasTestTag(Tags.SHARE + "c2"))
        rule.waitForIdle()
        val share = bounds(Tags.SHARE + "c2")
        val tool = bounds(Tags.TOOL_CARD + "c2")
        assertOnScreen("share", share)
        assertTrue("share stays inside its card", share.right <= tool.right + 0.5f && share.bottom <= tool.bottom + 0.5f)
    }

    @Test fun theModelMenuSitsUnderTheHeaderWithoutCoveringIt() {
        show()
        rule.onNodeWithTag(Tags.MODEL_CHIP).performClick()
        rule.waitForIdle()
        val menu = bounds(Tags.MODEL_MENU)
        assertOnScreen("model menu", menu)
        listOf(Tags.CHAT_LIST_BUTTON, Tags.CHAT_TITLE, Tags.MODEL_CHIP).forEach { assertApart(it, bounds(it), "model menu", menu) }
        val options = rule.onAllNodes(SemanticsMatcher("model option") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(Tags.MODEL_OPTION) == true
        }, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("five models with a Claude key: ${options.size}", options.size == 5)
        options.forEachIndexed { i, r ->
            assertOnScreen("option $i", r)
            assertTrue("option $i is a 48 dp target", r.height >= 47.5f * dp)
            options.drop(i + 1).forEach { o -> assertApart("option", r, "option", o) }
        }
        // Inline, not floating: the messages start below the menu.
        assertTrue(bounds(Tags.CHAT_MESSAGES).top >= menu.bottom - 0.5f)
    }
}
