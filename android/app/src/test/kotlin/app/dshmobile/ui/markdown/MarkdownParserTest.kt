package app.dshmobile.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun links(text: AnnotatedString): List<String> =
        text.getLinkAnnotations(0, text.length).mapNotNull { (it.item as? LinkAnnotation.Clickable)?.tag }

    private fun tagged(text: AnnotatedString): List<String> =
        text.getStringAnnotations(LINK_TAG, 0, text.length).map { it.item }

    @Test fun boldItalicCodeAndLinksBecomeSpans() {
        val blocks = MarkdownParser.parse("**粗** *斜* `c` [a](https://x.y) ~~刪~~")
        assertEquals(1, blocks.size)
        val text = (blocks.single() as MdBlock.Paragraph).text
        assertEquals("粗 斜 c a 刪", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "粗" })
        assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic && text.text.substring(it.start, it.end) == "斜" })
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && text.text.substring(it.start, it.end) == "c" })
        assertTrue(text.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough && text.text.substring(it.start, it.end) == "刪" })
        assertEquals(listOf("https://x.y"), links(text))
        assertEquals(listOf("https://x.y"), tagged(text))
    }

    @Test fun listsKeepOrderAndStart() {
        val list = MarkdownParser.parse("3. a\n4. b").single() as MdBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
        assertEquals("a", ((list.items[0].single()) as MdBlock.Paragraph).text.text)
        assertEquals("b", ((list.items[1].single()) as MdBlock.Paragraph).text.text)
        val bullets = MarkdownParser.parse("- x\n- y").single() as MdBlock.ListBlock
        assertFalse(bullets.ordered)
        assertEquals(1, bullets.start)
    }

    @Test fun gfmTablesParse() {
        val table = MarkdownParser.parse("| a | b |\n|---|---|\n| 1 | 2 |").single() as MdBlock.Table
        assertEquals(listOf("a", "b"), table.header.map { it.text })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { r -> r.map { it.text } })
    }

    @Test fun fencedCodeKeepsLanguageAndText() {
        val code = MarkdownParser.parse("```kotlin\nval x = 1\nprintln(x)\n```").single() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertNull((MarkdownParser.parse("```\nplain\n```").single() as MdBlock.Code).language)
    }

    @Test fun anUnclosedFenceWhileStreamingIsStillACodeBlock() {
        val blocks = MarkdownParser.parse("前言\n\n```py\nprint(1)\nprint(")
        assertEquals(2, blocks.size)
        val code = blocks[1] as MdBlock.Code
        assertEquals("py", code.language)
        assertEquals("print(1)\nprint(", code.code)
    }

    @Test fun rawHtmlIsShownAsText() {
        val block = (MarkdownParser.parse("<script>x</script>").single() as MdBlock.Paragraph).text
        assertTrue(block.text.contains("<script>"))
        val inline = (MarkdownParser.parse("hi <b>there</b>").single() as MdBlock.Paragraph).text
        assertEquals("hi <b>there</b>", inline.text)
    }

    @Test fun imagesAreNotLoadedButShownAsLinks() {
        val text = (MarkdownParser.parse("![貓](https://x/cat.png)").single() as MdBlock.Paragraph).text
        assertEquals("〔圖片〕貓", text.text)
        assertEquals(listOf("https://x/cat.png"), tagged(text))
        assertEquals(listOf("https://x/cat.png"), links(text))
    }

    @Test fun nonHttpLinksAreNotClickable() {
        val text = (MarkdownParser.parse("[x](javascript:alert(1)) [y](file:///etc)").single() as MdBlock.Paragraph).text
        assertEquals("x y", text.text)
        assertTrue(links(text).isEmpty())
        assertTrue(tagged(text).isEmpty())
    }

    @Test fun hugeInputIsCappedWithANotice() {
        val input = "字".repeat(300_000)
        val start = System.nanoTime()
        val blocks = MarkdownParser.parse(input)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("parse took $ms ms", ms < 2_000)
        assertEquals("（內容過長，只顯示前 20 萬字）", (blocks.last() as MdBlock.Paragraph).text.text)
        val shown = blocks.dropLast(1).sumOf { (it as MdBlock.Paragraph).text.length }
        assertEquals(MarkdownParser.MAX_CHARS, shown)
    }

    @Test fun aTwentyColumnTableParses() {
        val cols = 20
        val md = (1..cols).joinToString("|", "|", "|") { "h$it" } + "\n" +
            (1..cols).joinToString("|", "|", "|") { "---" } + "\n" +
            (1..cols).joinToString("|", "|", "|") { "c$it" }
        val table = MarkdownParser.parse(md).single() as MdBlock.Table
        assertEquals(cols, table.header.size)
        assertEquals(cols, table.rows.single().size)
        assertEquals("c20", table.rows.single().last().text)
    }

    @Test fun headingsQuotesRulesAndNestingAreBounded() {
        val blocks = MarkdownParser.parse("## 標題\n\n> 引用\n\n---")
        assertEquals(2, (blocks[0] as MdBlock.Heading).level)
        assertTrue(blocks[1] is MdBlock.Quote)
        assertEquals(MdBlock.Rule, blocks[2])
        // Pathological nesting must neither overflow the stack nor throw.
        assertTrue(MarkdownParser.parse(">".repeat(50_000) + " x").isNotEmpty())
        assertTrue(MarkdownParser.parse("*a ".repeat(30_000)).isNotEmpty())
    }

    @Test fun aHugeTableIsCappedWithARemainderRow() {
        val md = "| a | b |\n|---|---|\n" + (1..600).joinToString("\n") { "| r$it | x |" }
        val table = MarkdownParser.parse(md).single() as MdBlock.Table
        assertEquals(MarkdownParser.MAX_ROWS + 1, table.rows.size)
        assertEquals("r500", table.rows[499][0].text)
        assertEquals("…還有 100 列", table.rows.last()[0].text)
    }

    @Test fun aHugeListIsCappedWithARemainderItem() {
        val list = MarkdownParser.parse((1..650).joinToString("\n") { "- i$it" }).single() as MdBlock.ListBlock
        assertEquals(MarkdownParser.MAX_ITEMS + 1, list.items.size)
        assertEquals("…還有 150 項", (list.items.last().single() as MdBlock.Paragraph).text.text)
        val exact = MarkdownParser.parse((1..500).joinToString("\n") { "- i$it" }).single() as MdBlock.ListBlock
        assertEquals(500, exact.items.size)
    }

    @Test fun truncationNeverSplitsASurrogatePair() {
        val input = "a".repeat(MarkdownParser.MAX_CHARS - 1) + "\uD83D\uDE00" + "tail"
        val blocks = MarkdownParser.parse(input)
        val shown = blocks.dropLast(1).joinToString("") { (it as MdBlock.Paragraph).text.text }
        assertEquals(MarkdownParser.MAX_CHARS - 1, shown.length)
        assertFalse(Character.isHighSurrogate(shown.last()))
        assertEquals("（內容過長，只顯示前 20 萬字）", (blocks.last() as MdBlock.Paragraph).text.text)
    }
}
