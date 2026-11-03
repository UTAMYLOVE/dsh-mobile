package app.dshmobile.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the shell is: the selected tab, the conversation open in the chat tab and whether the conversation list is showing over
 * it. Compose state, so the shell follows every change; main thread only. One per screen (it is not dsh's state).
 */
class ShellNav {
    /** The mockup's page 0 is the overview. */
    var tab: ShellTab by mutableStateOf(ShellTab.OVERVIEW)
    var openSessionId: String? by mutableStateOf(null)
    var listOpen: Boolean by mutableStateOf(false)

    fun select(tab: ShellTab) {
        this.tab = tab
    }

    /** Opens conversation [id] in the chat tab. */
    fun openSession(id: String) {
        openSessionId = id
        listOpen = false
        tab = ShellTab.CHAT
    }

    /**
     * One step back inside the shell: the conversation list closes first, then the open conversation, then any tab goes back to
     * the overview. False when there is nothing left to go back from (the system's back then applies).
     */
    fun back(): Boolean = when {
        listOpen -> { listOpen = false; true }
        openSessionId != null -> { openSessionId = null; true }
        tab != ShellTab.OVERVIEW -> { tab = ShellTab.OVERVIEW; true }
        else -> false
    }
}
