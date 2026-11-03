package app.dshmobile.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens links from the chat (search sources, links in replies) in the system browser. Only plain web links ever leave the app. */
object ExternalLinks {
    /**
     * Starts a browsable `ACTION_VIEW` for [url] when it is an http or https URL with a host. False for every other scheme
     * (`file:`, `content:`, `intent:`, `javascript:`...) and when no app can open it.
     */
    fun open(context: Context, url: String): Boolean {
        val uri = try { Uri.parse(url.trim()) } catch (_: RuntimeException) { return false }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
