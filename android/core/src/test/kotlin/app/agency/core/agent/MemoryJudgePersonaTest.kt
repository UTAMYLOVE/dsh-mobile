package app.agency.core.agent

import app.agency.core.base.TestClock
import app.agency.core.llm.Fail
import app.agency.core.llm.SearchClient
import app.agency.core.llm.SearchHit
import app.agency.core.llm.SearchResult
import app.agency.core.rules.Fixtures
import app.agency.core.store.Doc
import app.agency.core.store.MemStore
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryJudgePersonaTest {
    private val store = MemStore()
    private val clock = TestClock()

    @Test fun rememberIsCappedDedupedAndForgetBySubstring() {
        assertTrue(Memory.remember(store, "  他喜歡\n豆漿  ").added)
        assertEquals(listOf("他喜歡 豆漿"), Memory.lines(store))
        assertFalse(Memory.remember(store, "他喜歡 豆漿").added)
        assertFalse(Memory.remember(store, "   ").added)
        repeat(Memory.CAP) { Memory.remember(store, "第 $it 條") }
        val lines = Memory.lines(store)
        assertEquals(Memory.CAP, lines.size)
        assertFalse(lines.contains("他喜歡 豆漿"))
        assertEquals(listOf("第 1 條", "第 10 條", "第 11 條"), Memory.forget(store, "第 1").take(3))
        assertTrue(Memory.forget(store, "").isEmpty())
        assertFalse(Memory.lines(store).any { it.contains("第 1") })
    }

    @Test fun plainTextMemoryFromElsewhereStillReads() {
        store.putDoc(Doc.MEMORY, "第一行\n\n第二行")
        assertEquals(listOf("第一行", "第二行"), Memory.lines(store))
    }

    @Test fun dayNotesOfTheLastThreeDays() {
        Memory.noteToday(store, clock, "第一天")
        clock.plus(Duration.ofDays(3))
        Memory.noteToday(store, clock, "第四天")
        assertNull(Memory.noteToday(store, clock, " "))
        assertEquals(listOf("第四天"), Memory.notes(store, clock).map { it.read<app.agency.core.store.Note>().text })
        assertEquals(2, Memory.notes(store, clock, days = 4).size)
    }

    @Test fun theSummaryIsCappedAndTheMarkKept() {
        Memory.setSummary(store, "字".repeat(1000), 42)
        assertEquals(Memory.SUMMARY_MAX, Memory.summary(store).length)
        assertEquals(42L, Memory.summarisedThrough(store))
    }

    @Test fun judgeVerdictsParseAndTopicsTrigger() {
        assertEquals(Judge.Verdict(false, "不行"), Judge.parse("""```json
            {"ok":false,"reason":"不行"}
            ```"""))
        assertEquals(Judge.Verdict(true, ""), Judge.parse("""{"ok":true}"""))
        assertNull(Judge.parse("好"))
        val j = Judge(app.agency.core.llm.ScriptedLlm())
        assertTrue(j.triggered("今天吃了 1200 大卡"))
        assertTrue(j.triggered("體重記好了"))
        assertTrue(j.triggered("伏地挺身做完了嗎"))
        assertFalse(j.triggered("早安，先簽到。"))
    }

    @Test fun thePersonaUsesHisNameAndCarriesHandbookRulesAndMemory() {
        val p = Persona.system(Fixtures.rules(), "他不吃香菜。", "每天熱量目標 2000 大卡。", listOf("他喜歡豆漿"))
        assertTrue(p.contains("「阿星」"))
        listOf("他不吃香菜。", "每天熱量目標 2000 大卡。", "・他喜歡豆漿", "log_meal", "set_status", "propose", "find_videos").forEach {
            assertTrue(it, p.contains(it))
        }
        assertTrue(p.indexOf("他不吃香菜。") < p.indexOf("每天熱量目標") && p.indexOf("每天熱量目標") < p.indexOf("・他喜歡豆漿"))
        val blank = Persona.system(app.agency.core.rules.Rules(), "", "", emptyList())
        assertTrue(blank.contains("還沒有"))
    }

    private class FakeSearch(val result: SearchResult) : SearchClient {
        var placesArgs: Pair<String, String>? = null
        override suspend fun searchPlaces(area: String, dish: String): SearchResult { placesArgs = area to dish; return result }
        override suspend fun searchVideos(query: String): SearchResult = result
    }

    private fun hit(url: String) = SearchHit(url, "標題", "摘要", null, 2, 2.0, content = "教材內容")

    @Test fun theSearchPortKeepsOnlyVideoSitesForVideos() = runBlocking {
        val s = FakeSearch(SearchResult.Ok("q", listOf(hit("https://www.youtube.com/watch?v=a"), hit("https://example.com/a"))))
        val port = DeepSeekSearchPort(s)
        val v = port.videos("發聲 入門")
        assertEquals(listOf("https://www.youtube.com/watch?v=a"), v.map { it.url })
        assertEquals("教材內容", v.single().content)
        assertEquals(2, port.places("健康餐盒").size)
        assertEquals("" to "健康餐盒", s.placesArgs)
    }

    @Test fun aFailedSearchSaysSo() = runBlocking {
        try {
            DeepSeekSearchPort(FakeSearch(SearchResult.Failed(Fail.OFFLINE))).videos("發聲")
            fail("expected SearchUnavailable")
        } catch (e: SearchUnavailable) {
            assertEquals(Fail.OFFLINE, e.fail)
        }
    }
}
