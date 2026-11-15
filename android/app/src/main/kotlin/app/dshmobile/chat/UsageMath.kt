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
fun teamUsage(tree: SessionTreeState, rootId: String): SessionUsage {
    val children = tree.summaries.values.groupBy { it.parentId }
    var sum = SessionUsage()
    val seen = HashSet<String>()
    val queue = ArrayDeque(listOf(rootId))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        sum += SessionUsage.of(tree.projections[id])
        children[id]?.forEach { queue.add(it.id) }
    }
    return sum
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

/** The app's own estimate of one reply's cost; null when the provider is unknown. */
fun replyCostUsd(prices: PriceTable, r: ReplyStats, at: Instant): Double? {
    val p = providerOf(r.provider) ?: return null
    return prices.costUsd(p, r.model.orEmpty(), Usage(r.inputTokens, r.outputTokens, r.cacheReadTokens, r.cacheWriteTokens), at)
}

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
 * advice. Only dsh's team-member limit names the app setting (never dsh's own config files, which the app rewrites).
 */
fun friendlyToolError(error: String): String {
    val raw = error.trim().let { if (it.startsWith("error:", ignoreCase = true)) it.substring("error:".length).trimStart() else it }
    val e = raw.lowercase()
    val lead = when {
        "active child limit" in e || "wait for an existing child" in e -> return SUBAGENTS_BUSY
        TEAM_MEMBER_LIMIT.containsMatchIn(raw) ->
            "上限在 app 的「設定 → 代理人」（並行上限、團隊上限）調整，改完按「套用並重啟」。不要改 dsh 的設定檔，app 會蓋掉。"
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

/** The subagent-limit line: nothing for the owner to do, so the card draws it grey, not red. */
const val SUBAGENTS_BUSY = "子代理人都在忙（同時最多的數量已滿），dsh 會等前面的做完再派，不用處理。"

/** dsh-experimental-agent-team's `Team member limit N reached` (TEAM_MEMBER_LIMIT), the one limit a setting changes. */
private val TEAM_MEMBER_LIMIT = Regex("""team member limit\b.*\breached|TEAM_MEMBER_LIMIT""", RegexOption.IGNORE_CASE)
