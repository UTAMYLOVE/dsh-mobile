package app.dshmobile.runtime

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

/** Production [GuestCommand]: one short-lived proot process per call. */
class ProotGuestCommand(
    private val runner: ProotRunner,
    private val rootfsDir: File,
    private val dnsServers: () -> List<String>,
) : GuestCommand {
    override fun run(args: List<String>, timeoutMs: Long): CommandResult {
        GuestNetworkFiles.write(rootfsDir, dnsServers())
        val process = try {
            runner.command(PREFIX + args, ENV).start()
        } catch (e: IOException) {
            return CommandResult(-1, "", "cannot start proot: ${e.message}")
        }
        try {
            process.outputStream.close() // the command never reads stdin
            val out = Collector(process.inputStream).also { it.start() }
            val err = Collector(process.errorStream).also { it.start() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                out.join(JOIN_MS); err.join(JOIN_MS)
                return CommandResult(-1, out.text(), err.text() + "\ntimed out after $timeoutMs ms")
            }
            out.join(JOIN_MS); err.join(JOIN_MS)
            return CommandResult(process.exitValue(), out.text(), err.text())
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return CommandResult(-1, "", "interrupted")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
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
