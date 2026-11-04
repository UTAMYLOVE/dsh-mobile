package app.dshmobile.agents

import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.SessionUsage
import app.dshmobile.chat.ToolStatus
import app.dshmobile.ui.agents.PER_STAGE
import app.dshmobile.ui.agents.flyers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarDeriveTest {
    private fun tool(key: String, name: String, status: ToolStatus, error: String? = null, summary: String = "x") =
        ChatItem.Tool(key, name, summary, status, error = error)

    private fun agent(id: String, mood: Mood, stage: Stage = Stage.WRITE, tokens: Long = 0, lead: Boolean = false, finished: Long? = null) = AgentStatus(
        id, if (lead) null else "lead", if (lead) 0 else 1, "A-$id", mood, stage, Prop.NONE, null, null, null, null,
        SessionUsage(outputTokens = tokens), null, null, 0, null, null, emptyList(), lead, finished,
    )

    @Test fun decisionsAreNewestFirstAndSayWhy() {
        val d = WarDerive.decisions(
            listOf(
                tool("1", "web_search", ToolStatus.DONE),
                tool("2", "bash", ToolStatus.FAILED, error = "Permission denied by auto review"),
                tool("3", "bash", ToolStatus.WAITING_APPROVAL),
                tool("4", "write", ToolStatus.FAILED, error = "disk full"),
            ),
        )
        assertEquals(listOf(Decision.Kind.FAILED, Decision.Kind.WAITING, Decision.Kind.BLOCKED, Decision.Kind.ALLOWED), d.map { it.kind })
        assertEquals("唯讀 · 自動放行", d[3].why)
        assertEquals("要你允許才會做", d[1].why)
        assertTrue(d[2].risk > .9f)
    }

    @Test fun countsSplitSearchReadWriteAndDispatches() {
        val c = WarDerive.counts(
            listOf(tool("1", "web_search", ToolStatus.DONE), tool("2", "read", ToolStatus.DONE), tool("3", "write", ToolStatus.DONE), tool("4", "bash", ToolStatus.FAILED, "denied")),
            listOf(agent("lead", Mood.RUNNING, lead = true), agent("k1", Mood.DONE), agent("k2", Mood.RUNNING)),
        )
        assertEquals(ActionCounts(search = 1, read = 1, dispatch = 2, write = 1, blocked = 1, review = 0), c)
    }

    @Test fun temperatureRankingAndSpeed() {
        val agents = listOf(agent("a", Mood.RUNNING, tokens = 50), agent("b", Mood.WAITING, tokens = 900), agent("c", Mood.IDLE), agent("d", Mood.ERROR, tokens = 10))
        val t = WarDerive.temperature(agents)!!
        assertEquals(1 / 3f, t.running, .001f)
        assertEquals(1 / 3f, t.waiting, .001f)
        assertNull(WarDerive.temperature(listOf(agent("c", Mood.IDLE))))
        assertEquals(listOf("b", "a", "d"), WarDerive.ranking(agents).map { it.first.id })
        val samples = (0..11).map { i -> WarSample(i * 5_000L, 10f + i, 0, 0, 0f, 0, 0, mapOf("a" to 100f + i, "b" to 5f)) }
        val lines = WarDerive.speedLines(samples, agents, max = 1)
        assertEquals("A-a", lines.single().label)
        assertEquals(12, lines.single().values.size)
        assertEquals(100f * (21f - 11f) / 11f, WarDerive.speedChange(samples)!!, .01f)
    }

    @Test fun onlyWorkingAgentsAndThreeRecentDoneOnesFly() {
        // The owner's screenshot: 17 finished agents piled up on 「完成」.
        val done = (1..17).map { agent("d$it", Mood.DONE, Stage.DONE, finished = it * 1_000L) }
        val shown = flyers(done + agent("w", Mood.RUNNING))
        assertEquals(listOf("w", "d17", "d16", "d15"), shown.map { it.id })
        assertEquals(4, PER_STAGE)
    }
}
