package app.dshmobile

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The model behind the gateway during the emulator end-to-end run: speaks the Anthropic Messages protocol with SSE streaming
 * (`message_start`, `content_block_*`, `message_delta` with usage, `message_stop`; docs/dsh-findings.md §Providers) and
 * answers by what the request contains:
 *  - the memory plugin's dream prompt -> one reminder `E2E-REMIND`;
 *  - a conversation turn (the agent's tool list is there) without a `tool_result` -> a `memory_write` tool call;
 *  - the follow-up that carries the `tool_result` -> the text `ok`;
 *  - anything else (session title, ...) -> plain text.
 * No real key, no network: it listens on 127.0.0.1 inside the test process.
 */
class FakeModel {
    enum class Kind { DREAM, TOOL_USE, FOLLOW_UP, OTHER }

    data class Seen(val path: String, val kind: Kind, val apiKeyIsTheVaultKey: Boolean)

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

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: "?"
        if (request.method != "POST" || !path.endsWith("/v1/messages")) {
            Log.i(TAG, "unexpected ${request.method} $path")
            return MockResponse().setResponseCode(404).setBody("""{"type":"error","error":{"type":"not_found_error","message":"e2e"}}""")
        }
        val body = request.body.readUtf8()
        val kind = classify(body)
        seen += Seen(path, kind, request.getHeader("x-api-key") == E2E_KEY)
        Log.i(TAG, "model request #${seen.size}: $kind (${body.length} bytes)")
        return when (kind) {
            Kind.DREAM -> sse(listOf(Block.Text(DREAM_REPLY)), "end_turn")
            Kind.TOOL_USE -> sse(
                listOf(Block.ToolUse("toolu_e2e_${ids.incrementAndGet()}", "memory_write", """{"target":"daily","text":"$MEMORY_MARK"}""")),
                "tool_use",
            )
            Kind.FOLLOW_UP -> sse(listOf(Block.Text("ok")), "end_turn")
            Kind.OTHER -> sse(listOf(Block.Text("E2E title")), "end_turn")
        }
    }

    private sealed interface Block {
        data class Text(val text: String) : Block
        data class ToolUse(val id: String, val name: String, val inputJson: String) : Block
    }

    private fun sse(blocks: List<Block>, stopReason: String): MockResponse {
        val out = StringBuilder()
        fun event(type: String, data: JSONObject) {
            data.put("type", type)
            out.append("event: ").append(type).append('\n').append("data: ").append(data).append("\n\n")
        }
        event(
            "message_start",
            JSONObject().put(
                "message",
                JSONObject().put("id", "msg_e2e_${ids.incrementAndGet()}").put("type", "message").put("role", "assistant")
                    .put("model", "deepseek-flash").put("content", org.json.JSONArray())
                    .put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
                    .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 1)),
            ),
        )
        blocks.forEachIndexed { i, b ->
            when (b) {
                is Block.Text -> {
                    event("content_block_start", JSONObject().put("index", i).put("content_block", JSONObject().put("type", "text").put("text", "")))
                    event("content_block_delta", JSONObject().put("index", i).put("delta", JSONObject().put("type", "text_delta").put("text", b.text)))
                }
                is Block.ToolUse -> {
                    event(
                        "content_block_start",
                        JSONObject().put("index", i).put(
                            "content_block",
                            JSONObject().put("type", "tool_use").put("id", b.id).put("name", b.name).put("input", JSONObject()),
                        ),
                    )
                    event(
                        "content_block_delta",
                        JSONObject().put("index", i).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", b.inputJson)),
                    )
                }
            }
            event("content_block_stop", JSONObject().put("index", i))
        }
        event(
            "message_delta",
            JSONObject().put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL))
                .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5)),
        )
        event("message_stop", JSONObject())
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(out.toString())
    }

    companion object {
        const val TAG = "DSHM-E2E"
        const val E2E_KEY = "sk-e2e-fake"
        const val MEMORY_MARK = "E2E-MEMORY-MARK"
        const val REMIND_TITLE = "E2E-REMIND"

        /** The first line of the memory plugin's dream prompt (plugins/packages/memory/src/dream.ts, DREAM_INSTRUCTIONS). */
        const val DREAM_MARKER = "You maintain a personal assistant's long-term memory."
        private const val DREAM_REPLY = """{"promote":[],"remind":[{"title":"$REMIND_TITLE","body":"from e2e"}]}"""

        fun classify(body: String): Kind = when {
            body.contains(DREAM_MARKER) -> Kind.DREAM
            !body.contains("\"memory_write\"") -> Kind.OTHER // no agent tool list: title generation and the like
            body.contains("\"tool_result\"") -> Kind.FOLLOW_UP
            else -> Kind.TOOL_USE
        }

        /** The one instance of this instrumentation process (started by [E2eRunner]). */
        @Volatile lateinit var instance: FakeModel
    }
}
