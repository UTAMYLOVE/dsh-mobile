package app.dshmobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
private fun q(s: String) = JsonPrimitive(s).toString()

internal fun ev(type: String, seq: Long, data: String = "{}") = """{"type":"$type","seq":$seq,"time":1,"data":$data}"""
internal fun snapshot(vararg events: String, hasMore: Boolean = false): JsonElement =
    j("""{"type":"snapshot","header":{"version":4},"cursor":1,"records":[${events.joinToString(",") { """{"type":"event","event":$it}""" }}],"hasMore":$hasMore}""")
internal fun live(event: String): JsonElement = j("""{"type":"event","event":$event}""")
internal fun userMsg(seq: Long, text: String, rpcId: String? = null, kind: String = "user") =
    ev("user/message", seq, """{"content":[{"type":"text","text":${q(text)}}],"source":{"kind":"$kind"${if (rpcId != null) ""","rpcId":"$rpcId"""" else ""}},"role":"user"}""")
internal fun asstMsg(seq: Long, vararg blocks: String) =
    ev("assistant/message", seq, """{"turn":1,"step":1,"message":{"role":"assistant","content":[${blocks.joinToString(",")}]}}""")
internal fun streamFrame(frame: String) = j("""{"type":"assistant-stream","frame":$frame}""")
internal fun start(id: String = "a1") = streamFrame("""{"type":"start","attemptId":"$id","revision":1}""")
internal fun chunk(kind: String, text: String, index: Int = 0, id: String = "a1") =
    streamFrame("""{"type":"chunk","attemptId":"$id","revision":2,"index":$index,"chunk":{"type":"$kind","index":$index,"text":${q(text)}}}""")
internal fun end(outcome: String = "committed", id: String = "a1") =
    streamFrame("""{"type":"end","attemptId":"$id","outcome":{"kind":"$outcome"}}""")

class ConversationReducerTest {
    private val s0 = ConversationState("s1")
    private fun ConversationState.then(vararg frames: JsonElement?) = frames.fold(this) { s, f -> ConversationReducer.reduce(s, f) }

    @Test fun snapshotShowsOnlyRealPromptsAndReplies() {
        val s = s0.then(snapshot(
            userMsg(3, "hi"), userMsg(4, "ctx", kind = "runtime-context"), ev("system/message", 2), ev("request/header", 5),
            asstMsg(6, """{"type":"reasoning","text":"think"}""", """{"type":"text","text":"Hello"}"""),
        ))
        assertEquals(listOf(ChatItem.User("e3", "hi", emptyList()), ChatItem.Assistant("e6", 6, "think", "Hello")), s.items)
        assertEquals(2L, s.firstSeq)
    }

    @Test fun aSecondSnapshotReplacesItemsWithoutDuplicates() {
        val snap = snapshot(userMsg(3, "hi"), asstMsg(6, """{"type":"text","text":"Hello"}"""))
        val s = s0.then(snap, snap)
        assertEquals(2, s.items.size)
    }

    @Test fun aSnapshotClearsAStaleLiveReplyAndRunning() {
        val mid = s0.then(snapshot(ev("turn/start", 1)), start(), chunk("text-delta", "par"))
        assertTrue(mid.running); assertEquals("par", mid.live?.text)
        val ended = mid.then(snapshot(ev("turn/start", 1), ev("turn/end", 9, """{"reason":{"kind":"completed"}}""")))
        assertNull(ended.live); assertFalse(ended.running)
        val open = ended.then(snapshot(ev("turn/start", 1), ev("turn/end", 2), ev("turn/start", 3)))
        assertTrue(open.running)
    }

    @Test fun reopenedClearsLiveAndRunning() {
        val s = s0.then(snapshot(userMsg(3, "hi"), ev("turn/start", 4)), start(), chunk("text-delta", "x"))
        val r = ConversationReducer.reopened(s)
        assertNull(r.live); assertFalse(r.running); assertEquals(s.items, r.items)
    }

    @Test fun liveTextAccumulatesByIndexAndCommitClearsIt() {
        val s = s0.then(
            start(), streamFrame("""{"type":"chunk","attemptId":"a1","chunk":{"type":"block-start","index":0,"blockType":"text"}}"""),
            chunk("text-delta", "Hel"), chunk("text-delta", "lo"),
        )
        assertEquals("Hello", s.live?.text)
        assertNull(s.then(end()).live)
    }

    @Test fun reasoningDeltasGoToLiveReasoning() {
        val s = s0.then(start(), chunk("reasoning-delta", "Let "), chunk("reasoning-delta", "me"),
            streamFrame("""{"type":"chunk","attemptId":"a1","chunk":{"type":"tool-call-delta","index":1,"id":"t","name":"bash","argumentsDelta":""}}"""))
        assertEquals("Let me", s.live?.reasoning)
        assertEquals(listOf("bash"), s.live?.toolNames)
    }

    @Test fun abandonedAttemptClearsLive() {
        assertNull(s0.then(start(), chunk("text-delta", "x"), end("abandoned")).live)
    }

    private fun call(id: String, name: String, args: String) = ev("tool/call", 10, """{"callId":"$id","name":"$name","arguments":${q(args)}}""")
    private fun result(id: String, text: String, isError: Boolean = false, meta: String = "{}") =
        ev("tool/result", 11, """{"message":{"role":"tool","toolCallId":"$id","content":[{"type":"text","text":${q(text)}}],"isError":$isError},"meta":$meta}""")
    private fun tool(s: ConversationState) = s.items.filterIsInstance<ChatItem.Tool>().single()

