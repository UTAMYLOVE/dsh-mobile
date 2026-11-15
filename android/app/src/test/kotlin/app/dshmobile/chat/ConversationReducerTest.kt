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
    private fun ConversationState.then(vararg frames: JsonElement?) = frames.fold(this) { s, f -> ConversationReducer.reduceLive(ConversationReducer.reduce(s, f), f) }

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

    // ---- B3: the owner's own choices are not 「失敗」 ----

    private fun asked(id: String = "ap1", call: String = "c1", tool: String = "write") =
        live(ev("approval/asked", 12, """{"id":"$id","toolName":"$tool","callId":"$call","reason":"r"}"""))
    private fun decided(outcome: String, id: String = "ap1") = live(ev("approval/decided", 13, """{"id":"$id","outcome":"$outcome"}"""))
    private fun failedResult(id: String, text: String, code: String? = null) = ev(
        "tool/result", 14,
        """{"message":{"role":"tool","toolCallId":"$id","content":[{"type":"text","text":${q(text)}}],"isError":true}""" +
            (if (code != null) ""","error":{"name":"AbortError","code":"$code"}""" else "") + "}",
    )

    @Test fun aRejectionStaysRejectedAfterDshReportsIt() {
        val s = s0.then(live(call("c1", "write", "{}")), asked(), decided("rejected"),
            live(failedResult("c1", "Error: the user rejected tool \"write\"")))
        assertEquals(ToolStatus.FAILED, tool(s).status); assertEquals("已拒絕", tool(s).error)
    }

    @Test fun theOwnersRejectionReadsAsRejectedEvenWithoutTheApprovalEvent() {
        val s = s0.then(live(call("c1", "bash", "{}")), live(failedResult("c1", "Error: the user rejected tool \"bash\"")))
        assertEquals("已拒絕", tool(s).error)
    }

    @Test fun stopDuringAToolOrBeforeItRanIsStopped() {
        for (code in listOf("ABORTED", "ABORTED_BEFORE_DISPATCH")) {
            val text = if (code == "ABORTED") "Error: tool call aborted" else "Error: tool call aborted before dispatch"
            val s = s0.then(live(call("c1", "bash", "{}")), live(failedResult("c1", text, code)))
            assertEquals(code, ToolStatus.FAILED, tool(s).status); assertEquals(code, "已停止", tool(s).error)
        }
    }

    @Test fun stopWhileAskingIsStoppedAndStaysSo() {
        val cancelled = s0.then(live(call("c1", "bash", "{}")), asked(tool = "bash"), decided("cancelled"))
        assertEquals("已停止", tool(cancelled).error)
        val after = cancelled.then(live(failedResult("c1", "Error: approval for tool \"bash\" was cancelled")))
        assertEquals("已停止", tool(after).error)
    }

    @Test fun skippingAQuestionIsSkipped() {
        val s = s0.then(live(call("c1", "ask_user_question", "{}")), live(failedResult("c1", "Error: " + ConversationReducer.DECLINED_IN_APP)))
        assertEquals(ToolStatus.FAILED, tool(s).status); assertEquals("已略過", tool(s).error)
    }

    @Test fun anAutoReviewRejectionIsARealFailureWithItsText() {
        val text = "Error: Auto review rejected tool \"bash\"; its body was not executed"
        val s = s0.then(live(call("c1", "bash", "{}")), asked(tool = "bash"), decided("allowed-once"), live(failedResult("c1", text)))
        assertEquals(ToolStatus.FAILED, tool(s).status); assertEquals(text, tool(s).error)
        assertNull(tool(s).error?.takeIf { it in ConversationReducer.OWNER_CHOICES })
    }

    @Test fun noOneToAskIsARealFailureSaidInChinese() {
        val s = s0.then(live(call("c1", "bash", "{}")), asked(tool = "bash"), decided("unavailable"),
            live(failedResult("c1", "Error: tool \"bash\" requires approval, but no approval channel is available")))
        assertEquals(ToolStatus.FAILED, tool(s).status); assertEquals("沒有可以詢問你的連線", tool(s).error)
    }

    // ---- B1: a turn that did not complete says why ----

    private fun turnEnd(reason: String, seq: Long = 18) = ev("turn/end", seq, """{"turn":1,"reason":$reason}""")
    private fun errorEnd(error: String) = turnEnd("""{"kind":"error","error":$error}""")
    private fun notice(s: ConversationState) = s.items.last() as ChatItem.Notice

    @Test fun eachTurnEndKindAddsOneErrorNotice() {
        val cases = mapOf(
            """{"kind":"interrupted"}""" to "dsh 中途重新啟動，這一輪沒做完，說「繼續」可接著做",
            """{"kind":"max-tokens"}""" to "回覆太長被截斷，說「繼續」可接著寫",
            """{"kind":"blocked"}""" to "這一輪被擋下了",
            """{"kind":"error","error":{"message":"boom\nstack","code":"UNKNOWN"}}""" to "這一輪出錯了：boom",
            """{"kind":"error"}""" to "這一輪出錯了",
        )
        for ((reason, text) in cases) {
            val s = s0.then(live(ev("turn/start", 1)), live(turnEnd(reason)))
            assertFalse(reason, s.running)
            assertEquals(reason, ChatItem.Notice("n18", NoticeKind.ERROR, text), s.items.last())
        }
    }

    @Test fun aCompletedTurnAddsNothing() {
        val s = s0.then(live(ev("turn/start", 1)), live(turnEnd("""{"kind":"completed"}""")))
        assertTrue(s.items.none { it is ChatItem.Notice })
    }

    @Test fun gatewayCodesAndStatusesBecomeOnePlainSentence() {
        val cases = mapOf(
            // Claude (pi-ai) quotes the gateway's body; DeepSeek keeps only the status and dsh's code.
            """{"message":"401 {\"error\":\"dshm_missing_key\",\"provider\":\"ANTHROPIC\"}","code":"AUTH"}""" to "找不到金鑰，請到設定貼上金鑰",
            """{"message":"401 {\"error\":\"dshm_invalid_key\"}","code":"AUTH"}""" to "金鑰被拒絕，請到設定重新貼上金鑰",
            """{"message":"DeepSeek Messages request failed (401)","code":"AUTH","status":401}""" to "金鑰被拒絕，請到設定重新貼上金鑰",
            """{"message":"Forbidden","code":"HTTP_403","status":403}""" to "金鑰被拒絕，請到設定重新貼上金鑰",
            """{"message":"429 {\"error\":\"dshm_daily_cap\"}","code":"RATE_LIMIT"}""" to "已達今日花費上限（每日硬上限）",
            """{"message":"429 {\"error\":\"dshm_rate_guard\"}","code":"RATE_LIMIT"}""" to "模型服務太忙，稍後再試",
            """{"message":"DeepSeek Messages request failed (429)","code":"RATE_LIMIT","status":429}""" to "模型服務太忙，稍後再試",
            """{"message":"Overloaded","code":"SERVER","status":529}""" to "模型服務太忙，稍後再試",
            """{"message":"Insufficient Balance","code":"QUOTA","status":402}""" to "帳戶餘額不足",
            """{"message":"429 You exceeded: insufficient_quota","code":"QUOTA"}""" to "帳戶餘額不足",
            """{"message":"502 {\"error\":\"dshm_upstream_unreachable\"}","code":"SERVER"}""" to "連不上模型服務，請檢查網路",
            """{"message":"fetch failed: ECONNREFUSED","code":"TRANSPORT"}""" to "連不上模型服務，請檢查網路",
            """{"message":"pi-ai stream idle timeout after 120000ms","code":"TIMEOUT"}""" to "連不上模型服務，請檢查網路",
            """{"message":"model \"x\" returned a completed response with no content","code":"EMPTY_RESPONSE"}""" to
                "這一輪出錯了：model \"x\" returned a completed response with no content",
        )
        for ((error, text) in cases) {
            assertEquals(error, text, notice(s0.then(live(ev("turn/start", 1)), live(errorEnd(error)))).text)
        }
    }

    @Test fun aReplayedSnapshotKeepsASingleErrorNotice() {
        val end = errorEnd("""{"message":"x","code":"AUTH","status":401}""")
        val s = s0.then(snapshot(ev("turn/start", 1), end), live(end), snapshot(ev("turn/start", 1), end))
        assertEquals(1, s.items.count { it is ChatItem.Notice })
        assertEquals(NoticeKind.ERROR, notice(s).kind)
        assertTrue(s.endedWithError())
    }

    // ---- B6: dsh's model retries ----

    private fun retry(n: Int, max: Int? = 5) =
        live(ev("llm/retry", 20L + n, """{"retryId":"r","turn":1,"step":1,"provider":"deepseek-official","mode":"normal","retry":$n""" +
            (if (max != null) ""","maxRetries":$max""" else "") + ""","delayMs":500,"failure":{"message":"busy","code":"RATE_LIMIT","status":429}}"""))

    @Test fun aRetryIsShownUntilTheReplyLands() {
        val running = s0.then(live(ev("turn/start", 1)))
        val retrying = running.then(retry(2))
        assertEquals(RetryInfo(2, 5), retrying.retry)
        assertEquals("模型忙線，重試 2/5…", retrying.retry?.label)
        assertNull(retrying.then(live(asstMsg(30, """{"type":"text","text":"ok"}"""))).retry)
        assertNull(retrying.then(live(turnEnd("""{"kind":"completed"}""", seq = 31))).retry)
        assertEquals("模型忙線，重試第 3 次…", running.then(retry(3, max = null)).retry?.label)
    }

    @Test fun aNewTurnClearsAnOldRetry() {
        val s = s0.then(live(ev("turn/start", 1)), retry(1))
        assertNull(s.then(live(ev("turn/start", 40))).retry)
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

    @Test fun aReplayedApprovalAskedDoesNotReviveADoneCard() {
        val asked = ev("approval/asked", 12, """{"id":"ap1","toolName":"bash","callId":"c1","reason":"r"}""")
        val s = s0.then(snapshot(call("c1", "bash", "{}"), asked, result("c1", "ok").replace("\"seq\":11", "\"seq\":13")))
        assertEquals(ToolStatus.DONE, tool(s).status)
        assertEquals(ToolStatus.DONE, tool(s.then(live(asked))).status)
    }

    @Test fun aReplayedTurnStartAfterTurnEndKeepsRunningFalse() {
        val s = s0.then(snapshot(ev("turn/start", 1), ev("turn/end", 2, """{"reason":{"kind":"completed"}}""")))
        assertFalse(s.running)
        assertFalse(s.then(live(ev("turn/start", 1))).running)
        assertFalse(s.then(live(ev("turn/start", 2))).running)
        assertTrue(s.then(live(ev("turn/start", 3))).running)
    }

    @Test fun reduceIgnoresStreamFramesSoOnlyTheStoreAccumulatesLiveText() {
        assertEquals(s0, ConversationReducer.reduce(s0, start()))
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
