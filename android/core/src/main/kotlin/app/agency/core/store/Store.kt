package app.agency.core.store

import app.agency.core.agent.Card
import app.agency.core.base.J
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** One thing he did or that happened, on logical [day]. [kind] is one of [Kind]; [data] is that kind's payload. */
data class Entry(val id: Long, val day: LocalDate, val at: Instant, val kind: String, val data: JsonObject) {
    inline fun <reified T> read(): T = J.decodeFromJsonElement(data)
}

enum class Role {
    /** What he typed, said or tapped. */
    OWNER,

    /** 小葵's reply. */
    MANAGER,

    /** A small line written by code, without a model call (「✓ 已記：喝水一壺」, a reminder that was shown). */
    NOTE,
}

/** One line of the conversation as shown. [meta] carries small flags (e.g. `"failed": true` for a message that was not sent). */
data class Message(
    val id: Long,
    val at: Instant,
    val role: Role,
    val text: String,
    val cards: List<Card> = emptyList(),
    val meta: JsonObject = JsonObject(emptyMap()),
)

/**
 * Everything the app keeps. Synchronous and small on purpose: the Android implementation is one encrypted SQLite file with
 * three tables; [MemStore] is the same contract in memory for tests and the simulation. Call it off the main thread.
 */
interface Store {
    fun add(day: LocalDate, at: Instant, kind: String, data: JsonObject): Entry
    fun update(id: Long, data: JsonObject): Boolean
    fun remove(id: Long): Boolean
    fun entry(id: Long): Entry?

    /** Entries of one logical day, oldest first; [kind] null = all kinds. */
    fun entries(day: LocalDate, kind: String? = null): List<Entry> = entries(day, day, kind)

    /** Entries of the days [from]..[to] (both included), oldest first. */
    fun entries(from: LocalDate, to: LocalDate, kind: String? = null): List<Entry>

    /** A singleton JSON document ([Doc] keys), or null. */
    fun doc(key: String): String?
    fun putDoc(key: String, json: String?)

    fun addMessage(at: Instant, role: Role, text: String, cards: List<Card> = emptyList(), meta: JsonObject = JsonObject(emptyMap())): Message
    fun updateMessage(message: Message)

    /** The newest [limit] messages older than [beforeId] (null = newest), returned oldest first. */
    fun messages(limit: Int, beforeId: Long? = null): List<Message>

    /** Runs [block] atomically where the implementation can. */
    fun <T> tx(block: () -> T): T = block()
}

inline fun <reified T> Store.add(day: LocalDate, at: Instant, kind: String, value: T): Entry =
    add(day, at, kind, J.encodeToJsonElement(value).jsonObject)

inline fun <reified T> Store.update(id: Long, value: T): Boolean = update(id, J.encodeToJsonElement(value).jsonObject)

inline fun <reified T> Store.readDoc(key: String): T? = doc(key)?.let { runCatching { J.decodeFromString<T>(it) }.getOrNull() }

inline fun <reified T> Store.writeDoc(key: String, value: T) = putDoc(key, J.encodeToString(value))

/** Keys of the singleton documents. */
object Doc {
    /** [app.agency.core.rules.Rules]. */
    const val RULES = "rules"

    /** Soft knowledge taken from his pasted document (tastes, ordering tips, how she should talk): plain text. */
    const val HANDBOOK = "handbook"

    /** His pasted document, as pasted. Never sent whole in every request; a tool reads parts of it. */
    const val RULES_TEXT = "rules_text"

    /** [app.agency.core.stage.StageState]. */
    const val STAGE = "stage"

    /** Long-term memory: what 小葵 chose to remember (plain text lines). */
    const val MEMORY = "memory"

    /** Rolling summary of the conversation older than the recent window. */
    const val SUMMARY = "summary"

    /** Per-subject lesson levels and the trainee level. */
    const val LEVELS = "levels"

    /** App settings that are not rules (provider choice, folded panels, notification options). */
    const val SETTINGS = "settings"
}
