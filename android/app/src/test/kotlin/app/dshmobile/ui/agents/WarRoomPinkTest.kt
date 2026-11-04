package app.dshmobile.ui.agents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Mood
import app.dshmobile.agents.Prop
import app.dshmobile.agents.Stage
import app.dshmobile.agents.WarLogRow
import app.dshmobile.agents.WarSample
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ConversationState
import app.dshmobile.chat.SessionUsage
import app.dshmobile.chat.ToolStatus
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class WarRoomPinkTest {
    @get:Rule val rule = createComposeRule()

    private fun a(id: String, mood: Mood, lead: Boolean = false) = AgentStatus(
        id, if (lead) null else "lead", if (lead) 0 else 1, if (lead) "哈嘍問候" else "台股新聞 $id", mood, Stage.WRITE, Prop.PEN,
        "撰寫 reports/news.md", null, "deepseek-v4-pro", "high", SessionUsage(inputTokens = 4_000, outputTokens = 900, turns = 1, steps = 3),
        .23f, 60_000, 0, "外資連三日買超", null, listOf("撰寫 reports/news.md ✓"), lead,
    )

    @Test fun everySectionShowsAndACardOpensItsDetail() {
        val agents = listOf(a("lead", Mood.RUNNING, lead = true), a("k1", Mood.RUNNING), a("k2", Mood.WAITING))
        val lead = ConversationState("lead", items = listOf(ChatItem.Tool("t1", "bash", "rm -rf data/", ToolStatus.FAILED, error = "denied by auto review")))
        val samples = (0..6).map { WarSample(it * 5_000L, 100f + it, 1_000L * it, 100L * it, .6f, it.toLong(), 0, mapOf("k1" to 120f + it)) }
        val log = listOf(WarLogRow(1_000, "派出", "台股新聞 k1", "第 1 層"))
        var stopped = ""
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                var open by remember { mutableStateOf<String?>(null) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WarRoomPink(
                        WarRoomInput(agents, lead, log, samples, 1.85, 5.0, 32, 16, 0),
                        expanded = open, onExpand = { open = it }, costOf = { .031 }, stopping = emptySet(),
                        onStop = { stopped = it.id }, onOpen = {}, limits = "並行上限 32",
                    )
                }
            }
        }
        for (tag in listOf(Tags.WAR_TICKER, Tags.WAR_TILES, Tags.WAR_CORE, Tags.WAR_FLOW, Tags.WAR_CHART, Tags.WAR_STACK, Tags.WAR_LOG)) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("1.85").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.WAR_CARD + "k1").performScrollTo().performClick()
        rule.onNodeWithTag(Tags.WAR_DETAIL + "k1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(Tags.AGENT_STOP + "k1", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("k1", stopped)
    }
}
