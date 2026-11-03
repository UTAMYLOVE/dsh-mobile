package app.dshmobile.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptOriginTest {
    @Test fun aTopLevelSessionShowsItsTitle() {
        assertEquals("寫小說", promptOrigin("s1", titleOf = { if (it == "s1") "寫小說" else null }))
    }

    @Test fun anUntitledTopLevelSessionIsANewConversation() {
        assertEquals("新對話", promptOrigin("s1", titleOf = { null }, isTopLevel = { true }))
    }

    @Test fun anUnknownChildShowsSubagentByDefault() {
        assertEquals("子代理人", promptOrigin("child", titleOf = { null }))
    }

    @Test fun originOfIsUsedForChildren() {
        assertEquals("子代理人「查資料」（來自 寫小說）", promptOrigin("child", titleOf = { null }, originOf = { "子代理人「查資料」（來自 寫小說）" }))
    }

    @Test fun aTitleWinsOverOriginOf() {
        assertEquals("T", promptOrigin("s1", titleOf = { "T" }, originOf = { "x" }))
    }
}
