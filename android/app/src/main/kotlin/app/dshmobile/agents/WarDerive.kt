package app.dshmobile.agents

import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ToolStatus

/** One row of the decision stack: what the auto review (or the owner) decided about one tool call. */
data class Decision(val kind: Kind, val text: String, val why: String, val risk: Float) {
    enum class Kind(val label: String) { BLOCKED("擋下"), WAITING("等你"), ALLOWED("放行"), FAILED("錯誤") }
}

/** How many of each action the lead's tools took (the six-way tree). */
data class ActionCounts(val search: Int, val read: Int, val dispatch: Int, val write: Int, val blocked: Int, val review: Int)

/** Share of agents working / waiting / failed, 0..1 each; null when no agent is counted. */
data class Temperature(val running: Float, val waiting: Float, val error: Float)

/** One line of the speed chart: an agent and its tok/s per sample (null where it had no value). */
data class SpeedLine(val id: String, val label: String, val values: List<Float?>)

/** Pure derivations for the pink war room (owner-approved A+B+C layout); every input is what the screen already has. */
object WarDerive {
    private val DENIED = listOf("denied", "deny", "rejected", "blocked", "not allowed", "拒", "擋")

    fun isBlocked(t: ChatItem.Tool): Boolean =
        t.status == ToolStatus.FAILED && t.error?.lowercase()?.let { e -> DENIED.any { it in e } } == true

    /** The newest [max] tool calls, newest first, as decisions with a rough risk score for the bar. */
    fun decisions(tools: List<ChatItem.Tool>, max: Int = 7): List<Decision> = tools.asReversed().take(max).map { t ->
        val (stage, prop, line) = stageOf(t.name, t.summary)
        val base = when {
            t.name.lowercase() in setOf("bash", "shell", "exec", "python") -> .66f
            stage == Stage.WRITE -> .12f + if (prop == Prop.PEN) 0f else .2f
            stage == Stage.DISPATCH -> .2f
            else -> .04f
        }
        when {
            t.status == ToolStatus.WAITING_APPROVAL -> Decision(Decision.Kind.WAITING, line, "要你允許才會做", base.coerceAtLeast(.5f))
            isBlocked(t) -> Decision(Decision.Kind.BLOCKED, line, t.error?.lineSequence()?.firstOrNull()?.take(50) ?: "自動審查拒絕", .96f)
            t.status == ToolStatus.FAILED -> Decision(Decision.Kind.FAILED, line, t.error?.lineSequence()?.firstOrNull()?.take(50) ?: "失敗", .8f)
            else -> Decision(Decision.Kind.ALLOWED, line, if (base < .1f) "唯讀 · 自動放行" else "工作區內 · 放行", base)
        }
    }

    fun counts(tools: List<ChatItem.Tool>, agents: List<AgentStatus>): ActionCounts {
        var search = 0; var read = 0; var write = 0; var review = 0; var blocked = 0; var dispatch = 0
        for (t in tools) {
            if (isBlocked(t)) { blocked++; continue }
            val (stage, prop, _) = stageOf(t.name, t.summary)
            when {
                stage == Stage.SEARCH && prop == Prop.PAGE -> read++
                stage == Stage.SEARCH -> search++
                stage == Stage.DISPATCH -> dispatch++
                stage == Stage.REVIEW -> review++
                stage == Stage.WRITE -> write++
            }
        }
        return ActionCounts(search, read, maxOf(dispatch, agents.count { !it.isLead }), write, blocked, review)
    }

    fun temperature(agents: List<AgentStatus>): Temperature? {
        val counted = agents.filter { it.mood != Mood.IDLE }
        if (counted.isEmpty()) return null
        val n = counted.size.toFloat()
        return Temperature(
            counted.count { it.mood == Mood.RUNNING || it.mood == Mood.DONE } / n,
            counted.count { it.mood == Mood.WAITING } / n,
            counted.count { it.mood == Mood.ERROR } / n,
        )
    }

    /** Agents by tokens used, most first. */
    fun ranking(agents: List<AgentStatus>, max: Int = 7): List<Pair<AgentStatus, Long>> =
        agents.map { it to it.usage.totalTokens }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max)

    /** Up to [max] agents with the highest latest speed, each with its values over [samples]. */
    fun speedLines(samples: List<WarSample>, agents: List<AgentStatus>, max: Int = 3): List<SpeedLine> {
        if (samples.isEmpty()) return emptyList()
        val names = agents.associate { it.id to it.label }
        val ids = samples.flatMap { it.perAgent.keys }.toSet()
        val latest = ids.associateWith { id -> samples.lastOrNull { id in it.perAgent }?.perAgent?.get(id) ?: 0f }
        return latest.entries.sortedByDescending { it.value }.take(max).map { (id, _) ->
            SpeedLine(id, names[id] ?: "代理人", samples.map { it.perAgent[id] })
        }
    }

    /** The change of the average speed against [back] samples ago, in %; null without enough samples or a zero start. */
    fun speedChange(samples: List<WarSample>, back: Int = 10): Float? {
        if (samples.size < 2) return null
        val then = samples[(samples.size - 1 - back).coerceAtLeast(0)].tokensPerSecond
        val now = samples.last().tokensPerSecond
        return if (then <= 0f) null else (now - then) / then * 100f
    }
}
