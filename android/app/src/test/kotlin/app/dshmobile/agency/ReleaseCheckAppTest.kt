package app.dshmobile.agency

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.agency.core.net.HttpFactory
import app.agency.core.net.NetworkGate
import app.agency.core.net.Origin
import app.agency.core.net.ReleaseCheck
import app.agency.core.net.Transport
import app.dshmobile.BuildConfig
import app.dshmobile.DshMobileApp
import app.dshmobile.agency.AgencyTestKit.Companion.waitForTag
import app.dshmobile.ui.agency.AgencyActivity
import app.dshmobile.ui.agency.AgencyTab
import app.dshmobile.ui.agency.AgencyTags
import app.dshmobile.ui.settings.SamsungTags
import app.dshmobile.ui.settings.SettingsPage
import app.dshmobile.ui.settings.SettingsTags
import app.dshmobile.ui.settings.VersionTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 新版本通知 at most once a day (shown in 設定), the version 0.9.0 (18), and 設定's Samsung page (owner 2026-11-27). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
class ReleaseCheckAppTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("release").toFile()
    private val kit = AgencyTestKit(tmp)
    private val server = MockWebServer().apply { start() }
    private var scenario: ActivityScenario<AgencyActivity>? = null

    @After fun tearDown() {
        scenario?.close()
        runBlocking { runCatching { app.agency.stopForTest() } }
        AgencyApp.testOverrides = null
        app.agency.settings.releaseCheckedDay = null
        app.agency.settings.newRelease = null
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun check(): ReleaseCheck {
        val url = server.url("/repos/UTAMYLOVE/dsh-mobile-releases/releases/latest")
        return ReleaseCheck(Transport(HttpFactory.create(NetworkGate({ setOf(Origin(url.scheme, url.host, url.port)) }))) {}, url)
    }

    @Test fun aNewerReleaseIsCheckedOnceADayAndShownInSettings() {
        assertEquals("0.9.0" to 18, BuildConfig.VERSION_NAME to BuildConfig.VERSION_CODE)
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.9.1+19","html_url":"https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/v0.9.1%2B19"}"""))
        runBlocking { app.agency.checkReleaseWith(check()) }
        assertEquals("0.9.1|https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/v0.9.1%2B19", app.agency.settings.newRelease)
        // Once a day: the second check the same day sends nothing.
        runBlocking { app.agency.checkReleaseWith(check()) }
        assertEquals(1, server.requestCount)
        rule.onNodeWithTag(AgencyTags.TAB + AgencyTab.SETTINGS.name).performClick()
        rule.waitForTag(VersionTags.NOW)
        rule.onNodeWithTag(VersionTags.NOW).assertTextEquals("現在的版本：0.9.0（第 18 版）")
        rule.onNodeWithTag(VersionTags.NEW).assertTextEquals("有新版本 0.9.1，點這裡看下載頁")
        // The Samsung page: the steps and the button to the system's page of the app.
        rule.onNodeWithTag(SettingsTags.ENTRY + SettingsPage.SAMSUNG.name).performScrollTo().performClick()
        rule.waitForTag(SamsungTags.STEP + "2")
        rule.onNodeWithTag(SamsungTags.STEP + "2").assertTextEquals("2. 點「電池」，選「不受限制」。")
        rule.onNodeWithTag(SamsungTags.OPEN).performClick()
    }

    @Test fun theSameVersionClearsTheNotice() {
        AgencyTestKit.swap(app, "keyVault", AgencyTestKit.MemoryKeys())
        AgencyApp.testOverrides = kit.overrides()
        scenario = ActivityScenario.launch(AgencyActivity::class.java)
        rule.waitForTag(AgencyTags.HOME)
        app.agency.settings.newRelease = "0.8.9|https://github.com/UTAMYLOVE/dsh-mobile-releases/releases"
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.9.0+18","html_url":"https://github.com/UTAMYLOVE/dsh-mobile-releases/releases/tag/v0.9.0%2B18"}"""))
        runBlocking { app.agency.checkReleaseWith(check()) }
        assertNull(app.agency.settings.newRelease)
    }
}
