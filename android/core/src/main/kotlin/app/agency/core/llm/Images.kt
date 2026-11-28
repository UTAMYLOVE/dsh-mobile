package app.agency.core.llm

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The only code that turns photo bytes into request blocks, and the filter that keeps media out of every other turn. Shapes are
 * the ones the 0.9 vision clients used against the live APIs:
 * - Claude: `{type: image, source: {type: base64, media_type: image/jpeg, data}}`, before the text block;
 * - DeepSeek (`deepseek-flash`, the one DeepSeek model documented to take images): `{type: image_url, image_url: {url:
 *   data:image/jpeg;base64,…}}` content parts, before the text part.
 */
internal object Images {
    const val MIME = "image/jpeg"

    private val MEDIA_TYPES = setOf("image", "image_url", "input_image", "document", "file", "audio", "input_audio")

    fun claudeBlock(jpeg: ByteArray): JsonObject = buildJsonObject {
        put("type", "image")
        put("source", buildJsonObject {
            put("type", "base64")
            put("media_type", MIME)
            put("data", Base64.getEncoder().encodeToString(jpeg))
        })
    }

    fun openAiPart(jpeg: ByteArray): JsonObject = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", "data:$MIME;base64," + Base64.getEncoder().encodeToString(jpeg)) })
    }

    /** True for a content block or part that carries a picture, a file or a sound. */
    fun isMedia(e: JsonElement): Boolean = ((e as? JsonObject)?.get("type") as? JsonPrimitive)?.content in MEDIA_TYPES

    /** A provider's assistant payload without media: a Messages `content` array, or a chat `message` with array content. */
    fun strip(raw: JsonElement): JsonElement = when {
        raw is JsonArray -> if (raw.any(::isMedia)) JsonArray(raw.filterNot(::isMedia)) else raw
        raw is JsonObject && raw["content"] is JsonArray -> {
            val c = raw["content"] as JsonArray
            if (c.any(::isMedia)) JsonObject(raw + ("content" to JsonArray(c.filterNot(::isMedia)))) else raw
        }
        else -> raw
    }

    fun any(turns: List<Turn>): Boolean = turns.any { it is Turn.User && it.images.isNotEmpty() }
}
