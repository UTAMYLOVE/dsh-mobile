package app.dshmobile.chat

import app.dshmobile.remote.HostEvent
import app.dshmobile.remote.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SessionIndexTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    private fun index(remote: FakeIndexRemote): SessionIndex = SessionIndex(SessionTree(remote, scope), remote, scope)

    @Test fun onlyTopLevelUnarchivedSessionsNewestFirst() {
        val remote = FakeIndexRemote(listOf(
            sum("old", updatedAt = 10), sum("new", updatedAt = 30), sum("none", updatedAt = null),
            sum("kid", parent = "new", origin = "subagent", updatedAt = 99), sum("gone", updatedAt = 50),
            sum("odd", origin = "subagent", updatedAt = 98),
        ))
        val idx = index(remote)
        remote.workspace("""{"type":"archived","archivedSessionIds":["gone"]}""")
        waitUntil("rows") { idx.rows.value.map { it.id } == listOf("new", "old", "none") }
        assertEquals(0L, idx.rows.value.last().updatedAt)
    }

    @Test fun projectionsUpdateTitleLastLineModelAndNextModel() {
        val remote = FakeIndexRemote(listOf(sum("s1", title = "舊", lastLine = "舊句", model = "x")))
        val idx = index(remote)
        waitUntil("row") { idx.rows.value.size == 1 }
        remote.control("""{"type":"baseline","value":{"projections":{"s1":{"values":{"title":"新標題","turnOutline":[{"prompt":"p","response":"回覆"}],"modelSelection":{"lastUsed":{"model":"deepseek-flash"},"next":{"model":"deepseek-v4-pro"}}}}}}}""")
        waitUntil("projected") { idx.rows.value.single().title == "新標題" }
        val r = idx.rows.value.single()
        assertEquals("回覆", r.lastLine)
        assertEquals("deepseek-flash", r.model)
        assertEquals("deepseek-v4-pro", r.nextModel)
    }

    @Test fun titleOfIsNullForChildren() {
        val remote = FakeIndexRemote(listOf(sum("s1", title = "主"), sum("c1", parent = "s1", origin = "subagent", title = "子")))
        val idx = index(remote)
        waitUntil("both") { idx.titleOf("s1") == "主" }
        assertNull(idx.titleOf("c1"))
        assertNull(idx.titleOf("zzz"))
    }

    @Test fun groupingUsesTheLocalDay() {
        val zone = ZoneId.of("Asia/Taipei")
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
        fun row(id: String, t: Long) = SessionRow(id, id, null, null, null, t, false)
        val rows = listOf(
            row("today", at(2026, 11, 3, 0, 1)), row("late", at(2026, 11, 2, 23, 59)),
            row("old", at(2026, 11, 1, 12, 0)), row("zero", 0),
        )
        val g = groupByDay(rows, LocalDate.of(2026, 11, 3), zone)
        assertEquals(listOf(DayGroup.TODAY, DayGroup.YESTERDAY, DayGroup.EARLIER), g.map { it.first })
        assertEquals(listOf("今天", "昨天", "更早"), g.map { it.first.zh })
        assertEquals(listOf("old", "zero"), g[2].second.map { it.id })
        assertEquals(listOf(DayGroup.EARLIER), groupByDay(rows.drop(2), LocalDate.of(2026, 11, 3), zone).map { it.first })
    }

    @Test fun createUsesTheWorkspaceIdOrFallsBackToCwd() = runBlocking {
        val remote = FakeIndexRemote()
        val idx = index(remote)
        assertEquals("new1", idx.create())
        assertEquals(listOf<String?>(null), remote.created.toList())
        remote.workspace("""{"type":"baseline","value":{"items":[{"workspaceId":"w-app","path":"/root/workspace"}],"archivedSessionIds":[]}}""")
        waitUntil("workspace known") { runBlocking { idx.create() }; remote.created.last() == "w-app" }
    }

    @Test fun deleteArchivesAndRefreshes() = runBlocking {
        val remote = FakeIndexRemote(listOf(sum("s1"), sum("s2")))
        val idx = index(remote)
        waitUntil("rows") { idx.rows.value.size == 2 }
        val before = remote.listCalls.get()
        idx.delete("s1")
        assertEquals(listOf("s1"), remote.archived.toList())
        waitUntil("row gone") { idx.rows.value.map { it.id } == listOf("s2") }
        waitUntil("refreshed") { remote.listCalls.get() > before }
    }

    @Test fun renameStoresAndRefreshes() = runBlocking {
        val remote = FakeIndexRemote(listOf(sum("s1", title = "a")))
        val idx = index(remote)
        waitUntil("rows") { idx.rows.value.size == 1 }
        idx.rename("s1", "b")
        assertEquals(listOf("s1" to "b"), remote.renamed.toList())
        assertEquals("b", idx.titleOf("s1"))
    }

    @Test fun searchKeepsDshOrderAndShowsSnippets() = runBlocking {
        val remote = FakeIndexRemote(listOf(sum("a", title = "A", lastLine = "la"), sum("b", title = "B", lastLine = "lb"), sum("kid", parent = "a", origin = "subagent")))
        val idx = index(remote)
        waitUntil("rows") { idx.rows.value.size == 2 }
        remote.hits = listOf(SearchHit("b", "…命中 B…"), SearchHit("kid", "x"), SearchHit("a", "…命中 A…"), SearchHit("unknown", "y"))
        val r = idx.search("命中")
        assertEquals(listOf("b", "a"), r.map { it.id })
        assertEquals(listOf("…命中 B…", "…命中 A…"), r.map { it.lastLine })
        assertEquals(listOf("B", "A"), r.map { it.title })
    }

    /** Final review B2-M7: a double tap on 新對話 (or two quick dock sends) makes one conversation, not two. */
    @Test fun aCreateWhileAnotherRunsJoinsIt() = runBlocking {
        val remote = FakeIndexRemote()
        val idx = index(remote)
        remote.createGate = kotlinx.coroutines.CompletableDeferred()
        val first = scope.async { idx.create() }
        waitUntil("first create sent") { remote.created.size == 1 }
        val second = scope.async { idx.create() }
        Thread.sleep(50)
        remote.createGate!!.complete(Unit)
        assertEquals("new1", first.await())
        assertEquals("new1", second.await())
        assertEquals(1, remote.created.size)
        assertEquals("a later create is a new one", "new2", idx.create())
    }
}
