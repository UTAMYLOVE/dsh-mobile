package app.agency.core.llm

import app.agency.core.metering.Provider
import app.agency.core.metering.UsageParser
import app.agency.core.metering.UsageSink
import app.agency.core.net.HttpOutcome
import app.agency.core.net.Transport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where and how an OpenAI-style chat completion is sent. [base] ends in `/`; the client appends `chat/completions`.
 * [strictToolsBase] is DeepSeek's beta base, where `strict: true` functions are enforced (null: tools go to [base], not strict).
 * [deepseek] turns on the DeepSeek fields (`thinking`, `reasoning_effort`) and metering; a generic service gets neither.
 */
data class CompatEndpoint(
    val base: HttpUrl,
    val model: String,
    val deepseek: Boolean,
    val jsonMode: Boolean,
    val tools: Boolean,
    val strictToolsBase: HttpUrl? = null,
) {
    companion object {
        val DEEPSEEK_BASE = "https://api.deepseek.com/".toHttpUrl()
        const val DEEPSEEK_FLASH = "deepseek-flash"
        const val DEEPSEEK_PRO = "deepseek-v4-pro"
        val DEEPSEEK_MODELS = listOf(DEEPSEEK_FLASH, DEEPSEEK_PRO)

        /** DeepSeek; [base] is injectable for tests. Strict tools live under `<base>beta/`. */
        fun deepseek(model: String = DEEPSEEK_FLASH, base: HttpUrl = DEEPSEEK_BASE) = CompatEndpoint(
            base = base, model = model, deepseek = true, jsonMode = true, tools = true,
            strictToolsBase = base.newBuilder().addPathSegment("beta").addPathSegment("").build(),
        )

        /**
         * His compatible service: https only (http only when [allowHttp], tests), a model id, and the two switches from settings.
         * An address he pasted with `/chat/completions` on the end is cut back to its base. Null when not usable.
         */
        fun generic(url: String, model: String, jsonMode: Boolean, tools: Boolean, allowHttp: Boolean = false): CompatEndpoint? {
            val raw = url.trim()
            if (model.isBlank()) return null
            val parsed = raw.toHttpUrlOrNull() ?: return null
            if (!allowHttp && (!raw.startsWith("https://", ignoreCase = true) || !parsed.isHttps)) return null
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            if (segments.takeLast(2) == listOf("chat", "completions")) repeat(2) { segments.removeAt(segments.lastIndex) }
            // No user info: a `user:pass@` he pasted is a credential and must not sit in a data class that prints itself.
            val base = parsed.newBuilder().username("").password("").query(null).fragment(null).encodedPath("/")
                .apply { segments.forEach { addPathSegment(it) } }.addPathSegment("").build()
            return CompatEndpoint(base, model.trim(), deepseek = false, jsonMode = jsonMode, tools = tools)
        }
    }
}

/**
 * DeepSeek and generic OpenAI-compatible chat completions (request and response shapes verified against
 * api-docs.deepseek.com on 2026-11-22: `thinking: {type}`, `reasoning_effort`, `response_format: json_object`, strict tools on
 * `/beta`, `reasoning_content` passed back on tool requests, `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`).
 *
 * - INTAKE / UTTERANCE / NUDGE (thinking OFF): `thinking: disabled`, temperature 0.2 (intake) or 0.7, JSON mode when asked.
 * - CHAT with tools and thinking LOW: strict functions on the beta base, `thinking: enabled`, `reasoning_effort: low`, no
 *   temperature (ignored in thinking mode). Every assistant message of the turn goes back exactly as it came
 *   ([Turn.Assistant.raw]: content, `reasoning_content`, `tool_calls`), otherwise DeepSeek answers 400.
 * - DeepSeek answers 400 to a thinking request with tools when any earlier assistant message lacks its `reasoning_content`
 *   (thinking_mode.md, "Tool Calls"): a [Turn.AssistantText] history line, a turn another provider made, or one made with
 *   thinking off. Such a request goes out with `thinking: disabled` (and the temperature) instead. It stays consistent for the
 *   rest of the turn, because replies to a non-thinking request carry no reasoning either. Without tools the reasoning of
 *   earlier turns is ignored by DeepSeek, so it does not matter there.
 * - A blank reply without tool calls (JSON mode can return only spaces) is BAD_RESPONSE, so the caller retries or falls back.
 * - The byte-for-byte echo is a DeepSeek requirement only: a generic service gets its assistant messages back as role,
 *   content, tool_calls and `reasoning_content` when the service returned one, because several compatible servers reject
 *   unknown message fields (`refusal`, `annotations`, `reasoning`…), while services that think by default require
 *   `reasoning_content` back on an assistant tool-call message (DeepSeek's convention) and answer 400 without it.
 *
 * [takeQuota] is the generic service's daily request cap (ruling Q8; it has no price, so nothing is metered for it). It
 * consumes one request of the day when it returns true, so it is called last, right before the request goes out.
 */
