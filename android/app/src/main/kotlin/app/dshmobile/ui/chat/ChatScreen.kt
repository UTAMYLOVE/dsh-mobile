package app.dshmobile.ui.chat

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import app.dshmobile.ui.fog.LocalBackdrop
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.LiveReply
import app.dshmobile.chat.PendingSend
import app.dshmobile.config.AgentModels
import app.dshmobile.config.displayName
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogDimens
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.markdown.MarkdownParser
import app.dshmobile.ui.markdown.MdBlock
import app.dshmobile.ui.markdown.conflatedParse
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One model in the chat's model menu. */
data class ModelChoice(val id: String, val label: String, val selected: Boolean)

/**
 * What the chat page shows: the conversation ([items], the streaming [live] reply, [running]), the user's messages dsh has not
 * recorded yet ([pending]), the model menu and the error lines. [sendError], [followError] and [olderError] are codes
 * ([app.dshmobile.remote.DshErrors.safeCode], `ended`, or an exception class name); the screen words them. [modelLabel] is the
 * chip's text when the current model is none of [models] (e.g. a Claude model after its key was removed).
 */
data class ChatUiState(
    val title: String,
    val items: List<ChatItem>,
    val live: LiveReply?,
    val running: Boolean,
    val pending: List<PendingSend>,
    val models: List<ModelChoice>,
    val hasMore: Boolean,
    val sendError: String?,
    val followError: String? = null,
    val olderError: String? = null,
    val modelLabel: String? = null,
    /** Thinking strength picked for this conversation (dsh reasoningEffort), null = the model's default. */
    val effort: String? = null,
    /** Independent reviewer rounds per reply. */
    val reviewRounds: Int = 0,
)

/** The thinking strengths dsh's model selection accepts (stage 2 §B.1). */
val EFFORTS = listOf("off", "low", "high", "max")

/** The models this conversation can switch to: the app's own list (DeepSeek always, Claude only with a key), [current] selected. */
fun modelChoices(hasClaudeKey: Boolean, current: String?): List<ModelChoice> =
    AgentModels.available(hasClaudeKey).map { ModelChoice(it.id, AgentModels.displayName(it.id) ?: it.id, it.id == current) }

// ---- rows: what the lazy list holds ------------------------------------------------------------------------------------

/** Up to this many characters, a message is parsed in the composition (the first frame already shows it). */
internal const val EAGER_PARSE_CHARS = 2_000
/**
 * Only the newest this many messages, within [EAGER_BUDGET_CHARS] characters together, are parsed in the composition; older
 * ones are parsed off the main thread (a placeholder until then). Opening a 500-message conversation must not parse them all on
 * the main thread (final review B2-I3).
 */
internal const val EAGER_ROWS = 20
private const val EAGER_BUDGET_CHARS = 20_000
/** A message this short, in this few pieces, is one bubble in one lazy item (exactly the mockup's glass). */
private const val SINGLE_MAX_CHARS = 3_000
private const val SINGLE_MAX_PIECES = 12
/** A paragraph is cut into pieces of about this many characters / lines, at line ends where it can be. */
private const val PIECE_CHARS = 400
private const val PIECE_MAX_CHARS = 600
private const val PIECE_MAX_LINES = 16
private const val CODE_PIECE_LINES = 30
private const val LIST_PIECE_ITEMS = 10
private const val LIST_SPLIT_OVER = 12
private const val REASON_PIECE_CHARS = 600

/** Counters the tests read (cheap, allocation free). */
internal object ChatDebug {
    /** Messages parsed off the main thread. */
    val backgroundParses = java.util.concurrent.atomic.AtomicInteger()
}

/** One block for a lazy item of its own; [gap] is the space above it inside its bubble (0: it carries on the block before). */
internal data class Piece(val block: MdBlock, val gap: Int)

/** [blocks] cut into pieces small enough for one lazy item each: long paragraphs, code blocks and lists are split. */
internal fun pieces(blocks: List<MdBlock>): List<Piece> {
    val out = ArrayList<Piece>(blocks.size)
    for (b in blocks) {
        when {
            b is MdBlock.Paragraph && (b.text.length > PIECE_MAX_CHARS || lineCount(b.text.text) > PIECE_MAX_LINES) ->
                splitText(b.text.text).forEachIndexed { i, (start, end) -> out += Piece(MdBlock.Paragraph(b.text.subSequence(start, end)), if (i == 0) 8 else 0) }
            b is MdBlock.Code && lineCount(b.code) > CODE_PIECE_LINES ->
                b.code.split('\n').chunked(CODE_PIECE_LINES).forEachIndexed { i, lines -> out += Piece(MdBlock.Code(b.language, lines.joinToString("\n")), if (i == 0) 8 else 2) }
            b is MdBlock.ListBlock && b.items.size > LIST_SPLIT_OVER ->
                b.items.chunked(LIST_PIECE_ITEMS).forEachIndexed { i, items ->
                    out += Piece(MdBlock.ListBlock(b.ordered, b.start + i * LIST_PIECE_ITEMS, items), if (i == 0) 8 else 4)
                }
            else -> out += Piece(b, 8)
        }
    }
    return out
}

