package app.dshmobile.coach

import app.dshmobile.gateway.CostMeter
import app.agency.core.metering.Provider
import app.agency.core.metering.UsageParser
import app.dshmobile.log.ErrorLog
import app.dshmobile.security.KeyVault
import java.io.IOException
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class Role { USER, ASSISTANT }

sealed interface Part {
    data class Text(val text: String) : Part
    /** Stage 2 (evidence photos). */
    data class Image(val mime: String, val base64: String) : Part
}

data class Turn(val role: Role, val parts: List<Part>) {
    companion object {
        fun user(text: String) = Turn(Role.USER, listOf(Part.Text(text)))
        fun assistant(text: String) = Turn(Role.ASSISTANT, listOf(Part.Text(text)))
    }
}

enum class Fail { NO_KEY, NO_SETUP, CAP, OFFLINE, KEY_REJECTED, RATE, SERVER, REFUSED, BAD_RESPONSE }

sealed interface ModelReply {
    data class Ok(val text: String) : ModelReply
    data class Failed(val why: Fail) : ModelReply
}

/** One completion. The coach builds exactly what is sent (system + turns); nothing else goes out. */
interface CoachModel {
    suspend fun complete(system: String, turns: List<Turn>, maxTokens: Int = 2048): ModelReply
}

/**
 * The coach's own client, independent of dsh (it works while dsh is stopped). DeepSeek and Claude speak the Anthropic Messages
 * format; the compatible service speaks OpenAI-style chat completions. The key is read from [vault] for each call, used for one
 * header and never kept, logged or shown; redirects are refused so the key never follows one to another host.
 */
class HttpCoachModel(
    private val http: OkHttpClient,
    private val vault: KeyVault,
    private val settings: CoachSettings,
    private val meter: CostMeter,
    private val upstreams: () -> Map<Provider, HttpUrl>,
    private val log: ErrorLog?,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.of("Asia/Taipei"),
    /** Tests only: MockWebServer speaks plain http. The app always requires https for the compatible service. */
    private val requireHttps: Boolean = true,
) : CoachModel {
    override suspend fun complete(system: String, turns: List<Turn>, maxTokens: Int): ModelReply = withContext(Dispatchers.IO) {
        val provider = settings.provider
        val reply = when (provider) {
            CoachProvider.DEEPSEEK -> anthropic(Provider.DEEPSEEK, settings.deepseekModel, system, turns, maxTokens, claude = false)
            CoachProvider.CLAUDE -> anthropic(Provider.ANTHROPIC, settings.claudeModel, system, turns, maxTokens, claude = true)
            CoachProvider.COMPAT -> compat(system, turns, maxTokens)
        }
        if (reply is ModelReply.Failed) log?.append("coach", "${provider.name} ${reply.why.name}")
        reply
    }

    private fun usableKey(k: String?): String? = k?.trim()?.takeIf { s -> s.isNotEmpty() && s.all { it in ' '..'~' } }

    private fun anthropic(p: Provider, model: String, system: String, turns: List<Turn>, maxTokens: Int, claude: Boolean): ModelReply {
        if (meter.isBlocked()) return ModelReply.Failed(Fail.CAP)
        val key = usableKey(vault.get(p)) ?: return ModelReply.Failed(Fail.NO_KEY)
        val base = upstreams()[p] ?: return ModelReply.Failed(Fail.NO_SETUP)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", buildJsonArray {
                turns.forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.role == Role.USER) "user" else "assistant")
                        put("content", buildJsonArray {
                            t.parts.forEach { part ->
                                when (part) {
                                    is Part.Text -> add(buildJsonObject { put("type", "text"); put("text", part.text) })
                                    is Part.Image -> add(buildJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject { put("type", "base64"); put("media_type", part.mime); put("data", part.base64) })
                                    })
                                }
                            }
                        })
                    })
                }
            })
            if (claude) put("output_config", buildJsonObject { put("effort", "low") })
        }
        val url = base.newBuilder().addPathSegments("v1/messages").build()
        val req = Request.Builder().url(url)
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val (code, text) = call(req) ?: return ModelReply.Failed(Fail.OFFLINE)
        status(code)?.let { return ModelReply.Failed(it) }
        UsageParser.fromJson(p, text)?.let { runCatching { meter.record(p, model, it) } }
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ModelReply.Failed(Fail.BAD_RESPONSE)
        if ((o["stop_reason"] as? JsonPrimitive)?.content == "refusal") return ModelReply.Failed(Fail.REFUSED)
        val out = (o["content"] as? JsonArray).orEmpty().mapNotNull { b ->
            (b as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.content == "text" }?.let { (it["text"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        }.joinToString("")
        return if (out.isBlank()) ModelReply.Failed(Fail.BAD_RESPONSE) else ModelReply.Ok(out)
    }

    private fun compat(system: String, turns: List<Turn>, maxTokens: Int): ModelReply {
        val raw = settings.compatUrl
        val base = raw.toHttpUrlOrNull()?.takeIf { !requireHttps || (raw.startsWith("https://") && it.isHttps) } ?: return ModelReply.Failed(Fail.NO_SETUP)
        val model = settings.compatModel.ifBlank { return ModelReply.Failed(Fail.NO_SETUP) }
        val key = usableKey(vault.getNamed(CoachSettings.COMPAT_KEY)) ?: return ModelReply.Failed(Fail.NO_KEY)
        if (!settings.takeCompatRequest(clock.instant().atZone(zone).toLocalDate().toString())) return ModelReply.Failed(Fail.CAP)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                turns.forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.role == Role.USER) "user" else "assistant")
                        val images = t.parts.filterIsInstance<Part.Image>()
                        if (images.isEmpty()) {
                            put("content", t.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text })
                        } else {
                            put("content", buildJsonArray {
                                t.parts.forEach { part ->
                                    when (part) {
                                        is Part.Text -> add(buildJsonObject { put("type", "text"); put("text", part.text) })
                                        is Part.Image -> add(buildJsonObject {
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject { put("url", "data:${part.mime};base64,${part.base64}") })
                                        })
                                    }
                                }
                            })
                        }
                    })
                }
            })
        }
        val url = base.newBuilder().addPathSegments("chat/completions").build()
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val (code, text) = call(req) ?: return ModelReply.Failed(Fail.OFFLINE)
        status(code)?.let { return ModelReply.Failed(it) }
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ModelReply.Failed(Fail.BAD_RESPONSE)
        val choice = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: return ModelReply.Failed(Fail.BAD_RESPONSE)
        if ((choice["finish_reason"] as? JsonPrimitive)?.content == "content_filter") return ModelReply.Failed(Fail.REFUSED)
        val content = (choice["message"] as? JsonObject)?.get("content")
        val out = when (content) {
            is JsonPrimitive -> if (content.isString) content.content else ""
            is JsonArray -> content.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.joinToString("")
            else -> ""
        }
        return if (out.isBlank()) ModelReply.Failed(Fail.BAD_RESPONSE) else ModelReply.Ok(out)
    }

    /** The status code and body, or null when the network failed. The body is never logged. */
    private fun call(req: Request): Pair<Int, String>? = try {
        http.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
    } catch (_: IOException) {
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        null // e.g. a bad header value: treated as unreachable, never a crash
    }

    private fun status(code: Int): Fail? = when {
        code in 200..299 -> null
        code == 401 || code == 403 -> Fail.KEY_REJECTED
        code == 429 -> Fail.RATE
        code >= 500 -> Fail.SERVER
        else -> Fail.BAD_RESPONSE
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
