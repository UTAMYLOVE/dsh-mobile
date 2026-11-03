package app.dshmobile.ui

import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingIntentsTest {
    @Test fun batteryIntentTargetsThisPackage() {
        val i = OnboardingIntents.ignoreBatteryOptimizations("app.dshmobile")
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, i.action)
        assertEquals("package:app.dshmobile", i.data.toString())
    }

    @Test fun developerAndAboutPhoneIntentsOnlyOpenSettingsScreens() {
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, OnboardingIntents.developerSettings().action)
        assertEquals(Settings.ACTION_DEVICE_INFO_SETTINGS, OnboardingIntents.deviceInfoSettings().action)
        assertNull(OnboardingIntents.developerSettings().data)
    }

    @Test fun mainActivityIsAddressedByComponentName() {
        val i = OnboardingIntents.mainActivity("app.dshmobile")
        assertEquals("app.dshmobile", i.component!!.packageName)
        assertEquals("app.dshmobile.ui.MainActivity", i.component!!.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, i.flags)
    }
}