private fun lineCount(s: String): Int {
    var n = 1
    for (c in s) if (c == '\n') n++
    return n
}

/**
 * Ranges of [t] for the pieces of one paragraph: whole lines, about [PIECE_CHARS] and at most [PIECE_MAX_LINES] at a time (the
 * newline between two pieces is dropped: the next piece starts on its own line anyway). A line too long for one piece is cut
 * after [PIECE_CHARS] characters, never between the halves of a surrogate pair.
 */
private fun splitText(t: String): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    val n = t.length
    var start = 0
    while (start < n) {
        var end = start
        var lines = 0
        var hard = false
        while (true) {
            val from = if (lines == 0) start else end + 1
            val nl = t.indexOf('\n', from).let { if (it < 0) n else it }
            if (nl - start > PIECE_MAX_CHARS) {
                if (lines == 0) {
                    end = start + PIECE_CHARS
                    if (Character.isHighSurrogate(t[end - 1])) end--
                    hard = true
                }
                break
            }
            end = nl
            lines++
            if (nl >= n || lines >= PIECE_MAX_LINES || end - start >= PIECE_CHARS) break
        }
        out += start to end
        start = if (hard) end else end + 1
    }
    return out
}

private fun reasonPieces(t: String): List<String> {
    if (t.length <= REASON_PIECE_CHARS) return listOf(t)
    val out = ArrayList<String>()
    var start = 0
    while (start < t.length) {
        var end = minOf(t.length, start + REASON_PIECE_CHARS)
        if (end < t.length) {
            val nl = t.lastIndexOf('\n', end)
            if (nl > start + REASON_PIECE_CHARS / 3) end = nl else if (Character.isHighSurrogate(t[end - 1])) end--
        }
        out += t.substring(start, end).trim('\n')
        start = if (end < t.length && t[end] == '\n') end + 1 else end
    }
    return out
}

/** An entry of the lazy message list. [gap] (dp) is the space above it; [type] is its lazy content type. */
@Immutable
internal sealed interface ChatRow {
    val key: String
    val type: String
    val gap: Int

    data class Older(val error: String?) : ChatRow { override val key = "older"; override val type = "older"; override val gap = 12 }
    data class Whole(override val key: String, val kind: BubbleKind, val blocks: List<MdBlock>, val cites: List<Int>, override val gap: Int) : ChatRow {
        override val type get() = "whole-" + kind.name
    }
    /** [shade] places this piece on its bubble's 135° gradient (user bubbles only). */
    data class Segment(
        override val key: String, val kind: BubbleKind, val block: MdBlock, val pos: SegPos, val inner: Int, val cites: List<Int>, override val gap: Int,
        val shade: Shade = Shade.NONE,
    ) : ChatRow { override val type get() = "segment-" + kind.name }
    data class Raw(override val key: String, val text: String) : ChatRow { override val type = "raw"; override val gap = 12 }
    data class Placeholder(override val key: String, val kind: BubbleKind) : ChatRow { override val type = "placeholder"; override val gap = 12 }
    data class Typing(override val key: String, val tools: List<String>, override val gap: Int) : ChatRow { override val type = "typing" }
    data class Thinking(override val key: String, val open: Boolean) : ChatRow { override val type = "thinking"; override val gap = 12 }
    data class Reasoning(override val key: String, val text: String, val first: Boolean, val last: Boolean) : ChatRow {
        override val type = "reasoning"
        override val gap get() = if (first) 4 else 0
    }
    data class Tool(val item: ChatItem.Tool) : ChatRow { override val key = "tool:" + item.key; override val type = "tool"; override val gap = 12 }
    data class Search(val item: ChatItem.Tool) : ChatRow { override val key = "search:" + item.key; override val type = "search"; override val gap = 12 }
    data class Notice(val item: ChatItem.Notice) : ChatRow { override val key = "notice:" + item.key; override val type = "notice"; override val gap = 12 }
    data class Pending(val p: PendingSend) : ChatRow { override val key = "pending:" + p.requestId; override val type = "pending"; override val gap = 12 }
}

