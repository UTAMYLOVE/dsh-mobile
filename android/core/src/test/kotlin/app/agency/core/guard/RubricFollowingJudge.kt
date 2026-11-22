package app.agency.core.guard

import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Turn
import app.agency.core.structured.ScriptedLlm

/**
 * A fake judge that follows the rubric it is sent instead of a script. For each class line of the system prompt with
 * 「例如…。」 it takes the listed examples (split on 、, a leading 說 dropped) and flags the reply when it contains one. So
 * whatever the rubric names as an example is what gets flagged: a rubric that listed a bare 「沒關係」 would flag every gentle
 * comfort line, and this fake would show it.
 */
class RubricFollowingJudge : LlmClient {
    val requests = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResult {
        requests += request
        val reply = (request.turns.single() as Turn.User).text.substringAfter(REPLY_LABEL)
        val hit = examples(request.system).firstNotNullOfOrNull { (cls, words) -> words.firstOrNull { it in reply }?.let { cls to it } }
        return ScriptedLlm.ok(
            if (hit == null) """{"violation":false,"class":"none","reason":""}"""
            else """{"violation":true,"class":"${hit.first}","reason":"${hit.second}"}""",
        )
    }

    companion object {
        private const val REPLY_LABEL = "要檢查的回覆：\n"

        /** class key → the examples its rubric line lists. */
        fun examples(system: String): List<Pair<String, List<String>>> = system.lines().mapNotNull { line ->
            val key = JudgeClass.entries.firstOrNull { line.startsWith(it.key + "：") }?.key ?: return@mapNotNull null
            val listed = line.substringAfter("例如", "").substringBefore("。").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to listed.split('、').map { it.trim().removePrefix("說") }.filter { it.isNotEmpty() }
        }
    }
}
