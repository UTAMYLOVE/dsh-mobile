package app.dshmobile.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/** Real signals on the phone: `kill(2)` through [Os.kill]. The kernel only lets an app signal its own uid's processes. */
object AndroidSignals : SignalSender {
    override fun send(pid: Int, sig: Sig) {
        val number = when (sig) {
            Sig.TERM -> OsConstants.SIGTERM
            Sig.QUIT -> OsConstants.SIGQUIT
            Sig.KILL -> OsConstants.SIGKILL
        }
        try {
            Os.kill(pid, number)
        } catch (_: ErrnoException) {
            // ESRCH: it ended meanwhile; EPERM: not ours (the killer never asks for that). Either way the /proc check decides.
        }
    }
}
