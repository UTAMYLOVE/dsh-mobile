package app.dshmobile.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.dshmobile.remote.MuxState
import app.dshmobile.ui.MainUiState
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared by the shell tests: one FogShell with every input in a mutable state, and counters for the callbacks. */
internal class ShellHarness {
    var status by mutableStateOf(MainUiState.Ready)
    var connection by mutableStateOf<MuxState?>(MuxState.OPEN)
    var chips by mutableStateOf(emptyList<AttachmentChip>())
    val nav = ShellNav().apply { more = true; tab = ShellTab.OVERVIEW }
    var text by mutableStateOf("")
    var retries = 0
    var exports = 0
    var images = 0
    var files = 0
    var sends = 0

    fun show(rule: ComposeContentTestRule, page: @androidx.compose.runtime.Composable (ShellTab) -> Unit = { DefaultPage(it) }) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
            FogShell(
                status = status,
                connection = connection,
                nav = nav,
                composer = ComposerUi(
                    text = text, onText = { text = it }, enabled = true, canSend = true, running = false,
                    onSend = { sends++ }, onStop = {}, onAttachImage = { images++ }, onAttachFile = { files++ },
                    chips = chips,
                ),
                onRetry = { retries++ },
                onExportErrorLog = { exports++ },
                page = page,
            )
        }
    }

    companion object {
        /** Scrolls every scrollable page container (not the dock's chip row) to its end, as a user reaching the bottom would. */
        fun scrollPageToTheEnd(rule: ComposeContentTestRule) {
            val notInDock = !hasAnyAncestor(hasTestTag(Tags.DOCK))
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and notInDock).fetchSemanticsNodes().forEach { s ->
                rule.onNode(SemanticsMatcher("node ${s.id}") { it.id == s.id }).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            }
            rule.waitForIdle()
        }

        const val PAGE = "test-page-"
        const val PAGE_LAST = "test-page-last"

        /** A long page with a button at the very end, laid out with the shell's page padding like every real page. */
        @androidx.compose.runtime.Composable
        fun DefaultPage(tab: ShellTab) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current).testTag(PAGE + tab.name),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("page ${tab.en}")
                repeat(12) { Text("filler $it", Modifier.fillMaxWidth().height(80.dp)) }
                Button(onClick = {}, Modifier.testTag(PAGE_LAST)) { Text("last") }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FogShellTest {
    @get:Rule val rule = createComposeRule()
    private val h = ShellHarness()

    private fun tab(t: ShellTab) = rule.onNodeWithTag(Tags.TAB + t.name)

    @Test fun fiveTabsInOrderWithChineseLabels() {
        h.show(rule)
        assertEquals(listOf("OVERVIEW", "CHAT", "AGENTS", "LIBRARY", "SETTINGS"), ShellTab.entries.map { it.name })
        assertEquals(listOf("總覽", "對話", "指揮室", "書架", "設定"), ShellTab.entries.map { it.zh })
        val lefts = ShellTab.entries.map { t ->
            tab(t).assertIsDisplayed().assertTextContains(t.zh) // the English line is decoration (ShellAccessibilityTest)
            tab(t).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals("tabs left to right in enum order", lefts.sorted(), lefts)
    }

    @Test fun selectingATabUpdatesNavAndSelectedState() {
        h.show(rule)
        assertEquals(ShellTab.OVERVIEW, h.nav.tab)
        tab(ShellTab.OVERVIEW).assertIsSelected()
        tab(ShellTab.AGENTS).performClick()
        rule.waitForIdle()
        assertEquals(ShellTab.AGENTS, h.nav.tab)
        tab(ShellTab.AGENTS).assertIsSelected()
        tab(ShellTab.OVERVIEW).assertIsNotSelected()
        rule.onNodeWithTag(ShellHarness.PAGE + "AGENTS").assertExists()
    }

    @Test fun theDockComposerIsOnEveryTab() {
        h.show(rule)
        ShellTab.entries.forEach { t ->
            tab(t).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(Tags.COMPOSER_INPUT).assertIsDisplayed()
            rule.onNodeWithTag(Tags.SEND).assertIsDisplayed()
            rule.onNodeWithTag(Tags.ATTACH).assertIsDisplayed()
        }
    }

    @Test fun theComposerIsDisabledUntilReady() {
        h.status = MainUiState.Loading
        h.show(rule)
        rule.onNodeWithTag(Tags.COMPOSER_INPUT).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.SEND).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.COMPOSER_HINT, useUnmergedTree = true).assertTextEquals("DSH 尚未就緒")
        h.status = MainUiState.Ready
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.COMPOSER_INPUT).assertIsEnabled()
        rule.onNodeWithTag(Tags.SEND).assertIsEnabled()
        rule.onNodeWithTag(Tags.COMPOSER_HINT, useUnmergedTree = true).assertTextEquals("問點什麼…")
    }

    @Test fun attachOpensImageAndFileChoices() {
        h.show(rule)
        rule.onNodeWithTag(Tags.ATTACH_IMAGE).assertDoesNotExist()
        rule.onNodeWithTag(Tags.ATTACH).performClick()
        rule.onNodeWithTag(Tags.ATTACH_IMAGE).assertTextContains("圖片").performClick()
        assertEquals(1, h.images)
        assertEquals(0, h.files)
        rule.onNodeWithTag(Tags.ATTACH_IMAGE).assertDoesNotExist() // a choice closes the menu
        rule.onNodeWithTag(Tags.ATTACH).performClick()
        rule.onNodeWithTag(Tags.ATTACH_FILE).assertTextContains("檔案").performClick()
        assertEquals(1, h.images)
        assertEquals(1, h.files)
    }

    @Test fun tagsAreExposedAsResourceIds() {
        h.show(rule)
        val marked = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsPropertiesAndroid.TestTagsAsResourceId, true)).fetchSemanticsNodes()
        assertEquals(1, marked.size)
        assertEquals("the shell's root carries the flag", rule.onRoot().fetchSemanticsNode().boundsInRoot, marked.single().boundsInRoot)
        // Every tag below it is then visible to UiAutomator: the tabs and the composer sit inside the marked node.
        rule.onNode(hasTestTag(Tags.COMPOSER_INPUT) and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsPropertiesAndroid.TestTagsAsResourceId, true)))
            .assertExists()
    }

    @Test fun libraryShowsItsOwnPageEvenBeforeDshIsReady() {
        h.show(rule)
        tab(ShellTab.LIBRARY).performClick()
        rule.waitForIdle()
        // Stage 2 §D: the bookshelf is a real page now (stored books read without dsh), never the status panel.
        rule.onNodeWithTag(ShellHarness.PAGE + "LIBRARY").assertExists()
        rule.onNodeWithTag(Tags.LIBRARY_PLACEHOLDER).assertDoesNotExist()
        rule.onNodeWithTag(Tags.STATUS_PANEL).assertDoesNotExist()
    }

    @Test fun loadingAndErrorShowInChatAndAgentsButSettingsStillWorks() {
        h.status = MainUiState.Error
        h.show(rule)
        rule.onNodeWithTag(ShellHarness.PAGE + "OVERVIEW").assertExists() // the overview does not need dsh
        rule.onNodeWithTag(Tags.STATUS_PANEL).assertDoesNotExist()
        for (t in listOf(ShellTab.CHAT, ShellTab.AGENTS)) {
            tab(t).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(Tags.STATUS_PANEL).assertIsDisplayed().assertTextContains("無法啟動 DSH", substring = true)
            rule.onNodeWithTag(ShellHarness.PAGE + t.name).assertDoesNotExist()
        }
        ShellHarness.scrollPageToTheEnd(rule) // a small test screen: the buttons are below the dock until scrolled up
        rule.onNodeWithTag(Tags.RETRY).performClick()
        rule.onNodeWithTag(Tags.EXPORT_LOG).performClick()
        assertEquals(1, h.retries)
        assertEquals(1, h.exports)

        tab(ShellTab.SETTINGS).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.STATUS_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(ShellHarness.PAGE + "SETTINGS").assertExists()
        ShellHarness.scrollPageToTheEnd(rule)
        rule.onNodeWithTag(ShellHarness.PAGE_LAST).assertIsEnabled() // settings stay usable

        h.status = MainUiState.Loading
        tab(ShellTab.CHAT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.STATUS_PANEL).assertTextContains("正在啟動 DSH…", substring = true)
        rule.onNodeWithTag(Tags.RETRY).assertDoesNotExist()
        h.status = MainUiState.Ready
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.STATUS_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(ShellHarness.PAGE + "CHAT").assertExists()
    }

    @Test fun reconnectingChipShowsOnlyWhileReconnecting() {
        h.show(rule)
        rule.onNodeWithTag(Tags.RECONNECTING).assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        h.connection = MuxState.RECONNECTING
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame() // the shell sees the new state; the 2 s start here
        rule.mainClock.advanceTimeBy(1_999, ignoreFrameDuration = true)
        rule.onNodeWithTag(Tags.RECONNECTING).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
        rule.mainClock.advanceTimeByFrame() // draw it
        rule.onNodeWithTag(Tags.RECONNECTING).assertIsDisplayed().assertTextEquals("重新連線中…")
        h.connection = MuxState.OPEN
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(Tags.RECONNECTING).assertDoesNotExist()
        // A short blip never shows it.
        h.connection = MuxState.RECONNECTING
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(1_000)
        h.connection = MuxState.OPEN
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithTag(Tags.RECONNECTING).assertDoesNotExist()
    }
}

