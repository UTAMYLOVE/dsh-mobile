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
    /** The list's tab (ListTab name) and the project page open in it, if any (0.5.0). */
    var listTab: String by mutableStateOf("ALL")
    var projectSlug: String? by mutableStateOf(null)

    /**
     * Asked before [select] leaves the current tab (e.g. settings while a save runs: leaving would cancel it); false keeps
     * the tab and calls [onLeaveRefused] instead.
     */
    var canLeave: (ShellTab) -> Boolean = { true }
    var onLeaveRefused: () -> Unit = {}

    fun select(tab: ShellTab) {
        if (tab == this.tab) return
        if (!canLeave(this.tab)) {
            onLeaveRefused()
            return
        }
        this.tab = tab
    }

    /** Opens conversation [id] in the chat tab. */
    fun openSession(id: String) {
        openSessionId = id
        listOpen = false
        tab = ShellTab.CHAT
    }

    /**
     * One step back inside the shell. On the chat tab the conversation list closes first (over an open conversation), then the
     * open conversation. Any other tab, and the chat with nothing left to close, goes to the overview through [select], so
     * [canLeave] is asked (a refusal still consumes the back: the app must not close under a busy page). The chat's state is
     * only touched while the chat is the tab (final review B1-I5). False on the overview: the system's back then applies.
     */
    fun back(): Boolean {
        if (tab == ShellTab.CHAT) {
            if (listOpen && openSessionId != null) {
                listOpen = false
                return true
            }
            listOpen = false
            if (openSessionId != null) {
                openSessionId = null
                return true
            }
        }
        if (tab == ShellTab.OVERVIEW) return false
        select(ShellTab.OVERVIEW)
        return true
    }
}
