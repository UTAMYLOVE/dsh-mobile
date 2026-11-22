package app.agency.core.llm

import app.agency.core.llm.LlmTestKit.Companion.json
import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSearchClientTest {
    private val kit = LlmTestKit()

    @After fun tearDown() = kit.close()

    private fun hit(url: String?, title: String?, rerank: Double, summary: String = "好吃的雞胸便當，蛋白質高。") =
        """{"rerank_score":$rerank,"title":${title?.let { "\"$it\"" } ?: "null"},"url":${url?.let { "\"$it\"" } ?: "null"},
           "snippet":"短摘要","summary":"$summary","content":"","site_name":null,"site_icon":null,
           "date":"2026-10-01T00:00:00+00:00","authority":2}"""

    @Test fun theRequestCarriesOnlyTheCodeBuiltQueryAndACount() = runBlocking {
        kit.enqueue(200, """{"results":[]}""")
        kit.search().searchPlaces("大安區", "雞胸肉便當")
        val req = kit.take()
        assertEquals("/v1/tools/search", req.path)
        assertEquals("Bearer sk-test-key", req.getHeader("Authorization"))
        val body = json(req)
        assertEquals(setOf("query", "count"), body.keys)
        assertEquals(JsonPrimitive("大安區 雞胸肉便當 推薦"), body["query"])
        assertEquals(JsonPrimitive(10), body["count"])
    }

    @Test fun searchOperatorsAreStrippedAndTheQueryIsCapped() {
        assertEquals("大安區 site example.com 雞腿 推薦", DeepSeekSearchClient.placesQuery("大安區", "site:example.com -雞腿"))
        assertEquals("牛肉麵 推薦", DeepSeekSearchClient.placesQuery("  ", "\"牛肉麵\""))
        assertNull(DeepSeekSearchClient.placesQuery("大安區", " -: "))
        val q = DeepSeekSearchClient.placesQuery("區".repeat(200), "麵".repeat(200))!!
        assertTrue(q.length <= DeepSeekSearchClient.MAX_QUERY)
        assertEquals("麵".repeat(20), q.split(' ')[1])
    }

    @Test fun aFullWidthSpaceSeparatesWordsSoAnExclusionCannotForm() {
        assertEquals("大安區 雞排 炸物 推薦", DeepSeekSearchClient.placesQuery("大安區", "雞排\u3000-炸物"))
        assertEquals("大安區 雞排 推薦", DeepSeekSearchClient.placesQuery("大安區\u3000", "\u3000雞排"))
    }

    @Test fun capsNeverSplitASurrogatePair() = runBlocking {
        val dish = "麵".repeat(19) + "\uD83C\uDF5C" + "湯" // the bowl emoji sits on the 20th code point
        assertEquals("麵".repeat(19) + "\uD83C\uDF5C", DeepSeekSearchClient.placesQuery("", dish)!!.removeSuffix(" 推薦"))
        val title = "店".repeat(79) + "\uD83C\uDF5C" + "尾"
        kit.enqueue(200, """{"results":[${hit("https://a.example/1", title, 2.0, "好".repeat(119) + "\uD83C\uDF5C" + "尾")}]}""")
        val h = (kit.search().searchPlaces("大安區", "麵") as SearchResult.Ok).hits.single()
        assertEquals("店".repeat(79) + "\uD83C\uDF5C", h.title)
        assertEquals("好".repeat(119) + "\uD83C\uDF5C", h.summary)
    }

    @Test fun resultsAreFilteredDedupedAndCapped() = runBlocking {
        val long = "很".repeat(300)
        val results = listOf(
            hit("https://a.example/1", "甲店", 2.0, long),
            hit(null, "沒網址", 2.9),
            hit("https://b.example/1", null, 2.9),
            hit("https://c.example/1", "太不相關", 0.4),
            hit("not a url", "壞網址", 2.0),
            hit("https://a.example/1", "甲店重複", 2.0),
        ) + (2..7).map { hit("https://d.example/$it", "店$it", 1.5) }
        kit.enqueue(200, """{"results":[${results.joinToString(",")}]}""")
        val r = kit.search().searchPlaces("大安區", "雞胸肉") as SearchResult.Ok
        assertEquals(listOf("甲店", "店2", "店3", "店4", "店5"), r.hits.map { it.title })
        assertEquals(DeepSeekSearchClient.MAX_SUMMARY, r.hits[0].summary.length)
        assertEquals("https://a.example/1", r.hits[0].url)
        assertEquals(2, r.hits[0].authority)
        assertEquals("2026-10-01T00:00:00+00:00", r.hits[0].date)
        assertEquals(listOf(Triple(Provider.DEEPSEEK, DeepSeekSearchClient.METER_MODEL, Usage(0, 0, webSearchRequests = 1))), kit.recorded)
    }

    @Test fun anEmptyResultIsNotBilled() = runBlocking {
        kit.enqueue(200, """{"results":[]}""")
        assertEquals(SearchResult.Ok("大安區 雞胸肉 推薦", emptyList()), kit.search().searchPlaces("大安區", "雞胸肉"))
        assertTrue(kit.recorded.isEmpty())
    }

    @Test fun theSpendCapAndAMissingKeyStopItBeforeAnyRequest() = runBlocking {
        kit.capBlocked = true
        assertEquals(SearchResult.Failed(Fail.CAP), kit.search().searchPlaces("大安區", "雞胸肉"))
        kit.capBlocked = false
        kit.key = null
        assertEquals(SearchResult.Failed(Fail.NO_KEY), kit.search().searchPlaces("大安區", "雞胸肉"))
        assertEquals(0, kit.server.requestCount)
    }

    @Test fun errorsMapAndOnlyRetryableOnesAreRetried() = runBlocking {
        kit.enqueue(402, """{"error":{"message":"Insufficient Balance","type":"invalid_request_error","param":null,"code":"x"}}""")
        assertEquals(SearchResult.Failed(Fail.KEY_REJECTED), kit.search().searchPlaces("大安區", "雞胸肉"))
        assertEquals(1, kit.server.requestCount)
        kit.enqueue(408)
        kit.enqueue(200, """{"results":[]}""")
        assertTrue(kit.search().searchPlaces("大安區", "雞胸肉") is SearchResult.Ok)
        assertEquals(3, kit.server.requestCount)
        kit.enqueue(200, """{"nope":1}""")
        assertEquals(SearchResult.Failed(Fail.BAD_RESPONSE), kit.search().searchPlaces("大安區", "雞胸肉"))
    }

    // ---- 理論課 videos (T19c) ----

    private fun video(url: String, title: String, content: String) =
        """{"rerank_score":2.0,"title":"$title","url":"$url","snippet":"短摘要","summary":"影片摘要","content":"$content","site_name":null,"site_icon":null,"date":null,"authority":2}"""

    @Test fun aVideoSearchSendsOnlyCodesWordsAsksForThePageTextAndPutsVideoSitesFirst() = runBlocking {
        kit.enqueue(200, """{"results":[${video("https://blog.example/a", "部落格文章", "文章內容")},${video("https://www.youtube.com/watch?v=abc", "發聲練習", "腹式呼吸")}]}""")
        val r = kit.search().searchVideos("發聲練習 唱歌技巧 教學 影片 site:evil.example") as SearchResult.Ok
        val body = json(kit.take())
        assertEquals(setOf("query", "count", "need_content"), body.keys)
        assertEquals(JsonPrimitive(true), body["need_content"])
        assertEquals(JsonPrimitive("發聲練習 唱歌技巧 教學 影片 site evil.example"), body["query"])
        assertEquals(listOf("https://www.youtube.com/watch?v=abc", "https://blog.example/a"), r.hits.map { it.url })
        assertEquals("腹式呼吸", r.hits[0].content)
        assertEquals(1, kit.recorded.size)
    }

    @Test fun aVideoSearchGoesOnlyThroughTheGateAndStopsAtTheCap() = runBlocking {
        kit.allowed = emptySet()
        assertTrue(kit.search().searchVideos("聲樂 影片") is SearchResult.Failed)
        assertEquals(0, kit.server.requestCount)
        assertEquals(listOf(kit.base.host), kit.blockedHosts.distinct())
        kit.allowed = setOf(kit.origin)
        kit.capBlocked = true
        assertEquals(SearchResult.Failed(Fail.CAP), kit.search().searchVideos("聲樂 影片"))
        assertEquals(0, kit.server.requestCount)
    }
}
