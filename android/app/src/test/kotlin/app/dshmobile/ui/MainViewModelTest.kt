package app.dshmobile.ui

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.dshmobile.DshMobileApp
import app.dshmobile.config.AgentSettings
import app.dshmobile.ui.shell.ShellTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<DshMobileApp>()

    private fun idle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Residual review: a pasted chapter in the composer must not overflow the saved-state Bundle (TransactionTooLargeException). */
    @Test fun aHugeDraftStaysInMemoryButIsNotSaved() {
        val vm = MainViewModel(app, SavedStateHandle())
        val chapter = "霧".repeat(MainViewModel.MAX_SAVED_TEXT + 1)
        vm.onText(chapter)
        val saved = vm.saveState()
        assertFalse("too long for a Bundle", saved.containsKey("text"))
        assertEquals("the draft itself is untouched", chapter, vm.composerText)
        val restored = MainViewModel(app, SavedStateHandle(mapOf("dshm.shell" to saved)))
        assertEquals("", restored.composerText)
    }

    @Test fun aNormalDraftIsSavedAndRestored() {
        val vm = MainViewModel(app, SavedStateHandle())
        val text = "x".repeat(MainViewModel.MAX_SAVED_TEXT)
        vm.onText(text)
        vm.nav.openSession("s1")
        val restored = MainViewModel(app, SavedStateHandle(mapOf("dshm.shell" to vm.saveState())))
        assertEquals(text, restored.composerText)
        assertEquals("s1", restored.nav.openSessionId)
        assertEquals(ShellTab.CHAT, restored.nav.tab)
    }

    /** Residual review: a link that waited for a key save or an export opens when that local action ends. */
    @Test fun aLinkWaitingForALocalSettingsActionOpensWhenItEnds() {
        val vm = MainViewModel(app, SavedStateHandle())
        idle()
        vm.nav.select(ShellTab.SETTINGS)
        val draft = SettingsDraft(AgentSettings(maxConcurrent = 6, leadModel = "deepseek-flash"), "5", false, "10")
        vm.settingsDraft = draft
        draft.localBusy = true
        idle()
        vm.openFromLink("s2")
        assertEquals(ShellTab.SETTINGS, vm.nav.tab)
        assertTrue(vm.nav.openSessionId != "s2")
        draft.localBusy = false
        idle()
        assertEquals(ShellTab.CHAT, vm.nav.tab)
        assertEquals("s2", vm.nav.openSessionId)
        assertNull(vm.pendingOpen)
    }
}
