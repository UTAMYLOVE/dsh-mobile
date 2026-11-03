package app.dshmobile.runtime

import java.io.File

/** Real signals for JVM tests on Linux (`kill -s`), where `android.system.Os` is not available. */
object DesktopSignals : SignalSender {
    override fun send(pid: Int, sig: Sig) {
        try {
            ProcessBuilder("kill", "-s", sig.name, pid.toString()).redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) {
        }
    }

    /** A [ProcessTreeKiller] on this machine's real /proc with short waits; [markers] should be unique to the test. */
    fun killer(markers: List<String>): ProcessTreeKiller {
        val (uid, pid) = ProcfsSource.self()!!
        return ProcessTreeKiller(ProcfsSource(File("/proc")), this, uid, pid, markers, termGraceMs = 1_000, quitGraceMs = 1_000, killGraceMs = 2_000)
    }
}
