package app.dshmobile.agents

import app.dshmobile.chat.SessionRows
import app.dshmobile.chat.SessionTree
import app.dshmobile.chat.SessionTreeState
import app.dshmobile.remote.obj
import app.dshmobile.remote.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What the agents tab needs from dsh besides the shared session tree; `DshRemote` implements it. */
interface AgentsRemote {
    /** `subagents/interruptByParent` (continuable): the child stops its turn and can be prompted again. */
    suspend fun interruptSubagent(childSessionId: String, parentSessionId: String)
}

/** One subagent: a session that has a parent. */
data class AgentRow(val childId: String, val parentId: String, val label: String, val running: Boolean)

object AgentRows {
    const val DEFAULT_LABEL = "子代理人"

    /**
     * The children in [s] (every session with a parent). Label: the live `subagent` projection, else the list's, else
     * [DEFAULT_LABEL]. Running ones first, then the rest by most recent update.
     */
    fun from(s: SessionTreeState): List<AgentRow> = s.summaries.values
        .filter { it.parentId != null }
        .sortedWith(compareByDescending<app.dshmobile.remote.SessionSummary> { it.running ?: false }.thenByDescending { it.updatedAt ?: 0L })
        .map { m ->
            val projected = (s.projections[m.id]?.get("subagent") as? JsonObject)?.string("label")?.takeIf { it.isNotBlank() }
            AgentRow(
                childId = m.id,
                parentId = m.parentId!!,
                label = projected ?: m.label?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL,
                running = m.running ?: false,
            )
        }
}

/**
 * The agents tab's state. Rows come from [tree] (Ruling 16): there is no baseline or event loop of its own. [stopping] holds the
 * children whose stop was asked and whose status has not turned false yet; [failed] those whose last stop failed (the button is
 * back on, the row says so).
 */
class RunningAgents(private val tree: SessionTree, private val remote: AgentsRemote, scope: CoroutineScope) {
    val rows: StateFlow<List<AgentRow>> =
        tree.state.map(AgentRows::from).stateIn(scope, SharingStarted.Eagerly, AgentRows.from(tree.state.value))
    val runningCount: StateFlow<Int> =
        rows.map { list -> list.count { it.running } }.stateIn(scope, SharingStarted.Eagerly, rows.value.count { it.running })

    private val _stopping = MutableStateFlow<Set<String>>(emptySet())
    val stopping: StateFlow<Set<String>> = _stopping.asStateFlow()
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failed: StateFlow<Set<String>> = _failed.asStateFlow()

    init {
        // A stop is over when dsh says the child is no longer running (or the child is gone).
        scope.launch {
            rows.collect { list ->
                val live = list.filter { it.running }.map { it.childId }.toSet()
                _stopping.update { it intersect live }
                _failed.update { it intersect live }
            }
        }
    }

    /** 「子代理人「標籤」（來自 父對話標題）」; null when [sessionId] is not a child session. */
    fun originOf(sessionId: String): String? {
        val s = tree.state.value
        val row = AgentRows.from(s).firstOrNull { it.childId == sessionId } ?: return null
        val parent = SessionRows.titleOf(s, row.parentId) ?: UNTITLED
        return "子代理人「${row.label}」（來自 $parent）"
    }

    /** Asks dsh to stop [row]; the tap is ignored while a stop for it is already in flight or the row is not running. */
    suspend fun stop(row: AgentRow) {
        if (!rows.value.any { it.childId == row.childId && it.running }) return
        var already = false
        _stopping.update { if (row.childId in it) { already = true; it } else it + row.childId }
        if (already) return
        _failed.update { it - row.childId }
        try {
            remote.interruptSubagent(row.childId, row.parentId)
        } catch (e: CancellationException) {
            _stopping.update { it - row.childId }
            throw e
        } catch (e: Exception) {
            // Only the fact is kept: an exception text could carry anything dsh said.
            _stopping.update { it - row.childId }
            _failed.update { it + row.childId }
        }
    }

    private companion object {
        const val UNTITLED = "新對話"
    }
}
