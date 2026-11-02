package app.dshmobile.service

import app.dshmobile.config.DshPatch
import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.LocalGateway
import app.dshmobile.gateway.NotifyRequest
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.DshLaunchLine
import app.dshmobile.runtime.GuestNetworkFiles
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.security.KeyVault
import app.dshmobile.security.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val upstreams: Map<Provider, HttpUrl> = DEFAULT_UPSTREAMS,
    private val launchTimeoutMs: Long = 60_000,
    private val stopGraceMs: Long = 2_000,
) {
    private class Run(val process: Process, val gateway: LocalGateway, val patch: File) {
        @Volatile var info: RunningDsh? = null
        var tornDown = false // guarded by the run itself
        @Volatile var stopRequested = false
        val launched = CompletableDeferred<LaunchUrl>()
    }

    private val rootfs = File(filesDir, "rootfs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private val stateLock = Any()
    private var current: Run? = null
    private val exitListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val _isRunning = MutableStateFlow(false)

    val isRunning: StateFlow<Boolean> = _isRunning

    suspend fun ensureRunning(): RunningDsh = startLock.withLock {
        runningInfo()?.let { return it }
        synchronized(stateLock) { current }?.let { teardown(it) } // a dead leftover
        if (!isRootfsInstalled()) throw IllegalStateException("rootfs not installed")
        withContext(Dispatchers.IO) { start() }
    }

    /** Ends dsh and the gateway. Safe to call at any time, any number of times. */
    fun stop() {
        val run = synchronized(stateLock) { current } ?: return
        run.stopRequested = true
        teardown(run)
    }

    /** [listener] gets the exit code when dsh dies without [stop] having been called. Close the result to unregister. */
    fun onExit(listener: (exitCode: Int) -> Unit): AutoCloseable {
        exitListeners.add(listener)
        return AutoCloseable { exitListeners.remove(listener) }
    }

    private fun runningInfo(): RunningDsh? = synchronized(stateLock) { current }?.takeIf { it.process.isAlive }?.info

    private suspend fun start(): RunningDsh {
        File(filesDir, "rootfs.old").deleteRecursively()
        val token = SessionToken.generate()
        errorLog.addSecret(token.value)
        val gateway = LocalGateway(
            token = token, vault = vault, meter = meter, upstreams = upstreams,
            onNotify = onNotify, onCostAlert = onCostAlert, onRateGuard = onRateGuard,
        )
        val gatewayPort = gateway.start()
        var run: Run? = null
        try {
            val gatewayBase = "http://127.0.0.1:$gatewayPort/${token.value}"
            GuestNetworkFiles.write(rootfs, dnsServers())
            File(rootfs, "root/workspace").mkdirs()
            val patch = writePatch(DshPatch.render(gatewayBase))
            val process = launcher.start(guestEnv(gatewayBase), DSH_ARGS)
            run = Run(process, gateway, patch)
            synchronized(stateLock) { current = run }
            watch(run)

            val launch = withTimeoutOrNull(launchTimeoutMs) { run.launched.await() }
                ?: throw IllegalStateException("dsh did not print its launch line within $launchTimeoutMs ms")
            if (!healthCheck(launch)) throw IllegalStateException("dsh health check failed")
            val info = RunningDsh(launch.port, gatewayPort, token, launch)
            run.info = info
            _isRunning.value = true
            return info
        } catch (t: Throwable) {
            if (t !is CancellationException) errorLog.append("controller", "start failed: ${t.message}")
            if (run != null) {
                run.stopRequested = true
                teardown(run)
            } else {
                gateway.stop()
            }
            throw t
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
            val unexpected = !run.stopRequested && run.info != null
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

    private fun tearDownLocked(run: Run) {
        if (run.tornDown) return
        run.tornDown = true
        synchronized(stateLock) {
            if (current === run) {
                current = null
                _isRunning.value = false
            }
        }
        // An already dead process is left alone: destroy() would close its pipes before the readers have logged its last words.
        if (run.process.isAlive) {
            run.process.destroy()
            try {
                if (!run.process.waitFor(stopGraceMs, TimeUnit.MILLISECONDS)) {
                    run.process.destroyForcibly()
                    run.process.waitFor(stopGraceMs, TimeUnit.MILLISECONDS)
                }
            } catch (e: InterruptedException) {
                run.process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        run.gateway.stop()
        run.patch.delete() // holds the gateway URL with the session token
    }

    /** Owner-only, created fresh (an existing file or link at that path is removed, never written through). */
    private fun writePatch(yaml: String): File {
        val file = File(rootfs, "opt/dshm/dshm.cordis.yml")
        file.parentFile?.mkdirs()
        Files.deleteIfExists(file.toPath())
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(file.toPath(), yaml.toByteArray(Charsets.UTF_8))
        return file
    }

    private fun guestEnv(gatewayBase: String): Map<String, String> = linkedMapOf(
        "DSHM_GATEWAY" to gatewayBase,
        "DSHM_WORKSPACE" to "/root/workspace",
        "DSH_HOME" to "/root/.dsh",
        "DSH_TELEMETRY_MODE" to "DISABLED",
        "DEEPSEEK_API_KEY" to FAKE_KEY,
        "ANTHROPIC_API_KEY" to FAKE_KEY,
        "TZ" to "Asia/Taipei",
    )

    companion object {
        val DEFAULT_UPSTREAMS: Map<Provider, HttpUrl> = mapOf(
            Provider.DEEPSEEK to "https://api.deepseek.com/anthropic".toHttpUrl(),
            Provider.ANTHROPIC to "https://api.anthropic.com".toHttpUrl(),
        )

        /** Launcher flags (`--profile`, `--patch`) must come before the app flags (`--no-open`, `--host`, `--port`). */
        val DSH_ARGS = listOf(
            "/usr/bin/env", "-u", "LD_LIBRARY_PATH",
            "node", "/opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "--profile", "web", "--patch", "/opt/dshm/dshm.cordis.yml",
            "--no-open", "--host", "127.0.0.1", "--port", "0",
        )

        private const val FAKE_KEY = "dshm-proxy"
        private const val LOG_TAG = "dsh"
    }
}
