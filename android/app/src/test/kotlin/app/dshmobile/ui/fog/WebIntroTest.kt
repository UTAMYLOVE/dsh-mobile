package app.dshmobile.ui.fog

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.ui.OpenSourceLicences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboWebSettings
import java.io.File

/**
 * The dsh community looks (櫻花動漫風, 粉色霓虹敘事, 光點聚形波浪, 科技檔案授權) in their locked-down WebView: what the page
 * is told, what it may load (its own folder and the user's picture, nothing else), the one thing it can call (DSHM.done), how a
 * run ends and how a page that cannot show gives way to 櫻紋陣. A WebView does not render under Robolectric, so the page's own
 * side is driven by hand here; the emulator test (IntroWebLooksTest) plays the real pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebIntroTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val base = "https://appassets.androidplatform.net/assets/intro-web/"

    // ---------- the styles ----------

    @Test fun theCommunityLooksComeAfterTheDrawnFiveWithTheirOwnIds() {
        val ids = IntroStyle.entries.map { it.id }
        assertEquals(listOf("sigil", "stardust", "petal-storm", "tunnel", "one-stroke", "web-sakura", "web-neon", "web-wave", "web-access"), ids)
        assertEquals(listOf(null, null, null, null, null, WebLook.SAKURA, WebLook.NEON, WebLook.WAVE, WebLook.ACCESS), IntroStyle.entries.map { it.web })
        assertEquals(listOf("sakura", "neon", "wave", "access"), WebLook.entries.map { it.key })
        for (style in IntroStyle.entries) assertEquals(style, IntroStyle.of(style.id))
        // An id this app does not know (an older or newer app's look) reads as the default.
        assertEquals(IntroStyle.SIGIL, IntroStyle.of("web-unknown"))
    }

    @Test fun aCommunityLookIsSavedAndReadBack() {
        val prefs = app.dshmobile.gateway.InMemorySharedPreferences()
        for (style in listOf(IntroStyle.WEB_SAKURA, IntroStyle.WEB_NEON, IntroStyle.WEB_WAVE, IntroStyle.WEB_ACCESS)) {
            LookStore(prefs).save(Look(intro = Intro(enabled = true, style = style)))
            assertEquals(style, LookStore(prefs).load().intro.style)
        }
    }

    // ---------- what the page is told ----------

    @Test fun theUrlCarriesTheUsersWordsAndChoices() {
        val intro = Intro(name = " 小雨 & co ", welcome = "早安", number = "No.7", level = "", seconds = 2.5f, skippable = false)
        val url = Uri.parse(IntroWeb.url(WebLook.NEON, intro, reduced = true, picture = true))
        assertEquals("https", url.scheme)
        assertEquals("appassets.androidplatform.net", url.authority)
        assertEquals("/assets/intro-web/neon/index.html", url.path)
        assertEquals(listOf("name", "welcome", "number", "level", "seconds", "reduced", "skippable", "picture", "lang"), url.queryParameterNames.toList())
        assertEquals("小雨 & co", url.getQueryParameter("name"))
        assertEquals("早安", url.getQueryParameter("welcome"))
        assertEquals("No.7", url.getQueryParameter("number"))
        assertEquals("", url.getQueryParameter("level"))
        assertEquals("2.5", url.getQueryParameter("seconds"))
        assertEquals("1", url.getQueryParameter("reduced"))
        assertEquals("0", url.getQueryParameter("skippable"))
        assertEquals("1", url.getQueryParameter("picture"))
        assertEquals("zh-TW", url.getQueryParameter("lang"))
        assertNull(url.fragment)
    }

    @Test fun theUrlKeepsTheDefaultsAndTheSecondsInRange() {
        val d = Uri.parse(IntroWeb.url(WebLook.SAKURA, Intro(), reduced = false, picture = false))
        assertEquals("/assets/intro-web/sakura/index.html", d.path)
        assertEquals("3.0", d.getQueryParameter("seconds"))
        assertEquals("0", d.getQueryParameter("reduced"))
        assertEquals("1", d.getQueryParameter("skippable"))
        assertEquals("0", d.getQueryParameter("picture"))
        assertEquals("", d.getQueryParameter("name"))
        assertEquals("8.0", Uri.parse(IntroWeb.url(WebLook.WAVE, Intro(seconds = 99f), false, false)).getQueryParameter("seconds"))
        assertEquals("1.5", Uri.parse(IntroWeb.url(WebLook.WAVE, Intro(seconds = 0f), false, false)).getQueryParameter("seconds"))
        assertEquals("3.0", Uri.parse(IntroWeb.url(WebLook.WAVE, Intro(seconds = Float.NaN), false, false)).getQueryParameter("seconds"))
        val access = Uri.parse(IntroWeb.url(WebLook.ACCESS, Intro(name = "小雨", seconds = 6.5f), reduced = false, picture = true))
        assertEquals("/assets/intro-web/access/index.html", access.path)
        assertEquals(listOf("name", "welcome", "number", "level", "seconds", "reduced", "skippable", "picture", "lang"), access.queryParameterNames.toList())
        assertEquals("小雨", access.getQueryParameter("name"))
        assertEquals("6.5", access.getQueryParameter("seconds"))
        assertEquals("1", access.getQueryParameter("picture"))
        assertNull(access.getQueryParameter("interact")) // the page's own tap-to-go-on steps stay off
        // Hostile words travel as data: encoded in the query, never as markup or another parameter.
        val odd = Uri.parse(IntroWeb.url(WebLook.SAKURA, Intro(name = "<img src=x>&picture=0#done"), false, true))
        assertEquals("<img src=x>&picture=0#done", odd.getQueryParameter("name"))
        assertEquals("1", odd.getQueryParameter("picture"))
        assertNull(odd.fragment)
    }

    @Test fun eachLookHasItsOwnLengthAndTheCeilingIsThreeSecondsMoreAtMostAMinute() {
        fun own(l: WebLook, s: Float, reduced: Boolean = false) = l.ownMs(Intro(seconds = s), reduced)
        assertEquals(3_000L, own(WebLook.SAKURA, 3f))
        assertEquals(2_600L, own(WebLook.SAKURA, 1.5f)) // a long welcome may stretch the short setting
        assertEquals(8_000L, own(WebLook.SAKURA, 8f))
        assertEquals(4_200L, own(WebLook.NEON, 3f)) // its own floor is about 4 s
        assertEquals(8_200L, own(WebLook.NEON, 8f))
        assertEquals(3_600L, own(WebLook.WAVE, 3f))
        assertEquals(6_234L, own(WebLook.ACCESS, 1.5f)) // its own speed tops out at 3×: 18.7 s / 3
        assertEquals(6_234L, own(WebLook.ACCESS, 3f))
        assertEquals(6_500L, own(WebLook.ACCESS, 6.5f))
        assertEquals(8_000L, own(WebLook.ACCESS, 8f))
        assertEquals(2_000L, own(WebLook.SAKURA, 8f, reduced = true))
        assertEquals(2_200L, own(WebLook.NEON, 8f, reduced = true))
        assertEquals(1_700L, own(WebLook.WAVE, 8f, reduced = true))
        assertEquals(2_200L, own(WebLook.ACCESS, 8f, reduced = true)) // 1.5 s still, done 0.7 s into its fade
        assertEquals(6_000L, IntroWeb.ceilingMs(WebLook.SAKURA, Intro(seconds = 3f), false))
        for (look in WebLook.entries) for (s in listOf(1.5f, 3f, 8f, 99f)) for (r in listOf(false, true)) {
            val c = IntroWeb.ceilingMs(look, Intro(seconds = s), r)
            assertEquals(minOf(look.ownMs(Intro(seconds = s), r) + 3_000L, 60_000L), c)
            assertTrue(c <= IntroWeb.MAX_MS)
        }
    }

    // ---------- what the page may load ----------

    private fun requests(picture: File? = null) = IntroWebRequests(context, picture)
    private fun body(r: WebResourceResponse) = r.data?.use { it.readBytes() } ?: ByteArray(0)
    private fun assertBlocked(r: WebResourceResponse, what: String) {
        assertEquals("$what is blocked", 403, r.statusCode)
        assertEquals("$what has an empty body", 0, body(r).size)
    }

    @Test fun eachLooksOwnFilesAreServedWithThePolicy() {
        val req = requests()
        for (look in WebLook.entries) {
            val r = req.respond(Uri.parse("$base${look.key}/index.html?name=x#done"))
            assertEquals(200, r.statusCode)
            assertEquals("text/html", r.mimeType)
            assertEquals("utf-8", r.encoding)
            assertEquals(IntroWeb.CSP, r.responseHeaders["Content-Security-Policy"])
            assertEquals("nosniff", r.responseHeaders["X-Content-Type-Options"])
            val html = String(body(r), Charsets.UTF_8)
            assertTrue(html.startsWith("<!doctype html>"))
            // The page carries the same policy in its own <meta> (the bundle contract).
            assertTrue("${look.key}'s page has the contract's policy", html.contains("content=\"${IntroWeb.CSP}\""))
        }
        assertEquals("text/css", req.respond(Uri.parse("${base}sakura/boot.css")).mimeType)
        assertEquals("text/javascript", req.respond(Uri.parse("${base}neon/neon.js")).mimeType)
        assertEquals(200, req.respond(Uri.parse("${base}wave/wave.js")).statusCode)
        assertEquals("text/css", req.respond(Uri.parse("${base}access/splash.css")).mimeType)
        assertEquals("text/javascript", req.respond(Uri.parse("${base}access/splash.js")).mimeType)
        assertEquals(200, req.respond(Uri.parse("${base}access/splash.js")).statusCode)
    }

    @Test fun everythingElseIsBlocked() {
        val req = requests(tmp.newFile("picture.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)) })
        listOf(
            "https://appassets.androidplatform.net/assets/licenses/fonts-OFL.txt",
            "https://appassets.androidplatform.net/assets/intro-web.html",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/../../licenses/fonts-OFL.txt",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/%2e%2e/neon/index.html",
            "https://appassets.androidplatform.net/assets/intro-web/%2e%2e/licenses",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/sub/index.html",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/x/picture.img",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/NOTICE.md",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/LICENSE",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/.hidden.js",
            "https://appassets.androidplatform.net/assets/intro-web/sakura/missing.js",
            "https://appassets.androidplatform.net/assets/intro-web/access/NOTICE.md",
            "https://appassets.androidplatform.net/assets/intro-web/access/LICENSE",
            "https://appassets.androidplatform.net/assets/intro-web/access/%2e%2e/sakura/boot.js",
            "https://appassets.androidplatform.net/assets/intro-web/access/splash.mjs",
            "https://appassets.androidplatform.net/assets/intro-web/access/sfx-tick.mp3",
            "https://appassets.androidplatform.net/assets/intro-web/accessx/index.html",
            "https://appassets.androidplatform.net/res/drawable/x.png",
            "https://appassets.androidplatform.net/",
            "http://appassets.androidplatform.net/assets/intro-web/sakura/index.html",
            "https://example.com/assets/intro-web/sakura/index.html",
            "https://cdn.jsdelivr.net/npm/x@1.0.0/x.js",
            "https://fonts.googleapis.com/css2?family=Noto",
            "wss://appassets.androidplatform.net/assets/intro-web/sakura/index.html",
            "file:///android_asset/intro-web/sakura/index.html",
            "content://app.dshmobile.files/picture.jpg",
            "data:text/html,hi",
            "javascript:alert(1)",
            "about:blank",
            "https:no-host",
        ).forEach { assertBlocked(req.respond(Uri.parse(it)), it) }
    }

    @Test fun thePictureIsTheUsersOwnFileAndOnlyWhenThereIsOne() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 8, 7, 6, 5, 4, 3, 2, 1)
        val file = tmp.newFile("picture.jpg").apply { writeBytes(jpeg) }
        for (look in WebLook.entries) {
            val r = requests(file).respond(Uri.parse("$base${look.key}/picture.img"))
            assertEquals(200, r.statusCode)
            assertEquals("image/jpeg", r.mimeType)
            assertEquals(jpeg.toList(), body(r).toList())
        }
        // A cut-out picture is kept as PNG under the same name.
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)
        file.writeBytes(png)
        assertEquals("image/png", requests(file).respond(Uri.parse("${base}wave/picture.img")).mimeType)
        assertBlocked(requests(null).respond(Uri.parse("${base}sakura/picture.img")), "no picture")
        assertBlocked(requests(File(tmp.root, "gone.jpg")).respond(Uri.parse("${base}sakura/picture.img")), "a removed picture")
        file.writeBytes(ByteArray(0))
        assertBlocked(requests(file).respond(Uri.parse("${base}sakura/picture.img")), "an empty picture")
    }

    @Test fun theClientSendsEveryRequestThroughTheFilterAndFollowsNoNavigation() {
        val events = Events()
        val client = IntroWebClient(requests(), events)
        val web = WebView(context)
        assertEquals(200, client.shouldInterceptRequest(web, request("${base}sakura/index.html")).statusCode)
        assertBlocked(client.shouldInterceptRequest(web, request("https://example.com/beacon")), "a beacon")
        assertBlocked(client.shouldInterceptRequest(web, null as WebResourceRequest?), "a request without a URL")
        assertTrue(client.shouldOverrideUrlLoading(web, request("https://example.com/")))
        assertTrue(client.shouldOverrideUrlLoading(web, request("${base}neon/index.html")))
        assertTrue(client.shouldOverrideUrlLoading(web, request("intent://scan/#Intent;scheme=zxing;end")))
        client.doUpdateVisitedHistory(web, "${base}sakura/index.html?x=1", false)
        assertEquals(0, events.done)
        client.doUpdateVisitedHistory(web, "${base}sakura/index.html?x=1#done", false)
        assertEquals(1, events.done)
        // A sub-resource that fails (a missing picture) is the page's business; only the page itself failing counts.
        client.onReceivedHttpError(web, request("${base}sakura/picture.img", main = false), IntroWebRequests.blocked())
        assertEquals(0, events.failed)
        client.onReceivedHttpError(web, request("${base}sakura/index.html"), IntroWebRequests.blocked())
        assertEquals(1, events.failed)
        assertTrue(client.onRenderProcessGone(web, null))
        assertEquals(2, events.failed)
        assertTrue(IntroWeb.isDone("${base}x/index.html#done"))
        assertFalse(IntroWeb.isDone("${base}x/index.html#doner"))
        assertFalse(IntroWeb.isDone(null))
    }

    // ---------- the WebView itself ----------

    @Test fun theWebViewIsLockedDownWithOneBridge() {
        val web = IntroWebView.create(context, requests(), IntroWebBridge {}, Events())
        val s = web.settings
        assertTrue(s.javaScriptEnabled)
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
        assertFalse(s.supportMultipleWindows())
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
        assertFalse(s.domStorageEnabled)
        @Suppress("DEPRECATION") assertFalse(s.databaseEnabled)
        @Suppress("DEPRECATION") assertFalse(s.saveFormData)
        assertFalse((s as RoboWebSettings).geolocationEnabled)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
        assertEquals(WebSettings.LOAD_NO_CACHE, s.cacheMode)
        // safeBrowsingEnabled is set too; Robolectric's settings do not keep it, so it is not read back here.
        assertTrue(s.mediaPlaybackRequiresUserGesture)
        assertFalse(s.supportZoom())
        assertEquals(100, s.textZoom)
        val shadow = shadowOf(web)
        assertTrue(shadow.webViewClient is IntroWebClient)
        assertTrue(shadow.webChromeClient is IntroWebChrome)
        assertTrue(shadow.getJavascriptInterface(IntroWeb.BRIDGE) is IntroWebBridge)
        assertEquals(0, shadow.backgroundColor)
        IntroWebView.release(web)
        IntroWebView.release(web) // twice is fine
        assertNull(shadow.getJavascriptInterface(IntroWeb.BRIDGE))
        // Until it is destroyed the page may still run a moment: it stays locked (a plain client would let its requests reach
        // the network, and its false from onRenderProcessGone would kill the app).
        val closed = shadow.webViewClient
        assertTrue(closed is ClosedIntroWebClient)
        assertBlocked(closed.shouldInterceptRequest(web, request("${base}sakura/index.html"))!!, "a request after the run")
        assertBlocked(closed.shouldInterceptRequest(web, request("https://example.com/beacon"))!!, "a beacon after the run")
        assertTrue(closed.shouldOverrideUrlLoading(web, request("https://example.com/")))
        assertTrue(closed.onRenderProcessGone(web, null))
        assertTrue(shadow.webChromeClient is IntroWebChrome)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadow.wasDestroyCalled())
    }

    @Test fun thePageCanCallDoneAndNothingElse() {
        val methods = IntroWebBridge::class.java.methods
        val exposed = methods.filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
        assertEquals(listOf("done"), exposed.map { it.name })
        assertEquals(Void.TYPE, exposed.single().returnType)
        assertEquals(0, exposed.single().parameterCount)
        // No other public method either (should a WebView ever expose unannotated ones).
        assertEquals(listOf("done"), methods.filter { it.declaringClass != Any::class.java }.map { it.name })
        var called = 0
        val bridge = IntroWebBridge { called++ }
        bridge.done()
        bridge.done()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, called)
    }

    @Test fun aPageGetsNoPermissionsOrDialogs() {
        val chrome = IntroWebChrome()
        var denied = false
        chrome.onPermissionRequest(object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse(base)
            override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)
            override fun grant(resources: Array<out String>?) = throw AssertionError("granted")
            override fun deny() { denied = true }
        })
        assertTrue(denied)
        var geo: Boolean? = null
        chrome.onGeolocationPermissionsShowPrompt("https://x") { _, allow, _ -> geo = allow }
        assertEquals(false, geo)
        assertFalse(chrome.onCreateWindow(null, false, true, null))
    }

    // ---------- a run ----------

    private var done = 0
    private val opaque = mutableListOf<Boolean>()
    private var start = 0L

    private fun play(intro: Intro, look: Look = Look(intro = intro), picture: File? = null, reduced: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            var showing by androidx.compose.runtime.remember { mutableStateOf(true) }
            FogTheme(effectsFor(EffectLevel.FULL, false)) {
                CompositionLocalProvider(LocalLook provides look, LocalLookPicture provides picture) {
                    if (showing) StartupIntro(intro, null, reduced, onOpaqueChange = { opaque += it }) { done++; showing = false }
                }
            }
        }
        start = rule.mainClock.currentTime
        at(50)
    }

    private fun at(ms: Long) {
        val now = rule.mainClock.currentTime - start
        if (ms > now) rule.mainClock.advanceTimeBy(ms - now)
        rule.waitForIdle()
    }

    private fun webViews(): List<WebView> {
        val found = mutableListOf<WebView>()
        fun walk(v: View) {
            if (v is WebView) found += v
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        rule.runOnUiThread { walk(rule.activity.window.decorView) }
        return found
    }

    private fun web(): WebView = webViews().single()
    private fun client(web: WebView) = shadowOf(web).webViewClient
    private fun showFirstFrame(web: WebView) = ui { client(web).onPageCommitVisible(web, shadowOf(web).lastLoadedUrl) }

    /** Runs [block] as the WebView would (on the main thread) and lets Compose hear of what it changed. */
    private fun ui(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    @Test fun aCommunityLookLoadsItsPageAndEndsWhenThePageSaysSo() {
        play(Intro(enabled = true, name = "小雨", seconds = 3f, style = IntroStyle.WEB_SAKURA))
        rule.onNodeWithTag(WEB_INTRO_TAG).assertExists()
        rule.onNodeWithTag(INTRO_TAG).assertExists()
        val web = web()
        val url = Uri.parse(shadowOf(web).lastLoadedUrl)
        assertEquals("/assets/intro-web/sakura/index.html", url.path)
        assertEquals("小雨", url.getQueryParameter("name"))
        assertEquals("0", url.getQueryParameter("picture")) // no picture chosen
        assertEquals(listOf(true), opaque)
        showFirstFrame(web)
        at(1_000)
        assertEquals(0, done)
        ui { (shadowOf(web).getJavascriptInterface(IntroWeb.BRIDGE) as IntroWebBridge).done() }
        assertEquals(1, done)
        assertEquals(false, opaque.last())
        // The WebView is let go of: out of the screen and destroyed.
        at(1_100)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(web).wasDestroyCalled())
        assertTrue(webViews().isEmpty())
    }

    @Test fun theHashIsTheFallbackSignal() {
        play(Intro(enabled = true, seconds = 3f, style = IntroStyle.WEB_WAVE))
        val web = web()
        showFirstFrame(web)
        at(500)
        ui { client(web).doUpdateVisitedHistory(web, shadowOf(web).lastLoadedUrl + "#done", false) }
        assertEquals(1, done)
    }

    @Test fun theUsersOwnPictureGoesToThePageWhenTheWelcomeMayUseIt() {
        val file = tmp.newFile("picture.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4, 5)) }
        val intro = Intro(enabled = true, seconds = 3f, style = IntroStyle.WEB_NEON)
        play(intro, Look(picture = 42L, intro = intro), picture = file)
        val web = web()
        assertEquals("1", Uri.parse(shadowOf(web).lastLoadedUrl).getQueryParameter("picture"))
        val r = client(web).shouldInterceptRequest(web, request("${base}neon/picture.img"))
        assertEquals(200, r!!.statusCode)
        assertEquals(file.readBytes().toList(), body(r).toList())
    }

    @Test fun noPictureGoesToThePageWhenTheWelcomeMayNotUseIt() {
        val file = tmp.newFile("picture.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)) }
        val intro = Intro(enabled = true, seconds = 3f, style = IntroStyle.WEB_NEON, portrait = false)
        play(intro, Look(picture = 42L, intro = intro), picture = file)
        val web = web()
        assertEquals("0", Uri.parse(shadowOf(web).lastLoadedUrl).getQueryParameter("picture"))
        assertBlocked(client(web).shouldInterceptRequest(web, request("${base}neon/picture.img"))!!, "the picture with the portrait off")
    }

    @Test fun theTechArchiveIsGivenItsOwnSixSecondsEvenWhenTheSettingIsShorter() {
        play(Intro(enabled = true, seconds = 3f, style = IntroStyle.WEB_ACCESS))
        val web = web()
        assertEquals("/assets/intro-web/access/index.html", Uri.parse(shadowOf(web).lastLoadedUrl).path)
        showFirstFrame(web)
        at(100)
        val visibleAt = rule.mainClock.currentTime - start
        // Its own floor is 6.234 s: the screen underneath comes back 1.2 s before that, the ceiling is 3 s after it.
        at(visibleAt + 4_900)
        assertEquals(listOf(true), opaque)
        at(visibleAt + 5_150)
        assertEquals(listOf(true, false), opaque)
        at(visibleAt + 9_100)
        assertEquals(0, done)
        at(visibleAt + 9_350)
        assertEquals(1, done)
    }

    @Test fun aPageThatNeverEndsIsEndedAtItsCeiling() {
        play(Intro(enabled = true, seconds = 3f, style = IntroStyle.WEB_SAKURA))
        val web = web()
        showFirstFrame(web)
        at(100)
        val visibleAt = rule.mainClock.currentTime - start
        // The screen underneath comes back 1.2 s before the page's own 3 s end, as its exit turns see-through.
        at(visibleAt + 1_700)
        assertEquals(listOf(true), opaque)
        at(visibleAt + 1_900)
        assertEquals(listOf(true, false), opaque)
        at(visibleAt + 5_900)
        assertEquals(0, done)
        at(visibleAt + 6_100)
        assertEquals(1, done)
    }

    @Test fun backEndsItWhenSkippableAndNotOtherwise() {
        play(Intro(enabled = true, seconds = 8f, style = IntroStyle.WEB_NEON))
        showFirstFrame(web())
        at(500)
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, done)
    }

    @Test fun backDoesNothingWhenNotSkippable() {
        play(Intro(enabled = true, seconds = 8f, style = IntroStyle.WEB_NEON, skippable = false))
        showFirstFrame(web())
        at(500)
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        at(600)
        assertEquals(0, done)
    }

    @Test fun aPageThatDoesNotShowGivesWayToTheSigil() {
        play(Intro(enabled = true, name = "小雨", seconds = 4f, style = IntroStyle.WEB_NEON))
        val web = web()
        at(3_900)
        rule.onNodeWithTag(WEB_INTRO_TAG).assertExists()
        at(4_100)
        // No first frame within 4 s: 櫻紋陣 plays instead, from its start, and the WebView is gone.
        rule.onNodeWithTag(WEB_INTRO_TAG).assertDoesNotExist()
        rule.onNodeWithTag(INTRO_TAG).assertExists()
        at(4_300)
        rule.onNodeWithText("需要訪問許可").assertExists()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(web).wasDestroyCalled())
        assertEquals(0, done)
        at(4_100 + 4_300)
        assertEquals(1, done)
    }

    @Test fun aPageThatFailsToLoadGivesWayToTheSigilAtOnce() {
        play(Intro(enabled = true, seconds = 3f, style = IntroStyle.WEB_WAVE))
        val web = web()
        ui { client(web).onReceivedHttpError(web, request(shadowOf(web).lastLoadedUrl), IntroWebRequests.blocked()) }
        at(200)
        rule.onNodeWithTag(WEB_INTRO_TAG).assertDoesNotExist()
        rule.onNodeWithText("需要訪問許可").assertExists()
        assertEquals(0, done)
    }

    @Test fun aPageThatBreaksAfterItShowedSimplyEnds() {
        play(Intro(enabled = true, seconds = 3f, style = IntroStyle.WEB_SAKURA))
        val web = web()
        showFirstFrame(web)
        at(800)
        ui { client(web).onRenderProcessGone(web, null) }
        assertEquals(1, done)
        at(900)
        rule.onNodeWithText("需要訪問許可").assertDoesNotExist()
    }

    @Test fun reducedMotionAsksThePageForItsStillAndEndsSoon() {
        play(Intro(enabled = true, seconds = 8f, style = IntroStyle.WEB_SAKURA), reduced = true)
        val web = web()
        assertEquals("1", Uri.parse(shadowOf(web).lastLoadedUrl).getQueryParameter("reduced"))
        showFirstFrame(web)
        at(100 + 2_000 + 3_000 + 100)
        assertEquals(1, done)
    }

    // ---------- the shipped pages ----------

    @Test fun eachShippedLookHasItsLicenceNoticeAndNoOutsideAddress() {
        for (look in WebLook.entries) {
            val dir = "intro-web/${look.key}"
            val files = context.assets.list(dir)!!.toSet()
            assertTrue("$dir: $files", files.containsAll(listOf("index.html", "LICENSE", "NOTICE.md")))
            assertTrue(read("$dir/LICENSE").startsWith("MIT License"))
            val credit = OpenSourceLicences.portedLooks.single { it.key == look.key }
            assertTrue("$dir/LICENSE keeps ${credit.copyright}", read("$dir/LICENSE").contains(credit.copyright))
            assertTrue("$dir/NOTICE.md names commit ${credit.commit}", read("$dir/NOTICE.md").contains(credit.commit))
            for (f in files.filter { it.endsWith(".html") || it.endsWith(".js") || it.endsWith(".css") }) {
                val text = withoutNames(read("$dir/$f"))
                for (bad in listOf(
                    "http:", "https:", "//cdn", "@import", "@font-face", "fetch(", "XMLHttpRequest", "WebSocket", "EventSource", "sendBeacon",
                    "WebTransport", "RTCPeerConnection", "importScripts", "import(", "Worker(", "serviceWorker", "postMessage", "window.open",
                    "eval(", "new Function", "setTimeout('", "setTimeout(\"", "localStorage", "sessionStorage.", "indexedDB", "openDatabase",
                    "caches.", "document.cookie", "navigator.", "AudioContext", "speechSynthesis", "new Audio", "<audio", "<video", "<iframe",
                    "<object", "<embed", "<base", "<form",
                )) {
                    assertFalse("$dir/$f has $bad", text.contains(bad))
                }
                // No protocol-relative address either (`src="//host/x.js"`, `url(//host/x.png)`).
                assertFalse("$dir/$f has a //host address", Regex("""(src|href|action)\s*=\s*["'`]?\s*//|url\(\s*["']?\s*//""").containsMatchIn(text))
            }
        }
        // Every shipped look is credited, and every credited one is shipped.
        assertEquals(WebLook.entries.map { it.key }, OpenSourceLicences.portedLooks.map { it.key })
        assertEquals(WebLook.entries.map { it.key }.toSet(), assetFiles("intro-web").map { it.substringBefore('/') }.toSet())
        assertNotNull(OpenSourceLicences.portedLooks.singleOrNull { it.key == "access" })
    }

    @Test fun onlyNamesThatAreNeverFetchedAreLetThroughTheScan() {
        // 科技檔案授權 names the SVG namespace (createElementNS, its grain picture's xmlns) and credits its source in a header.
        val js = read("intro-web/access/splash.js")
        assertTrue(js.contains("'http://www.w3.org/2000/svg'"))
        assertFalse(withoutNames(js).contains("http:"))
        assertFalse(withoutNames(js).contains("https:"))
        assertFalse(withoutNames(read("intro-web/access/splash.css")).contains("http"))
        // The same words anywhere else still fail it.
        for (bad in listOf(
            "<script src=\"http://www.w3.org/2000/svg.js\"></script>",
            "img.src = 'http://www.w3.org/2000/svg/x.png'",
            "img.src = 'http://www.w3.org/2000/svg'",
            "<img src=\"http://www.w3.org/2000/svg\">",
            "var u = 'https://github.com/x/y'",
            "// Source: https://github.com/x/y",
            "/* a */\n * Source: https://github.com/x/y\n",
            " * Source: https://github.com/x/y/raw/main/z.js\n",
        )) {
            assertTrue(bad, withoutNames(bad).contains("http"))
        }
    }

    /**
     * [text] without the addresses a page carries only as names, never fetched: the SVG namespace as an `xmlns` value (a `data:`
     * picture's) or a variable's value (for `createElementNS`), and the author's repository on a block comment's `Source:` credit
     * line. Every other `http:` or `https:` is left for the scan to fail.
     */
    private fun withoutNames(text: String): String {
        val svg = text.replace(Regex("""(xmlns=|(?:var|let|const)\s+\w+\s*=\s*)(['"])http://www\.w3\.org/2000/svg\2"""), "$1$2$2")
        return Regex("""(?m)^ \* Source: https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""").replace(svg) { m ->
            val at = m.range.first
            if (svg.lastIndexOf("/*", at) > svg.lastIndexOf("*/", at)) "" else m.value
        }
    }

    /**
     * The pages are exactly the ones that passed their security review (each NOTICE.md names the source commit and every
     * change): every file is pinned by its SHA-256 and nothing else is in the folders, so no script, picture, font or sound can
     * reach the app unreviewed. Changing a page means reviewing it again and updating its hash here with that review.
     */
    @Test fun theShippedPagesAreExactlyTheReviewedOnes() {
        val reviewed = mapOf(
            "neon/LICENSE" to "b77ef850de9e6c148d68d62e84dc0198d5208ca065d2af7ccb5bf05f9a59a829",
            "neon/NOTICE.md" to "8318358e38b811d96233a5434b30046ae189b90b5ba6bbb0ee8867f639ec403a",
            "neon/index.html" to "18013d9e6d922d3f23648bd1ac32992d5f2860573e66d02d5605972cd0b056f7",
            "neon/neon.css" to "a4e0112819ecdeb671afadf31acbe76f22fee53a1b47798cd48ed7e33db14ead",
            "neon/neon.js" to "a66284ce14c3ea341850c7548fa61db46ce475f66f19dc783b09941facc209c4",
            "sakura/LICENSE" to "9df9e9dee7f674175ee6779a546577e34dc8a807ff51df15688f8cd936d075eb",
            "sakura/NOTICE.md" to "29f428047fa851dbaa2c1b94cfad6412c91eb47537d58da38aaf2e79b7ce2e9e",
            "sakura/boot.css" to "4ff12472f84e8b540d9b1715de3f9d818624d06861b06d25b6d12e289324d6eb",
            "sakura/boot.js" to "1e5f9a07c50f1e628b1a91cd78eeb53487131734026c514768ca33ce7ebdb885",
            "sakura/index.html" to "a4c8d82c75a1e8949a090c0cb42193647c5bb33440f88571b3955629c4b7e570",
            "wave/LICENSE" to "bd4901eabfc18ad33b500c643150a930860076868da232efd36fefe8c0e6fdef",
            "wave/NOTICE.md" to "90930f0145c5e8de4db1ce428a36203f257cb13153807b57906fbe314a380e76",
            "wave/index.html" to "92e5ae25e7020873aeb76ad58fc3a3d74f30a4f155879ce0a3368c1f5bc33098",
            "wave/wave.css" to "1857084a595c91af8e5da84360d1eb63ddb5be2d94f02bd5bb4cd69c0481bbd4",
            "wave/wave.js" to "bd1b6e04d6bfadadbf0b977a77f87af375d659463c7ca26d23bc63ada72a88ae",
            "access/LICENSE" to "9bbdfb8e1b847a8a9f614b95110fff653733ecd1fe86d04ac8836788f71ec736",
            "access/NOTICE.md" to "2143bbcda7ae16d3dfa418ab7366d0a8b9ccebec03d659af9cf3cd50989054d9",
            "access/index.html" to "94a6270220f76eade2535aa802d162965559d493db03e1d82e0c8e11c7cdb09b",
            "access/splash.css" to "adf87d59d5c31bddceaa6215e2fefd9642bf619b4064dbaf4c4deffe0ad80682",
            "access/splash.js" to "b012ad8a9861b6b2042cebd18e2be377fb3e7ed744885ebf91f298d105729bd4",
        )
        assertEquals(reviewed.keys, assetFiles("intro-web"))
        for ((path, sha) in reviewed) assertEquals("intro-web/$path is the reviewed file", sha, sha256("intro-web/$path"))
    }

    /**
     * Every file under assets/[dir], as a path relative to it. Walked one level at a time, as a phone lists a folder (Robolectric
     * already gives the whole tree for the top one).
     */
    private fun assetFiles(dir: String): Set<String> {
        val out = sortedSetOf<String>()
        fun walk(rel: String) {
            val entries = context.assets.list(if (rel.isEmpty()) dir else "$dir/$rel").orEmpty()
            if (entries.isEmpty()) out += rel else entries.forEach { walk(if (rel.isEmpty()) it else "$rel/$it") }
        }
        walk("")
        return out
    }

    /** The file's SHA-256, its line ends read as LF (a checkout that writes CRLF is the same reviewed text). */
    private fun sha256(path: String): String {
        val raw = context.assets.open(path).use { it.readBytes() }
        val lf = String(raw, Charsets.ISO_8859_1).replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1)
        return java.security.MessageDigest.getInstance("SHA-256").digest(lf).joinToString("") { "%02x".format(it) }
    }

    private fun read(path: String) = context.assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) }

    private class Events : IntroWebEvents {
        var visible = 0
        var done = 0
        var failed = 0
        var touched = 0
        override fun visible() { visible++ }
        override fun done() { done++ }
        override fun failed() { failed++ }
        override fun touched() { touched++ }
    }

    private fun request(url: String, main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
