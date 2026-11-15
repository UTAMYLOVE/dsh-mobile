package app.dshmobile.chat

import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.gateway.Usage
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One session's (or a whole team's) usage from dsh's `tokenUsage` and `sessionStats` projections (docs/dsh-ui-protocol.md §6).
 * dsh's lead `tokenUsage` excludes its children, so [teamUsage] sums them.
 */
data class SessionUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val turns: Long = 0,
    val steps: Long = 0,
    val ttftMs: Long = 0,
    val ttftSteps: Long = 0,
    val decodeMs: Long = 0,
    val decodeTokens: Long = 0,
    val llmMs: Long = 0,
    val toolMs: Long = 0,
) {
    operator fun plus(o: SessionUsage) = SessionUsage(
        inputTokens + o.inputTokens, outputTokens + o.outputTokens, cacheReadTokens + o.cacheReadTokens,
        cacheWriteTokens + o.cacheWriteTokens, turns + o.turns, steps + o.steps, ttftMs + o.ttftMs, ttftSteps + o.ttftSteps,
        decodeMs + o.decodeMs, decodeTokens + o.decodeTokens, llmMs + o.llmMs, toolMs + o.toolMs,
    )

    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens

    /** Share of prompt tokens served from the cache, 0..1; null before any prompt. */
    val cacheHit: Double? get() = (inputTokens + cacheReadTokens + cacheWriteTokens).takeIf { it > 0 }?.let { cacheReadTokens.toDouble() / it }

    val tokensPerSecond: Double? get() = if (decodeMs > 0 && decodeTokens > 0) decodeTokens * 1000.0 / decodeMs else null

    val averageTtftMs: Long? get() = if (ttftSteps > 0) ttftMs / ttftSteps else null

    val isEmpty: Boolean get() = totalTokens == 0L && turns == 0L

    companion object {
        fun of(projections: Map<String, JsonElement>?): SessionUsage {
            if (projections == null) return SessionUsage()
            val t = projections["tokenUsage"] as? JsonObject
            val s = projections["sessionStats"] as? JsonObject
            return SessionUsage(
                inputTokens = t.n("uncachedInputTokens"),
                outputTokens = t.n("outputTokens"),
                cacheReadTokens = t.n("cacheReadTokens"),
                cacheWriteTokens = t.n("cacheWriteTokens"),
                turns = s.n("turns"),
                steps = s.n("steps"),
                ttftMs = s.n("ttftMs"),
                ttftSteps = s.n("ttftSteps"),
                decodeMs = s.n("decodeMs"),
                decodeTokens = s.n("decodeTokens"),
                llmMs = s.n("llmMs"),
                toolMs = s.n("toolMs"),
            )
        }

        private fun JsonObject?.n(key: String): Long =
            (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0
    }
}

/** [rootId] and every session below it (subagents, their subagents, teammates), summed. */
fun teamUsage(tree: SessionTreeState, rootId: String): SessionUsage =
    teamIds(tree, rootId).fold(SessionUsage()) { sum, id -> sum + SessionUsage.of(tree.projections[id]) }

/** [rootId] first, then every session below it, each once (a cycle in a broken tree cannot loop). */
private fun teamIds(tree: SessionTreeState, rootId: String): List<String> {
    val children = tree.summaries.values.groupBy { it.parentId }
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    val queue = ArrayDeque(listOf(rootId))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        out += id
        children[id]?.forEach { queue.add(it.id) }
    }
    return out
}

/** Every session dsh has in memory, summed (the overview's live numbers). */
fun allUsage(tree: SessionTreeState): SessionUsage =
    (tree.summaries.keys + tree.projections.keys).fold(SessionUsage()) { a, id -> a + SessionUsage.of(tree.projections[id]) }

/** dsh names the route's provider (`deepseek-official`, `deepseek`, `anthropic`); the price follows the company. */
fun providerOf(name: String?): Provider? {
    val n = name?.lowercase() ?: return null
    return when {
        n.startsWith("deepseek") -> Provider.DEEPSEEK
        n.startsWith("anthropic") || n.startsWith("claude") -> Provider.ANTHROPIC
        else -> null
    }
}

/**
 * The app's own estimate of one reply's cost; null when the provider is unknown. DeepSeek's peak price follows when the reply
 * ended ([ReplyStats.endedAtMs]), so an off-peak reply does not double when looked at during peak hours; [now] is only for a reply
 * without that time. Haiku 5.5's tier follows the turn's largest single request, not the steps summed.
 */
fun replyCostUsd(prices: PriceTable, r: ReplyStats, now: Instant): Double? {
    val p = providerOf(r.provider) ?: return null
    val at = r.endedAtMs?.let(Instant::ofEpochMilli) ?: now
    val u = Usage(r.inputTokens, r.outputTokens, r.cacheReadTokens, r.cacheWriteTokens)
    return prices.costUsd(p, r.model.orEmpty(), u, at, maxPromptTokens = r.maxPrompt.takeIf { it > 0 }, requests = r.steps.toLong())
}

