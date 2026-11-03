package app.dshmobile.chat

import app.dshmobile.remote.HostEvent
import app.dshmobile.remote.MuxState
import app.dshmobile.remote.SearchHit
import app.dshmobile.remote.SessionSummary
import app.dshmobile.remote.StreamSignal
import app.dshmobile.runtime.AppPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the conversation list and the session tree need from dsh; `DshRemote` implements it. */
interface IndexRemote {
    suspend fun listSessions(): List<SessionSummary>
    suspend fun searchSessions(query: String): List<SearchHit>
    suspend fun createSession(workspaceId: String?): String
    suspend fun rename(sessionId: String, title: String): String
    suspend fun archive(sessionId: String)
    fun control(): Flow<StreamSignal>
    fun workspaces(): Flow<StreamSignal>
    val hostEvents: Flow<HostEvent>
    val muxState: StateFlow<MuxState>

    /** `$events`' client id: it changes (through null) whenever dsh's event stream was reopened, which can lose events. */
    val eventsClientId: StateFlow<String?> get() = NO_CLIENT
    private companion object { val NO_CLIENT: StateFlow<String?> = MutableStateFlow(null) }
}

/** Every session dsh knows (top level and subagents), what is archived, the app workspace and the live projections we use. */
data class SessionTreeState(
    val summaries: Map<String, SessionSummary> = emptyMap(),
    val archived: Set<String> = emptySet(),
    val workspaceId: String? = null,
    val projections: Map<String, Map<String, JsonElement>> = emptyMap(),
)

object SessionTreeReducer {
    private val KEPT = setOf("title", "turnOutline", "modelSelection", "subagent")

    /** The list is the new baseline (children included). */
    fun applyList(s: SessionTreeState, list: List<SessionSummary>): SessionTreeState = s.copy(summaries = list.associateBy { it.id })

    /** `workspace/follow`: `baseline`/`replace` (items, archivedSessionIds), `upsert`, `archived`. Anything else changes nothing. */
    fun applyWorkspace(s: SessionTreeState, frame: JsonElement?): SessionTreeState = try {
        val f = frame as? JsonObject
        val body = (f?.get("value") as? JsonObject) ?: f
        when ((f?.get("type") as? JsonPrimitive)?.content) {
            "baseline", "replace" -> {
                val items = body?.get("items") as? JsonArray
                val ws = items?.mapNotNull { it as? JsonObject }?.firstOrNull { str(it, "path") == AppPaths.GUEST_WORKSPACE }
                s.copy(
                    workspaceId = ws?.let { str(it, "workspaceId") } ?: s.workspaceId,
                    archived = ids(body?.get("archivedSessionIds")) ?: s.archived,
                )
            }
            "upsert" -> {
                val w = f?.get("workspace") as? JsonObject
                if (w != null && str(w, "path") == AppPaths.GUEST_WORKSPACE) s.copy(workspaceId = str(w, "workspaceId") ?: s.workspaceId) else s
            }
            "archived" -> ids(body?.get("archivedSessionIds"))?.let { s.copy(archived = s.archived + it) } ?: s
            else -> s
        }
    } catch (_: RuntimeException) {
        s
    }

    /** `session/control`: only [KEPT] keys are stored. */
    fun applyControl(s: SessionTreeState, frame: JsonElement?): SessionTreeState = try {
        val f = frame as? JsonObject
        when ((f?.get("type") as? JsonPrimitive)?.content) {
            "baseline" -> {
                val all = (f?.get("value") as? JsonObject)?.get("projections") as? JsonObject
                if (all == null) s else s.copy(projections = all.mapNotNull { (sid, p) ->
                    val values = (p as? JsonObject)?.get("values") as? JsonObject ?: return@mapNotNull null
                    sid to values.filterKeys { it in KEPT }
                }.toMap())
            }
            "projection" -> {
                val sid = str(f, "sessionId")
                val key = str(f, "key")
                val value = f?.get("value")
                if (sid == null || key == null || key !in KEPT || value == null) s
                else s.copy(projections = s.projections + (sid to ((s.projections[sid] ?: emptyMap()) + (key to value))))
            }
            else -> s
        }
    } catch (_: RuntimeException) {
        s
    }

