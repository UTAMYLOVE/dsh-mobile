package app.dshmobile.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A dsh `waterfall` waiting for this user's answer. */
sealed interface PendingPrompt {
    val eventId: String
    val agentId: String

    /** `approval/request`; [reason] is dsh's `displayReason.zh`, else `.en`, else `reason`. */
    data class Approval(
        override val eventId: String,
        override val agentId: String,
        val toolName: String,
        val callId: String?,
        val reason: String,
    ) : PendingPrompt

    /** `user-questions/request` (the model's ask_user_question). */
    data class Question(override val eventId: String, override val agentId: String, val questions: List<AskQuestion>) : PendingPrompt
}

data class AskQuestion(
    val id: String,
    val header: String?,
    val question: String,
    val options: List<String>,
    val multiSelect: Boolean,
    /** Longer text under the question (dsh's plan review puts the whole Markdown plan here). */
    val detail: String? = null,
    /** dsh's plan review (`intent.kind` "plan-review"): [approveLabel] approves, anything else keeps planning. */
    val planReview: Boolean = false,
    val approveLabel: String? = null,
) {
    /** The keep-planning option of a plan review. */
    val keepPlanningLabel: String? get() = options.firstOrNull { it != approveLabel }
}

/** dsh's session lifecycle notifications (`api-session/added|status|removed`). */
sealed interface HostEvent {
    data class SessionAdded(val summary: SessionSummary) : HostEvent
    data class SessionStatus(val sessionId: String, val running: Boolean) : HostEvent
    data class SessionRemoved(val sessionId: String) : HostEvent
}

/**
 * dsh's `$events` stream (docs/dsh-ui-protocol.md §1.4): the `clientId` that answers prompts, the pending approvals and
 * questions in arrival order, and session lifecycle notifications. Answers go out as `$events/result` through [call].
 *
 * Reconnects (planner's call): the `clientId` is cleared as soon as the mux leaves [MuxState.OPEN]. dsh re-delivers a
 * still-pending waterfall with the same `eventId`, so on [StreamSignal.Reopened] every card is kept but marked
 * unconfirmed; a card not re-delivered
 * within [redeliveryGraceMs] of the new `ready` was answered elsewhere and is dropped. A re-delivered card never leaves the
 * list in between.
 */
