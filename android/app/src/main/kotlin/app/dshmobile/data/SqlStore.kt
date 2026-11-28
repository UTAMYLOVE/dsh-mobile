package app.dshmobile.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.agency.core.agent.Card
import app.agency.core.base.J
import app.agency.core.store.Entry
import app.agency.core.store.Message
import app.agency.core.store.Role
import app.agency.core.store.Store
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * [Store] over an open [SupportSQLiteDatabase] with the [Schema] tables: `entry`, `doc`, `message`. The same class runs on
 * SQLCipher in the app and on plain SQLite in the tests (the shared `StoreContract`). Synchronous: call it off the main thread.
 * [tx] is a real transaction (nested calls join the outer one on the same thread).
 *
 * A row whose JSON cannot be read (a damaged payload) comes back with an empty payload instead of failing the whole list: one
 * bad row must not keep him out of his day.
 */
class SqlStore(private val db: SupportSQLiteDatabase) : Store {
    override fun add(day: LocalDate, at: Instant, kind: String, data: JsonObject): Entry {
        val values = ContentValues().apply {
            put("day", day.toString())
            put("at", at.toEpochMilli())
            put("kind", kind)
            put("data", data.toString())
        }
        val id = db.insert(Schema.ENTRY, SQLiteDatabase.CONFLICT_ABORT, values)
        check(id >= 0) { "entry not stored" }
        return Entry(id, day, Instant.ofEpochMilli(at.toEpochMilli()), kind, data)
    }

    override fun update(id: Long, data: JsonObject): Boolean {
        val values = ContentValues().apply { put("data", data.toString()) }
        return db.update(Schema.ENTRY, SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf<Any>(id)) > 0
    }

    override fun remove(id: Long): Boolean = db.delete(Schema.ENTRY, "id = ?", arrayOf<Any>(id)) > 0

    override fun entry(id: Long): Entry? =
        db.query("SELECT $ENTRY_COLUMNS FROM entry WHERE id = ?", arrayOf<Any>(id)).use { c -> if (c.moveToFirst()) entryOf(c) else null }

    override fun entries(from: LocalDate, to: LocalDate, kind: String?): List<Entry> {
        val sql = StringBuilder("SELECT $ENTRY_COLUMNS FROM entry WHERE day >= ? AND day <= ?")
        val args = mutableListOf<Any>(from.toString(), to.toString())
        if (kind != null) {
            sql.append(" AND kind = ?")
            args += kind
        }
        sql.append(" ORDER BY at, id")
        return db.query(sql.toString(), args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(entryOf(c)) } }
    }

    override fun doc(key: String): String? =
        db.query("SELECT json FROM doc WHERE key = ?", arrayOf<Any>(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun putDoc(key: String, json: String?) {
        if (json == null) {
            db.delete(Schema.DOC, "key = ?", arrayOf<Any>(key))
            return
        }
        val values = ContentValues().apply {
            put("key", key)
            put("json", json)
        }
        db.insert(Schema.DOC, SQLiteDatabase.CONFLICT_REPLACE, values)
    }

    override fun addMessage(at: Instant, role: Role, text: String, cards: List<Card>, meta: JsonObject): Message {
        val values = ContentValues().apply {
            put("at", at.toEpochMilli())
            put("role", role.name)
            put("text", text)
            put("cards", encodeCards(cards))
            put("meta", meta.toString())
        }
        val id = db.insert(Schema.MESSAGE, SQLiteDatabase.CONFLICT_ABORT, values)
        check(id >= 0) { "message not stored" }
        return Message(id, Instant.ofEpochMilli(at.toEpochMilli()), role, text, cards, meta)
    }

    override fun updateMessage(message: Message) {
        val values = ContentValues().apply {
            put("at", message.at.toEpochMilli())
            put("role", message.role.name)
            put("text", message.text)
            put("cards", encodeCards(message.cards))
            put("meta", message.meta.toString())
        }
        db.update(Schema.MESSAGE, SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf<Any>(message.id))
    }

    override fun messages(limit: Int, beforeId: Long?): List<Message> {
        if (limit <= 0) return emptyList()
        val sql = "SELECT id, at, role, text, cards, meta FROM (SELECT * FROM message " +
            (if (beforeId != null) "WHERE id < ? " else "") + "ORDER BY id DESC LIMIT ?) ORDER BY id"
        val args: Array<Any> = if (beforeId != null) arrayOf(beforeId, limit) else arrayOf(limit)
        return db.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(messageOf(c)) } }
    }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun entryOf(c: Cursor) = Entry(
        id = c.getLong(0),
        day = LocalDate.parse(c.getString(1)),
        at = Instant.ofEpochMilli(c.getLong(2)),
        kind = c.getString(3),
        data = parseObject(c.getString(4)),
    )

    private fun messageOf(c: Cursor) = Message(
        id = c.getLong(0),
        at = Instant.ofEpochMilli(c.getLong(1)),
        role = Role.entries.firstOrNull { it.name == c.getString(2) } ?: Role.NOTE,
        text = c.getString(3),
        cards = runCatching { J.decodeFromString(CARDS, c.getString(4)) }.getOrDefault(emptyList()),
        meta = parseObject(c.getString(5)),
    )

    private fun encodeCards(cards: List<Card>): String = J.encodeToString(CARDS, cards)

    private fun parseObject(text: String): JsonObject =
        runCatching { J.parseToJsonElement(text) as JsonObject }.getOrDefault(JsonObject(emptyMap()))

    private companion object {
        const val ENTRY_COLUMNS = "id, day, at, kind, data"
        val CARDS = ListSerializer(Card.serializer())
    }
}
