package app.agency.core.extras

import app.agency.core.base.J
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The facts of one evaluation period that code counted. The only thing besides his own words a model may see. */
data class PeriodFacts(
    val days: Int,
    val daysCheckedIn: Int,
    /** Days with both a check-in and a check-out, as a share of [days]. */
    val completionPct: Int,
    val violations: Int,
    val practiceMinutes: Int,
    val tasksDone: Int,
) {
    fun lines(): List<String> = listOf(
        "期間 $days 天，簽到 $daysCheckedIn 天，簽到又簽退的比例 $completionPct%",
        "違規 $violations 次",
        "練習共 $practiceMinutes 分鐘，完成 $tasksDone 項課表",
    )
}

/**
 * One structured model call that suggests the three scores code cannot compute (舞蹈、外貌與鏡頭、明星特質), each with a
 * one-sentence reason. The model sees only [PeriodFacts] and his own short self-report, never a recording or a photo. The answer
 * is checked: anything unusable (not JSON, a score outside 1..5, a missing or too long reason, a Latin letter) gives null, and
 * the evaluation simply carries no suggestion. No retry: an evaluation is never held up by the model.
 */
class EvalSuggester(private val llm: LlmClient) {

    suspend fun suggest(facts: PeriodFacts, selfReport: String): List<EvalScore>? {
        val request = ChatRequest(
            system = SYSTEM,
            turns = listOf(Turn.User(prompt(facts, selfReport))),
            purpose = Purpose.JUDGE,
            maxTokens = 600,
            output = Output.Json(schema()),
        )
        val result = runCatching { llm.chat(request) }.getOrNull()
        if (result !is ChatResult.Ok) return null
        return parse(result.text)
    }

    private fun prompt(facts: PeriodFacts, selfReport: String): String = buildString {
        appendLine("請依下面程式算出的事實，替練習生這段期間的三項表現各給 1 到 5 分的建議，並各寫一句理由。")
        appendLine("只能用事實和他自己的話，不要編造沒有的事，不要提到你看過影片或照片。理由用繁體中文，不超過 $MAX_REASON 個字。")
        appendLine("三項：dance（舞蹈）、visual（外貌與鏡頭）、star（明星特質）。")
        appendLine()
        appendLine("事實：")
        facts.lines().forEach { appendLine("- $it") }
        appendLine()
        appendLine("他自己的話：${selfReport.trim().take(MAX_SELF).ifBlank { "（沒有寫）" }}")
    }

    companion object {
        const val MAX_REASON = 60
        const val MAX_SELF = 300
        private val DIMS = listOf("dance" to EvalDim.DANCE, "visual" to EvalDim.VISUAL, "star" to EvalDim.STAR)

        private const val SYSTEM = "你是練習生評鑑的助手。只輸出指定格式的 JSON，不要多餘的文字。"

        fun schema(): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonArray("required") { DIMS.forEach { add(JsonPrimitive(it.first)) } }
            put("additionalProperties", false)
            putJsonObject("properties") {
                DIMS.forEach { (k, _) ->
                    putJsonObject(k) {
                        put("type", "object")
                        putJsonArray("required") { add(JsonPrimitive("score")); add(JsonPrimitive("reason")) }
                        put("additionalProperties", false)
                        putJsonObject("properties") {
                            putJsonObject("score") { put("type", "integer"); put("minimum", 1); put("maximum", 5) }
                            putJsonObject("reason") { put("type", "string") }
                        }
                    }
                }
            }
        }

        /** The three scores, or null when anything about the reply is not usable. */
        fun parse(text: String?): List<EvalScore>? {
            if (text.isNullOrBlank()) return null
            val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = runCatching { J.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val out = ArrayList<EvalScore>()
            for ((key, dim) in DIMS) {
                val o = (obj[key] as? JsonObject) ?: return null
                val score = (o["score"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
                val reason = (o["reason"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
                if (score !in 1..5) return null
                if (reason.isEmpty() || reason.length > MAX_REASON) return null
                if (reason.any { it in 'A'..'Z' || it in 'a'..'z' }) return null
                out += EvalScore(dim, score, ScoreSource.SUGGESTED, reason)
            }
            return out
        }
    }
}
