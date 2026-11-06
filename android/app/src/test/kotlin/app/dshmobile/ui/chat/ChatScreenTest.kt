package app.dshmobile.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.LiveReply
import app.dshmobile.chat.NoticeKind
import app.dshmobile.chat.PendingSend
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.WebSource
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogSemantics
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.LocalBackdrop
import app.dshmobile.ui.fog.rememberBackdrop
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat page's inputs and callbacks, shared by the screen and layout tests. */
internal class ChatHarness {
    var state by mutableStateOf(chatState())
    val links = mutableListOf<String>()
    val shares = mutableListOf<String>()
    val picked = mutableListOf<String>()
    val retried = mutableListOf<String>()
    val dismissed = mutableListOf<String>()
    val efforts = mutableListOf<String>()
    val reviews = mutableListOf<Int>()
    var lists = 0
    var olders = 0
    var follows = 0
    /** What the shell session keeps per conversation; a test may swap it or hide the screen and bring it back. */
    var ui = ChatUiCache()
    var visible by mutableStateOf(true)

    @androidx.compose.runtime.Composable
    fun Screen() = if (visible) ChatScreen(
        state = state,
        onOpenList = { lists++ },
        onPickModel = { picked += it },
        onPickEffort = { efforts += it.toString() },
        onPickReview = { reviews += it },
        onLink = { links += it },
        onShare = { shares += it },
        onLoadOlder = { olders++ },
        onRetryFollow = { follows++ },
        onRetrySend = { retried += it },
        onDismissSend = { dismissed += it },
        ui = ui,
    ) else Unit

