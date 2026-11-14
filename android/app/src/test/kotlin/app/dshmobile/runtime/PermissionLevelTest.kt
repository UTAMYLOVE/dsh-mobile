package app.dshmobile.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionLevelTest {
    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun aNewInstallStartsAtTheSmartLevel() {
        assertEquals(PermissionLevel.SMART, RuntimeModeStore(prefs("fresh")).permission.value)
    }

    /** Before the levels there was one switch, 「原生模式：指令不用每次問我」: who had it on keeps never being asked. */
    @Test fun theOldNoApprovalSwitchBecomesTheFullLevel() {
        val p = prefs("old").apply { edit().putBoolean("native_no_approval", true).commit() }
        assertEquals(PermissionLevel.FULL, RuntimeModeStore(p).permission.value)
        val off = prefs("old-off").apply { edit().putBoolean("native_no_approval", false).commit() }
        assertEquals(PermissionLevel.SMART, RuntimeModeStore(off).permission.value)
    }

    @Test fun aChosenLevelIsKeptAndWinsOverTheOldSwitch() {
        val p = prefs("chosen").apply { edit().putBoolean("native_no_approval", true).commit() }
        val store = RuntimeModeStore(p)
        store.setPermission(PermissionLevel.ASK)
        assertEquals(PermissionLevel.ASK, store.permission.value)
        assertEquals(PermissionLevel.ASK, RuntimeModeStore(p).permission.value)
        assertEquals("ask", p.getString("permission_level", null))
    }

    @Test fun theIdsAreTheOnesTheGuardPluginReads() {
        assertEquals(listOf("ask", "smart", "full"), PermissionLevel.entries.map { it.id })
        assertEquals(PermissionLevel.FULL, PermissionLevel.of("full"))
        assertNull(PermissionLevel.of("nonsense"))
        assertNull(PermissionLevel.of(null))
    }
}
