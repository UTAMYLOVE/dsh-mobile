package app.dshmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchingTest {
    private val items = listOf(
        ChatItem.User("e3", "台股呢？", emptyList()),
        ChatItem.Tool("e4", "web_search", "台股", ToolStatus.DONE),
        ChatItem.Assistant("e9", 9, null, "加權指數…"),
        ChatItem.User("e12", "那 2330 呢？", emptyList()),
        ChatItem.Assistant("e15", 15, null, "台積電…"),
    )

    @Test fun boundaryIsTheEventBeforeTheUserMessage() {
        assertEquals(2L, Branching.boundaryBefore(items, "e3"))
        assertEquals(2L, Branching.boundaryBefore(items, "e9"))
        assertEquals(11L, Branching.boundaryBefore(items, "e15"))
        assertEquals("那 2330 呢？", Branching.questionOf(items, "e15"))
    }

    /** A long conversation whose first question is paged out: dsh's turn outline still knows where that turn began. */
    @Test fun aPagedOutStartStillFindsTheBoundary() {
        val paged = listOf(ChatItem.Assistant("e40", 40, null, "第一則回覆"))
        assertEquals(36L, Branching.boundaryBefore(paged, "e40", outline = listOf(4L, 37L, 60L)))
        assertNull(Branching.boundaryBefore(paged, "e40"))
    }

    @Test fun branchKeepsARepliesAndLeavesAQuestionOut() {
        assertEquals(15L, Branching.branchPoint(items, "e15"))
        assertEquals(11L, Branching.branchPoint(items, "e12"))
        assertNull(Branching.branchPoint(items, "x1"))
    }
}
