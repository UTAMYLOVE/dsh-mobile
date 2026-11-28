package app.dshmobile.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.agency.core.base.TestClock
import app.agency.core.net.HttpFactory
import app.agency.core.net.HttpOutcome
import app.agency.core.net.NetworkGate
import app.agency.core.net.Origin
import app.agency.core.net.ReleaseCheck
import app.agency.core.net.Transport
import app.dshmobile.AppGraph
import app.dshmobile.BuildConfig
import app.dshmobile.DshMobileApp
import app.dshmobile.data.Settings
import app.dshmobile.data.SqlStore
import app.dshmobile.data.TestDb
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateAndNetTest {
    private val app: DshMobileApp = ApplicationProvider.getApplicationContext()
    private val context: Context = app
    private val server = MockWebServer().apply { start() }
    private val clock = TestClock()
    private val settings = Settings(SqlStore(TestDb.open(context)))
    private val notifier = Notifier(context).also { shadowOf(app).grantPermissions(Notifier.PERMISSION) }

    @After fun stop() = server.shutdown()

    private fun release(tag: String) = MockResponse().setBody(
        """{"tag_name":"$tag","html_url":"https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/x"}""",
    )

    private fun checker(): UpdateCheck {
        val url = server.url("/repos/o/r/releases/latest")
        val gate = NetworkGate({ setOf(Origin(url.scheme, url.host, url.port)) })
        val rc = ReleaseCheck(Transport(HttpFactory.create(gate)) {}, url)
        return UpdateCheck({ rc }, settings, notifier, { clock }, "1.0.0", 20, context)
    }

    private fun shown() = shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications

    @Test fun aNewerReleaseIsKeptAndNotifiedOnceQuietly() = runBlocking {
        server.enqueue(release("v1.0.1+21"))
        assertEquals(UpdateCheck.Outcome.NEWER, checker().run())
        assertEquals("1.0.1", settings.get().newVersion?.versionName)
        val n = shown().single()
        assertEquals(Notifier.CHANNEL_QUIET, n.channelId)
        assertEquals("有新版本", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        // the same day: no second request
        assertEquals(UpdateCheck.Outcome.SKIPPED_TODAY, checker().run())
        assertEquals(1, server.requestCount)
        // next day, same version: kept, not notified again
        n.let { context.getSystemService(android.app.NotificationManager::class.java).cancelAll() }
        clock.plus(java.time.Duration.ofDays(1))
        server.enqueue(release("v1.0.1+21"))
        assertEquals(UpdateCheck.Outcome.NEWER, checker().run())
        assertEquals(0, shown().size)
    }

    @Test fun anOlderOrEqualReleaseClearsTheNote() = runBlocking {
        server.enqueue(release("v1.0.1+21"))
        checker().run()
        clock.plus(java.time.Duration.ofDays(1))
        server.enqueue(release("v1.0.0+20"))
        assertEquals(UpdateCheck.Outcome.UP_TO_DATE, checker().run())
        assertNull(settings.get().newVersion)
        assertEquals(0, shown().size)
    }

    @Test fun aBadAnswerIsNoReleaseAndIsNotRetriedTheSameDay() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(UpdateCheck.Outcome.NO_ANSWER, checker().run())
        assertEquals(UpdateCheck.Outcome.SKIPPED_TODAY, checker().run())
        assertEquals(1, server.requestCount)
    }

    @Test fun theReleaseUrlIsBuiltOnGithubsApiHostOnly() {
        assertEquals(NetworkGate.GITHUB_API_HOST, UpdateCheck.latestUrl(BuildConfig.RELEASES_REPO).host)
        assertTrue(BuildConfig.RELEASES_REPO.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
    }

    @Test fun theDailyCheckIsOnePeriodicWorkNeedingTheNetwork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        UpdateWork.schedule(context)
        UpdateWork.schedule(context) // kept, not doubled
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UpdateWork.NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    // ---- the one network exit ----

    @Test fun onlyTheThreeHostsPassTheGraphsGate() {
        val g = AppGraph(app, openDb = { TestDb.open(context) })
        listOf("https://api.deepseek.com/x", "https://api.anthropic.com/v1/messages", "https://api.github.com/repos/a/b").forEach {
            assertTrue(it, g.gate.permits(it.toHttpUrl()))
        }
        listOf("https://example.com/", "http://api.github.com/", "https://api.github.com:8443/", "https://github.com/", "https://evil.api.anthropic.com/")
            .forEach { assertFalse(it, g.gate.permits(it.toHttpUrl())) }
    }

    @Test fun aRequestToAnotherHostIsBlockedBeforeAnythingIsSent() = runBlocking {
        val g = AppGraph(app, openDb = { TestDb.open(context) })
        val out = g.transport.postJson("https://example.com/v1".toHttpUrl(), mapOf("x-api-key" to "invented"), "{}")
        assertEquals(HttpOutcome.Blocked("example.com"), out)
        assertEquals(1, g.gate.blockedCount)
        assertTrue(g.errorLog.file().readText().contains("blocked host example.com"))
        assertFalse(g.errorLog.file().readText().contains("invented"))
    }

    @Test fun testBasesReplaceTheRealHostsEntirely() = runBlocking {
        val g = AppGraph(app, openDb = { TestDb.open(context) })
        g.claudeBase = server.url("/")
        assertTrue(g.gate.permits(server.url("/v1")))
        assertFalse(g.gate.permits("https://api.anthropic.com/".toHttpUrl()))
        server.enqueue(MockResponse().setBody("{}"))
        val out = g.transport.postJson(server.url("/v1/messages"), emptyMap(), "{}")
        assertEquals(HttpOutcome.Response(200, "{}"), out)
    }
}
