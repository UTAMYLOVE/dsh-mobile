package app.dshmobile

import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The model during the emulator run (arch §3, "How the emulator e2e suite changes"): a MockWebServer on 127.0.0.1 inside the
 * test process that answers
 *  - `POST …/chat/completions` (DeepSeek shape): the data clerk's call (its system prompt is the clerk's) with
 *    `{"kind": [intakeKind]}`; a chat call first with one `get_today` tool call carrying `reasoning_content` when
 *    [toolFirst], then with an Utterance whose text is [reply];
 *  - `POST /v1/messages` (Claude shape) with the same Utterance;
 *  - `POST /v1/tools/search` with no results.
 * Every request body is kept in [seen] for assertions (never logged); [online] false drops every connection (offline test).
 * No real key, no network.
 */
class FakeModel {
    /** One request: [path] and [body] (for assertions only: [toString] leaves the body out). */
    data class Seen(val path: String, val body: String, val auth: String?) {
        override fun toString(): String = "Seen($path, ${body.length} bytes)"
    }

    val seen = CopyOnWriteArrayList<Seen>()
    @Volatile var online = true
    @Volatile var intakeKind = "chat"
    @Volatile var reply = "收到。現在去喝水。"
    @Volatile var toolFirst = false
    private val ids = AtomicInteger()
    private val server = MockWebServer()

    /** Starts listening; blocking, so it is called off the main thread. */
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!online) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                seen += Seen(path, body, request.getHeader("Authorization") ?: request.getHeader("x-api-key"))
                return when {
                    path.endsWith("/chat/completions") -> chatCompletion(JSONObject(body))
                    path.endsWith("/v1/messages") -> json(messages())
                    path.endsWith("/v1/tools/search") -> json(JSONObject().put("results", JSONArray()))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // Built from the literal address (no host name lookup, which the main thread may not do).
        base = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).build()
    }

    fun shutdown() = server.shutdown()

    /** Where the app sends every model request in this run (`http://127.0.0.1:<port>/`); set by [start]. */
    lateinit var base: HttpUrl
        private set

    fun reset() {
        seen.clear()
        online = true
        intakeKind = "chat"
        reply = "收到。現在去喝水。"
        toolFirst = false
    }

    private fun utterance() = JSONObject().put("text", reply).put("ask", JSONObject.NULL).put("options", JSONArray())
        .put("recommended", JSONObject.NULL).put("memory", JSONArray()).toString()

    private fun chatCompletion(req: JSONObject): MockResponse {
        val msgs = req.optJSONArray("messages") ?: JSONArray()
        val system = msgs.optJSONObject(0)?.optString("content").orEmpty()
        val last = msgs.optJSONObject(msgs.length() - 1)
        val message = JSONObject().put("role", "assistant")
        when {
            system.contains("資料整理員") -> message.put("content", JSONObject().put("kind", intakeKind).toString())
            toolFirst && req.has("tools") && last?.optString("role") != "tool" -> message.put("content", "")
                .put("reasoning_content", "先看今天的紀錄")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_${ids.incrementAndGet()}").put("type", "function")
                    .put("function", JSONObject().put("name", "get_today").put("arguments", "{}"))))
            else -> message.put("content", utterance()).put("reasoning_content", "好")
        }
        return json(
            JSONObject().put("id", "fake-${ids.incrementAndGet()}").put("object", "chat.completion")
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message).put("finish_reason", "stop")))
                .put("usage", JSONObject().put("prompt_tokens", 100).put("completion_tokens", 20)
                    .put("prompt_cache_hit_tokens", 0).put("prompt_cache_miss_tokens", 100)),
        )
    }

    private fun messages() = JSONObject().put("id", "msg_fake").put("type", "message").put("role", "assistant").put("model", "claude-sonnet-5-5")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", utterance())))
        .put("stop_reason", "end_turn").put("usage", JSONObject().put("input_tokens", 100).put("output_tokens", 20))

    private fun json(o: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())

    companion object {
        @Volatile lateinit var instance: FakeModel
    }
}
