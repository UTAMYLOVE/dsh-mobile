package app.agency.core.llm

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A picture reaches a model only from [Turn.User.images]: never from a tool result, never from an assistant turn (even one
 * whose provider payload holds a media block), and never for a model that cannot see images (that call fails instead).
 */
class NoMediaInRequestsTest {
    private val kit = LlmTestKit()

    @After fun tearDown() = kit.close()

    private val media = Regex("""(?i)image|base64|audio|document|input_file|data:""")
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

    @Test fun onlyTypesAndImagesHandleBytes() {
        val dir = listOf(File("src/main/kotlin/app/agency/core/llm"), File("core/src/main/kotlin/app/agency/core/llm")).first { it.isDirectory }
        val raw = Regex("""ByteArray|Base64|base64|data:""")
        val offenders = dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in setOf("Types.kt", "Images.kt") }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val code = l.trim()
                !code.startsWith("*") && !code.startsWith("/*") && !code.startsWith("//") && raw.containsMatchIn(code.substringBefore("//"))
            }.map { "${f.name}:${it.index + 1}" }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private fun keysAndTypes(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.flatMap { (k, v) -> listOf(k) + (if (k == "type") listOf(v.toString()) else emptyList()) + keysAndTypes(v) }
        is JsonArray -> e.flatMap { keysAndTypes(it) }
        else -> emptyList()
    }

    @Test fun everyTurnTypeWithoutImagesSerialisesWithoutMedia() = runBlocking {
        val call = ToolCall("c1", "get_today", "{}")
        val turns = listOf(
            Turn.User("我吃了雞胸肉"),
            Turn.AssistantText("好"),
            Turn.Assistant(Wire.OPENAI, buildJsonObject { put("role", "assistant"); put("content", "") }, null, listOf(call)),
            Turn.ToolResult("c1", "{}"),
            Turn.Assistant(Wire.ANTHROPIC, JsonArray(emptyList()), "查好了", emptyList()),
            Turn.User("謝謝"),
        )
        val req = ChatRequest("系統", turns, Purpose.CHAT, 100, output = Output.Json(buildJsonObject { put("type", "object") }))
        val bodies = listOf(
            kit.deepseek().body(CompatEndpoint.deepseek(base = kit.base), req, withTools = false),
            kit.claude().body(AnthropicClient.DEFAULT_MODEL, req),
        )
        bodies.forEach { b ->
            val found = keysAndTypes(b).filter { media.containsMatchIn(it) || it.contains("url", ignoreCase = true) }
            assertEquals(emptyList<String>(), found)
            assertTrue(b.toString().contains("我吃了雞胸肉"))
        }
    }

    /** A history where media could sneak in through every other door. */
    private fun sneaky(): List<Turn> {
        val claudeImage = buildJsonObject {
            put("type", "image"); put("source", buildJsonObject { put("type", "base64"); put("media_type", "image/jpeg"); put("data", "QUJD") })
        }
        val openAiImage = buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,QUJD") }) }
        return listOf(
            Turn.User("早安"),
            Turn.Assistant(Wire.ANTHROPIC, JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "早") }, claudeImage)), "早", emptyList()),
            Turn.Assistant(
                Wire.OPENAI,
                buildJsonObject { put("role", "assistant"); put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "嗯") }, openAiImage))) },
                "嗯",
                listOf(ToolCall("c1", "get_today", "{}")),
            ),
            Turn.ToolResult("c1", """{"photo":"data:image/jpeg;base64,QUJD"}"""),
            Turn.User("這是我的午餐", images = listOf(jpeg)),
        )
    }

    private fun mediaBlocks(e: JsonElement): List<JsonObject> = when (e) {
        is JsonObject -> (if (e["type"]?.let { (it as? JsonPrimitive)?.content } in setOf("image", "image_url")) listOf(e) else emptyList()) +
            e.values.flatMap { mediaBlocks(it) }
        is JsonArray -> e.flatMap { mediaBlocks(it) }
        else -> emptyList()
    }

    @Test fun claudeGetsImagesOnlyOnTheUserTurnThatCarriesThem() = runBlocking {
        val body = kit.claude().body(AnthropicClient.DEFAULT_MODEL, ChatRequest("系統", sneaky(), Purpose.CHAT, 100))
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, mediaBlocks(messages).size)
        val last = messages.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        // The tool result shares that user message and goes first; then the photo, then his words.
        val all = last["content"]!!.jsonArray
        assertEquals("tool_result", all[0].jsonObject["type"]!!.jsonPrimitive.content)
        val content = all.drop(1)
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val source = content[0].jsonObject["source"]!!.jsonObject
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(java.util.Base64.getEncoder().encodeToString(jpeg), source["data"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun deepseekFlashGetsImagesOnlyOnTheUserTurnThatCarriesThem() = runBlocking {
        val ep = CompatEndpoint.deepseek(base = kit.base)
        val body = kit.deepseek(ep).body(ep, ChatRequest("系統", sneaky(), Purpose.CHAT, 100, thinking = Thinking.LOW), withTools = false)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, mediaBlocks(messages).size)
        val content = messages.last().jsonObject["content"]!!.jsonArray
        val url = content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg), url)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        // The one verified DeepSeek image shape was sent with thinking off.
        assertEquals("""{"type":"disabled"}""", body["thinking"].toString())
    }

    @Test fun aModelThatCannotSeeFailsInsteadOfDroppingThePhoto() = runBlocking {
        val req = ChatRequest("系統", listOf(Turn.User("看", images = listOf(jpeg))), Purpose.CHAT, 100)
        val pro = kit.deepseek(CompatEndpoint.deepseek(model = CompatEndpoint.DEEPSEEK_PRO, base = kit.base)).chat(req)
        assertEquals(ChatResult.Failed(Fail.NO_VISION), pro)
        val generic = kit.deepseek(CompatEndpoint.generic(kit.base.toString(), "m", jsonMode = true, tools = true, allowHttp = true)).chat(req)
        assertEquals(ChatResult.Failed(Fail.NO_VISION), generic)
        assertEquals(0, kit.server.requestCount)
    }
}
