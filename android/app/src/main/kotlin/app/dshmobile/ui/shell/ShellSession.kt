package app.dshmobile.ui.shell

import kotlinx.coroutines.launch
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
interface ShellRemote : ChatRemote, IndexRemote, AgentsRemote, app.dshmobile.chat.ScheduleRemote {
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
    /** The dsh permission preset of the owner's permission level (PermissionLevel.preset); null leaves conversations as they are. */
    private val levelPreset: () -> String? = { null },
) {
    /** Conversations this session's dsh already has on the wanted preset (by conversation id). */
    private val presetSet = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Before a message goes out, the conversation is put on the preset the owner's settings ask for: Auto review when that is
     * on, else the permission level's preset. New conversations start there already (DshPatch.permission); this moves the ones
     * made earlier, which dsh keeps on the preset they were created with (the old "workspace-write", where every command had to
     * be escalated and approved). Nothing is sent when dsh already reports the wanted preset.
     */
    private suspend fun ensurePreset(id: String) {
        val wanted = if (autoReview()) "auto" else levelPreset() ?: return
        if (presetSet[id] == wanted) return
        val current = ((tree.state.value.projections[id]?.get("permissions") as? kotlinx.serialization.json.JsonObject)
            ?.get("currentValue") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        if (current != wanted) remote.runCommand(id, "/permission $wanted")
        presetSet[id] = wanted
    }


    /** The shared session tree and the conversation list built on it (later tasks derive agents and the overview from the tree). */
    val tree = SessionTree(remote, scope)
    val index = SessionIndex(tree, remote, scope)

    /** The agents tab's rows and stops, derived from [tree]. */
    val agents = RunningAgents(tree, remote, scope)

    /** The war room's history of finished children and its live log (stage 2 §E/F/H). */
    val warRoom = app.dshmobile.agents.WarRoomRecorder().also { rec ->
        scope.launch {
            tree.state.collect { t ->
                rec.observeAgents(app.dshmobile.agents.WarRoomBuilder.children(t))
                rec.sample(t)
            }
        }
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

    /**
     * The conversation the shell shows (MainActivity keeps it current). It is never evicted: the dock's STOP and running state
     * read its store, and a closed store stopped updating (no STOP for the next reply, or STOP stuck after one).
     */
    @Volatile var openId: String? = null

    /** A conversation's own choices (思考強度, 審查輪數, when it was last condensed) outlive its evicted store. Under `stores`. */
    private val keptChoices = HashMap<String, Triple<String?, Int, Long>>()

    private fun open(id: String): Open = synchronized(stores) {
        stores[id] ?: Open(ConversationStore(remote, id, scope, runningHint = { tree.state.value.summaries[id]?.running }, beforeSend = { ensurePreset(id) }, decorate = { parts -> app.dshmobile.chat.ReviewRounds.decorate(synchronized(stores) { stores[id] }?.ui?.reviewRounds ?: 0, parts) }), ChatUiCache().also { ui ->
            keptChoices.remove(id)?.let { (effort, rounds, compacted) -> ui.effort = effort; ui.reviewRounds = rounds; ui.compactedAt = compacted }
        }).also { fresh ->
            stores[id] = fresh
            if (stores.size > MAX_STORES) {
                val shown = openId
                val evictable = stores.entries.filter { it.key != id && it.key != shown && it.value.store.pending.value.isEmpty() }
                evictable.take(stores.size - MAX_STORES).forEach { (key, old) ->
                    stores.remove(key)
                    keptChoices[key] = Triple(old.ui.effort, old.ui.reviewRounds, old.ui.compactedAt)
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
