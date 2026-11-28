package app.agency.core.agent

import app.agency.core.llm.DeepSeekSearchClient
import app.agency.core.llm.Fail
import app.agency.core.llm.SearchClient
import app.agency.core.llm.SearchHit
import app.agency.core.llm.SearchResult

/** A search that could not run (offline, no key, cap…). A tool catches it and tells 小葵 the search is not available now. */
class SearchUnavailable(val fail: Fail) : Exception("search unavailable: $fail")

/**
 * [SearchPort] over the DeepSeek search ([DeepSeekSearchClient]). Every hit is a real search result with its own link; nothing
 * a model wrote can become one.
 * - [videos] asks for the page content (it becomes the lesson's material): results on video sites
 *   ([DeepSeekSearchClient.videoSite]) first, then teaching pages with enough text;
 * - [places] searches `<query> 推薦` (the query is cut to the client's dish length; operators are stripped by the client).
 * A failed search throws [SearchUnavailable] so the tool can say so instead of claiming nothing exists.
 */
class DeepSeekSearchPort(private val client: SearchClient) : SearchPort {
    override suspend fun videos(query: String): List<Hit> = when (val r = client.searchVideos(query)) {
        is SearchResult.Failed -> throw SearchUnavailable(r.fail)
        // Videos first. This search returns few video-site pages, so real teaching pages with enough text to learn from (and to
        // write a quiz from) follow them rather than leaving him with nothing.
        is SearchResult.Ok -> r.hits.filter { DeepSeekSearchClient.videoSite(it.url) || it.content.length >= MIN_PAGE_TEXT }
            .map { it.toHit().copy(video = DeepSeekSearchClient.videoSite(it.url)) }
    }

    override suspend fun places(query: String): List<Hit> = when (val r = client.searchPlaces("", query)) {
        is SearchResult.Failed -> throw SearchUnavailable(r.fail)
        is SearchResult.Ok -> r.hits.map { it.toHit() }
    }

    private companion object { const val MIN_PAGE_TEXT = 400 }

    private fun SearchHit.toHit() = Hit(title = title, url = url, summary = summary, content = content)
}
