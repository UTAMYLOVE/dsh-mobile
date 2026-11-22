package app.agency.core.structured

import app.agency.core.guard.Evidence
import app.agency.core.guard.GuardMode
import app.agency.core.guard.OutputGuard
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The wording of one proactive message (code already decided to speak and posted its template). */
data class NudgeLine(val text: String) {
    companion object {
        const val TEXT_CHARS = 60

        val SCHEMA: JsonObject = JsonSchema.obj("text" to JsonSchema.str())

        val FORMAT = "只輸出一個 json 物件，不要其他文字：{\"text\":\"一到兩句提醒（最多 $TEXT_CHARS 字）\"}"

        val SPEC: StructuredSpec<NudgeLine> = object : StructuredSpec<NudgeLine> {
            override val schema = SCHEMA
            override val defaults = emptyMap<String, JsonElement>()
            override fun decode(o: JsonObject): Decoded<NudgeLine> {
                val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                val n = text.codePointCount(0, text.length)
                return when {
                    text.isEmpty() -> Decoded(null, listOf(Violation("text", "要有提醒的話")))
                    n > TEXT_CHARS -> Decoded(null, listOf(Violation("text", "最多 $TEXT_CHARS 個字，現在 $n 個")))
                    else -> Decoded(NudgeLine(text))
                }
            }
        }

        /** The output guard in nudge mode (at most two sentences); dropped sentences are taken out. */
        fun checker(evidence: Evidence): (NudgeLine) -> Checked<NudgeLine> = { n ->
            val r = OutputGuard.check(n.text, evidence, GuardMode.NUDGE)
            if (r.needsRetry) Checked.Reject(Utterance.reasons("text", r).ifEmpty { listOf(Violation("text", "每一句都不能用")) })
            else Checked.Accept(NudgeLine(r.kept))
        }
    }
}
