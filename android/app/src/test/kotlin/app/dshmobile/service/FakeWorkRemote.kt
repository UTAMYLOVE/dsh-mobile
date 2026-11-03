package app.dshmobile.service

import app.dshmobile.remote.HostEvent
import app.dshmobile.remote.MuxState
import app.dshmobile.remote.SessionSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** A [WorkRemote] a test drives by hand: push host events, change the list answer, flip the mux state. */
class FakeWorkRemote(initial: List<SessionSummary> = emptyList(), state: MuxState = MuxState.OPEN) : WorkRemote {
    private val events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 64)
    private val _state = MutableStateFlow(state)
    private val _client = MutableStateFlow<String?>("c1")
    private var clients = 1

    @Volatile var sessions: List<SessionSummary> = initial
    val cancelled = CopyOnWriteArrayList<String>()
    val interrupted = CopyOnWriteArrayList<Pair<String, String>>()

    override val prompts = MutableStateFlow<List<app.dshmobile.remote.PendingPrompt>>(emptyList())
    override val hostEvents get() = events
    override val muxState: StateFlow<MuxState> get() = _state
    override val eventsClientId: StateFlow<String?> get() = _client
    override suspend fun listSessions() = sessions
    override suspend fun cancel(sessionId: String) { cancelled.add(sessionId) }
    override suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) {
        interrupted.add(childSessionId to parentSessionId)
    }

    fun emit(e: HostEvent) { check(events.tryEmit(e)) }
    fun status(id: String, running: Boolean) = emit(HostEvent.SessionStatus(id, running))
    /** `$events` was reopened on an open mux (what DshEvents does when dsh ends the stream): a new client id. */
    fun reopenEvents() { _client.value = null; _client.value = "c${++clients}" }
    fun setState(s: MuxState) { _state.value = s }

    companion object {
        fun row(id: String, running: Boolean?, parent: String? = null, title: String? = null) =
            SessionSummary(id, parent, null, null, running, null, title, null, null, null, null)
    }
}
