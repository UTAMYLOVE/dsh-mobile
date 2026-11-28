package app.agency.core.llm

import app.agency.core.metering.Provider
import app.agency.core.metering.Usage
import app.agency.core.metering.UsageParser
import app.agency.core.metering.UsageSink
import app.agency.core.net.HttpOutcome
import app.agency.core.net.Transport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One place a search found. [url] stays in code (cards, the browser); a model only ever sees [title] and [summary]. */
data class SearchHit(
    val url: String,
    val title: String,
    val summary: String,
    /** RFC 3339 publication time as the search gave it, or null when unknown. */
    val date: String?,
    /** 0..4, -1 unknown. */
    val authority: Int,
    /** 0..3; 1.5 and above is what the provider calls high quality. */
    val rerank: Double,
    /** The page text the search returned (video searches ask for it: it is the lesson's 教材); empty otherwise. */
    val content: String = "",
)

sealed interface SearchResult {
    data class Ok(val query: String, val hits: List<SearchHit>) : SearchResult
    data class Failed(val fail: Fail) : SearchResult
}

/**
 * Web search for food places (T16) and for 理論課 videos (T19c). Every query is built by code: his coarse area and a dish name,
 * or a lesson category's words; nothing else is sent.
 */
fun interface SearchClient {
    suspend fun searchPlaces(area: String, dish: String): SearchResult

    /** Videos for [query] (code's words for a lesson category), with the page text; none by default. */
    suspend fun searchVideos(query: String): SearchResult = SearchResult.Ok("", emptyList())
}

/**
 * DeepSeek Search API, verified against api-docs.deepseek.com/guides/search and /api/search on 2026-11-22:
 * `POST https://api.deepseek.com/v1/tools/search`, bearer key, body `{query (1..128 chars), count (1..50)}` (also optional
 * `ori_question`, `localization` (an IP address), `need_content`, none of which we send); response `{results: [{rerank_score,
 * title, url, snippet, summary, content, site_name, site_icon, date, authority}]}`. Billing: 0.75 USD per 1,000 successful
 * requests, and a request is billed only when it returns 200 with a non-empty `results` array, so only those are metered.
 *
 * Results are data, not instructions: kept only with an http(s) URL and a title, `rerank_score` ≥ [minRerank], deduplicated by
 * URL, at most [MAX_HITS], title and summary cut short. Search operators (`site:`, `-`, quotes) are stripped from his words so
 * neither he nor a model can widen the query.
 */
