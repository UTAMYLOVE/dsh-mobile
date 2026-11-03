package app.dshmobile.runtime

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake process table with fake signals: each process dies on the signals it is told to die on (SIGKILL always, unless it is
 * [Fake.immortal]), and a proot-like one kills its whole tree on SIGQUIT. Time is a counter that [sleep] advances, so the
 * killer's waits run instantly and their bounds can be checked.
 */
class FakeProc(val myUid: Int = 10123, val myPid: Int = 4000) : ProcSource, SignalSender {
    class Fake(
        val pid: Int,
        val ppid: Int,
        val cmdline: String,
        val uid: Int,
        val startTime: Long,
        val diesOn: Set<Sig>,
        /** SIGQUIT kills its descendants, like proot's handler. */
        val prootLike: Boolean,
        val immortal: Boolean,
    ) {
        @Volatile var alive = true
    }

    val procs = CopyOnWriteArrayList<Fake>()
    val sent = CopyOnWriteArrayList<Pair<Int, Sig>>()
    var now = 0L

    fun add(
        pid: Int, ppid: Int, cmdline: String, uid: Int = myUid, startTime: Long = pid * 10L,
        diesOn: Set<Sig> = setOf(Sig.TERM, Sig.QUIT, Sig.KILL), prootLike: Boolean = false, immortal: Boolean = false,
    ): Fake = Fake(pid, ppid, cmdline, uid, startTime, diesOn, prootLike, immortal).also { procs += it }

    /** proot ignores SIGTERM (and SIGINT, SIGHUP); its SIGQUIT handler kills every tracee. */
    fun proot(pid: Int, ppid: Int = myPid, args: String = "/data/app/lib/libproot.so -r rootfs /usr/bin/env node /opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js --port 0") =
        add(pid, ppid, args, diesOn = setOf(Sig.QUIT, Sig.KILL), prootLike = true)

    fun node(pid: Int, ppid: Int, diesOn: Set<Sig> = setOf(Sig.TERM, Sig.QUIT, Sig.KILL)) =
        add(pid, ppid, "node /opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --port 0", diesOn = diesOn)

    override fun list(): List<ProcInfo> = procs.filter { it.alive }.map { ProcInfo(it.pid, it.ppid, it.uid, it.startTime, 'S', it.cmdline) }

    override fun send(pid: Int, sig: Sig) {
        sent += pid to sig
        val p = procs.firstOrNull { it.pid == pid && it.alive } ?: return
        if (p.immortal || sig !in p.diesOn) return
        if (p.prootLike && sig == Sig.QUIT) killTree(p.pid)
        p.alive = false
    }

    private fun killTree(pid: Int) {
        procs.filter { it.ppid == pid && it.alive && !it.immortal }.forEach { killTree(it.pid); it.alive = false }
    }

    fun alive(pid: Int) = procs.any { it.pid == pid && it.alive }

    fun killer(markers: List<String> = DSH_MARKERS) = ProcessTreeKiller(
        procs = this, signals = this, myUid = myUid, myPid = myPid, markers = markers,
        clock = { now }, sleep = { now += it },
    )

    companion object {
        val DSH_MARKERS = listOf("libproot.so", "@deepseek-ai/dsh/lib/bin.js")
    }
}
