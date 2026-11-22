package app.dshmobile

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.TestOverrides
import kotlin.concurrent.thread

/**
 * Starts the [FakeModel] and sets [AgencyApp.testOverrides] before the application's own `onCreate`: every model request goes
 * to the fake on 127.0.0.1, and the network gate allows only that origin. The database and the media vault are the real ones
 * (SQLCipher, Keystore). Every instrumentation test in this APK runs with it; nothing here reaches the internet.
 */
class E2eRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        val model = FakeModel()
        thread(name = "e2e-fake-model-start") { model.start() }.join() // binding a socket on the main thread is not allowed
        FakeModel.instance = model
        AgencyApp.testOverrides = TestOverrides(modelBase = model.base)
        super.callApplicationOnCreate(app)
    }
}
