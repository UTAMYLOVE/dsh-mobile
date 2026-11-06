package app.dshmobile

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The model behind the gateway during the emulator end-to-end run: speaks the Anthropic Messages protocol with SSE streaming
 * (`message_start`, `content_block_*`, `message_delta` with usage, `message_stop`; docs/dsh-findings.md §Providers) and answers
 * by route ([classify]):
 *  - the memory plugin's dream prompt -> one reminder `E2E-REMIND`;
 *  - dsh's DeepSeek web-search backend (`Perform a web search for the query:`) -> a non-streaming answer with two results;
 *  - a request without the agent's tool list (title generation and the like) -> plain text;
 *  - a follow-up (the last message carries a `tool_result`) -> by the prefix of the `tool_use` id it answers;
 *  - otherwise by a keyword in the text of the last user message (`E2E_HELLO`, `E2E_SLOW`, ...; see the brief's route table).
 * Every reply reports 20 000 input and 1 000 output tokens, so the spend shows on the overview. No real key, no network: it
 * listens on 127.0.0.1 inside the test process. Request bodies are kept for assertions but never logged.
 */
class FakeModel {
    enum class Kind { DREAM, SEARCH_BACKEND, TOOL_USE, FOLLOW_UP, HELLO, SLOW, LONG, SEARCH, APPROVE, ASK, SUB, CHILD, FILE, FOLLOWUPS, TODOS, PRESENT, PLAN, SCHEDULE, OTHER }

    /** One request the model got. [body] is for assertions only: [toString] leaves it out, so failure logs never carry it. */
    data class Seen(
        val path: String,
        val kind: Kind,
        val apiKeyIsTheVaultKey: Boolean,
        val model: String?,
        val body: String,
        /** When the request arrived (wall clock, ms). */
        val at: Long = System.currentTimeMillis(),
    ) {
        override fun toString(): String = "Seen($kind, $path, model=$model, vaultKey=$apiKeyIsTheVaultKey, ${body.length} bytes)"
    }

    val seen = CopyOnWriteArrayList<Seen>()
    private val ids = AtomicInteger()
    private val server = MockWebServer()

    /** Starts listening; blocking, so it is called off the main thread. */
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /** Upstreams for the gateway. Built by hand: MockWebServer's own URL says `localhost`, which the app's cleartext rule refuses. */
    fun upstreams(): Map<app.dshmobile.gateway.Provider, HttpUrl> = mapOf(
        app.dshmobile.gateway.Provider.DEEPSEEK to "http://127.0.0.1:${server.port}/deepseek".toHttpUrl(),
        app.dshmobile.gateway.Provider.ANTHROPIC to "http://127.0.0.1:${server.port}/anthropic".toHttpUrl(),
    )

    /** The requests seen since [from] (an earlier `seen.size`). */
    fun since(from: Int): List<Seen> = seen.toList().drop(from)

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: "?"
        if (request.method != "POST" || !path.endsWith("/v1/messages")) {
            Log.i(TAG, "unexpected ${request.method} $path")
            return MockResponse().setResponseCode(404).setBody("""{"type":"error","error":{"type":"not_found_error","message":"e2e"}}""")
        }
        val body = request.body.readUtf8()
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val kind = classify(body)
        seen += Seen(path, kind, request.getHeader("x-api-key") == E2E_KEY, parsed?.optString("model")?.takeIf { it.isNotEmpty() }, body)
        Log.i(TAG, "model request #${seen.size}: $kind (${body.length} bytes)")
        return when (kind) {
            Kind.DREAM -> sse(listOf(Block.Text(DREAM_REPLY)), "end_turn")
            Kind.SEARCH_BACKEND -> searchBackend()
            Kind.TOOL_USE -> sse(listOf(toolUse("e2e", "memory_write", JSONObject().put("target", "daily").put("text", MEMORY_MARK))), "tool_use")
            Kind.FOLLOW_UP -> followUp(parsed)
            Kind.HELLO -> sse(
                listOf(Block.Thinking("E2E thinking"), Block.Text("**E2E** reply\n\n", "- one\n- two\n\n", "| a | b |\n|---|---|\n| 1 | 2 |")),
                "end_turn",
            )
            Kind.SLOW -> slow(40, periodMs = 300)
            // Slower than the 30 s the stop is given in t19, so a stopped child cannot be mistaken for one that just finished.
            Kind.CHILD -> slow(60, periodMs = 600)
            Kind.LONG -> sse(listOf(Block.Text(*longParts())), "end_turn")
            Kind.SEARCH -> sse(listOf(toolUse("search", "web_search", JSONObject().put("queries", JSONArray().put("e2e weather")))), "tool_use")
            Kind.APPROVE -> sse(
                listOf(
                    toolUse(
                        "approve", "write",
                        JSONObject().put("file_path", "/tmp/e2e-outside.txt").put("content", "x")
                            .put("sandbox_permissions", "danger-full-access").put("justification", "E2E approval"),
                    ),
                ),
                "tool_use",
            )
            Kind.ASK -> sse(
                listOf(
                    toolUse(
                        "ask", "ask_user_question",
                        JSONObject().put(
                            "questions",
                            JSONArray().put(
                                JSONObject().put("id", "q1").put("question", "Which color?").put("header", "Choose")
                                    .put("options", JSONArray().put(JSONObject().put("label", "Pink")).put(JSONObject().put("label", "Blue"))),
                            ),
                        ),
                    ),
                ),
                "tool_use",
            )
            Kind.SUB -> sse(
                listOf(toolUse("sub", "subagent", JSONObject().put("description", "E2E child").put("prompt", "$CHILD work slowly"))),
                "tool_use",
            )
            Kind.FILE -> sse(listOf(Block.Text(FILE_REPLY)), "end_turn")
            // 0.5.0 scenarios
            Kind.FOLLOWUPS -> sse(listOf(Block.Text("E2E answer with suggestions\n\n", "<!--dshm-followups\n$FOLLOWUP_ONE\nE2E next two\n-->")), "end_turn")
            Kind.TODOS -> sse(
                listOf(
                    toolUse(
                        "todos", "todo_write",
                        JSONObject().put(
                            "todos",
                            JSONArray().put(JSONObject().put("content", "E2E step one").put("status", "completed"))
                                .put(JSONObject().put("content", "E2E step two").put("status", "in_progress"))
                                .put(JSONObject().put("content", "E2E step three").put("status", "pending")),
                        ),
                    ),
                ),
                "tool_use",
            )
            Kind.PRESENT -> sse(listOf(toolUse("presentw", "write", JSONObject().put("file_path", PRESENT_PATH).put("content", "# E2E report\n\nok\n"))), "tool_use")
            Kind.PLAN -> sse(listOf(toolUse("plan", "exit_plan_mode", JSONObject().put("plan", "# E2E plan\n\n1. look\n2. write"))), "tool_use")
            Kind.SCHEDULE -> sse(
                listOf(toolUse("sched", "schedule_create", JSONObject().put("title", "E2E helper").put("prompt", "E2E helper task").put("every_seconds", 7200))),
                "tool_use",
            )
            Kind.OTHER -> sse(listOf(Block.Text("E2E title")), "end_turn")
        }
    }

    private fun toolUse(prefix: String, name: String, input: JSONObject) = Block.ToolUse("toolu_${prefix}_${ids.incrementAndGet()}", name, input.toString())

    /** [count] pieces of text, throttled so the whole reply takes a while (E2E_SLOW about 25 s, E2E_CHILD over a minute). */
    private fun slow(count: Int, periodMs: Long): MockResponse {
        val parts = (1..count).map { "slow part $it of $count. " }.toTypedArray()
        return sse(listOf(Block.Text(*parts)), "end_turn").throttleBody(64, periodMs, TimeUnit.MILLISECONDS)
    }

    /** E2E_LONG: 60 pieces with headings, a small table and a code block each (moderate tables: the 500-row case is not this). */
    private fun longParts(): Array<String> = (1..60).map { i ->
        "## E2E long section $i\n\nThis paragraph is part $i of a long reply, long enough to scroll through several screens.\n\n" +
            "| key | value |\n|---|---|\n| row $i | ${i * 7} |\n| next | ${i * 11} |\n\n" +
            "```kotlin\nval section$i = listOf($i, ${i + 1}, ${i + 2})\n```\n\n"
    }.toTypedArray()

    /** A follow-up: the written file is presented next (E2E_PRESENT); everything else gets its text. */
    private fun followUp(request: JSONObject?): MockResponse {
        val id = request?.let(::lastToolResult)?.optString("tool_use_id").orEmpty()
        return if (id.startsWith("toolu_presentw_")) {
            sse(listOf(toolUse("present", "present", JSONObject().put("files", JSONArray().put(JSONObject().put("path", PRESENT_PATH).put("description", "E2E deliverable"))))), "tool_use")
        } else sse(listOf(Block.Text(followUpText(request))), "end_turn")
    }

    /** The answer to a tool's result, chosen by the prefix of the tool_use id it answers. */
    private fun followUpText(request: JSONObject?): String {
        val result = request?.let(::lastToolResult) ?: return "ok"
        val id = result.optString("tool_use_id")
        return when {
            id.startsWith("toolu_search_") -> SEARCH_REPLY
            id.startsWith("toolu_approve_") -> APPROVED_REPLY
            id.startsWith("toolu_ask_") -> if (result.toString().contains("Pink")) PICKED_REPLY else "E2E picked nothing"
            id.startsWith("toolu_sub_") -> "E2E sub done"
            id.startsWith("toolu_todos_") -> TODOS_REPLY
            id.startsWith("toolu_present_") -> PRESENT_REPLY
            id.startsWith("toolu_plan_") -> PLAN_REPLY
            id.startsWith("toolu_sched_") -> "E2E helper scheduled"
            else -> "ok"
        }
    }

    /** dsh's search backend: non-streaming, two web_search results and a cited text (dsh-web-search-deepseek maps exactly these). */
    private fun searchBackend(): MockResponse {
        val results = JSONArray()
            .put(JSONObject().put("type", "web_search_result").put("url", "https://example.com/e2e-weather").put("title", "E2E Weather"))
            .put(JSONObject().put("type", "web_search_result").put("url", "https://example.org/e2e-market").put("title", "E2E Market"))
        val content = JSONArray()
            .put(JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "srvtoolu_e2e").put("content", results))
            .put(
                JSONObject().put("type", "text").put("text", "ok")
                    .put("citations", JSONArray().put(JSONObject().put("url", "https://example.com/e2e-weather").put("cited_text", "sunny"))),
            )
        val message = JSONObject().put("id", "msg_e2e_${ids.incrementAndGet()}").put("type", "message").put("role", "assistant")
            .put("model", "deepseek-flash").put("content", content).put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL)
            .put("usage", usage())
        return MockResponse().setHeader("Content-Type", "application/json").setBody(message.toString())
    }

    private sealed interface Block {
        class Text(vararg val parts: String) : Block
        class Thinking(val text: String) : Block
        class ToolUse(val id: String, val name: String, val inputJson: String) : Block
    }

    private fun sse(blocks: List<Block>, stopReason: String): MockResponse {
        val out = StringBuilder()
        fun event(type: String, data: JSONObject) {
            data.put("type", type)
            out.append("event: ").append(type).append('\n').append("data: ").append(data).append("\n\n")
        }
        fun delta(i: Int, d: JSONObject) = event("content_block_delta", JSONObject().put("index", i).put("delta", d))
        event(
            "message_start",
            JSONObject().put(
                "message",
                JSONObject().put("id", "msg_e2e_${ids.incrementAndGet()}").put("type", "message").put("role", "assistant")
                    .put("model", "deepseek-flash").put("content", JSONArray())
                    .put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
                    .put("usage", JSONObject().put("input_tokens", INPUT_TOKENS).put("output_tokens", 1)),
            ),
        )
        blocks.forEachIndexed { i, b ->
            when (b) {
                is Block.Text -> {
                    event("content_block_start", JSONObject().put("index", i).put("content_block", JSONObject().put("type", "text").put("text", "")))
                    b.parts.forEach { delta(i, JSONObject().put("type", "text_delta").put("text", it)) }
                }
                is Block.Thinking -> {
                    event(
                        "content_block_start",
                        JSONObject().put("index", i).put("content_block", JSONObject().put("type", "thinking").put("thinking", "").put("signature", "")),
                    )
                    delta(i, JSONObject().put("type", "thinking_delta").put("thinking", b.text))
                    delta(i, JSONObject().put("type", "signature_delta").put("signature", "sig_e2e_${ids.incrementAndGet()}"))
                }
                is Block.ToolUse -> {
                    event(
                        "content_block_start",
                        JSONObject().put("index", i).put(
                            "content_block",
                            JSONObject().put("type", "tool_use").put("id", b.id).put("name", b.name).put("input", JSONObject()),
                        ),
                    )
                    delta(i, JSONObject().put("type", "input_json_delta").put("partial_json", b.inputJson))
                }
            }
            event("content_block_stop", JSONObject().put("index", i))
        }
        event(
            "message_delta",
            JSONObject().put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL)).put("usage", usage()),
        )
        event("message_stop", JSONObject())
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(out.toString())
    }

    private fun usage() = JSONObject().put("input_tokens", INPUT_TOKENS).put("output_tokens", OUTPUT_TOKENS)

    companion object {
        const val TAG = "DSHM-E2E"
        const val E2E_KEY = "sk-e2e-fake"
        const val MEMORY_MARK = "E2E-MEMORY-MARK"
        const val REMIND_TITLE = "E2E-REMIND"
        private const val INPUT_TOKENS = 20_000
        private const val OUTPUT_TOKENS = 1_000

        // Route keywords, put in the prompt text by the tests.
        const val HELLO = "E2E_HELLO"
        const val SLOW = "E2E_SLOW"
        const val LONG = "E2E_LONG"
        const val SEARCH = "E2E_SEARCH"
        const val APPROVE = "E2E_APPROVE"
        const val ASK = "E2E_ASK"
        const val SUB = "E2E_SUB"
        const val CHILD = "E2E_CHILD"
        const val FILE = "E2E_FILE"
        const val FOLLOWUPS = "E2E_FOLLOWUPS"
        const val FOLLOWUP_ONE = "E2E next one"
        const val TODOS = "E2E_TODOS"
        const val TODOS_REPLY = "E2E todos set"
        const val PRESENT = "E2E_PRESENT"
        const val PRESENT_PATH = "deliverables/e2e-report.md"
        const val PRESENT_REPLY = "E2E presented"
        const val PLAN = "E2E_PLAN"
        const val PLAN_REPLY = "E2E plan done"
        const val MEMORY_PROMPT = "remember the E2E mark"

        // What the tests wait for.
        const val SEARCH_REPLY = "Sunny today. [E2E Weather](https://example.com/e2e-weather)"
        const val APPROVED_REPLY = "E2E approved"
        const val PICKED_REPLY = "E2E picked Pink"
        const val FILE_REPLY = "E2E got the file"

        /** The first line of the memory plugin's dream prompt (plugins/packages/memory/src/dream.ts, DREAM_INSTRUCTIONS). */
        const val DREAM_MARKER = "You maintain a personal assistant's long-term memory."
        /** dsh-web-search-deepseek's request text (lib/index.js). */
        const val SEARCH_BACKEND_MARKER = "Perform a web search for the query:"
        private const val DREAM_REPLY = """{"promote":[],"remind":[{"title":"$REMIND_TITLE","body":"from e2e"}]}"""

        fun classify(body: String): Kind {
            if (body.contains(DREAM_MARKER)) return Kind.DREAM
            if (body.contains(SEARCH_BACKEND_MARKER)) return Kind.SEARCH_BACKEND
            val request = runCatching { JSONObject(body) }.getOrNull() ?: return Kind.OTHER
            if (lastToolResult(request) != null) return Kind.FOLLOW_UP
            val text = lastUserText(request)
            // A subagent's own turn: checked before the tool list, in case dsh gives children fewer tools.
            if (text.contains(CHILD)) return Kind.CHILD
            if (!body.contains("\"memory_write\"")) return Kind.OTHER // no agent tool list: title generation and the like
            return when {
                text.contains(MEMORY_PROMPT) -> Kind.TOOL_USE
                text.contains(HELLO) -> Kind.HELLO
                text.contains(SLOW) -> Kind.SLOW
                text.contains(LONG) -> Kind.LONG
                text.contains(SEARCH) -> Kind.SEARCH
                text.contains(APPROVE) -> Kind.APPROVE
                text.contains(ASK) -> Kind.ASK
                text.contains(SUB) -> Kind.SUB
                text.contains(FILE) -> Kind.FILE
                text.contains(FOLLOWUPS) -> Kind.FOLLOWUPS
                text.contains(TODOS) -> Kind.TODOS
                text.contains(PRESENT) -> Kind.PRESENT
                text.contains(PLAN) -> Kind.PLAN
                text.contains("schedule_create") -> Kind.SCHEDULE
                else -> Kind.OTHER
            }
        }

        private fun messages(request: JSONObject): JSONArray = request.optJSONArray("messages") ?: JSONArray()

        /** The last message's `tool_result` block, when the last message carries one (a follow-up). */
        private fun lastToolResult(request: JSONObject): JSONObject? {
            val all = messages(request)
            if (all.length() == 0) return null
            val content = all.optJSONObject(all.length() - 1)?.optJSONArray("content") ?: return null
            return (content.length() - 1 downTo 0).map { content.optJSONObject(it) }.firstOrNull { it?.optString("type") == "tool_result" }
        }

        /** The text of the last user message that is not a tool result (the routing key). */
        private fun lastUserText(request: JSONObject): String {
            val all = messages(request)
            for (i in all.length() - 1 downTo 0) {
                val m = all.optJSONObject(i) ?: continue
                if (m.optString("role") != "user") continue
                val content = m.opt("content")
                if (content is String) return content
                if (content !is JSONArray) continue
                val blocks = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                if (blocks.any { it.optString("type") == "tool_result" }) continue
                return blocks.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
            }
            return ""
        }

        /** The one instance of this instrumentation process (started by [E2eRunner]). */
        @Volatile lateinit var instance: FakeModel
    }
}
