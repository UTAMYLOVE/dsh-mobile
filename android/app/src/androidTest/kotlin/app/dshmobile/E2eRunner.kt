package app.dshmobile

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.TestOverrides
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import app.dshmobile.agency.media.RecorderEngine
import java.io.File
import kotlin.concurrent.thread

/**
 * Starts the [FakeModel] and sets [AgencyApp.testOverrides] before the application's own `onCreate`: every model request goes
 * to the fake on 127.0.0.1, and the network gate allows only that origin. The database and the media vault are the real ones
 * (SQLCipher, Keystore). Every instrumentation test in this APK runs with it; nothing here reaches the internet.
 */
/** The test camera: a drawn 2000 x 1500 picture as JPEG, so the real downsizing runs on the phone. */
fun drawShot(out: File): Boolean {
    val bmp = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(250, 190, 205)) }
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bmp.recycle()
    return true
}

/** The test microphone: the invented recording fixture from the test APK's assets, written when recording starts. */
class FakeMic(private val app: Context) : RecorderEngine {
    override fun start(out: File, maxMs: Int, onLimit: () -> Unit) {
        val asset = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        out.writeBytes(asset.open("legacy/agency-v2-baseline/recording-1795225200000.m4a").use { it.readBytes() })
    }

    override fun stop(): Boolean = true
    override fun release() = Unit
}

class E2eRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        val model = FakeModel()
        thread(name = "e2e-fake-model-start") { model.start() }.join() // binding a socket on the main thread is not allowed
        FakeModel.instance = model
        AgencyApp.testOverrides = TestOverrides(modelBase = model.base, camera = ::drawShot, recorder = { FakeMic(app) })
        super.callApplicationOnCreate(app)
    }
}
