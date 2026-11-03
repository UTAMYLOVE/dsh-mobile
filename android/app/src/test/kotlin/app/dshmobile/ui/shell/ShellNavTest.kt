package app.dshmobile.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavTest {
    @Test fun backWalksListThenConversationThenTab() {
        val nav = ShellNav()
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
        val nav = ShellNav()
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
        val nav = ShellNav()
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
        val nav = ShellNav()
        var refusals = 0
        nav.canLeave = { from -> from != ShellTab.SETTINGS }
        nav.onLeaveRefused = { refusals++ }
        nav.select(ShellTab.SETTINGS)
        assertTrue("consumed: the app does not close under a busy settings page", nav.back())
        assertEquals(ShellTab.SETTINGS, nav.tab)
        assertEquals(1, refusals)
    }

    @Test fun backFromTheListWithNothingOpenGoesToTheOverview() {
        val nav = ShellNav()
        nav.select(ShellTab.CHAT)
        nav.listOpen = true
        assertTrue(nav.back())
        assertEquals(ShellTab.OVERVIEW, nav.tab)
        assertFalse(nav.listOpen)
    }
}
