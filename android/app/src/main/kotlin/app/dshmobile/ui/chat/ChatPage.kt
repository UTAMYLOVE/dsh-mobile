package app.dshmobile.ui.chat

import app.dshmobile.chat.ContextFill
import app.dshmobile.chat.SessionUsage
import app.dshmobile.chat.replyCostUsd
import app.dshmobile.chat.replyLine
import app.dshmobile.chat.shortTokens
import app.dshmobile.chat.teamUsage
import app.dshmobile.gateway.PriceTable
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dshmobile.R
import app.dshmobile.chat.AttachmentLoader
import app.dshmobile.chat.AttachmentSender
import app.dshmobile.chat.PendingAttachment
import app.dshmobile.chat.SendOutcome
import app.dshmobile.chat.sendWithAttachments
import app.dshmobile.chat.SessionRow
import app.dshmobile.chat.groupByDay
import app.dshmobile.config.AgentModels
import app.dshmobile.config.displayName
import app.dshmobile.remote.DshErrors
import app.dshmobile.remote.DshRpcException
import app.dshmobile.remote.PromptPart
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.ShellNav
import app.dshmobile.ui.shell.ShellSession
import app.dshmobile.ui.shell.ShellTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Day groups (今天／昨天／更早) follow the owner's calendar. */
val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

/** Milliseconds from [now] to the next midnight in [zone]. */
fun msUntilNextMidnight(now: Instant, zone: ZoneId): Long =
    Duration.between(now, now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)).toMillis()

/**
 * The conversation list's search: dsh is asked about [debounceMs] after the last keystroke, only for the latest query
 * (`collectLatest` drops a search still running when the query changes), so results for an older query never show. [results]
 * is (query, rows); null while there is nothing for the current query yet, and at once for a blank query. A failed search is
 * an empty result.
 */
class ListSearch(private val search: suspend (String) -> List<SessionRow>, scope: CoroutineScope, private val debounceMs: Long = 250) {
    private val queries = MutableStateFlow("")
    private val _results = MutableStateFlow<Pair<String, List<SessionRow>>?>(null)
    val results: StateFlow<Pair<String, List<SessionRow>>?> = _results.asStateFlow()

    init {
        scope.launch {
            queries.collectLatest { q ->
                _results.value = null
                if (q.isBlank()) return@collectLatest
                delay(debounceMs)
                val found = try {
                    search(q)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                if (queries.value == q) _results.value = q to found
            }
        }
    }

    fun query(q: String) {
        queries.value = q
    }
}

/** Today in [zone]; it changes at midnight there and is checked again whenever the screen resumes. */
@Composable
internal fun rememberToday(zone: ZoneId = TAIPEI): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            delay(msUntilNextMidnight(Instant.now(), zone) + 1_000)
            today = LocalDate.now(zone)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, zone) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) today = LocalDate.now(zone) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return today
}

/** A short code for a failure: dsh's own (sanitised) or the exception's class name. */
internal fun failureCode(e: Exception): String =
    if (e is DshRpcException) DshErrors.safeCode(e.code) else e.javaClass.simpleName.ifEmpty { DshErrors.REFUSED }

/**
 * The chat tab: the conversation list while [ShellNav.listOpen] or while no conversation is open, else the open conversation.
 * Lasting actions (create, rename, delete, sends, model changes) run in the session's scope, so leaving the page does not cut
 * them short. [onMessage] shows a short message (a toast).
 */
@Composable
fun ChatPage(
    session: ShellSession,
    nav: ShellNav,
    hasClaudeKey: Boolean,
    leadModel: String,
    onLink: (String) -> Unit,
    onShare: (String) -> Unit,
    onMessage: (String) -> Unit,
    onRestartDsh: () -> Unit = {},
    /** 編輯並重新送出: the dock takes the text; its next send forks the conversation at the boundary (MainViewModel.startEdit). */
    onEditMessage: (sessionId: String, boundary: Long, text: String) -> Unit = { _, _, _ -> },
) {
    val openId = nav.openSessionId
    if (nav.listOpen || openId == null) {
        ListPage(session, nav, onMessage)
    } else {
        key(session, openId) { ConversationPage(session, openId, nav, hasClaudeKey, leadModel, onLink, onShare, onRestartDsh, onMessage, onEditMessage) }
    }
}

