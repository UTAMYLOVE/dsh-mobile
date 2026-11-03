package app.dshmobile.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.dshmobile.agents.AgentRow
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.RunningAgents
import app.dshmobile.agents.WarRoomBuilder
import app.dshmobile.agents.WarRoomRecorder
import app.dshmobile.chat.ConversationState
import app.dshmobile.chat.SessionRows
import app.dshmobile.chat.SessionTree
import app.dshmobile.chat.providerOf
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.gateway.Usage
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.LocalPageActive
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The app's estimate for an agent: its model's price (Claude models by name, else DeepSeek) times its tokens. */
internal fun agentCostUsd(prices: PriceTable, a: AgentStatus): Double? {
    val model = a.model ?: return null
    val p = providerOf(if (model.startsWith("claude")) "anthropic" else "deepseek") ?: Provider.DEEPSEEK
    val u = a.usage
    if (u.totalTokens == 0L) return null
    return prices.costUsd(p, model, Usage(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens), Instant.now())
}

/** The agents of the war room for [openId] (the open conversation, or the busiest), rebuilt every second while shown. */
@Composable
fun rememberWarAgents(tree: SessionTree, recorder: WarRoomRecorder, openId: String?, lead: Flow<ConversationState?>): List<AgentStatus> {
    val state by tree.state.collectAsState()
    val history by recorder.history.collectAsState()
    val leadState by remember(lead) { lead }.collectAsState(null)
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val leadId = WarRoomBuilder.pickLead(state, openId)
    val anyRunning = state.summaries.values.any { it.running == true }
    val ticking = anyRunning && LocalEffects.current.ambient && LocalPageActive.current
    // Elapsed times tick once a second only while something runs and motion is allowed; otherwise they move with dsh's updates.
    LaunchedEffect(ticking) {
        if (ticking) while (true) {
            androidx.compose.animation.core.withInfiniteAnimationFrameMillis { }
            val t = System.currentTimeMillis()
            if (t - tick >= 1_000) tick = t
        }
    }
    val now = if (ticking) tick else System.currentTimeMillis()
    // When the lead's turn began, for its elapsed time.
    var startedAt by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val running = leadState?.takeIf { it.sessionId == leadId }?.running ?: (leadId?.let { state.summaries[it]?.running } ?: false)
    LaunchedEffect(leadId, running) { startedAt = if (running && leadId != null) leadId to System.currentTimeMillis() else null }
    return WarRoomBuilder.build(
        state, leadId, leadState?.takeIf { it.sessionId == leadId }, history, now, startedAt?.takeIf { it.first == leadId }?.second,
    )
}

/**
 * The 代理人 tab: the war room (§E/G/H), or the plain list ([AgentsScreen]) for TalkBack and small screens. A stop is launched on
 * [sessionScope] (the dsh session's), so leaving the tab never cancels an interrupt in flight.
 */
@Composable
fun AgentsTab(
    agents: RunningAgents,
    tree: SessionTree,
    sessionScope: CoroutineScope,
    limit: Int,
    onOpenParent: (String) -> Unit,
    recorder: WarRoomRecorder? = null,
    openId: String? = null,
    lead: Flow<ConversationState?> = flowOf(null),
    todayUsd: Double? = null,
    limits: String = "",
) {
    val rows by agents.rows.collectAsState()
    val stopping by agents.stopping.collectAsState()
    val failed by agents.failed.collectAsState()
    val state by tree.state.collectAsState()
    var listMode by rememberSaveable { mutableStateOf(false) }
    if (recorder == null || listMode) {
        Column(Modifier.fillMaxWidth()) {
            if (recorder != null) ModeToggle(listMode) { listMode = !listMode }
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
        return
    }
    val war = rememberWarAgents(tree, recorder, openId, lead)
    val log by recorder.log.collectAsState()
    val prices = remember { PriceTable() }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModeToggle(listMode) { listMode = !listMode }
        WarRoom(
            agents = war, log = log, todayUsd = todayUsd, limits = limits, expanded = expanded, onExpand = { expanded = it },
            costOf = { agentCostUsd(prices, it) },
            onStop = { a -> rows.firstOrNull { it.childId == a.id }?.let { r: AgentRow -> sessionScope.launch { agents.stop(r) } } },
            stopping = stopping,
            onOpen = { a -> onOpenParent(if (a.isLead) a.id else a.parentId ?: a.id) },
            limit = limit,
        )
    }
}

@Composable
private fun ModeToggle(listMode: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        FogButton(if (listMode) "戰情室" else "清單模式", FogButtonKind.GHOST, onToggle, Modifier.testTag(Tags.WAR_LIST_TOGGLE))
    }
}
