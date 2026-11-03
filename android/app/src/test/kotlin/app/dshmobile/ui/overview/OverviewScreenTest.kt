package app.dshmobile.ui.overview

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dshmobile.chat.SessionRow
import app.dshmobile.overview.OverviewState
import app.dshmobile.overview.Reminder
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.LocalPagePadding
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
class OverviewScreenTest {
    @get:Rule val rule = createComposeRule()

    private val opened = mutableListOf<String>()
    private var agentsOpened = 0
    private val now = 1_000_000_000L

    private fun state(
        agentsRunning: Int? = 2, nextAt: Long? = now + 15 * 60_000L, unverified: String? = null,
        reminders: List<Reminder> = listOf(Reminder("記得澆花", "週三下午", now)),
        recent: List<SessionRow> = listOf(SessionRow("s1", "台北週末天氣", "晴", "DeepSeek", null, now, false), SessionRow("s2", "第 12 章", null, "Claude", null, now, false)),
    ) = OverviewState(
        deepseekUsd = 0.0089, claudeUsd = 0.0034, monthUsd = 12.5, alertUsd = 5.0, agentsRunning = agentsRunning, agentsLimit = 6,
        nextHeartbeatAtMs = nextAt, heartbeatIntervalMin = 45, nowMs = now, reminders = reminders, recent = recent, unverifiedDsh = unverified,
    )

    private fun show(s: OverviewState) = rule.setContent {
        FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
            CompositionLocalProvider(LocalPagePadding provides PaddingValues(16.dp)) {
                OverviewScreen(s, { opened += it }, { agentsOpened++ })
            }
        }
    }

    @Test fun ringDescribesTodaysSpend() {
        show(state())
        rule.onNodeWithTag(Tags.OVERVIEW_RING).assertIsDisplayed()
        rule.onNodeWithContentDescription("今日花費 US$0.0123").assertIsDisplayed()
    }

    @Test fun splitShowsDeepseekClaudeAndMonth() {
        show(state())
        rule.onNodeWithTag(Tags.OVERVIEW_SPLIT).assertTextContains("DeepSeek", substring = true)
        rule.onNodeWithTag(Tags.OVERVIEW_SPLIT).assertTextContains("0.01", substring = true)
        rule.onNodeWithTag(Tags.OVERVIEW_SPLIT).assertTextContains("Claude", substring = true)
        rule.onNodeWithTag(Tags.OVERVIEW_SPLIT).assertTextContains("0.00", substring = true)
        rule.onNodeWithTag(Tags.OVERVIEW_SPLIT).assertTextContains("這個月", substring = true)
        rule.onNodeWithTag(Tags.OVERVIEW_SPLIT).assertTextContains("12.50", substring = true)
    }

    @Test fun tappingARecentConversationOpensIt() {
        show(state())
        rule.onNodeWithTag(Tags.OVERVIEW_RECENT + "s2").performScrollTo().performClick()
        assertEquals(listOf("s2"), opened)
        rule.onNodeWithTag(Tags.OVERVIEW_RECENT + "s1").assertTextContains("台北週末天氣", substring = true)
    }

    @Test fun agentsPanelOpensTheAgentsTab() {
        show(state())
        rule.onNodeWithTag(Tags.OVERVIEW_AGENTS).assertTextContains("2 / 6", substring = true)
        rule.onNodeWithTag(Tags.OVERVIEW_AGENTS).performClick()
        assertEquals(1, agentsOpened)
    }

    @Test fun agentsAreADashWithoutDsh() {
        show(state(agentsRunning = null))
        rule.onNodeWithTag(Tags.OVERVIEW_AGENTS).assertTextContains("—", substring = true)
    }

    @Test fun unverifiedBannerOnlyWhenSet() {
        show(state())
        rule.onAllNodesWithTag(Tags.UNVERIFIED_BANNER).assertCountEquals(0)
    }

    @Test fun unverifiedBannerShowsTheVersion() {
        show(state(unverified = "0.3.1"))
        rule.onNodeWithTag(Tags.UNVERIFIED_BANNER).assertTextContains("0.3.1", substring = true)
    }

    @Test fun heartbeatPanelShowsMinutesAndTheBar() {
        show(state())
        rule.onNodeWithTag(Tags.OVERVIEW_HEARTBEAT).assertTextContains("15 分鐘後整理記憶", substring = true)
        val info = rule.onNodeWithTag(Tags.OVERVIEW_HEARTBEAT).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.667f, info.current, 0.01f)
    }

    @Test fun remindersShowCountAndEmptyNote() {
        show(state())
        rule.onNodeWithTag(Tags.OVERVIEW_REMINDERS).performScrollTo().assertTextContains("記得澆花", substring = true)
    }

    @Test fun emptyRemindersSayThereAreNone() {
        show(state(reminders = emptyList()))
        rule.onNodeWithTag(Tags.OVERVIEW_REMINDERS).performScrollTo().assertTextContains("沒有新的提醒", substring = true)
    }

    @Test fun nothingOverlapsAt412AndTheLastCardClearsTheDock() {
        val dockClear = 220.dp
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                CompositionLocalProvider(LocalPagePadding provides PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = dockClear)) {
                    Box(Modifier.fillMaxSize()) {
                        OverviewScreen(state(unverified = "0.3.1"), {}, {})
                    }
                }
            }
        }
        val tags = listOf(Tags.UNVERIFIED_BANNER, Tags.OVERVIEW_RING, Tags.OVERVIEW_SPLIT, Tags.OVERVIEW_AGENTS, Tags.OVERVIEW_HEARTBEAT, Tags.OVERVIEW_REMINDERS, Tags.OVERVIEW_RECENT + "s2")
        val b = tags.map { rule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        // vertical order, no overlap for the full-width stack (agents and heartbeat share a row, so compare them side by side)
        assertTrue(b[0].bottom <= b[1].top)
        assertTrue(b[1].bottom <= b[2].top)
        assertTrue(b[2].bottom <= b[3].top && b[2].bottom <= b[4].top)
        assertTrue(b[3].right <= b[4].left)
        assertTrue(b[3].bottom.value - b[4].bottom.value < 1f && b[4].bottom.value - b[3].bottom.value < 1f) // equal height
        assertTrue(b[3].bottom <= b[5].top && b[4].bottom <= b[5].top)
        assertTrue(b[5].bottom <= b[6].top)
        // the split's three cells fit inside the 412 dp width without wrapping off-screen
        assertTrue(b[2].right <= 412.dp && b[3].left >= 0.dp && b[4].right <= 412.dp)
        // scrolled to the end, the last row sits above the dock's reserved band (915 dp screen)
        rule.onRoot().performTouchInput { repeat(4) { swipeUp(startY = height * .9f, endY = height * .1f) } } // to the end of the page
        val last = rule.onNodeWithTag(Tags.OVERVIEW_RECENT + "s2").getUnclippedBoundsInRoot()
        assertTrue("last row bottom ${last.bottom}", last.bottom <= 915.dp - dockClear + 1.dp)
    }
}
