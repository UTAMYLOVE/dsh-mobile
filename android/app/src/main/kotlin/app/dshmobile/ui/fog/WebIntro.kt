package app.dshmobile.ui.fog

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.os.Message
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import app.dshmobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The dsh community startup looks the owner asked for as they are (「不是有很多很好看的dsh 開場動畫嗎？」): each one is the
 * author's own page, adapted only where its pictures, names, sound and dsh hooks had to go, in assets/intro-web/<[key]>/ (its
 * NOTICE.md lists the source commit and every change). [ground] is the page's own background, shown while the page loads.
 * [calmMs] and [runMs] are how long the page itself plays (the numbers its NOTICE.md gives), which sets the app's ceiling.
 */
enum class WebLook(val key: String, internal val ground: Long, private val calmMs: Long) {
    /** 櫻花動漫風: the whole run is the setting (a long welcome may stretch it to about 2.6 s). */
    SAKURA("sakura", 0xFFF7F1F8, 2_000) {
        override fun runMs(seconds: Float) = max(ms(seconds), 2_600L)
    },
    /** 粉色霓虹敘事: the original's own speed setting makes it about the setting long, but never under about 4 s. */
    NEON("neon", 0xFF0B0B0E, 2_200) {
        override fun runMs(seconds: Float) = max(ms(seconds), 4_000L) + 200L
    },
    /** 光點聚形波浪: the setting from its first frame, after up to 0.6 s of getting the mark ready on a slow phone. */
    WAVE("wave", 0xFFF7F8FA, 1_700) {
        override fun runMs(seconds: Float) = ms(seconds) + 600L
    },
    /**
     * 科技檔案授權: its own speed (about 18.7 s at 1×, kept within 0.35×–3×) is set from the setting, so it is the setting
     * long, but never under 18.7 s / 3 (about 6.2 s).
     */
    ACCESS("access", 0xFF000000, 2_200) {
        override fun runMs(seconds: Float) = max(ms(seconds), 6_234L)
    },
    ;

    protected abstract fun runMs(seconds: Float): Long

    /** How long the page plays for [intro] (from its first frame), or its short still with [reduced]. */
    fun ownMs(intro: Intro, reduced: Boolean): Long =
        if (reduced) calmMs else runMs(intro.seconds.let { if (it.isFinite()) it else Intro().seconds }.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS))

    protected fun ms(seconds: Float): Long = (seconds * 1000f).roundToLong()
}

/** How the app hosts a [WebLook]: where the pages are served from, what they are told and how long they may take. */
object IntroWeb {
    /** WebViewAssetLoader's own domain: https, never a real host, never anything on the network. */
    const val HOST = WebViewAssetLoader.DEFAULT_DOMAIN
    /** The only path the loader serves: the community looks' folders under assets/intro-web/. */
    const val PREFIX = "/assets/intro-web/"
    const val ASSET_DIR = "intro-web"
    /**
     * The user's own picture, as each page asks for it (relative to its folder) with picture=1: the picture exactly as the app
     * shows it (recoloured at 六色 > 0), JPEG, or PNG when it has see-through parts.
     */
    const val PICTURE = "picture.img"
    /**
     * Its avatar square (調整頭像, or the app's guess of the face), served exactly when [PICTURE] is (picture=1): a
     * [AvatarCuts.SIDE] × [AvatarCuts.SIDE] square cut from that same picture, JPEG, or PNG when the picture has see-through parts.
     */
    const val AVATAR = "avatar.img"
    /** How long a run waits for its picture files to be ready before the page loads without them. */
    const val PREPARE_MS = 3_000L
    /** The page's only way back to the app: `window.DSHM.done()` ([IntroWebBridge]); `#done` in the URL is the fallback. */
    const val BRIDGE = "DSHM"
    const val DONE = "done"
    /** A page that has not shown its first frame by then gives way to 櫻紋陣. */
    const val FIRST_FRAME_MS = 4_000L
    /** The ceiling after the page's first frame: its own length and this much more. */
    const val MARGIN_MS = 3_000L
    /** Never longer than this, whatever the page does. */
    const val MAX_MS = 60_000L
    /** The screen underneath comes back this long before the page's own end, as its exit turns it see-through. */
    const val EXIT_LEAD_MS = 1_200L
    /** The same policy each page carries in its own `<meta>`, sent again as a header with everything the loader serves. */
    const val CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; " +
        "connect-src 'none'; media-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"

