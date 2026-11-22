package app.agency.core.llm

import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolPairingAndInlineTest {
    private fun assistant(vararg ids: String) = Turn.Assistant(Wire.OPENAI, JsonNull, null, ids.map { ToolCall(it, "get_today", "{}") })

    @Test fun pairingKeepsAWellFormedListAsItIs() {
        val turns = listOf(Turn.User("a"), assistant("1"), Turn.ToolResult("1", "{}"), Turn.AssistantText("好"), Turn.User("b"))
        assertEquals(turns, ToolPairing.repair(turns))
    }

    @Test fun pairingAnswersMissingCallsBeforeTheNextTurnAndAtTheEnd() {
        val a = assistant("1", "2")
        val b = assistant("3")
        val out = ToolPairing.repair(listOf(a, Turn.ToolResult("2", "{}"), Turn.User("x"), b))
        assertEquals(
            listOf(a, Turn.ToolResult("2", "{}"), Turn.ToolResult("1", ToolPairing.MISSING_RESULT, true), Turn.User("x"), b,
                Turn.ToolResult("3", ToolPairing.MISSING_RESULT, true)),
            out,
        )
    }

    @Test fun pairingDropsStrayDuplicateAndLateResults() {
        val a = assistant("1")
        val out = ToolPairing.repair(listOf(Turn.ToolResult("0", "{}"), a, Turn.ToolResult("1", "{}"), Turn.ToolResult("1", "{}"),
            Turn.User("x"), Turn.ToolResult("1", "{}")))
        assertEquals(listOf(a, Turn.ToolResult("1", "{}"), Turn.User("x")), out)
    }

    @Test fun inlineTaggedCallsWithObjectOrStringArguments() {
        val p = InlineToolCallParser.parse(
            "先查。<tool_call>{\"name\":\"lookup_food\",\"arguments\":{\"query\":\"雞胸肉\"}}</tool_call>" +
                "<tool_call>{\"name\":\"lookup_food\",\"arguments\":\"{\\\"query\\\":\\\"豆漿\\\"}\"}</tool_call>",
            setOf("lookup_food"),
        )!!
        assertEquals(listOf(ToolCall("inline_1", "lookup_food", """{"query":"雞胸肉"}"""), ToolCall("inline_2", "lookup_food", """{"query":"豆漿"}""")), p.calls)
        assertEquals("先查。", p.text)
    }

    @Test fun inlineNativeTokensBothForms() {
        val newer = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>get_today<｜tool▁sep｜>{}<｜tool▁call▁end｜><｜tool▁calls▁end｜>"
        assertEquals(listOf(ToolCall("inline_1", "get_today", "{}")), InlineToolCallParser.parse(newer, setOf("get_today"))!!.calls)
        val older = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>lookup_food\n```json\n{\"query\":\"蛋\"}\n```" +
            "<｜tool▁call▁end｜><｜tool▁calls▁end｜>"
        val p = InlineToolCallParser.parse(older, setOf("lookup_food"))!!
        assertEquals(listOf(ToolCall("inline_1", "lookup_food", """{"query":"蛋"}""")), p.calls)
        assertEquals("", p.text)
    }

    @Test fun inlineUnknownNamesAndBadArgumentsAreDroppedButTheMarkupIsStillRemoved() {
        val p = InlineToolCallParser.parse("好。<tool_call>{\"name\":\"rm\",\"arguments\":{}}</tool_call><tool_call>{\"name\":\"get_today\",\"arguments\":[1]}</tool_call>", setOf("get_today"))!!
        assertEquals(emptyList<ToolCall>(), p.calls)
        assertEquals("好。", p.text)
        assertNull(InlineToolCallParser.parse("沒有工具呼叫", setOf("get_today")))
    }
}
