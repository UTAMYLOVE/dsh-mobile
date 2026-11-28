package app.dshmobile

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import app.agency.core.base.TestClock
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs before the application's own `onCreate` for the whole instrumentation run:
 * - clears what an earlier run left of the app's own database and secrets (a first start every time);
 * - starts the [FakeModel] and points the Claude and DeepSeek bases of `AppGraph` at it, so the network gate lets out nothing but
 *   that one local socket;
 * - gives the company a fixed clock (a Monday morning), so the journey does not depend on the hour the test runs.
 * The database and the key vault are the real ones (SQLCipher, Android Keystore).
 */
class E2eRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        wipe(app)
        val model = FakeModel()
        thread(name = "e2e-fake-model-start") { model.start() }.join() // binding a socket on the main thread is not allowed
        FakeModel.instance = model
        val graph = (app as DshMobileApp).graph
        graph.claudeBase = model.base
        graph.deepseekBase = model.base
        graph.clock = TestClock().also { it.set(TestClock.MONDAY, "10:00") }
        super.callApplicationOnCreate(app)
    }

    private fun wipe(app: Application) {
        val dir = app.noBackupFilesDir
        dir.listFiles().orEmpty().filter { it.name.startsWith("xiaoyu") }.forEach { f -> if (f.isDirectory) f.deleteRecursively() else f.delete() }
        File(app.filesDir, "xiaoyu").deleteRecursively()
        runCatching { app.deleteSharedPreferences("xiaoyu_alarm") }
    }
}
