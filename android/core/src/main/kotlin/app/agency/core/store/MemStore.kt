package app.agency.core.store

import app.agency.core.agent.Card
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/** [Store] in memory: tests, the simulation, previews. Not thread-safe beyond a single lock. */
class MemStore : Store {
    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val docs = HashMap<String, String>()
    private val messages = ArrayList<Message>()
    private var nextEntry = 1L
    private var nextMessage = 1L

    override fun add(day: LocalDate, at: Instant, kind: String, data: JsonObject): Entry = synchronized(lock) {
        Entry(nextEntry++, day, at, kind, data).also { entries += it }
    }

    override fun update(id: Long, data: JsonObject): Boolean = synchronized(lock) {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) false else { entries[i] = entries[i].copy(data = data); true }
    }

    override fun remove(id: Long): Boolean = synchronized(lock) { entries.removeAll { it.id == id } }

    override fun entry(id: Long): Entry? = synchronized(lock) { entries.firstOrNull { it.id == id } }

    override fun entries(from: LocalDate, to: LocalDate, kind: String?): List<Entry> = synchronized(lock) {
        entries.filter { it.day >= from && it.day <= to && (kind == null || it.kind == kind) }.sortedWith(compareBy({ it.at }, { it.id }))
    }

    override fun doc(key: String): String? = synchronized(lock) { docs[key] }

    override fun putDoc(key: String, json: String?) = synchronized(lock) {
        if (json == null) docs.remove(key) else docs[key] = json
        Unit
    }

    override fun addMessage(at: Instant, role: Role, text: String, cards: List<Card>, meta: JsonObject): Message = synchronized(lock) {
        Message(nextMessage++, at, role, text, cards, meta).also { messages += it }
    }

    override fun updateMessage(message: Message) = synchronized(lock) {
        val i = messages.indexOfFirst { it.id == message.id }
        if (i >= 0) messages[i] = message
        Unit
    }

    override fun messages(limit: Int, beforeId: Long?): List<Message> = synchronized(lock) {
        messages.filter { beforeId == null || it.id < beforeId }.takeLast(limit)
    }

    override fun <T> tx(block: () -> T): T = synchronized(lock) { block() }
}
