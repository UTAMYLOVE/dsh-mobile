package app.dshmobile.system

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhantomProcessDetectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver
    private val detector = PhantomProcessDetector(resolver)
    private val key = "settings_enable_monitor_phantom_procs"

    @Test fun falseMeansDisabled() {
        Settings.Global.putString(resolver, key, "false")
        assertEquals(PhantomState.DISABLED, detector.state())
    }

    @Test fun trueMeansEnabled() {
        Settings.Global.putString(resolver, key, "true")
        assertEquals(PhantomState.ENABLED, detector.state())
    }

    @Test fun anyOtherValueIsUnknown() {
        Settings.Global.putString(resolver, key, "maybe")
        assertEquals(PhantomState.UNKNOWN, detector.state())
        Settings.Global.putString(resolver, key, "")
        assertEquals(PhantomState.UNKNOWN, detector.state())
        Settings.Global.putString(resolver, key, "FALSE")
        assertEquals(PhantomState.UNKNOWN, detector.state())
    }

    @Test fun missingSettingIsUnknown() {
        assertEquals(PhantomState.UNKNOWN, detector.state())
    }
}
