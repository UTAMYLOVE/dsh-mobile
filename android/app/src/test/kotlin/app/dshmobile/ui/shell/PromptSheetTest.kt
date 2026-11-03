package app.dshmobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dshmobile.remote.AskQuestion
import app.dshmobile.remote.PendingPrompt
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

private val approval = PendingPrompt.Approval("e1", "s1", "write", "c1", "要寫入 notes.md")
private val color = AskQuestion("q1", "顏色", "你喜歡哪個顏色？", listOf("Pink", "Blue"), false)
private val toppings = AskQuestion("q2", null, "要加什麼？", listOf("Tea", "Milk", "Honey"), true)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class PromptSheetTest {
    @get:Rule val rule = createComposeRule()

    private var allowed = 0
    private var rejected = 0
    private var declined = 0
    private var answers = mutableListOf<Map<String, List<String>>>()

    private fun show(
        prompt: PendingPrompt,
        origin: String = "我的對話",
        waiting: Int = 0,
        busy: Boolean = false,
        error: String? = null,
    ) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
            Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                PromptSheet(
                    prompt, origin, waiting, busy, error,
                    onAllowOnce = { allowed++ }, onReject = { rejected++ }, onAnswer = { answers += it }, onDecline = { declined++ },
                )
            }
        }
    }

    private fun inSheet(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag(Tags.PROMPT_SHEET))

    @Test fun approvalShowsToolReasonAndConversation() {
        show(approval)
        rule.onNode(inSheet("dsh 想要執行 write")).assertExists()
        rule.onNode(inSheet("要寫入 notes.md")).assertExists()
        rule.onNode(inSheet("來自：我的對話")).assertExists()
        rule.onNode(inSheet("APPROVAL")).assertExists()
    }

    @Test fun allowOnceAndRejectCallBack() {
        show(approval)
        rule.onNodeWithTag(Tags.APPROVE_ONCE).performClick()
        rule.onNodeWithTag(Tags.REJECT).performClick()
        assertEquals(1, allowed)
        assertEquals(1, rejected)
    }

    @Test fun questionNeedsAnAnswerBeforeSubmit() {
        show(PendingPrompt.Question("e2", "s1", listOf(color)))
        rule.onNode(inSheet("QUESTION")).assertExists()
        rule.onNodeWithTag(Tags.QUESTION_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag("${Tags.OPTION}q1-0").performClick()
        rule.onNodeWithTag(Tags.QUESTION_SUBMIT).assertIsEnabled().performClick()
        assertEquals(listOf(mapOf("q1" to listOf("Pink"))), answers)
    }

    @Test fun aSingleChoiceReplacesItsPreviousPick() {
        show(PendingPrompt.Question("e2", "s1", listOf(color)))
        rule.onNodeWithTag("${Tags.OPTION}q1-0").performClick()
        rule.onNodeWithTag("${Tags.OPTION}q1-1").performClick()
        rule.onNodeWithTag(Tags.QUESTION_SUBMIT).performClick()
        assertEquals(listOf(mapOf("q1" to listOf("Blue"))), answers)
    }

    @Test fun multiSelectCollectsSeveralLabels() {
        show(PendingPrompt.Question("e2", "s1", listOf(color, toppings)))
        rule.onNodeWithTag("${Tags.OPTION}q1-1").performClick()
        rule.onNodeWithTag(Tags.QUESTION_SUBMIT).assertIsNotEnabled() // q2 still unanswered
        rule.onNodeWithTag("${Tags.OPTION}q2-2").performClick()
        rule.onNodeWithTag("${Tags.OPTION}q2-0").performClick()
        rule.onNodeWithTag("${Tags.OPTION}q2-2").performClick() // toggled off again
        rule.onNodeWithTag("${Tags.OPTION}q2-1").performClick()
        rule.onNodeWithTag(Tags.QUESTION_SUBMIT).performClick()
        assertEquals(listOf(mapOf("q1" to listOf("Blue"), "q2" to listOf("Tea", "Milk"))), answers)
    }

    @Test fun declineIsAlwaysAvailable() {
        show(PendingPrompt.Question("e2", "s1", listOf(color)))
        rule.onNodeWithTag(Tags.QUESTION_DECLINE).assertIsEnabled().performClick()
        assertEquals(1, declined)
        assertTrue(answers.isEmpty())
    }

    @Test fun anErrorKeepsTheSheetAndSaysSo() {
        show(approval, error = "沒有送出，請再試一次")
        rule.onNodeWithTag(Tags.PROMPT_SHEET).assertExists()
        rule.onNodeWithTag(Tags.PROMPT_ERROR).assertTextContains("沒有送出，請再試一次")
        rule.onNodeWithTag(Tags.APPROVE_ONCE).assertIsEnabled()
    }

    @Test fun theSheetIsAnnouncedAsAPane() {
        show(approval)
        val node = rule.onNodeWithTag(Tags.PROMPT_SHEET).fetchSemanticsNode()
        assertEquals("dsh 想要執行 write", node.config[androidx.compose.ui.semantics.SemanticsProperties.PaneTitle])
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, node.config[androidx.compose.ui.semantics.SemanticsProperties.LiveRegion])
    }

    @Test fun connectingIsSaidWhileTheAnswerWaits() {
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
                Box(androidx.compose.ui.Modifier.fillMaxSize()) { PromptSheet(approval, "x", 0, true, null, {}, {}, {}, {}, connecting = true) }
            }
        }
        rule.onNodeWithTag(Tags.PROMPT_CONNECTING).assertExists()
    }

    @Test fun noErrorLineWithoutAnError() {
        show(approval)
        rule.onNodeWithTag(Tags.PROMPT_ERROR).assertDoesNotExist()
    }

    @Test fun busyDisablesTheButtons() {
        show(approval, busy = true)
        rule.onNodeWithTag(Tags.APPROVE_ONCE).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.REJECT).assertIsNotEnabled()
    }

    @Test fun moreWaitingIsCounted() {
        show(approval, waiting = 3)
        rule.onNodeWithTag(Tags.PROMPT_MORE).assertTextContains("3", substring = true)
    }

    @Test fun nothingIsCountedWhenNothingWaits() {
        show(approval, waiting = 0)
        rule.onNodeWithTag(Tags.PROMPT_MORE).assertDoesNotExist()
    }

    @Test fun theNextPromptStartsWithAFreshSelection() {
        val current = mutableStateOf<PendingPrompt>(PendingPrompt.Question("e2", "s1", listOf(color)))
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
                Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    PromptSheet(current.value, "x", 0, false, null, {}, {}, { answers += it }, {})
                }
            }
        }
        rule.onNodeWithTag("${Tags.OPTION}q1-0").performClick()
        current.value = PendingPrompt.Question("e3", "s1", listOf(color))
        rule.onNodeWithTag(Tags.QUESTION_SUBMIT).assertIsNotEnabled()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class PromptSheetLayoutTest {
    @get:Rule val rule = createComposeRule()

    private fun show(prompt: PendingPrompt) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
            Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("root")) {
                PromptSheet(prompt, "我的對話", 2, false, "沒有送出，請再試一次", {}, {}, {}, {})
            }
        }
    }

    private fun androidx.compose.ui.geometry.Rect.overlaps(o: androidx.compose.ui.geometry.Rect) =
        left < o.right && o.left < right && top < o.bottom && o.top < bottom

    private fun assertContains(root: androidx.compose.ui.geometry.Rect, r: androidx.compose.ui.geometry.Rect, what: String) =
        assertTrue("$what $r is not inside the screen $root", r.left >= root.left && r.right <= root.right && r.top >= root.top && r.bottom <= root.bottom)

    @Test fun theSheetFitsAt412dpWithoutCoveringItsOwnButtons() {
        show(PendingPrompt.Approval("e1", "s1", "bash", "c1", "很長的說明。".repeat(333))) // about 2 000 characters
        val root = rule.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        val sheet = rule.onNodeWithTag(Tags.PROMPT_SHEET).fetchSemanticsNode().boundsInRoot
        val allow = rule.onNodeWithTag(Tags.APPROVE_ONCE).fetchSemanticsNode().boundsInRoot
        val reject = rule.onNodeWithTag(Tags.REJECT).fetchSemanticsNode().boundsInRoot
        assertContains(root, sheet, "the sheet")
        assertContains(sheet, allow, "approve")
        assertContains(sheet, reject, "reject")
        assertTrue("the buttons overlap: $allow $reject", !allow.overlaps(reject))
        assertTrue("the sheet should leave the scrim visible above it, top=${sheet.top}", sheet.top > 0f)
        // the long text scrolls inside the card
        rule.onNodeWithTag(Tags.PROMPT_SCROLL).assertExists()
    }

    @Test fun manyQuestionsKeepSubmitAndDeclineOnScreenAndApart() {
        val qs = (1..6).map { AskQuestion("q$it", "H$it", "問題 $it ".repeat(20), listOf("甲選項", "乙選項", "丙選項", "丁選項"), it % 2 == 0) }
        show(PendingPrompt.Question("e2", "s1", qs))
        val root = rule.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        val sheet = rule.onNodeWithTag(Tags.PROMPT_SHEET).fetchSemanticsNode().boundsInRoot
        val submit = rule.onNodeWithTag(Tags.QUESTION_SUBMIT).fetchSemanticsNode().boundsInRoot
        val decline = rule.onNodeWithTag(Tags.QUESTION_DECLINE).fetchSemanticsNode().boundsInRoot
        assertContains(root, sheet, "the sheet")
        assertContains(sheet, submit, "submit")
        assertContains(sheet, decline, "decline")
        assertTrue("the buttons overlap: $submit $decline", !submit.overlaps(decline))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class PromptModalTest {
    @get:Rule val rule = createComposeRule()
    private val modal = mutableStateOf(true)

    // accessibility services read the merged tree (clearAndSetSemantics only drops nodes from it; the unmerged tree keeps them)
    @Test fun whileAPromptShowsTheDockIsHiddenFromAccessibility() {
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = true)) {
                FogShell(
                    status = app.dshmobile.ui.MainUiState.Ready, connection = app.dshmobile.remote.MuxState.OPEN, nav = ShellNav(),
                    composer = ComposerUi("", {}, enabled = true, canSend = false, running = false, onSend = {}, onStop = {}),
                    onRetry = {}, onExportErrorLog = {},
                    overlay = { PromptSheet(approval, "x", 0, false, null, {}, {}, {}, {}) },
                    modalOverlay = modal.value,
                ) {}
            }
        }
        val tab = Tags.TAB + ShellTab.entries.first().name
        rule.onNodeWithTag(Tags.PROMPT_SHEET).assertExists()
        rule.onAllNodesWithTag(tab).assertCountEquals(0)
        rule.onAllNodesWithTag(Tags.SHELL_HEADER).assertCountEquals(0)
        modal.value = false
        rule.waitForIdle()
        rule.onAllNodesWithTag(tab).assertCountEquals(1)
    }
}
