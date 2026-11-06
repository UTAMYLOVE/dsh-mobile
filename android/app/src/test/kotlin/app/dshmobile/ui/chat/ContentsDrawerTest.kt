package app.dshmobile.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dshmobile.chat.ChatItem
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.effectsFor
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
class ContentsDrawerTest {
    @get:Rule val rule = createComposeRule()

    private val outline = Json.parseToJsonElement(
        """[{"turn":1,"seq":1,"prompt":"幫我寫霧城的大綱"},{"turn":2,"seq":5,"prompt":"請用 dshm-research 技能：外資動向"},{"turn":3,"seq":9,"prompt":"請寫第一章"}]""",
    )

    @Test fun entriesComeFromTheTurnOutline() {
        val items = listOf(ChatItem.Assistant("e7", 7, null, "## 外資買超\n內容\n### 投信\n更多"))
        val e = Toc.entries(outline, items)
        assertEquals(listOf("幫我寫霧城的大綱", "外資動向", "外資買超", "投信", "請寫第一章"), e.map { it.text })
        assertEquals(listOf(false, false, true, true, false), e.map { it.heading })
        assertEquals("e5", e[1].key)
        assertEquals("e7", e[2].key)
    }

    @Test fun filterMatchesText() {
        val e = Toc.entries(outline, emptyList())
        assertEquals(listOf("外資動向"), Toc.filter(e, "外資").map { it.text })
        assertEquals(3, Toc.filter(e, "  ").size)
    }

    @Test fun pickingAPagedOutTurnLoadsOlderFirst() {
        var loads = 0
        var items by mutableStateOf(listOf<ChatItem>(ChatItem.User("e9", "請寫第一章", emptyList()), ChatItem.Assistant("e10", 10, null, "第一章")))
        var more by mutableStateOf(true)
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ChatScreen(
                    ChatUiState("霧城", items, null, false, emptyList(), emptyList(), more, null),
                    onOpenList = {}, onPickModel = {}, onLink = {}, onShare = {},
                    onLoadOlder = {
                        loads++
                        items = listOf(ChatItem.User("e1", "幫我寫霧城的大綱", emptyList()), ChatItem.Assistant("e2", 2, null, "大綱")) + items
                        more = false
                    },
                    toc = Toc.entries(outline, items),
                )
            }
        }
        rule.onNodeWithTag(app.dshmobile.ui.shell.Tags.CHAT_MORE).performClick()
        rule.onNodeWithTag(TocTags.OPEN).performClick()
        rule.onNodeWithTag(TocTags.DRAWER).assertExists()
        rule.onNodeWithTag(TocTags.ENTRY + "e1").performClick()
        rule.waitForIdle()
        assertEquals(1, loads)
        rule.onNodeWithTag(TocTags.DRAWER).assertDoesNotExist()
    }

    @Test fun jumpIndexFindsTheTopRow() {
        val rows = listOf(ChatRow.Older(null), ChatRow.Older(null))
        assertEquals(-1, jumpIndex(rows, "e1"))
    }
}
