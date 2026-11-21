package app.dshmobile.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavCoachTest {
    @Test fun theDefaultsAreTodays() {
        val n = ShellNav()
        assertFalse(n.coach)
        assertFalse(n.coachHome)
        assertFalse(n.coachSettings)
        assertEquals(ShellTab.CHAT, n.tab)
        assertFalse(n.back())
    }

    @Test fun backFromTheChatHomeReturnsToTheCoach() {
        val n = ShellNav().apply { coachHome = true; coach = true }
        assertFalse(n.back()) // on the coach, back leaves the app
        n.select(ShellTab.CHAT) // 自由聊天
        assertFalse(n.coach)
        assertTrue(n.back())
        assertTrue(n.coach)
    }

    @Test fun openingAConversationOrATabLeavesTheCoach() {
        val n = ShellNav().apply { coachHome = true; coach = true }
        n.openSession("s1")
        assertFalse(n.coach)
        assertTrue(n.back()) // closes the conversation
        assertNull(n.openSessionId)
        assertTrue(n.back()) // then the coach
        assertTrue(n.coach)
        n.select(ShellTab.AGENTS)
        assertFalse(n.coach)
        n.openCoach()
        assertTrue(n.coach)
        assertEquals(ShellTab.CHAT, n.tab)
    }

    @Test fun theCoachsSettingsCloseFirst() {
        val n = ShellNav().apply { coachHome = true; coach = true; coachSettings = true }
        assertTrue(n.back())
        assertFalse(n.coachSettings)
        assertTrue(n.coach)
    }

    @Test fun withoutCoachHomeBackStaysAsToday() {
        val n = ShellNav()
        n.select(ShellTab.AGENTS)
        assertTrue(n.back())
        assertEquals(ShellTab.CHAT, n.tab)
        assertFalse(n.back())
        assertFalse(n.coach)
    }
}
