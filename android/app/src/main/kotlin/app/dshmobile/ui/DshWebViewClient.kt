package app.dshmobile.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.net.URISyntaxException

/**
 * Keeps the main WebView on the one dsh it belongs to: only `http://127.0.0.1:<dshPort>/...` loads in the view, other web
 * links go to the system browser (only after a user gesture), everything else is dropped. URLs are never logged: the
 * launch URL carries dsh's one-time token.
 */
class DshWebViewClient(
    private val openExternal: (Uri) -> Unit,
    private val onLoaded: () -> Unit = {},
    private val onMainFrameError: () -> Unit = {},
    private val onRendererGone: () -> Unit = {},
) : WebViewClient() {
    enum class Decision { ALLOW, EXTERNAL, BLOCK }

    /** Port of the dsh this view may show; until it is known (<= 0) nothing on loopback is allowed. */
    @Volatile var dshPort: Int = -1

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        when (decide(request.url?.toString(), dshPort)) {
            Decision.ALLOW -> return false
            Decision.EXTERNAL -> if (request.hasGesture()) {
                try {
                    openExternal(request.url)
                } catch (_: ActivityNotFoundException) {
                    // no browser installed: nothing to do, and the link must not load here
                }
            }
            Decision.BLOCK -> Unit
        }
        return true
    }

    override fun onPageFinished(view: WebView?, url: String?) = onLoaded()

    override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
        if (request.isForMainFrame) onMainFrameError()
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
        if (request.isForMainFrame && (errorResponse?.statusCode ?: 0) >= 400) onMainFrameError()
    }

    /** The renderer died (killed for memory, or crashed): the view is unusable, the activity builds a new one. */
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onRendererGone()
        return true
    }

    companion object {
        /** Pure whitelist: what to do with a navigation to [url] while the view belongs to the dsh on [dshPort]. */
        fun decide(url: String?, dshPort: Int): Decision {
            if (url.isNullOrBlank()) return Decision.BLOCK
            val uri = try {
                URI(url.trim())
            } catch (_: URISyntaxException) {
                return Decision.BLOCK
            }
            val scheme = uri.scheme?.lowercase() ?: return Decision.BLOCK
            if (scheme != "http" && scheme != "https") return Decision.BLOCK
            val host = uri.host?.lowercase() ?: return Decision.BLOCK // also drops "host:notaport"
            if (uri.userInfo != null) return Decision.BLOCK // `user@host` tricks: never load, never hand over
            if (isLoopbackName(host)) {
                val dsh = scheme == "http" && host == "127.0.0.1" && dshPort > 0 && uri.port == dshPort
                return if (dsh) Decision.ALLOW else Decision.BLOCK
            }
            return Decision.EXTERNAL
        }

        private fun isLoopbackName(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") || LOOPBACK_V4.matches(host) || host == "0.0.0.0" ||
                host.startsWith("[") // IPv6 literals (::1 and its mapped forms) are not worth telling apart

        private val LOOPBACK_V4 = Regex("""^127(\.\d{1,3}){3}$""")
    }
}