    /** The page for [look], told the user's words and choices in its query (the contract every bundle reads). */
    fun url(look: WebLook, intro: Intro, reduced: Boolean, picture: Boolean): String = Uri.Builder()
        .scheme("https").authority(HOST).path("$PREFIX${look.key}/index.html")
        .appendQueryParameter("name", intro.name.trim())
        .appendQueryParameter("welcome", intro.welcome.trim())
        .appendQueryParameter("number", intro.number.trim())
        .appendQueryParameter("level", intro.level.trim())
        .appendQueryParameter("seconds", String.format(Locale.ROOT, "%.1f", intro.seconds.let { if (it.isFinite()) it else Intro().seconds }.coerceIn(Intro.MIN_SECONDS, Intro.MAX_SECONDS)))
        .appendQueryParameter("reduced", if (reduced) "1" else "0")
        .appendQueryParameter("skippable", if (intro.skippable) "1" else "0")
        .appendQueryParameter("picture", if (picture) "1" else "0")
        .appendQueryParameter("lang", "zh-TW")
        .build().toString()

    /** The ceiling from the page's first frame: its own length + [MARGIN_MS], at most [MAX_MS]. */
    fun ceilingMs(look: WebLook, intro: Intro, reduced: Boolean): Long = min(look.ownMs(intro, reduced) + MARGIN_MS, MAX_MS)

    /** True for the page's URL once it has set `#done`. */
    fun isDone(url: String?): Boolean = url != null && runCatching { Uri.parse(url).fragment == DONE }.getOrDefault(false)
}

/**
 * Everything a community look's WebView may load, decided here and nowhere else: through [WebViewAssetLoader] (https on
 * [IntroWeb.HOST] only), the files of a look's own folder in assets/intro-web/ (one level, known kinds), [IntroWeb.PICTURE]
 * streamed from [picture] and [IntroWeb.AVATAR] streamed from [avatar] when there are such files. Both are prepared before the
 * page loads ([IntroWebPictures]): the picture exactly as the app shows it (the recoloured copy at 六色 > 0, so the owner's
 * picture comes in his colours) and its avatar square, small files the request thread only streams, never decodes. Every
 * other URL, of any scheme or host, gets [blocked]: an empty 403, never the network.
 */
class IntroWebRequests internal constructor(private val open: (String) -> InputStream, picture: File?, avatar: File? = null) {
    constructor(context: Context, picture: File?, avatar: File? = null) : this({ path -> context.assets.open(path) }, picture, avatar)

    /** The run's picture and avatar files; set once, before the page loads ([prepared]). */
    @Volatile private var files: Pair<File?, File?> = picture to avatar

    /** The files [IntroWebPictures] prepared for this run (null: none), handed over before the page is loaded. */
    internal fun prepared(picture: File?, avatar: File?) { files = picture to avatar }

    /** True when the page may be told picture=1: there is a picture to serve. */
    internal val hasPicture: Boolean get() = files.first?.isFile == true

    private val loader = WebViewAssetLoader.Builder()
        .setDomain(IntroWeb.HOST)
        .setHttpAllowed(false)
        .addPathHandler(IntroWeb.PREFIX) { path -> serve(path) }
        .build()

    fun respond(url: Uri): WebResourceResponse = runCatching { loader.shouldInterceptRequest(url) }.getOrNull() ?: blocked()

