package app.dshmobile.ui.shell

import app.dshmobile.remote.MuxState
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.security.SessionToken
import app.dshmobile.service.RunningDsh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSessionHolderTest {
    private class FakeRemote : ShellRemote {
        override val muxState: StateFlow<MuxState> = MutableStateFlow(MuxState.CONNECTING)
        var closes = 0
        override fun close() { closes++ }
    }

    private val made = mutableListOf<Pair<RunningDsh, FakeRemote>>()
    private val holder = ShellSessionHolder { running ->
        val remote = FakeRemote()
        made += running to remote
        ShellSession(remote, events = null, scope = CoroutineScope(Job()))
    }

    private fun running(port: Int) = RunningDsh(port, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:$port/?token=t$port", port))

    @Test fun sameLaunchUrlReturnsTheSameSession() {
        assertNull(holder.current.value)
        val a = holder.attach(running(1000))
        val again = holder.attach(running(1000))
        assertSame(a, again)
        assertSame(a, holder.current.value)
        assertEquals(1, made.size)
        assertEquals(0, made.single().second.closes)
        assertEquals("http://127.0.0.1:1000/?token=t1000", holder.launch?.url)
    }

    @Test fun aNewLaunchUrlClosesTheOldSession() {
        val a = holder.attach(running(1000))
        val b = holder.attach(running(1001))
        assertTrue(a !== b)
        assertSame(b, holder.current.value)
        assertEquals(2, made.size)
        assertEquals("the old remote is closed", 1, made[0].second.closes)
        assertFalse("and its scope ends with it", a.scope.isActive)
        assertEquals(0, made[1].second.closes)
        assertTrue(b.scope.isActive)
        assertEquals(1001, holder.launch?.port)
    }

    @Test fun detachClosesAndClears() {
        val a = holder.attach(running(1000))
        holder.detach()
        assertNull(holder.current.value)
        assertNull(holder.launch)
        assertEquals(1, made.single().second.closes)
        assertFalse(a.scope.isActive)
        holder.detach() // nothing attached: nothing happens
        a.close() // closing twice closes the remote once
        assertEquals(1, made.single().second.closes)
        // The same dsh attached again after a detach gets a fresh session.
        val b = holder.attach(running(1000))
        assertTrue(a !== b)
        assertEquals(2, made.size)
    }
}
