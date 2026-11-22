package app.dshmobile.ui.agency

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.Brain
import app.agency.core.llm.Fail
import app.agency.core.metering.Provider
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.agency.AgencyTestKit.Companion.waitForText
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The company's screens touch Compose state on the main thread only. The e2e e05 flake: the Compose test rule runs effects on
 * an unconfined dispatcher, so a `collectAsState` of a flow the brain sets on an IO thread, or a `LaunchedEffect` after
 * `withContext(Dispatchers.IO)`, went on on that IO thread: it wrote the screen's state there and sent the snapshot's apply
 * notifications from there, and Compose then observed layout reads off the main thread while the main thread drew
 * (「Detected multithreaded access to SnapshotStateObserver」). The same rule runs here, so any such write is caught.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h720dp-mdpi")
class AgencyMainThreadTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("agency-main-thread").toFile()
    private val kit = AgencyTestKit(tmp)
    private var scenario: ActivityScenario<AgencyActivity>? = null
    private var server: MockWebServer? = null
    private val offMain = Collections.synchronizedList(mutableListOf<String>())
    private val handles = mutableListOf<ObserverHandle>()

    private fun watchThreads() {
        fun check(what: String) {
            if (Looper.myLooper() !== Looper.getMainLooper()) offMain += "$what on ${Thread.currentThread().name}"
        }
        handles += Snapshot.registerGlobalWriteObserver { check("state write") }
        handles += Snapshot.registerApplyObserver { _, _ -> check("snapshot apply") }
    }

    @After fun tearDown() {
        handles.forEach { it.dispose() }
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        server?.shutdown()
        tmp.deleteRecursively()
    }

    @Test fun offlineThenOnlineTheScreenChangesOnlyOnTheMainThread() {
        val online = java.util.concurrent.atomic.AtomicBoolean(false)
        val s = AgencyTestKit.fakeDeepSeek("現在去喝水。").also { server = it }
        val answer = s.dispatcher
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (online.get()) answer.dispatch(request) else MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-test-0123456789abcdef"))
        AgencyApp.testOverrides = kit.overrides(s.url("/"))
        watchThreads()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)

        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitForText(Brain.failLine(Fail.OFFLINE))

        online.set(true)
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(AgencyTags.SEND).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitForText("現在去喝水。")
        rule.waitForIdle()

        assertEquals(emptyList<String>(), offMain.toList().distinct())
    }
}
