package app.agency.core.guard

import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.ProviderChoice
import app.agency.core.llm.Purpose
import app.agency.core.llm.Thinking
import app.agency.core.llm.Turn
import app.agency.core.structured.JsonSchema
import app.agency.core.structured.LocalRepair
import app.agency.core.structured.StructuredCaller
import app.agency.core.structured.Violation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The three paraphrase classes the rule guard cannot close (FIX-T05b, "where regex stops"). [key] is the wire value. */
enum class JudgeClass(val key: String, val label: String) {
    COMPENSATION("compensation", "吃超過或違規後叫他少吃、不吃、撐著或多運動來補"),
    EXCUSE("excuse", "替吃超過或違規找藉口、說沒關係"),
    ATTACHMENT("attachment", "情緒勒索、讓他愧疚或依戀"),
    ;

    companion object {
        const val NONE = "none"
        val KEYS = entries.map { it.key } + NONE
        fun of(key: String?): JudgeClass? = entries.firstOrNull { it.key == key }
    }
}

/** The judge's answer on one reply. [NoAnswer] (timeout, a failed call, the cap, an unreadable reply) keeps the rule result. */
sealed interface Verdict {
    data object Clean : Verdict

    data class Breach(val cls: JudgeClass, val reason: String) : Verdict {
        /** What the writing model is told on the rewrite: the class in code words, then the judge's short reason. */
        val violation: Violation get() = Violation("text", cls.label + (reason.takeIf { it.isNotEmpty() }?.let { "：$it" } ?: ""))
    }

    data class NoAnswer(val fail: Fail?) : Verdict
}

/**
 * The second-pass judge (arch 1.9b): one cheap structured call that reads a reply the rule guard has already accepted and says
 * whether it falls in one of the three [JudgeClass] paraphrase classes. It sees only the reply and one code-written context
 * line (「今天超過 380 大卡」): the system prompt is the fixed [SYSTEM] rubric, with no facts, history, memory or persona.
 *
 * [llm] is the cheapest model of the provider he chose (the data clerk's client), so the call goes through that client's spend
 * cap, call budget and usage meter like every other paid call. One call, no retry: anything but a readable verdict within
 * [timeoutMs] is [Verdict.NoAnswer] (fail-open; the rule guard's verdict stands).
 */
class SecondPassJudge(
    private val llm: LlmClient,
    private val provider: () -> ProviderChoice,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    suspend fun judge(reply: String, context: String): Verdict {
        val request = request(reply, context, StructuredCaller.maxTokensFor(provider(), MAX_TOKENS))
        val result = try {
            withTimeoutOrNull(timeoutMs) { llm.chat(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Verdict.NoAnswer(null)
        } ?: return Verdict.NoAnswer(null)
        return when (result) {
            is ChatResult.Failed -> Verdict.NoAnswer(result.fail)
            is ChatResult.Ok -> read(result.text.orEmpty())
        }
    }

    companion object {
        const val TIMEOUT_MS = 8_000L
        const val MAX_TOKENS = 150
        const val REASON_CHARS = 40

        val SCHEMA: JsonObject = with(JsonSchema) {
            obj("violation" to bool(), "class" to enum(JudgeClass.KEYS), "reason" to str())
        }

        /** The fixed rubric. It names no fact about him: what the judge knows of today is the one context line. */
        val SYSTEM = """
你是審稿員。使用者會給你一行今天的情況（程式寫的）和一則教練要傳給學員的回覆。只判斷這則回覆有沒有犯下面三種錯，其他都不管：
compensation：因為吃超過或違規，叫他接下來少吃、不吃、跳過一餐、只吃水果或喝黑咖啡撐過去、用更少的東西代替、餓一下，或多運動、加練、多走來補回來。照平常份量吃下一餐、選清淡一點、蛋白質吃夠，不算。
excuse：替吃超過或違規找藉口或安慰到讓破戒聽起來可以接受，例如沒關係、偶爾一次很正常、不會怎樣、大家都會這樣。關心他的心情、叫他照計畫繼續，不算。
attachment：情緒勒索、讓他愧疚或依戀，例如說她會難過、寂寞、被冷落、他不理她、他對不起她、要他為了她做、說他讓人失望。
只輸出一個 json 物件，不要其他文字：
{"violation":true,"class":"${JudgeClass.KEYS.joinToString("|")}","reason":"最多四十字，說哪一句錯在哪"}
沒有錯就輸出 {"violation":false,"class":"none","reason":""}。
""".trimIndent()

        /** The whole user turn: the context line and the reply, nothing else. */
        fun userText(reply: String, context: String): String = "今天的情況：$context\n要檢查的回覆：\n$reply"

        fun request(reply: String, context: String, maxTokens: Int = MAX_TOKENS): ChatRequest = ChatRequest(
            system = SYSTEM, turns = listOf(Turn.User(userText(reply, context))), purpose = Purpose.JUDGE, maxTokens = maxTokens,
            output = Output.Json(SCHEMA), thinking = Thinking.OFF,
        )

        /** A readable verdict, or [Verdict.NoAnswer] (a true violation must name one of the three classes). */
        fun read(text: String): Verdict {
            val o = LocalRepair.parse(text, emptyMap()) ?: return Verdict.NoAnswer(Fail.BAD_RESPONSE)
            val violation = (o["violation"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: return Verdict.NoAnswer(Fail.BAD_RESPONSE)
            if (!violation) return Verdict.Clean
            val cls = JudgeClass.of((o["class"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                ?: return Verdict.NoAnswer(Fail.BAD_RESPONSE)
            val reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            return Verdict.Breach(cls, reason.take(REASON_CHARS))
        }

        /**
         * The one context line, written by code, or null when the judge is not needed this turn: today's meals go over the
         * quota ([overKcal] > 0), today holds a breach, or he is discussing food ([foodTalk]).
         */
        fun contextLine(overKcal: Int, breachToday: Boolean, foodTalk: Boolean): String? = when {
            overKcal > 0 && breachToday -> "今天超過 $overKcal 大卡，也有違規"
            overKcal > 0 -> "今天超過 $overKcal 大卡"
            breachToday -> "今天有違規"
            foodTalk -> "他正在討論吃的"
            else -> null
        }
    }
}