    fun applyEvent(s: SessionTreeState, e: HostEvent): SessionTreeState = when (e) {
        is HostEvent.SessionAdded -> s.copy(summaries = s.summaries + (e.summary.id to e.summary))
        is HostEvent.SessionStatus -> s.summaries[e.sessionId]?.let { s.copy(summaries = s.summaries + (e.sessionId to it.copy(running = e.running))) } ?: s
        is HostEvent.SessionRemoved -> s.copy(summaries = s.summaries - e.sessionId, projections = s.projections - e.sessionId)
    }

    private fun str(o: JsonObject?, key: String): String? = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun ids(e: JsonElement?): Set<String>? =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet()
}

/**
 * The one shared tree of sessions: top-level conversations and subagents together. Built from `session/list` and kept current by
 * `$events` host events, `session/control` projections and `workspace/follow`. Host events have no replay, so the baseline is
 * fetched again every time the mux returns to OPEN and every time `$events` was reopened; events that arrive while the list call
 * is in flight are newer than the list and win (same method as `WorkTracker`).
 */
class SessionTree(private val remote: IndexRemote, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SessionTreeState())
    val state: StateFlow<SessionTreeState> = _state.asStateFlow()

    private val lock = Any()
    private var touched: MutableList<HostEvent>? = null // events seen while a list call is in flight; guarded by lock
    private val reconcileLock = Mutex()

    init {
        // Subscribe before anything else so no event falls between the baseline and the first collect.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            remote.hostEvents.collect { e -> synchronized(lock) { touched?.add(e); _state.update { SessionTreeReducer.applyEvent(it, e) } } }
        }
        keepCollecting(remote::control, SessionTreeReducer::applyControl)
        keepCollecting(remote::workspaces, SessionTreeReducer::applyWorkspace)
        scope.launch {
            combine(remote.muxState, remote.eventsClientId) { mux, client -> mux to client }.collectLatest { (mux, _) ->
                if (mux == MuxState.OPEN) baseline()
            }
        }
    }

    /** Fetches `session/list` again (after a create, rename or delete). Failures are ignored: the next reconnect refetches. */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    internal suspend fun refreshNow() {
        try {
            reconcile()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep what we have.
        }
    }

    internal fun markArchived(id: String) = _state.update { it.copy(archived = it.archived + id) }

    internal fun setTitle(id: String, title: String) = _state.update {
        it.copy(projections = it.projections + (id to ((it.projections[id] ?: emptyMap()) + ("title" to JsonPrimitive(title)))))
    }

    // Short handlers only: they must never suspend inside the collect.
    private fun keepCollecting(open: () -> Flow<StreamSignal>, apply: (SessionTreeState, JsonElement?) -> SessionTreeState) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                try {
                    open().collect { if (it is StreamSignal.Value) _state.update { s -> apply(s, it.value) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Opened again below.
                }
                delay(REOPEN_MS)
            }
        }
    }

    private suspend fun baseline() {
        while (true) {
            try {
                reconcile()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(RETRY_MS)
            }
        }
    }

    private suspend fun reconcile() = reconcileLock.withLock {
        synchronized(lock) { touched = ArrayList() }
        try {
            val list = remote.listSessions()
            synchronized(lock) {
                val seen = touched.orEmpty()
                _state.update { s -> seen.fold(SessionTreeReducer.applyList(s, list)) { acc, e -> SessionTreeReducer.applyEvent(acc, e) } }
            }
        } finally {
            synchronized(lock) { touched = null }
        }
    }

    private companion object {
        const val RETRY_MS = 2_000L
        const val REOPEN_MS = 2_000L
    }
}
