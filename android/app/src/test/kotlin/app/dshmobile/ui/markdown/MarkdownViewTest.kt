package app.dshmobile.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownViewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rendersTableCellsAndCode() {
        val blocks = MarkdownParser.parse("| 名稱 | 值 |\n|---|---|\n| 甲 | 乙 |\n\n```kotlin\nval answer = 42\n```")
        rule.setContent { MarkdownView(blocks, onLink = {}) }
        rule.onNodeWithText("名稱").assertExists()
        rule.onNodeWithText("甲").assertExists()
        rule.onNodeWithText("乙").assertExists()
        rule.onNodeWithText("val answer = 42").assertExists()
    }

    @Test fun tappingALinkCallsOnLink() {
        val opened = mutableListOf<String>()
        val blocks = MarkdownParser.parse("[點我](https://example.com/a)")
        rule.setContent { MarkdownView(blocks, onLink = { opened += it }) }
        rule.onNodeWithText("點我").performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/a"), opened)
    }

    @Test fun wideTablesScrollHorizontally() {
        val cols = 20
        val md = (1..cols).joinToString("|", "|", "|") { "標題$it" } + "\n" +
            (1..cols).joinToString("|", "|", "|") { "---" } + "\n" +
            (1..cols).joinToString("|", "|", "|") { "內容$it" }
        val blocks = MarkdownParser.parse(md)
        rule.setContent { Box { MarkdownView(blocks, onLink = {}) } }
        rule.onAllNodes(hasScrollAction()).assertCountEquals(1)
    }

    @Test fun aHundredThousandCharacterReplyParsesAndRenders() {
        val paragraphs = (1..100).map { i ->
            val body = "字".repeat(1_000 - 8)
            if (i == 100) body + "END-MARK" else body + "%07d.".format(i).takeLast(8)
        }
        val text = paragraphs.joinToString("\n\n")
        assertTrue(paragraphs.all { it.length == 1_000 })
        val start = System.nanoTime()
        val blocks = MarkdownParser.parse(text)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("parse took $ms ms", ms < 1_000)
        assertEquals(100, blocks.size)
        rule.setContent { MarkdownView(blocks, onLink = {}) }
        rule.onNodeWithText("END-MARK", substring = true).assertExists()
    }

    @Test fun fourDigitOrderedMarkersDoNotWrap() {
        val blocks = MarkdownParser.parse("1000. a\n1001. b")
        assertTrue(listMarkerWidth(blocks[0] as MdBlock.ListBlock) >= 50.dp)
        rule.setContent { MarkdownView(blocks, onLink = {}) }
        rule.onNodeWithText("1000.").assertExists()
        val oneLine = rule.onNodeWithText("a").fetchSemanticsNode().size.height
        val marker = rule.onNodeWithText("1000.").fetchSemanticsNode().size.height
        assertEquals("marker wrapped", oneLine, marker)
    }
}
