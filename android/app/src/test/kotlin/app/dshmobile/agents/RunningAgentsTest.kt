package app.dshmobile.agents

import app.dshmobile.chat.FakeIndexRemote
import app.dshmobile.chat.SessionTree
import app.dshmobile.chat.SessionTreeState
import app.dshmobile.chat.json
import app.dshmobile.chat.sum
import app.dshmobile.chat.waitUntil
import app.dshmobile.remote.HostEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class RunningAgentsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    private class FakeAgents : AgentsRemote {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failWith: Exception? = null
        override suspend fun interruptSubagent(childSessionId: String, parentSessionId: String) {
            calls += childSessionId to parentSessionId
            gate?.await()
            failWith?.let { throw it }
        }
    }

    private fun summaries(vararg s: app.dshmobile.remote.SessionSummary) = SessionTreeState(summaries = s.associateBy { it.id })

    private fun setup(vararg s: app.dshmobile.remote.SessionSummary): Triple<FakeIndexRemote, SessionTree, RunningAgents> {
        val remote = FakeIndexRemote(s.toList())
        val tree = SessionTree(remote, scope)
        waitUntil("baseline") { tree.state.value.summaries.size == s.size }
        return Triple(remote, tree, RunningAgents(tree, FakeAgents(), scope))
    }

    @Test fun rowsAreTheChildrenOfTheTree() {
        val s = summaries(
            sum("s1", title = "天氣"),
            sum("c1", parent = "s1", origin = "subagent", running = true),
            sum("c2", parent = "s1", origin = "subagent", running = true).copy(label = "從摘要來"),
            sum("c3", parent = "s1", origin = "subagent", running = true).copy(label = "從摘要來"),
        ).let {
            it.copy(projections = mapOf("c3" to mapOf("subagent" to json("""{"label":"從投影來"}"""))))
        }
        val rows = AgentRows.from(s).associateBy { it.childId }
        assertEquals(setOf("c1", "c2", "c3"), rows.keys) // the top-level session is not a row
        assertEquals("子代理人", rows.getValue("c1").label)
        assertEquals("從摘要來", rows.getValue("c2").label)
        assertEquals("從投影來", rows.getValue("c3").label)
        assertEquals("s1", rows.getValue("c1").parentId)
    }

    @Test fun runningFirstThenTheRestByUpdatedAt() {
        val s = summaries(
            sum("s1"),
            sum("a", parent = "s1", running = false, updatedAt = 5),
            sum("b", parent = "s1", running = false, updatedAt = 9),
            sum("c", parent = "s1", running = true, updatedAt = 1),
            sum("d", parent = "s1", running = null, updatedAt = 7),
        )
        val rows = AgentRows.from(s)
        assertEquals(listOf("c", "b", "d", "a"), rows.map { it.childId })
        assertFalse(rows.first { it.childId == "d" }.running)
    }

    @Test fun treeUpdatesFlowIntoRows() {
        val (remote, _, agents) = setup(sum("s1"), sum("c1", parent = "s1", origin = "subagent", running = false))
        waitUntil("row") { agents.rows.value.map { it.childId } == listOf("c1") }
        assertFalse(agents.rows.value.single().running)
        remote.events.tryEmit(HostEvent.SessionStatus("c1", true))
        waitUntil("running") { agents.rows.value.single().running }
        remote.events.tryEmit(HostEvent.SessionAdded(sum("c2", parent = "s1", origin = "subagent", running = true)))
        waitUntil("second") { agents.rows.value.size == 2 }
        remote.events.tryEmit(HostEvent.SessionRemoved("c1"))
        waitUntil("removed") { agents.rows.value.map { it.childId } == listOf("c2") }
    }

    @Test fun runningCountCountsOnlyRunning() {
        val (_, _, agents) = setup(
            sum("s1"), sum("c1", parent = "s1", running = true), sum("c2", parent = "s1", running = false), sum("c3", parent = "s1", running = true),
        )
        waitUntil("count") { agents.runningCount.value == 2 }
    }

    @Test fun stopInterruptsByParentWithTheRightIds() {
        val remote = FakeIndexRemote(listOf(sum("s1"), sum("c1", parent = "s1", running = true)))
        val tree = SessionTree(remote, scope)
        val fake = FakeAgents()
        val agents = RunningAgents(tree, fake, scope)
        waitUntil("row") { agents.rows.value.isNotEmpty() }
        runBlocking { agents.stop(agents.rows.value.single()) }
        assertEquals(listOf("c1" to "s1"), fake.calls.toList())
    }

    @Test fun stoppingShowsUntilTheStatusTurnsFalse() {
        val remote = FakeIndexRemote(listOf(sum("s1"), sum("c1", parent = "s1", running = true)))
        val tree = SessionTree(remote, scope)
        val agents = RunningAgents(tree, FakeAgents(), scope)
        waitUntil("row") { agents.rows.value.isNotEmpty() }
        runBlocking { agents.stop(agents.rows.value.single()) }
        assertEquals(setOf("c1"), agents.stopping.value) // the call returned, dsh has not said it stopped yet
        remote.events.tryEmit(HostEvent.SessionStatus("c1", false))
        waitUntil("cleared") { agents.stopping.value.isEmpty() }
    }

    @Test fun aFailedStopReEnablesAndSaysSo() {
        val remote = FakeIndexRemote(listOf(sum("s1"), sum("c1", parent = "s1", running = true)))
        val tree = SessionTree(remote, scope)
        val fake = FakeAgents().also { it.failWith = IOException("sk-secret-token boom") }
        val agents = RunningAgents(tree, fake, scope)
        waitUntil("row") { agents.rows.value.isNotEmpty() }
        val row = agents.rows.value.single()
        runBlocking { agents.stop(row) }
        assertTrue(agents.stopping.value.isEmpty())
        assertEquals(setOf("c1"), agents.failed.value)
        fake.failWith = null
        runBlocking { agents.stop(row) } // trying again clears the error
        assertTrue(agents.failed.value.isEmpty())
        assertEquals(2, fake.calls.size)
    }

    @Test fun aSecondTapWhileStoppingIsIgnored() {
        val remote = FakeIndexRemote(listOf(sum("s1"), sum("c1", parent = "s1", running = true)))
        val tree = SessionTree(remote, scope)
        val fake = FakeAgents().also { it.gate = CompletableDeferred() }
        val agents = RunningAgents(tree, fake, scope)
        waitUntil("row") { agents.rows.value.isNotEmpty() }
        val row = agents.rows.value.single()
        val first = scope.launch { agents.stop(row) }
        waitUntil("in flight") { fake.calls.size == 1 }
        runBlocking { agents.stop(row) }
        assertEquals(1, fake.calls.size)
        fake.gate!!.complete(Unit)
        runBlocking { first.join() }
    }

    @Test fun originOfNamesTheLabelAndParent() {
        val remote = FakeIndexRemote(listOf(sum("s1", title = "天氣"), sum("c1", parent = "s1", origin = "subagent").copy(label = "查資料")))
        val tree = SessionTree(remote, scope)
        waitUntil("baseline") { tree.state.value.summaries.size == 2 }
        val agents = RunningAgents(tree, FakeAgents(), scope)
        assertEquals("子代理人「查資料」（來自 天氣）", agents.originOf("c1"))
        assertNull(agents.originOf("s1"))
        assertNull(agents.originOf("nobody"))
    }

    @Test fun originOfWithoutAParentTitleSaysNewConversation() {
        val remote = FakeIndexRemote(listOf(sum("s1"), sum("c1", parent = "s1", origin = "subagent")))
        val tree = SessionTree(remote, scope)
        waitUntil("baseline") { tree.state.value.summaries.size == 2 }
        assertEquals("子代理人「子代理人」（來自 新對話）", RunningAgents(tree, FakeAgents(), scope).originOf("c1"))
    }
}
