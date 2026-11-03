package app.dshmobile.remote

import app.dshmobile.runtime.DshClient
import app.dshmobile.runtime.FakeDshServer
import app.dshmobile.runtime.FakeMux
import app.dshmobile.runtime.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MuxConnectionTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val fake = FakeMux()
    @Volatile private var answer: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val dsh = FakeDshServer(mux = fake, rpc = { answer(it) })
    private val client = DshClient(OkHttpClient(), dsh.launch)
    private val relogins = AtomicInteger()
    private val made = CopyOnWriteArrayList<MuxConnection>()

    @After fun cleanup() {
        made.forEach { it.close() }
        scope.cancel()
        dsh.close()
    }

    /** A logged-in client's mux, the way the brief sets it up (`backoffMs = listOf(0)`). */
    private fun newMux(backoffMs: List<Long> = listOf(0)): MuxConnection {
        runBlocking { client.login() }
        return MuxConnection(
            open = { client.openWebSocket("/api/remote.mux", it) },
            relogin = { relogins.incrementAndGet(); client.login() },
            scope = scope,
            backoffMs = backoffMs,
        ).also { made += it }
    }

    private inner class Collected(flow: Flow<StreamSignal>) {
        val signals = CopyOnWriteArrayList<StreamSignal>()
        val job: Job = scope.launch { flow.collect { signals += it } }
        val values get() = signals.filterIsInstance<StreamSignal.Value>().map { it.value }
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun streamOpensWithAFreshIdAndRoutesItsItems() = runBlocking {
        val mux = newMux()
        val control = Collected(mux.stream("session/control"))
        val args = buildJsonObject { put("request", buildJsonObject { put("sessionId", "session-1") }) }
        val follow = Collected(mux.stream("session/follow", args))
        eventually("two opens") { fake.received.count { it["type"] == JsonPrimitive("open") } == 2 }
        assertEquals(MuxState.OPEN, mux.state.value)

        val controlId = fake.opened("session/control").single()
        val followId = fake.opened("session/follow").single()
        assertNotEquals(controlId, followId)
        assertTrue(controlId, Regex("s[0-9]+").matches(controlId))
        assertTrue(followId, Regex("s[0-9]+").matches(followId))
        for (frame in fake.received) assertEquals(setOf("type", "streamId", "endpoint", "payload"), frame.keys)
        assertEquals(json("""{"args":{}}"""), fake.received.first { it["streamId"] == JsonPrimitive(controlId) }["payload"])
        assertEquals(buildJsonObject { put("args", args) }, fake.received.first { it["streamId"] == JsonPrimitive(followId) }["payload"])

        fake.sendItem(controlId, """{"type":"baseline","value":{"projections":{}}}""")
        fake.sendItem(followId, """{"type":"snapshot"}""")
        fake.send("""{"type":"item","streamId":"$followId"}""")
        fake.sendItem("s999", """{"type":"stray"}""")
        eventually("items routed") { control.signals.size == 1 && follow.signals.size == 2 }
        delay(100)
        assertEquals(listOf(json("""{"type":"baseline","value":{"projections":{}}}""")), control.values)
        assertEquals(listOf(json("""{"type":"snapshot"}"""), null), follow.values)

        // A new stream never reuses an id; the upgrade carries the cookie and no Origin header.
        val third = Collected(mux.stream("workspace/follow"))
        eventually("third open") { fake.opened("workspace/follow").isNotEmpty() }
        assertTrue(fake.opened("workspace/follow").single() !in setOf(controlId, followId))
        val upgrade = dsh.requests.first { it.path == "/api/remote.mux" }
        assertNull(upgrade.getHeader("Origin"))
        assertTrue(upgrade.getHeader("Cookie")!!.contains("dsh-auth-x=signed"))
        assertTrue(fake.violations.toString(), fake.violations.isEmpty())
        third.job.cancel()
    }

    @Test fun anErrorFrameFailsOnlyThatStream() = runBlocking {
        val mux = newMux()
        val a = Collected(mux.stream("session/follow"))
        val b = Collected(mux.stream("session/control"))
        eventually("opens") { fake.opened("session/follow").size == 1 && fake.opened("session/control").size == 1 }
        val aId = fake.opened("session/follow").single()
        val bId = fake.opened("session/control").single()

        fake.sendError(aId, "session/not-found", "no such session")
        withTimeout(5_000) { a.job.join() }
        assertEquals(StreamSignal.Failed("session/not-found", (a.signals.last() as StreamSignal.Failed).message), a.signals.last())
        assertFalse(a.job.isCancelled)

        fake.sendItem(bId, """{"n":1}""")
        eventually("b still flows") { b.values == listOf(json("""{"n":1}""")) }
        assertTrue(b.job.isActive)
        delay(100)
        assertTrue("the server ended it; no cancel", fake.received.none { it["type"] == JsonPrimitive("cancel") })
    }

    @Test fun cancellingTheCollectorSendsCancel() = runBlocking {
        val mux = newMux()
        val a = Collected(mux.stream("session/follow"))
        eventually("open") { fake.opened("session/follow").isNotEmpty() }
        val id = fake.opened("session/follow").single()
        a.job.cancel()
        eventually("cancel frame") { fake.received.any { it["type"] == JsonPrimitive("cancel") } }
        assertEquals(json("""{"type":"cancel","streamId":"$id"}"""), fake.received.last())
        assertTrue(fake.violations.toString(), fake.violations.isEmpty())
    }

    @Test fun endCompletesTheFlow() = runBlocking {
        val mux = newMux()
        val a = Collected(mux.stream("workspace/follow"))
        eventually("open") { fake.opened("workspace/follow").isNotEmpty() }
        val id = fake.opened("workspace/follow").single()
        fake.sendItem(id, """{"type":"baseline"}""")
        fake.sendEnd(id)
        withTimeout(5_000) { a.job.join() }
        assertFalse(a.job.isCancelled)
        assertEquals(listOf(StreamSignal.Value(json("""{"type":"baseline"}"""))), a.signals.toList())
        delay(100)
        assertTrue(fake.received.none { it["type"] == JsonPrimitive("cancel") })
    }

    @Test fun aDroppedSocketReconnectsAndReopensLiveStreams() = runBlocking {
        // A visible backoff so the RECONNECTING state can be observed (the brief's listOf(0) reconnects within a frame).
        val mux = newMux(backoffMs = listOf(300))
        val states = CopyOnWriteArrayList<MuxState>()
        val watcher = scope.launch { mux.state.collect { states += it } }
        val follow = Collected(mux.stream("session/follow"))
        val ended = Collected(mux.stream("workspace/follow"))
        eventually("opens") { fake.opened("session/follow").size == 1 && fake.opened("workspace/follow").size == 1 }
        val id = fake.opened("session/follow").single()
        fake.sendItem(id, """{"type":"snapshot","n":1}""")
        fake.sendEnd(fake.opened("workspace/follow").single())
        withTimeout(5_000) { ended.job.join() }
        eventually("first snapshot") { follow.signals.size == 1 }

        fake.closeAll()
        eventually("reconnecting") { mux.state.value == MuxState.RECONNECTING }
        eventually("reopened") { fake.opened("session/follow").size == 2 }
        assertEquals(MuxState.OPEN, mux.state.value)
        assertEquals(listOf(id, id), fake.opened("session/follow"))
        assertEquals("an ended stream is not reopened", 1, fake.opened("workspace/follow").size)
        fake.sendItem(id, """{"type":"snapshot","n":2}""")
        eventually("second snapshot") { follow.signals.size == 3 }
        assertEquals(
            listOf(
                StreamSignal.Value(json("""{"type":"snapshot","n":1}""")),
                StreamSignal.Reopened,
                StreamSignal.Value(json("""{"type":"snapshot","n":2}""")),
            ),
            follow.signals.toList(),
        )
        assertTrue(states.toString(), states.indexOf(MuxState.RECONNECTING) in 1 until states.lastIndexOf(MuxState.OPEN))

        val later = Collected(mux.stream("session/control"))
        eventually("fresh id after reconnect") { fake.opened("session/control").isNotEmpty() }
        assertTrue(fake.opened("session/control").single() !in setOf(id, fake.opened("workspace/follow").single()))
        assertTrue(fake.violations.toString(), fake.violations.isEmpty())
        later.job.cancel()
        watcher.cancel()
    }

    @Test fun a401UpgradeLogsInAgainBeforeRetrying() = runBlocking {
        val mux = newMux()
        val follow = Collected(mux.stream("session/follow"))
        eventually("open") { fake.opened("session/follow").size == 1 }
        assertEquals(0, relogins.get())
        val loginsBefore = dsh.requests.count { it.path == "/?token=good" }

        fake.rejectNextUpgradeWith = 401
        fake.closeAll()
        eventually("reopened after the 401") { fake.opened("session/follow").size == 2 }
        assertEquals(MuxState.OPEN, mux.state.value)
        assertEquals(1, relogins.get())
        assertEquals(loginsBefore + 1, dsh.requests.count { it.path == "/?token=good" })
        assertEquals(3, fake.upgrades.get())
        assertTrue(follow.signals.contains(StreamSignal.Reopened))
    }

    @Test fun closeStopsReconnectingAndFailsNothingLoudly() = runBlocking {
        val mux = newMux()
        val follow = Collected(mux.stream("session/follow"))
        eventually("open") { fake.opened("session/follow").isNotEmpty() }
        mux.close()
        assertEquals(MuxState.CLOSED, mux.state.value)
        withTimeout(5_000) { follow.job.join() }
        assertFalse("closing completes the flow without an exception", follow.job.isCancelled)
        assertTrue(follow.signals.none { it is StreamSignal.Failed })
        val upgrades = fake.upgrades.get()
        fake.closeAll()
        delay(300)
        assertEquals(upgrades, fake.upgrades.get())
        assertEquals(MuxState.CLOSED, mux.state.value)
        assertEquals(emptyList<StreamSignal>(), withTimeout(1_000) { mux.stream("session/control").toList() })
        mux.close() // idempotent
    }

    @Test fun failuresNeverCarryTheLaunchUrl() = runBlocking {
        client.login()
        val mux = MuxConnection({ client.openWebSocket("/api/remote.mux", it) }, { client.login() }, scope, listOf(0, 20))
            .also { made += it }
        val a = Collected(mux.stream("session/follow"))
        val b = Collected(mux.stream("session/control"))
        eventually("opens") { fake.opened("session/control").isNotEmpty() && fake.opened("session/follow").isNotEmpty() }
        fake.sendError(fake.opened("session/follow").single(), "session/not-found", "see ${dsh.launch.url}")
        withTimeout(5_000) { a.job.join() }

        dsh.close() // dsh is gone: every reconnect fails
        eventually("reconnecting") { mux.state.value == MuxState.RECONNECTING }
        delay(300)
        val failure = mux.lastFailure
        assertNotNull(failure)
        val texts = (a.signals + b.signals).map { it.toString() } + failure!!
        for (text in texts) {
            assertFalse(text, text.contains("token="))
            assertFalse(text, text.contains(dsh.launch.url))
        }
        assertTrue(b.job.isActive)
    }

    @Test fun muxAndEventErrorCodesAreSanitised() = runBlocking {
        val mux = newMux()
        val a = Collected(mux.stream("session/follow"))
        eventually("open") { fake.opened("session/follow").isNotEmpty() }
        fake.sendError(fake.opened("session/follow").single(), "http://x/?token=y", "http://x/?token=y")
        withTimeout(5_000) { a.job.join() }
        val failed = a.signals.single() as StreamSignal.Failed
        assertEquals("refused", failed.code)
        assertFalse(failed.message, failed.message.contains("token"))

        // `$events/result` answering ok:false through the real DshRemote.
        answer = {
            MockResponse().setResponseCode(200).setBody(
                """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":{"code":"http://x/?token=y","message":"http://x/?token=y","details":{}}}}""",
            )
        }
        fake.onOpen = { id, endpoint, _ ->
            if (endpoint == "\$events") {
                fake.sendItem(id, """{"type":"ready","clientId":"c1","host":{"home":"/root"}}""")
                fake.sendItem(id, """{"type":"waterfall","event":"approval/request","eventId":"e1","agentId":"session-1","request":{"toolName":"bash","callId":"t1","reason":"r"}}""")
            }
        }
        val remote = DshRemote(client, scope)
        try {
            eventually("prompt") { remote.events.prompts.value.isNotEmpty() }
            try {
                remote.events.allowOnce(remote.events.prompts.value.single() as PendingPrompt.Approval)
                fail("ok:false must throw")
            } catch (e: DshRpcException) {
                assertEquals("refused", e.code)
                assertFalse(e.toString(), e.toString().contains("token"))
            }
            assertEquals(1, remote.events.prompts.value.size)
        } finally {
            remote.close()
        }
    }

    @Test fun dshRemoteOpensFollowControlAndWorkspacesWithTheDocumentedArgs() = runBlocking {
        val remote = DshRemote(client, scope)
        try {
            val follow = Collected(remote.follow("session-1"))
            val control = Collected(remote.control())
            val workspaces = Collected(remote.workspaces())
            eventually("four opens") { fake.received.count { it["type"] == JsonPrimitive("open") } == 4 }
            assertEquals(MuxState.OPEN, remote.muxState.value)
            fun args(endpoint: String): JsonObject =
                fake.received.single { it["endpoint"] == JsonPrimitive(endpoint) }["payload"]!!.jsonObject["args"]!!.jsonObject
            assertEquals(
                json("""{"request":{"address":{"kind":"session","sessionId":"session-1"},"assistantStream":true,"maxMessages":500,"turnWindow":{"minMessages":50,"minTurns":2}}}"""),
                args("session/follow"),
            )
            assertEquals(json("{}"), args("session/control"))
            assertEquals(json("{}"), args("workspace/follow"))
            assertEquals(json("{}"), args("\$events"))
            assertEquals("the mux logged in once, before its first upgrade", 1, dsh.requests.count { it.path == "/?token=good" })
            assertTrue(dsh.unauthenticated.isEmpty())
            listOf(follow, control, workspaces).forEach { it.job.cancel() }
        } finally {
            remote.close()
        }
        assertEquals(MuxState.CLOSED, remote.muxState.value)
    }

    @Test fun cancellingTheScopeWithoutCloseStillClosesTheSocket() = runBlocking {
        client.login()
        val own = CoroutineScope(SupervisorJob())
        val mux = MuxConnection({ client.openWebSocket("/api/remote.mux", it) }, { client.login() }, own, listOf(0))
        val follow = Collected(mux.stream("session/follow"))
        eventually("open") { fake.opened("session/follow").isNotEmpty() && fake.openSockets == 1 }

        own.cancel() // the shell session's scope ends; nobody calls close()
        eventually("socket closed") { fake.openSockets == 0 }
        eventually("closed") { mux.state.value == MuxState.CLOSED }
        val upgrades = fake.upgrades.get()
        delay(300)
        assertEquals("no reconnect after the scope ended", upgrades, fake.upgrades.get())
        assertEquals(0, fake.openSockets)
        assertEquals(emptyList<StreamSignal>(), withTimeout(1_000) { mux.stream("session/control").toList() })
        follow.job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class) // getCompleted
    @Test fun closeWhileTheUpgradeIsStartingCancelsTheNewSocket() = runBlocking {
        client.login()
        // The connector starts inside the constructor; it may only reach open() once the test holds the instance.
        val constructed = CompletableDeferred<MuxConnection>()
        val opened = CopyOnWriteArrayList<okhttp3.WebSocket>()
        val mux = MuxConnection(
            open = { listener ->
                client.openWebSocket("/api/remote.mux", listener).also {
                    opened += it
                    constructed.getCompleted().close() // close() lands between the attempt being registered and open() returning
                }
            },
            relogin = { client.login() },
            scope = scope,
            backoffMs = listOf(0),
            beforeConnect = { constructed.await() },
        ).also { made += it }
        constructed.complete(mux)
        eventually("the upgrade started") { opened.isNotEmpty() }
        assertEquals(MuxState.CLOSED, mux.state.value)
        delay(500)
        assertEquals("the socket opened during close() is cancelled", 0, fake.openSockets)
        assertEquals(1, opened.size)
        assertFalse("a cancelled socket refuses to send", opened.single().send("{}"))
    }

    @Test fun aSocketThatClosesRightAfterOpeningBacksOff() = runBlocking {
        val mux = newMux(backoffMs = listOf(50, 400))
        val flapping = java.util.concurrent.atomic.AtomicBoolean(true)
        fake.onOpen = { _, _, _ -> if (flapping.get()) fake.closeAll() }
        val follow = Collected(mux.stream("session/follow"))
        delay(1_000)
        val flaps = fake.upgrades.get()
        // Without the stable-open rule every flap would retry after 50 ms (about 15 tries a second).
        assertTrue("$flaps upgrades in the first second", flaps in 2..5)

        flapping.set(false)
        eventually("stays open", timeoutMs = 2_000) { mux.state.value == MuxState.OPEN && fake.openSockets == 1 }
        delay(500) // longer than the last backoff value: the next drop starts the list again
        val before = fake.opened("session/follow").size
        fake.closeAll()
        val dropped = System.nanoTime()
        eventually("reopened") { fake.opened("session/follow").size == before + 1 }
        val reopenMs = (System.nanoTime() - dropped) / 1_000_000
        assertTrue("reopened after $reopenMs ms; a stable socket restarts at the first backoff", reopenMs < 300)
        follow.job.cancel()
    }
}
