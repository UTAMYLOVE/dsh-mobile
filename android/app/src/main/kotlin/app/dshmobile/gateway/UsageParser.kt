package app.dshmobile.gateway

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

/** Reads usage from Anthropic Messages bodies; DeepSeek's Anthropic endpoint uses the same format. */
object UsageParser {
    private val json = Json

    /** Usage of a non-streaming response (top-level `usage`), or null when absent or the body is not a JSON object. */
    @Suppress("UNUSED_PARAMETER") // both providers share one format
    fun fromJson(p: Provider, body: String): Usage? {
        val usage = parseObject(body)?.get("usage") as? JsonObject ?: return null
        return UsageAccumulator().apply { add(toUpdate(usage)) }.current()
    }

    /** Usage fields of a `message_start` (inside `message`) or `message_delta` event; null for any other event or bad JSON. */
    @Suppress("UNUSED_PARAMETER") // both providers share one format
    fun fromSseEvent(p: Provider, data: String): UsageUpdate? {
        val event = parseObject(data) ?: return null
        val usage = when ((event["type"] as? JsonPrimitive)?.content) {
            "message_start" -> (event["message"] as? JsonObject)?.get("usage")
            "message_delta" -> event["usage"]
            else -> null
        } as? JsonObject ?: return null
        return toUpdate(usage).takeIf { it != UsageUpdate() }
    }

    private fun parseObject(text: String): JsonObject? =
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
}

/**
 * Listens to an SSE byte stream (fed in arbitrary pieces) and accumulates the usage in its `data:` lines.
 * Each line is capped so a malformed stream cannot grow memory.
 */
class SseUsageScanner(private val provider: Provider) {
    private val accumulator = UsageAccumulator()
    private val line = java.io.ByteArrayOutputStream()
    private var skipping = false

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

    fun usage(): Usage? = accumulator.current()

    private fun finishLine() {
        val text = line.toString(Charsets.UTF_8).trimEnd('\r')
        if (!text.startsWith("data:")) return
        val data = text.removePrefix("data:").removePrefix(" ")
        UsageParser.fromSseEvent(provider, data)?.let(accumulator::add)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val MAX_LINE_BYTES = 1 shl 20
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
