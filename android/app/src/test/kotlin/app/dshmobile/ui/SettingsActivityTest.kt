package app.dshmobile.ui

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.DshMobileApp
import app.dshmobile.config.DshPatch
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.CommandResult
import app.dshmobile.runtime.DshVersions
import app.dshmobile.security.KeyVault
import app.dshmobile.service.DshController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as DshMobileApp
    private val tmp: File = Files.createTempDirectory("settingstest").toFile()
    private lateinit var controller: DshController

    @Before fun setUp() {
        // A controller that can never start dsh (no rootfs) and an empty versions directory: the screen only reads them.
        controller = DshController(
            patchProvider = { DshPatch.render(it) },
            filesDir = tmp, isRootfsInstalled = { false }, launcher = { _, _ -> error("never") },
            vault = object : KeyVault {
                override fun get(p: Provider): String? = null
                override fun put(p: Provider, key: String) = Unit
                override fun clear(p: Provider) = Unit
            },
            meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
            errorLog = ErrorLog(tmp), healthCheck = { true }, dnsServers = { emptyList() },
            onNotify = {}, onCostAlert = {}, onRateGuard = {},
        )
        swap("controller", controller)
        swap("dshVersions", DshVersions(File(tmp, "opt/dsh"), { _, _ -> CommandResult(-1, "", "") }, ErrorLog(tmp)))
    }

    @After fun tearDown() {
        controller.stop()
        tmp.deleteRecursively()
    }

    private fun swap(field: String, value: Any) {
        val f = DshMobileApp::class.java.getDeclaredField("$field\$delegate")
        f.isAccessible = true
        f.set(app, lazyOf(value))
    }

    @Test fun theActivityIsDeclaredAndNotExported() {
        val info = app.packageManager.getActivityInfo(ComponentName(app.packageName, MainActivity.SETTINGS_ACTIVITY), 0)
        assertFalse(info.exported)
        assertEquals(SettingsActivity::class.java.name, info.name)
    }

    @Test fun whileVisibleItHoldsDshAsTheUiAndReleasesWhenStopped() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        assertEquals(setOf("ui"), controller.holders)
        c.pause().stop()
        assertTrue(controller.holders.isEmpty())
        c.destroy()
    }

    @Test fun settingsOnTopOfMainKeepsTheHoldWhileMainStopsBehindIt() {
        // Android's order: Main started, Settings created/started/resumed, then Main stopped.
        app.uiHolder.onUiStart()                       // main
        val c = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        app.uiHolder.onUiStop()                        // main stops behind settings
        assertEquals(setOf("ui"), controller.holders)
        assertFalse(app.uiHolder.graceScheduled)
        c.pause().stop().destroy()
        assertTrue(controller.holders.isEmpty())
        assertTrue(app.uiHolder.graceScheduled)
    }
}
