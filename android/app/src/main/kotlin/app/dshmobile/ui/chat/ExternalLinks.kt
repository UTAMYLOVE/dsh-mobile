package app.dshmobile.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** What became of a link: opened, refused by the app (not a web link, or the phone's own loopback), or no app could open it. */
enum class LinkOpen { OPENED, REFUSED, NO_BROWSER }

/** Opens links from the chat (search sources, links in replies) in the system browser. Only plain web links ever leave the app. */
object ExternalLinks {
    /**
     * Starts a browsable `ACTION_VIEW` for [url] when it is an http or https URL with a host. [LinkOpen.REFUSED] for every other
     * scheme (`file:`, `content:`, `intent:`, `javascript:`...) and for loopback hosts (127.0.0.0/8, localhost, ::1, 0.0.0.0:
     * dsh and the gateway live there, and a link to them can carry dsh's token; final review B2-M9);
     * [LinkOpen.NO_BROWSER] when no app can open it.
     */
    fun open(context: Context, url: String): LinkOpen {
        val uri = try { Uri.parse(url.trim()) } catch (_: RuntimeException) { return LinkOpen.REFUSED }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty() || isLoopback(host)) return LinkOpen.REFUSED
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LinkOpen.OPENED
        } catch (_: ActivityNotFoundException) {
            LinkOpen.NO_BROWSER
        } catch (_: SecurityException) {
            LinkOpen.NO_BROWSER
        }
    }

    private val IPV4_LOOPBACK = Regex("""127(\.\d{1,3}){3}""")

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "0:0:0:0:0:0:0:1" || host == "0.0.0.0" ||
            IPV4_LOOPBACK.matches(host)
}