/**
 * Parsed Markdown per message key, so a message is parsed once (long ones off the main thread) and a long reply that has just
 * finished streaming reuses its last live parse. Main thread only; [revision] changes when a background parse lands.
 */
@Stable
internal class BlockCache {
    class Entry(val text: String, val blocks: List<MdBlock>) {
        val pieces: List<Piece> by lazy(LazyThreadSafetyMode.NONE) { pieces(blocks) }
    }

    private val entries = HashMap<String, Entry>()
    private var live: Entry? = null
    var revision by mutableIntStateOf(0)
        private set

    /**
     * [text]'s parse under [key]: cached, the live reply's when it is the same text, or parsed now when short and [parseNow];
     * else null.
     */
    fun entry(key: String, text: String, parseNow: Boolean = true): Entry? {
        entries[key]?.let { e -> if (e.text == text) return e }
        live?.let { l -> if (l.text.length == text.length && l.text == text) return l.also { entries[key] = it } }
        if (parseNow && text.length <= EAGER_PARSE_CHARS) return Entry(text, MarkdownParser.parse(text)).also { entries[key] = it }
        return null
    }

    /** The live reply's last parse when [text] carries it on: shown until [text]'s own parse lands, so a finished reply never blinks. */
    fun provisional(text: String): Entry? = live?.takeIf { it.text.isNotEmpty() && text.startsWith(it.text) }

    /** Stores a background parse without redrawing; [published] redraws once for a whole batch. */
    fun store(key: String, entry: Entry) {
        entries[key] = entry
    }

    fun has(key: String, text: String): Boolean = entries[key]?.text == text

    fun published() {
        revision++
    }

    fun live(entry: Entry) {
        live = entry
    }

    fun retain(keys: Set<String>) {
        entries.keys.retainAll(keys)
    }
}

/**
 * Where each piece of a long user bubble sits on the bubble's 135° gradient: the vertical share of the gradient runs from [from]
 * to [to] (estimated from the pieces' text lengths), so neighbouring pieces meet on the same colour; [across] is the horizontal
 * share (small for a tall bubble, as CSS `135deg` over a box much taller than wide).
 */
@Immutable
internal data class Shade(val from: Float, val to: Float, val across: Float) {
    companion object {
        val NONE = Shade(0f, 1f, .5f)
    }
}

private fun weight(b: MdBlock): Int = when (b) {
    is MdBlock.Paragraph -> b.text.length
    is MdBlock.Heading -> b.text.length
    is MdBlock.Code -> b.code.length
    is MdBlock.ListBlock -> b.items.size * 40
    is MdBlock.Table -> (b.rows.size + 1) * 40
    is MdBlock.Quote -> 100
    MdBlock.Rule -> 10
}.coerceAtLeast(1)

internal fun shades(ps: List<Piece>): List<Shade> {
    val weights = ps.map { weight(it.block) }
    val total = weights.sum().toFloat()
    // About 20 characters a line, 25.5 dp a line, 340 dp wide: height / width.
    val tall = total / 20f * 25.5f / 340f
    val across = 1f / (1f + tall)
    var at = 0
    return weights.map { w -> Shade(at / total, (at + w) / total, across).also { at += w } }
}

/**
 * Parses the long messages [missing] off the main thread: the whole batch, then one redraw. Each parse is kept as soon as it is
 * done, so a restart (the conversation changed meanwhile) skips what is already parsed instead of parsing it again.
 */
internal suspend fun parseMissing(
    missing: List<Pair<String, String>>,
    cache: BlockCache,
    parse: (String) -> List<MdBlock> = MarkdownParser::parse,
) {
    if (missing.isEmpty()) return
    for ((key, text) in missing) {
        if (cache.has(key, text)) continue
        val e = withContext(Dispatchers.Default) {
            ChatDebug.backgroundParses.incrementAndGet()
            BlockCache.Entry(text, parse(text)).also { it.pieces }
        }
        cache.store(key, e)
    }
    cache.published()
}

private class Built(val rows: List<ChatRow>, val missing: List<Pair<String, String>>)

private class RowBuilder(private val cache: BlockCache, private val expanded: Set<String>, private val eager: Set<String>? = null) {
    val rows = ArrayList<ChatRow>()
    val missing = ArrayList<Pair<String, String>>()
    val keys = HashSet<String>()

    fun add(r: ChatRow) {
        if (keys.add(r.key)) rows += r
    }