    private fun serve(path: String): WebResourceResponse? {
        val parts = path.split('/')
        if (parts.size != 2 || parts.any { !SAFE_NAME.matches(it) }) return null
        val (dir, name) = parts
        if (WebLook.entries.none { it.key == dir }) return null
        if (name == IntroWeb.PICTURE) return streamed(files.first)
        if (name == IntroWeb.AVATAR) return streamed(files.second)
        val type = TYPES[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: return null
        val stream = runCatching { open("${IntroWeb.ASSET_DIR}/$dir/$name") }.getOrNull() ?: return null
        return ok(type, stream)
    }

    /** [picture] as it is on disk (a prepared file: only streamed, never decoded here), its kind read from its first bytes. */
    private fun streamed(picture: File?): WebResourceResponse? {
        val file = picture?.takeIf { it.isFile } ?: return null
        val stream = runCatching { BufferedInputStream(file.inputStream()) }.getOrNull() ?: return null
        val head = ByteArray(12)
        val read = runCatching {
            stream.mark(head.size)
            val n = stream.read(head)
            stream.reset()
            n
        }.getOrDefault(-1)
        if (read <= 0) { runCatching { stream.close() }; return null }
        return ok(pictureType(head), stream)
    }

    companion object {
        /** A file or folder name: letters, digits, `-`, `_` and dots, never starting with a dot (so never `.` or `..`). */
        private val SAFE_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}")
        private val TYPES = mapOf(
            "html" to "text/html", "css" to "text/css", "js" to "text/javascript", "json" to "application/json",
            "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
            "woff2" to "font/woff2",
        )
        private val HEADERS = mapOf(
            "Content-Security-Policy" to IntroWeb.CSP,
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "no-store",
            "Referrer-Policy" to "no-referrer",
        )

        /** The picture's kind from its first bytes (the app keeps it as JPEG, or PNG when it has transparent parts). */
        internal fun pictureType(head: ByteArray): String = when {
            head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> "image/png"
            head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "GIF8" -> "image/gif"
            else -> "image/jpeg"
        }

        private fun ok(type: String, stream: InputStream) =
            WebResourceResponse(type, if (type.startsWith("text/") || type == "application/json") "utf-8" else null, 200, "OK", HEADERS, stream)

        /** What every URL outside the looks' folders gets: nothing. */
        fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", HEADERS, ByteArrayInputStream(ByteArray(0)))
    }
}

/**
 * The picture files a community look's run is given, made before its page loads and off the main thread: [IntroWeb.PICTURE] is
 * the picture exactly as the app shows it ([LookPictures.shownFile]: the recoloured copy at 六色 > 0, else the picture) and
 * [IntroWeb.AVATAR] the avatar square cut from it ([AvatarCuts.cut], with the square chosen in 調整頭像 or the app's guess).
 * Both are kept next to the picture, so a later run only finds them.
 */
internal object IntroWebPictures {
    class Ready(val picture: File, val avatar: File?)

    /** [file]'s files for [look]; null when there is no picture. */
    suspend fun prepare(file: File, look: Look): Ready? = withContext(Dispatchers.Default) {
        val shown = LookPictures.shownFile(file, look.picture, look.six, look.aqua) ?: return@withContext null
        val crop = look.avatar ?: AvatarGuess.auto(file, look.picture)
        Ready(shown, AvatarCuts.cut(file, shown, look.picture, look.six, look.aqua, crop))
    }
}

/**
 * The one object a page sees, as `window.DSHM`: [done] and nothing else, and it returns nothing. Called on the WebView's own
 * thread; [onDone] runs once, on the main thread.
 */
class IntroWebBridge internal constructor(private val onDone: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val called = AtomicBoolean(false)

    @JavascriptInterface
    fun done() {
        if (called.compareAndSet(false, true)) main.post(onDone)
    }
}

/** What a page's WebView tells its [WebIntro]; always on the main thread. */
internal interface IntroWebEvents {
    /** The page's first frame is on screen. */
    fun visible()
    /** The page said it is over (`DSHM.done()` or `#done`). */
    fun done()
    /** The page could not load, its renderer went away or it was flagged: it is not shown again. */
    fun failed()
    /** The user touched the page. */
    fun touched()
}

/**
 * The page's [WebViewClient]: every request through [requests], no navigation away (a page that tries is simply not followed),
 * no prompts for passwords or certificates, and the page's state reported to [events].
 */
