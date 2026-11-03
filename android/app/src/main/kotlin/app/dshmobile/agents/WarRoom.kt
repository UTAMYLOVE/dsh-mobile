package app.dshmobile.agents

import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ConversationState
import app.dshmobile.chat.ReviewRounds
import app.dshmobile.chat.SessionTreeState
import app.dshmobile.chat.SessionUsage
import app.dshmobile.chat.ToolStatus
import app.dshmobile.chat.teamUsage
import app.dshmobile.remote.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A spirit's face and colour (stage 2 §H.2): aqua running, pink waiting, milk tea done, rose-brown error, sleepy idle. */
enum class Mood { RUNNING, WAITING, DONE, ERROR, IDLE }

/** The LIVE FLOW stages (§H.1): 規劃 → 搜尋/讀取 → 派出 → 撰寫 → 審查 → 完成. */
enum class Stage(val label: String, val en: String) {
    PLAN("規劃", "PLAN"), SEARCH("搜尋", "SCAN"), DISPATCH("派出", "SPAWN"), WRITE("撰寫", "WRITE"), REVIEW("審查", "CHECK"), DONE("完成", "DONE"),
}

/** The tiny prop a spirit carries for its tool kind. */
enum class Prop { NONE, LENS, PAGE, PEN, SPLIT }

/** Everything the war room shows about one agent (the detailed status of §G). */
data class AgentStatus(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val label: String,
    val mood: Mood,
    val stage: Stage,
    val prop: Prop,
    /** 「搜尋「台北天氣」」, 「等你允許：bash」, 「思考中」…; null when there is nothing to say. */
    val action: String?,
    /** Why it waits (approval, a question, messages queued), or null. */
    val waiting: String?,
    val model: String?,
    val effort: String?,
    val usage: SessionUsage,
    val contextFill: Float?,
    /** Active time so far (dsh's `subagentTiming`, or the lead's turn). */
    val activeMs: Long?,
    val queued: Int,
    val lastLine: String?,
    val error: String?,
    /** The newest actions first (tool calls of the lead; dispatches and finishes of children). */
    val recent: List<String>,
    val isLead: Boolean,
    val finishedAtMs: Long? = null,
)

/** One row of the `tail -f` style log. */
data class WarLogRow(val atMs: Long, val badge: String, val text: String)

/** Stage, prop and the 「搜尋「…」」 line for a tool name and its summary. */
internal fun stageOf(tool: String, summary: String?): Triple<Stage, Prop, String> {
    val n = tool.lowercase()
    val s = summary?.takeIf { it.isNotBlank() }?.let { "「${it.take(40)}」" } ?: ""
    return when {
        "search" in n || "fetch" in n || n == "grep" || n == "glob" -> Triple(Stage.SEARCH, Prop.LENS, "搜尋$s")
        n == "read" || n == "ls" || "list" in n || "view" in n -> Triple(Stage.SEARCH, Prop.PAGE, "讀取$s")
        "subagent" in n || "spawn" in n || "teammate" in n || n == "task" || "agent" in n || "workflow" in n -> Triple(Stage.DISPATCH, Prop.SPLIT, "派出$s")
        "review" in n -> Triple(Stage.REVIEW, Prop.LENS, "審查$s")
        "question" in n || n == "ask" -> Triple(Stage.PLAN, Prop.NONE, "詢問$s")
        else -> Triple(Stage.WRITE, Prop.PEN, "執行 $tool$s")
    }
}

