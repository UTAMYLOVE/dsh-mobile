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

    /** An assistant line from history (the stored conversation), with no provider payload. */
    data class AssistantText(val text: String) : Turn

    /**
     * An assistant turn exactly as the provider returned it ([raw]: the whole chat-completion `message` object, or the Messages
     * `content` array, reasoning and thinking included). It is sent back unchanged to the provider that made it; another
     * provider gets [text] and [toolCalls] rebuilt in its own format.
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
