package app.dshmobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Folds `session/follow` frames (docs/dsh-ui-protocol.md §2.2-2.5) into [ConversationState]. Pure and total: a frame it
 * does not understand leaves the state as it was, and nothing here throws.
 */
object ConversationReducer {
    const val WORKSPACE_ROOT = "/root/workspace/"
    private const val SUMMARY_MAX = 80
    private const val ERROR_MAX = 200
    private const val STOPPED = "已停止"
    private const val REJECTED = "已拒絕"

    fun reduce(state: ConversationState, frame: JsonElement?): ConversationState = try {
        val o = frame as? JsonObject
        when (o?.str("type")) {
            "snapshot" -> if (o["records"] is JsonArray) snapshot(state, o) else state
            "event" -> o.obj("event")?.let { event ->
                val seq = event.num("seq")
                val last = state.lastSeq
                if (seq != null && last != null && seq <= last) state // replayed durable event
                else applyEvent(state, event).let { if (seq != null) it.copy(lastSeq = maxOf(seq, last ?: seq)) else it }
            } ?: state
            else -> state
        }
    } catch (_: RuntimeException) {
        state
    }

    /**
     * Folds one `assistant-stream` frame by plain concatenation. The store does NOT use this (it would be quadratic over a
     * 100k-character reply): it accumulates with StringBuilders and publishes at a bounded rate. [reduce] ignores
     * `assistant-stream` frames, so this is the reference behaviour for tests only.
     */
    internal fun reduceLive(state: ConversationState, frame: JsonElement?): ConversationState = try {
        val f = (frame as? JsonObject)?.takeIf { it.str("type") == "assistant-stream" }?.obj("frame")
        if (f == null) state else live(state, f)
    } catch (_: RuntimeException) {
        state
    }

    /** Adds the older [page] (a `session/page` answer, shaped like a snapshot) before the items already shown. */
    fun prepend(state: ConversationState, page: JsonElement): ConversationState = try {
        val o = page as? JsonObject ?: return state
        val records = events(o)
        val older = records.fold(ConversationState(state.sessionId)) { s, e -> applyEvent(s, e) }
        val known = state.items.mapTo(HashSet()) { it.key }
        state.copy(
            items = older.items.filter { it.key !in known } + state.items,
            hasMore = o.bool("hasMore") ?: false,
            firstSeq = listOfNotNull(minSeq(records), state.firstSeq).minOrNull(),
            acceptedRequestIds = state.acceptedRequestIds + older.acceptedRequestIds,
        )
    } catch (_: RuntimeException) {
        state
    }

    /** The socket was replaced: whatever was streaming is gone; [ConversationState.items] stay until the new snapshot. */
    fun reopened(state: ConversationState): ConversationState = state.copy(live = null, running = false)

    // ---- snapshot -------------------------------------------------------------------------------------------------

    private fun snapshot(state: ConversationState, o: JsonObject): ConversationState {
        val records = events(o)
        val base = ConversationState(state.sessionId, title = state.title)
        val rebuilt = records.fold(base) { s, e -> applyEvent(s, e) }
        val projected = o.obj("projections")?.obj("values")?.str("title")
        return rebuilt.copy(
            live = null,
            title = rebuilt.title ?: projected,
            hasMore = o.bool("hasMore") ?: false,
            firstSeq = minSeq(records),
            lastSeq = listOfNotNull(records.mapNotNull { it.num("seq") }.maxOrNull(), o.num("cursor")).maxOrNull(),
            acceptedRequestIds = state.acceptedRequestIds + rebuilt.acceptedRequestIds,
        )
    }

