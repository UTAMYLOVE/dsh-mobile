package app.agency.core.agent

import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The second check, by the provider's small model, for what regexes cannot judge: whether a reply about eating amounts,
 * weight or training load keeps the company's hard rules in spirit. It runs only when [triggered] (topic words), and never
 * blocks: a failed or unreadable call is treated as no objection ([review] returns null).
 */
class Judge(private val small: LlmClient) {
    data class Verdict(val ok: Boolean, val reason: String)

    fun triggered(reply: String): Boolean = topic.containsMatchIn(reply)

    suspend fun review(reply: String, ownerText: String, facts: String): Verdict? {
        val input = "他說的話：\n$ownerText\n\n這一輪的事實（程式算的）：\n${Memory.takeCodePoints(facts, FACTS_MAX)}\n\n" +
            "小葵準備回他的話：\n$reply"
        val request = ChatRequest(SYSTEM, listOf(Turn.User(input)), Purpose.JUDGE, 400, output = Output.Json(SCHEMA))
        val r = try {
            small.chat(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return (r as? ChatResult.Ok)?.text?.let(::parse)
    }

    companion object {
        private const val FACTS_MAX = 4000

        private val topic = Regex(
            "大卡|熱量|卡路里|吃太多|吃多|吃少|少吃|份量|分量|蛋白質|體重|公斤|變瘦|減重|瘦|胖|伏地挺身|深蹲|棒式|訓練量|強度|減量|少做|少練|輪",
        )

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("ok", buildJsonObject { put("type", "boolean") })
                put("reason", buildJsonObject { put("type", "string") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("ok"), JsonPrimitive("reason"))))
            put("additionalProperties", false)
        }

        private const val SYSTEM = "你是經紀公司的規章檢查員，只檢查經紀人小葵準備回給練習生的一段話有沒有違反下面的硬規則。" +
            "只看這些規則，語氣、長短、用字習慣都不是你的事；沒有明確違反就算通過。\n" +
            "1. 不能叫他跳過一餐、不吃、斷食，也不能叫他明天少吃來補今天吃多的。今天之內下一餐小一點可以。\n" +
            "2. 吃多了不能說「沒關係」帶過；要照實說對身體和身形的影響。偶爾小幅超過可以照實說是小幅。\n" +
            "3. 訓練減量、停練只能由程式依生病或受傷決定；小葵不能因為他累、痠、想睡、不想做就准他少練或休息。" +
            "事實裡寫著程式已經減量時，照事實說就可以。\n" +
            "4. 不能羞辱他、貶低他的身材或人格。\n" +
            "5. 不能鼓勵他把體重降到公司下限以下，也不能因為身體照片叫他少吃。\n" +
            "6. 關於他的數字必須和事實一致，不能自己估算或編造。\n" +
            "只回 JSON：{\"ok\": true 或 false, \"reason\": \"不通過時用一句繁體中文說哪裡違反、該怎麼改；通過時留空字串\"}。"

        internal fun parse(text: String): Verdict? {
            val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
            val reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return Verdict(ok, reason)
        }
    }
}
