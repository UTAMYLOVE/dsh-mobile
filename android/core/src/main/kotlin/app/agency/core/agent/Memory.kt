package app.agency.core.agent

import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.base.J
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Effort
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Turn
import app.agency.core.store.Doc
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Message
import app.agency.core.store.Note
import app.agency.core.store.Role
import app.agency.core.store.Store
import app.agency.core.store.add
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/**
 * 小葵's memory, in three layers:
 * - long-term lines she chose ([remember] / [forget], doc `memory`, at most [CAP] lines; the oldest goes when it is full);
 * - day notes ([noteToday], `Kind.NOTE` entries; the context shows the last three days);
 * - the rolling summary of the conversation older than the recent window (doc `summary`, written by [Compactor]).
 * The tools `remember`, `forget` and `note_today` are thin wrappers over these functions.
 */
object Memory {
    const val CAP = 60
    const val MAX_LINE = 200
    const val SUMMARY_MAX = 800

    /** Doc key of [Mark]: the newest message id folded into the summary. */
    const val SUMMARY_MARK = "summary_mark"

    @Serializable
    data class Mark(val throughId: Long)

    /** What [remember] did: [added] false when the line was blank or already there; [dropped] is the line that made room. */
    data class Remembered(val added: Boolean, val lines: List<String>, val dropped: String? = null)

    fun lines(store: Store): List<String> {
        val raw = store.doc(Doc.MEMORY) ?: return emptyList()
        return runCatching { J.decodeFromString<List<String>>(raw) }.getOrElse { raw.lines() }
            .map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun remember(store: Store, text: String): Remembered = store.tx {
        val line = clean(text)
        val now = lines(store)
        if (line.isEmpty() || now.any { it == line }) return@tx Remembered(false, now)
        val all = now + line
        val dropped = if (all.size > CAP) all.first() else null
        val kept = if (all.size > CAP) all.drop(all.size - CAP) else all
        store.putDoc(Doc.MEMORY, J.encodeToString(kept))
        Remembered(true, kept, dropped)
    }

    /** Removes every line containing [text]; returns the removed lines. A blank [text] removes nothing. */
    fun forget(store: Store, text: String): List<String> = store.tx {
        val needle = text.trim()
        if (needle.isEmpty()) return@tx emptyList()
        val (gone, kept) = lines(store).partition { it.contains(needle) }
        if (gone.isNotEmpty()) store.putDoc(Doc.MEMORY, J.encodeToString(kept))
        gone
    }

    fun noteToday(store: Store, clock: Clock, text: String): Entry? {
        val line = clean(text).ifEmpty { return null }
        val now = clock.now()
        return store.add(Days.of(now, clock.zone), now, Kind.NOTE, Note(line))
    }

    /** Her notes of today and the [days] − 1 days before, oldest first. */
    fun notes(store: Store, clock: Clock, days: Int = 3): List<Entry> {
        val today = Days.today(clock)
        return store.entries(today.minusDays((days - 1).toLong()), today, Kind.NOTE)
    }

    fun summary(store: Store): String =
        store.doc(Doc.SUMMARY)?.let { raw -> runCatching { J.decodeFromString<String>(raw) }.getOrElse { raw } }.orEmpty()

    fun summarisedThrough(store: Store): Long =
        store.doc(SUMMARY_MARK)?.let { runCatching { J.decodeFromString<Mark>(it).throughId }.getOrNull() } ?: 0L

    internal fun setSummary(store: Store, text: String, throughId: Long) = store.tx {
        store.putDoc(Doc.SUMMARY, J.encodeToString(takeCodePoints(text.trim(), SUMMARY_MAX)))
        store.putDoc(SUMMARY_MARK, J.encodeToString(Mark(throughId)))
    }

    private fun clean(s: String): String = takeCodePoints(s.replace(Regex("""\s+"""), " ").trim(), MAX_LINE)

    internal fun takeCodePoints(s: String, max: Int): String =
        if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))
}

