package app.dshmobile.chat

import app.dshmobile.config.AgentModels
import app.dshmobile.remote.DshErrors
import app.dshmobile.remote.DshRpcException
import app.dshmobile.remote.PromptPart
import app.dshmobile.remote.StreamSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** The calls the chat needs from dsh; [app.dshmobile.remote.DshRemote] implements it. */
interface ChatRemote {
    fun follow(sessionId: String): Flow<StreamSignal>
    suspend fun prompt(sessionId: String, requestId: String, parts: List<PromptPart>)
    suspend fun cancel(sessionId: String)
    suspend fun page(sessionId: String, beforeSeq: Long, maxMessages: Int): JsonElement
    suspend fun selectModel(sessionId: String, provider: String, model: String)
    suspend fun upload(sessionId: String, name: String, bytes: ByteArray): String
}

/** A prompt shown as a bubble until dsh records it as a user message. [queued]: a turn was running when it was sent. */
data class PendingSend(
    val requestId: String,
    val text: String,
    val attachments: List<String>,
    val queued: Boolean,
    /** dsh never recorded the message within [ConversationStore.PENDING_TIMEOUT_MS]; offer [ConversationStore.retrySend]. */
    val failed: Boolean = false,
)

/**
 * One conversation: follows `session/follow` and folds it into [state] with [ConversationReducer]. Frames are reduced on
 * one single-threaded [reduceContext]; live text deltas go into a StringBuilder per reply and reach [state] at most every
 * [PUBLISH_MS] ms, so a 100k-character reply is neither quadratic nor a flood of state changes.
 */
