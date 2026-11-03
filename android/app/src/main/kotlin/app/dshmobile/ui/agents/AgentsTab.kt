package app.dshmobile.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.dshmobile.agents.RunningAgents
import app.dshmobile.chat.SessionRows
import app.dshmobile.chat.SessionTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [AgentsScreen] wired to the session's agents. A stop is launched on [sessionScope] (the dsh session's), not on the screen's:
 * leaving the tab must not cancel an interrupt that is already in flight.
 */
@Composable
fun AgentsTab(agents: RunningAgents, tree: SessionTree, sessionScope: CoroutineScope, limit: Int, onOpenParent: (String) -> Unit) {
    val rows by agents.rows.collectAsState()
    val stopping by agents.stopping.collectAsState()
    val failed by agents.failed.collectAsState()
    val state by tree.state.collectAsState()
    AgentsScreen(
        rows = rows,
        parentTitle = { id -> SessionRows.titleOf(state, id) },
        limit = limit,
        stopping = stopping,
        onStop = { row -> sessionScope.launch { agents.stop(row) } },
        onOpenParent = onOpenParent,
        failed = failed,
    )
}
