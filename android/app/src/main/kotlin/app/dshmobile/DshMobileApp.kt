package app.dshmobile

import android.app.Application
import app.dshmobile.platform.media.AudioRecorder
import app.dshmobile.platform.media.MediaVault
import app.dshmobile.ui.UiGraph
import app.dshmobile.ui.UiGraphProvider
import app.dshmobile.wire.Screens
import app.dshmobile.wire.Wire
import kotlin.concurrent.thread

/**
 * The process. Everything is wired in [AppGraph], built on first use of [graph]; the graph itself opens nothing until a member is
 * used. Receivers, workers and screens all reach it from here.
 */
class DshMobileApp : Application(), UiGraphProvider {
    @Volatile private var built: AppGraph? = null

    /** Which of our screens is in front (pickers and permission questions need it; the wake handler reads it). */
    val screens = Screens()

    /** The wiring layer ([Wire]), registered in [onCreate]. */
    @Volatile var wire: Wire? = null

    /** The doors of the screens: always the real wiring (the demo bundle is only for tests and previews). */
    override val uiGraph: UiGraph by lazy { (wire ?: Wire.install(this)).uiGraph }

    /** The wiring. A test may assign its own (a graph over a plain SQLite database). */
    var graph: AppGraph
        get() = built ?: synchronized(this) { built ?: AppGraph(this).also { built = it } }
        set(value) {
            built = value
        }

    override fun onCreate() {
        super.onCreate()
        graph.start()
        registerActivityLifecycleCallbacks(screens)
        Wire.install(this)
        // Plaintext shots and recordings never outlive their screen; whatever a dead process left is swept at start.
        thread(name = "sweep-plaintext", isDaemon = true) {
            runCatching {
                MediaVault.sweepCameraCache(app.dshmobile.platform.media.SystemCamera.cameraDir(this), setOfNotNull(graph.systemCamera.pendingName()))
                MediaVault.sweepCameraCache(AudioRecorder.recordDir(this))
            }
        }
    }
}
