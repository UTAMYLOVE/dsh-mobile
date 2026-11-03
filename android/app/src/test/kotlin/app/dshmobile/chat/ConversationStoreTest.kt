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
    override suspend fun selectModel(sessionId: String, provider: String, model: String) { this.model = provider to model }
    override suspend fun upload(sessionId: String, name: String, bytes: ByteArray) = "r"
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationStoreTest {
    private val parts = listOf(PromptPart.Text("hello"))

    private fun TestScope.store(remote: FakeChatRemote): ConversationStore {
        var n = 0
        return ConversationStore(remote, "s1", CoroutineScope(StandardTestDispatcher(testScheduler)), { "r${++n}" }, StandardTestDispatcher(testScheduler))
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
        assertEquals("unexpected-answer", store.sendError.value)
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
}
