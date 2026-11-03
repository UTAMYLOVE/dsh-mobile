package app.dshmobile.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * dsh 0.2.1-rc.5's `/api/remote.mux` frames (docs/dsh-ui-protocol.md §1.3). dsh checks the client frames' key sets
 * exactly, so these builders never add a key: `open{type,streamId,endpoint,payload{args}}`, `item{type,streamId,value?}`,
 * `end{type,streamId}`, `cancel{type,streamId}`.
 */
object MuxFrames {
    fun open(streamId: String, endpoint: String, args: JsonObject): String = buildJsonObject {
        put("type", "open")
        put("streamId", streamId)
        put("endpoint", endpoint)
        put("payload", buildJsonObject { put("args", args) })
    }.toString()

    /** A null [value] leaves the `value` key out (dsh reads that as `undefined`); [kotlinx.serialization.json.JsonNull] sends `null`. */
    fun item(streamId: String, value: JsonElement?): String = buildJsonObject {
        put("type", "item")
        put("streamId", streamId)
        if (value != null) put("value", value)
    }.toString()

    fun end(streamId: String): String = bare("end", streamId)

    fun cancel(streamId: String): String = bare("cancel", streamId)

    /**
     * A server frame, or null for anything unknown or broken (not an object, no non-empty `streamId`, an unknown type).
     * An error's code goes through [DshErrors.safeCode]; its message is dsh's free text and must not be shown or logged as is.
     */
    fun parse(text: String): ServerFrame? {
        val frame = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) { // SerializationException
            null
        } ?: return null
        val streamId = frame.string("streamId")?.takeIf { it.isNotEmpty() } ?: return null
        return when (frame.string("type")) {
            "item" -> ServerFrame.Item(streamId, frame["value"])
            "error" -> {
                val error = frame.obj("error")
                ServerFrame.Error(streamId, DshErrors.safeCode(error?.string("code")), error?.string("message").orEmpty())
            }
            "end" -> ServerFrame.End(streamId)
            else -> null
        }
    }

    private fun bare(type: String, streamId: String): String = buildJsonObject {
        put("type", type)
        put("streamId", streamId)
    }.toString()
}

sealed interface ServerFrame {
    val streamId: String

    /** [value] is null when the frame had no `value` key. */
    data class Item(override val streamId: String, val value: JsonElement?) : ServerFrame
    data class Error(override val streamId: String, val code: String, val message: String) : ServerFrame
    data class End(override val streamId: String) : ServerFrame
}
