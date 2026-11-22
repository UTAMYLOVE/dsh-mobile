package app.agency.core.llm

import app.agency.core.metering.CallBudget
import app.agency.core.metering.MutableClock
import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import app.agency.core.metering.UsageSink
import app.agency.core.net.HttpFactory
import app.agency.core.net.NetworkGate
import app.agency.core.net.Origin
import app.agency.core.net.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** A MockWebServer behind the real HttpFactory client and gate, with recording fakes for the meter and the spend cap. */
class LlmTestKit(val server: MockWebServer = MockWebServer().apply { start() }) : AutoCloseable {
    val base = server.url("/")
    val origin = Origin(base.scheme, base.host, base.port)
    var allowed: Set<Origin> = setOf(origin)
    val blockedHosts = mutableListOf<String>()
    val gate = NetworkGate({ allowed }, { blockedHosts += it })
    val http = HttpFactory.create(gate)
    val sleeps = mutableListOf<Long>()
    val transport = Transport(http) { sleeps += it }

    var capBlocked = false
    val recorded = mutableListOf<Triple<Provider, String, Usage>>()
    val sink = UsageSink { p, m, u -> recorded += Triple(p, m, u) }
    val clock = MutableClock()
    var budget = CallBudget(clock = clock)
    val checks get() = CallChecks({ capBlocked }, budget)

    var key: String? = "sk-test-key"

    fun deepseek(endpoint: CompatEndpoint? = CompatEndpoint.deepseek(base = base)) =
        OpenAiCompatClient(transport, { endpoint }, { key }, checks, sink)

    fun claude(model: String = AnthropicClient.DEFAULT_MODEL) = AnthropicClient(transport, { model }, { key }, checks, sink, base)

    fun search(minRerank: Double = DeepSeekSearchClient.DEFAULT_MIN_RERANK) =
        DeepSeekSearchClient(transport, { key }, checks, sink, base, minRerank)

    fun enqueue(code: Int, body: String = "{}") {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    fun take(): RecordedRequest = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("no request reached the server")

    override fun close() = server.shutdown()

    companion object {
        fun json(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body.readUtf8()).jsonObject

        fun completion(message: String, finish: String = "stop", usage: String = USAGE) =
            """{"id":"c1","object":"chat.completion","model":"deepseek-flash","choices":[{"index":0,"message":$message,
               "finish_reason":"$finish","logprobs":null}],"usage":$usage}"""

        const val USAGE = """{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
            "prompt_tokens_details":{"cached_tokens":30},"prompt_cache_hit_tokens":30,"prompt_cache_miss_tokens":70}"""

        val user = ChatRequest(system = "回 json", turns = listOf(Turn.User("我吃了雞胸肉")), purpose = Purpose.INTAKE, maxTokens = 400)
    }
}
