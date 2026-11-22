package app.agency.core.metering

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

    private fun scan(vararg events: String): Usage? {
        val scanner = SseUsageScanner(Provider.ANTHROPIC)
        val bytes = events.joinToString("") { "data: $it\n\n" }.toByteArray()
        scanner.feed(bytes, 0, bytes.size)
        return scanner.usage()
    }

    private val start = """{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1}}}"""
    private val text = """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"${"a".repeat(300)}"}}"""
    private val thinking = """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"${"想".repeat(30)}"}}"""
    private val toolInput = """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.md\"}"}}"""

    /** H6: a reply stopped before its final `message_delta` booked ~1 output token; what it streamed is billed too. */
    @Test fun aStreamCutBeforeItsFinalCountEstimatesItsOutputFromTheDeltas() {
        // 300 ASCII bytes, 30 Chinese characters (90 bytes) and 15 bytes of tool input: 405 bytes, one token per 3.
        val u = scan(start, thinking, text, toolInput)
        assertEquals(Usage(inputTokens = 100, outputTokens = 135), u)
    }

    @Test fun theFinalCountWinsOverTheEstimate() {
        val u = scan(start, thinking, text, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""")
        assertEquals(42L, u?.outputTokens)
    }

    @Test fun aCutStreamWithoutDeltasKeepsWhatMessageStartSaid() {
        assertEquals(Usage(inputTokens = 100, outputTokens = 1), scan(start, """{"type":"ping"}"""))
        assertNull(scan(text))
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
