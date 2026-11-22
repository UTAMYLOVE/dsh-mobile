package app.dshmobile.ui.agency

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import app.dshmobile.ui.settings.LegacyTags
import app.dshmobile.ui.settings.ModelKeyTags
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsTags
import app.dshmobile.ui.settings.SpendTags
import kotlin.concurrent.thread
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.brain.Brain
import app.agency.core.llm.Fail
import app.agency.core.metering.Provider
import app.dshmobile.DshMobileApp
import app.dshmobile.R
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.agency.core.model.Profile
import app.dshmobile.agency.data.AgencyDatabase
import app.dshmobile.agency.data.RoomAgencyStore
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
        // An invented profile, so the header's profile load writes a value (not null over null).
        runBlocking {
            AgencyDatabase.build(app, File(tmp, "agency.db").path, FrameworkSQLiteOpenHelperFactory()).let { db ->
                RoomAgencyStore(db).profile.put(Profile(dayStartHour = 4))
                db.close()
            }
        }
        AgencyApp.testOverrides = kit.overrides(s.url("/"))
        watchThreads()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)

        // Each send bumps the brain's revision and the header reloads the profile: a changed one each time, so it is written.
        changeProfile(5)
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitForText(Brain.failLine(Fail.OFFLINE))

        online.set(true)
        changeProfile(6)
        rule.onNodeWithTag(AgencyTags.COMPOSER).performTextInput("姐姐在嗎")
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(AgencyTags.SEND).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(AgencyTags.SEND).performClick()
        rule.waitForText("現在去喝水。")
        rule.waitForIdle()

        assertEquals(emptyList<String>(), offMain.toList().distinct())
    }

    private fun changeProfile(dayStartHour: Int) = runBlocking {
        (app.agency.start.value as app.dshmobile.agency.StartState.Ready).store.profile.put(Profile(dayStartHour = dayStartHour))
    }

    private fun openSettings(page: SettingsPage) {
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + page.name).performClick()
    }

    /** Runs [block] on a plain background thread (no looper), as the brain and the meter do from IO. */
    private fun offThread(block: () -> Unit) = thread(name = "invented-worker") { block() }.join()

    /** 設定 › 模型與金鑰 and 花費上限: the flows they follow change on other threads; the pages change on the main thread. */
    @Test fun theModelAndSpendPagesChangeOnlyOnTheMainThread() {
        val s = MockWebServer().also { server = it }
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        s.start()
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-test-0123456789abcdef"))
        AgencyApp.testOverrides = kit.overrides(s.url("/"))
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        watchThreads()

        openSettings(SettingsPage.MODEL)
        rule.waitForTag(ModelKeyTags.KEY_STATUS)
        offThread { app.agency.settings.deepseekModel = "deepseek-v4-pro" }
        // The key is refused: the brain sets lastFail on its IO thread and the page says so.
        app.agency.send("姐姐在嗎")
        rule.waitForTag(ModelKeyTags.REJECTED)

        rule.onNodeWithTag(SettingsTags.BACK).performClick()
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.SPEND.name).performClick()
        rule.waitForTag(SpendTags.SUGGEST)
        offThread { app.costMeter.hardCapUsd = 2.0 }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(SpendTags.SUGGEST).fetchSemanticsNodes().isEmpty() }
        offThread { app.costMeter.hardCapUsd = null }
        rule.waitForTag(SpendTags.SUGGEST)
        rule.waitForIdle()

        assertEquals(emptyList<String>(), offMain.toList().distinct())
    }

    /** 設定 › 舊資料 after a failed first import: loading the page and 再試一次 change it on the main thread only. */
    @Test fun theLegacyPageRetryChangesOnlyOnTheMainThread() {
        AgencyTestKit.seedV2(app)
        kit.dropMessages = true
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME, 20_000)
        rule.waitForText("搬資料沒有成功，舊資料都還在。")
        kit.dropMessages = false
        watchThreads()
        // The notice goes away from another thread (as the import's bookkeeping does on IO): 公司 changes on the main thread.
        offThread { app.agency.dismissNotice() }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(AgencyTags.NOTICE).fetchSemanticsNodes().isEmpty() }

        openSettings(SettingsPage.LEGACY)
        rule.waitForTag(LegacyTags.RETRY)
        rule.onNodeWithTag(LegacyTags.RETRY).performClick()
        rule.waitForText("搬完了", 20_000)
        rule.waitForIdle()

        assertEquals(emptyList<String>(), offMain.toList().distinct())
    }

    /** 設定 › 舊資料 分享 of a file that is gone: the page says it could not, on the main thread. */
    @Test fun theLegacyPageShareChangesOnlyOnTheMainThread() {
        AgencyTestKit.seedV2(app)
        kit.vaultLocked = true
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME, 20_000)
        val name = "recording-1795225200000.m4a"
        val kept = app.filesDir.listFiles { f -> f.name.startsWith("coach.migrated-") }!!.single()
        watchThreads()

        openSettings(SettingsPage.LEGACY)
        rule.waitForTag(LegacyTags.SHARE + name)
        File(kept, "baseline/$name").delete()
        rule.onNodeWithTag(LegacyTags.SHARE + name).performScrollTo().performClick()
        rule.waitForText(app.getString(R.string.ag_legacy_share_failed))
        rule.waitForIdle()

        assertEquals(emptyList<String>(), offMain.toList().distinct())
    }
}
