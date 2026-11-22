package app.agency.core.brain

import app.agency.core.engine.Tone
import app.agency.core.guard.Evidence
import app.agency.core.guard.SecondPassJudge
import app.agency.core.guard.Verdict
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Turn
import app.agency.core.model.MessageMode
import app.agency.core.model.RoleKey
import app.agency.core.structured.Checked
import app.agency.core.structured.LocalRepair
import app.agency.core.structured.StructuredCaller
import app.agency.core.structured.Utterance
import app.agency.core.tools.ToolRegistry

/**
 * One owner message in chat: the prompt ([system] and [turns], built by PromptBuilder), who answers ([role]) in which [mode]
 * and [tone], and the evidence the guard checks against, rebuilt with the outputs of the tools called so far ([evidence]).
 * [judgeContext] is the code-written context line for the second-pass judge (arch 1.9b), or null when this turn needs no
 * judge ([SecondPassJudge.contextLine]).
 */
class ChatRound(
    val system: String,
    val turns: List<Turn>,
    val role: RoleKey,
    val mode: MessageMode,
    val tone: Tone,
    val judgeContext: String? = null,
    val evidence: (toolOutputs: List<String>) -> Evidence,
)

/**
 * How a round ended. [utterance] has passed the guard when [fromModel]; otherwise it is the role's template, and [fail] says
 * why when the provider gave no answer at all. [steps] counts the writing model's calls, [toolCalls] the calls the tools
 * answered, [judged] the second-pass judge's calls.
 */
data class LoopReply(
    val utterance: Utterance,
    val fromModel: Boolean,
    val fail: Fail?,
    val steps: Int,
    val toolCalls: Int,
    val judged: Int = 0,
)

/**
 * The chat tool loop (arch 1.3 step 4b): at most [maxSteps] model calls, ending in one Utterance that passes the output guard.
 * Within a round `system`, `tools` and the output schema are the same on every step (Claude binds preserved thinking to the
 * exact prefix), and every assistant turn goes back as the provider returned it ([Turn.Assistant], valid only inside this
 * round). A call that repeats an earlier one (same tool, same arguments) is not run again; a step made only of repeats ends the
 * round on the template. A reply that is not a usable Utterance, or that the guard rejects, is sent back with its reasons at
 * most [StructuredCaller.MAX_RETRIES] times (an empty reply counts too); then the template. Any other failure (no key, the
 * spend cap, offline, refused…) ends the round on the template at once.
 *
 * A reply the guard accepted in a round with a [ChatRound.judgeContext] goes to [judge] (arch 1.9b). A breach is sent back
 * like a guard rejection and uses the same retries; no answer from the judge keeps the guarded reply.
 */
class ChatLoop(
    private val llm: LlmClient,
    private val provider: () -> ProviderChoice,
    private val maxSteps: Int = MAX_STEPS,
    private val judge: SecondPassJudge? = null,
) {
    suspend fun run(round: ChatRound, tools: ToolRegistry): LoopReply {
        var convo = round.turns
        val outputs = mutableListOf<String>()
        val seen = mutableSetOf<Pair<String, String>>()
        var retries = 0
        var called = 0
        var judged = 0
        val tokens = StructuredCaller.maxTokensFor(provider(), MAX_TOKENS)
        fun template(fail: Fail?, steps: Int) = LoopReply(Utterance.template(round.role, round.tone), false, fail, steps, called, judged)

        for (step in 1..maxSteps) {
            val request = ChatRequest(
                system = round.system, turns = convo, purpose = Purpose.CHAT, maxTokens = tokens, tools = tools.specs,
                output = Output.Json(Utterance.SCHEMA), thinking = Thinking.LOW,
            )
            val result = llm.chat(request)
            if (result is ChatResult.Failed) {
                if (result.fail != Fail.BAD_RESPONSE || retries >= StructuredCaller.MAX_RETRIES) return template(result.fail, step)
                retries++
                convo = convo + Turn.User(StructuredCaller.retryText(listOf(StructuredCaller.EMPTY)))
                continue
            }
            val ok = result as ChatResult.Ok
            if (ok.toolCalls.isNotEmpty()) {
                if (ok.toolCalls.all { (it.name to it.arguments.trim()) in seen }) return template(null, step)
                val answers = ok.toolCalls.map { c ->
                    val key = c.name to c.arguments.trim()
                    if (!seen.add(key)) {
                        Turn.ToolResult(c.id, REPEATED, isError = true)
                    } else {
                        called++
                        tools.execute(c).also { if (!it.isError) outputs += it.content }
                    }
                }
                convo = convo + ok.assistant + answers
                continue
            }
            when (val checked = evaluate(ok.text.orEmpty(), round, outputs)) {
                is Checked.Accept -> {
                    val context = round.judgeContext
                    val verdict = if (judge != null && context != null) {
                        judged++
                        judge.judge(visible(checked.value), context)
                    } else {
                        null
                    }
                    if (verdict !is Verdict.Breach) return LoopReply(checked.value, true, null, step, called, judged)
                    if (retries >= StructuredCaller.MAX_RETRIES) return template(null, step)
                    retries++
                    convo = convo + ok.assistant + Turn.User(StructuredCaller.retryText(listOf(verdict.violation)))
                }
                is Checked.Reject -> {
                    if (retries >= StructuredCaller.MAX_RETRIES) return template(null, step)
                    retries++
                    convo = convo + ok.assistant + Turn.User(StructuredCaller.retryText(checked.violations))
                }
            }
        }
        return template(null, maxSteps)
    }

    private fun evaluate(reply: String, round: ChatRound, outputs: List<String>): Checked<Utterance> {
        val o = LocalRepair.parse(reply, Utterance.DEFAULTS) ?: return Checked.Reject(listOf(StructuredCaller.NOT_JSON))
        val decoded = runCatching { Utterance.decode(o) }.getOrNull() ?: return Checked.Reject(listOf(StructuredCaller.NOT_JSON))
        val value = decoded.value ?: return Checked.Reject(decoded.violations.ifEmpty { listOf(StructuredCaller.NOT_JSON) })
        return Utterance.checker(round.evidence(outputs.toList()), round.mode, round.tone)(value)
    }

    /** What he would read: the guarded text, and in 討論 the question and the options (chips) after it. */
    private fun visible(u: Utterance): String = (listOf(u.text) + listOfNotNull(u.ask) + u.options).joinToString("\n")

    companion object {
        const val MAX_STEPS = 6
        const val MAX_TOKENS = 800

        /** What a repeated call gets instead of running again. */
        const val REPEATED = """{"error":"FAILED","reason":"同樣的查詢剛剛做過了，直接用上面的結果"}"""
    }
}