    fun show(rule: ComposeContentTestRule) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) { Screen() }
    }

    companion object {
        fun chatState(
            items: List<ChatItem> = emptyList(),
            live: LiveReply? = null,
            running: Boolean = false,
            pending: List<PendingSend> = emptyList(),
            models: List<ModelChoice> = modelChoices(hasClaudeKey = false, current = "deepseek-flash"),
            hasMore: Boolean = false,
            sendError: String? = null,
            title: String = "霧城的天氣",
        ) = ChatUiState(title, items, live, running, pending, models, hasMore, sendError)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class ChatScreenTest {
    @get:Rule val rule = createComposeRule()
    private val h = ChatHarness()

    private fun show(vararg items: ChatItem, configure: ChatHarness.() -> Unit = {}) {
        h.state = ChatHarness.chatState(items.toList())
        h.configure()
        h.show(rule)
        rule.waitForIdle()
    }

    private fun text(tag: String): String = rule.onNodeWithTag(tag).text()

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test fun userAndAssistantBubblesRenderMarkdown() {
        show(
            ChatItem.User("e1", "**台北**週末天氣？", emptyList()),
            ChatItem.Assistant("e2", 2, null, "## 週末\n\n- 週六**晴**\n- 週日有雨"),
        )
        val user = text(Tags.USER_BUBBLE)
        assertTrue(user, user.contains("台北週末天氣？"))
        assertFalse("markdown markers are not shown: $user", user.contains("**"))
        val ai = text(Tags.ASSISTANT_BUBBLE)
        assertTrue(ai, ai.contains("週末") && ai.contains("週六晴") && ai.contains("週日有雨"))
        assertFalse(ai, ai.contains("##") || ai.contains("**"))
    }

    @Test fun liveTextShowsWhileStreaming() {
        show { state = state.copy(live = LiveReply("a1", "", "Hel", emptyList()), running = true) }
        rule.onNodeWithTag(Tags.LIVE_BUBBLE).assertIsDisplayed().assertTextContains("Hel", substring = true)
        h.state = h.state.copy(live = LiveReply("a1", "", "Hello，世界", emptyList()))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.LIVE_BUBBLE).assertTextContains("Hello，世界", substring = true)
    }

    @Test fun thinkingIsCollapsedUntilTapped() {
        show(ChatItem.Assistant("e2", 2, "先想一想明天的天氣", "明天晴"))
        rule.onNodeWithTag(Tags.THINKING).assertIsDisplayed().assertTextContains("思考過程", substring = true)
        assertEquals(0, rule.onAllNodes(hasText("先想一想明天的天氣", substring = true)).fetchSemanticsNodes().size)
        rule.onNodeWithTag(Tags.THINKING).performClick()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(hasText("先想一想明天的天氣", substring = true)).fetchSemanticsNodes().size)
        rule.onNodeWithTag(Tags.THINKING).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasText("先想一想明天的天氣", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun toolCardsShowNameSummaryAndStatus() {
        show(
            ChatItem.Tool("c1", "bash", "ls -la", ToolStatus.RUNNING),
            ChatItem.Tool("c2", "write", "notes.md", ToolStatus.WAITING_APPROVAL),
            ChatItem.Tool("c3", "bash", "rm x", ToolStatus.FAILED, error = "permission denied"),
            ChatItem.Tool("c4", "read", "a.txt", ToolStatus.DONE),
        )
        val card = text(Tags.TOOL_CARD + "c1")
        assertTrue(card, card.contains("bash") && card.contains("ls -la"))
        rule.onNodeWithTag(Tags.TOOL_STATUS + "c1", useUnmergedTree = true).assertTextEquals("執行中")
        rule.onNodeWithTag(Tags.TOOL_STATUS + "c2", useUnmergedTree = true).assertTextEquals("等待確認")
        rule.onNodeWithTag(Tags.TOOL_STATUS + "c3", useUnmergedTree = true).assertTextEquals("失敗")
        rule.onNodeWithTag(Tags.TOOL_STATUS + "c4", useUnmergedTree = true).assertTextEquals("完成")
        assertTrue(text(Tags.TOOL_CARD + "c3").contains("permission denied"))
    }

    private val search = ChatItem.Tool(
        "c9", "web_search", "台北 天氣", ToolStatus.DONE,
        sources = listOf(WebSource("https://example.com/e2e-weather", "E2E Weather"), WebSource("https://news.example.org/a/b", "")),
    )

    @Test fun webSearchShowsTheSourceCardAndChips() {
        show(search)
        rule.onNodeWithTag(Tags.SEARCH_CARD).assertTextContains("搜尋網路 · 2 個來源", substring = true)
        assertEquals(0, rule.onAllNodesWithTag(Tags.TOOL_CARD + "c9").fetchSemanticsNodes().size)
        rule.onNodeWithTag(Tags.SOURCE_CHIP + "1").assertTextEquals("E2E Weather")
        rule.onNodeWithTag(Tags.SOURCE_CHIP + "2").assertTextEquals("news.example.org")
        rule.onNodeWithTag(Tags.SOURCE_CHIP + "1").performClick()
        assertEquals(listOf("https://example.com/e2e-weather"), h.links)
    }

    @Test fun answersCiteTheSourcesTheyLinkTo() {
        show(search, ChatItem.Assistant("e3", 3, null, "根據 [E2E Weather](https://example.com/e2e-weather)，明天晴。"))
        rule.onNodeWithTag(Tags.CITES, useUnmergedTree = true).assertTextEquals("來源 ①")
    }

    @Test fun anAnswerWithoutLinksHasNoCites() {
        show(search, ChatItem.Assistant("e3", 3, null, "明天晴。"))
        assertEquals(0, rule.onAllNodesWithTag(Tags.CITES, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun queuedSendsAreMarked() {
        show(ChatItem.User("e1", "第一句", emptyList())) {
            state = state.copy(running = true, pending = listOf(PendingSend("r1", "下一句", emptyList(), queued = true)))
        }
        rule.onNodeWithTag(Tags.PENDING_BUBBLE + "r1").assertTextContains("下一句", substring = true)
        rule.onNodeWithTag(Tags.QUEUED, useUnmergedTree = true).assertTextEquals("排隊中")
    }

    @Test fun aFailedSendOffersResendAndRemove() {
        show { state = state.copy(pending = listOf(PendingSend("r7", "沒送到", emptyList(), queued = false, failed = true))) }
        assertEquals(0, rule.onAllNodesWithTag(Tags.QUEUED, useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag(Tags.SEND_RETRY + "r7").assertTextContains("重送").performClick()
        rule.onNodeWithTag(Tags.SEND_DISMISS + "r7").assertTextContains("移除").performClick()
        assertEquals(listOf("r7"), h.retried)
        assertEquals(listOf("r7"), h.dismissed)
    }

    @Test fun stoppedNoticeShows() {
        show(ChatItem.User("e1", "寫一首詩", emptyList()), ChatItem.Notice("n1", NoticeKind.STOPPED, "已停止"))
        rule.onNodeWithTag(Tags.NOTICE).assertTextContains("已停止", substring = true)
    }

    @Test fun modelChipListsOnlyAvailableModels() {
        show()
        rule.onNodeWithTag(Tags.MODEL_CHIP).assertTextEquals("DEEPSEEK FLASH")
        assertEquals(0, rule.onAllNodesWithTag(Tags.MODEL_MENU).fetchSemanticsNodes().size)
        rule.onNodeWithTag(Tags.MODEL_CHIP).performClick()
        rule.onNodeWithText("這個對話使用的模型", substring = true).assertExists()
        val options = rule.onAllNodes(hasTestTagPrefix(Tags.MODEL_OPTION)).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertEquals(listOf(Tags.MODEL_OPTION + "deepseek-flash", Tags.MODEL_OPTION + "deepseek-v4-pro"), options)
        rule.onNodeWithTag(Tags.MODEL_OPTION + "deepseek-v4-pro").performClick()
        rule.waitForIdle()
        assertEquals(listOf("deepseek-v4-pro"), h.picked)
        assertEquals("the menu closes", 0, rule.onAllNodesWithTag(Tags.MODEL_MENU).fetchSemanticsNodes().size)
    }

    @Test fun theModelMenuOffersThinkingStrengthAndReviewerRoundsAndTheHeaderSummarisesThem() {
        show()
        rule.onNodeWithTag(Tags.MODEL_CHIP).performClick()
        rule.onNodeWithTag(Tags.EFFORT_OPTION + "max").performClick()
        rule.onNodeWithTag(Tags.REVIEW_OPTION + "2").performClick()
        rule.waitForIdle()
        assertEquals(listOf("max"), h.efforts)
        assertEquals(listOf(2), h.reviews)
        h.state = h.state.copy(effort = "max", reviewRounds = 2)
        rule.waitForIdle()
        // 0.5.0: the tuning moved from under the header to the dock's status line (statusSegments).
        rule.onNodeWithTag(Tags.CHAT_TUNING).assertDoesNotExist()
    }

    @Test fun withAClaudeKeyTheClaudeModelsAreChoicesToo() {
        val choices = modelChoices(hasClaudeKey = true, current = "claude-sonnet-5-5")
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro", "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"), choices.map { it.id })
        assertEquals("claude-sonnet-5-5", choices.single { it.selected }.id)
        show { state = state.copy(models = choices) }
        rule.onNodeWithTag(Tags.MODEL_CHIP).assertTextEquals("CLAUDE SONNET")
    }

    @Test fun shareButtonOnlyForWorkspaceFiles() {
        show(
            ChatItem.Tool("w1", "write", "novels/ch1.md", ToolStatus.DONE, sharePath = "novels/ch1.md"),
            ChatItem.Tool("w2", "write", "/tmp/x.md", ToolStatus.DONE),
        )
        rule.onNodeWithTag(Tags.SHARE + "w1").performClick()
        assertEquals(listOf("novels/ch1.md"), h.shares)
        assertEquals(0, rule.onAllNodesWithTag(Tags.SHARE + "w2").fetchSemanticsNodes().size)
    }

    @Test fun loadOlderShowsWhenThereIsMore() {
        show(ChatItem.User("e9", "最新的一句", emptyList()))
        assertEquals(0, rule.onAllNodesWithTag(Tags.LOAD_OLDER).fetchSemanticsNodes().size)
        h.state = h.state.copy(hasMore = true)
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.LOAD_OLDER).assertTextContains("載入更早的訊息", substring = true).performClick()
        assertEquals(1, h.olders)
    }

    @Test fun aLoadOlderFailureIsAShortNote() {
        show(ChatItem.User("e9", "最新的一句", emptyList())) { state = state.copy(hasMore = true, olderError = "unexpected-answer") }
        val note = text(Tags.OLDER_ERROR)
        assertTrue(note, note.contains("更早的訊息") && note.contains("unexpected-answer"))
    }

    @Test fun aBrokenFollowShowsABannerThatReconnects() {
        show(ChatItem.User("e1", "嗨", emptyList())) { state = state.copy(followError = "ended") }
        rule.onNodeWithTag(Tags.FOLLOW_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(Tags.FOLLOW_RETRY).assertTextContains("重新連線").performClick()
        assertEquals(1, h.follows)
        h.state = h.state.copy(followError = null)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag(Tags.FOLLOW_ERROR).fetchSemanticsNodes().size)
    }

    @Test fun aSendErrorIsShownInWords() {
        show { state = state.copy(sendError = "session/model-unavailable") }
        val note = text(Tags.CHAT_ERROR)
        assertTrue(note, note.contains("session/model-unavailable"))
    }

    @Test fun theListButtonOpensTheList() {
        show()
        rule.onNodeWithTag(Tags.CHAT_LIST_BUTTON).assertTextContains("對話", substring = true).performClick()
        assertEquals(1, h.lists)
        rule.onNodeWithTag(Tags.CHAT_TITLE).assertTextEquals("霧城的天氣")
    }

    /** A 100k-character chapter is many lazy items (one per paragraph), never one huge item. */
    @Test fun aLongReplyIsHostedAsManySmallItems() {
        val chapter = (1..400).joinToString("\n\n") { "第 $it 段。" + "霧從河面升起，林夏走過長長的橋。".repeat(17) }
        assertTrue(chapter.length > 100_000)
        show(ChatItem.User("e1", "寫第 12 章", emptyList()), ChatItem.Assistant("e2", 2, null, chapter))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).fetchSemanticsNodes().size > 1 }
        val parts = rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).fetchSemanticsNodes()
        assertTrue("only the visible paragraphs are composed: ${parts.size}", parts.size in 2..60)
        val screen = rule.onNodeWithTag(Tags.CHAT_MESSAGES).fetchSemanticsNode().boundsInRoot
        parts.forEach { assertTrue("one paragraph is less than a screen: ${it.boundsInRoot}", it.boundsInRoot.height < screen.height) }
        // The newest end of the chapter is what shows first.
        assertTrue(parts.any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("第 400 段") } == true })
    }

    @Test fun aLongParagraphWithoutBreaksIsSplitToo() {
        val wall = "雨".repeat(60_000)
        show(ChatItem.Assistant("e2", 2, null, wall))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).fetchSemanticsNodes().size > 1 }
        val screen = rule.onNodeWithTag(Tags.CHAT_MESSAGES).fetchSemanticsNode().boundsInRoot
        rule.onAllNodesWithTag(Tags.ASSISTANT_BUBBLE).fetchSemanticsNodes().forEach { assertTrue(it.boundsInRoot.height < screen.height) }
    }

    @Test fun theNewestMessageIsInViewAndOlderOnesScrollIn() {
        val items = (1..40).flatMap { listOf(ChatItem.User("u$it", "問題 $it", emptyList()), ChatItem.Assistant("a$it", it.toLong(), null, "回答 $it")) }
        show(*items.toTypedArray())
        rule.onNode(hasText("回答 40", substring = true)).assertIsDisplayed()
        rule.onNodeWithTag(Tags.CHAT_MESSAGES).performScrollToNode(hasText("問題 1"))
        rule.onNode(hasText("問題 1")).assertIsDisplayed()
    }

    private fun hasTestTagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    // ---- fix round 1 ----

    /** I1: messages are glass without blur (a blurred backdrop per bubble would cost every frame); the header keeps its blur. */
    @Test fun bubblesNeverBlurButTheHeaderDoes() {
        h.state = ChatHarness.chatState(listOf(ChatItem.User("e1", "嗨", emptyList()), ChatItem.Assistant("e2", 2, null, "你好")))
            .copy(live = LiveReply("a9", "", "正在寫", emptyList()), running = true)
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = true)) {
                CompositionLocalProvider(LocalBackdrop provides rememberBackdrop()) { h.Screen() }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.CHAT_HEADER).assert(SemanticsMatcher.expectValue(FogSemantics.Blur, true))
        rule.onNodeWithTag(Tags.ASSISTANT_BUBBLE).assert(SemanticsMatcher.expectValue(FogSemantics.Blur, false))
        rule.onNodeWithTag(Tags.LIVE_BUBBLE).assert(SemanticsMatcher.expectValue(FogSemantics.Blur, false))
    }

    private fun conversation(n: Int) =
        (1..n).flatMap { listOf(ChatItem.User("u$it", "問題 $it", emptyList()), ChatItem.Assistant("a$it", it.toLong(), null, "回答 $it")) }

    /** I2 (a): resting at the bottom, a new message comes into view. */
    @Test fun atTheBottomANewRowComesIntoView() {
        show(*conversation(40).toTypedArray())
        h.state = h.state.copy(items = h.state.items + ChatItem.User("u41", "問題 41", emptyList()))
        rule.waitForIdle()
        rule.onNode(hasText("問題 41")).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(Tags.SCROLL_TO_NEWEST).fetchSemanticsNodes().size)
    }

    /** I2 (b): scrolled up, a new message keeps the reading place and offers the ↓ pill, which goes down to it. */
    @Test fun scrolledUpANewRowKeepsThePlaceAndOffersThePill() {
        show(*conversation(40).toTypedArray())
        rule.onNodeWithTag(Tags.CHAT_MESSAGES).performScrollToNode(hasText("問題 5"))
        rule.waitForIdle()
        val before = rule.onNode(hasText("問題 5")).fetchSemanticsNode().boundsInRoot
        assertEquals("nothing new yet", 0, rule.onAllNodesWithTag(Tags.SCROLL_TO_NEWEST).fetchSemanticsNodes().size)
        h.state = h.state.copy(items = h.state.items + ChatItem.User("u41", "問題 41", emptyList()))
        rule.waitForIdle()
        val after = rule.onNode(hasText("問題 5")).fetchSemanticsNode().boundsInRoot
        assertEquals("the place is kept", before.top, after.top, 1f)
        assertEquals(0, rule.onAllNodes(hasText("問題 41")).fetchSemanticsNodes().size)
        val pill = rule.onNodeWithTag(Tags.SCROLL_TO_NEWEST).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("a 48 dp target", pill.height >= 47.5f * rule.density.density && pill.width >= 47.5f * rule.density.density)
        rule.onNodeWithTag(Tags.SCROLL_TO_NEWEST).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("問題 41")).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(Tags.SCROLL_TO_NEWEST).fetchSemanticsNodes().size)
    }

    /** I2 (c): scrolled up, sending a message goes back down to it. */
    @Test fun scrolledUpASendReturnsToTheBottom() {
        show(*conversation(40).toTypedArray())
        rule.onNodeWithTag(Tags.CHAT_MESSAGES).performScrollToNode(hasText("問題 5"))
        rule.waitForIdle()
        h.state = h.state.copy(pending = listOf(PendingSend("r9", "新的一句", emptyList(), queued = false)))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.PENDING_BUBBLE + "r9").assertIsDisplayed()
    }

    // ---- final review B2-I3 / B2-M8 ----

    @Test fun onlyTheNewestMessagesAreParsedInTheCompositionAndTheCacheOutlivesTheScreen() {
        val before = ChatDebug.backgroundParses.get()
        val items = (1..60).map { ChatItem.User("e$it", "第 $it 則 **短訊**", emptyList()) }
        show(*items.toTypedArray())
        rule.waitUntil(5_000) { ChatDebug.backgroundParses.get() - before >= 60 - EAGER_ROWS }
        rule.waitForIdle()
        val parsed = ChatDebug.backgroundParses.get()
        assertTrue("the newest are parsed at once, the rest off the main thread", parsed - before in (60 - EAGER_ROWS)..59)
        // Leaving the conversation and coming back parses nothing again.
        h.visible = false
        rule.waitForIdle()
        h.visible = true
        rule.waitForIdle()
        Thread.sleep(100)
        rule.waitForIdle()
        assertEquals(parsed, ChatDebug.backgroundParses.get())
        rule.onNodeWithTag(Tags.CHAT_MESSAGES).assertExists()
    }

    @Test fun openThinkingStaysOpenWhenTheReplyFinishes() {
        show { state = state.copy(live = LiveReply("a1", "先想一想", "答", emptyList()), running = true) }
        rule.onNodeWithTag(Tags.THINKING).performClick()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(hasText("先想一想", substring = true)).fetchSemanticsNodes().size)
        h.state = h.state.copy(live = null, running = false, items = listOf(ChatItem.Assistant("e5", 5, "先想一想", "答")))
        rule.waitForIdle()
        assertEquals("still open", 1, rule.onAllNodes(hasText("先想一想", substring = true)).fetchSemanticsNodes().size)
    }
}