    private fun events(o: JsonObject): List<JsonObject> =
        (o["records"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.obj("event") }

    private fun minSeq(events: List<JsonObject>): Long? = events.mapNotNull { it.num("seq") }.minOrNull()

    // ---- durable events -------------------------------------------------------------------------------------------

    private fun applyEvent(state: ConversationState, event: JsonObject): ConversationState {
        val type = event.str("type") ?: return state
        val seq = event.num("seq")
        val data = event.obj("data") ?: return state
        return when (type) {
            "user/message" -> userMessage(state, seq, data)
            "assistant/message" -> assistantMessage(state, seq, data)
            "tool/call" -> toolCall(state, data)
            "tool/result" -> toolResult(state, data)
            "approval/asked" -> approvalAsked(state, data)
            "approval/decided" -> approvalDecided(state, data)
            "turn/start" -> state.copy(running = true)
            "turn/end" -> turnEnd(state, seq, data)
            "session/title" -> data.str("title")?.let { state.copy(title = it) } ?: state
            else -> state
        }
    }

    private fun userMessage(state: ConversationState, seq: Long?, data: JsonObject): ConversationState {
        val source = data.obj("source")
        if (source?.str("kind") != "user" || seq == null) return state
        val accepted = source.str("rpcId")?.let { state.acceptedRequestIds + it } ?: state.acceptedRequestIds
        val content = data.obj("message")?.get("content") ?: data["content"]
        val text = StringBuilder()
        val attachments = ArrayList<String>()
        when (content) {
            is JsonPrimitive -> if (content.isString) text.append(content.content)
            is JsonArray -> for (part in content) {
                val p = part as? JsonObject ?: continue
                when (p.str("type")) {
                    "text" -> p.str("text")?.let(text::append)
                    "image" -> attachments += p.str("name") ?: "圖片"
                    "file" -> attachments += p.str("name") ?: "檔案"
                }
            }
            else -> {}
        }
        val item = ChatItem.User("e$seq", text.toString(), attachments)
        return withItem(state.copy(acceptedRequestIds = accepted), item)
    }

    private fun assistantMessage(state: ConversationState, seq: Long?, data: JsonObject): ConversationState {
        if (seq == null) return state
        val content = data.obj("message")?.get("content")
        val reasoning = StringBuilder()
        val text = StringBuilder()
        val calls = ArrayList<JsonObject>()
        when (content) {
            is JsonPrimitive -> if (content.isString) text.append(content.content)
            is JsonArray -> for (part in content) {
                val p = part as? JsonObject ?: continue
                when (p.str("type")) {
                    "reasoning" -> p.str("text")?.let(reasoning::append)
                    "text" -> p.str("text")?.let(text::append)
                    "tool-call" -> calls += p
                }
            }
            else -> {}
        }
        var s = withItem(state, ChatItem.Assistant("e$seq", seq, reasoning.takeIf { it.isNotEmpty() }?.toString(), text.toString()))
        for (c in calls) {
            val id = c.str("id") ?: continue
            s = ensureTool(s, id, c.str("name").orEmpty(), c["arguments"])
        }
        return s
    }

    private fun toolCall(state: ConversationState, data: JsonObject): ConversationState {
        val id = data.str("callId") ?: return state
        return ensureTool(state, id, data.str("name").orEmpty(), data["arguments"])
    }

    /** Adds the card when [id] has none; fills in a card that an approval created before its call arrived. */
    private fun ensureTool(state: ConversationState, id: String, name: String, arguments: JsonElement?): ConversationState {
        val existing = state.items.indexOfLast { it is ChatItem.Tool && it.key == id }
        val args = argsOf(arguments)
        val card = ChatItem.Tool(id, name, summary(name, args, arguments), ToolStatus.RUNNING, sharePath = sharePath(name, args))
        if (existing < 0) return state.copy(items = state.items + card)
        val old = state.items[existing] as ChatItem.Tool
        if (old.name.isNotEmpty() && old.summary.isNotEmpty()) return state
        return replace(state, existing, old.copy(name = card.name.ifEmpty { old.name }, summary = card.summary.ifEmpty { old.summary }, sharePath = card.sharePath ?: old.sharePath))
    }

    private fun toolResult(state: ConversationState, data: JsonObject): ConversationState {
        val message = data.obj("message") ?: return state
        val id = message.str("toolCallId") ?: return state
        val index = state.items.indexOfLast { it is ChatItem.Tool && it.key == id }
        if (index < 0) return state
        val card = state.items[index] as ChatItem.Tool
        val text = textOf(message["content"])
        val failed = message.bool("isError") == true
        val sources = (sourcesFromMeta(data.obj("meta")).ifEmpty { if (card.name == "web_search") sourcesFromText(text) else emptyList() })
        return replace(
            state, index,
            card.copy(
                status = if (failed) ToolStatus.FAILED else ToolStatus.DONE,
                error = if (failed) text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(ERROR_MAX) else null,
                sources = sources.ifEmpty { card.sources },
            ),
        )
    }

    private fun approvalAsked(state: ConversationState, data: JsonObject): ConversationState {
        val callId = data.str("callId") ?: return state
        val withMap = data.str("id")?.let { state.copy(approvals = state.approvals + (it to callId)) } ?: state
        val index = withMap.items.indexOfLast { it is ChatItem.Tool && it.key == callId }
        if (index < 0) {
            val card = ChatItem.Tool(callId, data.str("toolName").orEmpty(), "", ToolStatus.WAITING_APPROVAL)
            return withMap.copy(items = withMap.items + card)
        }
        return replace(withMap, index, (withMap.items[index] as ChatItem.Tool).copy(status = ToolStatus.WAITING_APPROVAL))
    }

    private fun approvalDecided(state: ConversationState, data: JsonObject): ConversationState {
        val callId = data.str("id")?.let { state.approvals[it] } ?: return state
        val index = state.items.indexOfLast { it is ChatItem.Tool && it.key == callId }
        if (index < 0) return state
        val card = state.items[index] as ChatItem.Tool
        return when (data.str("outcome")) {
            "rejected", "cancelled", "unavailable" -> replace(state, index, card.copy(status = ToolStatus.FAILED, error = REJECTED))
            "allowed-once" -> replace(state, index, card.copy(status = ToolStatus.RUNNING))
            else -> state
        }
    }

    private fun turnEnd(state: ConversationState, seq: Long?, data: JsonObject): ConversationState {
        val ended = state.copy(running = false)
        if (data.obj("reason")?.str("kind") != "aborted" || seq == null) return ended
        return withItem(ended, ChatItem.Notice("n$seq", NoticeKind.STOPPED, STOPPED))
    }

    // ---- live stream ----------------------------------------------------------------------------------------------

    private fun live(state: ConversationState, frame: JsonObject): ConversationState {
        val current = state.live
        return when (frame.str("type")) {
            "start" -> frame.str("attemptId")?.let { state.copy(live = LiveReply(it, "", "", emptyList())) } ?: state
            "end" -> state.copy(live = null)
            "chunk" -> {
                if (current == null || frame.str("attemptId").let { it != null && it != current.attemptId }) return state
                val chunk = frame.obj("chunk") ?: return state
                val next = when (chunk.str("type")) {
                    "text-delta" -> chunk.str("text")?.let { current.copy(text = current.text + it) }
                    "reasoning-delta" -> chunk.str("text")?.let { current.copy(reasoning = current.reasoning + it) }
                    "tool-call-delta" -> chunk.str("name")?.takeIf { it !in current.toolNames }?.let { current.copy(toolNames = current.toolNames + it) }
                    else -> null
                }
                if (next == null) state else state.copy(live = next)
            }
            else -> state
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Adds [item] unless an item with its key is already there (an event that also came in the snapshot). */
    private fun withItem(state: ConversationState, item: ChatItem): ConversationState =
        if (state.items.any { it.key == item.key }) state else state.copy(items = state.items + item)

    private fun replace(state: ConversationState, index: Int, item: ChatItem): ConversationState =
        state.copy(items = state.items.toMutableList().also { it[index] = item })

    private fun argsOf(arguments: JsonElement?): JsonObject? = when (arguments) {
        is JsonObject -> arguments
        is JsonPrimitive -> if (arguments.isString) runCatching { Json.parseToJsonElement(arguments.content) as? JsonObject }.getOrNull() else null
        else -> null
    }

    internal fun summary(name: String, args: JsonObject?, raw: JsonElement?): String {
        if (args != null) {
            if (name == "web_search") {
                val queries = (args["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (!queries.isNullOrEmpty()) return queries.joinToString(", ").take(SUMMARY_MAX)
            }
            for (key in listOf("description", "command", "file_path", "path", "url", "query")) {
                args.str(key)?.takeIf { it.isNotBlank() }?.let { return it.take(SUMMARY_MAX) }
            }
            return args.toString().take(SUMMARY_MAX)
        }
        return ((raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").take(SUMMARY_MAX)
    }

    internal fun sharePath(name: String, args: JsonObject?): String? {
        if (name != "write" && name != "edit") return null
        val path = args?.str("file_path") ?: return null
        if (!path.startsWith(WORKSPACE_ROOT)) return null
        val stack = ArrayList<String>()
        for (segment in path.removePrefix(WORKSPACE_ROOT).split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.lastIndex)
                else -> stack += segment
            }
        }
        return stack.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun textOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("type") == "text" }?.str("text") }.joinToString("")
        else -> ""
    }

    private fun sourcesFromMeta(meta: JsonObject?): List<WebSource> =
        ((meta?.get("sources") as? JsonArray).orEmpty()).mapNotNull { s ->
            val o = s as? JsonObject ?: return@mapNotNull null
            val url = o.str("url") ?: return@mapNotNull null
            WebSource(url, o.str("title")?.takeIf { it.isNotBlank() } ?: url)
        }.let(::cleanSources)

    private val MARKDOWN_LINK = Regex("""\[([^\]\n]*)]\((https?://[^)\s]+)\)""", RegexOption.IGNORE_CASE)

    private fun sourcesFromText(text: String): List<WebSource> =
        cleanSources(MARKDOWN_LINK.findAll(text).map { WebSource(it.groupValues[2], it.groupValues[1].ifBlank { it.groupValues[2] }) }.toList())

    private fun cleanSources(all: List<WebSource>): List<WebSource> =
        all.filter { it.url.startsWith("http://", true) || it.url.startsWith("https://", true) }.distinctBy { it.url }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}
