package app.dshmobile

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun permissionsAreExactlyTheAllowedSet() {
        val perms = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toSet()
        assertEquals(ALLOWED_PERMISSIONS, perms)
    }

    companion object {
        /** Arch 1.13 (USE_EXACT_ALARM came with the scheduler, T13). */
        val ALLOWED_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.WAKE_LOCK",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.RECORD_AUDIO",
            "android.permission.USE_EXACT_ALARM",
        )
    }
}
