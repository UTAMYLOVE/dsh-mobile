package app.dshmobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.dshmobile.ui.fog.EffectLevel
import app.dshmobile.ui.fog.FogTheme
import app.dshmobile.ui.fog.Intro
import app.dshmobile.ui.fog.IntroStyle
import app.dshmobile.ui.fog.LocalLook
import app.dshmobile.ui.fog.LocalLookPicture
import app.dshmobile.ui.fog.Look
import app.dshmobile.ui.fog.StartupIntro
import app.dshmobile.ui.fog.WebLook
import app.dshmobile.ui.fog.effectsFor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The dsh community startup looks (櫻花動漫風, 粉色霓虹敘事, 光點聚形波浪, 科技檔案授權) on a real Android WebView, which
 * Robolectric cannot render (WebIntroTest covers the rest there). Each look plays with a test picture in its locked-down
 * WebView and UiAutomation takes screenshots into the app's external files dir, intro-shots/: upright three, about 1.0 s,
 * 3.0 s and 5.5 s after the page loaded (<key>-portrait-1/-2/-3.png), and on its side one at about 3 s (<key>-landscape.png).
 * Then a tap (or back) must end the overlay early and leave no WebView behind. The screenshots are also copied to
 * /data/local/tmp/intro-shots, which outlives the app's uninstall after the run, so CI can pull them for the owner.
 *
 * The activity itself asks for the orientation (`requestedOrientation`): UiDevice's rotation did not turn the CI emulator (API 34,
 * no window), and its "landscape" shots came out 320x640 upright. The run waits until the activity's window has the asked shape,
 * and every saved shot must have it too.
 *
 * Needs no dsh: the looks play in a bare activity, the way the shell hosts them, with the same StartupIntro.
 */
