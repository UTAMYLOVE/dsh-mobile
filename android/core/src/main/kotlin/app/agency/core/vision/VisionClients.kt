package app.agency.core.vision

import app.agency.core.llm.AnthropicClient
import app.agency.core.llm.CallChecks
import app.agency.core.llm.CompatEndpoint
import app.agency.core.llm.Fail
import app.agency.core.metering.Provider
import app.agency.core.metering.UsageParser
import app.agency.core.metering.UsageSink
import app.agency.core.net.HttpOutcome
import app.agency.core.net.Transport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/*
 * The only code that sends a photo (T15b). The chat clients in `llm/` stay text only (NoMediaInRequestsTest scans them); these
 * two share their transport (the one network gate), their checks (spend cap, key, call budget) and the usage meter, so a photo
 * call is metered and capped like any other.
 */

/**
 * DeepSeek's image model (api-docs.deepseek.com, Vision guide and the chat completion reference, read 2026-11-27):
 * `deepseek-flash` takes `image_url` content parts whose `url` is a base64 data URL (`data:image/jpeg;base64,…`); JPEG, PNG,
 * GIF and WebP; images are billed as input tokens with the text (at most 1024 tokens an image), so the meter books the
 * returned usage on [MODEL] at its usual price. JSON mode, thinking off.
 */
class DeepSeekVisionClient(
    private val transport: Transport,
    private val key: () -> String?,
    private val checks: CallChecks,
    private val usage: UsageSink,
    private val base: HttpUrl = CompatEndpoint.DEEPSEEK_BASE,
) : VisionClient {
    override suspend fun see(request: VisionRequest): VisionResult {
        val k = key()
        checks.blocked(k)?.let { return VisionResult.Failed(it) }
        checks.takeBudget()?.let { return VisionResult.Failed(it) }
        val url = base.newBuilder().addPathSegment("chat").addPathSegment("completions").build()
        val outcome = transport.postJson(url, mapOf("Authorization" to "Bearer ${CallChecks.usableKey(k)}"), body(request).toString())
        Fail.of(outcome)?.let { return VisionResult.Failed(it) }
        val text = (outcome as HttpOutcome.Response).body
        UsageParser.fromOpenAiJson(text)?.let { u -> runCatching { usage.record(Provider.DEEPSEEK, MODEL, u) } }
        val o = UsageParser.parseObject(text) ?: return VisionResult.Failed(Fail.BAD_RESPONSE)
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return VisionResult.Failed(Fail.BAD_RESPONSE)
        if ((choice["finish_reason"] as? JsonPrimitive)?.content == "content_filter") return VisionResult.Failed(Fail.REFUSED)
        val content = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return VisionResult.Failed(Fail.BAD_RESPONSE)
        return VisionResult.Ok(content)
    }

    internal fun body(r: VisionRequest): JsonObject = buildJsonObject {
        put("model", MODEL)
        put("max_tokens", r.maxTokens)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", r.system) })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    r.images.forEach { img -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", img.dataUrl()) }) }) }
                    add(buildJsonObject { put("type", "text"); put("text", r.prompt) })
                })
            })
        })
        put("thinking", buildJsonObject { put("type", "disabled") })
        put("temperature", TEMPERATURE)
        put("response_format", buildJsonObject { put("type", "json_object") })
    }

    companion object {
        const val MODEL = CompatEndpoint.DEEPSEEK_FLASH
        const val TEMPERATURE = 0.2
    }
}

/** Claude Messages image blocks (`type: image`, base64 `image/jpeg`) before the question, the answer through `json_schema`. */
class ClaudeVisionClient(
    private val transport: Transport,
    private val model: () -> String,
    private val key: () -> String?,
    private val checks: CallChecks,
    private val usage: UsageSink,
    private val base: HttpUrl = AnthropicClient.BASE,
) : VisionClient {
    override suspend fun see(request: VisionRequest): VisionResult {
        val k = key()
        checks.blocked(k)?.let { return VisionResult.Failed(it) }
        val m = model().trim().ifEmpty { return VisionResult.Failed(Fail.NO_SETUP) }
        checks.takeBudget()?.let { return VisionResult.Failed(it) }
        val headers = buildMap {
            put("x-api-key", CallChecks.usableKey(k)!!)
            put("anthropic-version", AnthropicClient.API_VERSION)
            if (m in AnthropicClient.SERVER_FALLBACK_MODELS) put("anthropic-beta", AnthropicClient.FALLBACK_BETA)
        }
        val url = base.newBuilder().addPathSegment("v1").addPathSegment("messages").build()
        val outcome = transport.postJson(url, headers, body(m, request).toString())
        Fail.of(outcome)?.let { return VisionResult.Failed(it) }
        val text = (outcome as HttpOutcome.Response).body
        val o = UsageParser.parseObject(text) ?: return VisionResult.Failed(Fail.BAD_RESPONSE)
        val served = (o["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: m
        UsageParser.fromJson(Provider.ANTHROPIC, text)?.let { u -> runCatching { usage.record(Provider.ANTHROPIC, served, u) } }
        if ((o["stop_reason"] as? JsonPrimitive)?.content == "refusal") return VisionResult.Failed(Fail.REFUSED)
        val content = o["content"] as? JsonArray ?: return VisionResult.Failed(Fail.BAD_RESPONSE)
        val reply = content.mapNotNull { b ->
            val block = b as? JsonObject ?: return@mapNotNull null
            if ((block["type"] as? JsonPrimitive)?.content != "text") null else (block["text"] as? JsonPrimitive)?.content
        }.joinToString("").takeIf { it.isNotBlank() } ?: return VisionResult.Failed(Fail.BAD_RESPONSE)
        return VisionResult.Ok(reply)
    }

    internal fun body(model: String, r: VisionRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", r.maxTokens)
        put("system", r.system)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    r.images.forEach { img ->
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject { put("type", "base64"); put("media_type", VisionImage.MIME); put("data", img.base64()) })
                        })
                    }
                    add(buildJsonObject { put("type", "text"); put("text", r.prompt) })
                })
            })
        })
        put("output_config", buildJsonObject {
            put("format", buildJsonObject { put("type", "json_schema"); put("schema", r.schema) })
            put("effort", "low")
        })
        if (model in AnthropicClient.SERVER_FALLBACK_MODELS) put("fallbacks", "default")
    }
}
