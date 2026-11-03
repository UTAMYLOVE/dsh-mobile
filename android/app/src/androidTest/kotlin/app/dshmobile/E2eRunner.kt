package app.dshmobile

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import kotlin.concurrent.thread

/**
 * Starts the [FakeModel] and points the app's gateway at it before the application's own `onCreate`, so the app-wide
 * controller (also created lazily by a heartbeat that WorkManager may run at process start) never sees the real
 * providers. Every instrumentation test in this APK runs with it; nothing here makes a request to the internet.
 */
class E2eRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        val model = FakeModel()
        thread(name = "e2e-fake-model-start") { model.start() }.join() // binding a socket on the main thread is not allowed
        FakeModel.instance = model
        (app as DshMobileApp).upstreamsOverride = model.upstreams()
        super.callApplicationOnCreate(app)
    }
}
