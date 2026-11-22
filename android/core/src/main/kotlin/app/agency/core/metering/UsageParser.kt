package app.agency.core.metering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Token and tool usage of one model request. */
data class Usage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val webSearchRequests: Long = 0,
)

/** The usage fields one SSE event carried; a null field was absent and leaves the earlier value alone. */
data class UsageUpdate(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val webSearchRequests: Long? = null,
)

/**
 * Merges stream events: a later event overrides the fields it has and keeps the rest.
 * Never adds, because `message_delta` usage is the cumulative (or final) value, not an increment.
 */
class UsageAccumulator {
    private var latest: UsageUpdate? = null

    fun add(update: UsageUpdate) {
        val old = latest
        latest = if (old == null) update else UsageUpdate(
            inputTokens = update.inputTokens ?: old.inputTokens,
            outputTokens = update.outputTokens ?: old.outputTokens,
            cacheReadTokens = update.cacheReadTokens ?: old.cacheReadTokens,
            cacheWriteTokens = update.cacheWriteTokens ?: old.cacheWriteTokens,
            webSearchRequests = update.webSearchRequests ?: old.webSearchRequests,
        )
    }

    /** The merged usage, or null while no event has carried any. */
    fun current(): Usage? = latest?.let {
        Usage(
            inputTokens = it.inputTokens ?: 0,
            outputTokens = it.outputTokens ?: 0,
            cacheReadTokens = it.cacheReadTokens ?: 0,
            cacheWriteTokens = it.cacheWriteTokens ?: 0,
            webSearchRequests = it.webSearchRequests ?: 0,
        )
    }
}

/** Reads usage from Anthropic Messages bodies (DeepSeek's Anthropic endpoint uses the same format) and from chat completions. */
object UsageParser {
    private val json = Json

    /** Usage of a non-streaming response (top-level `usage`), or null when absent or the body is not a JSON object. */
    @Suppress("UNUSED_PARAMETER") // both providers share one format
    fun fromJson(p: Provider, body: String): Usage? {
        val usage = parseObject(body)?.get("usage") as? JsonObject ?: return null
        return UsageAccumulator().apply { add(toUpdate(usage)) }.current()
    }