    fun thinking(owner: String, text: String) {
        val key = "think:$owner"
        val open = key in expanded
        add(ChatRow.Thinking(key, open))
        if (open) {
            val parts = reasonPieces(text)
            parts.forEachIndexed { i, p -> add(ChatRow.Reasoning("$key#$i", p, i == 0, i == parts.lastIndex)) }
        }
    }

    /** A durable message: parsed now, from the cache, or (long) queued for a background parse. */
    fun message(kind: BubbleKind, key: String, text: String, cites: List<Int>, gap: Int) {
        val e = cache.entry(key, text, parseNow = eager == null || key in eager)
        if (e != null) return emit(kind, key, e, cites, gap)
        missing += key to text
        val shown = cache.provisional(text)
        if (shown != null) emit(kind, key, shown, cites, gap) else add(ChatRow.Placeholder(key, kind))
    }

    fun emit(kind: BubbleKind, key: String, e: BlockCache.Entry, cites: List<Int>, gap: Int) {
        val ps = e.pieces
        if (ps.size <= 1 || (e.text.length <= SINGLE_MAX_CHARS && ps.size <= SINGLE_MAX_PIECES)) {
            add(ChatRow.Whole(key, kind, e.blocks, cites, gap))
            return
        }
        val shades = if (kind.user) shades(ps) else null
        ps.forEachIndexed { i, p ->
            val pos = when (i) { 0 -> SegPos.FIRST; ps.lastIndex -> SegPos.LAST; else -> SegPos.MIDDLE }
            add(
                ChatRow.Segment(
                    "$key#$i", kind, p.block, pos, p.gap, if (i == ps.lastIndex) cites else emptyList(), if (i == 0) gap else 0,
                    shades?.get(i) ?: Shade.NONE,
                ),
            )
        }
    }
}

/** The durable part of the list, oldest first: what only changes when dsh records something. */
private fun durableRows(
    items: List<ChatItem>, hasMore: Boolean, olderError: String?, cache: BlockCache, expanded: Set<String>, attachments: String,
): Built {
    val b = RowBuilder(cache, expanded, eagerKeys(items))
    if (hasMore) b.add(ChatRow.Older(olderError))
    var lastSearch: ChatItem.Tool? = null
    val used = HashSet<String>()
    for (item in items) {
        when (item) {
            is ChatItem.User -> {
                val key = "u:" + item.key
                val text = if (item.attachments.isEmpty()) item.text
                else listOf(item.text, item.attachments.joinToString("、", prefix = attachments)).filter { it.isNotBlank() }.joinToString("\n\n")
                used += key
                if (text.isNotBlank()) b.message(BubbleKind.USER, key, text, emptyList(), 12)
            }
            is ChatItem.Assistant -> {
                val key = "a:" + item.key
                used += key
                val reasoning = item.reasoning?.takeIf { it.isNotBlank() }
                if (reasoning != null) b.thinking(key, reasoning)
                if (item.text.isNotBlank()) {
                    val cites = lastSearch?.let { citedSources(item.text, it.sources) }.orEmpty()
                    b.message(BubbleKind.AI, key, item.text, cites, if (reasoning != null) 4 else 12)
                }
            }
            is ChatItem.Tool -> if (item.name == WEB_SEARCH) {
                b.add(ChatRow.Search(item))
                lastSearch = item
            } else b.add(ChatRow.Tool(item))
            is ChatItem.Notice -> b.add(ChatRow.Notice(item))
        }
    }
    cache.retain(used)
    return Built(b.rows, b.missing)
}

/** The row keys of the newest messages that may be parsed in the composition ([EAGER_ROWS], [EAGER_BUDGET_CHARS]). */
private fun eagerKeys(items: List<ChatItem>): Set<String> {
    val keys = HashSet<String>()
    var chars = 0
    for (item in items.asReversed()) {
        val (key, length) = when (item) {
            is ChatItem.User -> ("u:" + item.key) to item.text.length
            is ChatItem.Assistant -> ("a:" + item.key) to item.text.length
            else -> continue
        }
        if (keys.size >= EAGER_ROWS || chars + length > EAGER_BUDGET_CHARS) break
        keys += key
        chars += length
    }
    return keys
}

/** The streaming reply and the messages waiting for dsh, below the durable part. */
private fun tailRows(live: LiveReply?, liveEntry: BlockCache.Entry?, pending: List<PendingSend>, cache: BlockCache, expanded: Set<String>): List<ChatRow> {
    val b = RowBuilder(cache, expanded)
    if (live != null) {
        val key = "live:" + live.attemptId
        val thinking = live.reasoning.isNotBlank()
        if (thinking) b.thinking(key, live.reasoning)
        val gap = if (thinking) 4 else 12
        when {
            live.text.isBlank() -> b.add(ChatRow.Typing("typing:" + live.attemptId, live.toolNames, gap))
            liveEntry == null || liveEntry.blocks.isEmpty() -> b.add(ChatRow.Raw(key, live.text.take(EAGER_PARSE_CHARS)))
            else -> b.emit(BubbleKind.LIVE, key, liveEntry, emptyList(), gap)
        }
    }
    pending.forEach { b.add(ChatRow.Pending(it)) }
    return b.rows
}

