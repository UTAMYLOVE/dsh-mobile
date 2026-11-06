package app.dshmobile.chat

/**
 * What the chat page renders for one session. [items] are the durable history (from `session/follow` events), [live] the
 * assistant reply still being streamed, [running] whether a turn is in progress.
 */
data class ConversationState(
    val sessionId: String,
    val items: List<ChatItem> = emptyList(),
    val live: LiveReply? = null,
    val running: Boolean = false,
    val title: String? = null,
    val hasMore: Boolean = false,
    val firstSeq: Long? = null,
    /** `session/prompt` request ids dsh has recorded as user messages (`data.source.rpcId`). */
    val acceptedRequestIds: Set<String> = emptySet(),
    /** Approval id to tool callId, so `approval/decided` (which only names the approval) finds its card. */
    internal val approvals: Map<String, String> = emptyMap(),
    /** Highest durable event seq applied (snapshot max or cursor); a replayed event frame at or below it is dropped. */
    internal val lastSeq: Long? = null,
    /** Stats of each finished reply, keyed by the key of the turn's last [ChatItem.Assistant] (stage 2 §C). */
    val replyStats: Map<String, ReplyStats> = emptyMap(),
    /** The latest prompt size against the model's context window; null until a reply has been seen. */
    val context: ContextFill? = null,
    /** The turn being folded; only the reducer reads it. */
    internal val turn: TurnAcc? = null,
)

/**
 * One finished reply (one turn, every step summed). [ttftMs]: first step's start to its first streamed chunk; [decodeMs]: summed
 * first-chunk-to-message time of every step, so `outputTokens / decodeMs` is the generation speed. Times come from dsh's events.
 */
data class ReplyStats(
    val provider: String?,
    val model: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val ttftMs: Long?,
    val decodeMs: Long,
    val durationMs: Long?,
    val steps: Int,
) {
    /** Output tokens per second while generating; null when too short to measure. */
    val tokensPerSecond: Double? get() = if (decodeMs >= MIN_DECODE_MS && outputTokens > 0) outputTokens * 1000.0 / decodeMs else null

    private companion object {
        const val MIN_DECODE_MS = 50L
    }
}

/** [tokens] of the last prompt (uncached input plus cache reads) out of [window] tokens. */
data class ContextFill(val tokens: Long, val window: Long?) {
    val fraction: Float? get() = window?.takeIf { it > 0 }?.let { (tokens.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

data class TurnAcc(
    val startMs: Long? = null,
    val stepStartMs: Long? = null,
    val provider: String? = null,
    val model: String? = null,
    val window: Long? = null,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val ttftMs: Long? = null,
    val decodeMs: Long = 0,
    val steps: Int = 0,
    val lastAssistant: String? = null,
)

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<String>) : ChatItem
    data class Assistant(
        override val key: String, val seq: Long, val reasoning: String?, val text: String,
        /** 建議下一步 from the reply's hidden block (FollowUps). */
        val followUps: List<String> = emptyList(),
    ) : ChatItem
    data class Tool(
        override val key: String,
        val name: String,
        val summary: String,
        val status: ToolStatus,
        val sources: List<WebSource> = emptyList(),
        /** Path relative to the workspace root for a `write`/`edit` under it; the chat offers it for sharing. */
        val sharePath: String? = null,
        val error: String? = null,
    ) : ChatItem
    data class Notice(override val key: String, val kind: NoticeKind, val text: String) : ChatItem
}

enum class ToolStatus { RUNNING, WAITING_APPROVAL, DONE, FAILED }

enum class NoticeKind { STOPPED, ERROR }

data class WebSource(val url: String, val title: String)

data class LiveReply(val attemptId: String, val reasoning: String, val text: String, val toolNames: List<String>)