class ConversationStore(
    private val remote: ChatRemote,
    val sessionId: String,
    private val scope: CoroutineScope,
    private val newRequestId: () -> String = { "dshm-" + UUID.randomUUID() },
    @OptIn(ExperimentalCoroutinesApi::class)
    private val reduceContext: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) {
    private val _state = MutableStateFlow(ConversationState(sessionId))
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingSend>>(emptyList())
    val pending: StateFlow<List<PendingSend>> = _pending.asStateFlow()

    /** A [DshErrors.safeCode] (an exception class name for non-dsh failures) of the last send, stop or model change; the screen words it. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    // Only touched on reduceContext.
    private var liveText = StringBuilder()
    private var liveReasoning = StringBuilder()
    private var liveAttempt: String? = null
    private var liveTools = emptyList<String>()
    private var publishJob: Job? = null
    /** A committed reply's outcome seq: its live text stays up until that `assistant/message` is in the state (bounded). */
    private var heldSeq: Long? = null
    private var heldJob: Job? = null

    /** Why the follow stream is not delivering: a [DshErrors.safeCode], `ended`, or an exception class name; null when fine. */
    private val _followError = MutableStateFlow<String?>(null)
    val followError: StateFlow<String?> = _followError.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val timeouts = ConcurrentHashMap<String, Job>()
    private val sent = ConcurrentHashMap<String, List<PromptPart>>()

    private val job: Job = scope.launch(reduceContext) { followLoop() }

    /** Follows; whenever the stream fails, ends or throws, sets [followError] and follows again after a backoff. */
    private suspend fun followLoop() {
        var attempt = 0
        while (true) {
            var failed: String? = null
            try {
                remote.follow(sessionId).collect { signal ->
                    when (signal) {
                        is StreamSignal.Value -> { attempt = 0; _followError.value = null; onFrame(signal.value) }
                        StreamSignal.Reopened -> {
                            dropLive()
                            _state.update { ConversationReducer.reopened(it) }
                        }
                        is StreamSignal.Failed -> { failed = signal.code; _followError.value = signal.code }
                    }
                }
                if (failed == null) _followError.value = ENDED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _followError.value = errorName(e)
            }
            dropLive()
            _state.update { ConversationReducer.reopened(it) }
            val wait = BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)]
            attempt++
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** Follow again now instead of waiting out the backoff (no-op while the follow is healthy). */
    fun retry() {
        if (_followError.value != null) wake.trySend(Unit)
    }

    private fun onFrame(frame: JsonElement?) {
        val o = frame as? JsonObject
        when ((o?.get("type") as? JsonPrimitive)?.content) {
            "assistant-stream" -> onStream(o?.get("frame") as? JsonObject)
            else -> {
                if (o != null && (o["type"] as? JsonPrimitive)?.content == "snapshot") dropLive()
                _state.update { ConversationReducer.reduce(it, frame) }
                releaseHeld()
                dropAccepted()
            }
        }
    }

    private fun onStream(f: JsonObject?) {
        if (f == null) return
        when ((f["type"] as? JsonPrimitive)?.content) {
            "start" -> {
                val id = (f["attemptId"] as? JsonPrimitive)?.content ?: return
                dropLive()
                liveAttempt = id
                publishLive()
            }
            "end" -> {
                // The whole text, not the last throttled publish, is what stays up.
                publishLive()
                dropLive()
                val outcome = f["outcome"] as? JsonObject
                val seq = (outcome?.get("seq") as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
                val committed = (outcome?.get("kind") as? JsonPrimitive)?.content == "committed"
                if (committed && seq != null && (_state.value.lastSeq ?: Long.MIN_VALUE) < seq) {
                    // Keep the reply on screen until dsh's durable message for it arrives, so it never blinks out. The next
                    // baseline, a new reply or a reconnect drops it as well (dropLive); after HOLD_MS it goes anyway.
                    heldSeq = seq
                    heldJob = scope.launch(reduceContext) {
                        delay(HOLD_MS)
                        if (heldSeq == seq) {
                            heldSeq = null
                            heldJob = null
                            _state.update { it.copy(live = null) }
                        }
                    }
                } else {
                    _state.update { it.copy(live = null) }
                }
            }
            "chunk" -> {
                val attempt = liveAttempt ?: return
                val given = (f["attemptId"] as? JsonPrimitive)?.content
                if (given != null && given != attempt) return
                val chunk = f["chunk"] as? JsonObject ?: return
                val text = (chunk["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                when ((chunk["type"] as? JsonPrimitive)?.content) {
                    "text-delta" -> if (text != null) { liveText.append(text); schedulePublish() }
                    "reasoning-delta" -> if (text != null) { liveReasoning.append(text); schedulePublish() }
                    "tool-call-delta" -> {
                        val name = (chunk["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (name != null && name !in liveTools) { liveTools = liveTools + name; schedulePublish() }
                    }
                }
            }
        }
    }

    /** The held reply goes once the state holds its message. */
    private fun releaseHeld() {
        val seq = heldSeq ?: return
        if ((_state.value.lastSeq ?: Long.MIN_VALUE) >= seq) {
            heldSeq = null
            heldJob?.cancel()
            heldJob = null
            _state.update { it.copy(live = null) }
        }
    }

    private fun dropLive() {
        heldSeq = null
        heldJob?.cancel()
        heldJob = null
        liveAttempt = null
        liveText = StringBuilder()
        liveReasoning = StringBuilder()
        liveTools = emptyList()
        publishJob?.cancel()
        publishJob = null
    }

    private fun schedulePublish() {
        if (publishJob != null) return
        publishJob = scope.launch(reduceContext) {
            delay(PUBLISH_MS)
            publishJob = null
            publishLive()
        }
    }

    private fun publishLive() {
        val id = liveAttempt ?: return
        val reply = LiveReply(id, liveReasoning.toString(), liveText.toString(), liveTools)
        _state.update { it.copy(live = reply) }
    }

    private fun dropAccepted() {
        val accepted = _state.value.acceptedRequestIds
        val done = _pending.value.filter { it.requestId in accepted }
        if (done.isNotEmpty()) {
            _pending.update { list -> list.filter { it.requestId !in accepted } }
            done.forEach { forget(it.requestId) }
        }
    }

    /** Always `mode:"queue"`. False when dsh refused it (the bubble is removed and [sendError] holds the code). */
    suspend fun send(parts: List<PromptPart>, displayText: String, attachmentNames: List<String>): Boolean {
        val requestId = newRequestId()
        _sendError.value = null
        _pending.update { it + PendingSend(requestId, displayText, attachmentNames, queued = _state.value.running) }
        sent[requestId] = parts
        timeouts[requestId] = scope.launch(reduceContext) {
            // Only an idle conversation can time out: a queued prompt is recorded when its turn dequeues it, so the
            // clock is paused while a turn runs and restarts when it ends.
            while (true) {
                _state.first { !it.running }
                if (withTimeoutOrNull(PENDING_TIMEOUT_MS) { _state.first { it.running } } == null) break
            }
            _pending.update { list -> list.map { if (it.requestId == requestId) it.copy(failed = true) else it } }
        }
        return try {
            remote.prompt(sessionId, requestId, parts)
            true
        } catch (e: CancellationException) {
            _pending.update { list -> list.filter { it.requestId != requestId } }
            forget(requestId)
            throw e
        } catch (e: Exception) {
            _pending.update { list -> list.filter { it.requestId != requestId } }
            forget(requestId)
            report(e)
            false
        }
    }

    private fun forget(requestId: String) {
        timeouts.remove(requestId)?.cancel()
        sent.remove(requestId)
    }

    /** Sends a timed-out bubble again under a new request id (the old bubble is removed). False when it was refused. */
    suspend fun retrySend(requestId: String): Boolean {
        val bubble = _pending.value.firstOrNull { it.requestId == requestId && it.failed } ?: return false
        val parts = sent[requestId] ?: return false
        _pending.update { list -> list.filter { it.requestId != requestId } }
        forget(requestId)
        return send(parts, bubble.text, bubble.attachments)
    }

    /** Drops a timed-out bubble. */
    fun dismissSend(requestId: String) {
        _pending.update { list -> list.filter { it.requestId != requestId } }
        forget(requestId)
    }

    suspend fun stop() {
        try {
            remote.cancel(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    /** Why the last [loadOlder] failed (a [DshErrors.safeCode], `unexpected-answer` or an exception class name); null after a success. */
    private val _olderError = MutableStateFlow<String?>(null)
    val olderError: StateFlow<String?> = _olderError.asStateFlow()

    /** Older history: `session/page` before [ConversationState.firstSeq], 200 messages at a time. A failure goes to [olderError]. */
    suspend fun loadOlder() {
        val s = _state.value
        val before = s.firstSeq ?: return
        if (!s.hasMore) return
        try {
            val page = remote.page(sessionId, before, PAGE_MESSAGES)
            if ((page as? JsonObject)?.get("records") !is JsonArray) {
                _olderError.value = DshErrors.UNEXPECTED_ANSWER
                return
            }
            withContext(reduceContext) {
                _state.update { ConversationReducer.prepend(it, page) }
                // A page is as good as a baseline for a reply still held up: let it go (bounded wait).
                if (heldSeq != null) {
                    heldSeq = null
                    heldJob?.cancel()
                    heldJob = null
                    _state.update { it.copy(live = null) }
                }
            }
            _olderError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _olderError.value = errorName(e)
        }
    }

    /** False when dsh refused it ([sendError] holds the code). */
    suspend fun selectModel(model: AgentModels.Model): Boolean = try {
        remote.selectModel(sessionId, model.provider, model.id)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
        false
    }

    fun close() {
        job.cancel()
        publishJob?.cancel()
        heldJob?.cancel()
        timeouts.values.forEach { it.cancel() }
    }

    private fun report(e: Exception) {
        _sendError.value = errorName(e)
    }

    private fun errorName(e: Exception): String =
        if (e is DshRpcException) DshErrors.safeCode(e.code) else e.javaClass.simpleName.ifEmpty { DshErrors.REFUSED }

    companion object {
        const val PENDING_TIMEOUT_MS = 60_000L
        private const val ENDED = "ended"
        private val BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L)
        private const val PUBLISH_MS = 50L
        private const val HOLD_MS = 10_000L
        private const val PAGE_MESSAGES = 200
    }
}
