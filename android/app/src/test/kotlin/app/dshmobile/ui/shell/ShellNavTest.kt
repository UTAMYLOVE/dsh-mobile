package app.dshmobile.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavTest {
    /** With 「顯示更多功能」: every tab is in the dock and back ends on the overview. */
    private fun full() = ShellNav().apply { more = true; tab = ShellTab.OVERVIEW }

    /** 0.6.0: the app opens on the chat, the dock is the chat and the war room, and back ends on the chat. */
    @Test fun byDefaultTheShellIsTheChatAndTheWarRoom() {
        val nav = ShellNav()
        assertEquals(ShellTab.CHAT, nav.tab)
        assertEquals(listOf(ShellTab.CHAT, ShellTab.AGENTS), nav.tabs)
        assertFalse("nothing to close on the chat: the system's back applies", nav.back())
        nav.select(ShellTab.SETTINGS) // from the chat's menu
        assertTrue(nav.back())
        assertEquals(ShellTab.CHAT, nav.tab)
        nav.openSession("s1")
        nav.select(ShellTab.AGENTS)
        assertTrue(nav.back())
        assertEquals(ShellTab.CHAT, nav.tab)
        assertEquals("the conversation is still open", "s1", nav.openSessionId)
        nav.more = true
        assertEquals(ShellTab.entries, nav.tabs)
    }

    @Test fun backWalksListThenConversationThenTab() {
        val nav = full()
        nav.openSession("s1")
        nav.listOpen = true
        assertTrue(nav.back())
        assertFalse(nav.listOpen)
        assertTrue(nav.back())
        assertNull(nav.openSessionId)
        assertEquals(ShellTab.CHAT, nav.tab)
        assertTrue(nav.back())
        assertEquals(ShellTab.OVERVIEW, nav.tab)
        assertFalse(nav.back())
    }

    @Test fun aTabThatMayNotBeLeftStaysAndSaysWhy() {
        val nav = full()
        var busy = true
        var refusals = 0
        nav.canLeave = { from -> from != ShellTab.SETTINGS || !busy }
        nav.onLeaveRefused = { refusals++ }
        nav.select(ShellTab.SETTINGS)
        nav.select(ShellTab.CHAT)
        assertEquals(ShellTab.SETTINGS, nav.tab)
        assertEquals(1, refusals)
        nav.select(ShellTab.SETTINGS) // staying is not leaving
        assertEquals(1, refusals)
        busy = false
        nav.select(ShellTab.CHAT)
        assertEquals(ShellTab.CHAT, nav.tab)
    }

    /** Final review B1-I5: back on another tab goes to the overview and leaves the chat's hidden state alone. */
    @Test fun backOnAnotherTabLeavesTheHiddenChatAlone() {
        val nav = full()
        nav.openSession("s1")
        nav.listOpen = true
        nav.select(ShellTab.AGENTS)
        assertTrue(nav.back())
        assertEquals(ShellTab.OVERVIEW, nav.tab)
        assertEquals("s1", nav.openSessionId)
        assertTrue(nav.listOpen)
        assertFalse(nav.back())
    }

    @Test fun backAsksCanLeaveLikeATabTap() {
        val nav = full()
        var refusals = 0
        nav.canLeave = { from -> from != ShellTab.SETTINGS }
        nav.onLeaveRefused = { refusals++ }
        nav.select(ShellTab.SETTINGS)
        assertTrue("consumed: the app does not close under a busy settings page", nav.back())
        assertEquals(ShellTab.SETTINGS, nav.tab)
        assertEquals(1, refusals)
    }

    @Test fun backFromTheListWithNothingOpenGoesToTheOverview() {
        val nav = full()
        nav.select(ShellTab.CHAT)
        nav.listOpen = true
        assertTrue(nav.back())
        assertEquals(ShellTab.OVERVIEW, nav.tab)
        assertFalse(nav.listOpen)
    }
}
