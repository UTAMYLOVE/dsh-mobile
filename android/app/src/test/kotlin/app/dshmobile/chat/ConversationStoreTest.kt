package app.dshmobile.chat

import app.dshmobile.config.AgentModels
import app.dshmobile.remote.DshRpcException
import app.dshmobile.remote.PromptPart
import app.dshmobile.remote.StreamSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeChatRemote : ChatRemote {
    val signals = Channel<StreamSignal>(Channel.UNLIMITED)
    val prompts = mutableListOf<Triple<String, String, List<PromptPart>>>()
    var promptError: Exception? = null
    var cancelled = 0
    var pageCalls = mutableListOf<Pair<Long, Int>>()
    var pageAnswer: JsonElement = JsonNull
    var model: Pair<String, String>? = null
    val followQueue = ArrayDeque<Flow<StreamSignal>>()
    var followCalls = 0
    override fun follow(sessionId: String): Flow<StreamSignal> { followCalls++; return followQueue.removeFirstOrNull() ?: signals.receiveAsFlow() }
    override suspend fun prompt(sessionId: String, requestId: String, parts: List<PromptPart>) {
        promptError?.let { throw it }
        prompts += Triple(sessionId, requestId, parts)
    }
    override suspend fun cancel(sessionId: String) { cancelled++ }
    override suspend fun page(sessionId: String, beforeSeq: Long, maxMessages: Int): JsonElement { pageCalls += beforeSeq to maxMessages; return pageAnswer }
    var modelError: Exception? = null
    override suspend fun selectModel(sessionId: String, provider: String, model: String) { modelError?.let { throw it }; this.model = provider to model }
    override suspend fun upload(sessionId: String, name: String, bytes: ByteArray) = "r"
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationStoreTest {
    private val parts = listOf(PromptPart.Text("hello"))

    private fun TestScope.store(remote: FakeChatRemote, runningHint: () -> Boolean? = { null }, beforeSend: suspend () -> Unit = {}): ConversationStore {
        var n = 0
        return ConversationStore(
            remote, "s1", CoroutineScope(StandardTestDispatcher(testScheduler)), { "r${++n}" }, StandardTestDispatcher(testScheduler),
            runningHint = runningHint, beforeSend = { beforeSend() },
        )
    }

    @Test fun theBeforeSendHookRunsFirstAndItsFailureNeverBlocksTheSend() = runTest {
        val remote = FakeChatRemote()
        val order = mutableListOf<String>()
        val ok = store(remote, beforeSend = { order += "hook:" + remote.prompts.size })
        assertTrue(ok.send(parts, "hello", emptyList()))
        assertEquals(listOf("hook:0"), order)
        ok.close()
        val failing = store(remote, beforeSend = { throw IllegalStateException("permission/unavailable") })
        assertTrue("an Auto review switch that fails still sends", failing.send(parts, "again", emptyList()))
        assertEquals(2, remote.prompts.size)
        failing.close()
    }

    private fun FakeChatRemote.emit(v: JsonElement) { signals.trySend(StreamSignal.Value(v)) }

    @Test fun sendUsesQueueModeAndShowsAPendingBubble() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        assertTrue(store.send(parts, "hello", listOf("a.png")))
        assertEquals(listOf(PendingSend("r1", "hello", listOf("a.png"), queued = false)), store.pending.value)
        assertEquals(Triple("s1", "r1", parts), remote.prompts.single())
        store.close()
    }

    @Test fun thePendingBubbleLeavesWhenDshRecordsTheMessage() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshot(ev("turn/end", 1)))
        advanceUntilIdle()
        store.send(parts, "hello", emptyList())
        remote.emit(live(userMsg(5, "hello", rpcId = "r1")))
        advanceUntilIdle()
        assertTrue(store.pending.value.isEmpty())
        assertEquals(1, store.state.value.items.size)
        store.close()
    }

    @Test fun aPromptSentWhileRunningIsMarkedQueued() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshot()); remote.emit(live(ev("turn/start", 2)))
        advanceUntilIdle()
        store.send(parts, "more", emptyList())
        assertTrue(store.pending.value.single().queued)
        store.close()
    }

    @Test fun aRefusedPromptRemovesThePendingBubbleAndReportsTheCode() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.promptError = DshRpcException("session/model-unavailable", "x")
        assertFalse(store.send(parts, "hello", emptyList()))
        assertTrue(store.pending.value.isEmpty()); assertEquals("session/model-unavailable", store.sendError.value)
        remote.promptError = DshRpcException("https://secret.example/x?t=1", "x")
        store.send(parts, "hello", emptyList())
        assertEquals("refused", store.sendError.value)
        remote.promptError = java.io.IOException("boom")
        store.send(parts, "hello", emptyList())
        assertEquals("IOException", store.sendError.value)
        remote.promptError = null
        store.send(parts, "hello", emptyList())
        assertNull(store.sendError.value)
        store.close()
    }

    @Test fun stopCallsCancel() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        store.stop()
        assertEquals(1, remote.cancelled)
        store.close()
    }

    @Test fun reopenedKeepsPendingBubblesAndTakesTheNewSnapshot() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshot(userMsg(3, "hi"), ev("turn/start", 4))); remote.emit(start()); remote.emit(chunk("text-delta", "par"))
        advanceUntilIdle(); advanceTimeBy(100)
        assertEquals("par", store.state.value.live?.text)
        store.send(parts, "hello", emptyList())
        remote.signals.trySend(StreamSignal.Reopened)
        advanceUntilIdle()
        assertNull(store.state.value.live); assertFalse(store.state.value.running)
        assertEquals(1, store.state.value.items.size); assertEquals(1, store.pending.value.size)
        remote.emit(snapshot(userMsg(3, "hi"), userMsg(5, "hello", rpcId = "r1"), ev("turn/end", 6)))
        advanceUntilIdle()
        assertEquals(listOf("e3", "e5"), store.state.value.items.map { it.key })
        assertTrue(store.pending.value.isEmpty())
        store.close()
    }

    @Test fun liveDeltasArePublishedAtABoundedRate() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        var publishes = 0
        val collector = CoroutineScope(StandardTestDispatcher(testScheduler)).also {
            it.launch { store.state.collect { s -> if (s.live != null) publishes++ } }
        }
        remote.emit(start())
        repeat(10_000) { remote.emit(chunk("text-delta", "ab")) }
        advanceUntilIdle()
        assertEquals(20_000, store.state.value.live?.text?.length)
        assertTrue("publishes=$publishes", publishes <= 3)
        remote.emit(end()); advanceUntilIdle()
        assertNull(store.state.value.live)
        collector.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        store.close()
    }

    @Test fun loadOlderPrependsAPage() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshot(userMsg(30, "new"), hasMore = true)); advanceUntilIdle()
        remote.pageAnswer = snapshot(userMsg(10, "old"))
        store.loadOlder()
        assertEquals(listOf(30L to 200), remote.pageCalls)
        assertEquals(listOf("e10", "e30"), store.state.value.items.map { it.key })
        store.close()
    }

    @Test fun selectModelSendsProviderAndModel() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        store.selectModel(AgentModels.find("claude-sonnet-5-5")!!)
        assertEquals("anthropic" to "claude-sonnet-5-5", remote.model)
        store.close()
    }

    @Test fun aFailedFollowSetsFollowErrorThenRecoversAfterRefollow() = runTest {
        val remote = FakeChatRemote()
        remote.followQueue += flowOf(StreamSignal.Failed("session/gone", "x"))
        val store = store(remote)
        runCurrent()
        assertEquals("session/gone", store.followError.value)
        assertEquals(1, remote.followCalls)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, remote.followCalls)
        remote.emit(snapshot(userMsg(3, "hi"))); runCurrent()
        assertNull(store.followError.value)
        assertEquals(1, store.state.value.items.size)
        store.close()
    }

    @Test fun anEndedFollowSetsEndedAndBacksOffThenRetryRefollowsNow() = runTest {
        val remote = FakeChatRemote()
        remote.followQueue += flowOf(); remote.followQueue += flowOf(); remote.followQueue += flowOf()
        val store = store(remote)
        runCurrent()
        assertEquals("ended", store.followError.value); assertEquals(1, remote.followCalls)
        advanceTimeBy(1_001); runCurrent(); assertEquals(2, remote.followCalls)
        advanceTimeBy(1_500); runCurrent(); assertEquals(2, remote.followCalls)   // second wait is 2 s
        store.retry(); runCurrent()
        assertEquals(3, remote.followCalls)
        store.close()
    }

    @Test fun aThrowingFollowIsContained() = runTest {
        val remote = FakeChatRemote()
        remote.followQueue += flow { throw IllegalStateException("boom") }
        val store = store(remote)
        runCurrent()
        assertEquals("IllegalStateException", store.followError.value)
        advanceTimeBy(1_001); runCurrent()
        remote.emit(snapshot(userMsg(3, "hi"))); runCurrent()
        assertNull(store.followError.value)
        store.close()
    }

    @Test fun anOlderPageWithoutRecordsReportsAnUnexpectedAnswer() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshot(userMsg(30, "new"), hasMore = true)); runCurrent()
        remote.pageAnswer = JsonNull
        store.loadOlder()
        assertEquals("unexpected-answer", store.olderError.value)
        assertNull("not a send error", store.sendError.value)
        assertEquals(1, store.state.value.items.size)
        store.close()
    }

    @Test fun aBubbleDshNeverRecordsTimesOutAndCanBeRetried() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        store.send(parts, "hello", emptyList())
        advanceTimeBy(59_000); runCurrent()
        assertFalse(store.pending.value.single().failed)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(store.pending.value.single().failed)
        assertTrue(store.retrySend("r1"))
        assertEquals(listOf("r2"), store.pending.value.map { it.requestId })
        assertFalse(store.pending.value.single().failed)
        assertEquals(2, remote.prompts.size)
        store.close()
    }

    @Test fun aQueuedBubbleDuringALongTurnIsNotFailedUntilSixtySecondsAfterItEnds() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshot()); remote.emit(live(ev("turn/start", 2))); runCurrent()
        store.send(parts, "more", emptyList())
        assertTrue(store.pending.value.single().queued)
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertFalse(store.pending.value.single().failed)
        remote.emit(live(ev("turn/end", 3, """{"reason":{"kind":"completed"}}"""))); runCurrent()
        advanceTimeBy(59_000); runCurrent()
        assertFalse(store.pending.value.single().failed)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(store.pending.value.single().failed)
        store.close()
    }

    @Test fun anIdleSentBubbleStillFailsAtSixtySeconds() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshot(ev("turn/end", 1))); runCurrent()
        store.send(parts, "hello", emptyList())
        advanceTimeBy(59_000); runCurrent()
        assertFalse(store.pending.value.single().failed)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(store.pending.value.single().failed)
        store.close()
    }

    // ---- fix round 1: a committed reply stays until its message arrives ----

    private fun committed(seq: Long) =
        streamFrame("""{"type":"end","attemptId":"a1","revision":9,"outcome":{"kind":"committed","eventType":"assistant/message","seq":$seq}}""")

    private fun TestScope.streamHello(remote: FakeChatRemote) {
        remote.emit(snapshot(userMsg(3, "hi"), ev("turn/start", 4))); remote.emit(start()); remote.emit(chunk("text-delta", "Hello"))
        runCurrent(); advanceTimeBy(100); runCurrent()
    }

    @Test fun aCommittedReplyStaysUntilItsMessageArrives() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        streamHello(remote)
        remote.emit(chunk("text-delta", " world")) // not published yet when the end comes: the held reply is the whole text
        remote.emit(committed(7)); runCurrent()
        assertEquals("the reply does not blink out", "Hello world", store.state.value.live?.text)
        remote.emit(live(asstMsg(7, """{"type":"text","text":"Hello world"}"""))); runCurrent()
        assertNull(store.state.value.live)
        assertEquals("Hello world", (store.state.value.items.last() as ChatItem.Assistant).text)
        store.close()
    }

    @Test fun aCommittedReplyWhoseMessageNeverComesIsDroppedAfterTenSeconds() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        streamHello(remote)
        remote.emit(committed(9)); runCurrent()
        advanceTimeBy(9_990); runCurrent()
        assertEquals("Hello", store.state.value.live?.text)
        advanceTimeBy(20); runCurrent()
        assertNull(store.state.value.live)
        store.close()
    }

    @Test fun theNextBaselineDropsAHeldReply() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        streamHello(remote)
        remote.emit(committed(9)); runCurrent()
        remote.emit(snapshot(userMsg(3, "hi"))); runCurrent()
        assertNull(store.state.value.live)
        store.close()
    }

    @Test fun anAbandonedOrUnnumberedEndDropsTheReplyAtOnce() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        streamHello(remote)
        remote.emit(end("abandoned")); runCurrent()
        assertNull(store.state.value.live)
        remote.emit(start()); remote.emit(chunk("text-delta", "again")); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals("again", store.state.value.live?.text)
        remote.emit(end()); runCurrent() // committed without a seq: nothing to wait for
        assertNull(store.state.value.live)
        store.close()
    }

    @Test fun aMessageThatArrivedBeforeTheEndDropsTheReplyAtOnce() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        streamHello(remote)
        remote.emit(live(asstMsg(7, """{"type":"text","text":"Hello"}"""))); remote.emit(committed(7)); runCurrent()
        assertNull(store.state.value.live)
        store.close()
    }

    /** M7: a refused model change says so, so the chip can go back. */
    @Test fun aRefusedModelChangeReturnsFalse() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        assertTrue(store.selectModel(AgentModels.find("deepseek-v4-pro")!!))
        remote.modelError = DshRpcException("session/model-unavailable", "x")
        assertFalse(store.selectModel(AgentModels.find("deepseek-flash")!!))
        assertEquals("session/model-unavailable", store.sendError.value)
        store.close()
    }

    // ---- final review A-I1: joining a reply that is already streaming ----

    /** dsh's reconnect baseline: the in-flight attempt folded into the snapshot (history.js), then only chunks follow. */
    private fun snapshotWithAttempt(vararg events: String, stream: String, hasMore: Boolean = false): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(
        """{"type":"snapshot","header":{"version":4},"cursor":4,"records":[${events.joinToString(",") { """{"type":"event","event":$it}""" }}],"hasMore":$hasMore,""" +
            """"assistantStream":{"revision":6,"activeAttempt":{"attemptId":"a1","startedAfterSeq":4,"turn":1,"step":1,"nextIndex":4,"stream":$stream}}}""",
    )

    private val inFlight = """[{"type":"reasoning-chunks","time0":1,"index":0,"dt":[],"texts":["想一下"]},""" +
        """{"type":"chunk","time":2,"chunk":{"type":"block-start","index":1,"blockType":"text"}},""" +
        """{"type":"text-chunks","time0":3,"index":1,"dt":[1],"texts":["Hel","lo"]},""" +
        """{"type":"tool-call-chunks","time0":5,"index":2,"dt":[],"id":"c1","name":"bash","args":["{"]}]"""

    @Test fun aSnapshotWithAnActiveAttemptShowsTheReplyAndTakesItsChunks() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        remote.emit(snapshotWithAttempt(userMsg(3, "hi"), ev("turn/start", 4), stream = inFlight)); runCurrent()
        val seeded = store.state.value.live
        assertEquals("a1", seeded?.attemptId)
        assertEquals("Hello", seeded?.text)
        assertEquals("想一下", seeded?.reasoning)
        assertEquals(listOf("bash"), seeded?.toolNames)
        remote.emit(chunk("text-delta", " world", index = 4)); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals("the chunks after the snapshot are not dropped", "Hello world", store.state.value.live?.text)
        assertTrue(store.state.value.running)
        store.close()
    }

    @Test fun anActiveAttemptPastTheMessageCapStillCountsAsRunning() = runTest {
        val remote = FakeChatRemote(); val store = store(remote)
        // The 500-message window cut inside the turn: no turn/start among the records.
        remote.emit(snapshotWithAttempt(asstMsg(400, """{"type":"text","text":"step"}"""), stream = "[]", hasMore = true)); runCurrent()
        assertTrue(store.state.value.running)
        assertEquals("a1", store.state.value.live?.attemptId)
        store.close()
    }

    @Test fun aCappedSnapshotFallsBackToTheTreesRunningState() = runTest {
        val remote = FakeChatRemote(); val store = store(remote, runningHint = { true })
        remote.emit(snapshot(asstMsg(400, """{"type":"text","text":"tool step"}"""), hasMore = true)); runCurrent()
        assertTrue("the turn began before the window: STOP stays", store.state.value.running)
        remote.emit(live(ev("turn/end", 401, """{"reason":{"kind":"completed"}}"""))); runCurrent()
        assertFalse(store.state.value.running)
        store.close()
    }

    @Test fun aWholeSnapshotIgnoresAStaleRunningHint() = runTest {
        val remote = FakeChatRemote(); val store = store(remote, runningHint = { true })
        remote.emit(snapshot(userMsg(3, "hi"), ev("turn/end", 4))); runCurrent()
        assertFalse(store.state.value.running)
        store.close()
    }

    @Test fun aPermanentFollowFailureIsNotRetriedUntilAsked() = runTest {
        val remote = FakeChatRemote()
        remote.followQueue += flowOf(StreamSignal.Failed("session/not-found", "x"))
        val store = store(remote)
        runCurrent()
        assertEquals("session/not-found", store.followError.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("not retried on its own", 1, remote.followCalls)
        store.retry(); runCurrent()
        assertEquals(2, remote.followCalls)
        store.close()
    }

    // ---- final review A-I4: running out of memory while sending ----

    @Test fun runningOutOfMemoryWhileSendingIsReportedNotThrown() = runTest {
        val remote = object : ChatRemote by FakeChatRemote() {
            override suspend fun prompt(sessionId: String, requestId: String, parts: List<PromptPart>) = throw OutOfMemoryError("big")
        }
        var n = 0
        val store = ConversationStore(remote, "s1", CoroutineScope(StandardTestDispatcher(testScheduler)), { "r${++n}" }, StandardTestDispatcher(testScheduler))
        assertFalse(store.send(parts, "hello", emptyList()))
        assertTrue(store.pending.value.isEmpty())
        assertEquals(ConversationStore.OUT_OF_MEMORY, store.sendError.value)
        store.close()
    }
}