object WarRoomBuilder {
    /**
     * The lead [leadId] (its live chat state [lead] when open) and every session below it, plus [history] (finished children dsh
     * already forgot). Children only have dsh's projections: running or not, timing, tokens, queued messages, last line.
     */
    fun build(
        tree: SessionTreeState,
        leadId: String?,
        lead: ConversationState?,
        history: List<AgentStatus>,
        nowMs: Long,
        leadStartMs: Long?,
    ): List<AgentStatus> {
        if (leadId == null) return history
        val kids = tree.summaries.values.groupBy { it.parentId }
        val out = ArrayList<AgentStatus>()
        out += leadStatus(tree, leadId, lead, nowMs, leadStartMs)
        val queue = ArrayDeque(kids[leadId].orEmpty().map { it to 1 })
        val seen = hashSetOf(leadId)
        while (queue.isNotEmpty()) {
            val (m, depth) = queue.removeFirst()
            if (!seen.add(m.id)) continue
            out += childStatus(tree, m, depth)
            kids[m.id]?.forEach { queue.add(it to depth + 1) }
        }
        val live = out.mapTo(HashSet()) { it.id }
        return out + history.filter { it.id !in live }
    }

    /** Every child session dsh knows, for the recorder (history and log), whoever its lead is. */
    fun children(tree: SessionTreeState): List<AgentStatus> {
        val depth = HashMap<String, Int>()
        fun depthOf(id: String, guard: Int = 0): Int = depth.getOrPut(id) {
            val parent = tree.summaries[id]?.parentId
            if (parent == null || guard > 16) 0 else depthOf(parent, guard + 1) + 1
        }
        return tree.summaries.values.filter { it.parentId != null }.map { childStatus(tree, it, depthOf(it.id)) }
    }

    /** The lead to show: [open] when it is a top-level conversation, else the newest running one, else the newest. */
    fun pickLead(tree: SessionTreeState, open: String?): String? {
        val tops = tree.summaries.values.filter { it.parentId == null && it.id !in tree.archived }
        if (open != null && tops.any { it.id == open }) return open
        return (tops.filter { it.running == true }.maxByOrNull { it.updatedAt ?: 0 } ?: tops.maxByOrNull { it.updatedAt ?: 0 })?.id
    }

    private fun leadStatus(tree: SessionTreeState, id: String, c: ConversationState?, nowMs: Long, startMs: Long?): AgentStatus {
        val m = tree.summaries[id]
        val p = tree.projections[id].orEmpty()
        val running = c?.running ?: m?.running ?: false
        val tools = c?.items.orEmpty().filterIsInstance<ChatItem.Tool>()
        val active = tools.lastOrNull { it.status == ToolStatus.RUNNING || it.status == ToolStatus.WAITING_APPROVAL }
        val failed = c?.items?.lastOrNull()?.let { (it as? ChatItem.Tool)?.takeIf { t -> t.status == ToolStatus.FAILED } }
        val notice = (c?.items?.lastOrNull() as? ChatItem.Notice)?.takeIf { it.kind == app.dshmobile.chat.NoticeKind.ERROR }
        val stage: Stage
        var prop = Prop.NONE
        val action: String?
        var waiting: String? = null
        var mood: Mood
        when {
            active?.status == ToolStatus.WAITING_APPROVAL -> {
                val (st, pr, _) = stageOf(active.name, active.summary)
                stage = st; prop = pr; mood = Mood.WAITING
                waiting = "等你允許：${active.name}"; action = waiting
            }
            active != null -> {
                val (st, pr, line) = stageOf(active.name, active.summary)
                stage = st; prop = pr; mood = Mood.RUNNING; action = line
            }
            running && c?.live?.text?.isNotEmpty() == true -> { stage = Stage.WRITE; prop = Prop.PEN; mood = Mood.RUNNING; action = "撰寫回覆" }
            running && c?.live?.reasoning?.isNotEmpty() == true -> { stage = Stage.PLAN; mood = Mood.RUNNING; action = "思考中" }
            running -> { stage = Stage.PLAN; mood = Mood.RUNNING; action = "準備中" }
            notice != null || failed != null -> { stage = Stage.DONE; mood = Mood.ERROR; action = "出錯了" }
            c?.items.isNullOrEmpty() && m?.lastLine == null -> { stage = Stage.PLAN; mood = Mood.IDLE; action = "閒置" }
            else -> { stage = Stage.DONE; mood = Mood.DONE; action = "完成" }
        }
        val inbox = queued(p["inbox"])
        if (inbox > 0 && waiting == null && mood != Mood.RUNNING) { waiting = "有 $inbox 則訊息排隊"; mood = Mood.WAITING }
        val lastAssistant = c?.items?.lastOrNull { it is ChatItem.Assistant && it.text.isNotBlank() } as? ChatItem.Assistant
        val lastLine = c?.live?.text?.takeIf { it.isNotBlank() }?.lastLine() ?: lastAssistant?.text?.lastLine() ?: m?.lastLine?.lastLine()
        val recent = tools.asReversed().take(RECENT).map { t ->
            val (_, _, line) = stageOf(t.name, t.summary)
            line + when (t.status) {
                ToolStatus.DONE -> " ✓"
                ToolStatus.FAILED -> " ✗"
                ToolStatus.WAITING_APPROVAL -> " ⏸"
                ToolStatus.RUNNING -> " …"
            }
        }
        val (model, effort) = selection(p["modelSelection"], m)
        return AgentStatus(
            id = id, parentId = null, depth = 0,
            label = c?.title?.takeIf { it.isNotBlank() } ?: m?.title?.takeIf { it.isNotBlank() } ?: "主代理人",
            mood = mood, stage = stage, prop = prop, action = action, waiting = waiting,
            model = model, effort = effort, usage = teamUsage(tree, id),
            contextFill = c?.context?.fraction ?: fill(p["contextPressure"]),
            activeMs = if (running && startMs != null) (nowMs - startMs).coerceAtLeast(0) else null,
            queued = inbox, lastLine = lastLine,
            error = notice?.text ?: failed?.error,
            recent = recent, isLead = true,
        )
    }

