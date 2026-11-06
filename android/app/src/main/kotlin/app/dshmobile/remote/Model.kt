package app.dshmobile.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One row of `session/list` (dsh `SessionSummary`). Everything but [id] may be missing; callers pick their own defaults
 * (`updatedAt ?: 0`, `running ?: false`).
 */
data class SessionSummary(
    val id: String,
    val parentId: String?,
    val origin: String?,
    val updatedAt: Long?,
    val running: Boolean?,
    val blank: Boolean?,
    val title: String?,
    /** The last turn's response, or its prompt while that response is still empty. */
    val lastLine: String?,
    /** `modelSelection.lastUsed.model`. */
    val model: String?,
    /** `modelSelection.next.model`. */
    val nextModel: String?,
    /** A subagent's label (`subagent.label`). */
    val label: String?,
    /** The folder dsh runs this conversation in (`cwd`): proot's /root/workspace or the native mode's host path. */
    val cwd: String? = null,
) {
    companion object {
        /** Null when the row has no `sessionId`. */
        fun from(json: JsonObject): SessionSummary? {
            val id = json.string("sessionId")?.takeIf { it.isNotEmpty() } ?: return null
            val values = json.obj("projections")?.obj("values")
            val lastTurn = (values?.get("turnOutline") as? JsonArray)?.lastOrNull() as? JsonObject
            val selection = values?.obj("modelSelection")
            return SessionSummary(
                id = id,
                parentId = json.string("parentSessionId"),
                origin = json.string("origin"),
                updatedAt = json.long("updatedAt"),
                running = json.boolean("running"),
                blank = json.boolean("blank"),
                title = values?.string("title"),
                lastLine = lastTurn?.string("response")?.takeIf { it.isNotEmpty() } ?: lastTurn?.string("prompt"),
                model = selection?.obj("lastUsed")?.string("model"),
                nextModel = selection?.obj("next")?.string("model"),
                label = values?.obj("subagent")?.string("label"),
                cwd = json.string("cwd") ?: values?.string("workingDirectory"),
            )
        }
    }
}

/** One `session/search` hit. */
data class SearchHit(val sessionId: String, val snippet: String)

/** One part of a `session/prompt` content list. */
sealed interface PromptPart {
    data class Text(val text: String) : PromptPart
    data class Image(val mediaType: String, val base64: String, val name: String?) : PromptPart
    /** A file staged with `fileUploads/upload`; [name] is for the UI, dsh only takes the receipt. */
    data class File(val receiptId: String, val name: String) : PromptPart
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

/** One dsh reminder (`schedule/catalog`): a task bound to the conversation [sessionId]. [scheduledAt] is its next run (epoch ms). */
data class Reminder(
    val taskId: String,
    val sessionId: String,
    val title: String,
    val prompt: String,
    /** `daily`, `weekly`, `every` or `once`, as dsh names it. */
    val kind: String,
    val time: String?,
    val timeZone: String?,
    val scheduledAt: Long?,
    val active: Boolean,
) {
    companion object {
        fun from(o: JsonObject): Reminder? {
            val id = o.string("id") ?: return null
            val session = o.string("sessionId") ?: return null
            val at = o.string("scheduledAt")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            return Reminder(
                id, session, o.string("title").orEmpty(), o.string("prompt").orEmpty(), o.string("kind").orEmpty(),
                o.string("time"), o.string("timeZone"), at, (o.string("status") ?: "active") == "active",
            )
        }
    }
}
