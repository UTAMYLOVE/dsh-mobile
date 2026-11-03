package app.dshmobile.ui.shell

import kotlinx.coroutines.flow.map
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
import app.dshmobile.ui.chat.ChatUiCache
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
class ShellSession(
    val remote: ShellRemote,
    val events: DshEvents?,
    val scope: CoroutineScope,
    /** Whether conversations should run under dsh's Auto review (stage 2 §J). */
    private val autoReview: () -> Boolean = { false },
    /** Whether each message picks DeepSeek Flash or Pro by itself (stage 2 batch 2). */
    private val autoRoute: () -> Boolean = { false },
) {
    /** Conversations already switched to `/permission auto` by this session's dsh. */
    private val autoSwitched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun ensureAutoReview(id: String) {
        if (!autoReview() || id in autoSwitched) return
        remote.runCommand(id, "/permission auto")
        autoSwitched += id
    }

    /**
     * 「自動選模型」: before a send, switch the conversation between DeepSeek Flash and Pro by [app.dshmobile.config.ModelRouter],
     * keeping the chosen thinking strength. A conversation on a Claude model is left alone. A failure never blocks the send.
     */
    private suspend fun route(id: String, parts: List<app.dshmobile.remote.PromptPart>) {
        if (!autoRoute()) return
        val selection = tree.state.value.projections[id]?.get("modelSelection") as? kotlinx.serialization.json.JsonObject
        fun model(key: String) = ((selection?.get(key) as? kotlinx.serialization.json.JsonObject)?.get("model") as? kotlinx.serialization.json.JsonPrimitive)?.content
        val current = model("next") ?: model("lastUsed") ?: tree.state.value.summaries[id]?.let { it.nextModel ?: it.model }
        if (current != null && app.dshmobile.config.AgentModels.find(current)?.isClaude == true) return
        val pick = app.dshmobile.config.ModelRouter.pick(parts)
        if (pick == current) return
        val effort = synchronized(stores) { stores[id] }?.ui?.effort
        if (effort == null) remote.selectModel(id, app.dshmobile.config.AgentModels.DEEPSEEK_PROVIDER, pick)
        else remote.selectModelEffort(id, app.dshmobile.config.AgentModels.DEEPSEEK_PROVIDER, pick, effort)
    }

    /** The shared session tree and the conversation list built on it (later tasks derive agents and the overview from the tree). */
    val tree = SessionTree(remote, scope)
    val index = SessionIndex(tree, remote, scope)

    /** The agents tab's rows and stops, derived from [tree]. */
    val agents = RunningAgents(tree, remote, scope)

    /** The war room's history of finished children and its live log (stage 2 §E/F/H). */
    val warRoom = app.dshmobile.agents.WarRoomRecorder().also { rec ->
        rec.follow(scope, tree.state.map { app.dshmobile.agents.WarRoomBuilder.children(it) })
    }

    @Volatile private var closed = false

    /** One open conversation: its store and the chat page's cache for it. */
    private class Open(val store: ConversationStore, val ui: ChatUiCache)

    /** Open conversations, least recently used first (access order); guarded by itself. */
    private val stores = LinkedHashMap<String, Open>(4, .75f, true)

    /**
     * The store of conversation [id], kept while it is one of the [MAX_STORES] most recently used: going back to a conversation
     * just opened shows it at once. The least recently used one beyond that is closed (its follow ends), unless it holds a send
     * dsh has not recorded or that failed: evicting it would lose those bubbles (final review B2-M11).
     */
    fun conversation(id: String): ConversationStore = open(id).store

    /** The store of [id] when it is already open (never opens one: the war room must not evict the user's chats). */
    fun existing(id: String?): ConversationStore? = id?.let { synchronized(stores) { stores[it]?.store } }

    /** The chat page's cache for conversation [id] (parsed Markdown, open thinking, scroll), kept and evicted with its store. */
    fun chatUi(id: String): ChatUiCache = open(id).ui

    private fun open(id: String): Open = synchronized(stores) {
        stores[id] ?: Open(ConversationStore(remote, id, scope, runningHint = { tree.state.value.summaries[id]?.running }, beforeSend = { parts -> ensureAutoReview(id); runCatching { route(id, parts) } }, decorate = { parts -> app.dshmobile.chat.ReviewRounds.decorate(synchronized(stores) { stores[id] }?.ui?.reviewRounds ?: 0, parts) }), ChatUiCache()).also { fresh ->
            stores[id] = fresh
            if (stores.size > MAX_STORES) {
                val evictable = stores.entries.filter { it.key != id && it.value.store.pending.value.isEmpty() }
                evictable.take(stores.size - MAX_STORES).forEach { (key, old) ->
                    stores.remove(key)
                    old.store.close()
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(stores) {
            stores.values.forEach { it.store.close() }
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
