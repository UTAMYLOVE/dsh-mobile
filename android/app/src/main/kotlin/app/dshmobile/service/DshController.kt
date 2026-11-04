package app.dshmobile.service

import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.LocalGateway
import app.dshmobile.gateway.NotifyRequest
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.AppPaths
import app.dshmobile.runtime.DshLaunchLine
import app.dshmobile.runtime.DshLayout
import app.dshmobile.runtime.GuestNetworkFiles
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.runtime.ProcId
import app.dshmobile.runtime.ProcessTreeKiller
import app.dshmobile.runtime.StopReport
import app.dshmobile.security.KeyVault
import app.dshmobile.security.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Starts `dsh` (inside proot) with the arguments and environment from [DshController]. Production: `ProotRunner`. */
fun interface DshLauncher {
    fun start(env: Map<String, String>, args: List<String>): Process
}

/** A usable dsh: its own port, the local gateway's port, the session token and the launch URL (with its login token). */
data class RunningDsh(val dshPort: Int, val gatewayPort: Int, val token: SessionToken, val launch: LaunchUrl)

/** What `DshVersions` needs from the controller when it swaps versions (lets tests substitute a fake). */
interface DshRuntime {
    /** Ends dsh's whole process tree; a tree that cannot be ended is logged and blocks the next start (see [DshController]). */
    fun stop()
    suspend fun ensureRunning(): RunningDsh

    /**
     * The hard stop behind 「強制結束 dsh 並重啟」: [stop], then every leftover dsh process of this app is ended too. True when
     * nothing of an old dsh is left.
     */
    fun forceStop(): Boolean {
        stop()
        return true
    }
}

/** [ensureRunning] found no installed rootfs. Not a crash: starting again cannot help until the rootfs is installed. */
class RootfsNotInstalledException : IllegalStateException("rootfs not installed")

/**
 * A [DshRuntime] that several parties share. Each party that needs dsh to stay up [retain]s it under its own name and
 * [release]s it when done; [stopIfUnretained] only stops dsh when nobody holds it, so a background job can never end
 * a dsh somebody else is using. [stop] stays the forced stop.
 */
interface DshHolderRuntime : DshRuntime {
    fun retain(owner: String)
    fun release(owner: String)
    fun stopIfUnretained(): Boolean
}

/**
 * Owns the dsh process and the local gateway for the whole app. [ensureRunning] starts everything exactly once
 * however many callers ask; [stop] tears it all down. The real API keys stay in [vault] and are only ever used by
 * the gateway: dsh gets the fake key `dshm-proxy`.
 */