/**
 * 本對話花費: the conversation [rootId] and every helper under it (subagents, teammates). Before, only the lead's loaded replies
 * counted, so with helpers doing the work the number was a small fraction of the spend while 本對話 tok counted the whole team.
 * Each helper is priced at the model it used; the lead as [ownCostUsd]. Still an estimate: Auto review's own requests are not in
 * dsh's session counts and only show in today's total (the gateway meters every request). Null when nothing could be priced.
 */
fun teamCostUsd(tree: SessionTreeState, rootId: String, prices: PriceTable, loaded: Collection<ReplyStats>, now: Instant): Double? =
    teamIds(tree, rootId).fold(null as Double?) { sum, id ->
        val cost = if (id == rootId) ownCostUsd(tree, id, prices, loaded, now) else sessionCostUsd(tree, id, prices, now)
        if (cost == null) sum else (sum ?: 0.0) + cost
    }

/**
 * One conversation's own cost, without its helpers: each [loaded] reply at its own model and end time, plus what dsh's
 * `tokenUsage` counted beyond those replies (pages not loaded, and failed attempts that were retried, which are billed too) at
 * the model it last used. So a conversation that switched models is not priced wholly at the last one. Without dsh's
 * `tokenUsage` it is the loaded replies alone.
 */
fun ownCostUsd(tree: SessionTreeState, id: String, prices: PriceTable, loaded: Collection<ReplyStats>, now: Instant): Double? {
    val replies = loaded.mapNotNull { replyCostUsd(prices, it, now) }.takeIf { it.isNotEmpty() }?.sum()
    val projections = tree.projections[id]
    if (projections?.get("tokenUsage") == null) return replies
    val own = SessionUsage.of(projections)
    // dsh's buckets and the replies' are the same disjoint counts, so the difference is what the loaded replies do not cover.
    val rest = Usage(
        inputTokens = (own.inputTokens - loaded.sumOf { it.inputTokens }).coerceAtLeast(0),
        outputTokens = (own.outputTokens - loaded.sumOf { it.outputTokens }).coerceAtLeast(0),
        cacheReadTokens = (own.cacheReadTokens - loaded.sumOf { it.cacheReadTokens }).coerceAtLeast(0),
        cacheWriteTokens = (own.cacheWriteTokens - loaded.sumOf { it.cacheWriteTokens }).coerceAtLeast(0),
    )
    val restCost = if (rest == Usage(0, 0)) null else usedModel(tree, id)?.let { (provider, model) ->
        prices.costUsd(provider, model, rest, now, requests = (own.steps - loaded.sumOf { it.steps.toLong() }).coerceAtLeast(1))
    }
    return if (replies == null && restCost == null) null else (replies ?: 0.0) + (restCost ?: 0.0)
}

/** A helper's cost: all of dsh's counted tokens at the model it used; null with no tokens or no known model. */
private fun sessionCostUsd(tree: SessionTreeState, id: String, prices: PriceTable, now: Instant): Double? {
    val u = SessionUsage.of(tree.projections[id])
    if (u.totalTokens == 0L) return null
    val (provider, model) = usedModel(tree, id) ?: return null
    return prices.costUsd(provider, model, Usage(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens), now, requests = u.steps)
}

/** The provider and model a session's requests went to: dsh's `modelSelection.lastUsed`, else its next pick, else the list row's. */
private fun usedModel(tree: SessionTreeState, id: String): Pair<Provider, String>? {
    val selection = tree.projections[id]?.get("modelSelection") as? JsonObject
    val used = (selection?.get("lastUsed") as? JsonObject) ?: (selection?.get("next") as? JsonObject)
    val model = used?.text("model") ?: tree.summaries[id]?.model ?: return null
    val provider = providerOf(used?.text("provider")) ?: if (model.startsWith("claude")) Provider.ANTHROPIC else Provider.DEEPSEEK
    return provider to model
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/** Short token count: 950, 12.3K, 1.2M. */
fun shortTokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0).replace(".0K", "K")
    else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
}

/** Seconds with one decimal under a minute (0.8 秒, 12.4 秒), else minutes and seconds (2 分 05 秒). */
fun shortDuration(ms: Long): String = if (ms < 60_000) String.format(Locale.US, "%.1f 秒", ms / 1000.0)
else String.format(Locale.US, "%d 分 %02d 秒", ms / 60_000, (ms / 1000) % 60)

/** US$ with enough digits to show small amounts: US$0.0042, US$0.13, US$2.40. */
fun shortUsd(v: Double): String = when {
    v < 0.01 -> String.format(Locale.US, "US$%.4f", v)
    v < 1 -> String.format(Locale.US, "US$%.3f", v)
    else -> String.format(Locale.US, "US$%.2f", v)
}

