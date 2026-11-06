package app.dshmobile.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.dshmobile.chat.DayGroup
import app.dshmobile.chat.SessionRow
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
class ConversationListScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun row(id: String, title: String, last: String? = "最後一句", model: String? = "deepseek-flash", running: Boolean = false) =
        SessionRow(id, title, last, model, null, 1, running)

    private val groups = listOf(
        DayGroup.TODAY to listOf(row("a", "今天的對話", running = true)),
        DayGroup.YESTERDAY to listOf(row("b", "昨天的對話")),
        DayGroup.EARLIER to listOf(row("c", "")),
    )
    private val opened = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private var news = 0
    private var closes = 0
    private var query = ""

    private var back: androidx.activity.OnBackPressedDispatcher? = null

    private fun show(g: List<Pair<DayGroup, List<SessionRow>>> = groups) = rule.setContent {
        back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
            ConversationListScreen(g, query, { query = it }, { opened += it }, { news++ }, { id, t -> renamed += id to t }, { deleted += it }, { closes++ })
        }
    }

    @Test fun groupsShowInOrderWithRows() {
        show()
        val ys = DayGroup.entries.map { rule.onNodeWithTag("session-group-${it.name}").getUnclippedBoundsInRoot().top }
        assertEquals(ys.sorted(), ys)
        rule.onNodeWithText("今天").assertIsDisplayed()
        rule.onNodeWithText("昨天").assertIsDisplayed()
        rule.onNodeWithText("更早").assertIsDisplayed()
        rule.onNodeWithTag(Tags.SESSION_ROW + "a").assertTextContains("今天的對話", substring = true)
        rule.onNodeWithTag(Tags.SESSION_ROW + "a").assertTextContains("最後一句 · DeepSeek Flash", substring = true)
        rule.onNodeWithTag(Tags.SESSION_ROW + "c").assertTextContains("新對話", substring = true)
        rule.onNodeWithText("所有對話").assertIsDisplayed()
    }

    @Test fun tappingARowOpensIt() {
        show()
        rule.onNodeWithTag(Tags.SESSION_ROW + "b").performClick()
        assertEquals(listOf("b"), opened)
        rule.onNodeWithTag(Tags.NEW_SESSION).performClick()
        assertEquals(1, news)
    }

    @Test fun renameFromTheRowMenu() {
        show()
        rule.onNodeWithTag(Tags.SESSION_MENU + "b").performClick()
        rule.onNodeWithTag(Tags.RENAME).performClick()
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextClearance()
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextInput("   ")
        rule.onNodeWithTag(Tags.RENAME_SAVE).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextClearance()
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextInput("新名字")
        rule.onNodeWithTag(Tags.RENAME_SAVE).performClick()
        assertEquals(listOf("b" to "新名字"), renamed)
        rule.onNodeWithTag(Tags.RENAME_FIELD).assertDoesNotExist()
    }

    @Test fun deleteAsksFirst() {
        show()
        rule.onNodeWithTag(Tags.SESSION_MENU + "a").performClick()
        rule.onNodeWithTag(Tags.DELETE).performClick()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithText("刪除這個對話？").assertIsDisplayed()
        rule.onNodeWithTag(Tags.DELETE_CANCEL).performClick()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithTag(Tags.SESSION_MENU + "a").performClick()
        rule.onNodeWithTag(Tags.DELETE).performClick()
        rule.onNodeWithTag(Tags.DELETE_CONFIRM).performClick()
        assertEquals(listOf("a"), deleted)
    }

    @Test fun emptyListSaysSo() {
        show(emptyList())
        rule.onNodeWithText("還沒有對話").assertIsDisplayed()
        rule.onNodeWithTag(Tags.SESSION_SEARCH).assertIsDisplayed()
    }

    @Test fun searchTypingIsReported() {
        show()
        rule.onNodeWithTag(Tags.SESSION_SEARCH).performTextInput("找")
        assertEquals("找", query)
    }

    @Test fun closeButtonCloses() {
        show()
        rule.onNodeWithTag(Tags.SESSION_CLOSE).performClick()
        assertEquals(1, closes)
    }

    @Test fun layoutAt412dpKeepsLongTitlesOnOneLineAndControlsApart() {
        val long = "這是一個非常非常長的對話標題".repeat(8)
        show(listOf(DayGroup.TODAY to listOf(row("a", long, last = "很長的最後一句話".repeat(10), running = true), row("b", "短"))))
        val root = rule.onNodeWithTag(Tags.SESSION_LIST).getUnclippedBoundsInRoot()
        assertTrue(root.width <= 412.dp)
        val rowA = rule.onNodeWithTag(Tags.SESSION_ROW + "a").getUnclippedBoundsInRoot()
        val menuA = rule.onNodeWithTag(Tags.SESSION_MENU + "a").getUnclippedBoundsInRoot()
        assertTrue("row ends before its menu starts", rowA.right <= menuA.left)
        assertTrue("menu inside the screen", menuA.right <= 412.dp)
        assertTrue("two text lines at most, not a tall column: ${rowA.height}", rowA.height < 100.dp)
        val newB = rule.onNodeWithTag(Tags.NEW_SESSION).getUnclippedBoundsInRoot()
        val closeB = rule.onNodeWithTag(Tags.SESSION_CLOSE).getUnclippedBoundsInRoot()
        assertTrue("header controls do not overlap", newB.right <= closeB.left || closeB.right <= newB.left)
        assertTrue(newB.right <= 412.dp && closeB.right <= 412.dp)
        rule.onNodeWithTag(Tags.SESSION_MENU + "a").performClick()
        val ren = rule.onNodeWithTag(Tags.RENAME).getUnclippedBoundsInRoot()
        val del = rule.onNodeWithTag(Tags.DELETE).getUnclippedBoundsInRoot()
        assertTrue("menu actions do not overlap", ren.right <= del.left || del.right <= ren.left || ren.bottom <= del.top || del.bottom <= ren.top)
        assertTrue(del.right <= 412.dp && ren.right <= 412.dp)
    }

    /** Final review B2-M6: back closes the rename or delete card (and an open row menu) before it leaves the list. */
    @Test fun backClosesTheCardsAndTheMenuFirst() {
        show()
        rule.onNodeWithTag(Tags.SESSION_MENU + "a").performClick()
        rule.onNodeWithTag(Tags.DELETE).performClick()
        rule.onNodeWithTag(Tags.DELETE_CONFIRM).assertExists()
        rule.runOnIdle { back!!.onBackPressed() }
        rule.onNodeWithTag(Tags.DELETE_CONFIRM).assertDoesNotExist()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithTag(Tags.SESSION_MENU + "b").performClick()
        rule.onNodeWithTag(Tags.RENAME).performClick()
        rule.onNodeWithTag(Tags.RENAME_FIELD).assertExists()
        rule.runOnIdle { back!!.onBackPressed() }
        rule.onNodeWithTag(Tags.RENAME_FIELD).assertDoesNotExist()
        rule.onNodeWithTag(Tags.SESSION_MENU + "b").performClick()
        rule.onNodeWithTag(Tags.RENAME).assertExists()
        rule.runOnIdle { back!!.onBackPressed() }
        rule.onNodeWithTag(Tags.RENAME).assertDoesNotExist()
        assertEquals("the list itself did not close", 0, closes)
    }

    @Test fun sheetHasSevenActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            FogTheme(effectsFor(EffectLevel.SAVER, reducedMotion = true)) {
                ConversationListScreen(
                    groups, "", {}, {}, {}, { _, _ -> }, {}, {},
                    pinned = listOf(row("p", "釘住的")),
                    flags = { id -> RowFlags(pinned = id == "p", locked = id == "b", temporary = id == "a", project = "台股".takeIf { id == "a" }) },
                    actions = ListActions(
                        onPin = { id, on -> calls += "pin $id $on" }, onLock = { id, on -> calls += "lock $id $on" }, onMove = { calls += "move $it" },
                        onShelf = { calls += "shelf $it" }, onExport = { calls += "export $it" }, onNewTemporary = { calls += "temp" },
                    ),
                )
            }
        }
        rule.onNodeWithTag(Tags.SESSION_GROUP + "PINNED").assertExists()
        rule.onNodeWithTag(Tags.SESSION_ROW + "a-temp", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(Tags.SESSION_ROW + "a-project", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(Tags.SESSION_ROW + "b-locked", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("內容要解鎖才看得到", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(Tags.SESSION_MENU + "b").performClick()
        for (t in listOf(Tags.SHEET_PIN, Tags.SHEET_LOCK, Tags.SHEET_MOVE, Tags.RENAME, Tags.SHEET_SHELF, Tags.SHEET_EXPORT, Tags.DELETE)) rule.onNodeWithTag(t).assertExists()
        rule.onNodeWithText("解除鎖定").performClick()
        rule.onNodeWithTag(Tags.ROW_SHEET).assertDoesNotExist()
        rule.onNodeWithTag(Tags.NEW_MENU).performClick()
        rule.onNodeWithTag(Tags.NEW_TEMPORARY).performClick()
        assertEquals(listOf("lock b false", "temp"), calls)
    }
}
