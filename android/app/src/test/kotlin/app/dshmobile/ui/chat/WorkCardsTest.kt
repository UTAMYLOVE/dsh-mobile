package app.dshmobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Mood
import app.dshmobile.agents.Prop
import app.dshmobile.agents.Stage
import app.dshmobile.chat.SessionUsage
import app.dshmobile.chat.Todo
import app.dshmobile.chat.TodoStatus
import app.dshmobile.remote.AskQuestion
import app.dshmobile.remote.PendingPrompt
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import app.dshmobile.ui.shell.PromptSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class WorkCardsTest {
    @get:Rule val rule = createComposeRule()

    private val todos = listOf(
        Todo("查外資買賣超", TodoStatus.DONE),
        Todo("整理法說會重點", TodoStatus.IN_PROGRESS),
        Todo("寫成報告", TodoStatus.PENDING),
    )

    private fun agent(label: String, mood: Mood, lead: Boolean = false) = AgentStatus(
        label, if (lead) null else "s1", if (lead) 0 else 1, label, mood, Stage.values().first(), Prop.NONE, null, null, null, null,
        SessionUsage(), null, null, 0, null, null, emptyList(), lead,
    )

    @Test fun barShowsCountAndCurrent() {
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) { TodoBar(todos, open, { open = !open }) }
        }
        rule.onNodeWithText("1 / 3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("整理法說會重點", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("${WorkTags.TODO_ITEM}2", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(WorkTags.TODO_BAR).performClick()
        rule.onNodeWithTag("${WorkTags.TODO_ITEM}2", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("寫成報告", useUnmergedTree = true).assertExists()
    }

    @Test fun noTodosNoBar() {
        rule.setContent { FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) { TodoBar(emptyList(), false, {}) } }
        rule.onNodeWithTag(WorkTags.TODO_BAR).assertDoesNotExist()
    }

    @Test fun contemplateShowsThreeStances() {
        val team = listOf(agent("主對話", Mood.RUNNING, lead = true), agent("樂觀派", Mood.DONE), agent("保守派", Mood.RUNNING), agent("數據派", Mood.DONE), agent("查資料", Mood.RUNNING))
        val stances = stanceAgents(team)
        assertEquals(listOf("樂觀派", "保守派", "數據派"), stances.map { it.label })
        assertTrue(stanceAgents(listOf(agent("查資料", Mood.RUNNING))).isEmpty())
        rule.setContent { FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) { ContemplateCard(stances) } }
        rule.onNodeWithTag("${WorkTags.STANCE}樂觀派").assertExists()
        rule.onNodeWithTag("${WorkTags.STANCE}保守派").assertExists()
        rule.onNodeWithText("思考中", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("三方都想好後，AI 會合成一個答案").assertExists()
    }

    private val review = PendingPrompt.Question(
        "e1", "s1",
        listOf(AskQuestion("plan-review", "Plan review", "Approve?", listOf("Approve", "Keep planning"), false, "# 研究計畫\n1. 查外資", true, "Approve")),
    )

    @Test fun planCardApprovesAndRevisesWithFeedback() {
        val calls = mutableListOf<Pair<Boolean, String>>()
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.FULL, reducedMotion = false)) {
                Box(Modifier.fillMaxSize()) {
                    PromptSheet(review, "我的對話", 0, false, null, {}, {}, {}, {}, onPlan = { a, f -> calls += a to f })
                }
            }
        }
        rule.onNodeWithTag(WorkTags.PLAN_CARD).assertExists()
        rule.onNodeWithText("計畫好了，要開始做嗎？").assertExists()
        rule.onNodeWithText("研究計畫", substring = true).assertExists()
        rule.onNodeWithTag(WorkTags.PLAN_APPROVE).performClick()
        rule.onNodeWithTag(WorkTags.PLAN_REVISE).performClick()
        rule.onNodeWithTag(WorkTags.PLAN_FEEDBACK).performTextInput("多查一家")
        rule.onNodeWithTag(WorkTags.PLAN_SEND).performClick()
        assertEquals(listOf(true to "", false to "多查一家"), calls)
    }
}
