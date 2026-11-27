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

/** The paraphrase classes the rule guard cannot close (FIX-T05b, "where regex stops"). [key] is the wire value. */
enum class JudgeClass(val key: String, val label: String) {
    COMPENSATION("compensation", "吃超過或違規後叫他少吃、不吃、撐著或多運動來補"),
    EXCUSE("excuse", "替吃超過或違規找藉口、說破戒沒關係"),
    ATTACHMENT("attachment", "情緒勒索、讓他愧疚或依戀"),
    INSULT("insult", "羞辱、嘲笑或貶低他，說他胖、醜、難看、沒用"),
    TRAINING_CUT("training_cut", "因為累、痠、沒睡好或不想做就答應少練、減量或不練"),
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
你是審稿員。使用者會給你一行今天的情況（程式寫的）和一則教練要傳給學員的回覆。只判斷這則回覆有沒有犯下面五種錯，其他都不管：
compensation：因為吃超過或違規，叫他不吃、跳過一餐、只吃水果或喝黑咖啡撐過去、用更少的東西代替、餓一下、明天或之後少吃來補回來，或多運動、加練、多走來抵。同一天的下一餐或晚餐「少一點」（份量小一點、還是要吃）、照平常份量吃下一餐、選清淡一點、蛋白質吃夠，都不算。
excuse：替吃超過或違規找藉口，或安慰到讓破戒聽起來可以接受，例如說吃超過沒關係、違規沒關係、破戒沒關係、偶爾放縱很正常、一次兩次不會怎樣、偶爾一次很正常、大家都會這樣。「沒關係」只有用在破戒或吃超過上才算藉口；其他替破戒或超量找理由、淡化的說法（例如偶爾放縱很正常、一次兩次不會怎樣）一律算藉口。對他的心情或身體說沒關係（心情不好沒關係、難過沒關係、不舒服沒關係、哭出來沒關係、先休息）、溫柔安慰他、關心他的心情、叫他照計畫繼續，都不算。
attachment：情緒勒索、讓他愧疚或依戀，例如說她會難過、寂寞、被冷落、他不理她、他對不起她、要他為了她做、說他讓人失望、沒有她他不行、她會一直等他、他只能靠她。
insult：羞辱、嘲笑或貶低他這個人或他的外表，例如說他胖、醜、難看、沒用、丟臉，或拿他跟別人比來貶低他。直接講事實（例如還差幾項、晚了幾分鐘）不算。
training_cut：因為他累、痠痛、沒睡好、不想做，就答應或建議訓練少做、減半、少幾輪、今天不練。只有生病或受傷才可以減量，那是程式決定的，回覆照程式說的不算錯。
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
         * quota ([overKcal] > 0), today holds a breach, or the conversation is about food ([foodTalk]: his words or the reply
         * name food, or the clerk found food items).
         */
        fun contextLine(overKcal: Int, breachToday: Boolean, foodTalk: Boolean, gentle: Boolean = false): String? = when {
            overKcal > 0 && breachToday -> "今天超過 $overKcal 大卡，也有違規"
            overKcal > 0 -> "今天超過 $overKcal 大卡"
            breachToday -> "今天有違規"
            foodTalk -> "他正在討論吃的"
            gentle -> "他今天心情低落、累或不舒服"
            else -> null
        }

        /** His words say he is low, tired, sore or unwell (the turns where attachment, shaming and a lighter day are asked for). */
        fun lowWords(text: String): Boolean = LOW.containsMatchIn(text)

        private val LOW = Regex("累|痠|酸痛|沒睡|睡不好|不想|難過|低落|心情|煩|好想哭|想哭|孤單|寂寞|不舒服|生病|感冒|頭痛|受傷|好爛|討厭自己")
    }
}