@Composable
private fun ListPage(session: ShellSession, nav: ShellNav, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val rows by session.index.rows.collectAsState()
    val uiScope = rememberCoroutineScope()
    val search = remember(session) { ListSearch(session.index::search, uiScope) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(search) { search.query(query) }
    val results by search.results.collectAsState()
    val today = rememberToday()
    val groups = remember(rows, results, query, today) {
        val q = query.trim()
        val shown = when {
            q.isEmpty() -> rows
            results?.first == query -> results!!.second
            // Until dsh answers for this query: the titles that match it, at once.
            else -> rows.filter { it.title.contains(q, ignoreCase = true) }
        }
        groupByDay(shown, today, TAIPEI)
    }
    fun failed(e: Exception) = onMessage(context.getString(R.string.chat_list_failed, failureCode(e)))
    fun act(block: suspend () -> Unit) {
        session.scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(LocalPagePadding.current)) {
        ConversationListScreen(
            groups = groups,
            query = query,
            onQuery = { query = it; search.query(it) },
            onOpen = { nav.openSession(it) },
            onNew = { act { nav.openSession(session.index.create()) } },
            onRename = { id, title -> act { session.index.rename(id, title) } },
            onDelete = { id ->
                act {
                    session.index.delete(id)
                    if (nav.openSessionId == id) nav.openSessionId = null
                }
            },
            onClose = {
                nav.listOpen = false
                if (nav.openSessionId == null) nav.select(ShellTab.OVERVIEW)
            },
        )
    }
}

@Composable
private fun ConversationPage(
    session: ShellSession,
    id: String,
    nav: ShellNav,
    hasClaudeKey: Boolean,
    leadModel: String,
    onLink: (String) -> Unit,
    onShare: (String) -> Unit,
    onRestartDsh: () -> Unit,
    onMessage: (String) -> Unit = {},
    onEditMessage: (sessionId: String, boundary: Long, text: String) -> Unit = { _, _, _ -> },
) {
    val store = remember(session, id) { session.conversation(id) }
    val ui = remember(session, id) { session.chatUi(id) }
    val conversation by store.state.collectAsState()
    val pending by store.pending.collectAsState()
    val sendError by store.sendError.collectAsState()
    val followError by store.followError.collectAsState()
    val olderError by store.olderError.collectAsState()
    val rowFlow = remember(session, id) { session.index.rows.map { rows -> rows.firstOrNull { it.id == id } }.distinctUntilChanged() }
    val row by rowFlow.collectAsState(session.index.rows.value.firstOrNull { it.id == id })
    val archivedFlow = remember(session, id) { session.tree.state.map { id in it.archived }.distinctUntilChanged() }
    val archived by archivedFlow.collectAsState(false)
    // Deleted elsewhere (or a stale "last conversation"): back to the list.
    LaunchedEffect(archived) { if (archived && nav.openSessionId == id) nav.openSessionId = null }

    // The chosen model shows at once; dsh's own `modelSelection.next` takes over when it arrives, and a refused change goes back.
    var picked by remember { mutableStateOf<String?>(null) }
    val next = row?.nextModel
    LaunchedEffect(next) { if (next != null && next == picked) picked = null }
    val current = picked ?: next ?: row?.model ?: leadModel
    val models = remember(hasClaudeKey, current) { modelChoices(hasClaudeKey, current) }
    val title = conversation.title?.takeIf { it.isNotBlank() } ?: row?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_untitled)

    val prices = remember { PriceTable() }
    val replyLines = remember(conversation.replyStats) {
        val now = Instant.now()
        conversation.replyStats.mapValues { (_, r) -> replyLine(r, replyCostUsd(prices, r, now)) }
    }
    val usageFlow = remember(session, id) { session.tree.state.map { teamUsage(it, id) }.distinctUntilChanged() }
    val usage by usageFlow.collectAsState(SessionUsage())
    // dsh's tokenUsage/sessionStats projections can be missing (not yet sent, or an older dsh): then the loaded replies' own stats.
    val statsLine = remember(usage, conversation.context, conversation.replyStats) {
        conversationStatsLine(if (usage.isEmpty) usageOfReplies(conversation.replyStats.values) else usage, conversation.context)
    }
    LaunchedEffect(conversation.items) { session.warRoom.observeConversation(conversation) }
    // The team (lead + children) as a flow that only emits real changes: the tree ticks while subagents work (timing), and a
    // streaming chat must not recompose for that. Elapsed time is left out here (the war room shows it).
    val teamFlow = remember(session, id) {
        kotlinx.coroutines.flow.combine(session.tree.state, store.state) { t, c ->
            app.dshmobile.agents.WarRoomBuilder.build(t, id, c, emptyList(), 0, null).map { it.copy(activeMs = null) }
        }.distinctUntilChanged()
    }
    val team by teamFlow.collectAsState(emptyList())
    val lead = team.firstOrNull()
    val activity = when {
        pending.any { it.queued } -> "排隊中：${pending.count { it.queued }} 則等上一輪結束"
        conversation.running || lead?.mood == app.dshmobile.agents.Mood.WAITING ->
            (lead?.action ?: "工作中") + (if (conversation.running && ui.reviewRounds > 0) " · 完成後審查 ${ui.reviewRounds} 輪" else "")
        else -> null
    }
    val speeds = remember(conversation.replyStats, conversation.items) {
        conversation.items.mapNotNull { (it as? app.dshmobile.chat.ChatItem.Assistant)?.let { a -> conversation.replyStats[a.key]?.tokensPerSecond } }
    }
    val loadedCost = remember(conversation.replyStats) {
        val now = Instant.now()
        conversation.replyStats.values.mapNotNull { replyCostUsd(prices, it, now) }.takeIf { it.isNotEmpty() }?.sum()
    }

    // The long-press menu (0.5.0): branches go through dsh's session/fork; 朗讀 through the app's Speaker.
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext as? app.dshmobile.DshMobileApp
    val speaker = appCtx?.speaker
    val speaking = speaker?.speaking?.collectAsState()?.value
    val branchScope = rememberCoroutineScope()
    val branched = stringResource(R.string.msg_branched)
    val noTts = stringResource(R.string.msg_no_tts)
    val outline = remember(session, id) { session.tree.state.map { turnSeqs(it.projections[id]) }.distinctUntilChanged() }.collectAsState(emptyList()).value
    fun branchBoundary(key: String, beforeQuestion: Boolean): Long? =
        if (beforeQuestion) app.dshmobile.chat.Branching.boundaryBefore(conversation.items, key, outline)
        else app.dshmobile.chat.Branching.branchPoint(conversation.items, key)
    suspend fun runBranch(fork: suspend () -> String): String? = try {
        fork().also { session.tree.refreshNow() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        onMessage(appCtx?.getString(R.string.msg_branch_failed, failureCode(e)) ?: failureCode(e))
        null
    }

    val library = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? app.dshmobile.DshMobileApp)?.libraryStore
    val shelf = library?.state?.collectAsState()
    val onShelf = shelf?.value?.books?.any { it.sessionId == id } == true

    ChatScreen(
        state = ChatUiState(
            title, conversation.items, conversation.live, conversation.running, pending, models, conversation.hasMore, sendError,
            followError = followError, olderError = olderError, modelLabel = AgentModels.displayName(current),
            effort = ui.effort, reviewRounds = ui.reviewRounds,
            replyLines = replyLines, contextFill = conversation.context?.fraction, statsLine = statsLine, activity = activity,
        ),
        onAddToShelf = library?.let { lib -> { lib.addBook(title, id, null, current) } },
        onShelf = onShelf,
        onEdit = { key, text -> branchBoundary(key, true)?.let { b -> onEditMessage(id, b, text) } },
        onReanswer = { key ->
            val b = branchBoundary(key, true)
            val question = app.dshmobile.chat.Branching.questionOf(conversation.items, key)
            if (b != null && question != null) branchScope.launch {
                runBranch { session.remote.fork(id, b) }?.let { newId ->
                    nav.openSession(newId)
                    session.conversation(newId).send(listOf(app.dshmobile.remote.PromptPart.Text(question)), question, emptyList())
                }
            }
        },
        onBranch = { key ->
            branchBoundary(key, false)?.let { b -> branchScope.launch { runBranch { session.remote.fork(id, b) }?.let { nav.openSession(it); onMessage(branched) } } }
        },
        onSpeak = { key, text ->
            val sp = speaker
            if (sp != null) { if (speaking == key) sp.stop() else if (!sp.speak(key, text)) onMessage(noTts) }
        },
        speakingKey = speaking,
        onFollowUp = { q -> branchScope.launch { store.send(listOf(app.dshmobile.remote.PromptPart.Text(q)), q, emptyList()) } },
        dataPanel = {
            val root = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? app.dshmobile.DshMobileApp)?.appPaths?.workspace
            val files by androidx.compose.runtime.produceState(emptyList<app.dshmobile.chat.WorkspaceFile>(), root, conversation.items.size) {
                value = if (root == null) emptyList() else kotlinx.coroutines.withContext(Dispatchers.IO) { app.dshmobile.chat.WorkspaceFiles.list(root) }
            }
            ChatDataPanel(
                team, speeds, loadedCost, conversation.context?.fraction, usage.cacheHit,
                conversation.items.filterIsInstance<app.dshmobile.chat.ChatItem.Tool>(),
                files, onShare,
            )
        },
        onOpenList = { nav.listOpen = true },
        onPickModel = { mid ->
            AgentModels.find(mid)?.let { m ->
                picked = mid
                session.scope.launch(Dispatchers.Main.immediate) { if (!store.selectModel(m) && picked == mid) picked = null }
            }
        },
        onLink = onLink,
        onShare = onShare,
        onLoadOlder = { session.scope.launch { store.loadOlder() } },
        onRetryFollow = store::retry,
        onRestartDsh = onRestartDsh,
        onPickEffort = { e ->
            ui.effort = e
            AgentModels.find(current)?.let { m -> session.scope.launch { store.selectModel(m, e ?: "high") } }
        },
        onPickReview = { n -> ui.reviewRounds = n },
        onRetrySend = { rid -> session.scope.launch { store.retrySend(rid) } },
        onDismissSend = store::dismissSend,
        ui = ui,
    )
}

