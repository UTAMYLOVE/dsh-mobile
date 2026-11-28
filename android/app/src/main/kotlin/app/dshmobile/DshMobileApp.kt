package app.dshmobile

import android.app.Application
import app.dshmobile.platform.media.AudioRecorder
import app.dshmobile.platform.media.MediaVault
import kotlin.concurrent.thread

/**
 * The process. Everything is wired in [AppGraph], built on first use of [graph]; the graph itself opens nothing until a member is
 * used. Receivers, workers and screens all reach it from here.
 */
class DshMobileApp : Application() {
    @Volatile private var built: AppGraph? = null

    /** The wiring. A test may assign its own (a graph over a plain SQLite database). */
    var graph: AppGraph
        get() = built ?: synchronized(this) { built ?: AppGraph(this).also { built = it } }
        set(value) {
            built = value
        }

    override fun onCreate() {
        super.onCreate()
        graph.start()
        // Plaintext shots and recordings never outlive their screen; whatever a dead process left is swept at start.
        thread(name = "sweep-plaintext", isDaemon = true) {
            runCatching {
                MediaVault.sweepCameraCache(app.dshmobile.platform.media.SystemCamera.cameraDir(this), setOfNotNull(graph.systemCamera.pendingName()))
                MediaVault.sweepCameraCache(AudioRecorder.recordDir(this))
            }
        }
    }
}