@RunWith(AndroidJUnit4::class)
class IntroWebLooksTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    @After fun upright() {
        runCatching { device.setOrientationNatural() }
        runCatching { device.unfreezeRotation() }
    }

    @Test fun sakuraPlaysAndATapEndsIt() = play(IntroStyle.WEB_SAKURA, WebLook.SAKURA, "櫻花動漫風", landscape = false)
    @Test fun neonPlaysAndATapEndsIt() = play(IntroStyle.WEB_NEON, WebLook.NEON, "粉色霓虹敘事", landscape = false)
    @Test fun wavePlaysAndATapEndsIt() = play(IntroStyle.WEB_WAVE, WebLook.WAVE, "光點聚形波浪", landscape = false)
    @Test fun accessPlaysAndATapEndsIt() = play(IntroStyle.WEB_ACCESS, WebLook.ACCESS, "科技檔案授權", landscape = false)
    @Test fun sakuraFitsLandscape() = play(IntroStyle.WEB_SAKURA, WebLook.SAKURA, "櫻花動漫風", landscape = true)
    @Test fun neonFitsLandscape() = play(IntroStyle.WEB_NEON, WebLook.NEON, "粉色霓虹敘事", landscape = true)
    @Test fun waveFitsLandscape() = play(IntroStyle.WEB_WAVE, WebLook.WAVE, "光點聚形波浪", landscape = true)
    @Test fun accessFitsLandscape() = play(IntroStyle.WEB_ACCESS, WebLook.ACCESS, "科技檔案授權", landscape = true)

    private fun play(style: IntroStyle, look: WebLook, title: String, landscape: Boolean) {
        // The longest run the app allows (a longer setting is held to this one, page and ceiling alike), so the look's own end
        // comes well after the last shot and the tap: an end soon after the tap can only be the tap's.
        val intro = Intro(enabled = true, name = "小雨", welcome = "歡迎回來", seconds = Intro.MAX_SECONDS, style = style)
        val runMs = (intro.seconds * 1000).toLong()
        val picture = testPicture()
        val done = CountDownLatch(1)
        val ended = AtomicLong(0L)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            turn(scenario, landscape)
            var activity: Activity? = null
            scenario.onActivity { a ->
                activity = a
                a.requestedOrientation = orientation(landscape)
                a.setContent {
                    var showing by remember { mutableStateOf(true) }
                    FogTheme(effectsFor(EffectLevel.FULL, false)) {
                        CompositionLocalProvider(LocalLook provides Look(picture = 1L, intro = intro), LocalLookPicture provides picture) {
                            if (showing) StartupIntro(intro, null, reducedMotion = false) {
                                ended.compareAndSet(0L, SystemClock.elapsedRealtime())
                                done.countDown()
                                showing = false
                            }
                        }
                    }
                }
            }
            val host = activity!!
            // The page came from the app's own assets through the loader: its <title> is the look's name. A page that failed
            // would have given way to 櫻紋陣, and its WebView would be gone. Polled closely, so the shots' times are the page's.
            val loaded = loadedAt(30_000, "the ${look.key} page to load in its WebView") {
                E2e.onMain { webView(host)?.let { it.progress >= 100 && it.title == title } == true }
            }
            val shapeOk = E2e.onMain { host.window.decorView.let { if (landscape) it.width > it.height else it.height > it.width } }
            assertTrue("${look.key}: the activity's window is not ${if (landscape) "wider than tall" else "taller than wide"}", shapeOk)

            val tapped: Long
            if (landscape) {
                sleepUntil(loaded + 3_000)
                save(capture("${look.key}-landscape.png"), "${look.key}-landscape.png", landscape)
                assertTrue("${look.key} still plays in its WebView (no fall back to 櫻紋陣)", E2e.onMain { webView(host) != null })
                // Back ends a skippable look at once.
                tapped = SystemClock.elapsedRealtime()
                device.pressBack()
            } else {
                for ((i, at) in listOf(1_000L, 3_000L).withIndex()) {
                    sleepUntil(loaded + at)
                    save(capture("${look.key}-portrait-${i + 1}.png"), "${look.key}-portrait-${i + 1}.png", landscape)
                }
                sleepUntil(loaded + 5_500)
                val last = "${look.key}-portrait-3.png"
                val bitmap = capture(last)
                assertTrue("${look.key} still plays in its WebView (no fall back to 櫻紋陣)", E2e.onMain { webView(host) != null })
                // The tap comes right after the last picture is taken (it is written out afterwards), well before the look's own
                // end. Near the top, away from 粉色霓虹敘事's login card (a tap on the card is its story's SIGN IN, not a skip).
                tapped = SystemClock.elapsedRealtime()
                device.click(device.displayWidth / 2, device.displayHeight / 7)
                save(bitmap, last, landscape)
            }
            val how = if (landscape) "back" else "tap"
            assertTrue("${look.key}: the overlay ends after the $how", done.await(5, TimeUnit.SECONDS))
            val took = ended.get() - loaded
            val sinceTap = ended.get() - tapped
            Log.i(FakeModel.TAG, "intro-shots: ${look.key} ${if (landscape) "landscape" else "portrait"}, $how ${tapped - loaded} ms and end $took ms after the page loaded")
            // Every look plays at least the setting from about its load, so its own end comes at about runMs; the slowest exit
            // after a tap is 櫻花動漫風's 0.7 s fade (1.3 s at most, when its transition end is missed). Each bound alone would
            // leave a little doubt; together they leave none.
            assertTrue("${look.key} ended $sinceTap ms after the $how, not soon after it", sinceTap < 1_500)
            assertTrue("${look.key} ended early, not at its own ${runMs / 1000} s end ($took ms after it loaded)", took < runMs - 400)
            E2e.waitFor(5_000, "the ${look.key} WebView to be let go of") { E2e.onMain { webView(host) == null } }
        } finally {
            scenario.close()
        }
    }

    private fun orientation(landscape: Boolean) =
        if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    /**
     * Asks the activity for the orientation and waits until its window has that shape (the turn recreates the activity, which is
     * why the content is set afterwards). UiDevice's rotation is only tried when the request alone has not turned it.
     */
    private fun turn(scenario: ActivityScenario<ComponentActivity>, landscape: Boolean) {
        val want = if (landscape) "landscape (its window wider than tall)" else "portrait (its window taller than wide)"
        fun shaped(): Boolean {
            var ok = false
            scenario.onActivity { a ->
                val v = a.window.decorView
                ok = if (landscape) v.width > v.height else v.height > v.width
            }
            return ok
        }
        scenario.onActivity { a -> a.requestedOrientation = orientation(landscape) }
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            if (runCatching { shaped() }.getOrDefault(false)) return
            SystemClock.sleep(200)
        }
        runCatching { if (landscape) device.setOrientationLeft() else device.setOrientationNatural() }
        E2e.waitFor(10_000, "the activity to turn to $want") { shaped() }
    }

    /** Polls [cond] every 50 ms (E2e.waitFor's 500 ms would make the shots' times late) and returns when it first held. */
    private fun loadedAt(timeoutMs: Long, what: String, cond: () -> Boolean): Long {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (runCatching(cond).getOrDefault(false)) return SystemClock.elapsedRealtime()
            SystemClock.sleep(50)
        }
        throw AssertionError("timed out after ${timeoutMs / 1000} s waiting for $what\n--- error log tail ---\n${E2e.errorLogTail()}")
    }

    private fun sleepUntil(at: Long) {
        val left = at - SystemClock.elapsedRealtime()
        if (left > 0) SystemClock.sleep(left)
    }

    /** The screen now, as UiAutomation sees it (turned to the display's rotation). */
    private fun capture(name: String): Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("screenshot $name")

    /**
     * Writes [bitmap] to intro-shots/[name], checks that the saved picture has the run's shape (wider than tall in landscape,
     * taller than wide upright) and keeps a copy for CI.
     */
    private fun save(bitmap: Bitmap, name: String, landscape: Boolean) {
        val shot = File(context.getExternalFilesDir("intro-shots"), name)
        try {
            assertTrue("screenshot $name", shot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) })
        } finally {
            bitmap.recycle()
        }
        keepForCi(shot)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shot.absolutePath, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (landscape) {
            assertTrue("$name is ${w}x$h, not wider than tall: the emulator did not turn the screen to landscape", w > 0 && w > h)
        } else {
            assertTrue("$name is ${w}x$h, not taller than wide: the screen is not upright", h > 0 && h > w)
        }
    }

    /** The one WebView in [activity]'s window, or null. Main thread. */
    private fun webView(activity: Activity): WebView? {
        fun find(v: View): WebView? {
            if (v is WebView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(activity.window.decorView)
    }

    /** A plain test picture (a pink-to-aqua gradient with a round face), kept as the app keeps the user's: one JPEG file. */
    private fun testPicture(): File {
        val file = File(context.filesDir, "intro-web-test/picture.jpg")
        if (file.isFile) return file
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 600f, 800f, 0xFFF6C6D6.toInt(), 0xFFA8E0E8.toInt(), Shader.TileMode.CLAMP) })
        canvas.drawCircle(300f, 300f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE4D6.toInt() })
        canvas.drawCircle(250f, 280f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5E4445.toInt() })
        canvas.drawCircle(350f, 280f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5E4445.toInt() })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private companion object {
        const val CI_DIR = "/data/local/tmp/intro-shots"
    }

    /** Copies [shot] where the shell user keeps it after the app is uninstalled (CI pulls /data/local/tmp/intro-shots). */
    private fun keepForCi(shot: File) {
        // One plain command per call: UiAutomation runs them without a shell, so no `&&` or quotes (the paths have no spaces).
        E2e.shell("mkdir -p $CI_DIR")
        E2e.shell("cp ${shot.absolutePath} $CI_DIR/${shot.name}")
        Log.i(FakeModel.TAG, "intro-shots: kept ${E2e.shell("ls -l $CI_DIR/${shot.name}").trim()}")
    }
}
