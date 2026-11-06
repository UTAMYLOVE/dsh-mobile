package app.dshmobile.library

import app.dshmobile.chat.ChatItem
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A reusable setting card: characters, world, writing style (stage 2 §D). */
data class CharacterCard(val id: String, val name: String, val body: String)

/** One chapter: the reply's item key in the book's conversation and its text (kept so the book reads offline and exports). */
data class Chapter(val key: String, val text: String)

/** One book is one conversation (§D). [excluded]/[added]: replies marked 「不是章節」 or added by hand. */
data class Book(
    val id: String,
    val title: String,
    val sessionId: String,
    val cardId: String? = null,
    val model: String? = null,
    val createdAt: Long = 0,
    val excluded: Set<String> = emptySet(),
    val added: Set<String> = emptySet(),
    val chapters: List<Chapter> = emptyList(),
    /** Where reading stopped: the chapter key and the scroll position inside it. */
    val readKey: String? = null,
    val readScroll: Int = 0,
    /** Chapters read through (for the shelf's %). */
    val readCount: Int = 0,
)

/** A file on the shelf (0.5.0): a presented report, deliverable or drawing; [path] is workspace-relative. */
data class LibraryFile(val id: String, val path: String, val title: String, val kind: String, val sessionId: String?, val addedAt: Long)

data class LibraryData(
    val books: List<Book> = emptyList(),
    val cards: List<CharacterCard> = emptyList(),
    val fontSp: Int = 18,
    val night: Boolean = false,
    val files: List<LibraryFile> = emptyList(),
)

object Chapters {
    /** A reply this long or longer counts as prose (a chapter) unless excluded. */
    const val MIN_CHARS = 300

    /** The chapters of [book] from its conversation [items], oldest first; falls back to the stored ones when nothing is loaded. */
    fun of(book: Book, items: List<ChatItem>): List<Chapter> {
        val replies = items.filterIsInstance<ChatItem.Assistant>()
        if (replies.isEmpty()) return book.chapters
        val fresh = replies.filter { r ->
            r.key !in book.excluded && (r.key in book.added || r.text.trim().length >= MIN_CHARS)
        }.map { Chapter(it.key, it.text.trim()) }
        // Older chapters that the loaded window no longer holds stay (a long book is paged).
        val known = fresh.mapTo(HashSet()) { it.key }
        val older = book.chapters.filter { it.key !in known && it.key !in book.excluded }
        return older + fresh
    }

    /** The opening message of a new book: the card as setup, then the request for chapter one. */
    fun opening(title: String, card: CharacterCard?): String = buildString {
        append("我們來寫一本小說，書名《").append(title).append("》。")
        if (card != null) append("\n\n以下是設定卡「").append(card.name).append("」，請一直遵守：\n").append(card.body.trim())
        append("\n\n請直接寫第一章。每次回覆只寫一章正文，章名放第一行，不要加解說。")
    }

    const val NEXT = "請接著寫下一章。只寫正文，章名放第一行。"
}

/**
 * The bookshelf and cards, one JSON file in the workspace (`.dshm/library.json`), so it is backed up and exported with it.
 * Writes are atomic (temp file, then rename). Main-thread safe enough: the file is small and writes are rare (user actions).
 */
class LibraryStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<LibraryData> = _state.asStateFlow()

    private fun load(): LibraryData = try {
        if (file.isFile) LibraryJson.decode(Json.parseToJsonElement(file.readText())) else LibraryData()
    } catch (_: Exception) {
        LibraryData()
    }

    @Synchronized private fun update(f: (LibraryData) -> LibraryData) {
        val next = f(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(LibraryJson.encode(next).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    fun addBook(title: String, sessionId: String, cardId: String?, model: String?): Book {
        val existing = _state.value.books.firstOrNull { it.sessionId == sessionId }
        if (existing != null) return existing
        val b = Book(UUID.randomUUID().toString(), title.ifBlank { "未命名的書" }, sessionId, cardId, model, clock())
        update { it.copy(books = listOf(b) + it.books) }
        return b
    }

    fun removeBook(id: String) = update { d -> d.copy(books = d.books.filter { it.id != id }) }

    fun renameBook(id: String, title: String) = edit(id) { it.copy(title = title.ifBlank { it.title }) }

    fun syncChapters(id: String, chapters: List<Chapter>) = edit(id) { it.copy(chapters = chapters) }

    /** Marks a reply 「不是章節」, or takes it back in (also a short reply, by hand). */
    fun setChapter(id: String, key: String, isChapter: Boolean) = edit(id) {
        if (isChapter) it.copy(excluded = it.excluded - key, added = it.added + key)
        else it.copy(excluded = it.excluded + key, added = it.added - key, chapters = it.chapters.filter { c -> c.key != key })
    }

    fun savePosition(id: String, key: String, scroll: Int, readCount: Int) =
        edit(id) { it.copy(readKey = key, readScroll = scroll, readCount = maxOf(it.readCount, readCount)) }

    fun saveCard(card: CharacterCard) = update { d ->
        d.copy(cards = if (d.cards.any { it.id == card.id }) d.cards.map { if (it.id == card.id) card else it } else d.cards + card)
    }

    fun newCard(name: String, body: String): CharacterCard = CharacterCard(UUID.randomUUID().toString(), name.ifBlank { "新設定卡" }, body).also(::saveCard)

    fun removeCard(id: String) = update { d -> d.copy(cards = d.cards.filter { it.id != id }) }

    fun setReading(fontSp: Int? = null, night: Boolean? = null) =
        update { it.copy(fontSp = (fontSp ?: it.fontSp).coerceIn(12, 32), night = night ?: it.night) }

    /** Puts a file on the shelf, newest first; a path already there is left as it is. */
    fun addFile(path: String, title: String, kind: String, sessionId: String?) = update { d ->
        if (d.files.any { it.path == path }) d
        else d.copy(files = listOf(LibraryFile(UUID.randomUUID().toString(), path, title.ifBlank { path.substringAfterLast('/') }, kind, sessionId, clock())) + d.files)
    }

    fun removeFile(id: String) = update { d -> d.copy(files = d.files.filter { it.id != id }) }

    fun bookFor(sessionId: String): Book? = _state.value.books.firstOrNull { it.sessionId == sessionId }

    private fun edit(id: String, f: (Book) -> Book) = update { d -> d.copy(books = d.books.map { if (it.id == id) f(it) else it }) }

    companion object {
        const val RELATIVE_PATH = ".dshm/library.json"
    }
}

/** The library file's format, by hand (the project has no serialization compiler plugin). Unknown or broken parts are skipped. */
object LibraryJson {
    fun encode(d: LibraryData): JsonElement = buildJsonObject {
        put("version", 1)
        put("fontSp", d.fontSp)
        put("night", d.night)
        put("cards", buildJsonArray { d.cards.forEach { c -> add(buildJsonObject { put("id", c.id); put("name", c.name); put("body", c.body) }) } })
        put("books", buildJsonArray {
            d.books.forEach { b ->
                add(buildJsonObject {
                    put("id", b.id); put("title", b.title); put("sessionId", b.sessionId)
                    b.cardId?.let { put("cardId", it) }; b.model?.let { put("model", it) }
                    put("createdAt", b.createdAt)
                    put("excluded", JsonArray(b.excluded.map(::JsonPrimitive)))
                    put("added", JsonArray(b.added.map(::JsonPrimitive)))
                    put("chapters", buildJsonArray { b.chapters.forEach { c -> add(buildJsonObject { put("key", c.key); put("text", c.text) }) } })
                    b.readKey?.let { put("readKey", it) }
                    put("readScroll", b.readScroll); put("readCount", b.readCount)
                })
            }
        })
        put("files", buildJsonArray {
            d.files.forEach { f ->
                add(buildJsonObject {
                    put("id", f.id); put("path", f.path); put("title", f.title); put("kind", f.kind)
                    f.sessionId?.let { put("sessionId", it) }
                    put("addedAt", f.addedAt)
                })
            }
        })
    }

    fun decode(e: JsonElement): LibraryData {
        val o = e as? JsonObject ?: return LibraryData()
        return LibraryData(
            books = o.arr("books").mapNotNull { book(it as? JsonObject) },
            cards = o.arr("cards").mapNotNull { (it as? JsonObject)?.let { c -> CharacterCard(c.s("id") ?: return@mapNotNull null, c.s("name").orEmpty(), c.s("body").orEmpty()) } },
            fontSp = o.p("fontSp")?.intOrNull ?: 18,
            night = o.p("night")?.booleanOrNull ?: false,
            files = o.arr("files").mapNotNull { f ->
                (f as? JsonObject)?.let {
                    LibraryFile(
                        it.s("id") ?: return@mapNotNull null, it.s("path") ?: return@mapNotNull null, it.s("title").orEmpty(),
                        it.s("kind").orEmpty(), it.s("sessionId"), it.p("addedAt")?.longOrNull ?: 0,
                    )
                }
            },
        )
    }

    private fun book(o: JsonObject?): Book? {
        if (o == null) return null
        return Book(
            id = o.s("id") ?: return null,
            title = o.s("title").orEmpty(),
            sessionId = o.s("sessionId") ?: return null,
            cardId = o.s("cardId"),
            model = o.s("model"),
            createdAt = o.p("createdAt")?.longOrNull ?: 0,
            excluded = o.arr("excluded").mapNotNull { (it as? JsonPrimitive)?.content }.toSet(),
            added = o.arr("added").mapNotNull { (it as? JsonPrimitive)?.content }.toSet(),
            chapters = o.arr("chapters").mapNotNull { c -> (c as? JsonObject)?.let { Chapter(it.s("key") ?: return@mapNotNull null, it.s("text").orEmpty()) } },
            readKey = o.s("readKey"),
            readScroll = o.p("readScroll")?.intOrNull ?: 0,
            readCount = o.p("readCount")?.intOrNull ?: 0,
        )
    }

    private fun JsonObject.p(k: String): JsonPrimitive? = this[k] as? JsonPrimitive
    private fun JsonObject.s(k: String): String? = p(k)?.takeIf { it.isString }?.content
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
}
