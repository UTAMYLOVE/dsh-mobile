package app.dshmobile

import android.app.Activity
import android.graphics.Bitmap
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

/**
 * The dsh community startup looks (櫻花動漫風, 粉色霓虹敘事, 光點聚形波浪) on a real Android WebView, which Robolectric cannot
 * render (WebIntroTest covers the rest there). Each look plays with a test picture in its locked-down WebView, a few seconds in
 * UiAutomator takes a screenshot (portrait, and landscape in a second pass) into the app's external files dir, intro-shots/,
 * and a tap (or back) must end the overlay early and leave no WebView behind. The screenshots are also copied to
 * /data/local/tmp/intro-shots, which outlives the app's uninstall after the run, so CI can pull them for the owner.
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
    @Test fun sakuraFitsLandscape() = play(IntroStyle.WEB_SAKURA, WebLook.SAKURA, "櫻花動漫風", landscape = true)
    @Test fun neonFitsLandscape() = play(IntroStyle.WEB_NEON, WebLook.NEON, "粉色霓虹敘事", landscape = true)
    @Test fun waveFitsLandscape() = play(IntroStyle.WEB_WAVE, WebLook.WAVE, "光點聚形波浪", landscape = true)

    private fun play(style: IntroStyle, look: WebLook, title: String, landscape: Boolean) {
        if (landscape) {
            device.setOrientationLeft()
            SystemClock.sleep(1_000)
        }
        // 8 s, so an end within a few seconds of the tap can only be the tap's.
        val intro = Intro(enabled = true, name = "小雨", welcome = "歡迎回來", seconds = 8f, style = style)
        val picture = testPicture()
        val done = CountDownLatch(1)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            var activity: Activity? = null
            scenario.onActivity { a ->
                activity = a
                a.setContent {
                    var showing by remember { mutableStateOf(true) }
                    FogTheme(effectsFor(EffectLevel.FULL, false)) {
                        CompositionLocalProvider(LocalLook provides Look(picture = 1L, intro = intro), LocalLookPicture provides picture) {
                            if (showing) StartupIntro(intro, null, reducedMotion = false) { done.countDown(); showing = false }
                        }
                    }
                }
            }
            val host = activity!!
            // The page came from the app's own assets through the loader: its <title> is the look's name. A page that failed
            // would have given way to 櫻紋陣, and its WebView would be gone.
            E2e.waitFor(30_000, "the ${look.key} page to load in its WebView") {
                E2e.onMain { webView(host)?.let { it.progress >= 100 && it.title == title } == true }
            }
            val loaded = SystemClock.elapsedRealtime()
            SystemClock.sleep(3_000)
            val name = "${look.key}-${if (landscape) "landscape" else "portrait"}.png"
            val shot = File(context.getExternalFilesDir("intro-shots"), name)
            assertTrue("screenshot $name", device.takeScreenshot(shot))
            keepForCi(shot)
            assertTrue("${look.key} still plays in its WebView (no fall back to 櫻紋陣)", E2e.onMain { webView(host) != null })

            if (landscape) {
                // Back ends a skippable look at once.
                device.pressBack()
            } else {
                // Near the top, away from 粉色霓虹敘事's login card (a tap on the card is its story's SIGN IN, not a skip).
                device.click(device.displayWidth / 2, device.displayHeight / 7)
            }
            assertTrue("${look.key}: the overlay ends after the ${if (landscape) "back" else "tap"}", done.await(5, TimeUnit.SECONDS))
            val took = SystemClock.elapsedRealtime() - loaded
            Log.i(FakeModel.TAG, "intro-shots: $name, ended ${took} ms after the page loaded")
            assertTrue("${look.key} ended early, not at its own 8 s end ($took ms)", took < 7_500)
            E2e.waitFor(5_000, "the ${look.key} WebView to be let go of") { E2e.onMain { webView(host) == null } }
        } finally {
            scenario.close()
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
