package app.agency.core.llm

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No request can carry a picture, audio or file: the turn types have no such part (scan of core/llm code), and a request built
 * from every turn type, for every provider, has no media block or field anywhere in its JSON.
 */
class NoMediaInRequestsTest {
    private val kit = LlmTestKit()

    @After fun tearDown() = kit.close()

    private val media = Regex("""(?i)image|base64|ByteArray|InputStream|\bFile\b|audio|document|data:""")

    @Test fun theLlmCodeHasNoMediaType() {
        val dir = listOf(File("src/main/kotlin/app/agency/core/llm"), File("core/src/main/kotlin/app/agency/core/llm")).first { it.isDirectory }
        val offenders = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val code = l.trim()
                !code.startsWith("*") && !code.startsWith("/*") && !code.startsWith("//") && media.containsMatchIn(code.substringBefore("//"))
            }.map { "${f.name}:${it.index + 1}" }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private fun keysAndTypes(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.flatMap { (k, v) -> listOf(k) + (if (k == "type") listOf(v.toString()) else emptyList()) + keysAndTypes(v) }
        is JsonArray -> e.flatMap { keysAndTypes(it) }
        else -> emptyList()
    }

    @Test fun everyTurnTypeSerialisesWithoutMedia() = runBlocking {
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
}
