package app.agency.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The newest published release: its tag (`v<versionName>+<versionCode>`), its name and code, and its page. */
data class Release(val tag: String, val versionName: String, val versionCode: Int?, val page: String)

/**
 * 新版本通知 (owner 2026-11-27): at most once a day the app asks GitHub for the latest release of the public releases repository,
 * through the one network gate. Only what the request needs goes out: no key, no cookie, no id of the phone or of him; a
 * generic User-Agent (GitHub refuses a request without one). The answer is read for the tag and the page only.
 */
class ReleaseCheck(private val transport: Transport, private val url: HttpUrl = LATEST) {
    suspend fun latest(): Release? {
        val out = transport.getJson(url, HEADERS) as? HttpOutcome.Response ?: return null
        if (out.code != 200) return null
        val o = runCatching { Json.parseToJsonElement(out.body) as JsonObject }.getOrNull() ?: return null
        val tag = runCatching { o["tag_name"]!!.jsonPrimitive.content }.getOrNull() ?: return null
        val page = runCatching { o["html_url"]!!.jsonPrimitive.content }.getOrNull()?.takeIf { it.startsWith(PAGE_PREFIX) } ?: RELEASES_PAGE
        val (name, code) = parseTag(tag) ?: return null
        return Release(tag, name, code, page)
    }

    companion object {
        const val OWNER = "UTAMYLOVE"
        const val REPO = "dsh-mobile-releases"
        val LATEST: HttpUrl = "https://${NetworkGate.GITHUB_API_HOST}/repos/$OWNER/$REPO/releases/latest".toHttpUrl()
        const val PAGE_PREFIX = "https://github.com/$OWNER/$REPO/"
        const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

        /** Exactly these headers, nothing about him or the phone. */
        val HEADERS = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "dsh-mobile")

        private val TAG = Regex("^v?([0-9]+(?:\\.[0-9]+){1,3})(?:\\+([0-9]+))?$")

        /** `v0.9.1+19` → 0.9.1 and 19 (the release tags the owner makes); null for anything else. */
        fun parseTag(tag: String): Pair<String, Int?>? = TAG.find(tag.trim())?.let { it.groupValues[1] to it.groupValues[2].toIntOrNull() }

        /** Whether [r] is newer than the installed [versionName] / [versionCode]: by the code when the tag has one, else the name. */
        fun newer(r: Release, versionName: String, versionCode: Int): Boolean {
            r.versionCode?.let { return it > versionCode }
            val a = r.versionName.split('.').map { it.toIntOrNull() ?: 0 }
            val b = versionName.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
