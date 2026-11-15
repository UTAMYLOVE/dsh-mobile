package app.dshmobile.chat

import app.dshmobile.runtime.DshLayout
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
    private val SUMMARY_KEYS = listOf(
        "description", "command", "file_path", "path", "url", "query", "pattern", "subject", "title", "name", "question", "prompt", "message",
    )
    private const val ERROR_MAX = 200
    private const val TURN_ERROR_MAX = 160

    // The owner's own choices on a tool card. dsh reports each one as a failed result in English ("the user rejected tool",
    // "tool call aborted", "declined in DSH Mobile"), which the owner read as 「失敗」; the card shows the word instead, in grey.
    const val STOPPED = "已停止"
    const val REJECTED = "已拒絕"
    const val SKIPPED = "已略過"
    val OWNER_CHOICES = setOf(STOPPED, REJECTED, SKIPPED)

    /** dsh had nobody to ask (no screen was connected): a real failure, said in Chinese. */
    const val NO_ASK_CHANNEL = "沒有可以詢問你的連線"

    /** What [app.dshmobile.remote.DshEvents.decline] answers for 略過; dsh's tool result echoes it as `Error: <this>`. */
    const val DECLINED_IN_APP = "declined in DSH Mobile"

    // dsh's codes and wording for a cancelled tool (dsh-tools TOOL_ABORTED / TOOL_ABORTED_BEFORE_DISPATCH).
    private val ABORT_CODES = setOf("ABORTED", "ABORTED_BEFORE_DISPATCH")
    private val ABORT_TEXTS = setOf("Error: tool call aborted", "Error: tool call aborted before dispatch")
    private const val USER_REJECTED_PREFIX = "Error: the user rejected tool \""

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
            replyStats = older.replyStats + state.replyStats,
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
            // A reply in flight means a turn runs, even when the message window cut its turn/start off.
            running = rebuilt.running || activeAttempt(o) != null,
            title = rebuilt.title ?: projected,
            hasMore = o.bool("hasMore") ?: false,
            firstSeq = minSeq(records),
            lastSeq = listOfNotNull(records.mapNotNull { it.num("seq") }.maxOrNull(), o.num("cursor")).maxOrNull(),
            acceptedRequestIds = state.acceptedRequestIds + rebuilt.acceptedRequestIds,
            context = pressure(o.obj("projections")?.obj("values")?.obj("contextPressure")) ?: rebuilt.context,
        )
    }

    /**
     * dsh's own `contextPressure` projection `{pressureTokens, projectedTokens, contextWindow}`; null when empty.
     * `projectedTokens` (what the next request's prompt will be, dsh-token-meter) wins over `pressureTokens` (the last request's):
     * only a request reports usage, so after a `/compact` `pressureTokens` still says the old size until the next reply. After
     * a dsh restart the app then saw a condensed conversation as still over [AutoCompact.TOKENS] and sent `/compact` again,
     * and the context bar looked full.
     */
    fun pressure(p: JsonObject?): ContextFill? {
        if (p == null) return null
        val tokens = p.num("projectedTokens") ?: p.num("pressureTokens") ?: return null
        return ContextFill(tokens, p.num("contextWindow"))
    }

    /**
     * The reply dsh was streaming when it made this snapshot (`assistantStream.activeAttempt`, history.js): dsh folds the attempt's
     * chunks so far into the snapshot and then sends only the later chunks, without a `start`. Its compact records are
     * `text-chunks`/`reasoning-chunks` (`texts`), `tool-call-chunks` (`name`) and single `chunk`s (only tool-call deltas carry
     * anything shown). Null when nothing is in flight or the snapshot is not one.
     */
    fun activeAttempt(snapshot: JsonElement?): LiveReply? = try {
        val attempt = (snapshot as? JsonObject)?.obj("assistantStream")?.obj("activeAttempt")
        val id = attempt?.str("attemptId")
        if (attempt == null || id == null) null else {
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val tools = LinkedHashSet<String>()
            for (record in (attempt["stream"] as? JsonArray).orEmpty()) {
                val r = record as? JsonObject ?: continue
                when (r.str("type")) {
                    "text-chunks" -> texts(r).forEach(text::append)
                    "reasoning-chunks" -> texts(r).forEach(reasoning::append)
                    "tool-call-chunks" -> r.str("name")?.takeIf { it.isNotEmpty() }?.let(tools::add)
                    "chunk" -> r.obj("chunk")?.let { c ->
                        when (c.str("type")) {
                            "text-delta" -> c.str("text")?.let(text::append)
                            "reasoning-delta" -> c.str("text")?.let(reasoning::append)
                            "tool-call-delta" -> c.str("name")?.takeIf { it.isNotEmpty() }?.let(tools::add)
                        }
                    }
                }
            }
            LiveReply(id, reasoning.toString(), InlineCards.split(FollowUps.split(text.toString()).first).first, tools.toList())
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun texts(r: JsonObject): List<String> =
        (r["texts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun events(o: JsonObject): List<JsonObject> =
        (o["records"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.obj("event") }

    private fun minSeq(events: List<JsonObject>): Long? = events.mapNotNull { it.num("seq") }.minOrNull()

    // ---- durable events -------------------------------------------------------------------------------------------

    private fun applyEvent(state: ConversationState, event: JsonObject): ConversationState {
        val type = event.str("type") ?: return state
        val seq = event.num("seq")
        val data = event.obj("data") ?: return state
        val time = event.num("time")
        return when (type) {
            "user/message" -> userMessage(state, seq, data)
            "assistant/message" -> countReply(assistantMessage(state.copy(retry = null), seq, data), seq, time, data)
            "step/start" -> state.copy(turn = (state.turn ?: TurnAcc()).copy(stepStartMs = time))
            "request/context" -> state.copy(
                turn = (state.turn ?: TurnAcc()).copy(
                    provider = data.str("provider"), model = data.str("model"), window = data.num("contextWindow"),
                ),
            )
            "tool/call" -> toolCall(state, data)
            "tool/result" -> toolResult(state, data)
            "approval/asked" -> approvalAsked(state, data)
            "approval/decided" -> approvalDecided(state, data)
            "turn/start" -> state.copy(running = true, turn = TurnAcc(startMs = time), retry = null)
            "turn/end" -> turnEnd(finishReply(state, time), seq, data)
            // dsh-llm-retry: {retry, maxRetries?, delayMs, failure}; maxRetries is absent under the "always" policy.
            "llm/retry" -> state.copy(
                retry = RetryInfo(
                    (data.num("retry") ?: 1).toInt(), data.num("maxRetries")?.toInt(),
                    // dsh retries a 429 whatever it says; the owner's own daily cap is not a busy model and will not clear.
                    dailyCap = data.obj("failure")?.str("message")?.contains("dshm_daily_cap", ignoreCase = true) == true,
                ),
            )
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
        val item = ChatItem.User("e$seq", ComposeMode.strip(ReviewRounds.strip(text.toString())), attachments)
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
        val (withCards, followUps) = FollowUps.split(text.toString())
        val (body, cards) = InlineCards.split(withCards)
        var s = withItem(state, ChatItem.Assistant("e$seq", seq, reasoning.takeIf { it.isNotEmpty() }?.toString(), body, followUps, cards))
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
        val card = ChatItem.Tool(id, name, summary(name, args, arguments), ToolStatus.RUNNING, sharePath = sharePath(name, args), presented = presented(name, args))
        if (existing < 0) return state.copy(items = state.items + card)
        val old = state.items[existing] as ChatItem.Tool
        if (old.name.isNotEmpty() && old.summary.isNotEmpty()) return state
        return replace(state, existing, old.copy(name = card.name.ifEmpty { old.name }, summary = card.summary.ifEmpty { old.summary }, sharePath = card.sharePath ?: old.sharePath, presented = card.presented.ifEmpty { old.presented }))
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
                error = if (failed) failureOf(card, data, text) else null,
                sources = sources.ifEmpty { card.sources },
            ),
        )
    }

    /**
     * What a failed card says: the owner's own choice when it was one, else dsh's first error line. A card the owner already
     * decided keeps its word, because dsh's result for it (`Error: the user rejected tool …`) used to overwrite 已拒絕.
     */
    private fun failureOf(card: ChatItem.Tool, data: JsonObject, text: String): String? {
        card.error?.takeIf { card.status == ToolStatus.FAILED && (it in OWNER_CHOICES || it == NO_ASK_CHANNEL) }?.let { return it }
        return when {
            data.obj("error")?.str("code") in ABORT_CODES || text.trim() in ABORT_TEXTS -> STOPPED
            // Only the owner's own rejection: "Auto review rejected tool" is a real block and stays a failure with its text.
            text.startsWith(USER_REJECTED_PREFIX) -> REJECTED
            text.startsWith("Error: $DECLINED_IN_APP") -> SKIPPED
            else -> text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(ERROR_MAX)
        }
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
            "rejected" -> replace(state, index, card.copy(status = ToolStatus.FAILED, error = REJECTED))
            // STOP while the question was up.
            "cancelled" -> replace(state, index, card.copy(status = ToolStatus.FAILED, error = STOPPED))
            "unavailable" -> replace(state, index, card.copy(status = ToolStatus.FAILED, error = NO_ASK_CHANNEL))
            "allowed-once" -> replace(state, index, card.copy(status = ToolStatus.RUNNING))
            else -> state
        }
    }

    /** Adds one `assistant/message`'s usage and timing to the running turn, and updates the context fill. */
    private fun countReply(state: ConversationState, seq: Long?, time: Long?, data: JsonObject): ConversationState {
        val usage = data.obj("usage") ?: return state
        val acc = state.turn ?: TurnAcc()
        val input = usage.num("inputTokens") ?: 0
        val cacheRead = usage.num("cacheReadTokens") ?: 0
        val cacheWrite = usage.num("cacheWriteTokens") ?: 0
        val firstChunk = (data["stream"] as? JsonArray)?.firstNotNullOfOrNull { r ->
            (r as? JsonObject)?.let { it.num("time") ?: it.num("time0") }
        }
        val ttft = acc.ttftMs ?: if (firstChunk != null && acc.stepStartMs != null) (firstChunk - acc.stepStartMs).coerceAtLeast(0) else null
        val decode = if (firstChunk != null && time != null) (time - firstChunk).coerceAtLeast(0) else 0
        val next = acc.copy(
            input = acc.input + input,
            output = acc.output + (usage.num("outputTokens") ?: 0),
            cacheRead = acc.cacheRead + cacheRead,
            cacheWrite = acc.cacheWrite + cacheWrite,
            ttftMs = ttft,
            decodeMs = acc.decodeMs + decode,
            steps = acc.steps + 1,
            lastAssistant = seq?.let { "e$it" } ?: acc.lastAssistant,
            maxPrompt = maxOf(acc.maxPrompt, input + cacheRead + cacheWrite),
        )
        return state.copy(turn = next, context = ContextFill(input + cacheRead + cacheWrite, acc.window ?: state.context?.window))
    }

    /** At `turn/end`, files the folded turn under its last assistant item. */
    private fun finishReply(state: ConversationState, time: Long?): ConversationState {
        val acc = state.turn ?: return state
        val key = acc.lastAssistant ?: return state.copy(turn = null)
        val stats = ReplyStats(
            provider = acc.provider,
            model = acc.model,
            inputTokens = acc.input,
            outputTokens = acc.output,
            cacheReadTokens = acc.cacheRead,
            ttftMs = acc.ttftMs,
            decodeMs = acc.decodeMs,
            durationMs = if (time != null && acc.startMs != null) (time - acc.startMs).coerceAtLeast(0) else null,
            steps = acc.steps,
            cacheWriteTokens = acc.cacheWrite,
            maxPrompt = acc.maxPrompt,
            endedAtMs = time,
        )
        return state.copy(turn = null, replyStats = state.replyStats + (key to stats))
    }

    /**
     * A turn that did not complete says why. Before, only STOP did: a rejected key, an empty balance or a dsh restart ended the
     * turn with no word in the chat while the phone announced 「DSH 完成了」. The key comes from the event's seq, so a snapshot
     * that replays the event finds the notice already there.
     */
    private fun turnEnd(state: ConversationState, seq: Long?, data: JsonObject): ConversationState {
        val ended = state.copy(running = false, retry = null)
        if (seq == null) return ended
        val reason = data.obj("reason")
        val notice = when (reason?.str("kind")) {
            "aborted" -> ChatItem.Notice("n$seq", NoticeKind.STOPPED, STOPPED)
            "error" -> ChatItem.Notice("n$seq", NoticeKind.ERROR, turnErrorText(reason.obj("error")))
            "interrupted" -> ChatItem.Notice("n$seq", NoticeKind.ERROR, TURN_INTERRUPTED)
            "max-tokens" -> ChatItem.Notice("n$seq", NoticeKind.ERROR, TURN_TRUNCATED)
            "blocked" -> ChatItem.Notice("n$seq", NoticeKind.ERROR, TURN_BLOCKED)
            else -> null
        }
        return if (notice == null) ended else withItem(ended, notice)
    }

    const val TURN_INTERRUPTED = "dsh 中途重新啟動，這一輪沒做完，說「繼續」可接著做"
    const val TURN_TRUNCATED = "回覆太長被截斷，說「繼續」可接著寫"
    const val TURN_BLOCKED = "這一輪被擋下了"
    const val TURN_NO_KEY = "找不到金鑰，請到設定貼上金鑰"
    /** The Claude key was removed (on purpose, Settings said Claude work stops): picking a DeepSeek model is enough. */
    const val TURN_NO_CLAUDE_KEY = "沒有 Claude 金鑰了，這個對話換成 DeepSeek 模型就能繼續"
    const val TURN_NO_DEEPSEEK_KEY = "找不到 DeepSeek 金鑰，請到設定貼上金鑰"
    const val TURN_KEY_REJECTED = "金鑰被拒絕，請到設定重新貼上金鑰"
    const val TURN_DAILY_CAP = "已達今日花費上限（每日硬上限）"
    const val TURN_NO_BALANCE = "帳戶餘額不足"
    const val TURN_BUSY = "模型服務太忙，稍後再試"
    const val TURN_OFFLINE = "連不上模型服務，請檢查網路"

    private val QUOTA_TEXT = Regex("""insufficient[\s_-]+(quota|balance|credits?)|credit balance is too low""", RegexOption.IGNORE_CASE)
    private val NETWORK_TEXT = Regex("""\b(network|econn[a-z]+|fetch failed|socket hang up|timed? ?out)\b""", RegexOption.IGNORE_CASE)

    /**
     * One plain sentence for dsh's turn failure `{message, code, status}` (LlmFailure). The app's gateway codes (`dshm_…`) only
     * reach the message through the Claude route (pi-ai quotes the body); the DeepSeek adapter keeps just the status, so every
     * check also accepts dsh's own code and the HTTP status. The quota check runs before the 429 one: a 429 that says
     * "insufficient quota" is an empty balance, not a busy server.
     */
    internal fun turnErrorText(error: JsonObject?): String {
        val message = error?.str("message").orEmpty()
        val code = error?.str("code").orEmpty()
        val status = error?.num("status")
        val m = message.lowercase()
        return when {
            // The gateway names the provider whose key is missing (LocalGateway.providerError).
            "dshm_missing_key" in m && "anthropic" in m -> TURN_NO_CLAUDE_KEY
            "dshm_missing_key" in m && "deepseek" in m -> TURN_NO_DEEPSEEK_KEY
            "dshm_missing_key" in m -> TURN_NO_KEY
            "dshm_invalid_key" in m || status == 401L || status == 403L || code == "AUTH" -> TURN_KEY_REJECTED
            "dshm_daily_cap" in m -> TURN_DAILY_CAP
            status == 402L || code == "QUOTA" || code == "ACCOUNT_QUOTA" || QUOTA_TEXT.containsMatchIn(message) -> TURN_NO_BALANCE
            "dshm_rate_guard" in m || "overloaded" in m || status == 429L || status == 529L || status == 503L || code == "RATE_LIMIT" -> TURN_BUSY
            "dshm_upstream_unreachable" in m || status == 502L || status == 504L || code == "TRANSPORT" || code == "TIMEOUT" ||
                NETWORK_TEXT.containsMatchIn(message) -> TURN_OFFLINE
            else -> message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TURN_ERROR_MAX)?.let { "這一輪出錯了：$it" } ?: "這一輪出錯了"
        }
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

    /**
     * The one-line summary of a tool call. Prose arguments (a subagent's prompt, a message) lose their Markdown marks and line
     * breaks (owner, 2026-11-05: the war room showed 「**可轉債…」); commands, patterns and paths stay exactly as written.
     */
    internal fun summary(name: String, args: JsonObject?, raw: JsonElement?): String {
        val text = summaryRaw(name, args, raw)
        return if (name in LITERAL_TOOLS) text else plainLine(text).take(SUMMARY_MAX)
    }

    private val LITERAL_TOOLS = setOf("bash", "grep", "glob", "read", "write", "edit", "read_image", "web_fetch", "job_output", "job_kill")

    /** Markdown marks and line breaks out of a line of prose: `**bold**`, `` `code` ``, headings, quotes, bullets. */
    internal fun plainLine(s: String): String = s
        .replace(Regex("(\\*\\*|__|~~|`)"), "")
        .lineSequence().map { it.trim().replace(Regex("^(#{1,6}\\s+|>\\s*|[-*+]\\s+)"), "") }.filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun summaryRaw(name: String, args: JsonObject?, raw: JsonElement?): String {
        if (args != null) {
            if (name == "web_search") {
                val queries = (args["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (!queries.isNullOrEmpty()) return queries.joinToString(", ").take(SUMMARY_MAX)
            }
            for (key in SUMMARY_KEYS) {
                args.str(key)?.takeIf { it.isNotBlank() }?.let { return it.take(SUMMARY_MAX) }
            }
            // A list argument (present's `files`, ask's `questions`…): its first item's readable field and how many there are.
            for ((_, v) in args) {
                val list = v as? JsonArray ?: continue
                val first = list.firstOrNull() as? JsonObject ?: continue
                val label = SUMMARY_KEYS.firstNotNullOfOrNull { k -> first.str(k)?.takeIf { it.isNotBlank() } } ?: continue
                return (if (list.size > 1) "$label 等 ${list.size} 項" else label).take(SUMMARY_MAX)
            }
            // Never raw JSON on screen: the first short text value, else nothing.
            return args.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() }?.content }?.take(SUMMARY_MAX) ?: ""
        }
        return ((raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").take(SUMMARY_MAX)
    }

    /** `present {files:[{path, description}]}`: each file, workspace-relative when it is inside the workspace. */
    internal fun presented(name: String, args: JsonObject?): List<PresentedFile> {
        if (name != "present") return emptyList()
        val files = args?.get("files") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return files.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val raw = o.str("path")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val rel = if (raw.startsWith("/")) workspaceRelative(raw) ?: raw else normalized(raw) ?: raw
            PresentedFile(rel, o.str("description")?.takeIf { it.isNotBlank() })
        }
    }

    private fun workspaceRelative(path: String): String? {
        val root = DshLayout.workspacePrefixes().firstOrNull { path.startsWith(it) } ?: return null
        return normalized(path.removePrefix(root))
    }

    private fun normalized(rel: String): String? {
        val stack = ArrayList<String>()
        for (segment in rel.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.lastIndex)
                else -> stack += segment
            }
        }
        return stack.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    internal fun sharePath(name: String, args: JsonObject?): String? {
        if (name != "write" && name != "edit") return null
        val path = args?.str("file_path") ?: return null
        // Either mode's workspace: proot's /root/workspace/ or the host path the native mode uses.
        val root = DshLayout.workspacePrefixes().firstOrNull { path.startsWith(it) } ?: return null
        val stack = ArrayList<String>()
        for (segment in path.removePrefix(root).split('/')) {
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
