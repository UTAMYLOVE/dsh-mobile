package app.agency.core.structured

import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Turn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One thing wrong with a model output: [field] is the JSON path (`items[2].name`, or `整體`), [reason] says what to fix. */
data class Violation(val field: String, val reason: String)

/** A decoded output, or why it could not be used. [value] is null only when [violations] is not empty. */
data class Decoded<out T>(val value: T?, val violations: List<Violation> = emptyList())

/** The caller's verdict on a decoded value: keep it (possibly changed, e.g. guarded text), or send the reasons back. */
sealed interface Checked<out T> {
    data class Accept<T>(val value: T) : Checked<T>
    data class Reject(val violations: List<Violation>) : Checked<Nothing>
}

/** What a structured output looks like and how code reads it. */
interface StructuredSpec<T : Any> {
    /** Strict schema (sent where the provider enforces schemas). */
    val schema: JsonObject

    /** Values put in for top-level keys the reply left out (the "fill optional nulls" step of the local repair). */
    val defaults: Map<String, JsonElement>

    fun decode(o: JsonObject): Decoded<T>
}

/** The end of a structured call: a model value that passed every check, or the template path. */
sealed interface Structured<out T> {
    val attempts: Int

    data class Model<T>(val value: T, override val attempts: Int) : Structured<T>

    /**
     * No usable model value. [value] is the caller's fallback (null when it has none); [fail] is set when the provider gave no
     * answer at all; [violations] are the last reasons sent to (or found in) the model.
     */
    data class Template<T>(val value: T?, override val attempts: Int, val fail: Fail?, val violations: List<Violation>) : Structured<T>
}

/** Pulls the one JSON object out of a reply: no second model ever "fixes" JSON. */
object LocalRepair {
    private val FENCE = Regex("```[A-Za-z]*\\s*([\\s\\S]*?)```")

    /**
     * The outermost JSON object in [text] (inside a code fence if there is one), found by matching braces outside strings.
     * Null when no object closes: a reply cut off by the token limit is incomplete and never taken as whole.
     */
    fun objectText(text: String): String? {
        val t = FENCE.find(text)?.groupValues?.get(1) ?: text
        val start = t.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until t.length) {
            val c = t[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return t.substring(start, i + 1)
            }
        }
        return null
    }

    /** [text]'s object with every key of [defaults] it lacks filled in; null when there is no complete, valid object. */
    fun parse(text: String, defaults: Map<String, JsonElement>): JsonObject? {
        val s = objectText(text) ?: return null
        val o = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return null
        val missing = defaults.filterKeys { it !in o }
        return if (missing.isEmpty()) o else JsonObject(o + missing)
    }
}

/**
 * Structured output with retry-with-reason (Pydantic-AI style): call → local repair → decode → the caller's check; on
 * violations the model's output and one user turn 「上一個輸出不合格：<field>：<reason>；… 只修正這些地方，重新輸出 JSON」 are
 * appended and the call is made again, at most [MAX_RETRIES] times; then the caller's template. An empty or unreadable reply
 * (BAD_RESPONSE, an empty DeepSeek JSON reply included) counts as one failed attempt; every other failure (no key, spend cap,
 * offline, refusal…) goes to the template at once, since asking again cannot help.
 *
 * [provider] is read per call for the token floor: thinking cannot be turned off on the Claude models, so a small budget
 * (a nudge) could be used up by thinking; Claude calls get at least [CLAUDE_MIN_TOKENS].
 */
class StructuredCaller(private val llm: LlmClient, private val provider: () -> ProviderChoice) {
    suspend fun <T : Any> call(
        spec: StructuredSpec<T>,
        system: String,
        turns: List<Turn>,
        purpose: Purpose,
        maxTokens: Int,
        check: (T) -> Checked<T> = { Checked.Accept(it) },
        fallback: () -> T?,
    ): Structured<T> {
        var convo = turns
        var last = emptyList<Violation>()
        val tokens = maxTokensFor(provider(), maxTokens)
        for (attempt in 1..1 + MAX_RETRIES) {
            val request = ChatRequest(system, convo, purpose, tokens, output = Output.Json(spec.schema), thinking = Thinking.OFF)
            val reply = when (val r = llm.chat(request)) {
                is ChatResult.Failed -> {
                    if (r.fail != Fail.BAD_RESPONSE) return Structured.Template(fallback(), attempt, r.fail, last)
                    null
                }
                is ChatResult.Ok -> r.text?.takeIf { it.isNotBlank() }
            }
            last = if (reply == null) {
                listOf(EMPTY)
            } else {
                when (val outcome = evaluate(spec, reply, check)) {
                    is Checked.Accept -> return Structured.Model(outcome.value, attempt)
                    is Checked.Reject -> outcome.violations.ifEmpty { listOf(NOT_JSON) }
                }
            }
            if (attempt <= MAX_RETRIES) {
                convo = convo + listOfNotNull(reply?.let { Turn.AssistantText(it.take(ECHO_CAP)) }) + Turn.User(retryText(last))
            }
        }
        return Structured.Template(fallback(), 1 + MAX_RETRIES, null, last)
    }

    private fun <T : Any> evaluate(spec: StructuredSpec<T>, reply: String, check: (T) -> Checked<T>): Checked<T> {
        val o = LocalRepair.parse(reply, spec.defaults) ?: return Checked.Reject(listOf(NOT_JSON))
        val decoded = runCatching { spec.decode(o) }.getOrElse { Decoded(null, listOf(NOT_JSON)) }
        val value = decoded.value
        if (value == null || decoded.violations.isNotEmpty()) return Checked.Reject(decoded.violations.ifEmpty { listOf(NOT_JSON) })
        return check(value)
    }

    companion object {
        const val MAX_RETRIES = 2
        const val CLAUDE_MIN_TOKENS = 1024
        private const val ECHO_CAP = 2000
        private const val MAX_LISTED = 8

        val EMPTY = Violation("整體", "輸出是空的")
        val NOT_JSON = Violation("整體", "不是一個完整的 JSON 物件")

        fun maxTokensFor(choice: ProviderChoice, wanted: Int): Int =
            if (choice == ProviderChoice.CLAUDE) maxOf(wanted, CLAUDE_MIN_TOKENS) else wanted

        fun retryText(violations: List<Violation>): String =
            "上一個輸出不合格：" + violations.take(MAX_LISTED).joinToString("；") { "${it.field}：${it.reason}" } +
                "。只修正這些地方，重新輸出 JSON。"
    }
}
