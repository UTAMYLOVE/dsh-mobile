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
import kotlinx.coroutines.flow.Flow
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
data class PendingSend(val requestId: String, val text: String, val attachments: List<String>, val queued: Boolean)

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

    /** A [DshErrors.safeCode] (an exception class name for non-dsh failures); the screen words it. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    // Only touched on reduceContext.
    private var liveText = StringBuilder()
    private var liveReasoning = StringBuilder()
    private var liveAttempt: String? = null
    private var liveTools = emptyList<String>()
    private var publishJob: Job? = null

    private val job: Job = scope.launch(reduceContext) {
        remote.follow(sessionId).collect { signal ->
            when (signal) {
                is StreamSignal.Value -> onFrame(signal.value)
                StreamSignal.Reopened -> {
                    dropLive()
                    _state.update { ConversationReducer.reopened(it) }
                }
                is StreamSignal.Failed -> {}
            }
        }
    }

    private fun onFrame(frame: JsonElement?) {
        val o = frame as? JsonObject
        when ((o?.get("type") as? JsonPrimitive)?.content) {
            "assistant-stream" -> onStream(o?.get("frame") as? JsonObject)
            else -> {
                if (o != null && (o["type"] as? JsonPrimitive)?.content == "snapshot") dropLive()
                _state.update { ConversationReducer.reduce(it, frame) }
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
                dropLive()
                _state.update { it.copy(live = null) }
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

    private fun dropLive() {
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
        if (_pending.value.any { it.requestId in accepted }) _pending.update { list -> list.filter { it.requestId !in accepted } }
    }

    /** Always `mode:"queue"`. False when dsh refused it (the bubble is removed and [sendError] holds the code). */
    suspend fun send(parts: List<PromptPart>, displayText: String, attachmentNames: List<String>): Boolean {
        val requestId = newRequestId()
        _sendError.value = null
        _pending.update { it + PendingSend(requestId, displayText, attachmentNames, queued = _state.value.running) }
        return try {
            remote.prompt(sessionId, requestId, parts)
            true
        } catch (e: CancellationException) {
            _pending.update { list -> list.filter { it.requestId != requestId } }
            throw e
        } catch (e: Exception) {
            _pending.update { list -> list.filter { it.requestId != requestId } }
            report(e)
            false
        }
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

    /** Older history: `session/page` before [ConversationState.firstSeq], 200 messages at a time. */
    suspend fun loadOlder() {
        val s = _state.value
        val before = s.firstSeq ?: return
        if (!s.hasMore) return
        try {
            val page = remote.page(sessionId, before, PAGE_MESSAGES)
            withContext(reduceContext) { _state.update { ConversationReducer.prepend(it, page) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    suspend fun selectModel(model: AgentModels.Model) {
        try {
            remote.selectModel(sessionId, model.provider, model.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    fun close() {
        job.cancel()
        publishJob?.cancel()
    }

    private fun report(e: Exception) {
        _sendError.value = if (e is DshRpcException) DshErrors.safeCode(e.code) else e.javaClass.simpleName.ifEmpty { DshErrors.REFUSED }
    }

    private companion object {
        const val PUBLISH_MS = 50L
        const val PAGE_MESSAGES = 200
    }
}