class DshController(
    private val filesDir: File,
    private val isRootfsInstalled: () -> Boolean,
    private val launcher: DshLauncher,
    private val vault: KeyVault,
    private val meter: CostMeter,
    private val errorLog: ErrorLog,
    private val healthCheck: suspend (LaunchUrl) -> Boolean,
    private val dnsServers: () -> List<String>,
    private val onNotify: (NotifyRequest) -> Unit,
    private val onCostAlert: (usd: Double) -> Unit,
    private val onRateGuard: (Provider) -> Unit,
    /** The dsh patch (YAML) for a start, given the gateway base URL `http://127.0.0.1:<port>/<token>`. Production: `AgentPatchProvider`. */
    private val patchProvider: (gatewayBase: String) -> String,
    private val upstreams: Map<Provider, HttpUrl> = DEFAULT_UPSTREAMS,
    /**
     * Runs before every launch, after the guest network files are written (production: create the user-data directories that
     * proot binds in, and put a broken `/opt/dsh/current` back on the bundled version). A failure is a failed start.
     */
    private val beforeLaunch: () -> Unit = {},
    /**
     * Runs once per start, after the health check passed and before the dsh is published (production: register the workspace
     * in dsh, so the page shows it from its first load). It must not fail the start: anything it throws is logged and ignored.
     */
    private val afterHealthy: suspend (LaunchUrl) -> Unit = {},
    private val launchTimeoutMs: Long = 60_000,
    /**
     * Ends process trees for real (proot ignores the SIGTERM that `Process.destroy()` sends on Android) and sweeps leftover dsh
     * processes before every start. Production: signals through `android.system.Os`, `/proc` for the tree.
     */
    private val killer: ProcessTreeKiller,
    /** proot's pid from its [Process] (production: [ProcessTreeKiller.pidOf]). */
    private val pidOf: (Process) -> Int? = ProcessTreeKiller::pidOf,
    /** The layout of the next start (proot or the experimental native mode); read once per start and published as [DshLayout.active]. */
    private val layout: () -> DshLayout = { DshLayout.PROOT },
    private val clock: () -> Long = ::monotonicMs,
) : DshHolderRuntime {
    private class Run(val process: Process, val pid: Int?, val gateway: LocalGateway, val patch: File) {
        @Volatile var info: RunningDsh? = null
        var tornDown = false // guarded by the run itself
        @Volatile var stopRequested = false
        /** Completed when a stop begins: a start waiting on dsh (health check, workspace) gives up at once. */
        val stopSignal = CompletableDeferred<Unit>()
        /** The exit code once the process has ended (set by the exit watcher, under stateLock); null while it runs. */
        var exitCode: Int? = null
        val launched = CompletableDeferred<LaunchUrl>()
    }

    private val rootfs = File(filesDir, "rootfs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private val stateLock = Any()
    private var current: Run? = null
    private val holderSet = LinkedHashSet<String>() // guarded by stateLock
    private var starting = false // guarded by stateLock
    private var stopPending = false // a stop() that arrived while a start had not published its Run yet; guarded by stateLock
    private val exitListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val _isRunning = MutableStateFlow(false)
    /** Processes of an old dsh that a stop could not end; no start happens while one of them lives. Guarded by stateLock. */
    private val lingering = ArrayList<ProcId>()
    private val _status = MutableStateFlow(DshStatus(DshPhase.STOPPED, clock()))

    val isRunning: StateFlow<Boolean> = _isRunning

    /** The detailed phase (stage 2 §G): what dsh is doing, since when, what it waits on, and the last failure. */
    val status: StateFlow<DshStatus> = _status

    private fun setStatus(phase: DshPhase, port: Int? = null, remaining: Int = 0, failure: DshFailure? = _status.value.failure) {
        val leftover = synchronized(stateLock) { lingering.size }
        _status.value = DshStatus(phase, if (phase == _status.value.phase) _status.value.sinceMs else clock(), port, remaining, leftover, failure)
    }

    private fun updateRemaining(phase: DshPhase, remaining: Int) {
        val s = _status.value
        if (s.phase == phase) _status.value = s.copy(remaining = remaining)
    }

    /** Read-only snapshot of who currently holds dsh (see [retain]). */
    val holders: Set<String> get() = synchronized(stateLock) { holderSet.toSet() }

    override suspend fun ensureRunning(): RunningDsh = startLock.withLock {
        runningInfo()?.let { return it }
        synchronized(stateLock) { current }?.let { teardown(it) } // a dead leftover
        if (!isRootfsInstalled()) {
            setStatus(DshPhase.STOPPED, failure = DshFailure(DshPhase.STARTING, DshFailReason.ROOTFS_MISSING))
            throw RootfsNotInstalledException()
        }
        synchronized(stateLock) { starting = true; stopPending = false }
        try {
            withContext(Dispatchers.IO) {
                val t0 = clock()
                sweepBeforeStart()
                start(clock() - t0)
            }
        } finally {
            synchronized(stateLock) { starting = false; stopPending = false }
        }
    }

    /**
     * Ends every leftover dsh process this app did not start through a live run, plus what an earlier stop could not end. A
     * leftover that survives refuses the start ([DshStillRunningException]): a second dsh must never run on top of an old one,
     * which would still hold its sessions' locks (bug-writer-held).
     */
    private fun sweepBeforeStart() {
        val before = synchronized(stateLock) { lingering.toList() }
        setStatus(DshPhase.SWEEPING, remaining = before.size, failure = null)
        val report = killer.sweep(lingering = before) { updateRemaining(DshPhase.SWEEPING, it) }
        if (report.found > 0) errorLog.append("controller", "swept stray dsh: ${report.describe()}")
        synchronized(stateLock) {
            lingering.clear()
            lingering.addAll(report.survivors)
        }
        if (!report.stopped) {
            val n = report.survivors.size
            errorLog.append("controller", "start refused: $n old dsh processes are still alive")
            setStatus(DshPhase.STOPPED, failure = DshFailure(DshPhase.SWEEPING, DshFailReason.STILL_RUNNING, n))
            throw DshStillRunningException(n)
        }
    }

    override fun forceStop(): Boolean {
        stop()
        val before = synchronized(stateLock) { lingering.toList() }
        // A start in flight has its own run, which stop() ended; the sweep must not race a start that is about to launch.
        val report = killer.sweep(keep = synchronized(stateLock) { current }?.pid?.let(::setOf).orEmpty(), lingering = before) {
            updateRemaining(DshPhase.SWEEPING, it)
        }
        errorLog.append("controller", "force stop: ${report.describe()}")
        synchronized(stateLock) {
            lingering.clear()
            lingering.addAll(report.survivors)
        }
        val n = report.survivors.size
        if (_status.value.phase == DshPhase.STOPPED || n > 0) {
            setStatus(DshPhase.STOPPED, failure = if (n > 0) DshFailure(DshPhase.STOPPING, DshFailReason.STILL_RUNNING, n) else null)
        }
        return report.stopped
    }

    /** Ends dsh and the gateway, whoever holds it. Safe to call at any time, any number of times, also while a start is under way. */
    override fun stop() {
        val active = synchronized(stateLock) {
            val c = current
            if (c == null && starting) stopPending = true // start() sees this before it publishes anything
            c
        } ?: return
        active.stopRequested = true
        teardown(active)
    }

    /** Registers [owner] as needing dsh to keep running. Holding twice under one name is the same as once. Thread-safe. */
    override fun retain(owner: String) {
        synchronized(stateLock) { holderSet.add(owner) }
    }

    /** Drops [owner]'s hold. Releasing a name that holds nothing is harmless. Thread-safe. */
    override fun release(owner: String) {
        synchronized(stateLock) { holderSet.remove(owner) }
    }

    /**
     * Stops dsh only if nobody holds it; returns whether it did (true also when nothing was running). The decision and
     * the marking of the run as stopping are one atomic step, so a holder arriving later gets a fresh dsh from
     * [ensureRunning], never the one being shut down.
     */
    override fun stopIfUnretained(): Boolean {
        val run = synchronized(stateLock) {
            if (holderSet.isNotEmpty()) return false
            // A start still in flight must not publish a running dsh that nobody holds: it sees this and tears down.
            if (current == null && starting) stopPending = true
            current?.also { it.stopRequested = true }
        }
        if (run != null) teardown(run)
        return true
    }

    /** [listener] gets the exit code when dsh dies without [stop] having been called. Close the result to unregister. */
    fun onExit(listener: (exitCode: Int) -> Unit): AutoCloseable {
        exitListeners.add(listener)
        return AutoCloseable { exitListeners.remove(listener) }
    }

    private fun runningInfo(): RunningDsh? =
        synchronized(stateLock) { current }?.takeIf { it.process.isAlive && !it.stopRequested }?.info

    private suspend fun start(sweepMs: Long): RunningDsh {
        File(filesDir, "rootfs.old").deleteRecursively()
        val token = SessionToken.generate()
        errorLog.addSecret(token.value)
        val gateway = LocalGateway(
            token = token, vault = vault, meter = meter, upstreams = upstreams,
            onNotify = onNotify, onCostAlert = onCostAlert, onRateGuard = onRateGuard,
        )
        val gatewayPort = gateway.start()
        var run: Run? = null
        val patchFile = File(rootfs, PATCH_PATH)
        var phase = DshPhase.STARTING
        val t0 = clock()
        var tLaunch = 0L
        var tHealth = 0L
        try {
            setStatus(DshPhase.STARTING, failure = null)
            val gatewayBase = "http://127.0.0.1:$gatewayPort/${token.value}"
            GuestNetworkFiles.write(rootfs, dnsServers())
            beforeLaunch()
            val lay = layout()
            DshLayout.active = lay
            val patch = writePatch(patchProvider(gatewayBase))
            val process = try {
                launcher.start(guestEnv(gatewayBase, lay), lay.args)
            } catch (e: IOException) {
                throw LaunchFailed(e)
            }
            run = Run(process, pidOf(process), gateway, patch)
            val cancelled = synchronized(stateLock) {
                current = run
                stopPending.also { if (it) run.stopRequested = true }
            }
            if (cancelled) throw CancellationException("dsh start cancelled by stop()")
            watch(run)

            val launch = withTimeoutOrNull(launchTimeoutMs) { run.launched.await() }
                ?: throw LaunchTimeout(launchTimeoutMs)
            tLaunch = clock() - t0
            phase = DshPhase.HEALTH_CHECK
            setStatus(DshPhase.HEALTH_CHECK, port = launch.port)
            if (!untilStopped(run) { healthCheck(launch) }) throw HealthFailed()
            tHealth = clock() - t0 - tLaunch
            phase = DshPhase.REGISTERING
            setStatus(DshPhase.REGISTERING, port = launch.port)
            try {
                untilStopped(run) { afterHealthy(launch) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLog.append("controller", "after-start step failed (${e.javaClass.simpleName})")
            }
            val info = RunningDsh(launch.port, gatewayPort, token, launch)
            synchronized(stateLock) {
                // A stop() that raced with this start wins: nothing is published for a dsh somebody asked to end.
                if (stopPending || run.stopRequested) throw CancellationException("dsh start cancelled by stop()")
                // Died after its launch line while being checked: the exit watcher saw no published run, so the start reports it.
                run.exitCode?.let { throw ExitedEarly(it) }
                run.info = info
                _isRunning.value = true
            }
            val total = clock() - t0
            setStatus(DshPhase.RUNNING, port = launch.port, failure = null)
            errorLog.append(
                "controller",
                "dsh started in ${sweepMs + total} ms (sweep $sweepMs ms, launch line $tLaunch ms, health $tHealth ms, " +
                    "workspace ${total - tLaunch - tHealth} ms) pid=${run.pid ?: "?"}",
            )
            return info
        } catch (t: Throwable) {
            val stopped = run?.stopRequested == true
            val failure = if (t is CancellationException || stopped) null else failureOf(phase, t)
            if (failure != null) errorLog.append("controller", "start failed: ${t.message} (step ${failure.step}, after ${clock() - t0} ms)")
            if (run != null) {
                run.stopRequested = true
                teardown(run)
            } else {
                gateway.stop()
                patchFile.delete() // the launcher failed after the patch (with the session token in it) was written
            }
            setStatus(DshPhase.STOPPED, failure = failure ?: _status.value.failure)
            // stop() made the launch fail on purpose: report it as the cancellation it is, not as a crash.
            val original = (t as? LaunchFailed)?.cause ?: t
            throw if (stopped && t !is CancellationException) CancellationException("dsh start cancelled by stop()").also { it.initCause(original) } else original
        }
    }

    private class LaunchFailed(cause: IOException) : IOException(cause.message, cause)
    private class LaunchTimeout(val ms: Long) : IllegalStateException("dsh did not print its launch line within $ms ms")
    private class HealthFailed : IllegalStateException("dsh health check failed")
    private class ExitedEarly(val code: Int) : IllegalStateException("dsh exited with code $code before it was ready")

    private fun failureOf(phase: DshPhase, t: Throwable): DshFailure {
        val exited = (t as? ExitedEarly)?.code
            ?: Regex("""exited with code (-?\d+) before it was ready""").find(t.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        return when {
            t is LaunchFailed -> DshFailure(DshPhase.STARTING, DshFailReason.LAUNCH_ERROR)
            t is LaunchTimeout -> DshFailure(DshPhase.STARTING, DshFailReason.LAUNCH_TIMEOUT, (t.ms / 1000).toInt())
            t is HealthFailed -> DshFailure(DshPhase.HEALTH_CHECK, DshFailReason.HEALTH_FAILED)
            exited != null -> DshFailure(phase, DshFailReason.EXITED_EARLY, exited)
            else -> DshFailure(phase, DshFailReason.OTHER)
        }
    }

    /** Runs [block] (a wait on dsh) but gives up as soon as a stop of [run] begins: a force restart never waits it out. */
    private suspend fun <T> untilStopped(run: Run, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            run.stopSignal.await()
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (run.stopSignal.isCompleted) throw CancellationException("dsh start cancelled by stop()") else throw e
        } finally {
            watcher.cancel()
        }
    }

    /** Reads the process's output and reports its exit. Each of the three jobs blocks a thread of Dispatchers.IO. */
    private fun watch(run: Run) {
        scope.launch {
            readLines(run.process.inputStream) { line ->
                val launch = if (run.launched.isCompleted) null else DshLaunchLine.parse(line)
                if (launch != null) {
                    errorLog.addSecret(launch.url.substringAfter("token="))
                    run.launched.complete(launch) // the launch line carries the login token, so it is not logged
                } else {
                    errorLog.append(LOG_TAG, line)
                }
            }
        }
        scope.launch { readLines(run.process.errorStream) { errorLog.append(LOG_TAG, it) } }
        scope.launch {
            val code = run.process.waitFor()
            run.launched.completeExceptionally(IllegalStateException("dsh exited with code $code before it was ready"))
            // One atomic decision with start()'s publish: either the run was published (an unexpected exit, reported to the
            // listeners) or it was not (start() sees exitCode and fails). Never both, never neither.
            val unexpected = synchronized(stateLock) {
                run.exitCode = code
                !run.stopRequested && run.info != null
            }
            teardown(run)
            if (unexpected) {
                errorLog.append("controller", "dsh exited with code $code")
                exitListeners.forEach { runCatching { it(code) } }
            }
        }
    }

    /** Destroying the process closes its pipes under the reader, which then fails with an IOException: that is the normal end. */
    private fun readLines(stream: InputStream, onLine: (String) -> Unit) {
        try {
            stream.bufferedReader().useLines { it.forEach(onLine) }
        } catch (_: IOException) {
        }
    }

    /** Idempotent; a second caller waits until the first has finished, so after it returns everything is down. */
    private fun teardown(run: Run) {
        synchronized(run) { tearDownLocked(run) }
    }

    /**
     * Ends the run's whole process tree through [killer]: SIGTERM to node, SIGQUIT to proot, SIGKILL to what is left, bounded
     * (bug-writer-held: `destroy()` only sends SIGTERM, which proot ignores). A tree that survives is logged as a failed stop and
     * remembered: the next start sweeps it again and refuses to launch while it lives.
     */
    private fun tearDownLocked(run: Run) {
        if (run.tornDown) return
        run.tornDown = true
        if (run.stopRequested) run.stopSignal.complete(Unit)
        val wasCurrent = synchronized(stateLock) {
            (current === run).also {
                if (it) {
                    current = null
                    _isRunning.value = false
                }
            }
        }
        // An already dead process is left alone: signals would close its pipes before the readers have logged its last words.
        // Whatever it left behind (an orphaned node) is the next start's sweep's to end.
        if (run.process.isAlive) {
            if (wasCurrent) setStatus(DshPhase.STOPPING, failure = null)
            val report = stopTree(run)
            if (report.stopped) {
                errorLog.append("controller", "stopped dsh pid=${run.pid ?: "?"}: ${report.describe()}")
            } else {
                synchronized(stateLock) { report.survivors.forEach { if (it !in lingering) lingering.add(it) } }
                errorLog.append("controller", "stop FAILED pid=${run.pid ?: "?"}: ${report.describe()}")
            }
            try {
                run.process.waitFor(REAP_MS, TimeUnit.MILLISECONDS) // the JVM reaps it; its readers end with it
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (wasCurrent) {
                val n = report.survivors.size
                setStatus(DshPhase.STOPPED, failure = if (n > 0) DshFailure(DshPhase.STOPPING, DshFailReason.STILL_RUNNING, n) else null)
            }
        } else if (wasCurrent) {
            setStatus(DshPhase.STOPPED)
        }
        run.gateway.stop()
        run.patch.delete() // holds the gateway URL with the session token
    }

    private fun stopTree(run: Run): StopReport {
        val pid = run.pid
        if (pid != null) return killer.stopTree(pid) { updateRemaining(DshPhase.STOPPING, it) }
        // No pid (an unknown Process implementation): every dsh process of this app that no run of ours tracks goes.
        run.process.destroy()
        return killer.sweep { updateRemaining(DshPhase.STOPPING, it) }
    }

    /** Owner-only, created fresh (an existing file or link at that path is removed, never written through). */
    private fun writePatch(yaml: String): File {
        val file = File(rootfs, PATCH_PATH)
        file.parentFile?.mkdirs()
        Files.deleteIfExists(file.toPath())
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(file.toPath(), yaml.toByteArray(Charsets.UTF_8))
        return file
    }

    /**
     * The fake key only for providers that have a real key in the vault. pi-ai discovers the anthropic provider from
     * `ANTHROPIC_API_KEY` alone (with its default endpoint, api.anthropic.com), so without a Claude key the variable stays out.
     */
    private fun guestEnv(gatewayBase: String, lay: DshLayout): Map<String, String> = linkedMapOf(
        "DSHM_GATEWAY" to gatewayBase,
        "DSHM_WORKSPACE" to lay.workspace,
        "DSH_HOME" to lay.dshHome,
        "DSH_TELEMETRY_MODE" to "DISABLED",
        "DEEPSEEK_API_KEY" to FAKE_KEY,
    ).also { env ->
        if (vault.get(Provider.ANTHROPIC) != null) env["ANTHROPIC_API_KEY"] = FAKE_KEY
        env["TZ"] = "Asia/Taipei"
    }

    companion object {
        val DEFAULT_UPSTREAMS: Map<Provider, HttpUrl> = mapOf(
            Provider.DEEPSEEK to "https://api.deepseek.com/anthropic".toHttpUrl(),
            Provider.ANTHROPIC to "https://api.anthropic.com".toHttpUrl(),
        )

        /** Launcher flags (`--profile`, `--patch`) must come before the app flags (`--no-open`, `--host`, `--port`). */
        val DSH_ARGS = DshLayout.PROOT.args

        /** What makes a process a leftover dsh for the sweep: proot (`libproot.so`), native node (`libdshn_`) and dsh's own entry point. */
        val SWEEP_MARKERS = listOf("libproot.so", "libdshn_bin_node.so", "@deepseek-ai/dsh/lib/bin.js")

        private const val REAP_MS = 1_000L
        private const val PATCH_PATH = "opt/dshm/dshm.cordis.yml"
        private const val FAKE_KEY = "dshm-proxy"
        private const val LOG_TAG = "dsh"
    }
}
