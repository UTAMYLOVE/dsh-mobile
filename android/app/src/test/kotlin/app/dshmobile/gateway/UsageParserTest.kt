package app.dshmobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsageParserTest {
    private fun merged(p: Provider, vararg events: String): Usage? {
        val acc = UsageAccumulator()
        for (e in events) UsageParser.fromSseEvent(p, e)?.let { acc.add(it) }
        return acc.current()
    }

    @Test fun nonStreamingJson() {
        val body = """{"id":"m","model":"x","content":[],"usage":{"input_tokens":10,"output_tokens":20,
            "cache_read_input_tokens":3,"cache_creation_input_tokens":4,"server_tool_use":{"web_search_requests":2}}}"""
        assertEquals(Usage(10, 20, 3, 4, 2), UsageParser.fromJson(Provider.ANTHROPIC, body))
    }

    @Test fun nonStreamingJsonDefaultsMissingFieldsToZero() {
        assertEquals(Usage(5, 6), UsageParser.fromJson(Provider.DEEPSEEK, """{"usage":{"input_tokens":5,"output_tokens":6}}"""))
    }

    @Test fun nonStreamingWithoutUsageOrBadJsonIsNull() {
        assertNull(UsageParser.fromJson(Provider.ANTHROPIC, """{"id":"m"}"""))
        assertNull(UsageParser.fromJson(Provider.ANTHROPIC, "not json"))
        assertNull(UsageParser.fromJson(Provider.ANTHROPIC, "[1,2]"))
        assertNull(UsageParser.fromJson(Provider.ANTHROPIC, ""))
    }

    @Test fun anthropicDeltaWithoutInputKeepsMessageStartInput() {
        val u = merged(
            Provider.ANTHROPIC,
            """{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1,"cache_read_input_tokens":7}}}""",
            """{"type":"content_block_delta","delta":{"text":"hi"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
        )
        assertEquals(Usage(inputTokens = 100, outputTokens = 42, cacheReadTokens = 7), u)
    }

    @Test fun deepseekDeltaIsFinalValueAndIsNotAddedToStart() {
        val u = merged(
            Provider.DEEPSEEK,
            """{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1}}}""",
            """{"type":"message_delta","usage":{"input_tokens":120,"output_tokens":50,"cache_read_input_tokens":30}}""",
        )
        assertEquals(Usage(inputTokens = 120, outputTokens = 50, cacheReadTokens = 30), u)
    }

    @Test fun webSearchRequestsFromServerToolUse() {
        val u = merged(
            Provider.ANTHROPIC,
            """{"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":1}}}""",
            """{"type":"message_delta","usage":{"output_tokens":9,"server_tool_use":{"web_search_requests":3}}}""",
        )
        assertEquals(3L, u?.webSearchRequests)
    }

    @Test fun eventWithoutUsageIsNull() {
        assertNull(UsageParser.fromSseEvent(Provider.ANTHROPIC, """{"type":"content_block_delta","delta":{"text":"hi"}}"""))
        assertNull(UsageParser.fromSseEvent(Provider.ANTHROPIC, """{"type":"ping"}"""))
        assertNull(UsageParser.fromSseEvent(Provider.ANTHROPIC, "[DONE]"))
        assertNull(UsageParser.fromSseEvent(Provider.ANTHROPIC, "{broken"))
    }

    @Test fun messageStartUsageIsReadFromMessage() {
        val update = UsageParser.fromSseEvent(
            Provider.ANTHROPIC,
            """{"type":"message_start","message":{"usage":{"input_tokens":8,"output_tokens":2}}}""",
        )
        assertNotNull(update)
        assertEquals(8L, update?.inputTokens)
    }

    @Test fun accumulatorIsNullUntilAnyUsageSeen() {
        assertNull(UsageAccumulator().current())
    }

    @Test fun scannerReassemblesLinesSplitAcrossFeeds() {
        val scanner = SseUsageScanner(Provider.DEEPSEEK)
        val bytes = "event: message_delta\r\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\r\n\r\n".toByteArray()
        for (b in bytes) scanner.feed(byteArrayOf(b), 0, 1)
        assertEquals(Usage(inputTokens = 0, outputTokens = 9), scanner.usage())
    }

    @Test fun scannerSurvivesAnOversizedLine() {
        val scanner = SseUsageScanner(Provider.ANTHROPIC)
        val huge = ByteArray(2 shl 20) { 'x'.code.toByte() }
        scanner.feed(huge, 0, huge.size)
        val tail = "\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":4}}\n".toByteArray()
        scanner.feed(tail, 0, tail.size)
        assertEquals(4L, scanner.usage()?.outputTokens)
    }
}
