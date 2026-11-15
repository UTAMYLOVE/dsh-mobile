package app.dshmobile.ui.shell

import app.dshmobile.remote.MuxState
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.security.SessionToken
import app.dshmobile.service.RunningDsh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSessionHolderTest {
    private class FakeRemote(
        /** What `session/control` sends (each time it is opened); none by default. */
        private val controlFrames: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    ) : ShellRemote {
        override suspend fun listSessions(): List<app.dshmobile.remote.SessionSummary> = emptyList()
        override suspend fun searchSessions(query: String): List<app.dshmobile.remote.SearchHit> = emptyList()
        override suspend fun createSession(workspaceId: String?): String = ""
        override suspend fun rename(sessionId: String, title: String): String = title
        override suspend fun archive(sessionId: String) = Unit
        override fun control(): kotlinx.coroutines.flow.Flow<app.dshmobile.remote.StreamSignal> =
            kotlinx.coroutines.flow.flowOf(*controlFrames.map { app.dshmobile.remote.StreamSignal.Value(it) }.toTypedArray())
        override fun workspaces(): kotlinx.coroutines.flow.Flow<app.dshmobile.remote.StreamSignal> = kotlinx.coroutines.flow.emptyFlow()
        override val hostEvents: kotlinx.coroutines.flow.Flow<app.dshmobile.remote.HostEvent> = kotlinx.coroutines.flow.emptyFlow()
        override val muxState: StateFlow<MuxState> = MutableStateFlow(MuxState.CONNECTING)
        var closes = 0
        override fun close() { closes++ }
        /** Session ids whose follow is being collected right now. */
        val following: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override fun follow(sessionId: String): kotlinx.coroutines.flow.Flow<app.dshmobile.remote.StreamSignal> = kotlinx.coroutines.flow.flow {
            following += sessionId
            try { kotlinx.coroutines.awaitCancellation() } finally { following -= sessionId }
        }
        override suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) = Unit
        /** Commands and prompts in the order dsh got them. */
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun prompt(sessionId: String, requestId: String, parts: List<app.dshmobile.remote.PromptPart>) { calls += "prompt $sessionId" }
        override suspend fun cancel(sessionId: String) = Unit
        override suspend fun page(sessionId: String, beforeSeq: Long, maxMessages: Int): kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull
        override suspend fun selectModel(sessionId: String, provider: String, model: String) = Unit
        override suspend fun upload(sessionId: String, name: String, bytes: ByteArray): String = ""
        val commands = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun runCommand(sessionId: String, line: String) { commands += sessionId to line; calls += "$line $sessionId" }
    }

    private val made = mutableListOf<Pair<RunningDsh, FakeRemote>>()
    private val holder = ShellSessionHolder { running ->
        val remote = FakeRemote()
        made += running to remote
        ShellSession(remote, events = null, scope = CoroutineScope(Job()))
    }

    private fun running(port: Int) = RunningDsh(port, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:$port/?token=t$port", port))

    @Test fun sameLaunchUrlReturnsTheSameSession() {
        assertNull(holder.current.value)
        val a = holder.attach(running(1000))
        val again = holder.attach(running(1000))
        assertSame(a, again)
        assertSame(a, holder.current.value)
        assertEquals(1, made.size)
        assertEquals(0, made.single().second.closes)
        assertEquals("http://127.0.0.1:1000/?token=t1000", holder.launch?.url)
    }

    @Test fun aNewLaunchUrlClosesTheOldSession() {
        val a = holder.attach(running(1000))
        val b = holder.attach(running(1001))
        assertTrue(a !== b)
        assertSame(b, holder.current.value)
        assertEquals(2, made.size)
        assertEquals("the old remote is closed", 1, made[0].second.closes)
        assertFalse("and its scope ends with it", a.scope.isActive)
        assertEquals(0, made[1].second.closes)
        assertTrue(b.scope.isActive)
        assertEquals(1001, holder.launch?.port)
    }

    @Test fun detachClosesAndClears() {
        val a = holder.attach(running(1000))
        holder.detach()
        assertNull(holder.current.value)
        assertNull(holder.launch)
        assertEquals(1, made.single().second.closes)
        assertFalse(a.scope.isActive)
        holder.detach() // nothing attached: nothing happens
        a.close() // closing twice closes the remote once
        assertEquals(1, made.single().second.closes)
        // The same dsh attached again after a detach gets a fresh session.
        val b = holder.attach(running(1000))
        assertTrue(a !== b)
        assertEquals(2, made.size)
    }

    @Test fun conversationsAreCachedUpToThreeAndTheLeastRecentlyUsedIsClosed() {
        val remote = FakeRemote()
        val session = ShellSession(remote, events = null, scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Default))
        try {
            val a = session.conversation("a")
            assertSame(a, session.conversation("a"))
            val b = session.conversation("b")
            session.conversation("c")
            session.conversation("a") // a is used again: b is now the least recent
            waitFor { remote.following == setOf("a", "b", "c") }
            session.conversation("d")
            waitFor { remote.following == setOf("a", "c", "d") }
            assertSame("a stayed cached", a, session.conversation("a"))
            assertTrue("b was closed, a new store follows it", b !== session.conversation("b"))
            waitFor { remote.following == setOf("a", "b", "d") }
        } finally {
            session.close()
        }
        waitFor { remote.following.isEmpty() }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue(cond())
    }

    /** Final review B2-M11: a conversation with a send dsh has not recorded (or that failed) is not evicted with it. */
    @Test fun aConversationHoldingASendIsNotEvicted() {
        val remote = FakeRemote()
        val session = ShellSession(remote, events = null, scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Default))
        try {
            val a = session.conversation("a")
            kotlinx.coroutines.runBlocking { a.send(listOf(app.dshmobile.remote.PromptPart.Text("hi")), "hi", emptyList()) }
            assertEquals(1, a.pending.value.size)
            session.conversation("b"); session.conversation("c"); session.conversation("d")
            assertSame("a keeps its bubble", a, session.conversation("a"))
            assertSame("the UI cache stays with it", session.chatUi("a"), session.chatUi("a"))
        } finally {
            session.close()
        }
    }

    @Test fun eachConversationKeepsItsChatUiCacheWhileCached() {
        val remote = FakeRemote()
        val session = ShellSession(remote, events = null, scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Default))
        try {
            val ui = session.chatUi("a")
            assertSame(ui, session.chatUi("a"))
            assertTrue(ui !== session.chatUi("b"))
        } finally {
            session.close()
        }
    }

    /** 審查 2 輪 (and the rest) survives a dsh restart, i.e. a new ShellSession; 審查輪數 survives the app process too. */
    @Test fun aConversationsChoicesOutliveItsDshAndTheProcess() {
        val prefs = app.dshmobile.ui.fog.UiPrefs(app.dshmobile.gateway.InMemorySharedPreferences())
        val kept = KeptChoices(prefs)
        val first = ShellSession(FakeRemote(), events = null, scope = CoroutineScope(Job()), keptChoices = kept)
        first.chatUi("s1").apply { reviewRounds = 2; effort = "max"; compactedAt = 150_000 }
        first.close()
        val second = ShellSession(FakeRemote(), events = null, scope = CoroutineScope(Job()), keptChoices = kept)
        second.chatUi("s1").let {
            assertEquals(2, it.reviewRounds)
            assertEquals("max", it.effort)
            assertEquals(150_000L, it.compactedAt)
        }
        assertEquals("another conversation has its own", 0, second.chatUi("s2").reviewRounds)
        second.close()
        // The process died: a new KeptChoices, only the stored 審查輪數 is left (the rest comes back from dsh).
        val third = ShellSession(FakeRemote(), events = null, scope = CoroutineScope(Job()), keptChoices = KeptChoices(prefs))
        assertEquals(2, third.chatUi("s1").reviewRounds)
        third.chatUi("s1").reviewRounds = 0 // back to 不審: nothing stored any more
        third.close()
        assertEquals(0, KeptChoices(prefs).let { k -> app.dshmobile.ui.chat.ChatUiCache().also { k.restore("s1", it) }.reviewRounds })
    }

    /** An evicted conversation's choices come back when it is opened again, as before. */
    @Test fun anEvictedConversationGetsItsChoicesBack() {
        val session = ShellSession(FakeRemote(), events = null, scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Default))
        try {
            session.chatUi("a").apply { reviewRounds = 3; effort = "low" }
            session.conversation("b"); session.conversation("c"); session.conversation("d") // a is evicted
            session.chatUi("a").let {
                assertEquals(3, it.reviewRounds)
                assertEquals("low", it.effort)
            }
        } finally {
            session.close()
        }
    }

    /** After the app process died, 思考強度 comes from dsh's modelSelection projection for the conversation. */
    @Test fun aNewCacheTakesDshsThinkingStrength() {
        val baseline = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"baseline","value":{"projections":{"s1":{"values":{"modelSelection":{"lastUsed":null,
               "next":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"max"}}}}}}}""",
        )
        val session = ShellSession(FakeRemote(controlFrames = listOf(baseline)), events = null, scope = CoroutineScope(Job()))
        try {
            waitFor { session.tree.state.value.projections["s1"] != null }
            assertEquals("max", session.chatUi("s1").effort)
            assertNull(session.chatUi("s2").effort)
        } finally {
            session.close()
        }
    }

    @Test fun autoReviewSwitchesEachConversationOnceBeforeItsFirstSend() = kotlinx.coroutines.runBlocking {
        var enabled = true
        val remote = FakeRemote()
        val session = ShellSession(remote, events = null, scope = CoroutineScope(Job()), autoReview = { enabled })
        val parts = listOf(app.dshmobile.remote.PromptPart.Text("hi"))
        session.conversation("s1").send(parts, "hi", emptyList())
        session.conversation("s1").send(parts, "hi again", emptyList())
        session.conversation("s2").send(parts, "hi", emptyList())
        assertEquals(listOf("s1" to "/permission auto", "s2" to "/permission auto"), remote.commands.toList())
        enabled = false
        session.conversation("s3").send(parts, "hi", emptyList())
        assertEquals("off: nothing is switched", 2, remote.commands.size)
        session.close()
    }

    /**
     * The permission level's preset: a conversation made before (dsh keeps the preset it was created with) is moved once before
     * its next message; Auto review, when on, wins; without a level nothing is sent.
     */
    @Test fun aConversationIsPutOnThePermissionLevelsPresetOnceBeforeItsNextSend() = kotlinx.coroutines.runBlocking {
        var preset: String? = "dshm-guarded"
        var auto = false
        val remote = FakeRemote()
        val session = ShellSession(remote, events = null, scope = CoroutineScope(Job()), autoReview = { auto }, levelPreset = { preset })
        val parts = listOf(app.dshmobile.remote.PromptPart.Text("hi"))
        session.conversation("s1").send(parts, "hi", emptyList())
        session.conversation("s1").send(parts, "again", emptyList())
        assertEquals(listOf("s1" to "/permission dshm-guarded"), remote.commands.toList())
        preset = "danger-full-access" // the owner changed the level
        session.conversation("s1").send(parts, "third", emptyList())
        auto = true
        session.conversation("s1").send(parts, "fourth", emptyList())
        assertEquals(listOf("/permission dshm-guarded", "/permission danger-full-access", "/permission auto"), remote.commands.map { it.second })
        preset = null; auto = false
        session.conversation("s9").send(parts, "hi", emptyList())
        assertEquals("no level: nothing is switched", 3, remote.commands.size)
        session.close()
    }

    /**
     * The owner's case (2026-11-15): 全部放行 with the Auto review switch on, and conversations an earlier build put on "auto".
     * Nothing reviews at 全部放行, so such a conversation is moved to the level's preset before its next message goes out.
     */
    @Test fun aConversationOnAutoIsMovedOffItAtTheFullLevelBeforeThePrompt() = kotlinx.coroutines.runBlocking {
        val baseline = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"baseline","value":{"projections":{"s1":{"values":{"permissions":{"currentValue":"auto"}}}}}}""",
        )
        val remote = FakeRemote(controlFrames = listOf(baseline))
        val level = app.dshmobile.runtime.PermissionLevel.FULL
        val session = ShellSession(remote, events = null, scope = CoroutineScope(Job()), autoReview = { level.autoReviews(true) }, levelPreset = { level.preset })
        try {
            waitFor { session.tree.state.value.projections["s1"] != null }
            session.conversation("s1").send(listOf(app.dshmobile.remote.PromptPart.Text("hi")), "hi", emptyList())
            waitFor { remote.calls.any { it.startsWith("prompt") } }
            assertEquals(listOf("/permission danger-full-access s1", "prompt s1"), remote.calls.toList())
        } finally {
            session.close()
        }
    }

    @Test fun autoReviewNeverAppliesAtTheFullLevel() {
        assertFalse(app.dshmobile.runtime.PermissionLevel.FULL.autoReviews(true))
        assertTrue(app.dshmobile.runtime.PermissionLevel.SMART.autoReviews(true))
        assertTrue(app.dshmobile.runtime.PermissionLevel.ASK.autoReviews(true))
        assertFalse(app.dshmobile.runtime.PermissionLevel.SMART.autoReviews(false))
    }
}
