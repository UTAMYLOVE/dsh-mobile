package app.agency.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The thinking a provider returned with a reply, and how it goes back later (owner's choice 乙, T12). The shapes are the ones
 * the clients already send and parse (verified at T04 / FIX-T04 / FIX-WAVE3):
 * - OpenAI wire (DeepSeek, a compatible service): the `reasoning_content` string of the assistant `message`. With tools,
 *   DeepSeek needs it on every earlier assistant message of a thinking request, so a stored reply goes back as
 *   `{role: assistant, content, reasoning_content}` ([replay]); [OpenAiCompatClient] echoes that object to DeepSeek and
 *   rebuilds role, content and `reasoning_content` for a compatible service.
 * - Claude: the `thinking` and `redacted_thinking` blocks of the `content` array, signatures included, kept verbatim. They
 *   are not sent into a later round: a block's signature is bound to the exact conversation prefix it was made in (system
 *   prompt, tools and every earlier message), and each round here rebuilds the system prompt with today's facts and a fresh
 *   ten-message window, so a later request would be refused or would drop them. Inside one round the client already sends
 *   the whole `content` back unchanged.
 * Nothing here logs, shows or stores anything; the brain keeps the payload in the encrypted database only.
 */
object Thoughts {
    /** What [t] carries as thinking: (wire, payload), or null when it has none (a reply made with thinking off). */
    fun of(t: Turn.Assistant): Pair<Wire, String>? = when (t.wire) {
        Wire.OPENAI -> ((t.raw as? JsonObject)?.get("reasoning_content") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Wire.OPENAI to it }
        Wire.ANTHROPIC -> (t.raw as? JsonArray)?.filter { b ->
            ((b as? JsonObject)?.get("type") as? JsonPrimitive)?.content in CLAUDE_THINKING
        }?.takeIf { it.isNotEmpty() }?.let { Wire.ANTHROPIC to JsonArray(it).toString() }
    }

    /**
     * A stored reply of an earlier round with its `reasoning_content`, for an OpenAI-wire provider. Another provider gets it
     * rebuilt as plain text (its reasoning stays with the provider that made it).
     */
    fun replay(text: String, reasoning: String): Turn.Assistant = Turn.Assistant(
        Wire.OPENAI,
        buildJsonObject {
            put("role", "assistant")
            put("content", text)
            put("reasoning_content", reasoning)
        },
        text,
        emptyList(),
    )

    private val CLAUDE_THINKING = setOf("thinking", "redacted_thinking")
}
