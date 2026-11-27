package app.agency.core.net

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** One place the app may talk to: scheme, host and port must all match. */
data class Origin(val scheme: String, val host: String, val port: Int) {
    fun matches(url: HttpUrl): Boolean = url.scheme == scheme && url.host.equals(host, ignoreCase = true) && url.port == port

    companion object {
        fun https(host: String) = Origin("https", host.lowercase(), 443)
    }
}

/** A request to a place outside the allowlist. Thrown before any byte of the request (or its key) leaves the phone. */
class BlockedHostException(val host: String) : IOException("blocked host")

/**
 * The allowlist interceptor. [HttpFactory] installs it twice: as an application interceptor (checks the request before a
 * connection is looked up or opened) and as a network interceptor (checks every hop again, so a redirect target is refused
 * even on a client someone rebuilt with redirects on). [allowed] is read on every request, so a settings change applies at once.
 * [onBlocked] gets the host only (never the path, query, headers or body); the app logs it in its error log.
 */
class NetworkGate(
    private val allowed: () -> Set<Origin>,
    private val onBlocked: (String) -> Unit = {},
) : Interceptor {
    private val blocked = AtomicInteger()

    /** Requests refused since start. */
    val blockedCount: Int get() = blocked.get()

    /** Fails closed: an [allowed] that throws (settings not readable) permits nothing, instead of crashing OkHttp's thread. */
    fun permits(url: HttpUrl): Boolean = runCatching { allowed() }.getOrDefault(emptySet()).any { it.matches(url) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!permits(url)) {
            blocked.incrementAndGet()
            runCatching { onBlocked(url.host) }
            throw BlockedHostException(url.host)
        }
        return chain.proceed(chain.request())
    }

    companion object {
        const val DEEPSEEK_HOST = "api.deepseek.com"
        const val ANTHROPIC_HOST = "api.anthropic.com"

        /** The release check (owner 2026-11-27: 「有新版本要跳通知」): GitHub's API, for the one public releases repository. */
        const val GITHUB_API_HOST = "api.github.com"

        /**
         * The production allowlist: the two model hosts, GitHub's API for the release check, plus the host of his compatible
         * service when its address is https on port 443. Anything else he types (http, another port, garbage) adds nothing.
         */
        fun allowlist(compatUrl: String?): Set<Origin> {
            val base = setOf(Origin.https(DEEPSEEK_HOST), Origin.https(ANTHROPIC_HOST), Origin.https(GITHUB_API_HOST))
            val compat = compatUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }?.toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.port == 443 }
                ?: return base
            return base + Origin.https(compat.host)
        }
    }
}
