package app.agency.core.llm

import app.agency.core.metering.Usage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What a call is for; it picks temperature and thinking on DeepSeek. Discussion mode calls use [CHAT]. */
enum class Purpose { INTAKE, UTTERANCE, NUDGE, CHAT }

enum class Thinking { OFF, LOW }

sealed interface Output {
    data object Text : Output

    /** A JSON object reply. [schema] is sent where the provider enforces schemas; elsewhere the prompt carries the example. */
    data class Json(val schema: JsonObject) : Output
}

/** A function the model may call. [parameters] is a strict JSON schema (every property required, no additional properties). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** One call the model asked for. [arguments] is the raw JSON text; the tool layer validates it. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** The wire format an assistant turn was produced in. */
enum class Wire { OPENAI, ANTHROPIC }

/**
 * One entry of the conversation sent to a model. Only text exists: there is deliberately no picture, audio or file part, so a
 * photo cannot reach a model even by mistake (PromptNeverHasMediaTest in T07 and NoMediaInRequestsTest here).
 */
sealed interface Turn {
    data class User(val text: String) : Turn

    /**
     * An assistant line from history (the stored conversation), with no provider payload. On DeepSeek a tools request that
     * contains one is sent with thinking off (it has no `reasoning_content`; see [OpenAiCompatClient]).
     */
    data class AssistantText(val text: String) : Turn

    /**
     * An assistant turn exactly as the provider returned it ([raw]: the whole chat-completion `message` object, or the Messages
     * `content` array, reasoning and thinking included). It is sent back unchanged to DeepSeek and to Claude; a generic
     * compatible service gets it rebuilt as role, content, tool_calls and its `reasoning_content` (no other field); another
     * provider gets [text] and [toolCalls] rebuilt in its own format.
     *
     * Valid only inside the ChatLoop round that produced it (one owner message and the tool steps that answer it). Stored
     * history crosses rounds as [AssistantText], or as context in the system prompt; never keep this turn for a later round.
     * On DeepSeek a tools request whose assistant messages are not all of this kind (with their reasoning) goes out with
     * thinking off, so a caller that wants thinking kept for chat puts its history in the system prompt or the first user
     * message instead of [AssistantText] turns.
     */
    data class Assistant(val wire: Wire, val raw: JsonElement, val text: String?, val toolCalls: List<ToolCall>) : Turn

    /** The result of [ToolCall] [callId]; [content] is the tool's JSON output (or its error), never instructions. */
    data class ToolResult(val callId: String, val content: String, val isError: Boolean = false) : Turn
}

data class ChatRequest(
    val system: String,
    val turns: List<Turn>,
    val purpose: Purpose,
    val maxTokens: Int,
    val tools: List<ToolSpec> = emptyList(),
    val output: Output = Output.Text,
    val thinking: Thinking = Thinking.OFF,
)

sealed interface ChatResult {
    /** [assistant] is the turn to append before the tool results of the next request. */
    data class Ok(val text: String?, val toolCalls: List<ToolCall>, val assistant: Turn.Assistant, val usage: Usage?) : ChatResult

    data class Failed(val fail: Fail) : ChatResult
}
