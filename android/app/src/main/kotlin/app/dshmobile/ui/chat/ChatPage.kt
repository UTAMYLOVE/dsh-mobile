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
) {
    val openId = nav.openSessionId
    if (nav.listOpen || openId == null) {
        ListPage(session, nav, onMessage)
    } else {
        key(session, openId) { ConversationPage(session, openId, nav, hasClaudeKey, leadModel, onLink, onShare, onRestartDsh) }
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
    val statsLine = remember(usage, conversation.context) { conversationStatsLine(usage, conversation.context) }

    ChatScreen(
        state = ChatUiState(
            title, conversation.items, conversation.live, conversation.running, pending, models, conversation.hasMore, sendError,
            followError = followError, olderError = olderError, modelLabel = AgentModels.displayName(current),
            effort = ui.effort, reviewRounds = ui.reviewRounds,
            replyLines = replyLines, contextFill = conversation.context?.fraction, statsLine = statsLine,
        ),
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

/** How a send from the dock ended. */
sealed interface DockSend {
    data object Sent : DockSend
    /** No conversation was open and creating one failed. */
    data class NotCreated(val code: String) : DockSend
    /** Reading or uploading an attachment failed; nothing was sent. */
    data class AttachmentFailed(val name: String, val reason: String) : DockSend
    /** dsh refused the prompt; [code] is its [app.dshmobile.chat.ConversationStore.sendError] (the conversation shows it too). */
    data class Refused(val code: String) : DockSend
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
): DockSend {
    val id = nav.openSessionId ?: try {
        session.index.create()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return DockSend.NotCreated(failureCode(e))
    }
    if (nav.openSessionId != id || nav.listOpen) nav.openSession(id)
    val store = session.conversation(id)
    return when (val out = sendWithAttachments(store, AttachmentSender(session.remote, loader), text, attachments)) {
        SendOutcome.Sent -> DockSend.Sent
        is SendOutcome.AttachmentFailed -> DockSend.AttachmentFailed(out.name, out.reason)
        is SendOutcome.Refused -> DockSend.Refused(out.code)
    }
}
