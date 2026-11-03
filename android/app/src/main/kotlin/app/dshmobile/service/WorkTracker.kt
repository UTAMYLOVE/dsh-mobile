package app.dshmobile.service

import app.dshmobile.remote.DshRemote
import app.dshmobile.remote.HostEvent
import app.dshmobile.remote.MuxState
import app.dshmobile.remote.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The slice of [DshRemote] that [WorkTracker] needs (lets tests substitute a fake). */
interface WorkRemote {
    val hostEvents: Flow<HostEvent>
    val muxState: StateFlow<MuxState>
    suspend fun listSessions(): List<SessionSummary>
    suspend fun cancel(sessionId: String)
    suspend fun interruptSubagent(childSessionId: String, parentSessionId: String)
    fun close() = Unit
}

fun DshRemote.asWorkRemote(): WorkRemote = object : WorkRemote {
    override val hostEvents get() = this@asWorkRemote.hostEvents
    override val muxState get() = this@asWorkRemote.muxState
    override suspend fun listSessions() = this@asWorkRemote.listSessions()
    override suspend fun cancel(sessionId: String) = this@asWorkRemote.cancel(sessionId)
    override suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) =
        this@asWorkRemote.interruptSubagent(childSessionId, parentSessionId)
    override fun close() = this@asWorkRemote.close()
}

/** One conversation (or subagent, when [parentId] is set) that dsh is working on right now. */
data class RunningWork(val sessionId: String, val parentId: String?, val title: String?)

/**
 * Knows whether dsh still has work: any session, subagents included, that is running. Built from `$events` host events
 * and rebuilt from `session/list` every time the mux (re)opens, so events missed while disconnected cannot leave it stuck.
 * Holds no dsh lifecycle itself: [DshService] turns [busy] into the `"work"` holder.
 */
class WorkTracker(private val remote: WorkRemote, private val scope: CoroutineScope) {
    private val lock = Any()
    private val runningIds = LinkedHashSet<String>()      // guarded by lock
    private val meta = HashMap<String, SessionSummary>()  // guarded by lock
    private var touched: MutableMap<String, Boolean>? = null // events seen while a baseline list is in flight; guarded by lock
    private val asked = HashSet<String>()                 // sessions whose metadata was already requested; guarded by lock

    private val _running = MutableStateFlow<List<RunningWork>>(emptyList())
    private val _busy = MutableStateFlow(false)

    /** True while any session (a subagent too) is running. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val running: StateFlow<List<RunningWork>> = _running.asStateFlow()

    init {
        // Subscribe before anything else so no event falls between the baseline and the first collect.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { remote.hostEvents.collect(::onEvent) }
        scope.launch {
            remote.muxState.collectLatest { state ->
                if (state == MuxState.OPEN) baseline()
            }
        }
    }

    /** Cancels every top-level conversation and interrupts every subagent. One failure does not stop the others. */
    suspend fun stopAll() {
        val work = _running.value
        for (w in work) {
            val parent = w.parentId
            try {
                if (parent == null) remote.cancel(w.sessionId) else remote.interruptSubagent(w.sessionId, parent)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The session may have ended meanwhile; the status events decide what is still running.
            }
        }
    }

    private fun onEvent(event: HostEvent) {
        when (event) {
            is HostEvent.SessionAdded -> synchronized(lock) {
                meta[event.summary.id] = event.summary
                if (event.summary.running == true) mark(event.summary.id, true)
            }
            is HostEvent.SessionStatus -> {
                synchronized(lock) { mark(event.sessionId, event.running) }
                if (event.running) ensureMeta(event.sessionId)
            }
            is HostEvent.SessionRemoved -> synchronized(lock) { mark(event.sessionId, false); meta.remove(event.sessionId) }
        }
        publish()
    }

    private fun mark(id: String, isRunning: Boolean) {
        touched?.put(id, isRunning)
        if (isRunning) runningIds.add(id) else runningIds.remove(id)
    }

    private suspend fun baseline() {
        while (true) {
            synchronized(lock) { touched = HashMap() }
            try {
                val list = remote.listSessions()
                synchronized(lock) {
                    val seen = touched.orEmpty()
                    runningIds.clear()
                    list.forEach { meta[it.id] = it; if (it.running == true) runningIds.add(it.id) }
                    seen.forEach { (id, r) -> if (r) runningIds.add(id) else runningIds.remove(id) } // newer than the list
                    touched = null
                }
                publish()
                return
            } catch (e: CancellationException) {
                synchronized(lock) { touched = null }
                throw e
            } catch (_: Exception) {
                synchronized(lock) { touched = null }
                delay(RETRY_MS)
            }
        }
    }

    /** A status event can name a session we have no row for (parent and title unknown): ask the list once. */
    private fun ensureMeta(id: String) {
        val need = synchronized(lock) { id !in meta && asked.add(id) }
        if (!need) return
        scope.launch {
            try {
                val list = remote.listSessions()
                synchronized(lock) { list.forEach { meta.putIfAbsent(it.id, it) } }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                synchronized(lock) { asked.remove(id) }
            }
        }
    }

    private fun publish() = synchronized(lock) {
        val list = runningIds.map { id -> RunningWork(id, meta[id]?.parentId, meta[id]?.title?.takeIf { it.isNotBlank() }) }
        _running.value = list
        _busy.value = list.isNotEmpty()
    }

    private companion object {
        const val RETRY_MS = 2_000L
    }
}