private const val WEB_SEARCH = "web_search"

/**
 * The streaming reply's parse: short text in the composition, long text off the main thread at most every 100 ms and never
 * starved (see [conflatedParse]); until a long parse lands, the last one stays.
 */
@Composable
private fun rememberLiveEntry(live: LiveReply?, cache: BlockCache): BlockCache.Entry? {
    val attempt = live?.attemptId
    val text = live?.text.orEmpty()
    val holder = remember(attempt) { arrayOfNulls<BlockCache.Entry>(1) }
    var parsed by remember(attempt) { mutableStateOf<BlockCache.Entry?>(null) }
    val latest by rememberUpdatedState(text)
    val long = text.length > EAGER_PARSE_CHARS
    LaunchedEffect(attempt, long) {
        if (attempt == null || !long) return@LaunchedEffect
        var made: BlockCache.Entry? = null
        conflatedParse(
            snapshotFlow { latest },
            streaming = true,
            parse = { t -> MarkdownParser.parse(t).also { made = BlockCache.Entry(t, it).also { e -> e.pieces } } },
        ) {
            made?.let { e -> parsed = e; cache.live(e) }
        }
    }
    if (attempt == null) return null
    if (!long) {
        val last = holder[0]
        if (last == null || last.text != text) holder[0] = BlockCache.Entry(text, MarkdownParser.parse(text)).also(cache::live)
        return holder[0]
    }
    return parsed ?: holder[0]
}

// ---- the screen --------------------------------------------------------------------------------------------------------

/**
 * What the chat page keeps for one conversation beyond a visit (final review B2-I3): the parsed Markdown, the open thinking
 * sections and where the list was. The shell session holds one next to the conversation's store, so switching tabs or opening
 * the list and coming back neither parses again nor folds anything. Main thread only.
 */
@Stable
class ChatUiCache {
    /** This conversation's thinking strength (dsh reasoningEffort: off/low/high/max); null = the model's default. */
    var effort by mutableStateOf<String?>(null)
    /** How many independent reviewer rounds each reply gets (0..[app.dshmobile.chat.ReviewRounds.MAX]). */
    var reviewRounds by mutableIntStateOf(0)
    internal val blocks = BlockCache()
    internal var expanded by mutableStateOf(emptySet<String>())
    internal var scrollIndex = 0
    internal var scrollOffset = 0
    /** The live reply seen last; when it ends with its thinking open, the finished message's thinking opens (final review B2-M8). */
    internal var lastLive: String? = null
}

/** The thinking of a reply that just finished stays open on its durable message (its key changes from `live:` to `a:`). */
private fun carryOpenThinking(ui: ChatUiCache, live: String?, items: List<ChatItem>) {
    if (live == ui.lastLive) return
    val ended = ui.lastLive
    ui.lastLive = live
    if (ended == null) return
    val liveKey = "think:live:$ended"
    Snapshot.withoutReadObservation {
        if (liveKey !in ui.expanded) return@withoutReadObservation
        val reply = items.lastOrNull { it is ChatItem.Assistant && !it.reasoning.isNullOrBlank() } ?: return@withoutReadObservation
        ui.expanded = ui.expanded - liveKey + ("think:a:" + reply.key)
    }
}