/** 「上下文 23%（46K / 200K）· 本對話 12.3K tok · 平均 45 tok/s · 快取 60%」; null before anything was counted. */
internal fun conversationStatsLine(u: SessionUsage, context: ContextFill?): String? {
    val parts = buildList {
        context?.let { c ->
            val pct = c.fraction?.let { "${(it * 100).roundToInt()}%" }
            add("上下文 " + listOfNotNull(pct, c.window?.let { "（${shortTokens(c.tokens)} / ${shortTokens(it)}）" } ?: shortTokens(c.tokens)).joinToString(""))
        }
        if (!u.isEmpty) {
            add("本對話 ${shortTokens(u.totalTokens)} tok")
            u.tokensPerSecond?.let { add(String.format(Locale.US, "平均 %.0f tok/s", it)) }
            u.cacheHit?.let { add("快取 ${(it * 100).roundToInt()}%") }
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The loaded replies summed as a [SessionUsage] (fallback when dsh's projections are not there). */
internal fun usageOfReplies(replies: Collection<app.dshmobile.chat.ReplyStats>): SessionUsage = replies.fold(SessionUsage()) { a, r ->
    a + SessionUsage(
        inputTokens = r.inputTokens, outputTokens = r.outputTokens, cacheReadTokens = r.cacheReadTokens, turns = 1, steps = r.steps.toLong(),
        ttftMs = r.ttftMs ?: 0, ttftSteps = if (r.ttftMs != null) 1 else 0, decodeMs = r.decodeMs, decodeTokens = r.outputTokens,
    )
}

/** How a send from the dock ended. */
sealed interface DockSend {
    data object Sent : DockSend
    /** No conversation was open and creating one failed. */
    data class NotCreated(val code: String) : DockSend
    /** Reading or uploading an attachment failed; nothing was sent. */
    data class AttachmentFailed(val name: String, val reason: String) : DockSend
    /** dsh refused the prompt; [code] is its [app.dshmobile.chat.ConversationStore.sendError] (the conversation shows it too). */
    data class Refused(val code: String) : DockSend
    /** The open conversation belongs to the other mode ([madeNative]: it was made in native mode); nothing was sent. */
    data class OtherMode(val madeNative: Boolean) : DockSend
}

/**
 * The dock's send: [text] and [attachments] go to the conversation open in the chat tab, or to a new one (created first) when
 * none is open, and that conversation is shown. Attachments are read and uploaded first ([sendWithAttachments]); any failure
 * sends nothing. Main thread (it moves [nav]).
 */
suspend fun sendFromDock(
    session: ShellSession,
    nav: ShellNav,
    text: String,
    attachments: List<PendingAttachment> = emptyList(),
    loader: AttachmentLoader,
    /** The one-shot mode from the ＋ sheet: 計畫 runs dsh's `/plan` first, the others lead the text with their skill. */
    mode: app.dshmobile.chat.ComposeMode = app.dshmobile.chat.ComposeMode.NORMAL,
): DockSend {
    val id = nav.openSessionId ?: try {
        session.index.create()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return DockSend.NotCreated(failureCode(e))
    }
    app.dshmobile.runtime.DshLayout.otherModeOf(session.index.cwdOf(id))?.let { return DockSend.OtherMode(it) }
    if (nav.openSessionId != id || nav.listOpen) nav.openSession(id)
    val store = session.conversation(id)
    if (mode.plan) {
        try {
            session.remote.runCommand(id, "/plan")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DockSend.Refused(failureCode(e))
        }
    }
    return when (val out = sendWithAttachments(store, AttachmentSender(session.remote, loader), mode.apply(text), attachments)) {
        SendOutcome.Sent -> DockSend.Sent
        is SendOutcome.AttachmentFailed -> DockSend.AttachmentFailed(out.name, out.reason)
        is SendOutcome.Refused -> DockSend.Refused(out.code)
    }
}

/** Every turn's prompt seq from dsh's `turnOutline` projection (also for turns paged out of the chat). */
internal fun turnSeqs(p: Map<String, kotlinx.serialization.json.JsonElement>?): List<Long> =
    (p?.get("turnOutline") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
        ((e as? kotlinx.serialization.json.JsonObject)?.get("seq") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
    }
