package app.dshmobile.agents

import app.dshmobile.chat.ChatItem
import app.dshmobile.chat.ConversationState
import app.dshmobile.chat.SessionTreeState
import app.dshmobile.chat.ToolStatus
import app.dshmobile.remote.SessionSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarRoomTest {
    private fun s(id: String, parent: String? = null, running: Boolean? = false, updated: Long = 0) =
        SessionSummary(id, parent, null, updated, running, false, "t-$id", null, null, null, null)

    @Test fun toolsMapToStagesAndProps() {
        assertEquals(Triple(Stage.SEARCH, Prop.LENS, "搜尋「台北天氣」"), stageOf("web_search", "台北天氣"))
        assertEquals(Stage.SEARCH to Prop.PAGE, stageOf("read", "a.md").let { it.first to it.second })
        assertEquals(Stage.DISPATCH, stageOf("subagent", null).first)
        assertEquals(Stage.WRITE, stageOf("bash", "ls").first)
    }

    @Test fun theLeadWaitingForApprovalSaysSoAndChildrenFollowByDepth() {
        val tree = SessionTreeState(
            summaries = listOf(s("lead", running = true), s("kid", "lead", running = true), s("gk", "kid"), s("other")).associateBy { it.id },
            projections = mapOf(
                "kid" to mapOf(
                    "subagent" to Json.parseToJsonElement("""{"label":"查資料"}"""),
                    "turnOutline" to Json.parseToJsonElement("""[{"turn":1,"prompt":"go","response":"第一行\n找到了"}]"""),
                    "subagentTiming" to Json.parseToJsonElement("""{"active":{"since":1000,"through":4000}}"""),
                ),
                "gk" to mapOf("inbox" to Json.parseToJsonElement("""{"next-turn":[{},{}]}""")),
            ),
        )
        val lead = ConversationState(
            "lead",
            items = listOf(ChatItem.Tool("t1", "bash", "rm -rf build", ToolStatus.WAITING_APPROVAL)),
            running = true,
        )
        val all = WarRoomBuilder.build(tree, "lead", lead, emptyList(), nowMs = 10_000, leadStartMs = 4_000)
        assertEquals(listOf("lead", "kid", "gk"), all.map { it.id })
        val l = all[0]
        assertEquals(Mood.WAITING, l.mood)
        assertEquals("等你允許：bash", l.waiting)
        assertEquals(6_000L, l.activeMs)
        val kid = all[1]
        assertEquals("查資料", kid.label)
        assertEquals(Stage.WRITE, kid.stage)
        assertEquals("找到了", kid.lastLine)
        assertEquals(3_000L, kid.activeMs)
        val gk = all[2]
        assertEquals(2, gk.depth)
        assertEquals(Mood.WAITING, gk.mood)
        assertEquals(2, gk.queued)
    }

    @Test fun pickLeadPrefersTheOpenThenTheRunningThenTheNewest() {
        val tree = SessionTreeState(summaries = listOf(s("a", updated = 5), s("b", running = true, updated = 1), s("c", "a")).associateBy { it.id })
        assertEquals("a", WarRoomBuilder.pickLead(tree, "a"))
        assertEquals("b", WarRoomBuilder.pickLead(tree, "c"))
        assertEquals("b", WarRoomBuilder.pickLead(tree, null))
        assertNull(WarRoomBuilder.pickLead(SessionTreeState(), null))
    }

    @Test fun theRecorderKeepsFinishedChildrenAndLogsDispatchAndFinish() {
        var now = 0L
        val rec = WarRoomRecorder { now }
        fun tree(vararg kids: SessionSummary) = SessionTreeState(summaries = (listOf(s("lead")) + kids).associateBy { it.id })
        rec.observeAgents(WarRoomBuilder.children(tree()))
        now = 10
        rec.observeAgents(WarRoomBuilder.children(tree(s("k1", "lead", running = true))))
        now = 20
        rec.observeAgents(WarRoomBuilder.children(tree()))
        assertEquals(listOf("完成", "派出"), rec.log.value.map { it.badge })
        val h = rec.history.value.single()
        assertEquals("k1", h.id)
        assertEquals(Mood.DONE, h.mood)
        assertEquals(20L, h.finishedAtMs)
        // History shows in the war room while dsh no longer lists the child.
        val shown = WarRoomBuilder.build(tree(), "lead", null, rec.history.value, 0, null)
        assertTrue(shown.any { it.id == "k1" })
    }

    @Test fun theRecorderLogsNewToolStatusesButNotTheHistoryOnScreen() {
        val rec = WarRoomRecorder { 1 }
        rec.observeConversation(ConversationState("c", items = listOf(ChatItem.Tool("old", "read", "a", ToolStatus.DONE))))
        assertTrue(rec.log.value.isEmpty())
        rec.observeConversation(
            ConversationState("c", items = listOf(ChatItem.Tool("old", "read", "a", ToolStatus.DONE), ChatItem.Tool("new", "web_search", "x", ToolStatus.RUNNING))),
        )
        assertEquals("搜尋「x」", rec.log.value.single().text)
    }
}