/**
 * The chat page: the header (「☰ 對話」, the title on one line, the model chip and its inline menu), the error lines, and the
 * messages in a lazy list anchored at the newest. Every Markdown block of a long reply is a lazy item of its own, so a
 * 100 000-character chapter scrolls like a short one. Thinking is folded under 「思考過程」; `web_search` is a source card; an
 * answer that links to the previous search card's sources says which. At the bottom it follows the newest message; scrolled up,
 * it stays where it is.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onOpenList: () -> Unit,
    onPickModel: (String) -> Unit,
    onLink: (String) -> Unit,
    onShare: (String) -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryFollow: () -> Unit = {},
    onRetrySend: (String) -> Unit = {},
    onDismissSend: (String) -> Unit = {},
    /** The conversation's cache from the shell session; a fresh one (lost when the screen leaves) when none is given. */
    ui: ChatUiCache = remember { ChatUiCache() },
    /** Restarts dsh for the 「被舊的 dsh 佔住」 refusal (stage 2 §A). */
    onRestartDsh: () -> Unit = {},
    onPickEffort: (String?) -> Unit = {},
    onPickReview: (Int) -> Unit = {},
) {
    val pad = LocalPagePadding.current
    val dir = LocalLayoutDirection.current
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }

    carryOpenThinking(ui, state.live?.attemptId, state.items)
    val cache = ui.blocks
    var expanded by ui::expanded
    val revision = cache.revision
    val attachments = stringResource(R.string.chat_attachments)
    val durable = remember(state.items, state.hasMore, state.olderError, expanded, revision, attachments) {
        durableRows(state.items, state.hasMore, state.olderError, cache, expanded, attachments)
    }
    LaunchedEffect(durable.missing) { parseMissing(durable.missing, cache) }
    val liveEntry = rememberLiveEntry(state.live, cache)
    val tail = remember(state.live, liveEntry, state.pending, expanded) { tailRows(state.live, liveEntry, state.pending, cache, expanded) }
    // The list is drawn bottom-up: index 0 is the newest row, at the bottom.
    val newest = remember(tail) { tail.asReversed() }
    val oldest = remember(durable) { durable.rows.asReversed() }

    val listState = rememberLazyListState(ui.scrollIndex, ui.scrollOffset)
    // Where the list was, for the next visit to this conversation.
    androidx.compose.runtime.DisposableEffect(listState, ui) {
        onDispose {
            ui.scrollIndex = listState.firstVisibleItemIndex
            ui.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    val scope = rememberCoroutineScope()
    // The ↓ pill: shown while the list is scrolled up and something newer arrived since the user left the bottom.
    val newestKey = newest.firstOrNull()?.key ?: oldest.firstOrNull()?.key
    val latestNewestKey by rememberUpdatedState(newestKey)
    var keyWhenLeft by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex > 0 }.collect { up -> keyWhenLeft = if (up) latestNewestKey else null }
    }
    val showPill = keyWhenLeft != null && newestKey != keyWhenLeft
    // A message the user just sent: go down to it, wherever the list was.
    val pendingIds = remember(state.pending) { state.pending.mapTo(HashSet()) { it.requestId } }
    var knownPending by remember { mutableStateOf(pendingIds) }
    LaunchedEffect(pendingIds) {
        val fresh = pendingIds.any { it !in knownPending }
        knownPending = pendingIds
        if (fresh) listState.animateScrollToItem(0)
    }
    val link by rememberUpdatedState(onLink)
    val share by rememberUpdatedState(onShare)
    val older by rememberUpdatedState(onLoadOlder)
    val resend by rememberUpdatedState(onRetrySend)
    val dismiss by rememberUpdatedState(onDismissSend)
    // One instance for the screen's life: rows that did not change skip recomposition while a reply streams.
    val actions = remember {
        RowActions({ link(it) }, { share(it) }, { older() }, { resend(it) }, { dismiss(it) }) { key ->
            expanded = if (key in expanded) expanded - key else expanded + key
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir), top = pad.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val chip = (state.models.firstOrNull { it.selected }?.label ?: state.modelLabel ?: stringResource(R.string.chat_model_unknown)).uppercase()
            ChatHeader(state.title.ifBlank { stringResource(R.string.chat_untitled) }, chip, onOpenList) { menuOpen = !menuOpen }
            if (menuOpen) ModelMenu(state.models, state.effort, state.reviewRounds, onPickEffort, onPickReview) { id -> menuOpen = false; onPickModel(id) }
            if (state.effort != null || state.reviewRounds > 0) {
                Text(
                    tuningSummary(state.effort, state.reviewRounds), color = Sakura.TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp).testTag(Tags.CHAT_TUNING),
                )
            }
            state.followError?.let { FollowBanner(it, onRetryFollow) }
            state.sendError?.let { code ->
                if (code == WRITER_HELD) {
                    // dsh refuses because an old dsh still holds this conversation (bug-writer-held): say so once, with the cure.
                    ErrorLine(stringResource(R.string.chat_writer_held), Tags.CHAT_ERROR)
                    TextButton(onClick = onRestartDsh, modifier = Modifier.testTag(Tags.CHAT_RESTART_DSH)) { Text(stringResource(R.string.chat_writer_held_action)) }
                } else {
                    ErrorLine(stringResource(R.string.chat_action_failed, code), Tags.CHAT_ERROR)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Messages are glass without blur: a blurred backdrop per bubble would be redrawn on every scroll frame. Panels, the
            // header and the dock keep theirs.
            CompositionLocalProvider(LocalBackdrop provides null) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().testTag(Tags.CHAT_MESSAGES),
                    contentPadding = PaddingValues(
                        start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir), top = 6.dp, bottom = pad.calculateBottomPadding(),
                    ),
                ) {
                    // Runs when the rows change, before they are measured: a list resting at the very bottom stays there, so a new row
                    // below comes into view. Scrolled up, or while a scroll runs, the list keeps its place (rows keep their keys). Read
                    // without observation, so scrolling never re-runs this.
                    Snapshot.withoutReadObservation {
                        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 && !listState.isScrollInProgress) {
                            listState.requestScrollToItem(0)
                        }
                    }
                    rows(newest, actions)
                    rows(oldest, actions)
                }
            }
            if (showPill) {
                val label = stringResource(R.string.chat_scroll_to_newest)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = pad.calculateBottomPadding() + 4.dp)
                        .size(48.dp)
                        .glass(CircleShape)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { scope.launch { listState.animateScrollToItem(0) } }
                        .testTag(Tags.SCROLL_TO_NEWEST)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { Text("↓", color = Sakura.Text, fontSize = 20.sp) }
            }
            // Messages soften under the header instead of being cut (like the shell's fade under its own header).
            Box(Modifier.fillMaxWidth().height(10.dp).background(Brush.verticalGradient(listOf(Sakura.Background.copy(alpha = .9f), Color.Transparent))))
        }
    }
}