/** At the phone's real width: nothing floating covers anything else, and every page's last control clears the dock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class FogShellLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val h = ShellHarness()

    private fun clickable(n: SemanticsNode) =
        n.config.getOrNull(SemanticsActions.OnClick) != null || n.config.getOrNull(SemanticsActions.SetText) != null

    private val inDock = hasAnyAncestor(hasTestTag(Tags.DOCK))

    private fun floatingControls(): List<SemanticsNode> =
        rule.onAllNodes(inDock).fetchSemanticsNodes().filter { n ->
            val tag = n.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            clickable(n) || tag == Tags.RECONNECTING || tag.startsWith(Tags.ATTACHMENT_CHIP)
        }

    /** Every control of the page itself (the dock and everything in it are the shell's). */
    private fun pageControls(): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher("clickable") { clickable(it) } and !inDock and !hasTestTag(Tags.DOCK)).fetchSemanticsNodes()

    private fun Rect.overlaps(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    private fun SemanticsNode.isAncestorOf(other: SemanticsNode): Boolean {
        var p = other.parent
        while (p != null) {
            if (p.id == id) return true
            p = p.parent
        }
        return false
    }

    private fun assertNoOverlap(where: String) {
        val nodes = floatingControls()
        assertTrue("$where: expected the dock's controls, found ${nodes.size}", nodes.size >= 3 + ShellTab.entries.size)
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]
            val b = nodes[j]
            if (a.isAncestorOf(b) || b.isAncestorOf(a)) continue // a chip and its own remove button
            assertTrue("$where: ${describe(a)} ${a.boundsInRoot} overlaps ${describe(b)} ${b.boundsInRoot}", !a.boundsInRoot.overlaps(b.boundsInRoot))
        }
    }

    private fun describe(n: SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.TestTag) ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "node ${n.id}"

    /** Scrolled to the end, the page's last control sits above the dock and above the band where pages fade out. */
    private fun assertLastPageControlClearsTheDock(where: String) {
        ShellHarness.scrollPageToTheEnd(rule)
        val dockTop = rule.onNodeWithTag(Tags.DOCK).fetchSemanticsNode().boundsInRoot.top
        val fadeTop = dockTop - with(rule.density) { 24.dp.toPx() }
        val controls = pageControls()
        if (controls.isEmpty()) return
        val lowest = controls.maxBy { it.boundsInRoot.bottom }
        assertTrue("$where: ${describe(lowest)} ends at ${lowest.boundsInRoot.bottom}, into the fade above the dock ($fadeTop)", lowest.boundsInRoot.bottom <= fadeTop)
    }

    /** The page viewport starts where the header ends: nothing scrolls under the header. */
    private fun assertTheStageStartsBelowTheHeader(where: String) {
        val header = rule.onNodeWithTag(Tags.SHELL_HEADER).fetchSemanticsNode().boundsInRoot
        val stage = rule.onNodeWithTag(Tags.SHELL_STAGE).fetchSemanticsNode().boundsInRoot
        assertTrue("$where: stage top ${stage.top} < header bottom ${header.bottom}", stage.top >= header.bottom)
    }

    @Test fun atPhoneWidthNoFloatingControlOverlapsAnother() {
        h.show(rule)
        with(rule.density) { assertEquals(412.dp.toPx(), rule.onRoot().fetchSemanticsNode().boundsInRoot.width, 0.5f) }

        fun everyTab(where: String) = ShellTab.entries.forEach { t ->
            rule.onNodeWithTag(Tags.TAB + t.name).performClick()
            rule.waitForIdle()
            assertNoOverlap("$where/${t.name}")
            assertTheStageStartsBelowTheHeader("$where/${t.name}")
            assertLastPageControlClearsTheDock("$where/${t.name}")
        }

        everyTab("ready")
        h.status = MainUiState.Error
        everyTab("error")
        h.connection = MuxState.RECONNECTING
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(2_100)
        rule.onNodeWithTag(Tags.RECONNECTING).assertIsDisplayed()
        everyTab("reconnecting")
        rule.onNodeWithTag(Tags.ATTACH).performClick()
        rule.onNodeWithTag(Tags.ATTACH_IMAGE).assertIsDisplayed()
        h.chips = listOf(AttachmentChip("a", "a-very-long-photo-name-from-the-camera-roll.jpg", isImage = true), AttachmentChip("b", "report.pdf", isImage = false))
        rule.waitForIdle()
        everyTab("menu and chips")
        rule.onNodeWithTag(Tags.ATTACH_FILE).assertIsDisplayed() // still open after the tab tour
        h.status = MainUiState.Ready
        rule.waitForIdle()
        everyTab("ready with menu and chips")
    }
}