internal class IntroWebClient(private val requests: IntroWebRequests, private val events: IntroWebEvents) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
        request?.url?.let(requests::respond) ?: IntroWebRequests.blocked()

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    override fun onPageCommitVisible(view: WebView?, url: String?) = events.visible()

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        if (IntroWeb.isDone(url)) events.done()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) events.failed()
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (request?.isForMainFrame == true) events.failed()
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        events.failed()
        return true // the WebView is let go of and destroyed by its WebIntro; the app goes on
    }

    override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
        callback?.backToSafety(false)
        events.failed()
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel() }

    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) { request?.cancel() }

    override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) { dontResend?.sendToTarget() }
}

/**
 * The client a let-go WebView keeps until it is destroyed: the page may still run for a moment, so every request still gets
 * [IntroWebRequests.blocked] and nothing is followed (a plain WebViewClient would send its requests to the network), a renderer
 * that goes away does not take the app with it (a plain one returns false, which kills the app), and it holds nothing of the
 * intro.
 */
internal object ClosedIntroWebClient : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse = IntroWebRequests.blocked()

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = true

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel() }

    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) { request?.cancel() }
}

/** No permission, window, dialog, file picker or console output for a page. */
internal class IntroWebChrome : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }
    override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
    override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
}

/** Making and letting go of a community look's WebView. */
internal object IntroWebView {
    /** The WebViews already let go of (weakly held: a destroyed one is soon gone). Main thread only. */
    private val released: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

    /**
     * A WebView locked down for one community look: JavaScript (the looks are scripts) and nothing else a page could reach out
     * with. No file or content access, no storage, no cache, no saved form data, no zoom, no geolocation, no mixed content, no
     * second window, media only after a tap, Safe Browsing on, text at 100 % (the looks are laid out at fixed sizes), the page's
     * own colours (no algorithmic darkening), a see-through background (each page turns see-through as it leaves), [bridge] as
     * `window.DSHM` and every request through [IntroWebClient].
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun create(context: Context, requests: IntroWebRequests, bridge: IntroWebBridge, events: IntroWebEvents): WebView {
        val web = WebView(context)
        lockDown(web.settings)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web.webViewClient = IntroWebClient(requests, events)
        web.webChromeClient = IntroWebChrome()
        web.addJavascriptInterface(bridge, IntroWeb.BRIDGE)
        web.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) events.touched(); false }
        return web
    }

    @Suppress("DEPRECATION")
    internal fun lockDown(s: WebSettings) {
        s.javaScriptEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.domStorageEnabled = false
        s.databaseEnabled = false
        s.setGeolocationEnabled(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.saveFormData = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.safeBrowsingEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        // Already the default for this app; said again so a page keeps its own colours. Robolectric's settings lack it.
        runCatching { s.isAlgorithmicDarkeningAllowed = false }
    }

    /**
     * Lets [web] go: no client, bridge or listener keeps a reference to the intro, and it is destroyed once it is out of the view
     * tree (posted, so its AndroidView has detached it first). Until then it stays as locked as it was ([ClosedIntroWebClient],
     * a fresh [IntroWebChrome]). Safe to call more than once.
     */
    fun release(web: WebView) {
        if (!released.add(web)) return
        web.setOnTouchListener(null)
        web.stopLoading()
        web.removeJavascriptInterface(IntroWeb.BRIDGE)
        web.webChromeClient = IntroWebChrome()
        web.webViewClient = ClosedIntroWebClient
        Handler(Looper.getMainLooper()).post {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }
}

/** The run's WebView, let go of when the run leaves the screen (or its composition is dropped before it showed). */
private class WebViewHolder(val web: WebView?) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() { web?.let(IntroWebView::release) }
    override fun onAbandoned() { web?.let(IntroWebView::release) }
}

/** One run of a community look: where it is. Read and written on the main thread only. */
private class WebIntroRun {
    var visible by mutableStateOf(false)
    /** The screen underneath is hidden (and the page's ground shows behind the WebView) until the page's exit nears. */
    var covering by mutableStateOf(true)
    var over = false
}

