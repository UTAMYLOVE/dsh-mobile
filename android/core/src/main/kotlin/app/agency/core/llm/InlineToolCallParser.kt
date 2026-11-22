package app.agency.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Finds tool calls a model wrote into its text instead of the `tool_calls` field: `<tool_call>{"name":…,"arguments":…}</tool_call>`
 * blocks, and DeepSeek's raw call tokens (`<｜tool▁call▁begin｜>name<｜tool▁sep｜>{args}<｜tool▁call▁end｜>`, also the older
 * `function<｜tool▁sep｜>name` + fenced json form). Only names in [offered] with a JSON object as arguments are kept; anything
 * else stays out (it is never run). The idea follows Kai's InlineToolCallParser (Apache-2.0, see KaiNotice.md); this code is ours.
 */
object InlineToolCallParser {
    data class Parsed(val calls: List<ToolCall>, val text: String)

    private val tagged = Regex("""<tool_call>\s*(\{.*?})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
    private val native = Regex("""<｜tool▁call▁begin｜>(.*?)<｜tool▁sep｜>(.*?)<｜tool▁call▁end｜>""", RegexOption.DOT_MATCHES_ALL)
    private val nativeFrame = Regex("""<｜tool▁calls▁(begin|end)｜>""")
    private val fence = Regex("""^\s*([^\n`]+?)\s*\n\s*```(?:json)?\s*(.*?)\s*```\s*$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The calls found in [content] (ids `inline_1`, `inline_2`…) and the text with all call markup removed (also when no call in
     * it was usable, so raw markup never reaches the owner); null when [content] has no call markup at all.
     */
    fun parse(content: String, offered: Set<String>): Parsed? {
        if (!content.contains("<tool_call>") && !content.contains("<｜tool▁call▁begin｜>")) return null
        val calls = ArrayList<ToolCall>()
        fun add(name: String, args: String) {
            val obj = runCatching { Json.parseToJsonElement(args) as? JsonObject }.getOrNull() ?: return
            if (name in offered) calls += ToolCall("inline_${calls.size + 1}", name, obj.toString())
        }
        tagged.findAll(content).forEach { m ->
            val o = runCatching { Json.parseToJsonElement(m.groupValues[1]) as? JsonObject }.getOrNull() ?: return@forEach
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            val args = o["arguments"] ?: o["parameters"]
            val text = when {
                args is JsonPrimitive && args.isString -> args.content
                args is JsonObject -> args.toString()
                else -> return@forEach
            }
            add(name, text)
        }
        native.findAll(content).forEach { m ->
            val head = m.groupValues[1].trim()
            val body = m.groupValues[2]
            if (head == "function") {
                fence.find(body)?.let { add(it.groupValues[1].trim(), it.groupValues[2]) }
            } else {
                add(head, body.trim().removeSurrounding("```json", "```").removeSurrounding("```").trim())
            }
        }
        val text = content.replace(tagged, "").replace(native, "").replace(nativeFrame, "").trim()
        return Parsed(calls, text)
    }
}
