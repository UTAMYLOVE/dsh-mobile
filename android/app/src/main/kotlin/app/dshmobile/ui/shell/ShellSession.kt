package app.dshmobile.ui.shell

import app.dshmobile.agents.AgentsRemote
import app.dshmobile.agents.RunningAgents
import app.dshmobile.chat.ChatRemote
import app.dshmobile.chat.ConversationStore
import app.dshmobile.chat.IndexRemote
import app.dshmobile.chat.SessionIndex
import app.dshmobile.chat.SessionTree
import app.dshmobile.remote.DshEvents
import app.dshmobile.remote.MuxState
import app.dshmobile.service.RunningDsh
import app.dshmobile.runtime.LaunchUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the shell needs from one running dsh; `DshRemote` implements it (later tasks widen it with chat, index and agents). */
interface ShellRemote : ChatRemote, IndexRemote, AgentsRemote {
    override val muxState: StateFlow<MuxState>
    fun close()
}

/**
 * Everything the native interface keeps for one dsh instance (app level: it outlives a recreated screen and dies with that
 * dsh). [scope] belongs to this session: [close] closes [remote] and cancels it, so pass a scope of its own (a child of the
 * application's scope), never the application's scope itself. Later tasks add their stores here.
 */
class ShellSession(val remote: ShellRemote, val events: DshEvents?, val scope: CoroutineScope) {
    /** The shared session tree and the conversation list built on it (later tasks derive agents and the overview from the tree). */
    val tree = SessionTree(remote, scope)
    val index = SessionIndex(tree, remote, scope)

    /** The agents tab's rows and stops, derived from [tree]. */
    val agents = RunningAgents(tree, remote, scope)

    @Volatile private var closed = false

    /** Open conversations, least recently used first (access order); guarded by itself. */
    private val stores = LinkedHashMap<String, ConversationStore>(4, .75f, true)

    /**
     * The store of conversation [id], kept while it is one of the [MAX_STORES] most recently used: going back to a conversation
     * just opened shows it at once. The least recently used one beyond that is closed (its follow ends).
     */
    fun conversation(id: String): ConversationStore = synchronized(stores) {
        stores[id] ?: ConversationStore(remote, id, scope).also { fresh ->
            stores[id] = fresh
            while (stores.size > MAX_STORES) {
                val eldest = stores.entries.iterator().next()
                stores.remove(eldest.key)
                eldest.value.close()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(stores) {
            stores.values.forEach { it.close() }
            stores.clear()
        }
        remote.close()
        scope.cancel()
    }

    private companion object {
        const val MAX_STORES = 3
    }
}

/**
 * The one current [ShellSession], keyed by dsh's launch URL: the same dsh keeps its session (a screen coming back, a second
 * start racing the first), a new dsh instance gets a new one and the old one is closed. The launch URL carries dsh's one-time
 * token: it stays in memory here and is never logged.
 */
class ShellSessionHolder(private val create: (RunningDsh) -> ShellSession) {
    private val lock = Any()
    private var attached: LaunchUrl? = null
    private val _current = MutableStateFlow<ShellSession?>(null)
    val current: StateFlow<ShellSession?> = _current.asStateFlow()

    /** The launch of the dsh the current session talks to; null when none is attached. */
    val launch: LaunchUrl? get() = synchronized(lock) { attached }

    /** The session for [running]: the current one when it is the same launch URL, else a new one (the old one is closed first). */
    fun attach(running: RunningDsh): ShellSession = synchronized(lock) {
        val current = _current.value
        if (current != null && attached?.url == running.launch.url) return current
        clearLocked()
        val fresh = create(running)
        attached = running.launch
        _current.value = fresh
        fresh
    }

    /** dsh stopped: the current session (if any) is closed and forgotten. */
    fun detach() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        val old = _current.value
        _current.value = null
        attached = null
        old?.close()
    }
}