    private fun childStatus(tree: SessionTreeState, m: SessionSummary, depth: Int): AgentStatus {
        val p = tree.projections[m.id].orEmpty()
        val running = m.running ?: false
        val inbox = queued(p["inbox"])
        val label = (p["subagent"] as? JsonObject)?.str("label")?.takeIf { it.isNotBlank() } ?: m.label?.takeIf { it.isNotBlank() } ?: AgentRows.DEFAULT_LABEL
        val outline = (p["turnOutline"] as? JsonArray)?.lastOrNull() as? JsonObject
        val response = outline?.str("response")?.takeIf { it.isNotBlank() }
        val (stage, prop, action) = when {
            running && response == null -> Triple(Stage.PLAN, Prop.NONE, "思考中")
            running -> Triple(Stage.WRITE, Prop.PEN, "撰寫中")
            inbox > 0 -> Triple(Stage.PLAN, Prop.NONE, "等待訊息")
            else -> Triple(Stage.DONE, Prop.NONE, "完成")
        }
        val mood = when {
            running -> Mood.RUNNING
            inbox > 0 -> Mood.WAITING
            else -> Mood.DONE
        }
        val timing = p["subagentTiming"] as? JsonObject
        val activeObj = timing?.get("active") as? JsonObject
        val activeMs = activeObj?.let { a -> a.num("since")?.let { s -> a.num("through")?.minus(s) } } ?: timing?.num("settledMs")
        val (model, effort) = selection(p["modelSelection"], m)
        return AgentStatus(
            id = m.id, parentId = m.parentId, depth = depth, label = label, mood = mood, stage = stage, prop = prop,
            action = action, waiting = if (inbox > 0 && !running) "有 $inbox 則訊息排隊" else null,
            model = model, effort = effort, usage = SessionUsage.of(p), contextFill = fill(p["contextPressure"]),
            activeMs = activeMs?.coerceAtLeast(0), queued = inbox,
            lastLine = (response ?: outline?.str("prompt") ?: m.lastLine)?.lastLine(),
            error = null, recent = emptyList(), isLead = false,
        )
    }