class DeepSeekSearchClient(
    private val transport: Transport,
    private val key: () -> String?,
    private val checks: CallChecks,
    private val usage: UsageSink,
    private val base: HttpUrl = CompatEndpoint.DEEPSEEK_BASE,
    private val minRerank: Double = DEFAULT_MIN_RERANK,
) : SearchClient {
    override suspend fun searchPlaces(area: String, dish: String): SearchResult {
        val k = key()
        checks.blocked(k)?.let { return SearchResult.Failed(it) }
        val query = placesQuery(area, dish) ?: return SearchResult.Ok("", emptyList())
        checks.takeBudget()?.let { return SearchResult.Failed(it) }
        val url = base.newBuilder().addPathSegments("v1/tools/search").build()
        val body = buildJsonObject { put("query", query); put("count", REQUEST_COUNT) }
        val outcome = transport.postJson(url, mapOf("Authorization" to "Bearer ${CallChecks.usableKey(k)}"), body.toString())
        Fail.of(outcome)?.let { return SearchResult.Failed(it) }
        val o = UsageParser.parseObject((outcome as HttpOutcome.Response).body) ?: return SearchResult.Failed(Fail.BAD_RESPONSE)
        val results = o["results"] as? JsonArray ?: return SearchResult.Failed(Fail.BAD_RESPONSE)
        if (results.isNotEmpty()) runCatching { usage.record(Provider.DEEPSEEK, METER_MODEL, Usage(0, 0, webSearchRequests = 1)) }
        return SearchResult.Ok(query, filter(results))
    }

    /**
     * 理論課 candidates (T19c): the same endpoint with `need_content` so the page text comes back as the lesson's 教材 (at most
     * [MAX_CONTENT] characters). Video sites come first; every hit is a real result with its own link.
     */
    override suspend fun searchVideos(query: String): SearchResult {
        val k = key()
        checks.blocked(k)?.let { return SearchResult.Failed(it) }
        val q = words(query, MAX_QUERY).ifEmpty { return SearchResult.Ok("", emptyList()) }
        checks.takeBudget()?.let { return SearchResult.Failed(it) }
        val url = base.newBuilder().addPathSegments("v1/tools/search").build()
        val body = buildJsonObject { put("query", q); put("count", VIDEO_REQUEST_COUNT); put("need_content", true) }
        val outcome = transport.postJson(url, mapOf("Authorization" to "Bearer ${CallChecks.usableKey(k)}"), body.toString())
        Fail.of(outcome)?.let { return SearchResult.Failed(it) }
        val o = UsageParser.parseObject((outcome as HttpOutcome.Response).body) ?: return SearchResult.Failed(Fail.BAD_RESPONSE)
        val results = o["results"] as? JsonArray ?: return SearchResult.Failed(Fail.BAD_RESPONSE)
        if (results.isNotEmpty()) runCatching { usage.record(Provider.DEEPSEEK, METER_MODEL, Usage(0, 0, webSearchRequests = 1)) }
        // Ask for more and keep more than a place search: video sites are a small share of what comes back, and they go first.
        val hits = filter(results, withContent = true, max = Int.MAX_VALUE)
        return SearchResult.Ok(q, hits.sortedBy { if (videoSite(it.url)) 0 else 1 }.take(VIDEO_MAX_HITS))
    }

    private fun filter(results: JsonArray, withContent: Boolean = false, max: Int = MAX_HITS): List<SearchHit> {
        val seen = HashSet<String>()
        return results.asSequence().mapNotNull { it as? JsonObject }.mapNotNull { r ->
            val url = r.str("url")?.trim()?.toHttpUrlOrNull()?.toString() ?: return@mapNotNull null
            val title = r.str("title")?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val rerank = (r["rerank_score"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (rerank < minRerank || !seen.add(url)) return@mapNotNull null
            val summary = (r.str("summary")?.takeIf { it.isNotBlank() } ?: r.str("snippet")).orEmpty()
            SearchHit(
                url = url,
                title = takeCodePoints(title, MAX_TITLE),
                summary = takeCodePoints(clean(summary), MAX_SUMMARY),
                date = r.str("date"),
                authority = (r["authority"] as? JsonPrimitive)?.intOrNull ?: -1,
                rerank = rerank,
                content = if (withContent) takeCodePoints(clean(r.str("content").orEmpty()), MAX_CONTENT) else "",
            )
        }.take(max).toList()
    }

    private fun JsonObject.str(k: String): String? = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val DEFAULT_MIN_RERANK = 1.0
        const val MAX_HITS = 5
        const val VIDEO_REQUEST_COUNT = 20
        const val VIDEO_MAX_HITS = 8
        const val MAX_SUMMARY = 120
        const val MAX_TITLE = 80
        const val MAX_QUERY = 128
        const val MAX_DISH = 20
        const val MAX_AREA = 40
        const val REQUEST_COUNT = 10
        const val METER_MODEL = "deepseek-search"
        const val MAX_CONTENT = 3500

        /** Hosts that are video sites (their results come first in a video search). */
        val VIDEO_HOSTS = setOf("youtube.com", "youtu.be", "bilibili.com", "vimeo.com")

        fun videoSite(url: String): Boolean = url.toHttpUrlOrNull()?.host?.let { h -> VIDEO_HOSTS.any { h == it || h.endsWith(".$it") } } == true

        private val operatorChars = Regex("""["'“”:：()（）|]""")
        // `\s` alone misses the full-width space U+3000, which would leave `雞排　-炸物` as one word with a live `-` in it.
        private val space = Regex("""[\s\u3000]+""")

        /** Control characters out, runs of white space folded to one space. */
        private fun clean(s: String) = s.filter { !it.isISOControl() || it == ' ' || it == '\n' }.replace(space, " ").trim()

        /** Operator characters out, and a leading `-` (exclude) or `site`-style word prefix cannot survive either. */
        private fun words(s: String, max: Int): String =
            clean(s.replace(operatorChars, " ")).split(' ').map { it.trimStart('-', '+') }.filter { it.isNotEmpty() }
                .joinToString(" ").let { takeCodePoints(it, max) }.trim()

        /** At most [max] characters counted in code points, so an emoji or other pair is never cut in half. */
        private fun takeCodePoints(s: String, max: Int): String =
            s.substring(0, s.offsetByCodePoints(0, minOf(max, s.codePointCount(0, s.length))))

        /** `<area> <dish> 推薦`, at most [MAX_QUERY] characters; null when no dish is left to search for. */
        fun placesQuery(area: String, dish: String): String? {
            val d = words(dish, MAX_DISH).ifEmpty { return null }
            val a = words(area, MAX_AREA)
            val q = listOf(a, d, "推薦").filter { it.isNotEmpty() }.joinToString(" ")
            return if (q.codePointCount(0, q.length) <= MAX_QUERY) q else q.substring(0, q.offsetByCodePoints(0, MAX_QUERY))
        }
    }
}
