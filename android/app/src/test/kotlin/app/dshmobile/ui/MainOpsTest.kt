package app.dshmobile.ui

import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.security.SessionToken
import app.dshmobile.service.DshHolderRuntime
import app.dshmobile.service.RunningDsh
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MainOpsTest {
    private val dir: File = Files.createTempDirectory("mainops").toFile()
    private val log = ErrorLog(dir)
    private val events = mutableListOf<String>()
    private val running = RunningDsh(1, 2, SessionToken.generate(), LaunchUrl("http://127.0.0.1:1/?token=x", 1))
    private var previous: String? = "0.2.0"
    private var rollbackResult = true
    private var startError: Exception? = null

    private val runtime = object : DshHolderRuntime {
        override fun retain(owner: String) = Unit
        override fun release(owner: String) = Unit
        override fun stopIfUnretained() = true
        override fun stop() { events += "stop" }
        override suspend fun ensureRunning(): RunningDsh {
            events += "ensureRunning"
            startError?.let { throw it }
            return running
        }
    }

    private val ops = MainOps(runtime, previousVersion = { previous }, rollbackVersion = { events += "rollback"; rollbackResult }, errorLog = log)

    @After fun cleanup() { dir.deleteRecursively() }

    private fun logText() = log.file().takeIf { it.exists() }?.readText().orEmpty()

    @Test fun rollbackStopsSwitchesAndRestarts() = runBlocking {
        val r = ops.rollback()
        assertEquals(listOf("stop", "rollback", "ensureRunning"), events)
        assertEquals(MainOps.RollbackOutcome.RolledBack("0.2.0", running), r)
    }

    @Test fun rollbackWithoutAPreviousVersionTouchesNothing() = runBlocking {
        previous = null
        assertEquals(MainOps.RollbackOutcome.NoPrevious, ops.rollback())
        assertTrue(events.isEmpty())
    }

    @Test fun rollbackThatCannotSwitchStillRestartsTheCurrentVersion() = runBlocking {
        rollbackResult = false
        val r = ops.rollback()
        assertEquals(listOf("stop", "rollback", "ensureRunning"), events)
        assertTrue(r is MainOps.RollbackOutcome.Failed)
    }

    @Test fun rollbackWhoseRestartFailsIsReportedAndLogged() = runBlocking {
        startError = IllegalStateException("dsh health check failed")
        val r = ops.rollback()
        assertTrue(r is MainOps.RollbackOutcome.Failed)
        assertTrue(logText().contains("dsh health check failed"))
    }

    @Test fun cancellationIsNotSwallowed() {
        startError = kotlinx.coroutines.CancellationException("bye")
        try {
            runBlocking { ops.rollback() }
            org.junit.Assert.fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    @Test fun nothingIsLoggedOnItsOwn() {
        assertFalse(log.file().exists() && log.file().length() > 0)
    }
}