    private fun selection(e: JsonElement?, m: SessionSummary?): Pair<String?, String?> {
        val last = ((e as? JsonObject)?.get("lastUsed") as? JsonObject) ?: ((e as? JsonObject)?.get("next") as? JsonObject)
        return (last?.str("model") ?: m?.model) to last?.str("reasoningEffort")
    }

    private fun fill(e: JsonElement?): Float? = app.dshmobile.chat.ConversationReducer.pressure(e as? JsonObject)?.fraction

    private fun queued(e: JsonElement?): Int = (e as? JsonObject)?.values?.sumOf { (it as? JsonArray)?.size ?: 0 } ?: 0

    private fun String.lastLine(): String? = ReviewRounds.strip(this).lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(120)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private const val RECENT = 8
}

/**
 * The war room's memory: finished children (dsh removes them, §F: the last [MAX_HISTORY] stay as 「完成」) and the live log of
 * dispatches, finishes and the lead's tool calls with the time they were seen. Fed from the tree and the open conversation.
 */
class WarRoomRecorder(private val clock: () -> Long = System::currentTimeMillis) {
    private val _history = MutableStateFlow<List<AgentStatus>>(emptyList())
    val history: StateFlow<List<AgentStatus>> = _history.asStateFlow()
    private val _log = MutableStateFlow<List<WarLogRow>>(emptyList())
    val log: StateFlow<List<WarLogRow>> = _log.asStateFlow()

    private var lastKids: Map<String, AgentStatus> = emptyMap()
    private val seenTools = HashMap<String, ToolStatus>()
    private val primedConversations = HashSet<String>()
    private var primed = false

    /** Compares [agents] (as built without history) with the previous call: new children, finished ones, vanished ones. */
    @Synchronized fun observeAgents(agents: List<AgentStatus>) {
        val now = clock()
        val kids = agents.filter { !it.isLead && it.finishedAtMs == null }.associateBy { it.id }
        if (primed) {
            for ((id, a) in kids) {
                val before = lastKids[id]
                if (before == null) add(now, "派出", a.label)
                else if (before.mood == Mood.RUNNING && a.mood != Mood.RUNNING) add(now, "完成", a.label)
            }
        }
        for ((id, before) in lastKids) {
            if (id !in kids) {
                if (primed && before.mood == Mood.RUNNING) add(now, "完成", before.label)
                _history.update { h -> (listOf(before.copy(mood = Mood.DONE, stage = Stage.DONE, action = "完成", finishedAtMs = now)) + h.filter { it.id != id }).take(MAX_HISTORY) }
            }
        }
        lastKids = kids
        primed = true
    }

    /** Logs each tool call of the open conversation once per status it reaches. */
    @Synchronized fun observeConversation(c: ConversationState) {
        val now = clock()
        val fresh = primedConversations.add(c.sessionId)
        for (t in c.items.filterIsInstance<ChatItem.Tool>()) {
            val key = c.sessionId + "/" + t.key
            if (seenTools[key] == t.status) continue
            seenTools[key] = t.status
            if (fresh) continue // history already on screen is not news
            val (_, _, line) = stageOf(t.name, t.summary)
            val badge = when (t.status) {
                ToolStatus.RUNNING -> "工具"
                ToolStatus.WAITING_APPROVAL -> "待批"
                ToolStatus.DONE -> "完成"
                ToolStatus.FAILED -> "錯誤"
            }
            add(now, badge, line)
        }
    }

    @Synchronized fun note(badge: String, text: String) = add(clock(), badge, text)

    private fun add(at: Long, badge: String, text: String) {
        _log.update { (listOf(WarLogRow(at, badge, text)) + it).take(MAX_LOG) }
    }

    /** Starts feeding from [agents] on [scope]. */
    fun follow(scope: CoroutineScope, agents: kotlinx.coroutines.flow.Flow<List<AgentStatus>>) {
        scope.launch { agents.collect { observeAgents(it) } }
    }

    companion object {
        const val MAX_HISTORY = 20
        const val MAX_LOG = 60
    }
}
