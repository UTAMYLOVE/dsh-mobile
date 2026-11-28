package app.agency.core.lessons

import app.agency.core.base.J
import app.agency.core.llm.ChatRequest
import app.agency.core.llm.ChatResult
import app.agency.core.llm.Fail
import app.agency.core.llm.LlmClient
import app.agency.core.llm.Output
import app.agency.core.llm.Purpose
import app.agency.core.llm.Turn
import app.agency.core.rules.StudyRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Makes a quiz from a lesson's own material with one structured model call. The model writes the questions; [QuizCheck]
 * decides whether they are usable. An invalid quiz is asked for once more (with what was wrong); after that, or when the call
 * fails, the answer is null and the lesson falls back to notes only.
 */
class Quizzer(private val llm: LlmClient) {
    /** What one try to make a quiz came to. */
    sealed interface Made {
        data class Ok(val quiz: Quiz) : Made

        /** The model could not be asked (offline, spending cap, refused…): nothing about the lesson is decided. */
        data class CallFailed(val fail: Fail) : Made

        /** Asked twice and never got a valid quiz (or there is no material): the lesson goes to notes only. */
        data object Invalid : Made
    }

    suspend fun make(lesson: Lesson, level: String, study: StudyRule = StudyRule()): Quiz? =
        (attempt(lesson, level, study) as? Made.Ok)?.quiz

    suspend fun attempt(lesson: Lesson, level: String, study: StudyRule = StudyRule()): Made {
        val material = lesson.material.trim()
        if (material.isEmpty()) return Made.Invalid
        val count = QuizCheck.questionCount(study)
        var problems: List<String> = emptyList()
        repeat(2) {
            val request = ChatRequest(
                system = SYSTEM,
                turns = listOf(Turn.User(prompt(lesson, level, count, material, problems))),
                purpose = Purpose.QUIZ,
                maxTokens = 2000,
                output = Output.Json(schema(count)),
            )
            val result = try {
                llm.chat(request)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                ChatResult.Failed(Fail.BAD_RESPONSE)
            }
            if (result is ChatResult.Failed) return Made.CallFailed(result.fail)
            val quiz = parse((result as ChatResult.Ok).text)
            if (quiz == null) {
                problems = listOf("回覆不是指定格式的 JSON")
            } else {
                problems = QuizCheck.problems(quiz, material, study)
                if (problems.isEmpty()) return Made.Ok(quiz)
            }
        }
        return Made.Invalid
    }

    private fun parse(text: String?): Quiz? {
        if (text.isNullOrBlank()) return null
        val body = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { J.decodeFromJsonElement<Quiz>(J.parseToJsonElement(body).jsonObject) }.getOrNull()
    }

    private fun prompt(lesson: Lesson, level: String, count: Int, material: String, problems: List<String>): String = buildString {
        appendLine("科目：${lesson.subject}")
        appendLine("學員程度：$level")
        appendLine("影片標題：${lesson.title}")
        appendLine()
        appendLine("請根據下面的教材出 $count 題四選一的選擇題。難度要配合「$level」程度。")
        appendLine("規則：")
        appendLine("1. 每一題都只能用教材裡寫到的內容回答，不可以用教材以外的知識。")
        appendLine("2. 每題剛好四個選項，四個選項彼此不同。")
        appendLine("3. answer 是正確選項的編號，從 0 開始（0、1、2、3）。")
        appendLine("4. quote 要從教材裡原封不動複製一段支持答案的原文，至少 ${QuizCheck.MIN_QUOTE} 個字，不可改寫。")
        appendLine("5. 題目和選項用繁體中文。")
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("上一次的題目有這些問題，請改掉：")
            problems.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("教材：")
        append(material.take(MAX_MATERIAL))
    }

    private fun schema(count: Int): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add(JsonPrimitive("questions")) }
        putJsonObject("properties") {
            putJsonObject("questions") {
                put("type", "array")
                put("minItems", count)
                put("maxItems", count)
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") { listOf("q", "options", "answer", "quote").forEach { add(JsonPrimitive(it)) } }
                    putJsonObject("properties") {
                        putJsonObject("q") { put("type", "string") }
                        putJsonObject("options") {
                            put("type", "array")
                            put("minItems", 4)
                            put("maxItems", 4)
                            putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("answer") { put("type", "integer") }
                        putJsonObject("quote") { put("type", "string") }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_MATERIAL = 12_000
        private const val SYSTEM = "你是一位嚴謹的出題老師。你只根據使用者給的教材出題，答案一定能在教材裡找到原文。只回覆指定格式的 JSON。"
    }
}
