package app.dshmobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSummaryTest {
    private fun o(s: String) = Json.parseToJsonElement(s) as JsonObject

    /** Owner screenshot, 2026-11-04: 「present「{"files":[{"description":"主…」 — raw JSON must never show. */
    @Test fun listArgumentsAndUnknownShapesNeverShowRawJson() {
        assertEquals("主報告 等 2 項", ConversationReducer.summary("present", o("""{"files":[{"description":"主報告","path":"a.md"},{"path":"b.md"}]}"""), null))
        assertEquals("a.md", ConversationReducer.summary("present", o("""{"files":[{"path":"a.md"}]}"""), null))
        assertEquals("ui/", ConversationReducer.summary("x", o("""{"n":3,"scope":"ui/"}"""), null))
        assertEquals("", ConversationReducer.summary("x", o("""{"n":3}"""), null))
        assertEquals("0050", ConversationReducer.summary("grep", o("""{"pattern":"0050"}"""), null))
    }

    /** Owner screenshot, 2026-11-05: the war room's decisions read 「新任務：**可轉債…」. Commands keep every character. */
    @Test fun proseLosesMarkdownMarksButCommandsDoNot() {
        assertEquals("新任務：可轉債反稀釋調整 第二行", ConversationReducer.summary("send_message", o("""{"message":"新任務：**可轉債反稀釋調整**\n- 第二行"}"""), null))
        assertEquals("rg '**/*.kt'", ConversationReducer.summary("bash", o("""{"command":"rg '**/*.kt'"}"""), null))
    }
}
