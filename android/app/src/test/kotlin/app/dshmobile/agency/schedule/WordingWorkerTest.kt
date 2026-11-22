package app.dshmobile.agency.schedule

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import app.agency.core.engine.Nudger
import app.agency.core.engine.Post
import app.agency.core.engine.PostChannel
import app.agency.core.metering.Provider
import app.agency.core.model.Nudge
import app.agency.core.model.WordedBy
import app.agency.core.schedule.SlotKey
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyApp
import app.dshmobile.agency.AgencyTestKit
import app.dshmobile.agency.StartState
import app.dshmobile.agency.notify.AgencyNotifier
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The model's words for a push already on screen (arch 1.6): the same notification id, silently; a failure keeps the template. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WordingWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as DshMobileApp
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val tmp: File = Files.createTempDirectory("wording").toFile()
    private var server: MockWebServer? = null

    @After fun tearDown() {
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        server?.shutdown()
        tmp.deleteRecursively()
    }

    private fun open(keys: AgencyTestKit.MemoryKeys, s: MockWebServer?): StartState.Ready {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AgencyTestKit.swap(app, "keyVault", keys)
        AgencyApp.testOverrides = AgencyTestKit(tmp).overrides(s?.url("/"))
        app.agency.start()
        val ready = runBlocking { withTimeout(10_000) { app.agency.start.first { it is StartState.Ready } } } as StartState.Ready
        // The open's own tick (it arms the alarm last) is done before the test posts.
        val am = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        val until = System.currentTimeMillis() + 10_000
        while (am.scheduledAlarms.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        return ready
    }

    private fun pushed(ready: StartState.Ready): Post {
        val p = Post(Nudger.GAP_KEY, "nudge-1", "午餐時間到了，做完回報。", PostChannel.SCHEDULE, alert = true, gapKeys = listOf(SlotKey.LUNCH), slot = SlotKey.LUNCH, word = true)
        runBlocking { ready.store.nudges.insert(Nudge(p.nudgeId, System.currentTimeMillis(), listOf("LUNCH"), "SCHEDULE", null, p.text, WordedBy.TEMPLATE)) }
        app.agency.notifier.post(p)
        return p
    }

    private fun run(p: Post) = runBlocking {
        TestListenableWorkerBuilder<WordingWorker>(context).setInputData(WordingWorker.data(p)).build().doWork()
    }

    private fun shown() = shadowOf(shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS)).contentText.toString()

    @Test fun theWordsReplaceTheTemplateOnTheSameNotificationSilently() {
        val s = AgencyTestKit.fakeDeepSeek("午餐時間到了，吃完拍照給我。").also { server = it }
        val ready = open(AgencyTestKit.MemoryKeys(Provider.DEEPSEEK to "sk-test-0123456789abcdef"), s)
        val p = pushed(ready)
        run(p)
        assertEquals("午餐時間到了，吃完拍照給我。", shown())
        val n = shadowOf(nm).getNotification(AgencyNotifier.ID_GAPS)
        assertTrue(n.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(WordedBy.MODEL, runBlocking { ready.store.nudges.get(p.nudgeId) }!!.wordedBy)
    }

    @Test fun noModelKeepsTheTemplate() {
        val ready = open(AgencyTestKit.MemoryKeys(), null)
        val p = pushed(ready)
        run(p)
        assertEquals("午餐時間到了，做完回報。", shown())
        assertEquals(WordedBy.TEMPLATE, runBlocking { ready.store.nudges.get(p.nudgeId) }!!.wordedBy)
    }

    @Test fun theInputRoundTrips() {
        val p = Post("preride", "n9", "快出門了。", PostChannel.RIDING, alert = true, gapKeys = listOf(SlotKey.DINNER, SlotKey.LUNCH), slot = SlotKey.DINNER, word = true)
        assertEquals(p.copy(alert = false, word = false), WordingWorker.read(WordingWorker.data(p)))
    }
}
