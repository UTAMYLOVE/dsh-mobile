package app.dshmobile.runtime

import java.io.File
import java.io.IOException

/** One process as `/proc` shows it. [startTime] (field 22 of `stat`) tells a reused pid from the process we saw. */
data class ProcInfo(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val startTime: Long,
    /** `stat`'s state letter: `Z` (zombie) and `X` (dead) count as gone. */
    val state: Char,
    val cmdline: String,
) {
    val alive: Boolean get() = state != 'Z' && state != 'X'
}

/** Reads the process table. Production: [ProcfsSource] on `/proc`; tests hand in a fake one. */
fun interface ProcSource {
    fun list(): List<ProcInfo>
}

/**
 * `/proc` (or a directory laid out like it): every numeric directory with a readable `stat` and `status`. A process that ends
 * while it is read is skipped. Android hides other apps' processes from an app; the uid is still read and checked by the caller.
 */
class ProcfsSource(private val root: File = File("/proc")) : ProcSource {
    override fun list(): List<ProcInfo> {
        val dirs = root.listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir -> dir.name.toIntOrNull()?.let { read(it, dir) } }
    }

    private fun read(pid: Int, dir: File): ProcInfo? = try {
        val stat = parseStat(File(dir, "stat").readText())
        val uid = parseUid(File(dir, "status").readText())
        if (stat == null || uid == null) null
        else {
            val cmd = runCatching { File(dir, "cmdline").readBytes() }.getOrDefault(ByteArray(0))
            ProcInfo(pid, stat.ppid, uid, stat.startTime, stat.state, String(cmd, Charsets.UTF_8).replace('\u0000', ' ').trim())
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    internal data class Stat(val state: Char, val ppid: Int, val startTime: Long)

    companion object {
        /**
         * `pid (comm) state ppid ... starttime`: comm may hold spaces and parentheses, so the fields are counted from the last
         * `)`. After it come state (field 3), ppid (4) and, 19 further on, starttime (22).
         */
        internal fun parseStat(text: String): Stat? {
            val close = text.lastIndexOf(')')
            if (close < 0) return null
            val f = text.substring(close + 1).trim().split(' ').filter { it.isNotEmpty() }
            if (f.size < 20) return null
            val state = f[0].firstOrNull() ?: return null
            val ppid = f[1].toIntOrNull() ?: return null
            val start = f[19].toLongOrNull() ?: return null
            return Stat(state, ppid, start)
        }

        /** The real uid: the first number of `status`' `Uid:` line. */
        internal fun parseUid(text: String): Int? =
            text.lineSequence().firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()

        /** This process' own uid and pid, from `/proc/self` (the same on Android and on a desktop JVM). */
        fun self(root: File = File("/proc")): Pair<Int, Int>? = try {
            val status = File(root, "self/status").readText()
            val uid = parseUid(status)
            val pid = status.lineSequence().firstOrNull { it.startsWith("Pid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
            if (uid != null && pid != null) uid to pid else null
        } catch (_: IOException) {
            null
        }
    }
}

/** The three signals the stop uses, in the order it uses them. */
enum class Sig { TERM, QUIT, KILL }

/** Sends a signal to a pid. Production: `android.system.Os.kill`; tests record or fake it. Must never throw. */
fun interface SignalSender {
    fun send(pid: Int, sig: Sig)
}

/** A process seen alive at some point: pid and start time together, so a pid the kernel reused is never signalled. */
data class ProcId(val pid: Int, val startTime: Long)

/**
 * How a stop went. [survivors] is empty when the whole tree is gone; otherwise the stop failed and must be reported so. The
 * counts and timings are what the error log gets (no command lines: they could hold paths a user typed).
 */
data class StopReport(
    val found: Int,
    val termed: Int,
    val quit: Int,
    val killed: Int,
    val survivors: List<ProcId>,
    val termMs: Long,
    val quitMs: Long,
    val killMs: Long,
) {
    val stopped: Boolean get() = survivors.isEmpty()
    val totalMs: Long get() = termMs + quitMs + killMs

    /** One log line: counts and phase timings, never a command line. */
    fun describe(): String =
        "found=$found term=$termed quit=$quit kill=$killed left=${survivors.size} in ${totalMs} ms " +
            "(term ${termMs} ms, quit ${quitMs} ms, kill ${killMs} ms)" +
            if (survivors.isEmpty()) "" else " still alive: ${survivors.joinToString(",") { it.pid.toString() }}"

    companion object {
        val NOTHING = StopReport(0, 0, 0, 0, emptyList(), 0, 0, 0)
    }
}

/**
 * Stops a process tree for real (bug-writer-held): Android's `Process.destroy()` and `destroyForcibly()` both send SIGTERM, and
 * proot ignores SIGTERM, so the old dsh lived on and kept its sessions' locks. Here, for the tree under a root (proot):
 *
 * 1. SIGTERM to the root's children (node: dsh shuts down cleanly and lets go of its session locks), wait up to [termGraceMs];
 * 2. SIGQUIT to the root (proot's handler kills every tracee), wait up to [quitGraceMs];
 * 3. SIGKILL to everything of the tree still alive, wait up to [killGraceMs] until `/proc` shows none of it.
 *
 * Only processes of [myUid] are ever signalled, never [myPid] and never pid 1 or below; a pid is signalled only while its start
 * time is still the one first seen. [sweep] finds leftover dsh processes nobody tracks ([markers] in the command line) and stops
 * them the same way. Pids that belong to a command run right now ([track]) are never swept.
 */
class ProcessTreeKiller(
    private val procs: ProcSource,
    private val signals: SignalSender,
    private val myUid: Int,
    private val myPid: Int,
    /** Command-line pieces that make a process a stray dsh for [sweep] (production: `libproot.so` and dsh's `bin.js`). */
    private val markers: List<String>,
    private val termGraceMs: Long = 3_000,
    private val quitGraceMs: Long = 1_500,
    private val killGraceMs: Long = 2_000,
    private val pollMs: Long = 50,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val tracked = HashSet<Int>() // guarded by itself

    /**
     * Runs [start] and keeps the pid it returns out of every [sweep] until [untrack]: one atomic step with the sweep's scan, so
     * a command started at that moment is never taken for a stray.
     */
    fun <T> startTracked(start: () -> T, pidOf: (T) -> Int?): T = synchronized(tracked) {
        start().also { pidOf(it)?.let(tracked::add) }
    }

    fun untrack(pid: Int) {
        synchronized(tracked) { tracked.remove(pid) }
    }

    /** Stops [rootPid] and everything under it. [onProgress] gets how many of the tree are still alive after each step. */
    fun stopTree(rootPid: Int, onProgress: (alive: Int) -> Unit = {}): StopReport {
        val table = procs.list()
        val root = table.firstOrNull { it.pid == rootPid && mine(it) && it.alive } ?: return StopReport.NOTHING
        return stopRoots(listOf(root), emptyList(), table, onProgress)
    }

    /**
     * Stops every process of this uid whose command line holds one of the [markers] and that is not under a tracked pid or
     * [keep], plus whatever of [lingering] is still alive (a tree an earlier stop could not end).
     */
    fun sweep(keep: Set<Int> = emptySet(), lingering: Collection<ProcId> = emptyList(), onProgress: (alive: Int) -> Unit = {}): StopReport {
        val (table, roots, extra) = synchronized(tracked) {
            val table = procs.list()
            val protectedPids = descendants(table, tracked + keep)
            val strays = table.filter { mine(it) && it.alive && it.pid !in protectedPids && markers.any { m -> m in it.cmdline } }
            val strayPids = strays.mapTo(HashSet()) { it.pid }
            // A stray under another stray is stopped as part of that one's tree.
            val roots = strays.filter { it.ppid !in strayPids }
            val extra = lingering.mapNotNull { id -> table.firstOrNull { it.pid == id.pid && it.startTime == id.startTime && mine(it) && it.alive && it.pid !in protectedPids } }
            Triple(table, roots, extra)
        }
        if (roots.isEmpty() && extra.isEmpty()) return StopReport.NOTHING
        return stopRoots(roots, extra, table, onProgress)
    }

    private fun stopRoots(roots: List<ProcInfo>, extra: List<ProcInfo>, table: List<ProcInfo>, onProgress: (Int) -> Unit): StopReport {
        val members = LinkedHashMap<Int, ProcId>()
        fun record(t: List<ProcInfo>) {
            val live = roots.mapTo(HashSet()) { it.pid } + members.keys
            descendantsOf(t, live).forEach { p -> if (p.pid !in members) members[p.pid] = ProcId(p.pid, p.startTime) }
        }
        roots.forEach { members[it.pid] = ProcId(it.pid, it.startTime) }
        extra.forEach { members[it.pid] = ProcId(it.pid, it.startTime) }
        record(table)
        val found = members.size
        val rootIds = roots.map { ProcId(it.pid, it.startTime) }

        // 1. SIGTERM to the roots' own children: node under proot (dsh disposes its sessions and releases their locks).
        var t0 = clock()
        val children = table.filter { p -> roots.any { it.pid == p.ppid } && mine(p) && p.alive }.map { ProcId(it.pid, it.startTime) }
        val termed = children.count { signal(it, Sig.TERM) }
        if (termed > 0) waitGone(children, termGraceMs, onProgress, members)
        val termMs = clock() - t0

        // 2. SIGQUIT to the roots still alive (proot: its handler kills every tracee).
        t0 = clock()
        var snapshot = procs.list()
        record(snapshot)
        val liveRoots = rootIds.filter { aliveIn(snapshot, it) }
        val quit = liveRoots.count { signal(it, Sig.QUIT) }
        if (quit > 0) waitGone(members.values.toList(), quitGraceMs, onProgress, members)
        val quitMs = clock() - t0

        // 3. SIGKILL to anything of the tree that is still there.
        t0 = clock()
        snapshot = procs.list()
        record(snapshot)
        val left = members.values.filter { aliveIn(snapshot, it) }
        val killed = left.count { signal(it, Sig.KILL) }
        val survivors = if (left.isEmpty()) emptyList() else waitGone(members.values.toList(), killGraceMs, onProgress, members)
        val killMs = clock() - t0
        onProgress(survivors.size)
        return StopReport(found, termed, quit, killed, survivors, termMs, quitMs, killMs)
    }

    /** Polls until none of [ids] is alive or [timeoutMs] passed; returns the ones still alive. */
    private fun waitGone(ids: List<ProcId>, timeoutMs: Long, onProgress: (Int) -> Unit, members: Map<Int, ProcId>): List<ProcId> {
        val end = clock() + timeoutMs
        var lastCount = -1
        while (true) {
            val snapshot = procs.list()
            val alive = ids.filter { aliveIn(snapshot, it) }
            val treeAlive = members.values.count { aliveIn(snapshot, it) }
            if (treeAlive != lastCount) { onProgress(treeAlive); lastCount = treeAlive }
            if (alive.isEmpty() || clock() >= end) return alive
            sleep(pollMs)
        }
    }

    private fun signal(id: ProcId, sig: Sig): Boolean {
        if (id.pid <= 1 || id.pid == myPid) return false
        // Checked again right before the signal: the pid must still be ours and the same process.
        val now = procs.list().firstOrNull { it.pid == id.pid } ?: return false
        if (!mine(now) || now.startTime != id.startTime || !now.alive) return false
        signals.send(id.pid, sig)
        return true
    }

    private fun mine(p: ProcInfo) = p.uid == myUid && p.pid > 1 && p.pid != myPid

    private fun aliveIn(table: List<ProcInfo>, id: ProcId) = table.any { it.pid == id.pid && it.startTime == id.startTime && it.alive }

    /** [roots] and all their descendants (pids), through the ppid chain. */
    private fun descendants(table: List<ProcInfo>, roots: Set<Int>): Set<Int> {
        val out = HashSet(roots)
        var grew = true
        while (grew) {
            grew = false
            for (p in table) if (p.ppid in out && out.add(p.pid)) grew = true
        }
        return out
    }

    private fun descendantsOf(table: List<ProcInfo>, roots: Set<Int>): List<ProcInfo> {
        val pids = descendants(table, roots)
        return table.filter { it.pid in pids && it.pid !in roots && mine(it) && it.alive }
    }

    companion object {
        /** The pid of a [Process]: Android prints `Process[pid=N, hasExited=…]`, desktop Java `Process[pid=N, exitValue=…]`. */
        fun pidOf(process: Process): Int? {
            Regex("""pid=(\d+)""").find(process.toString())?.let { return it.groupValues[1].toIntOrNull() }
            return try {
                process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
            } catch (_: Exception) {
                null
            }
        }
    }
}