@Stable
private class RowActions(
    val onLink: (String) -> Unit,
    val onShare: (String) -> Unit,
    val onLoadOlder: () -> Unit,
    val onRetrySend: (String) -> Unit,
    val onDismissSend: (String) -> Unit,
    val onToggle: (String) -> Unit,
)

private fun LazyListScope.rows(rows: List<ChatRow>, a: RowActions) {
    items(rows.size, key = { rows[it].key }, contentType = { rows[it].type }) { i ->
        val row = rows[i]
        Box(Modifier.fillMaxWidth().padding(top = row.gap.dp)) { RowContent(row, a) }
    }
}

@Composable
private fun RowContent(row: ChatRow, a: RowActions) {
    when (row) {
        is ChatRow.Older -> OlderRow(row.error, a.onLoadOlder)
        is ChatRow.Whole -> Bubble(row.kind, row.blocks, row.cites, a.onLink)
        is ChatRow.Segment -> BubbleSegment(row.kind, row.block, row.pos, row.inner.dp, row.cites, row.shade, a.onLink)
        is ChatRow.Raw -> RawBubble(BubbleKind.LIVE, row.text)
        is ChatRow.Placeholder -> PlaceholderBubble(row.kind)
        is ChatRow.Typing -> TypingBubble(row.tools)
        is ChatRow.Thinking -> ThinkingToggle(row.open) { a.onToggle(row.key) }
        is ChatRow.Reasoning -> ReasoningPiece(row.text, row.first, row.last)
        is ChatRow.Tool -> ToolCard(row.item, a.onShare)
        is ChatRow.Search -> SearchCard(row.item, a.onLink)
        is ChatRow.Notice -> NoticeLine(row.item)
        is ChatRow.Pending -> PendingBubble(row.p, a.onRetrySend, a.onDismissSend)
    }
}

@Composable
private fun OlderRow(error: String?, onLoadOlder: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FogButton(stringResource(R.string.chat_load_older), FogButtonKind.GHOST, onLoadOlder, Modifier.testTag(Tags.LOAD_OLDER))
        error?.let { ErrorLine(stringResource(R.string.chat_older_failed, it), Tags.OLDER_ERROR) }
    }
}

private val Pill = RoundedCornerShape(50)

/** 「☰ 對話」, the title (one line, ellipsized) and the model chip, on a glass panel. */
@Composable
private fun ChatHeader(title: String, chip: String, onOpenList: () -> Unit, onChip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(FogDimens.Panel)).testTag(Tags.CHAT_HEADER).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .height(36.dp)
                .clip(Pill)
                .background(Color.White.copy(alpha = .6f))
                .border(1.dp, Sakura.Primary.copy(alpha = .6f), Pill)
                .clickable(role = Role.Button, onClick = onOpenList)
                .testTag(Tags.CHAT_LIST_BUTTON)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.chat_list_button), color = Sakura.Text, fontSize = 13.sp, maxLines = 1) }
        Text(
            title, color = Sakura.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag(Tags.CHAT_TITLE),
        )
        val menuLabel = stringResource(R.string.chat_model_menu_title)
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(Pill)
                .background(Color.White.copy(alpha = .6f))
                .border(1.dp, Sakura.Primary.copy(alpha = .6f), Pill)
                .clickable(role = Role.Button, onClick = onChip)
                .testTag(Tags.MODEL_CHIP)
                .semantics { contentDescription = menuLabel }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                chip, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = FogFonts.ChakraPetch, fontSize = 11.sp, letterSpacing = 0.14.em, color = Sakura.Text),
            )
        }
    }
}

