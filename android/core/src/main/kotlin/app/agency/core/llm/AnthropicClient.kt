package app.agency.core.llm

import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import app.agency.core.metering.UsageParser
import app.agency.core.metering.UsageSink
import app.agency.core.net.HttpOutcome
import app.agency.core.net.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Claude Messages API (`POST /v1/messages`, `x-api-key`, `anthropic-version`). Shapes checked on 2026-11-22 for
 * `claude-sonnet-5-5`, `claude-haiku-5-5` and `claude-opus-5-5`:
 * - structured output through `output_config.format` (`json_schema`), with `output_config.effort` on every call (the
 *   request's [Effort]: `low` unless the caller asked for more; the model thinks adaptively within it);
 * - photos of [Turn.User.images] as base64 `image` blocks before that turn's text block (every model in [MODELS] takes them);
 *   media blocks inside an echoed assistant `content` are dropped;
 * - no `thinking` field (it cannot be turned off on `claude-opus-5-5`; `disabled` is a 400 on `claude-sonnet-5-5`), no
 *   `temperature` / `top_p` (non-default sampling is a 400 on these models);
 * - tools with `strict: true` and `tool_choice: auto` (forced `any` / `tool` is a 400);
 * - `stop_reason: refusal` is REFUSED; `claude-sonnet-5-5` and `claude-opus-5-5` also send `fallbacks: "default"` with the
 *   server-side fallback beta header (`claude-haiku-5-5` has no server-side fallback, so it never sends one);
 * - in a tool loop the whole assistant `content` (thinking and fallback blocks included) goes back unchanged.
 * Usage is priced on the model that ran each attempt. With server-side fallback the top-level `usage` covers only the attempt
 * that produced the returned message, and `usage.iterations` is the per-attempt record (a refused attempt can be billed, a
 * mid-output refusal always is). So when `iterations` is present each entry is booked on its own `model` (every entry names
 * one in the live sample); when an entry has none, a `fallback_message` falls back to the top-level `model` and any other
 * entry to the requested model. Without `iterations` the top-level `usage` is booked on the top-level `model` (the one that
 * answered).
 *
 * Unbilled pre-output declines are booked too (conservative): a decline before any output is billed only in some refusal
 * categories, which change over time, but its tokens are reported on its entry either way. Booking them over-meters, which
 * is the safe direction for the spend cap.
 */
class AnthropicClient(
    private val transport: Transport,
    private val model: () -> String,
    private val key: () -> String?,
    private val checks: CallChecks,
    private val usage: UsageSink,
    private val base: HttpUrl = BASE,
) : LlmClient {
    override suspend fun chat(request: ChatRequest): ChatResult {
        val k = key()
        checks.blocked(k)?.let { return ChatResult.Failed(it) }
        val m = model().trim().ifEmpty { return ChatResult.Failed(Fail.NO_SETUP) }
        checks.takeBudget()?.let { return ChatResult.Failed(it) }
        val headers = buildMap {
            put("x-api-key", CallChecks.usableKey(k)!!)
            put("anthropic-version", API_VERSION)
            if (m in SERVER_FALLBACK_MODELS) put("anthropic-beta", FALLBACK_BETA)
        }
        val url = base.newBuilder().addPathSegment("v1").addPathSegment("messages").build()
        val outcome = transport.postJson(url, headers, body(m, request).toString())
        Fail.of(outcome)?.let { return ChatResult.Failed(it) }
        val text = (outcome as HttpOutcome.Response).body
        val o = UsageParser.parseObject(text) ?: return ChatResult.Failed(Fail.BAD_RESPONSE)
        val u = UsageParser.fromJson(Provider.ANTHROPIC, text)
        val served = (o["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: m
        val attempts = iterations(o, requested = m, served = served)
        if (attempts.isNotEmpty()) {
            attempts.forEach { (model, iu) -> runCatching { usage.record(Provider.ANTHROPIC, model, iu) } }
        } else if (u != null) {
            runCatching { usage.record(Provider.ANTHROPIC, served, u) }
        }
        return parse(o, u)
    }

    /** `usage.iterations` as (model, usage) per attempt; empty when the response has none. */
    private fun iterations(o: JsonObject, requested: String, served: String): List<Pair<String, Usage>> {
        val list = ((o["usage"] as? JsonObject)?.get("iterations") as? JsonArray) ?: return emptyList()
        return list.mapNotNull { e ->
            val entry = e as? JsonObject ?: return@mapNotNull null
            val iu = UsageParser.ofUsageObject(entry)
            val model = entry.str("model") ?: if (entry.str("type") == "fallback_message") served else requested
            model to iu
        }
    }

    private fun JsonObject.str(k: String): String? = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun body(model: String, request: ChatRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("system", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", request.system)
                put("cache_control", buildJsonObject { put("type", "ephemeral") })
            })
        })
        put("messages", messages(request.turns))
        put("output_config", buildJsonObject {
            (request.output as? Output.Json)?.let { j ->
                put("format", buildJsonObject { put("type", "json_schema"); put("schema", j.schema) })
            }
            put("effort", request.effort.wire)
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.parameters)
                        put("strict", true)
                    })
                }
            })
            put("tool_choice", buildJsonObject { put("type", "auto") })
        }
        if (model in SERVER_FALLBACK_MODELS) put("fallbacks", "default")
    }

    /** Turns as alternating messages: consecutive entries of one side (tool results, then text) share one message. */
    private fun messages(turns: List<Turn>): JsonArray {
        val out = ArrayList<Pair<String, MutableList<JsonElement>>>()
        fun append(role: String, blocks: List<JsonElement>) {
            val last = out.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else out += role to blocks.toMutableList()
        }
        ToolPairing.repair(turns).forEach { t ->
            when (t) {
                is Turn.User -> append("user", t.images.map { Images.claudeBlock(it) } + textBlock(t.text))
                is Turn.ToolResult -> append("user", listOf(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", t.callId)
                    put("content", t.content)
                    if (t.isError) put("is_error", true)
                }))
                is Turn.AssistantText -> append("assistant", listOf(textBlock(t.text)))
                is Turn.Assistant -> append(
                    "assistant",
                    if (t.wire == Wire.ANTHROPIC && t.raw is JsonArray) Images.strip(t.raw) as JsonArray else rebuilt(t),
                )
            }
        }
        // Tool results go before any text in a user message.
        return buildJsonArray {
            out.forEach { (role, blocks) ->
                val ordered = if (role == "user") blocks.sortedBy { if (it.type() == "tool_result") 0 else 1 } else blocks
                add(buildJsonObject { put("role", role); put("content", JsonArray(ordered)) })
            }
        }
    }

    /** An assistant turn another provider made, as Messages blocks (its reasoning stays with that provider). */
    private fun rebuilt(t: Turn.Assistant): List<JsonElement> = buildList {
        t.text?.takeIf { it.isNotEmpty() }?.let { add(textBlock(it)) }
        t.toolCalls.forEach { c ->
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", c.id)
                put("name", c.name)
                put("input", runCatching { Json.parseToJsonElement(c.arguments) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
            })
        }
    }

    private fun parse(o: JsonObject, u: Usage?): ChatResult {
        if ((o["stop_reason"] as? JsonPrimitive)?.content == "refusal") return ChatResult.Failed(Fail.REFUSED)
        val content = o["content"] as? JsonArray ?: return ChatResult.Failed(Fail.BAD_RESPONSE)
        val text = content.filter { it.type() == "text" }.mapNotNull { ((it as JsonObject)["text"] as? JsonPrimitive)?.content }
            .joinToString("").takeIf { it.isNotBlank() }
        val calls = content.filter { it.type() == "tool_use" }.mapNotNull { b ->
            val block = b as JsonObject
            val id = (block["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val name = (block["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            ToolCall(id, name, (block["input"] as? JsonObject ?: JsonObject(emptyMap())).toString())
        }
        if (text == null && calls.isEmpty()) return ChatResult.Failed(Fail.BAD_RESPONSE)
        return ChatResult.Ok(text, calls, Turn.Assistant(Wire.ANTHROPIC, content, text, calls), u)
    }

    private fun textBlock(text: String) = buildJsonObject { put("type", "text"); put("text", text) }

    private fun JsonElement.type(): String? = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.content

    companion object {
        val BASE = "https://api.anthropic.com/".toHttpUrl()

        /** The current API version; nothing this client sends changed between it and 2023-06-01. */
        const val API_VERSION = "2026-10-21"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val MODELS = listOf(DEFAULT_MODEL, "claude-haiku-5-5", "claude-opus-5-5")
        val SERVER_FALLBACK_MODELS = setOf("claude-sonnet-5-5", "claude-opus-5-5")
    }
}
