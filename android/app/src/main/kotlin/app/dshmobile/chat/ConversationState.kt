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
)

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<String>) : ChatItem
    data class Assistant(override val key: String, val seq: Long, val reasoning: String?, val text: String) : ChatItem
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