class DshEvents(
    private val mux: MuxConnection,
    private val call: suspend (String, JsonObject) -> JsonElement,
    private val scope: CoroutineScope,
    private val redeliveryGraceMs: Long = 3_000,
) {
    private val _clientId = MutableStateFlow<String?>(null)
    val clientId: StateFlow<String?> = _clientId.asStateFlow()

    private val _prompts = MutableStateFlow<List<PendingPrompt>>(emptyList())
    val prompts: StateFlow<List<PendingPrompt>> = _prompts.asStateFlow()

    private val _hostEvents = MutableSharedFlow<HostEvent>(extraBufferCapacity = HOST_EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hostEvents: SharedFlow<HostEvent> = _hostEvents.asSharedFlow()

    private val lock = Any()
    /** Cards kept across a reconnect that dsh has not re-delivered yet. Guarded by [lock]. */
    private val unconfirmed = HashSet<String>()
    private var generation = 0L
    private var grace: Job? = null

    init {
        scope.launch {
            while (true) {
                mux.stream(ENDPOINT).collect(::onSignal)
                if (mux.state.value == MuxState.CLOSED) break
                // dsh ended or failed `$events` itself: its clientId is gone with it; open it again.
                connectionLost()
                delay(REOPEN_DELAY_MS)
            }
        }
        // A clientId belongs to one socket: forget it the moment the mux is not OPEN, not only once it reopens.
        scope.launch {
            mux.state.transformWhile { emit(it); it != MuxState.CLOSED }.collect { state ->
                if (state != MuxState.OPEN) synchronized(lock) { if (mux.state.value != MuxState.OPEN) _clientId.value = null }
            }
        }
    }

    suspend fun allowOnce(p: PendingPrompt.Approval) = respond(p, result(JsonPrimitive("allowed-once")))

    suspend fun reject(p: PendingPrompt.Approval) = respond(p, result(JsonPrimitive("rejected")))

    /**
     * [selected]: question id → chosen option labels; a question missing from it is answered with nothing selected. [custom]:
     * question id → free text (dsh's 「其他」; a plan review reads it as the feedback).
     */
    suspend fun answer(p: PendingPrompt.Question, selected: Map<String, List<String>>, custom: Map<String, String> = emptyMap()) = respond(p, result(buildJsonObject {
        put("answers", buildJsonArray {
            for (q in p.questions) add(buildJsonObject {
                put("id", q.id)
                put("selected", buildJsonArray { selected[q.id].orEmpty().forEach { add(JsonPrimitive(it)) } })
                custom[q.id]?.takeIf { it.isNotBlank() }?.let { put("custom", it) }
            })
        })
    }))

    suspend fun decline(p: PendingPrompt.Question) = respond(p, buildJsonObject {
        put("kind", "rejected")
        put("error", buildJsonObject { put("name", "UserDeclined"); put("message", "declined in DSH Mobile") })
    })

    /**
     * Answers [p] (waiting up to [READY_WAIT_MS] for an OPEN mux with a fresh `clientId` while reconnecting; never with a
     * clientId of a socket that is gone) and removes its card. On failure the card stays and [DshRpcException] (code through
     * [DshErrors.safeCode]; `not-connected` after the wait) or an [java.io.IOException] is thrown.
     */
    private suspend fun respond(p: PendingPrompt, outcome: JsonObject) {
        val clientId = withTimeoutOrNull(READY_WAIT_MS) {
            combine(_clientId, mux.state) { id, state -> id.takeIf { state == MuxState.OPEN } }.filterNotNull().first()
        } ?: throw DshRpcException(NOT_CONNECTED, "dsh $RESULT: $NOT_CONNECTED")
        try {
            call(RESULT, buildJsonObject {
                put("clientId", clientId)
                put("eventId", p.eventId)
                put("outcome", outcome)
            })
        } catch (e: DshRpcException) {
            val code = DshErrors.safeCode(e.code)
            throw DshRpcException(code, "dsh $RESULT: $code")
        }
        remove(p.eventId)
    }

    private fun onSignal(signal: StreamSignal) {
        when (signal) {
            StreamSignal.Reopened -> connectionLost()
            is StreamSignal.Value -> (signal.value as? JsonObject)?.let(::onItem)
            is StreamSignal.Failed -> Unit // the flow ends; init reopens it
        }
    }

    private fun onItem(item: JsonObject) {
        when (item.string("type")) {
            "ready" -> item.string("clientId")?.let(::onReady)
            "waterfall" -> parsePrompt(item)?.let(::onPrompt)
            "cancel" -> item.string("eventId")?.let(::remove)
            "emit" -> parseHostEvent(item)?.let { _hostEvents.tryEmit(it) }
        }
    }

    private fun connectionLost() = synchronized(lock) {
        _clientId.value = null
        generation++
        grace?.cancel()
        grace = null
        _prompts.value.forEach { unconfirmed.add(it.eventId) }
    }

    private fun onReady(clientId: String) = synchronized(lock) {
        if (mux.state.value != MuxState.OPEN) return // a ready from a socket that is already gone
        _clientId.value = clientId
        if (unconfirmed.isEmpty()) return
        val gen = generation
        grace?.cancel()
        grace = scope.launch {
            delay(redeliveryGraceMs)
            dropUnconfirmed(gen)
        }
    }

    private fun dropUnconfirmed(gen: Long) = synchronized(lock) {
        if (gen != generation) return
        val stale = unconfirmed.toSet()
        unconfirmed.clear()
        grace = null
        _prompts.update { list -> list.filterNot { it.eventId in stale } }
    }

    private fun onPrompt(prompt: PendingPrompt) = synchronized(lock) {
        unconfirmed.remove(prompt.eventId)
        _prompts.update { list ->
            val at = list.indexOfFirst { it.eventId == prompt.eventId }
            if (at < 0) list + prompt else list.toMutableList().also { it[at] = prompt }
        }
    }

    private fun remove(eventId: String) = synchronized(lock) {
        unconfirmed.remove(eventId)
        _prompts.update { list -> list.filterNot { it.eventId == eventId } }
    }

    private companion object {
        const val ENDPOINT = "\$events"
        const val RESULT = "\$events/result"
        const val NOT_CONNECTED = "not-connected"
        const val READY_WAIT_MS = 5_000L
        const val REOPEN_DELAY_MS = 1_000L
        const val HOST_EVENT_BUFFER = 256

        fun result(value: JsonElement) = buildJsonObject {
            put("kind", "result")
            put("value", value)
        }

        fun parsePrompt(item: JsonObject): PendingPrompt? {
            val eventId = item.string("eventId")?.takeIf { it.isNotEmpty() } ?: return null
            val agentId = item.string("agentId").orEmpty()
            val request = item.obj("request") ?: JsonObject(emptyMap())
            return when (item.string("event")) {
                "approval/request" -> {
                    val display = request.obj("displayReason")
                    PendingPrompt.Approval(
                        eventId = eventId,
                        agentId = agentId,
                        toolName = request.string("toolName").orEmpty(),
                        callId = request.string("callId"),
                        reason = display?.string("zh")?.takeIf { it.isNotBlank() }
                            ?: display?.string("en")?.takeIf { it.isNotBlank() }
                            ?: request.string("reason").orEmpty(),
                    )
                }
                "user-questions/request" -> PendingPrompt.Question(
                    eventId, agentId,
                    (request["questions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseQuestion) },
                )
                else -> null // a waterfall stage 1 cannot answer: leave it to another client
            }
        }

        fun parseQuestion(q: JsonObject): AskQuestion? {
            val id = q.string("id")?.takeIf { it.isNotEmpty() } ?: return null
            return AskQuestion(
                id = id,
                header = q.string("header"),
                question = q.string("question").orEmpty(),
                options = (q["options"] as? JsonArray).orEmpty().mapNotNull { option ->
                    (option as? JsonObject)?.string("label") ?: (option as? JsonPrimitive)?.takeIf { it.isString }?.content
                },
                multiSelect = (q["multiSelect"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false,
                detail = q.string("detail")?.takeIf { it.isNotBlank() },
                planReview = (q["intent"] as? JsonObject)?.string("kind") == "plan-review",
                approveLabel = (q["intent"] as? JsonObject)?.string("approve"),
            )
        }

        fun parseHostEvent(item: JsonObject): HostEvent? {
            val args = item["args"] as? JsonArray ?: return null
            val first = args.firstOrNull()
            return when (item.string("event")) {
                "api-session/added" -> (first as? JsonObject)?.let(SessionSummary::from)?.let(HostEvent::SessionAdded)
                "api-session/status" -> {
                    val id = sessionIdOf(first) ?: return null
                    val running = (args.getOrNull(1) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                        ?: ((first as? JsonObject)?.get("running") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                        ?: return null
                    HostEvent.SessionStatus(id, running)
                }
                "api-session/removed" -> sessionIdOf(first)?.let(HostEvent::SessionRemoved)
                else -> null
            }
        }

        /** `"id"` or `{sessionId:"id"}`. */
        fun sessionIdOf(arg: JsonElement?): String? =
            ((arg as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (arg as? JsonObject)?.string("sessionId"))?.takeIf { it.isNotEmpty() }
    }
}
