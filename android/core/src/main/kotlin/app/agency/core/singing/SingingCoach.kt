package app.agency.core.singing

import app.agency.core.guard.Evidence
import app.agency.core.guard.GuardMode
import app.agency.core.guard.OutputGuard
import app.agency.core.structured.JsonSchema
import app.agency.core.structured.LocalRepair
import app.agency.core.vision.VisionImage
import app.agency.core.vision.VisionRequest
import kotlinx.serialization.json.JsonPrimitive

/**
 * The vocal teacher's words on a take (T19d): she reads the chart (a cleaned picture) and code's numbers, and says where it
 * drifts, where the breath runs out and how steady it is. Every number she writes must be one of code's (the output guard's
 * numeric check against [facts]; one dropped sentence and the whole comment is replaced by code's summary), and she may not
 * judge tone, feeling or 好不好聽 (that waits for a model that can listen).
 */
object SingingCoach {
    const val TOKENS = 600

    val SYSTEM = """
你是小雨娛樂的聲樂老師，照女團練習生的標準講評，直接、具體，不羞辱。
你只看得到音高曲線圖和程式算好的數字，聽不到聲音：不准評論好不好聽、音色、感情或聲音美不美。
圖：橫軸是時間，一條橫線是一個半音；粉紅點在音上、紫色點偏掉了；淺色長條是原唱的音；淺直線是換氣；最下面是音量。
講哪裡偏、哪裡氣不夠、哪裡不穩，各給一個今天可以練的做法。要用數字只能用下面給的數字，原樣照抄，不要自己算或估。
最多五句。只輸出一個 json 物件：{"text":"講評"}
""".trim()

    private val BANNED = Regex("好聽|難聽|音色|感情|聲音很美|聲音好美|嗓音")

    fun request(chart: VisionImage, facts: List<String>): VisionRequest =
        VisionRequest(SYSTEM, "程式算的數字：\n" + facts.joinToString("\n"), listOf(chart), JsonSchema.obj("text" to JsonSchema.str()), TOKENS)

    /** Her comment if it passes: valid JSON, nothing banned, every sentence kept by the guard with [facts] as the only evidence. */
    fun accept(reply: String, facts: List<String>): String? {
        val text = (LocalRepair.parse(reply, emptyMap())?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        if (text.isEmpty() || BANNED.containsMatchIn(text)) return null
        val g = OutputGuard.check(text, Evidence(facts), GuardMode.NORMAL)
        if (g.hard.isNotEmpty() || g.kept.replace(Regex("\\s"), "") != text.replace(Regex("\\s"), "")) return null
        return text
    }

    /** Code's own summary (when no model, or her words did not pass). */
    fun summary(facts: List<String>): String = "聲樂老師看了曲線：\n" + facts.joinToString("\n")
}