class OpenAiCompatClient(
    private val transport: Transport,
    private val endpoint: () -> CompatEndpoint?,
    private val key: () -> String?,
    private val checks: CallChecks,
    private val usage: UsageSink,
    private val takeQuota: () -> Boolean = { true },
) : LlmClient {
    override suspend fun chat(request: ChatRequest): ChatResult {
        val k = key()
        checks.blocked(k)?.let { return ChatResult.Failed(it) }
        val ep = endpoint() ?: return ChatResult.Failed(Fail.NO_SETUP)
        checks.takeBudget()?.let { return ChatResult.Failed(it) }
        if (!ep.deepseek && !takeQuota()) return ChatResult.Failed(Fail.CAP)
        val withTools = request.tools.isNotEmpty() && ep.tools
        val base = if (withTools) ep.strictToolsBase ?: ep.base else ep.base
        val url = base.newBuilder().addPathSegment("chat").addPathSegment("completions").build()
        val headers = mapOf("Authorization" to "Bearer ${CallChecks.usableKey(k)}")
        val outcome = transport.postJson(url, headers, body(ep, request, withTools).toString())
        Fail.of(outcome)?.let { return ChatResult.Failed(it) }
        val text = (outcome as HttpOutcome.Response).body
        val u = UsageParser.fromOpenAiJson(text)
        if (ep.deepseek && u != null) runCatching { usage.record(Provider.DEEPSEEK, ep.model, u) }
        return parse(text, if (withTools) request.tools.map { it.name }.toSet() else emptySet(), u)
    }

    internal fun body(ep: CompatEndpoint, request: ChatRequest, withTools: Boolean): JsonObject = buildJsonObject {
        val msgs = messages(ep, request)
        val thinking = ep.deepseek && request.thinking == Thinking.LOW && (!withTools || reasoningComplete(msgs))
        put("model", ep.model)
        put("max_tokens", request.maxTokens)
        put("messages", msgs)
        if (ep.deepseek) {
            put("thinking", buildJsonObject { put("type", if (thinking) "enabled" else "disabled") })
            if (thinking) put("reasoning_effort", "low")
        }
        if (!thinking) put("temperature", temperature(request.purpose))
        if (request.output is Output.Json && ep.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
        if (withTools) {
            put("tools", buildJsonArray {
                request.tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                            if (ep.strictToolsBase != null) put("strict", true)
                        })
                    })
                }
            })
        }
    }

    /** True when every assistant message carries its `reasoning_content` (a string; `null` counts as missing). */
    private fun reasoningComplete(msgs: JsonArray): Boolean = msgs.all { m ->
        val o = m as? JsonObject ?: return@all true
        o.str("role") != "assistant" || o.str("reasoning_content") != null
    }

    private fun messages(ep: CompatEndpoint, request: ChatRequest): JsonArray = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", request.system) })
        ToolPairing.repair(request.turns).forEach { t ->
            when (t) {
                is Turn.User -> add(buildJsonObject { put("role", "user"); put("content", t.text) })
                is Turn.AssistantText -> add(buildJsonObject { put("role", "assistant"); put("content", t.text) })
                is Turn.Assistant -> add(when {
                    t.wire != Wire.OPENAI || t.raw !is JsonObject -> rebuilt(t)
                    ep.deepseek -> t.raw
                    else -> rebuilt(t, reasoning = t.raw.str("reasoning_content"))
                })
                is Turn.ToolResult -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", t.callId)
                    put("content", t.content)
                })
            }
        }
    }

    /**
     * An assistant turn in plain chat-completion form: role, content, tool_calls, and `reasoning_content` when [reasoning] is
     * given (the string a generic service returned). Used for a turn another provider made (its reasoning stays with that
     * provider) and for every turn sent to a generic service.
     */
    private fun rebuilt(t: Turn.Assistant, reasoning: String? = null): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", t.text.orEmpty())
        if (reasoning != null) put("reasoning_content", reasoning)
        if (t.toolCalls.isNotEmpty()) put("tool_calls", toolCallsJson(t.toolCalls))
    }

    private fun toolCallsJson(calls: List<ToolCall>) = buildJsonArray {
        calls.forEach { c ->
            add(buildJsonObject {
                put("id", c.id)
                put("type", "function")
                put("function", buildJsonObject { put("name", c.name); put("arguments", c.arguments) })
            })
        }
    }

    private fun parse(body: String, offered: Set<String>, u: app.agency.core.metering.Usage?): ChatResult {
        val o = UsageParser.parseObject(body) ?: return ChatResult.Failed(Fail.BAD_RESPONSE)
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return ChatResult.Failed(Fail.BAD_RESPONSE)
        when (choice.str("finish_reason")) {
            "content_filter" -> return ChatResult.Failed(Fail.REFUSED)
            "insufficient_system_resource" -> return ChatResult.Failed(Fail.SERVER)
        }
        val message = choice["message"] as? JsonObject ?: return ChatResult.Failed(Fail.BAD_RESPONSE)
        var raw: JsonObject = message
        var text = contentText(message["content"])
        var calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { c ->
            val call = c as? JsonObject ?: return@mapNotNull null
            val fn = call["function"] as? JsonObject ?: return@mapNotNull null
            val id = call.str("id") ?: return@mapNotNull null
            val name = fn.str("name") ?: return@mapNotNull null
            ToolCall(id, name, fn.str("arguments") ?: "{}")
        }
        if (calls.isEmpty() && offered.isNotEmpty() && text != null) {
            InlineToolCallParser.parse(text, offered, inlineIdPrefix(o))?.let { p ->
                // The calls were written into the text: the message goes back with real tool_calls (so the tool results that
                // follow pair with them) and the markup removed; reasoning_content and every other field stay as they came.
                text = p.text
                calls = p.calls
                raw = JsonObject(message + mapOf<String, JsonElement>("content" to JsonPrimitive(p.text)) +
                    (if (p.calls.isEmpty()) emptyMap() else mapOf("tool_calls" to toolCallsJson(p.calls))))
            }
        }
        val visible = text?.takeIf { it.isNotBlank() }
        if (visible == null && calls.isEmpty()) return ChatResult.Failed(Fail.BAD_RESPONSE)
        return ChatResult.Ok(visible, calls, Turn.Assistant(Wire.OPENAI, raw, visible, calls), u)
    }

    /**
     * Ids for calls written into the text: `inline_<n>_<response id>_` then the parser's counter. Each response numbers its calls
     * from 1, so without this a loop of several steps would hold the same `inline_1` more than once (Claude needs unique
     * `tool_use` ids once such a turn is rebuilt for it). [n] is unique in the process; the response id is kept to
     * `[A-Za-z0-9_-]` and 32 characters.
     */
    private fun inlineIdPrefix(response: JsonObject): String {
        val id = response.str("id")?.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }?.take(32)
        return "inline_${inlineSeq.incrementAndGet()}" + (id?.takeIf { it.isNotEmpty() }?.let { "_$it" } ?: "")
    }

    private fun contentText(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else null
        is JsonArray -> e.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
        else -> null
    }

    private fun JsonObject.str(k: String): String? = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        val inlineSeq = java.util.concurrent.atomic.AtomicLong()

        /** Low for the data clerk, zero for the second-pass judge (a verdict, not wording), low for a quiz, 0.7 for wording. */
        fun temperature(p: Purpose): Double = when (p) {
            Purpose.INTAKE -> 0.2
            Purpose.JUDGE -> 0.0
            Purpose.QUIZ -> 0.3
            Purpose.VISION -> 0.2
            Purpose.UTTERANCE, Purpose.NUDGE, Purpose.CHAT -> 0.7
        }
    }
}
