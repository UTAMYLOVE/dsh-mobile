package app.agency.core.agent

import app.agency.core.base.Clock
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Effort
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Thoughts
import app.agency.core.llm.Turn
import app.agency.core.llm.Wire
import app.agency.core.store.Message
import app.agency.core.store.Role
import app.agency.core.store.Store
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 小葵's conversation loop (SPEC D "Loop"): a normal tool-using agent with the full context and thinking on, not a router.
 *
 * One owner message:
 * 1. is stored (with a photo / file card per attachment);
 * 2. becomes a request: [system] (persona, handbook, rules, memory), then one user-side context message (this turn's
 *    [ContextSource.block] and the rolling summary), then the recent conversation (NOTE lines as short bracketed notes), then
 *    his message with its photos as image blocks and its files as fenced text;
 * 3. runs up to [MAX_ROUNDS] tool rounds: every call is executed by code, errors and unknown tools go back as error results,
 *    cards are collected;
 * 4. her final text goes through [Guard] (on a problem she is told why and rewrites, at most twice; still wrong → only the
 *    offending sentences are dropped) and, on topics that need it, [Judge] (one rewrite on a veto);
 * 5. her reply is stored with the cards; then [Compactor] may fold old messages into the summary.
 * On a provider failure his message is marked `meta.failed = true` and [SayResult.NotSent] says why in his language.
 * Her words are never replaced by a canned sentence.
 */
