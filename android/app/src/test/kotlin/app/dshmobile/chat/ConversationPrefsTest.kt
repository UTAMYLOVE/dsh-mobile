package app.dshmobile.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrefsTest {
    private fun row(id: String) = SessionRow(id, id, null, null, null, 0, false)

    @Test fun pinnedComeFirst() {
        val rows = listOf(row("a"), row("b"), row("c"), row("d"))
        val (top, rest) = ConversationPrefs.pinnedFirst(rows, listOf("c", "gone", "a"))
        assertEquals(listOf("c", "a"), top.map { it.id })
        assertEquals(listOf("b", "d"), rest.map { it.id })
    }

    @Test fun prefsSurviveRestart() {
        val file = File(Files.createTempDirectory("p").toFile(), ConversationPrefs.FILE_NAME)
        val p = ConversationPrefs(file)
        p.setPinned("a", true); p.setPinned("b", true); p.setLocked("a", true); p.setTemporary("t", true); p.setProject("a", "stocks")
        val q = ConversationPrefs(file)
        assertEquals(listOf("b", "a"), q.state.value.pinned)
        assertTrue(q.isLocked("a"))
        assertTrue(q.isTemporary("t"))
        assertEquals("stocks", q.state.value.projectOf["a"])
        q.forget("a")
        assertFalse(ConversationPrefs(file).isPinned("a"))
        file.writeText("{broken")
        assertEquals(ConvPrefs(), ConversationPrefs(file).state.value)
    }

    @Test fun temporaryLeadIsNotShownInTheBubble() {
        assertEquals("你好", ComposeMode.strip(ConversationPrefs.TEMPORARY_LEAD + "你好"))
        assertEquals("查天氣", ComposeMode.strip(ConversationPrefs.TEMPORARY_LEAD + ComposeMode.RESEARCH.apply("查天氣")))
    }

    @Test fun exportedMarkdownKeepsTheOrder() {
        val md = ConversationMarkdown.of("台股", listOf(ChatItem.User("e1", "外資？", emptyList()), ChatItem.Assistant("e2", 2, null, "賣超 300 億")))
        assertEquals("# 台股\n\n## 我\n\n外資？\n\n## AI\n\n賣超 300 億\n", md)
    }
}
