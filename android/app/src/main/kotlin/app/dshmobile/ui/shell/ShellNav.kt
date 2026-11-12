package app.dshmobile.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the shell is: the selected tab, the conversation open in the chat tab and whether the conversation list is showing over
 * it. Compose state, so the shell follows every change; main thread only. One per screen (it is not dsh's state).
 */
class ShellNav {
    /** 「顯示更多功能」 (Settings): the overview, the bookshelf, projects, helpers and the status line are back. Off by default. */
    var more: Boolean by mutableStateOf(false)
    /** The dock's tabs. Settings is not among the simple ones: the chat's menus open it. */
    val tabs: List<ShellTab> get() = if (more) ShellTab.entries else ShellTab.SIMPLE
    /** Where back ends up: the chat, or the overview while it shows. */
    val home: ShellTab get() = if (more) ShellTab.OVERVIEW else ShellTab.CHAT
    /** The app opens on the chat. */
    var tab: ShellTab by mutableStateOf(ShellTab.CHAT)
    var openSessionId: String? by mutableStateOf(null)
    var listOpen: Boolean by mutableStateOf(false)
    /** The list's tab (ListTab name) and the project page open in it, if any (0.5.0). */
    var listTab: String by mutableStateOf("ALL")
    var projectSlug: String? by mutableStateOf(null)
    /** The open conversation's status line for the dock, and what a tap on a segment does (set by the chat page). */
    var statusLine: List<StatusSeg> by mutableStateOf(emptyList())
    var onStatus: (String) -> Unit = {}

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
     * open conversation. Any other tab, and the chat with nothing left to close, goes to [home] through [select], so
     * [canLeave] is asked (a refusal still consumes the back: the app must not close under a busy page). The chat's state is
     * only touched while the chat is the tab (final review B1-I5). False on [home]: the system's back then applies.
     */
    fun back(): Boolean {
        if (tab == ShellTab.CHAT) {
            // The list closes over an open conversation, and over the home page (the simple shell's chat with nothing open).
            if (listOpen && (openSessionId != null || !more)) {
                listOpen = false
                return true
            }
            listOpen = false
            if (openSessionId != null) {
                openSessionId = null
                return true
            }
        }
        if (tab == home) return false
        select(home)
        return true
    }
}
