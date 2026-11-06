package app.dshmobile.chat

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the app remembers about conversations that dsh does not: pins, locks, temporary chats, project membership. */
data class ConvPrefs(
    val pinned: List<String> = emptyList(),
    val locked: Set<String> = emptySet(),
    val temporary: Set<String> = emptySet(),
    /** conversation id → project slug. */
    val projectOf: Map<String, String> = emptyMap(),
)

/**
 * The app's conversation preferences, one small JSON file in `filesDir` (never in the workspace: dsh must not read which
 * conversations are locked). Writes are atomic; a broken file reads as empty.
 */
class ConversationPrefs(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ConvPrefs> = _state.asStateFlow()

    fun isPinned(id: String) = id in _state.value.pinned
    fun isLocked(id: String) = id in _state.value.locked
    fun isTemporary(id: String) = id in _state.value.temporary

    /** Newest pin first. */
    fun setPinned(id: String, on: Boolean) = update { p -> p.copy(pinned = if (on) listOf(id) + (p.pinned - id) else p.pinned - id) }
    fun setLocked(id: String, on: Boolean) = update { p -> p.copy(locked = if (on) p.locked + id else p.locked - id) }
    fun setTemporary(id: String, on: Boolean) = update { p -> p.copy(temporary = if (on) p.temporary + id else p.temporary - id) }
    fun setProject(id: String, slug: String?) = update { p -> p.copy(projectOf = if (slug == null) p.projectOf - id else p.projectOf + (id to slug)) }

    /** Forgets everything about [id] (it was deleted). */
    fun forget(id: String) = update { p ->
        p.copy(pinned = p.pinned - id, locked = p.locked - id, temporary = p.temporary - id, projectOf = p.projectOf - id)
    }

    @Synchronized private fun update(f: (ConvPrefs) -> ConvPrefs) {
        val next = f(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(next).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun load(): ConvPrefs = try {
        if (file.isFile) decode(Json.parseToJsonElement(file.readText()) as JsonObject) else ConvPrefs()
    } catch (_: Exception) {
        ConvPrefs()
    }

    companion object {
        const val FILE_NAME = "conversation-prefs.json"

        fun encode(p: ConvPrefs) = buildJsonObject {
            put("version", 1)
            put("pinned", JsonArray(p.pinned.map(::JsonPrimitive)))
            put("locked", JsonArray(p.locked.map(::JsonPrimitive)))
            put("temporary", JsonArray(p.temporary.map(::JsonPrimitive)))
            put("projectOf", buildJsonObject { p.projectOf.forEach { (k, v) -> put(k, v) } })
        }

        fun decode(o: JsonObject): ConvPrefs {
            fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            return ConvPrefs(
                pinned = list("pinned").distinct(),
                locked = list("locked").toSet(),
                temporary = list("temporary").toSet(),
                projectOf = (o["projectOf"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
                }.toMap(),
            )
        }

        /** The instruction a temporary chat's first message carries (dsh has no memory switch per conversation). */
        const val TEMPORARY_LEAD = "（這是臨時對話：不要讀取或寫入長期記憶，不要建立排程。）\n"

        /** [rows] with the pinned ones first (in pin order), the rest as they were. */
        fun pinnedFirst(rows: List<SessionRow>, pinned: List<String>): Pair<List<SessionRow>, List<SessionRow>> {
            val byId = rows.associateBy { it.id }
            val top = pinned.mapNotNull { byId[it] }
            val set = top.mapTo(HashSet()) { it.id }
            return top to rows.filter { it.id !in set }
        }
    }
}
