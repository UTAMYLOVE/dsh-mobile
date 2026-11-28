package app.dshmobile

import app.agency.core.base.J
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * The model during the emulator run: a MockWebServer on 127.0.0.1 inside the test process that answers the Claude shape
 * (`POST /v1/messages`) and the DeepSeek shape (`POST …/chat/completions`) alike, so whichever provider the app picks lands here.
 * - the rules import (its schema names `callName`): the invented [Fixtures] rules as the structured answer;
 * - any other structured call (second-pass judge, summary): a clean verdict;
 * - everything else: the plain sentence [reply].
 * Every request body is kept in [seen] for assertions (never logged). No real key, no network beyond this socket.
 */
class FakeModel {
    class Seen(val path: String, val body: String, val key: String?) {
        override fun toString() = "Seen($path, ${body.length} bytes)"
    }

    val seen = CopyOnWriteArrayList<Seen>()

    @Volatile var reply = "好，先從第一堂課開始吧。"

    private val server = MockWebServer()

    lateinit var base: HttpUrl
        private set

    /** Binds the socket; call it off the main thread. */
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val path = request.path.orEmpty()
                seen += Seen(path, body, request.getHeader("x-api-key") ?: request.getHeader("Authorization"))
                val text = when {
                    body.contains("callName") -> rulesAnswer()
                    body.contains("json_schema") || body.contains("json_object") -> """{"ok":true,"reason":""}"""
                    else -> reply
                }
                return when {
                    path.endsWith("/v1/messages") -> json(
                        """{"id":"msg_e2e","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                           "content":[{"type":"text","text":${JsonPrimitive(text)}}],"stop_reason":"end_turn",
                           "usage":{"input_tokens":120,"output_tokens":20}}""",
                    )
                    path.endsWith("/chat/completions") -> json(
                        """{"id":"c_e2e","choices":[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(text)}},"finish_reason":"stop"}],
                           "usage":{"prompt_tokens":120,"completion_tokens":20}}""",
                    )
                    path.endsWith("/v1/tools/search") -> json("""{"results":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // Built from the literal address (no host name lookup, which the main thread may not do).
        base = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).build()
    }

    fun shutdown() = server.shutdown()

    private fun rulesAnswer(): String {
        val rules = J.encodeToString(Rules.serializer(), Fixtures.rules())
        return """{"rules":$rules,"handbook":"他喜歡簡單直接的說法。"}"""
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        @Volatile var instance: FakeModel? = null
    }
}
