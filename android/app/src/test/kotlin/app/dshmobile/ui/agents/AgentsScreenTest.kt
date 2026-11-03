package app.dshmobile.ui.agents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.dshmobile.agents.AgentRow
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class AgentsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val stopped = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private val running = AgentRow("c1", "s1", "查資料", true)
    private val done = AgentRow("c2", "s1", "整理筆記", false)

    private fun show(
        rows: List<AgentRow>, limit: Int = 6, stopping: Set<String> = emptySet(), failed: Set<String> = emptySet(),
        title: (String) -> String? = { if (it == "s1") "天氣" else null },
    ) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
            AgentsScreen(rows, title, limit, stopping, { stopped += it.childId }, { opened += it }, failed)
        }
    }

    @Test fun rowsShowLabelParentAndStatus() {
        show(listOf(running, done))
        rule.onNodeWithTag("agent-row-c1").assertTextContains("查資料", substring = true)
        rule.onNodeWithTag("agent-row-c1").assertTextContains("來自：天氣", substring = true)
        rule.onNodeWithTag("agent-row-c2").assertTextContains("整理筆記", substring = true)
        rule.onNodeWithContentDescription("執行中").assertIsDisplayed()
        rule.onNodeWithContentDescription("已結束").assertIsDisplayed()
        rule.onNodeWithTag("agent-row-c1").performClick()
        assertEquals(listOf("s1"), opened)
    }

    @Test fun stopOnlyForRunningAndShowsStopping() {
        show(listOf(running, done), stopping = emptySet())
        rule.onNodeWithTag("agent-stop-c1").assertIsEnabled().assertTextContains("叫它停")
        rule.onNodeWithTag("agent-stop-c2").assertDoesNotExist()
        rule.onNodeWithTag("agent-stop-c1").performClick()
        assertEquals(listOf("c1"), stopped)
    }

    @Test fun stoppingRowShowsStoppingAndCannotBeTapped() {
        show(listOf(running), stopping = setOf("c1"))
        rule.onNodeWithTag("agent-stop-c1").assertIsNotEnabled().assertTextContains("停止中…")
    }

    @Test fun aFailedStopShowsAShortErrorAndTheButtonIsBackOn() {
        show(listOf(running), failed = setOf("c1"))
        rule.onNodeWithText("沒能叫停，請再試一次", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("agent-stop-c1").assertIsEnabled()
    }

    @Test fun emptyStateSaysSo() {
        show(emptyList())
        rule.onNodeWithTag(Tags.AGENTS_EMPTY).assertIsDisplayed().assertTextContains("目前沒有在跑的代理人")
    }

    @Test fun headerShowsRunningAndLimit() {
        show(listOf(running, done), limit = 6)
        rule.onNodeWithText("在跑 1 / 上限 6").assertIsDisplayed()
    }

    @Test fun longLabelsEllipsizeAndTheStopButtonNeverOverlapsThem() {
        val long = AgentRow("c9", "s1", "這是一個非常非常長的子代理人標籤".repeat(8), true)
        show(listOf(long), title = { "一個同樣很長很長很長很長很長的父對話標題".repeat(6) })
        val text = rule.onNodeWithTag("agent-row-c9").getUnclippedBoundsInRoot()
        val stop = rule.onNodeWithTag("agent-stop-c9").getUnclippedBoundsInRoot()
        assertTrue("stop button ${stop.left} must start at or after the text ends ${text.right}", stop.left >= text.right)
        assertTrue("stop button inside 412 dp (${stop.right})", stop.right <= 412.dp)
        assertTrue("the row text stays within the page", text.left >= 0.dp && text.right <= 412.dp)
        assertTrue("two lines at most per text (${text.bottom - text.top})", text.bottom - text.top < 120.dp)
    }
}
