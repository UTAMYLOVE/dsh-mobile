package app.dshmobile.remote

import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.FakeDshServer
import app.dshmobile.runtime.FakeMux
import app.dshmobile.runtime.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class DshEventsTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val fake = FakeMux()
    private val rpcBodies = CopyOnWriteArrayList<String>()
    @Volatile private var answer: (RecordedRequest) -> MockResponse = { okResult() }
    private val dsh = FakeDshServer(mux = fake, rpc = { req -> rpcBodies.add(req.body.readUtf8()); answer(req) })
    private val client = DshClient(OkHttpClient(), dsh.launch)
    private lateinit var mux: MuxConnection

    @After fun cleanup() {
        if (::mux.isInitialized) mux.close()
        scope.cancel()
        dsh.close()
    }

    /** `$events` over a logged-in mux; `call` is the plain unary RPC (`POST /api/<method>`, Task 1's envelope). */
    private fun newEvents(graceMs: Long = 3_000): DshEvents {
        runBlocking { client.login() }
        mux = MuxConnection({ client.openWebSocket("/api/remote.mux", it) }, { client.login() }, scope, listOf(0))
        return DshEvents(mux, ::rpc, scope, graceMs)
    }

    private suspend fun rpc(method: String, args: JsonObject): JsonElement {
        val response = client.postJsonForText("/api/$method", RpcEnvelope.request(method, args, "test"))
        if (response.code != 200) throw IOException("HTTP ${response.code}")
        return when (val result = RpcEnvelope.parse(response.body)) {
            is RpcResult.Ok -> result.value
            is RpcResult.Err -> throw DshRpcException(result.code, "dsh $method: ${result.code}")
        }
    }

    private fun okResult() = MockResponse().setResponseCode(200)
        .setBody("""{"type":"server-response","rpcId":"test","result":{"ok":true}}""")

    private suspend fun eventsStream(): String {
        eventually("\$events open") { fake.opened("\$events").isNotEmpty() }
        return fake.opened("\$events").last()
    }

    private suspend fun ev(json: String) = fake.sendItem(eventsStream(), json)

    private fun ready(id: String) = """{"type":"ready","clientId":"$id","host":{"home":"/root"}}"""

    private fun approval(eventId: String, request: String = """{"toolName":"write","callId":"toolu_6_1","reason":"escalate"}""") =
        """{"type":"waterfall","event":"approval/request","eventId":"$eventId","agentId":"session-a","request":$request}"""

    private fun lastArgs(): JsonElement =
        Json.parseToJsonElement(rpcBodies.last()).jsonObject["payload"]!!.jsonObject["args"]!!

    private val DshEvents.ids get() = prompts.value.map { it.eventId }

    @Test fun readyGivesTheClientIdUsedForAnswers() = runBlocking {
        val events = newEvents()
        ev(ready("c1"))
        ev(approval("e1"))
        eventually("prompt") { events.prompts.value.size == 1 }
        assertEquals("c1", events.clientId.value)
        events.allowOnce(events.prompts.value.single() as PendingPrompt.Approval)
        assertEquals("/api/\$events/result", dsh.requests.last { it.method == "POST" }.path)
        assertEquals(
            Json.parseToJsonElement("""{"clientId":"c1","eventId":"e1","outcome":{"kind":"result","value":"allowed-once"}}"""),
            lastArgs(),
        )
        assertEquals(emptyList<PendingPrompt>(), events.prompts.value)

        ev(approval("e2"))
        eventually("second prompt") { events.ids == listOf("e2") }
        events.reject(events.prompts.value.single() as PendingPrompt.Approval)
        assertEquals(
            Json.parseToJsonElement("""{"clientId":"c1","eventId":"e2","outcome":{"kind":"result","value":"rejected"}}"""),
            lastArgs(),
        )
        assertEquals(emptyList<PendingPrompt>(), events.prompts.value)
    }

    @Test fun approvalPrefersTheChineseDisplayReason() = runBlocking {
        val events = newEvents()
        ev(ready("c1"))
        ev(approval("e1", """{"toolName":"write","callId":"toolu_6_1","reason":"raw","displayReason":{"en":"Allow this","zh":"允许本次操作"}}"""))
        ev(approval("e2", """{"toolName":"bash","callId":"toolu_7_1","reason":"raw","displayReason":{"en":"Allow this"}}"""))
        ev(approval("e3", """{"toolName":"bash","reason":"raw"}"""))
        eventually("three prompts") { events.prompts.value.size == 3 }
        assertEquals(
            listOf(
                PendingPrompt.Approval("e1", "session-a", "write", "toolu_6_1", "允许本次操作"),
                PendingPrompt.Approval("e2", "session-a", "bash", "toolu_7_1", "Allow this"),
                PendingPrompt.Approval("e3", "session-a", "bash", null, "raw"),
            ),
            events.prompts.value,
        )
    }

    @Test fun questionsParseIdsHeadersAndOptions() = runBlocking {
        val events = newEvents()
        ev(ready("c1"))
        ev("""{"type":"waterfall","event":"user-questions/request","eventId":"e1","agentId":"session-a","request":{"questions":[
            {"id":"q1","question":"Which color?","header":"Choose","options":[{"label":"Pink"},{"label":"Blue"}]},
            {"id":"q2","question":"Which toppings?","options":[{"label":"Tea"},{"label":"Milk"}],"multiSelect":true}]}}""")
        eventually("question") { events.prompts.value.size == 1 }
        assertEquals(
            PendingPrompt.Question(
                "e1", "session-a",
                listOf(
                    AskQuestion("q1", "Choose", "Which color?", listOf("Pink", "Blue"), false),
                    AskQuestion("q2", null, "Which toppings?", listOf("Tea", "Milk"), true),
                ),
            ),
            events.prompts.value.single(),
        )
    }

    @Test fun answerSendsSelectedLabels() = runBlocking {
        val events = newEvents()
        ev(ready("c1"))
        ev("""{"type":"waterfall","event":"user-questions/request","eventId":"e1","agentId":"session-a","request":{"questions":[{"id":"q1","question":"Which color?","header":"Choose","options":[{"label":"Pink"},{"label":"Blue"}]}]}}""")
        eventually("question") { events.prompts.value.size == 1 }
        events.answer(events.prompts.value.single() as PendingPrompt.Question, mapOf("q1" to listOf("Pink")))
        assertEquals(
            Json.parseToJsonElement("""{"clientId":"c1","eventId":"e1","outcome":{"kind":"result","value":{"answers":[{"id":"q1","selected":["Pink"]}]}}}"""),
            lastArgs(),
        )
        assertEquals(emptyList<PendingPrompt>(), events.prompts.value)

        ev("""{"type":"waterfall","event":"user-questions/request","eventId":"e2","agentId":"session-a","request":{"questions":[{"id":"q1","question":"Which color?","options":[{"label":"Pink"}]}]}}""")
        eventually("second question") { events.ids == listOf("e2") }
        events.decline(events.prompts.value.single() as PendingPrompt.Question)
        assertEquals(
            Json.parseToJsonElement("""{"clientId":"c1","eventId":"e2","outcome":{"kind":"rejected","error":{"name":"UserDeclined","message":"declined in DSH Mobile"}}}"""),
            lastArgs(),
        )
        assertEquals(emptyList<PendingPrompt>(), events.prompts.value)
    }

    @Test fun cancelRemovesThePrompt() = runBlocking {
        val events = newEvents()
        ev(ready("c1"))
        ev(approval("e1"))
        ev(approval("e2"))
        eventually("two prompts") { events.prompts.value.size == 2 }
        ev("""{"type":"cancel","eventId":"e1"}""")
        eventually("e1 withdrawn") { events.ids == listOf("e2") }
    }

    @Test fun aRedeliveredPromptKeepsOneCard() = runBlocking {
        val events = newEvents()
        ev(ready("c1"))
        ev(approval("e1"))
        ev(approval("e1"))
        ev(approval("e2"))
        eventually("e2") { events.ids.contains("e2") }
        delay(100)
        assertEquals(listOf("e1", "e2"), events.ids)
    }

    @Test fun aRedeliveredPromptStaysVisibleThroughTheGrace() = runBlocking {
        val events = newEvents(graceMs = 500)
        ev(ready("c1"))
        ev(approval("e1"))
        eventually("e1") { events.ids == listOf("e1") }
        val id = eventsStream()

        val samples = CopyOnWriteArrayList<Boolean>()
        val sampler = scope.launch { while (true) { samples += events.ids.contains("e1"); delay(10) } }
        mux.dropForTest()
        eventually("\$events reopened") { fake.opened("\$events").size == 2 }
        assertEquals(listOf(id, id), fake.opened("\$events"))
        eventually("clientId cleared") { events.clientId.value == null }
        fake.sendItem(id, ready("c2"))
        delay(50)
        fake.sendItem(id, approval("e1"))
        delay(800)
        sampler.cancel()

        assertTrue("sampled ${samples.size}", samples.size > 40)
        assertTrue("e1 vanished at sample ${samples.indexOf(false)}", samples.all { it })
        assertEquals(listOf("e1"), events.ids)
        assertEquals("c2", events.clientId.value)
        events.allowOnce(events.prompts.value.single() as PendingPrompt.Approval)
        assertEquals(Json.parseToJsonElement("\"c2\""), lastArgs().jsonObject["clientId"])
    }

    @Test fun promptsNotRedeliveredWithinTheGraceAreDropped() = runBlocking {
        val events = newEvents(graceMs = 500)
        ev(ready("c1"))
        ev(approval("e1"))
        ev(approval("e2"))
        eventually("both") { events.ids == listOf("e1", "e2") }
        val id = eventsStream()

        fake.closeAll()
        eventually("\$events reopened") { fake.opened("\$events").size == 2 }
        fake.sendItem(id, ready("c2"))
        fake.sendItem(id, approval("e1"))
        delay(300)
        assertEquals("within the grace both cards stay", listOf("e1", "e2"), events.ids)
        delay(300)
        eventually("e2 dropped", timeoutMs = 1_000) { events.ids == listOf("e1") }
    }

    @Test fun aFailedAnswerKeepsThePromptAndThrows() = runBlocking {
        val events = newEvents()
        ev(ready("c1"))
        ev(approval("e1"))
        eventually("e1") { events.ids == listOf("e1") }
        answer = {
            MockResponse().setResponseCode(200).setBody(
                """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":{"code":"events/unknown-event","message":"gone","details":{}}}}""",
            )
        }
        try {
            events.allowOnce(events.prompts.value.single() as PendingPrompt.Approval)
            fail("ok:false must throw")
        } catch (e: DshRpcException) {
            assertEquals("events/unknown-event", e.code)
        }
        assertEquals(listOf("e1"), events.ids)
    }

    @Test fun sessionLifecycleEmitsBecomeHostEvents() = runBlocking {
        val events = newEvents()
        val seen = CopyOnWriteArrayList<HostEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = scope.launch { events.hostEvents.onSubscription { subscribed.complete(Unit) }.collect { seen += it } }
        withTimeout(5_000) { subscribed.await() }
        ev(ready("c1"))
        ev("""{"type":"emit","event":"api-session/added","args":[{"sessionId":"8381e2d1","parentSessionId":"session-f105","origin":"subagent","running":true,"blank":true,"projections":{"values":{"subagent":{"mode":"continuable","label":"Child research task","seq":0}}}}]}""")
        ev("""{"type":"emit","event":"api-session/activity","args":["x",1793684882024]}""")
        ev("""{"type":"emit","event":"api-session/status","args":["x",false]}""")
        ev("""{"type":"emit","event":"api-session/removed","args":["x"]}""")
        ev("""{"type":"emit","event":"api-session/removed","args":[{"sessionId":"x"}]}""")
        ev("""{"type":"emit","event":"api-session/status","args":[]}""")
        ev("""{"type":"something-new","eventId":"z"}""")
        eventually("four host events") { seen.size == 4 }
        delay(100)
        val added = seen[0] as HostEvent.SessionAdded
        assertEquals("8381e2d1", added.summary.id)
        assertEquals("session-f105", added.summary.parentId)
        assertEquals("subagent", added.summary.origin)
        assertEquals(true, added.summary.running)
        assertEquals("Child research task", added.summary.label)
        assertEquals(
            listOf(HostEvent.SessionStatus("x", false), HostEvent.SessionRemoved("x"), HostEvent.SessionRemoved("x")),
            seen.drop(1),
        )
        assertEquals(emptyList<PendingPrompt>(), events.prompts.value)
        collector.cancel()
    }

    @Test fun answeringBeforeReadyWaitsThenFailsWithoutAClientId() = runBlocking {
        val events = newEvents()
        eventsStream()
        assertNull(events.clientId.value)
        val prompt = PendingPrompt.Approval("e1", "session-a", "bash", null, "r")
        val waiting = scope.launch { events.allowOnce(prompt) }
        delay(100)
        assertTrue("an answer waits for ready", rpcBodies.isEmpty())
        ev(ready("c9"))
        withTimeout(5_000) { waiting.join() }
        assertEquals(Json.parseToJsonElement("\"c9\""), lastArgs().jsonObject["clientId"])
    }
}
