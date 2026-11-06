package app.dshmobile.remote

import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.FakeDshServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DshRemoteCallTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val rpcBodies = CopyOnWriteArrayList<String>()
    private var answer: (RecordedRequest) -> MockResponse = { ok(defaultValue(it.path.orEmpty())) }
    private val dsh = FakeDshServer(rpc = { req -> rpcBodies.add(req.body.readUtf8()); answer(req) })
    private val remote = DshRemote(DshClient(OkHttpClient(), dsh.launch), scope, ZoneId.of("Asia/Taipei"))

    @After fun cleanup() { remote.close(); scope.cancel(); dsh.close() }

    @Test fun callPostsToApiNamespaceMethodWithTheCookie() = runBlocking {
        remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
        val sent = dsh.requests.last { it.method == "POST" }
        assertEquals("/api/session/list", sent.path)
        assertEquals("POST", sent.method)
        assertTrue(sent.getHeader("Cookie")!!.contains("dsh-auth-x=signed"))
        assertTrue(dsh.unauthenticated.isEmpty())
        val envelope = Json.parseToJsonElement(rpcBodies.single()).jsonObject
        assertEquals("session/list", envelope["method"]!!.jsonPrimitive.content)
        assertTrue(envelope["rpcId"]!!.jsonPrimitive.content.startsWith("dshm-"))
    }

    @Test fun loginHappensOnceAndA401LogsInAgain() = runBlocking {
        remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
        remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
        assertEquals(1, dsh.requests.count { it.path == "/?token=good" })
        val rejected = AtomicInteger()
        answer = { if (rejected.getAndIncrement() == 0) MockResponse().setResponseCode(401) else ok("""{"items":[]}""") }
        remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
        assertEquals(2, dsh.requests.count { it.path == "/?token=good" })
        answer = { MockResponse().setResponseCode(401) }
        try {
            remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
            fail("a second 401 must not be retried forever")
        } catch (e: IOException) {
            assertFalse(e is DshRpcException)
        }
        assertEquals(3, dsh.requests.count { it.path == "/?token=good" })
    }

    @Test fun aBusinessErrorThrowsWithASafeCode() = runBlocking {
        answer = { err("workspace/invalid-path", "no such directory") }
        try {
            remote.call("workspace/create", buildJsonObject { putJsonObject("request") {} })
            fail("ok:false must throw")
        } catch (e: DshRpcException) {
            assertEquals("workspace/invalid-path", e.code)
        }
        answer = { err("http://127.0.0.1:1/?token=x", "see http://127.0.0.1:1/?token=x") }
        try {
            remote.call("workspace/create", buildJsonObject { putJsonObject("request") {} })
            fail("ok:false must throw")
        } catch (e: DshRpcException) {
            assertEquals("refused", e.code)
            assertFalse(e.message.orEmpty().contains("token"))
            assertFalse(e.toString().contains("token"))
        }
        assertEquals("refused", DshErrors.safeCode(null))
        assertEquals("refused", DshErrors.safeCode(""))
        assertEquals("refused", DshErrors.safeCode("a".repeat(81)))
        assertEquals("session/provider-credentials-unavailable", DshErrors.safeCode("session/provider-credentials-unavailable"))
    }

    @Test fun aNon200IsAnIOExceptionWithoutTheLaunchUrl() = runBlocking {
        answer = { MockResponse().setResponseCode(500).setBody("boom ${dsh.launch.url}") }
        try {
            remote.call("session/list", buildJsonObject { putJsonObject("_request") {} })
            fail("HTTP 500 must throw")
        } catch (e: IOException) {
            assertFalse(e is DshRpcException)
            val text = e.toString()
            assertFalse(text, text.contains("token="))
            assertFalse(text, text.contains(dsh.server.port.toString()))
        }
    }

    @Test fun typedCallsSendTheDocumentedArgs() = runBlocking {
        val cases: List<Pair<suspend () -> Unit, String>> = listOf(
            suspend { remote.listSessions(); Unit } to """{"_request":{}}""",
            suspend { remote.createSession("ws1"); Unit } to """{"request":{"workspaceId":"ws1"}}""",
            suspend { remote.createSession(null); Unit } to """{"request":{"cwd":"/root/workspace"}}""",
            suspend { remote.cancel("s1") } to """{"request":{"sessionId":"s1"}}""",
            suspend { remote.rename("s1", "New title"); Unit } to """{"request":{"sessionId":"s1","title":"New title"}}""",
            suspend { remote.archive("s1") } to """{"request":{"sessionId":"s1","stopActivity":true}}""",
            suspend { remote.selectModel("s1", "deepseek-official", "deepseek-flash") } to
                """{"request":{"sessionId":"s1","provider":"deepseek-official","model":"deepseek-flash"}}""",
            suspend { remote.interruptSubagent("c1", "p1") } to """{"childSessionId":"c1","parentSessionId":"p1","mode":"continuable"}""",
            suspend { remote.searchSessions("pink"); Unit } to """{"request":{"query":"pink"}}""",
            suspend { remote.page("s1", 40, 100); Unit } to
                """{"request":{"address":{"kind":"session","sessionId":"s1"},"beforeSeq":40,"maxMessages":100}}""",
        )
        val methods = listOf(
            "session/list", "session/create", "session/create", "session/cancel", "session/rename", "workspace/archiveSession",
            "session/selectModel", "subagents/interruptByParent", "session/search", "session/page",
        )
        for ((i, case) in cases.withIndex()) {
            case.first()
            val envelope = Json.parseToJsonElement(rpcBodies.last()).jsonObject
            assertEquals(methods[i], envelope["method"]!!.jsonPrimitive.content)
            assertEquals("/api/${methods[i]}", dsh.requests.last { it.method == "POST" }.path)
            assertEquals(methods[i], Json.parseToJsonElement(case.second), envelope["payload"]!!.jsonObject["args"])
        }
    }

    @Test fun typedCallsReadTheirValues() = runBlocking {
        assertEquals("s-new", remote.createSession(null))
        assertEquals("Renamed", remote.rename("s1", "New title"))
        assertEquals(listOf(SearchHit("s1", "a pink cat")), remote.searchSessions("pink"))
        assertEquals(Json.parseToJsonElement("""{"items":[],"hasMore":false}"""), remote.page("s1", 40, 100))
        answer = { ok("""{"accepted":true}""") }
        try {
            remote.createSession(null)
            fail("a value without sessionId must throw")
        } catch (e: DshRpcException) {
            assertEquals("unexpected-answer", e.code)
        }
    }

    @Test fun promptSendsQueueModeContentPartsAndTimeZone() = runBlocking {
        val parts = listOf(PromptPart.Text("hi"), PromptPart.Image("image/png", "AAAA", "a.png"), PromptPart.File("rcpt-1", "n.txt"))
        remote.prompt("s1", "q1", parts)
        val envelope = Json.parseToJsonElement(rpcBodies.last()).jsonObject
        assertEquals("session/prompt", envelope["method"]!!.jsonPrimitive.content)
        val expected = Json.parseToJsonElement(
            """{"request":{"requestId":"q1","sessionId":"s1","mode":"queue","content":[{"type":"text","text":"hi"},""" +
                """{"type":"image","mediaType":"image/png","data":"AAAA","name":"a.png"},{"type":"file","receiptId":"rcpt-1"}],""" +
                """"clientTimeZone":"Asia/Taipei"}}""",
        )
        assertEquals(expected, envelope["payload"]!!.jsonObject["args"])
        remote.prompt("s1", "q2", listOf(PromptPart.Image("image/jpeg", "BBBB", null)))
        val image = Json.parseToJsonElement(rpcBodies.last()).jsonObject["payload"]!!.jsonObject["args"]!!
            .jsonObject["request"]!!.jsonObject["content"]
        assertEquals(Json.parseToJsonElement("""[{"type":"image","mediaType":"image/jpeg","data":"BBBB"}]"""), image)
    }

    @Test fun uploadSendsBase64AndReturnsTheReceipt() = runBlocking {
        assertEquals("rcpt-1", remote.upload("s1", "n.txt", "hi".toByteArray()))
        val envelope = Json.parseToJsonElement(rpcBodies.last()).jsonObject
        assertEquals("fileUploads/upload", envelope["method"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"agentId":"s1","request":{"data":"aGk=","name":"n.txt"}}"""), envelope["payload"]!!.jsonObject["args"])
    }

    @Test fun listSessionsParsesSummaries() = runBlocking {
        answer = {
            ok(
                """{"items":[
                {"sessionId":"session-a","parentSessionId":"session-p","origin":"subagent","updatedAt":1793684831517,"running":true,"blank":false,
                 "cwd":"/root/workspace","projections":{"kind":"sequenced","asOfSeq":33,"values":{"title":"Fake Session Title",
                 "turnOutline":[{"turn":1,"seq":4,"prompt":"DO_BASH please","response":"Tool finished."},{"turn":2,"seq":27,"prompt":"second","response":"All good"}],
                 "modelSelection":{"lastUsed":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"high"},
                                   "next":{"provider":"deepseek-official","model":"deepseek-pro","reasoningEffort":"high"}},
                 "subagent":{"mode":"continuable","label":"Alpha worker","seq":0}}}},
                {"sessionId":"session-b"},
                {"projections":{}}
                ]}""",
            )
        }
        val list = remote.listSessions()
        assertEquals(
            listOf(
                SessionSummary(
                    id = "session-a", parentId = "session-p", origin = "subagent", updatedAt = 1793684831517, running = true, blank = false,
                    title = "Fake Session Title", lastLine = "All good", model = "deepseek-flash", nextModel = "deepseek-pro", label = "Alpha worker", cwd = "/root/workspace",
                ),
                SessionSummary("session-b", null, null, null, null, null, null, null, null, null, null),
            ),
            list,
        )
    }

    @Test fun anUnfinishedTurnShowsItsPromptAsTheLastLine() {
        val json = Json.parseToJsonElement(
            """{"sessionId":"s","projections":{"values":{"turnOutline":[{"turn":1,"prompt":"first","response":"done"},{"turn":2,"prompt":"DO_APPROVE now","response":""}]}}}""",
        ).jsonObject
        assertEquals("DO_APPROVE now", SessionSummary.from(json)!!.lastLine)
    }

    private fun ok(value: String) = MockResponse().setResponseCode(200)
        .setBody("""{"type":"server-response","rpcId":"x","result":{"ok":true,"value":$value}}""")

    private fun err(code: String, message: String): MockResponse {
        val body = buildJsonObject {
            put("type", "server-response")
            put("rpcId", "x")
            putJsonObject("result") {
                put("ok", false)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                    putJsonObject("details") {}
                }
            }
        }
        return MockResponse().setResponseCode(200).setBody(body.toString())
    }

    private fun defaultValue(path: String): String = when (path) {
        "/api/session/list" -> """{"items":[]}"""
        "/api/session/create" -> """{"sessionId":"s-new","agentPreset":"standard"}"""
        "/api/session/rename" -> """{"title":"Renamed","seq":9}"""
        "/api/session/search" -> """{"items":[{"sessionId":"s1","snippet":"a pink cat"},{"snippet":"no id"}],"hasMore":false}"""
        "/api/session/page" -> """{"items":[],"hasMore":false}"""
        "/api/fileUploads/upload" -> """{"receiptId":"rcpt-1","file":{"name":"n.txt"}}"""
        else -> """{"accepted":true}"""
    }

    /** Final review A-I2: building the request (photos, base64 uploads) and reading the answer never run on the caller's thread. */
    @Test fun encodingAndDecodingRunOnTheCodecDispatcherNotTheCallers() = runBlocking {
        val dispatched = AtomicInteger()
        val codec = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                kotlinx.coroutines.Dispatchers.Default.dispatch(context, block)
            }
        }
        val counted = DshRemote(DshClient(OkHttpClient(), dsh.launch), scope, ZoneId.of("Asia/Taipei"), codec = codec)
        try {
            counted.listSessions()
            assertTrue("encode and decode: ${dispatched.get()}", dispatched.get() >= 2)
            val before = dispatched.get()
            counted.upload("s1", "a.bin", ByteArray(1024))
            assertTrue("base64, encode and decode: ${dispatched.get() - before}", dispatched.get() - before >= 3)
        } finally {
            counted.close()
        }
    }

    /**
     * Residual review (A-I2): the request body's UTF-8 bytes (27 MB for a 20 MB upload) are made off the caller's thread, and the
     * client is handed bytes, so nothing re-encodes them on it.
     */
    @Test fun theBodyBytesAreEncodedOffTheCallersThread() {
        val encodedOn = CopyOnWriteArrayList<Thread>()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }
        val counted = DshRemote(
            DshClient(OkHttpClient(), dsh.launch), scope, ZoneId.of("Asia/Taipei"),
            encodeBody = { json -> encodedOn += Thread.currentThread(); json.toByteArray(Charsets.UTF_8) },
        )
        try {
            runBlocking(caller.asCoroutineDispatcher()) {
                counted.upload("s1", "a.bin", ByteArray(4096))
                counted.listSessions()
            }
            assertEquals(2, encodedOn.size)
            assertTrue(encodedOn.toString(), encodedOn.none { it.name == "caller" })
            assertTrue(rpcBodies.any { it.contains("fileUploads/upload") })
        } finally {
            counted.close()
            caller.shutdown()
        }
    }

    // ---- 0.5.0 Task 1: fork, create-at, schedules (shapes recorded from dsh 0.2.1-rc.5, 2026-11-06) ----

    @Test fun forkPostsTheBoundaryAndReturnsTheNewId() = runBlocking {
        answer = { ok("""{"sessionId":"session-new"}""") }
        assertEquals("session-new", remote.fork("session-a", 41L))
        val args = Json.parseToJsonElement(rpcBodies.last()).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("session-a", args["sessionId"]!!.jsonPrimitive.content)
        assertEquals(41L, args["atSeq"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun createAtPostsCwd() = runBlocking {
        answer = { ok("""{"sessionId":"session-p"}""") }
        assertEquals("session-p", remote.createSessionAt("/w/projects/p-1"))
        val args = Json.parseToJsonElement(rpcBodies.last()).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("/w/projects/p-1", args["cwd"]!!.jsonPrimitive.content)
    }

    @Test fun catalogParsesReminders() = runBlocking {
        answer = { ok("""[{"id":"schedule-1","kind":"daily","title":"台股晨報","prompt":"請給我今天的台股晨報","time":"08:00:00.000","timeZone":"Asia/Taipei","scheduledAt":"2026-11-07T00:00:00.000Z","sessionId":"session-h","status":"active"}]""") }
        val r = remote.scheduleCatalog().single()
        assertEquals(Reminder("schedule-1", "session-h", "台股晨報", "請給我今天的台股晨報", "daily", "08:00:00.000", "Asia/Taipei", 1794009600000L, true), r)
    }

    @Test fun deleteSendsTheTaskAndItsSession() = runBlocking {
        answer = { ok("""{"id":"schedule-1","deleted":true}""") }
        remote.scheduleDelete("session-h", "schedule-1")
        val args = Json.parseToJsonElement(rpcBodies.last()).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("schedule-1", args["id"]!!.jsonPrimitive.content)
        assertEquals("session-h", args["sessionId"]!!.jsonPrimitive.content)
    }
}
