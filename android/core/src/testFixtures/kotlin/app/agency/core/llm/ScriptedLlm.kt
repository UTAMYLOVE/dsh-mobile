package app.agency.core.llm

import java.util.concurrent.atomic.AtomicInteger

/**
 * An [LlmClient] that answers from a script, for tests and the simulation. Each call takes the next step of the queue; every
 * request is recorded in [requests]. Running out of steps is a test failure (an [IllegalStateException]), unless a [fallback]
 * is given.
 *
 * ```
 * val llm = ScriptedLlm(calls("log_water", """{"bottles":1}"""), says("記好了。"))
 * ```
 */
class ScriptedLlm(
    vararg steps: (ChatRequest) -> ChatResult,
    private val fallback: ((ChatRequest) -> ChatResult)? = null,
) : LlmClient {
    private val queue = ArrayDeque(steps.toList())
    private val _requests = ArrayList<ChatRequest>()

    /** Every request this client received, in order. */
    val requests: List<ChatRequest> get() = synchronized(this) { _requests.toList() }

    /** Steps not used yet. */
    val remaining: Int get() = synchronized(this) { queue.size }

    fun then(vararg more: (ChatRequest) -> ChatResult): ScriptedLlm = apply { synchronized(this) { queue.addAll(more) } }

    override suspend fun chat(request: ChatRequest): ChatResult {
        val step = synchronized(this) {
            _requests += request
            queue.removeFirstOrNull()
        } ?: fallback ?: error("ScriptedLlm ran out of steps at request ${requests.size}")
        return step(request)
    }

    companion object {
        private val ids = AtomicInteger()

        /** A plain text answer. */
        fun says(text: String): (ChatRequest) -> ChatResult = { ok(text, emptyList()) }

        /** One tool call (optionally with some text before it). */
        fun calls(tool: String, json: String = "{}", text: String? = null): (ChatRequest) -> ChatResult =
            { ok(text, listOf(ToolCall("call_${ids.incrementAndGet()}", tool, json))) }

        /** Several tool calls in one step: pairs of (tool, json). */
        fun callsAll(vararg calls: Pair<String, String>): (ChatRequest) -> ChatResult =
            { ok(null, calls.map { (n, j) -> ToolCall("call_${ids.incrementAndGet()}", n, j) }) }

        fun fails(fail: Fail): (ChatRequest) -> ChatResult = { ChatResult.Failed(fail) }

        fun ok(text: String?, calls: List<ToolCall>): ChatResult =
            ChatResult.Ok(text, calls, Turn.Assistant.synthetic(text, calls), null)
    }
}
