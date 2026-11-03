package app.dshmobile.chat

import app.dshmobile.remote.HostEvent
import app.dshmobile.remote.MuxState
import app.dshmobile.remote.SearchHit
import app.dshmobile.remote.SessionSummary
import app.dshmobile.remote.StreamSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal fun sum(
    id: String, parent: String? = null, origin: String? = null, updatedAt: Long? = 1, running: Boolean? = false,
    title: String? = null, lastLine: String? = null, model: String? = null, nextModel: String? = null,
) = SessionSummary(id, parent, origin, updatedAt, running, null, title, lastLine, model, nextModel, null)

internal fun json(s: String): JsonElement = Json.parseToJsonElement(s)

internal class FakeIndexRemote(var list: List<SessionSummary> = emptyList()) : IndexRemote {
    val listCalls = AtomicInteger()
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 64)
    val controlFrames = MutableSharedFlow<StreamSignal>(replay = 16, extraBufferCapacity = 16)
    val workspaceFrames = MutableSharedFlow<StreamSignal>(replay = 16, extraBufferCapacity = 16)
    override val muxState = MutableStateFlow(MuxState.OPEN)
    override val eventsClientId = MutableStateFlow<String?>("c1")
    val created = CopyOnWriteArrayList<String?>()
    val renamed = CopyOnWriteArrayList<Pair<String, String>>()
    val archived = CopyOnWriteArrayList<String>()
    var hits: List<SearchHit> = emptyList()

    override val hostEvents: Flow<HostEvent> get() = events
    override suspend fun listSessions(): List<SessionSummary> {
        listCalls.incrementAndGet()
        val snapshot = list
        gate?.await()
        return snapshot
    }
    override suspend fun searchSessions(query: String) = hits
    @Volatile var createGate: CompletableDeferred<Unit>? = null
    override suspend fun createSession(workspaceId: String?): String { created += workspaceId; createGate?.await(); return "new${created.size}" }
    override suspend fun rename(sessionId: String, title: String): String { renamed += sessionId to title; return title }
    override suspend fun archive(sessionId: String) { archived += sessionId }
    val controlOpens = AtomicInteger()
    /** When set, every `session/control` open gets this instead of [controlFrames]. */
    @Volatile var controlOverride: Flow<StreamSignal>? = null
    override fun control(): Flow<StreamSignal> { controlOpens.incrementAndGet(); return controlOverride ?: controlFrames }
    override fun workspaces(): Flow<StreamSignal> = workspaceFrames
    fun control(s: String) { controlFrames.tryEmit(StreamSignal.Value(json(s))) }
    fun workspace(s: String) { workspaceFrames.tryEmit(StreamSignal.Value(json(s))) }
}

internal fun waitUntil(what: String, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + 5_000
    while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
    assertTrue("not met in time: $what", cond())
}

class SessionTreeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    @Test fun theListIsTheBaselineIncludingChildren() {
        val remote = FakeIndexRemote(listOf(sum("s1"), sum("c1", parent = "s1", origin = "subagent")))
        val tree = SessionTree(remote, scope)
        waitUntil("baseline") { tree.state.value.summaries.size == 2 }
        assertEquals("s1", tree.state.value.summaries["c1"]?.parentId)
    }

    @Test fun controlProjectionsKeepOnlyTheUsedKeys() {
        val s = SessionTreeReducer.applyControl(
            SessionTreeState(),
            json("""{"type":"baseline","value":{"projections":{"s1":{"asOfSeq":3,"values":{"title":"hi","tokenUsage":{"outputTokens":1},"turnOutline":[],"modelSelection":{},"subagent":{"label":"x"}}}}}}"""),
        )
        assertEquals(setOf("title", "turnOutline", "modelSelection", "subagent"), s.projections.getValue("s1").keys)
        val more = SessionTreeReducer.applyControl(s, json("""{"type":"projection","sessionId":"s1","key":"tokenUsage","value":{},"seq":4}"""))
        assertEquals(s, more)
        val title = SessionTreeReducer.applyControl(s, json("""{"type":"projection","sessionId":"s2","key":"title","value":"yo","seq":4}"""))
        assertEquals("yo", (title.projections.getValue("s2").getValue("title") as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun workspaceArchivedFrameMarksSessions() {
        val s = SessionTreeReducer.applyWorkspace(SessionTreeState(), json("""{"type":"archived","archivedSessionIds":["s2"]}"""))
        assertEquals(setOf("s2"), s.archived)
        val b = SessionTreeReducer.applyWorkspace(s, json("""{"type":"baseline","value":{"items":[],"archivedSessionIds":["s3"]}}"""))
        assertEquals(setOf("s3"), b.archived)
    }

    @Test fun workspaceIdIsTheAppWorkspace() {
        val s = SessionTreeReducer.applyWorkspace(
            SessionTreeState(),
            json("""{"type":"baseline","value":{"items":[{"workspaceId":"w-other","path":"/tmp/x"},{"workspaceId":"w-app","path":"/root/workspace"}],"archivedSessionIds":[]}}"""),
        )
        assertEquals("w-app", s.workspaceId)
        val up = SessionTreeReducer.applyWorkspace(SessionTreeState(), json("""{"type":"upsert","workspace":{"workspaceId":"w2","path":"/root/workspace"}}"""))
        assertEquals("w2", up.workspaceId)
    }

    @Test fun addedStatusAndRemovedEventsUpdateTheTree() {
        var s = SessionTreeState()
        s = SessionTreeReducer.applyEvent(s, HostEvent.SessionAdded(sum("s1")))
        s = SessionTreeReducer.applyEvent(s, HostEvent.SessionAdded(sum("c1", parent = "s1", origin = "subagent")))
        assertEquals(setOf("s1", "c1"), s.summaries.keys)
        s = SessionTreeReducer.applyEvent(s, HostEvent.SessionStatus("s1", true))
        assertEquals(true, s.summaries["s1"]?.running)
        s = SessionTreeReducer.applyEvent(s, HostEvent.SessionStatus("nobody", true))
        assertFalse("nobody" in s.summaries)
        s = SessionTreeReducer.applyEvent(s, HostEvent.SessionRemoved("c1"))
        assertEquals(setOf("s1"), s.summaries.keys)
    }

    @Test fun aReconnectRefetchesTheBaseline() {
        val remote = FakeIndexRemote(listOf(sum("s1")))
        remote.muxState.value = MuxState.CONNECTING
        val tree = SessionTree(remote, scope)
        Thread.sleep(50)
        assertEquals(0, remote.listCalls.get())
        remote.muxState.value = MuxState.OPEN
        waitUntil("first list") { remote.listCalls.get() == 1 && tree.state.value.summaries.isNotEmpty() }
        remote.muxState.value = MuxState.RECONNECTING
        Thread.sleep(100) // a StateFlow conflates: give the collector time to see the dip
        remote.list = listOf(sum("s1"), sum("s9"))
        remote.muxState.value = MuxState.OPEN
        waitUntil("second list") { remote.listCalls.get() == 2 && "s9" in tree.state.value.summaries }
    }

    @Test fun reopeningEventsRefetchesToo() {
        val remote = FakeIndexRemote(listOf(sum("s1")))
        val tree = SessionTree(remote, scope)
        waitUntil("first") { remote.listCalls.get() == 1 }
        remote.eventsClientId.value = null
        remote.eventsClientId.value = "c2"
        waitUntil("second") { remote.listCalls.get() >= 2 }
        assertTrue(tree.state.value.summaries.isNotEmpty())
    }

    @Test fun eventsDuringTheListCallWinOverTheList() {
        val remote = FakeIndexRemote(listOf(sum("s1", running = false), sum("s2")))
        remote.gate = CompletableDeferred()
        val tree = SessionTree(remote, scope)
        waitUntil("list in flight") { remote.listCalls.get() == 1 }
        remote.events.tryEmit(HostEvent.SessionStatus("s1", true))
        remote.events.tryEmit(HostEvent.SessionRemoved("s2"))
        remote.events.tryEmit(HostEvent.SessionAdded(sum("s3")))
        waitUntil("events seen") { "s3" in tree.state.value.summaries }
        remote.gate!!.complete(Unit)
        waitUntil("list applied") { "s1" in tree.state.value.summaries }
        val s = tree.state.value
        assertEquals(true, s.summaries["s1"]?.running)
        assertFalse("s2" in s.summaries)
        assertTrue("s3" in s.summaries)
    }

    @Test fun brokenFramesAreIgnored() {
        val s = SessionTreeState(workspaceId = "w")
        for (f in listOf(null, json("1"), json("[]"), json("""{"type":"baseline","value":5}"""), json("""{"type":"projection","key":3}"""), json("""{"type":"mystery"}"""))) {
            assertEquals(s, SessionTreeReducer.applyControl(s, f))
            assertEquals(s, SessionTreeReducer.applyWorkspace(s, f))
        }
        assertNull(SessionTreeReducer.applyWorkspace(SessionTreeState(), json("""{"type":"baseline","value":{"items":"x"}}""")).workspaceId)
    }

    @Test fun framesArriveThroughTheRemoteStreams() {
        val remote = FakeIndexRemote(listOf(sum("s1")))
        val tree = SessionTree(remote, scope)
        remote.control("""{"type":"baseline","value":{"projections":{"s1":{"values":{"title":"T"}}}}}""")
        remote.workspace("""{"type":"baseline","value":{"items":[{"workspaceId":"w","path":"/root/workspace"}],"archivedSessionIds":["s1"]}}""")
        waitUntil("frames") { tree.state.value.workspaceId == "w" && tree.state.value.projections.containsKey("s1") }
        assertEquals(setOf("s1"), tree.state.value.archived)
    }

    /** A status for a session the tree does not know (made elsewhere, e.g. by the heartbeat) refetches the list, once per burst. */
    @Test fun aStatusForAnUnknownSessionRefetchesTheListOnce() {
        val remote = FakeIndexRemote(listOf(sum("s1")))
        val tree = SessionTree(remote, scope, unknownRefreshMs = 200)
        waitUntil("baseline") { remote.listCalls.get() == 1 && "s1" in tree.state.value.summaries }
        remote.list = listOf(sum("s1"), sum("s2", running = true))
        repeat(3) { remote.events.tryEmit(HostEvent.SessionStatus("s2", true)) }
        waitUntil("s2 known") { "s2" in tree.state.value.summaries }
        Thread.sleep(500)
        assertEquals("one list call for the burst", 2, remote.listCalls.get())
        assertEquals(true, tree.state.value.summaries["s2"]?.running)
        remote.events.tryEmit(HostEvent.SessionStatus("s1", true))
        Thread.sleep(500)
        assertEquals("a known session's status needs no list call", 2, remote.listCalls.get())
        assertEquals(true, tree.state.value.summaries["s1"]?.running)
    }

    /** Final review A: a stream dsh refuses for good (an unknown endpoint, a bad request) is not reopened forever. */
    @Test fun aPermanentlyRefusedStreamIsNotReopened() {
        val remote = FakeIndexRemote()
        remote.controlOverride = kotlinx.coroutines.flow.flowOf(StreamSignal.Failed("gateway/bad-request", "x"))
        SessionTree(remote, scope, reopenMs = 20)
        Thread.sleep(400)
        assertEquals(1, remote.controlOpens.get())
    }

    @Test fun aTransientStreamFailureIsReopened() {
        val remote = FakeIndexRemote()
        remote.controlOverride = kotlinx.coroutines.flow.flowOf(StreamSignal.Failed("gateway/internal", "x"))
        SessionTree(remote, scope, reopenMs = 20)
        waitUntil("reopened") { remote.controlOpens.get() >= 3 }
    }
}
