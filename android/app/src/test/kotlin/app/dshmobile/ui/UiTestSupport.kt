package app.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.Dispatchers

/** Plain calls run in place (no thread hop), and the mic button is shown, so a test sees one deterministic screen. */
@Composable
fun TestEnv(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIo provides Dispatchers.Unconfined, LocalMicOverride provides true) { content() }
}

fun ComposeContentTestRule.show(content: @Composable () -> Unit) {
    setContent { TestEnv(content) }
}

/** Waits (real time) until [condition] holds; suspend work in the demo finishes in place, but a gate may hold it. */
fun ComposeContentTestRule.until(timeoutMs: Long = 5000, condition: () -> Boolean) {
    waitUntil(timeoutMs) { condition() }
}

/** The directory for the owner's pictures, or null when the property is not set. */
fun shotsDir(): File? = System.getProperty("dshm.shots")?.takeIf { it.isNotBlank() }?.let { File(it).also(File::mkdirs) }

/**
 * A picture of the whole window. The library's captureToImage waits for a draw callback that never comes while the main looper
 * is paused (the test thread is the main thread), so this asks the window for a copy itself and then lets the looper run.
 */
fun grabWindow(activity: Activity): Bitmap {
    val decor = activity.window.decorView
    val w = decor.width.coerceAtLeast(1)
    val h = decor.height.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    var status = -1
    runCatching {
        PixelCopy.request(activity.window, Rect(0, 0, w, h), bmp, { status = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
    }
    if (status != PixelCopy.SUCCESS) decor.draw(Canvas(bmp))
    return bmp
}