/**
 * A community look ([look]) over the app, the way [IntroHost] plays the drawn ones: [onOpaqueChange] true while it covers the
 * screen underneath, [onDone] exactly once. The WebView is made only here, so only when such a look is chosen and plays, and
 * it is destroyed as soon as the run is over.
 *
 * It ends when the page says so, at its own length + 3 s after its first frame (never later than a minute), or on back when
 * skippable; a tap goes to the page, which skips with its own exit when skippable. A page that cannot be made, loaded or shown
 * within [IntroWeb.FIRST_FRAME_MS] calls [onFail] instead (StartupIntro then plays 櫻紋陣), with nothing said to the user; one
 * that breaks after it showed simply ends.
 */
@Composable
internal fun WebIntro(
    intro: Intro,
    look: WebLook,
    reducedMotion: Boolean,
    onOpaqueChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    onFail: () -> Unit,
) {
    val context = LocalContext.current
    val file = LocalLookPicture.current
    val chosen = LocalLook.current
    val opaque by rememberUpdatedState(onOpaqueChange)
    val done by rememberUpdatedState(onDone)
    val fail by rememberUpdatedState(onFail)
    val run = remember { WebIntroRun() }
    val skipLabel = stringResource(R.string.intro_skip)

    fun uncover() {
        if (run.covering) {
            run.covering = false
            opaque(false)
        }
    }
    fun end() {
        if (run.over) return
        run.over = true
        uncover()
        done()
    }
    fun broke() {
        if (run.over) return
        if (run.visible) return end()
        run.over = true
        fail()
    }

    // The picture, only when there is one and the user lets the welcome use it (歡迎畫面用背景圖當頭像); its files for the page
    // (the picture as the app shows it and its avatar square) are prepared before the page loads.
    val picture = remember { file?.takeIf { intro.portrait && chosen.hasPicture && it.isFile } }
    val requests = remember { IntroWebRequests(context.applicationContext, null) }
    val holder = remember {
        val events = object : IntroWebEvents {
            override fun visible() { run.visible = true }
            override fun done() = end()
            override fun failed() = broke()
            override fun touched() { if (intro.skippable) uncover() }
        }
        WebViewHolder(runCatching { IntroWebView.create(context, requests, IntroWebBridge(::end), events) }.getOrNull())
    }
    DisposableEffect(Unit) { onDispose { opaque(false) } }
    val web = holder.web
    if (web == null) {
        // No WebView on this phone (or it is being updated): 櫻紋陣 plays instead.
        LaunchedEffect(Unit) { broke() }
        return
    }
    LaunchedEffect(web) {
        opaque(true)
        launch { delay(IntroWeb.MAX_MS); end() }
        if (picture != null) {
            // Off the main thread, and never on the WebView's request thread: a big picture is decoded (and recoloured, cut and
            // kept) only when its files are not kept yet. A picture not ready in time is left out of this run.
            val ready = withTimeoutOrNull(IntroWeb.PREPARE_MS) { runCatching { IntroWebPictures.prepare(picture, chosen) }.getOrNull() }
            withContext(Dispatchers.Main.immediate) { requests.prepared(ready?.picture, ready?.avatar) }
        }
        val given = picture != null && requests.hasPicture
        withContext(Dispatchers.Main.immediate) { web.loadUrl(IntroWeb.url(look, intro, reducedMotion, given)) }
        if (withTimeoutOrNull(IntroWeb.FIRST_FRAME_MS) { snapshotFlow { run.visible }.first { it } } == null) {
            broke()
            return@LaunchedEffect
        }
        val own = look.ownMs(intro, reducedMotion)
        launch { delay((own - IntroWeb.EXIT_LEAD_MS).coerceAtLeast(0L)); uncover() }
        delay(IntroWeb.ceilingMs(look, intro, reducedMotion))
        end()
    }
    BackHandler(enabled = intro.skippable) { end() }

    val ground = Color(look.ground)
    Box(
        Modifier
            .fillMaxSize()
            .testTag(INTRO_TAG)
            .semantics { if (intro.skippable) onClick(skipLabel) { end(); true } }
            .drawBehind { if (run.covering) drawRect(ground) },
    ) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().testTag(WEB_INTRO_TAG))
    }
}

const val WEB_INTRO_TAG = "startup-intro-web"