    /**
     * Usage of an OpenAI-style chat completion (top-level `usage`), or null when absent or not JSON. DeepSeek reports
     * `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` (verified against api-docs.deepseek.com, 2026-11-22): the misses
     * become [Usage.inputTokens] and the hits [Usage.cacheReadTokens], which is how [PriceTable] prices DeepSeek. A service
     * without those fields reports `prompt_tokens` and maybe `prompt_tokens_details.cached_tokens`; the cached part is split out.
     */
    fun fromOpenAiJson(body: String): Usage? {
        val usage = parseObject(body)?.get("usage") as? JsonObject ?: return null
        val output = usage.long("completion_tokens") ?: 0
        val hit = usage.long("prompt_cache_hit_tokens")
        val miss = usage.long("prompt_cache_miss_tokens")
        if (hit != null || miss != null) return Usage(inputTokens = miss ?: 0, outputTokens = output, cacheReadTokens = hit ?: 0)
        val prompt = usage.long("prompt_tokens") ?: 0
        val cached = ((usage["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0).coerceIn(0, prompt)
        return Usage(inputTokens = prompt - cached, outputTokens = output, cacheReadTokens = cached)
    }

    /** Usage fields of a `message_start` (inside `message`) or `message_delta` event; null for any other event or bad JSON. */
    fun fromSseEvent(p: Provider, data: String): UsageUpdate? = parseObject(data)?.let { usageOf(p, it) }

    /** [fromSseEvent] for an event already parsed. */
    @Suppress("UNUSED_PARAMETER") // both providers share one format
    internal fun usageOf(p: Provider, event: JsonObject): UsageUpdate? {
        val usage = when ((event["type"] as? JsonPrimitive)?.content) {
            "message_start" -> (event["message"] as? JsonObject)?.get("usage")
            "message_delta" -> event["usage"]
            else -> null
        } as? JsonObject ?: return null
        return toUpdate(usage).takeIf { it != UsageUpdate() }
    }

    /**
     * UTF-8 bytes of the generated content a `content_block_delta` carried (`text`, tool input `partial_json`, `thinking`); 0 for
     * any other event. A stream cut before its final `message_delta` has no other trace of its output.
     */
    internal fun streamedBytes(event: JsonObject): Long {
        if ((event["type"] as? JsonPrimitive)?.content != "content_block_delta") return 0
        val delta = event["delta"] as? JsonObject ?: return 0
        return STREAMED_FIELDS.sumOf { k -> (delta[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::utf8Length) ?: 0L }
    }

    internal fun parseObject(text: String): JsonObject? =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null // SerializationException is an IllegalArgumentException
        }

    private fun toUpdate(usage: JsonObject) = UsageUpdate(
        inputTokens = usage.long("input_tokens"),
        outputTokens = usage.long("output_tokens"),
        cacheReadTokens = usage.long("cache_read_input_tokens"),
        cacheWriteTokens = usage.long("cache_creation_input_tokens"),
        webSearchRequests = (usage["server_tool_use"] as? JsonObject)?.long("web_search_requests"),
    )

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private val STREAMED_FIELDS = listOf("text", "partial_json", "thinking")

    private fun utf8Length(s: String): Long {
        var n = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }
}

/**
 * Listens to an SSE byte stream (fed in arbitrary pieces) and accumulates the usage in its `data:` lines.
 * Each line is capped so a malformed stream cannot grow memory.
 *
 * The output count arrives only in the final `message_delta`; until then `message_start` says ~1. A reply stopped or cut off
 * before the end (STOP, a lost connection) used to book that 1, though the provider bills every token it generated (thousands of
 * thinking tokens at high effort). Without the final count, the output is estimated from the streamed content instead.
 */
class SseUsageScanner(private val provider: Provider) {
    private val accumulator = UsageAccumulator()
    private val line = java.io.ByteArrayOutputStream()
    private var skipping = false
    private var sawFinal = false
    private var streamedBytes = 0L

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val b = bytes[i]
            if (b == NEWLINE) {
                if (!skipping) finishLine()
                line.reset()
                skipping = false
            } else if (!skipping) {
                if (line.size() >= MAX_LINE_BYTES) {
                    skipping = true
                    line.reset()
                } else {
                    line.write(b.toInt())
                }
            }
        }
    }

    /**
     * The usage seen so far. Without the final output count, one token per [BYTES_PER_TOKEN] bytes of streamed content (about
     * one per Chinese character). Only an estimate, and low when thinking is summarized or omitted: the provider bills the full
     * thinking, which it does not stream.
     */
    fun usage(): Usage? = accumulator.current()?.let { u ->
        if (sawFinal) u else u.copy(outputTokens = maxOf(u.outputTokens, streamedBytes / BYTES_PER_TOKEN))
    }

    private fun finishLine() {
        val text = line.toString(Charsets.UTF_8).trimEnd('\r')
        if (!text.startsWith("data:")) return
        val event = UsageParser.parseObject(text.removePrefix("data:").removePrefix(" ")) ?: return
        UsageParser.usageOf(provider, event)?.let { update ->
            accumulator.add(update)
            if (update.outputTokens != null && (event["type"] as? JsonPrimitive)?.content == "message_delta") sawFinal = true
        }
        streamedBytes += UsageParser.streamedBytes(event)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val MAX_LINE_BYTES = 1 shl 20
        const val BYTES_PER_TOKEN = 3L
    }
}

/**
 * Collects the usage of one upstream response as its body streams past: SSE bodies are scanned event by event,
 * JSON bodies are buffered (up to [MAX_JSON_BYTES]) and parsed once at the end, anything else is ignored.
 */
class UsageTap(private val provider: Provider, contentType: String?) {
    private enum class Mode { SSE, JSON, NONE }

    private val mode = when {
        contentType == null -> Mode.NONE
        contentType.startsWith("text/event-stream", ignoreCase = true) -> Mode.SSE
        contentType.contains("json", ignoreCase = true) -> Mode.JSON
        else -> Mode.NONE
    }
    private val scanner = SseUsageScanner(provider)
    private val jsonBody = java.io.ByteArrayOutputStream()
    private var jsonOverflow = false

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        when (mode) {
            Mode.SSE -> scanner.feed(bytes, offset, length)
            Mode.JSON -> if (!jsonOverflow) {
                if (jsonBody.size() + length > MAX_JSON_BYTES) jsonOverflow = true else jsonBody.write(bytes, offset, length)
            }
            Mode.NONE -> Unit
        }
    }

    /** Usage seen so far, or null when none. Valid after a clean end and after an abort. */
    fun usage(): Usage? = when (mode) {
        Mode.SSE -> scanner.usage()
        Mode.JSON -> if (jsonOverflow) null else UsageParser.fromJson(provider, jsonBody.toString(Charsets.UTF_8))
        Mode.NONE -> null
    }

    private companion object {
        const val MAX_JSON_BYTES = 8 shl 20
    }
}