/**
 * Keeps the request small without forgetting: when more than [threshold] messages are not in the summary yet, first a flush
 * call lets 小葵 write down what should last (only `remember` and `note_today` are offered), then a summary call folds
 * everything but the newest [keep] messages into doc `summary` (at most [Memory.SUMMARY_MAX] characters). A failed flush is
 * ignored; a failed summary leaves everything as it was, to try again after the next message.
 */
class Compactor(
    private val llm: LlmClient,
    private val small: LlmClient,
    private val store: Store,
    private val system: () -> String,
    private val tools: () -> List<Tool>,
    private val toolCtx: (photoIds: List<String>) -> ToolCtx,
    private val threshold: Int = 40,
    private val keep: Int = 20,
) {
    suspend fun maybeCompact(): Boolean {
        val mark = Memory.summarisedThrough(store)
        val open = store.messages(SCAN).filter { it.id > mark }
        if (open.size <= threshold) return false
        val fold = open.dropLast(keep)
        if (fold.isEmpty()) return false
        val transcript = transcript(fold)
        flush(transcript)
        return summarise(transcript, fold.last().id)
    }

    private suspend fun flush(transcript: String) {
        val offered = tools().filter { it.spec.name in FLUSH_TOOLS }
        if (offered.isEmpty()) return
        val turns = mutableListOf<Turn>(Turn.User(FLUSH_PROMPT + "\n\n" + transcript))
        val ctx = toolCtx(emptyList())
        repeat(FLUSH_ROUNDS) {
            val r = call(llm, ChatRequest(system(), turns, Purpose.CHAT, 2000, offered.map { it.spec }, thinking = Thinking.LOW,
                effort = Effort.LOW)) ?: return
            if (r.toolCalls.isEmpty()) return
            turns += r.assistant
            ToolRunner.run(r.toolCalls, offered, ctx).forEach { turns += it.result }
        }
    }

    private suspend fun summarise(transcript: String, throughId: Long): Boolean {
        val old = Memory.summary(store)
        val input = (if (old.isBlank()) "" else "舊的摘要：\n$old\n\n") + "要併進摘要的對話：\n$transcript"
        val r = call(small, ChatRequest(SUMMARY_SYSTEM, listOf(Turn.User(input)), Purpose.SUMMARY, 1500)) ?: return false
        val text = r.text?.trim().orEmpty().ifEmpty { return false }
        Memory.setSummary(store, text, throughId)
        return true
    }

    private suspend fun call(client: LlmClient, request: ChatRequest): ChatResult.Ok? = try {
        client.chat(request) as? ChatResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val SCAN = 400
        private const val FLUSH_ROUNDS = 3
        private const val LINE_MAX = 300
        val FLUSH_TOOLS = setOf("remember", "note_today")

        private const val FLUSH_PROMPT = "〔整理記憶〕下面這段較早的對話很快會被收成摘要，細節會看不到。" +
            "有值得長期記住的事（他的喜好、習慣、說過的計畫、在意的事、對你的要求），用 remember 一條一條記下；" +
            "今天值得留意的事用 note_today 記下。已經記過的不用重記。沒有要記的就直接回「沒有」。"

        private const val SUMMARY_SYSTEM = "你替經紀人小葵整理和練習生的對話摘要。用繁體中文寫成一段連貫的摘要，最多八百字：" +
            "保留發生過的事、他的狀況和情緒、說好的約定、還沒處理完的事、他的偏好；把舊摘要和新對話合在一起，" +
            "舊的、已經不重要的細節可以縮短。只寫摘要本身，不要標題、不要評論。數字只照對話裡出現過的寫。"

        internal fun transcript(messages: List<Message>): String = messages.joinToString("\n") { m ->
            val who = when (m.role) {
                Role.OWNER -> "他"
                Role.MANAGER -> "小葵"
                Role.NOTE -> "〔紀錄〕"
            }
            "$who：" + Memory.takeCodePoints(m.text.replace('\n', ' '), LINE_MAX)
        }
    }
}
