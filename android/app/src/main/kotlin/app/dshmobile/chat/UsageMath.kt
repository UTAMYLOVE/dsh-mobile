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

fun providerOf(name: String?): Provider? = when (name?.lowercase()) {
    "deepseek" -> Provider.DEEPSEEK
    "anthropic", "claude" -> Provider.ANTHROPIC
    else -> null
}

/** The app's own estimate of one reply's cost; null when the provider is unknown. */
fun replyCostUsd(prices: PriceTable, r: ReplyStats, at: Instant): Double? {
    val p = providerOf(r.provider) ?: return null
    return prices.costUsd(p, r.model.orEmpty(), Usage(r.inputTokens, r.outputTokens, r.cacheReadTokens), at)
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
    add("入 ${shortTokens(r.inputTokens + r.cacheReadTokens)}" + (if (r.cacheReadTokens > 0) "（快取 ${shortTokens(r.cacheReadTokens)}）" else ""))
    add("出 ${shortTokens(r.outputTokens)}")
    costUsd?.let { add(shortUsd(it)) }
    r.durationMs?.let { add("共 ${shortDuration(it)}") }
}.joinToString(" · ")

/**
 * dsh's tool error plus what to do in this app (stage 2 §I.2/§I.3): a team or agent limit names the app setting (never dsh's own
 * config files, which the app rewrites); a missing sandbox explains that workflows cannot run inside Android's proot.
 */
fun friendlyToolError(error: String): String {
    val e = error.lowercase()
    return when {
        "limit" in e && ("reached" in e || "exceed" in e) && ("team" in e || "member" in e || "agent" in e || "concurren" in e) ->
            "$error\n→ 上限在 app 的「設定 → 代理人」（並行上限、團隊上限）調整，改完按「套用並重啟」。不要改 dsh 的設定檔，app 會蓋掉。"
        "sandbox" in e || "沙盒" in error || "bubblewrap" in e || "landlock" in e ->
            "$error\n→ 手機上的 dsh 跑在 proot 裡，沒有工作流需要的沙盒，所以 workflow 暫時不能用。請叫它直接做，或派子代理人分工。"
        else -> error
    }
}
