package app.dshmobile.runtime

import app.dshmobile.log.ErrorLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs a command inside the guest and waits for it. Blocking: call from a background thread. */
fun interface GuestCommand {
    /** [CommandResult.exitCode] is -1 when [timeoutMs] ran out (the process is killed) or the command could not start. */
    fun run(args: List<String>, timeoutMs: Long): CommandResult
}

/**
 * Production [GuestCommand]: one short-lived proot process per call. A timeout (or an interrupt) ends its whole process tree
 * through [killer] (proot ignores the SIGTERM that `destroy()` sends on Android); while it runs, its pid is tracked so the
 * controller's sweep of leftover proot processes never takes it for one.
 */
class ProotGuestCommand(
    private val runner: ProotRunner,
    private val rootfsDir: File,
    private val dnsServers: () -> List<String>,
    private val killer: ProcessTreeKiller,
    private val errorLog: ErrorLog,
) : GuestCommand {
    override fun run(args: List<String>, timeoutMs: Long): CommandResult {
        GuestNetworkFiles.write(rootfsDir, dnsServers())
        val process = try {
            killer.startTracked({ runner.command(PREFIX + args, ENV).start() }, ProcessTreeKiller::pidOf)
        } catch (e: IOException) {
            return CommandResult(-1, "", "cannot start proot: ${e.message}")
        }
        val pid = ProcessTreeKiller.pidOf(process)
        try {
            process.outputStream.close() // the command never reads stdin
            val out = Collector(process.inputStream).also { it.start() }
            val err = Collector(process.errorStream).also { it.start() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                val report = endTree(process, pid)
                errorLog.append(TAG, "timed out after $timeoutMs ms, stopped: ${report.describe()}")
                process.waitFor(REAP_MS, TimeUnit.MILLISECONDS)
                out.join(JOIN_MS); err.join(JOIN_MS)
                return CommandResult(-1, out.text(), err.text() + "\ntimed out after $timeoutMs ms")
            }
            out.join(JOIN_MS); err.join(JOIN_MS)
            return CommandResult(process.exitValue(), out.text(), err.text())
        } catch (e: InterruptedException) {
            val report = endTree(process, pid)
            errorLog.append(TAG, "interrupted, stopped: ${report.describe()}")
            Thread.currentThread().interrupt()
            return CommandResult(-1, "", "interrupted")
        } finally {
            if (process.isAlive) endTree(process, pid)
            pid?.let(killer::untrack)
        }
    }

    private fun endTree(process: Process, pid: Int?): StopReport {
        if (pid != null) return killer.stopTree(pid)
        process.destroyForcibly()
        return StopReport.NOTHING
    }

    /** Drains a stream on its own thread so a chatty command can never block on a full pipe. */
    private class Collector(private val stream: InputStream) : Thread("guest-cmd-reader") {
        private val buf = ByteArrayOutputStream()
        init { isDaemon = true }
        override fun run() {
            try {
                val chunk = ByteArray(8192)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    synchronized(buf) { buf.write(chunk, 0, n) }
                }
            } catch (_: IOException) {
                // killed process: whatever was read so far is all there is
            }
        }
        fun text(): String = synchronized(buf) { buf.toString(Charsets.UTF_8) }
    }

    private companion object {
        const val JOIN_MS = 2_000L
        const val REAP_MS = 2_000L
        const val TAG = "guest"
        val PREFIX = listOf("/usr/bin/env", "-u", "LD_LIBRARY_PATH")
        val ENV = mapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
            "npm_config_update_notifier" to "false",
            "npm_config_fund" to "false",
            "npm_config_audit" to "false",
        )
    }
}