/** 「這個對話使用的模型」: inline under the header (it pushes the messages down, so it never covers a control). */
private fun effortLabel(e: String?): String = when (e) {
    "off" -> "關"; "low" -> "低"; "high" -> "高"; "max" -> "最高"; else -> "預設"
}

@Composable
private fun tuningSummary(effort: String?, rounds: Int): String =
    listOfNotNull(
        effort?.let { stringResource(R.string.chat_effort_summary, effortLabel(it)) },
        rounds.takeIf { it > 0 }?.let { stringResource(R.string.chat_review_summary, it) },
    ).joinToString(" · ")

/** A row of small selectable chips (thinking strength, reviewer rounds). */
@Composable
private fun <T> ChipRow(title: String, options: List<T>, current: T, label: (T) -> String, tag: (T) -> String, onPick: (T) -> Unit) {
    Text(title, color = Sakura.TextSecondary, fontSize = 13.sp, letterSpacing = 0.12.em, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 6.dp)) {
        options.forEach { o ->
            val on = o == current
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(FogDimens.Thumb))
                    .background(if (on) Color.White.copy(alpha = .7f) else Color.White.copy(alpha = .25f))
                    .border(1.dp, if (on) Sakura.Primary else Color.Transparent, RoundedCornerShape(FogDimens.Thumb))
                    .clickable(role = Role.Button) { onPick(o) }
                    .testTag(tag(o))
                    .semantics { selected = on }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), color = Sakura.Text, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun ModelMenu(
    models: List<ModelChoice>,
    effort: String?,
    reviewRounds: Int,
    onPickEffort: (String?) -> Unit,
    onPickReview: (Int) -> Unit,
    onPick: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(FogDimens.Sheet))
            .testTag(Tags.MODEL_MENU)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.chat_model_menu_title), color = Sakura.TextSecondary, fontSize = 13.sp, letterSpacing = 0.12.em,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )
        models.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(FogDimens.Thumb))
                    .background(if (m.selected) Color.White.copy(alpha = .55f) else Color.Transparent)
                    .clickable(role = Role.Button) { onPick(m.id) }
                    .testTag(Tags.MODEL_OPTION + m.id)
                    .semantics { selected = m.selected }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val dot = if (m.selected) Sakura.Primary else Sakura.RoseBrown.copy(alpha = .3f)
                Box(Modifier.size(8.dp).then(if (m.selected) Modifier.glow(Sakura.Primary, 6.dp, CircleShape) else Modifier).background(dot, CircleShape))
                Text(
                    m.label, color = Sakura.Text, fontSize = 15.sp, fontWeight = if (m.selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ChipRow(stringResource(R.string.chat_effort_title), listOf<String?>(null) + EFFORTS, effort, { effortLabel(it) }, { Tags.EFFORT_OPTION + (it ?: "default") }, onPickEffort)
        ChipRow(stringResource(R.string.chat_review_title), (0..app.dshmobile.chat.ReviewRounds.MAX).toList(), reviewRounds, { if (it == 0) "不審" else "$it 輪" }, { Tags.REVIEW_OPTION + it }, onPickReview)
    }
}

@Composable
private fun FollowBanner(code: String, onRetry: () -> Unit) {
    val text = if (code == "ended") stringResource(R.string.chat_follow_ended) else stringResource(R.string.chat_follow_failed, code)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .6f), RoundedCornerShape(FogDimens.SearchCard))
            .border(1.dp, Sakura.Primary.copy(alpha = .6f), RoundedCornerShape(FogDimens.SearchCard))
            .testTag(Tags.FOLLOW_ERROR)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(Sakura.RoseBrown, CircleShape))
        Text(text, color = Sakura.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        FogButton(stringResource(R.string.chat_follow_retry), FogButtonKind.AQUA, onRetry, Modifier.testTag(Tags.FOLLOW_RETRY))
    }
}

/** dsh's refusal when another live dsh still holds the conversation's write lock. */
const val WRITER_HELD = "session/writer-held"

@Composable
private fun ErrorLine(text: String, tag: String) {
    Text(
        text, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .6f), RoundedCornerShape(12.dp))
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