class Agent(
    private val llm: LlmClient,
    private val small: LlmClient,
    private val store: Store,
    private val clock: Clock,
    private val tools: () -> List<Tool>,
    private val toolCtx: (photoIds: List<String>) -> ToolCtx,
    private val context: () -> ContextSource,
    private val system: () -> String,
    /** The cleaned bytes of a vault photo, for [resend] (the first try had them in hand). Null = not available. */
    private val loadPhoto: (photoId: String) -> ByteArray? = { null },
    private val effort: Effort = Effort.MEDIUM,
) {
    private val judge = Judge(small)
    private val compactor = Compactor(llm, small, store, system, tools, toolCtx)

    suspend fun reply(text: String, attachments: List<Attachment>): SayResult {
        val photos = attachments.filterIsInstance<Attachment.Photo>()
        val files = attachments.filterIsInstance<Attachment.File>().map { it.name to cap(it.text, FILE_MAX) }
        val cards = photos.map { Card.Photo(it.photoId) } + files.map { (n, t) -> Card.File(n, t.length) }
        val meta = if (files.isEmpty()) JsonObject(emptyMap()) else buildJsonObject {
            put(META_FILES, buildJsonArray {
                files.forEach { (n, t) -> add(buildJsonObject { put("name", n); put("text", t) }) }
            })
        }
        val msg = store.addMessage(clock.now(), Role.OWNER, text, cards, meta)
        return answer(Said(msg, photos.map { it.photoId }, photos.map { it.jpeg }, files))
    }

    /** His message as it is answered: [images] are the cleaned bytes of [photoIds]; [files] are (name, capped text). */
    private class Said(
        val msg: Message,
        val photoIds: List<String>,
        val images: List<ByteArray>,
        val files: List<Pair<String, String>>,
    )

    /** Sends a failed message of his again: same text, same photos (from the vault) and files. */
    suspend fun resend(messageId: Long): SayResult {
        val msg = findMessage(messageId)?.takeIf { it.role == Role.OWNER } ?: return SayResult.NotSent("找不到這則訊息。")
        val photoIds = msg.cards.filterIsInstance<Card.Photo>().map { it.photoId }
        val images = photoIds.map { loadPhoto(it) ?: return SayResult.NotSent("找不到這則訊息的照片，請重新傳一次。") }
        val files = (msg.meta[META_FILES] as? JsonArray).orEmpty().mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            o.str("name").orEmpty() to o.str("text").orEmpty()
        }
        val cleared = msg.copy(meta = JsonObject(msg.meta - META_FAILED))
        store.updateMessage(cleared)
        return answer(Said(cleared, photoIds, images, files))
    }

    private suspend fun answer(said: Said): SayResult {
        val msg = said.msg
        val result = try {
            converse(said)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(Fail.BAD_RESPONSE)
        }
        return when (result) {
            is Outcome.Failed -> {
                store.updateMessage(msg.copy(meta = JsonObject(msg.meta + (META_FAILED to JsonPrimitive(true)))))
                SayResult.NotSent(reason(result.fail))
            }
            is Outcome.Replied -> {
                store.addMessage(clock.now(), Role.MANAGER, result.text, result.cards, result.meta)
                try {
                    compactor.maybeCompact()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Compaction is housekeeping; it never costs him his answer.
                }
                SayResult.Ok
            }
        }
    }

    private sealed interface Outcome {
        data class Replied(val text: String, val cards: List<Card>, val meta: JsonObject) : Outcome
        data class Failed(val fail: Fail) : Outcome
    }

    /** What the tools of this turn produced. */
    private class Collected {
        val cards = ArrayList<Card>()
        val facts = StringBuilder()
    }

    private sealed interface Step {
        data class Text(val text: String, val assistant: Turn.Assistant) : Step
        data class Failed(val fail: Fail) : Step
    }

    private suspend fun converse(said: Said): Outcome {
        val before = context()
        val sys = system()
        val ownerText = (listOf(said.msg.text) + said.files.map { it.second }).joinToString("\n")
        val turns = buildTurns(said, before).toMutableList()
        val offered = tools()
        val ctx = toolCtx(said.photoIds)
        val got = Collected()

        val first = loop(sys, turns, offered, ctx, got, MAX_ROUNDS)
        if (first is Step.Failed) return Outcome.Failed(first.fail)
        var last = (first as Step.Text).assistant
        var text = first.text

        // The context again after the tools ran, so what they changed (a status set, a meal logged) counts for the guard.
        val after = try {
            context()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            before
        }
        val contextFacts = before.facts() + "\n" + after.facts()
        val flags = GuardFlags.of(after)
        // Tool facts grow while she rewrites (she may look something up), so they are read at every check.
        fun facts() = contextFacts + "\n" + got.facts

        suspend fun rewrite(note: String): Step {
            turns += Turn.User(note)
            return loop(sys, turns, offered, ctx, got, REWRITE_ROUNDS)
        }

        // Guard: she rewrites at most twice; then only the offending sentences go.
        var verdict = Guard.check(text, facts(), ownerText, flags)
        var rewrites = 0
        while (verdict is GuardResult.Fix && rewrites < MAX_REWRITES) {
            rewrites++
            val step = rewrite(fixNote(verdict.problems))
            if (step !is Step.Text) break
            text = step.text
            last = step.assistant
            verdict = Guard.check(text, facts(), ownerText, flags)
        }
        if (verdict is GuardResult.Fix) text = Guard.strip(text, verdict.problems)

        // Judge: one rewrite on a veto; a failed judge call never blocks.
        if (text.isNotBlank() && judge.triggered(text)) {
            val v = judge.review(text, ownerText, facts())
            if (v != null && !v.ok) {
                val step = rewrite(judgeNote(v.reason))
                if (step is Step.Text) {
                    val g = Guard.check(step.text, facts(), ownerText, flags)
                    val t = if (g is GuardResult.Fix) Guard.strip(step.text, g.problems) else step.text
                    if (t.isNotBlank()) { text = t; last = step.assistant }
                }
            }
        }

        if (text.isBlank()) text = neutralLine(sys, turns, offered, facts(), ownerText, flags)

        val meta = Thoughts.of(last)?.takeIf { it.first == Wire.OPENAI }?.let { (_, reasoning) ->
            buildJsonObject { put(META_REASONING, reasoning) }
        } ?: JsonObject(emptyMap())
        return Outcome.Replied(text.trim(), got.cards.toList(), meta)
    }

    /**
     * Calls the model until it answers in text, running tool calls in between (at most [rounds] tool rounds). When the rounds
     * are used up, the results of the last calls go back with a note asking for a text answer.
     */
    private suspend fun loop(
        sys: String,
        turns: MutableList<Turn>,
        offered: List<Tool>,
        ctx: ToolCtx,
        got: Collected,
        rounds: Int,
    ): Step {
        var round = 0
        while (true) {
            val request = ChatRequest(
                system = sys, turns = turns.toList(), purpose = Purpose.CHAT, maxTokens = MAX_TOKENS,
                tools = offered.map { it.spec }, thinking = Thinking.LOW, effort = effort,
            )
            val r = llm.chat(request)
            if (r is ChatResult.Failed) return Step.Failed(r.fail)
            r as ChatResult.Ok
            turns += r.assistant
            if (r.toolCalls.isEmpty()) {
                val t = r.text?.trim().orEmpty()
                return if (t.isEmpty()) Step.Failed(Fail.BAD_RESPONSE) else Step.Text(t, r.assistant)
            }
            if (round >= rounds) {
                // Out of rounds and still calling tools: take her words if she wrote any, else answer every call with an
                // error and ask once for text.
                r.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return Step.Text(it, r.assistant) }
                r.toolCalls.forEach { turns += Turn.ToolResult(it.id, TOO_MANY_ROUNDS, isError = true) }
                turns += Turn.User(NO_MORE_TOOLS)
                val again = llm.chat(request.copy(turns = turns.toList()))
                if (again !is ChatResult.Ok) return Step.Failed((again as ChatResult.Failed).fail)
                turns += again.assistant
                val t = again.text?.trim().orEmpty()
                return if (t.isEmpty()) Step.Failed(Fail.BAD_RESPONSE) else Step.Text(t, again.assistant)
            }
            round++
            for (ran in ToolRunner.run(r.toolCalls, offered, ctx)) {
                turns += ran.result
                ran.out?.let { out ->
                    got.cards += out.cards
                    got.facts.append(out.facts.toString()).append('\n')
                }
            }
        }
    }

    /** Everything was removed: one short neutral line of her own, checked like any other; nothing if that fails too. */
    private suspend fun neutralLine(
        sys: String,
        turns: MutableList<Turn>,
        offered: List<Tool>,
        facts: String,
        owner: String,
        flags: GuardFlags,
    ): String {
        turns += Turn.User(NEUTRAL_NOTE)
        // The tools stay declared (the history holds tool calls, which Claude only accepts with tools defined); any call she
        // makes here is not run.
        val request = ChatRequest(sys, turns.toList(), Purpose.CHAT, MAX_TOKENS, offered.map { it.spec }, thinking = Thinking.LOW,
            effort = Effort.LOW)
        val r = try {
            llm.chat(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val t = (r as? ChatResult.Ok)?.text?.trim().orEmpty()
        if (t.isEmpty()) return ""
        return when (val g = Guard.check(t, facts, owner, flags)) {
            GuardResult.Ok -> t
            is GuardResult.Fix -> Guard.strip(t, g.problems)
        }
    }

    private fun buildTurns(said: Said, c: ContextSource): List<Turn> {
        val msg = said.msg
        val out = ArrayList<Turn>()
        out += Turn.User(buildString {
            append("〔現在的情況（程式剛整理的事實）〕\n")
            append(c.block().trim())
            append("\n可以演小劇場：").append(if (c.mayPlayScene) "是" else "否")
            append("\n可以撒嬌：").append(if (c.mayBeSweet) "是" else "否")
            val summary = Memory.summary(store)
            if (summary.isNotBlank()) append("\n\n〔之前的對話摘要〕\n").append(summary)
        })
        val mark = Memory.summarisedThrough(store)
        store.messages(HISTORY, beforeId = msg.id).filter { it.id > mark }.forEach { m ->
            when (m.role) {
                Role.OWNER -> if (m.meta[META_FAILED] != JsonPrimitive(true)) out += Turn.User(historyOwner(m))
                Role.NOTE -> out += Turn.User("〔紀錄〕" + m.text)
                Role.MANAGER -> {
                    val t = m.text.ifBlank { "〔只顯示了卡片〕" }
                    val reasoning = m.meta.str(META_REASONING)
                    out += if (reasoning != null) Thoughts.replay(t, reasoning) else Turn.AssistantText(t)
                }
            }
        }
        out += Turn.User(currentText(msg, said.images.size, said.files), said.images)
        return mergeUsers(out)
    }

    private fun historyOwner(m: Message): String {
        val photos = m.cards.count { it is Card.Photo }
        val files = m.cards.filterIsInstance<Card.File>().map { it.name }
        return buildString {
            append(m.text)
            if (photos > 0) append(" 〔附了 $photos 張照片〕")
            if (files.isNotEmpty()) append(" 〔附了檔案：${files.joinToString("、")}〕")
        }.trim().ifEmpty { "〔空白訊息〕" }
    }

    private fun currentText(msg: Message, photos: Int, files: List<Pair<String, String>>): String {
        var budget = FILES_TOTAL_MAX
        return buildString {
            append(msg.text.ifBlank { if (photos > 0) "〔他只傳了照片〕" else if (files.isNotEmpty()) "〔他只傳了檔案〕" else "〔空白訊息〕" })
            files.forEach { (name, text) ->
                val part = cap(text, budget)
                budget -= part.length
                append("\n\n〔檔案「").append(name).append("」〕\n```\n").append(part).append("\n```")
            }
        }
    }

    /** Consecutive user-side turns become one (their texts joined, their photos kept in order). */
    private fun mergeUsers(turns: List<Turn>): List<Turn> = buildList {
        for (t in turns) {
            val prev = lastOrNull()
            if (t is Turn.User && prev is Turn.User) {
                set(size - 1, Turn.User(prev.text + "\n\n" + t.text, prev.images + t.images))
            } else {
                add(t)
            }
        }
    }

    private fun findMessage(id: Long): Message? =
        store.messages(1, beforeId = id + 1).firstOrNull { it.id == id }

    private fun fixNote(problems: List<Problem>): String = buildString {
        append("〔檢查沒過：上一版回覆有下面的問題。請重寫整段要給他看的回覆，只改有問題的地方，其他照舊；不要提到檢查，也不要道歉。〕")
        problems.forEach { p -> append("\n・「").append(p.sentence).append("」：").append(p.reason) }
    }

    private fun judgeNote(reason: String): String =
        "〔檢查沒過：上一版回覆違反了公司規則：${reason.ifBlank { "請再對照規則檢查一次" }}。請重寫整段要給他看的回覆；不要提到檢查，也不要道歉。〕"

    private fun JsonObject.str(k: String): String? = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val MAX_ROUNDS = 6
        const val REWRITE_ROUNDS = 2
        const val MAX_REWRITES = 2
        const val MAX_TOKENS = 8000
        const val HISTORY = 60
        const val FILE_MAX = 12_000
        const val FILES_TOTAL_MAX = 24_000

        const val META_FAILED = "failed"
        const val META_FILES = "files"
        const val META_REASONING = "reasoning"

        private const val TOO_MANY_ROUNDS = """{"error":"too many tool rounds in one answer"}"""
        private const val NO_MORE_TOOLS = "〔這一輪工具已經用太多次了，請直接用文字回他。〕"
        private const val NEUTRAL_NOTE = "〔前面的回覆都沒通過檢查。只寫一句很短、中性的話接住他：不含數字、不含建議、不提檢查。數字畫面上的卡片會顯示。〕"

        private fun cap(s: String, max: Int): String = Memory.takeCodePoints(s, maxOf(0, max))

        /** Why a message was not sent, in his language. */
        fun reason(fail: Fail): String = when (fail) {
            Fail.OFFLINE -> "現在沒有網路，訊息沒送出。連上之後點一下重送。"
            Fail.NO_KEY -> "還沒有可以用的金鑰，請到設定加上。"
            Fail.KEY_REJECTED -> "金鑰被拒絕了，請到設定檢查金鑰或餘額。"
            Fail.CAP -> "已經到花費上限了，訊息沒送出。可以到設定調整上限。"
            Fail.RATE -> "傳得太頻繁了，等一下再重送。"
            Fail.REFUSED -> "這則訊息被模型拒絕回答了，換個說法再試試。"
            Fail.BAD_RESPONSE -> "模型的回應讀不懂，訊息沒送出。等一下再重送。"
            Fail.SERVER -> "模型那邊暫時有問題，等一下再重送。"
            Fail.NO_SETUP -> "模型還沒設定好，請到設定選一個模型。"
            Fail.BLOCKED_HOST -> "這個連線不在允許的清單裡，訊息沒送出。"
            Fail.NO_VISION -> "現在選的模型看不了照片，請到設定換一個看得懂照片的模型。"
        }
    }
}