/** The per-reply line under an assistant bubble. */
fun replyLine(r: ReplyStats, costUsd: Double?): String = buildList {
    r.tokensPerSecond?.let { add(String.format(Locale.US, "%.0f tok/s", it)) }
    r.ttftMs?.let { add("首字 ${shortDuration(it)}") }
    add("入 ${shortTokens(r.inputTokens + r.cacheReadTokens + r.cacheWriteTokens)}" + (if (r.cacheReadTokens > 0) "（快取 ${shortTokens(r.cacheReadTokens)}）" else ""))
    add("出 ${shortTokens(r.outputTokens)}")
    costUsd?.let { add(shortUsd(it)) }
    r.durationMs?.let { add("共 ${shortDuration(it)}") }
}.joinToString(" · ")

/**
 * What a failed tool card says: one plain Traditional-Chinese line first when dsh's English is one of its fixed phrases, then
 * dsh's own words after a line break (the card draws them small and grey). The owner saw only red English 「Error: Auto review of
 * tool "bash" failed…」 and could not tell what happened. Nothing here promises a retry: whether the model tries again is its call.
 *
 * dsh's subagent limit is ordinary flow control (the model waits for a running child), so it gets one calm sentence and no
 * advice. Only dsh's team-member limit names the app setting. The app's own guard's refusals lead with what happened, in
 * Chinese, instead of the English it wrote for the model.
 */
fun friendlyToolError(error: String): String {
    val raw = error.trim().let { if (it.startsWith("error:", ignoreCase = true)) it.substring("error:".length).trimStart() else it }
    guardRefusal(raw)?.let { return it }
    val e = raw.lowercase()
    val lead = when {
        "active child limit" in e || "wait for an existing child" in e -> return SUBAGENTS_BUSY
        // 代理人 is only on the page with 進階設定 on (off by default): the path says so, or the owner finds nothing. The names are
        // the page's own labels (團隊人數 is capped at 同時數 there).
        TEAM_MEMBER_LIMIT.containsMatchIn(raw) -> TEAM_FULL
        "sandbox" in e || "沙盒" in raw || "bubblewrap" in e || "landlock" in e ->
            "手機上的 dsh 跑在 proot 裡，沒有工作流需要的沙盒，所以 workflow 暫時不能用。請叫它直接做，或派子代理人分工。"
        "auto review of tool" in e && "failed" in e -> "自動審查本身出錯，這個指令沒有執行。"
        "auto review rejected tool" in e || "auto review denied tool" in e -> "自動審查擋下了這個指令，沒有執行。"
        "tool call aborted" in e -> "已中止，沒有執行。"
        "interrupted after it was recorded" in e -> "上次被中斷，不確定有沒有做完。"
        "enoent" in e || "no such file" in e || "file not found" in e -> "找不到檔案。"
        else -> null
    }
    return if (lead == null) raw else "$lead\n$raw"
}

/**
 * The app's own guard (plugins/packages/guard) refusing a call: its reason starts with English meant for the model, and the
 * owner's Chinese text comes last. The card leads with what happened and shows the guard's own Chinese text under it.
 */
private fun guardRefusal(raw: String): String? {
    GUARD_REFUSED.matchEntire(raw)?.let { return "這一步一律不允許，沒有執行。\n" + it.groupValues[1].trim() }
    GUARD_CANNOT_ASK.matchEntire(raw)?.let { return "這個對話目前不能詢問你，這一步沒有執行。\n" + it.groupValues[1].trim() }
    GUARD_SUBAGENT.matchEntire(raw)?.let { return "子代理人不能詢問你，這一步沒有執行。\n" + it.groupValues[1].trim() }
    return null
}

private val GUARD_REFUSED = Regex("""DSH Mobile refused this call \([^)]*\): (.*)""", RegexOption.DOT_MATCHES_ALL)
private val GUARD_CANNOT_ASK = Regex("""DSH Mobile \([^)]*\): 這個對話目前不能詢問你，這一步沒有執行。.*?tell the owner what was not done and why\. (.*)""", RegexOption.DOT_MATCHES_ALL)
private val GUARD_SUBAGENT = Regex("""DSH Mobile: this needs the owner's approval \([^)]*\) and a subagent cannot ask\..*?which can ask\. (.*)""", RegexOption.DOT_MATCHES_ALL)

/**
 * The team-member limit: the existing teammates keep working (dsh never removes one), so this is information, drawn grey. It
 * names the settings by their labels on the page.
 */
const val TEAM_FULL = "團隊人數已滿。要更多成員：在設定打開「進階設定」，到「代理人」調高「團隊人數」（不超過「同時數」），再按「套用並重啟」。"

/** The subagent-limit line: nothing for the owner to do, so the card draws it grey, not red. */
const val SUBAGENTS_BUSY = "子代理人都在忙（同時最多的數量已滿），dsh 會等前面的做完再派，不用處理。"

/** dsh-experimental-agent-team's `Team member limit N reached` (TEAM_MEMBER_LIMIT), the one limit a setting changes. */
private val TEAM_MEMBER_LIMIT = Regex("""team member limit\b.*\breached|TEAM_MEMBER_LIMIT""", RegexOption.IGNORE_CASE)