    @Test fun aToolCallBecomesARunningCardThenDone() {
        val s = s0.then(live(call("c1", "bash", """{"description":"Print a greeting","command":"echo hi"}""")))
        assertEquals("Print a greeting", tool(s).summary); assertEquals(ToolStatus.RUNNING, tool(s).status)
        assertEquals(ToolStatus.DONE, tool(s.then(live(result("c1", "hi")))).status)
    }

    @Test fun anAssistantToolCallBlockAlsoMakesTheCardOnlyOnce() {
        val s = s0.then(live(asstMsg(6, """{"type":"tool-call","id":"c1","name":"bash","arguments":{"command":"ls"}}""")),
            live(call("c1", "bash", """{"command":"ls"}""")))
        assertEquals("ls", tool(s).summary)
    }

    @Test fun aFailedToolKeepsTheFirstErrorLine() {
        val s = s0.then(live(call("c1", "bash", "{}")), live(result("c1", "\nboom happened\nsecond", isError = true)))
        assertEquals(ToolStatus.FAILED, tool(s).status); assertEquals("boom happened", tool(s).error)
    }

    @Test fun webSearchCardsCarryMetaSources() {
        val s = s0.then(live(call("c1", "web_search", """{"queries":["a","b"]}""")),
            live(result("c1", "x", meta = """{"sources":[{"url":"https://example.com/w","title":"W"},{"url":"ftp://x","title":"no"}]}""")))
        assertEquals("a, b", tool(s).summary)
        assertEquals(listOf(WebSource("https://example.com/w", "W")), tool(s).sources)
    }

    @Test fun webSearchFallsBackToMarkdownLinksInTheResult() {
        val s = s0.then(live(call("c1", "web_search", """{"queries":["w"]}""")),
            live(result("c1", "- [E2E Weather](https://example.com/e2e-weather)\n- [x](javascript:alert(1))\n- [again](https://example.com/e2e-weather)")))
        assertEquals(listOf(WebSource("https://example.com/e2e-weather", "E2E Weather")), tool(s).sources)
    }

    @Test fun approvalAskedAndDecidedMoveTheToolStatus() {
        val base = s0.then(live(call("c1", "write", "{}")),
            live(ev("approval/asked", 12, """{"id":"ap1","toolName":"write","callId":"c1","reason":"r"}""")))
        assertEquals(ToolStatus.WAITING_APPROVAL, tool(base).status)
        val rejected = base.then(live(ev("approval/decided", 13, """{"id":"ap1","outcome":"rejected"}""")))
        assertEquals(ToolStatus.FAILED, tool(rejected).status); assertEquals("已拒絕", tool(rejected).error)
        assertEquals(ToolStatus.RUNNING, tool(base.then(live(ev("approval/decided", 13, """{"id":"ap1","outcome":"allowed-once"}""")))).status)
    }

    @Test fun turnEndAbortedAddsAStoppedNotice() {
        val s = s0.then(live(ev("turn/start", 1)), live(ev("turn/end", 18, """{"turn":1,"reason":{"kind":"aborted","reason":{"kind":"user"}}}""")))
        assertFalse(s.running)
        assertEquals(ChatItem.Notice("n18", NoticeKind.STOPPED, "已停止"), s.items.last())
    }

    @Test fun titleEventsSetTheTitle() {
        assertEquals("T", s0.then(live(ev("session/title", 5, """{"title":"T","source":{"kind":"fallback"}}"""))).title)
    }

    @Test fun acceptedRequestIdsComeFromUserMessages() {
        assertTrue("q1" in s0.then(live(userMsg(3, "hi", rpcId = "q1"))).acceptedRequestIds)
    }

    @Test fun writeUnderTheWorkspaceGetsASharePath() {
        fun share(path: String) = tool(s0.then(live(call("c", "write", """{"file_path":${q(path)},"content":"x"}""")))).sharePath
        assertEquals("notes/a.md", share("/root/workspace/notes/a.md"))
        assertNull(share("/tmp/x")); assertNull(share("/root/workspace/../etc/passwd"))
        assertEquals("b.md", share("/root/workspace/a/../b.md"))
    }

    @Test fun prependAddsOlderItemsFirst() {
        val s = s0.then(snapshot(userMsg(30, "new"), hasMore = true))
        val older = ConversationReducer.prepend(s, snapshot(userMsg(10, "old"), asstMsg(11, """{"type":"text","text":"r"}"""), hasMore = false))
        assertEquals(listOf("e10", "e11", "e30"), older.items.map { it.key })
        assertEquals(10L, older.firstSeq); assertFalse(older.hasMore)
    }

    @Test fun unknownAndMalformedFramesNeverThrow() {
        val s = s0.then(snapshot(userMsg(3, "hi")))
        for (f in listOf(null, j("{}"), j("""{"type":"event"}"""), j("""{"type":"event","event":{"type":"tool/result","data":5}}"""),
            j("""{"type":"weird"}"""), j("[1]"), j("""{"type":"snapshot","records":5}"""), j("""{"type":"assistant-stream","frame":7}"""))) {
            assertEquals(s, ConversationReducer.reduce(s, f))
        }
        assertEquals(s, ConversationReducer.prepend(s, j("5")))
    }
}
