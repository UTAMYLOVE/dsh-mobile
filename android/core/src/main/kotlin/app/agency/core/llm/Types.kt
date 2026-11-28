package app.agency.core.llm

import app.agency.core.metering.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a call is for; it picks the temperature on DeepSeek when thinking is off. 小葵's conversation uses [CHAT]; [JUDGE] is
 * the second-pass judge; [QUIZ] writes a lesson quiz from its material; [VISION] reads a photo; [SUMMARY] folds old
 * conversation into the rolling summary.
 */
enum class Purpose { INTAKE, UTTERANCE, NUDGE, CHAT, JUDGE, QUIZ, VISION, SUMMARY }

/** Whether the model thinks before it answers. [LOW] turns thinking on (the name is historical); [Effort] says how hard. */
enum class Thinking { OFF, LOW }

/**
 * How much the model may think. Claude takes it as `output_config.effort` on every call; DeepSeek as `reasoning_effort` when
 * thinking is on. [LOW] is the default so a caller that never asked keeps the request it always sent.
 */
enum class Effort(val wire: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }

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
 * One entry of the conversation sent to a model. The one place a picture can enter is [User.images]: photos he attached to the
 * message being answered, already downscaled and stripped of metadata by the app. Tool results and assistant turns are text
 * only, and the clients drop any media block a provider payload might carry (NoMediaInRequestsTest). Never a recording.
 */
sealed interface Turn {
    /**
     * What he said. [images] are cleaned JPEG bytes, sent as image blocks before the text; a client whose model cannot see
     * images fails with [Fail.NO_VISION] instead of dropping them silently.
     */
    data class User(val text: String, val images: List<ByteArray> = emptyList()) : Turn {
        override fun equals(other: Any?): Boolean =
            other is User && other.text == text && other.images.size == images.size &&
                images.indices.all { other.images[it].contentEquals(images[it]) }

        override fun hashCode(): Int = images.fold(text.hashCode()) { h, b -> 31 * h + b.contentHashCode() }

        override fun toString(): String = "User(text=$text, images=${images.size})"
    }

    /**
     * An assistant line from history (the stored conversation), with no provider payload. On DeepSeek a tools request that
     * contains one is sent with thinking off (it has no `reasoning_content`; see [OpenAiCompatClient]).
     */
    data class AssistantText(val text: String) : Turn

    /**
     * An assistant turn exactly as the provider returned it ([raw]: the whole chat-completion `message` object, or the Messages
     * `content` array, reasoning and thinking included). It is sent back unchanged to DeepSeek and to Claude (minus any media
     * block); a generic compatible service gets it rebuilt as role, content, tool_calls and its `reasoning_content` (no other
     * field); another provider gets [text] and [toolCalls] rebuilt in its own format.
     *
     * Valid only inside the loop that produced it (one owner message and the tool steps that answer it); never keep a
     * provider's turn for a later message. The one exception is [Thoughts.replay]: one of the manager's stored replies rebuilt
     * in OpenAI form with the `reasoning_content` DeepSeek returned for it, so a later DeepSeek request can keep thinking on.
     * On DeepSeek a tools request whose assistant messages do not all carry their reasoning goes out with thinking off.
     *
     * Only the clients in `:core` build one from a provider payload (the constructor and `copy` are internal); outside code
     * can only make a [synthetic] one, which carries no payload of its own.
     */
    @ConsistentCopyVisibility
    data class Assistant internal constructor(val wire: Wire, val raw: JsonElement, val text: String?, val toolCalls: List<ToolCall>) : Turn {
        companion object {
            /**
             * An assistant turn no provider returned: [text] and [toolCalls] in OpenAI form, no reasoning, no media. For scripted
             * test clients; every client treats it like any other turn made without thinking.
             */
            fun synthetic(text: String?, toolCalls: List<ToolCall> = emptyList()): Assistant = Assistant(
                Wire.OPENAI,
                buildJsonObject {
                    put("role", "assistant")
                    put("content", text.orEmpty())
                    if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls.map { c ->
                        buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            put("function", buildJsonObject { put("name", c.name); put("arguments", c.arguments) })
                        }
                    }))
                },
                text,
                toolCalls,
            )
        }
    }

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
    val effort: Effort = Effort.LOW,
    /**
     * How many leading [turns] are the stable conversation prefix (the same in the next request): a client that supports
     * prompt caching may mark the end of that prefix. 0 = none.
     */
    val cacheTurns: Int = 0,
)

sealed interface ChatResult {
    /** [assistant] is the turn to append before the tool results of the next request. */
    data class Ok(val text: String?, val toolCalls: List<ToolCall>, val assistant: Turn.Assistant, val usage: Usage?) : ChatResult

    /**
     * [detail] is what the provider actually answered (HTTP status and its own error message, cut short), or why nothing came
     * back. It is shown to him under a message that was not sent, so a failure can be diagnosed from a screenshot. Never a key.
     */
    class Failed(val fail: Fail, val detail: String? = null) : ChatResult {
        /** Two failures are the same when their kind is: [detail] is only for showing. */
        override fun equals(other: Any?): Boolean = other is Failed && other.fail == fail
        override fun hashCode(): Int = fail.hashCode()
        override fun toString(): String = "Failed(fail=$fail)"
    }
}
