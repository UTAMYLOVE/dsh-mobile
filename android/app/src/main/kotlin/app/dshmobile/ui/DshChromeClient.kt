package app.dshmobile.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * The main screen's [WebChromeClient]. Without one, a WebView ignores `<input type=file>` and silently dismisses every
 * `alert`/`confirm`/`prompt` (a `confirm()` before deleting something would never say yes). With one:
 * - `onJsAlert`/`onJsConfirm`/`onJsPrompt` are not overridden, so the WebView shows its own default dialogs;
 * - a file input opens the system picker ([FileChooserParams.createIntent], started by [openPicker] through the Activity Result
 *   API). The picked documents come back as content URIs the picker grants; no storage permission is involved. Every request is
 *   answered exactly once (null when cancelled or when no picker exists), as the WebView requires.
 */
class DshChromeClient(
    /** Starts the picker; false when it could not be started (no app handles the intent). */
    private val openPicker: (Intent) -> Boolean,
) : WebChromeClient() {
    private var pending: ValueCallback<Array<Uri>>? = null

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        pending?.onReceiveValue(null) // an earlier request that never got its answer
        pending = null
        val intent = try {
            fileChooserParams.createIntent()
        } catch (_: RuntimeException) {
            null
        }
        if (intent == null || !openPicker(intent)) {
            filePathCallback.onReceiveValue(null)
            return true
        }
        pending = filePathCallback
        return true
    }

    /** The picker's result (from the Activity Result callback). */
    fun onPickerResult(resultCode: Int, data: Intent?) {
        val callback = pending ?: return
        pending = null
        callback.onReceiveValue(pickedUris(resultCode, data))
    }

    companion object {
        /** What `FileChooserParams.parseResult` returns, without needing the WebView provider: null unless OK with a URI. */
        internal fun pickedUris(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            val clip = data.clipData
            val uris = if (clip != null && clip.itemCount > 0) {
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            } else {
                listOfNotNull(data.data)
            }
            return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        }
    }
}
