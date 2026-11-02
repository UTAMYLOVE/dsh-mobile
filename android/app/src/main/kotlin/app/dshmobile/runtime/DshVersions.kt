package app.dshmobile.runtime

import app.dshmobile.log.ErrorLog
import app.dshmobile.service.DshRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotLinkException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger

sealed interface UpdateOutcome {
    data class Updated(val version: String) : UpdateOutcome
    /** [version] is the one that failed; dsh runs (or was restarted) on the version it had before. */
    data class RolledBack(val version: String, val reason: String) : UpdateOutcome
    data class Failed(val reason: String) : UpdateOutcome
    data object UpToDate : UpdateOutcome
}

/**
 * dsh versions under host `rootfs/opt/dsh` (guest `/opt/dsh`): `<version>/` directories, an absolute-target
 * `current` symlink and a one-line `.previous` file. A new version is installed into `<version>.tmp` and renamed
 * into place only when complete, so a failed update leaves the running version untouched. At most two versions
 * (current and previous) are kept. Blocking calls (guest commands, file work) run on [Dispatchers.IO] when the
 * function is `suspend`; the non-suspend functions are quick file operations.
 */
class DshVersions(
    private val optDsh: File,
    private val cmd: GuestCommand,
    private val log: ErrorLog,
) {
    private val fsLock = Any()
    private val installMutex = Mutex()
    private val installing = AtomicInteger(0)

    /** The version `current` points at. Throws [IllegalStateException] when the link is missing or malformed. */
    fun current(): String = currentOrNull() ?: throw IllegalStateException("dsh has no current version link")

    /** The version a rollback goes back to, or null when there is none (or its directory is gone). */
    fun previous(): String? {
        val v = try {
            File(optDsh, PREVIOUS).readText(Charsets.UTF_8).trim()
        } catch (_: java.io.IOException) {
            return null
        }
        if (!isVersion(v) || v == currentOrNull() || !hasDir(v)) return null
        return v
    }

    /** Newest published version of `@deepseek-ai/dsh`. Throws when npm fails or prints something that is no version. */
    suspend fun latestAvailable(): String {
        val r = withContext(Dispatchers.IO) { cmd.run(listOf("npm", "view", PACKAGE, "version"), VIEW_TIMEOUT_MS) }
        val out = r.stdout.trim()
        if (r.exitCode != 0 || !isVersion(out)) {
            val msg = "npm view failed (exit ${r.exitCode}): ${tail(r.stderr.ifBlank { r.stdout })}"
            log.append(TAG, msg)
            throw IllegalStateException(msg)
        }
        return out
    }

    /** Installs [version] beside the others. Never touches the current version; leaves no `.tmp` behind on failure. */
    suspend fun install(version: String): Result<Unit> {
        if (!isVersion(version)) return Result.failure(IllegalArgumentException("invalid version"))
        return installMutex.withLock {
            installing.incrementAndGet()
            try {
                withContext(Dispatchers.IO) { installLocked(version) }
            } finally {
                installing.decrementAndGet()
            }
        }
    }

    private fun installLocked(version: String): Result<Unit> {
        if (File(optDsh, "$version/$MODULE_DIR").isDirectory) return Result.success(Unit)
        val tmp = File(optDsh, "$version.tmp")
        try {
            RootfsInstaller.deleteTree(tmp)
            val args = listOf(
                "npm", "install", "--prefix", "/opt/dsh/$version.tmp", "$PACKAGE@$version",
                "--no-audit", "--no-fund", "--loglevel=error",
            )
            val r = cmd.run(args, INSTALL_TIMEOUT_MS)
            if (r.exitCode != 0) throw IllegalStateException("npm install exited ${r.exitCode}: ${tail(r.stderr.ifBlank { r.stdout })}")
            val installed = readInstalledVersion(File(tmp, "$MODULE_DIR/package.json"))
            if (installed != version) throw IllegalStateException("installed version is $installed, expected $version")
            synchronized(fsLock) {
                val target = File(optDsh, version)
                RootfsInstaller.deleteTree(target) // a bare directory without the package: never a usable version
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.append(TAG, "install $version failed: ${e.message}")
            return Result.failure(e)
        } finally {
            try { RootfsInstaller.deleteTree(tmp) } catch (_: java.io.IOException) {}
        }
    }

    /**
     * Points `current` at [version] with one atomic rename, records the old version in `.previous` and deletes every
     * other version. Does nothing when [version] is already current. Throws [IllegalArgumentException] when
     * [version] is malformed or not installed.
     */
    fun switchTo(version: String) {
        requireVersion(version)
        synchronized(fsLock) {
            require(hasDir(version)) { "dsh $version is not installed" }
            val old = currentOrNull()
            if (old == version) return
            if (old != null) writeAtomic(File(optDsh, PREVIOUS), "$old\n")
            val tmpLink = File(optDsh, "current.tmp").toPath()
            Files.deleteIfExists(tmpLink)
            Files.createSymbolicLink(tmpLink, Path.of("/opt/dsh/$version"))
            Files.move(tmpLink, File(optDsh, CURRENT).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            cleanup()
        }
    }

    /** Goes back to the previous version (which makes the version just left the new previous). False when there is none. */
    fun rollback(): Boolean = synchronized(fsLock) {
        val prev = previous() ?: return false
        switchTo(prev)
        true
    }

    /**
     * Updates to the newest version and starts it; if it does not come up healthy, switches back to the old
     * version and starts that. [runtime]'s `ensureRunning` already includes the health check, so [health] is not
     * called again (kept for callers' compatibility). Cancellation during the switch puts the old version back
     * (dsh stays stopped) and rethrows.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun updateWithAutoRollback(runtime: DshRuntime, health: HealthCheck): UpdateOutcome {
        val latest = try {
            latestAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UpdateOutcome.Failed("cannot determine the latest version: ${e.message}")
        }
        val old = try {
            current()
        } catch (e: IllegalStateException) {
            return UpdateOutcome.Failed(e.message ?: "no current version")
        }
        if (latest == old) return UpdateOutcome.UpToDate
        install(latest).onFailure { return UpdateOutcome.Failed("install of $latest failed: ${it.message}") }

        var reason: String
        try {
            runtime.stop()
            withContext(Dispatchers.IO) { switchTo(latest) }
            runtime.ensureRunning()
            return UpdateOutcome.Updated(latest)
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                runtime.stop()
                try { switchTo(old) } catch (_: Exception) {}
            }
            throw e
        } catch (e: Exception) {
            reason = "dsh $latest did not start healthy: ${e.message}"
        }
        log.append(TAG, "$reason; rolling back to $old")
        try {
            runtime.stop()
            withContext(Dispatchers.IO) { switchTo(old) }
            runtime.ensureRunning()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reason += "; the previous version $old also failed to start: ${e.message}"
            log.append(TAG, "rollback to $old failed: ${e.message}")
        }
        return UpdateOutcome.RolledBack(latest, reason)
    }

    // ---------- helpers ----------

    private fun currentOrNull(): String? = try {
        Files.readSymbolicLink(File(optDsh, CURRENT).toPath()).fileName?.toString()?.takeIf(::isVersion)
    } catch (_: NoSuchFileException) {
        null
    } catch (_: NotLinkException) {
        null
    }

    private fun hasDir(version: String): Boolean =
        Files.isDirectory(File(optDsh, version).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun readInstalledVersion(pkg: File): String? = try {
        val obj = Json.parseToJsonElement(pkg.readText(Charsets.UTF_8)) as? JsonObject
        (obj?.get("version") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null // malformed JSON
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        Files.write(tmp.toPath(), text.toByteArray(Charsets.UTF_8))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Deletes every version except current and previous, and leftover temp entries. Caller holds [fsLock]. */
    private fun cleanup() {
        val keep = setOfNotNull(currentOrNull(), previous())
        val busy = installing.get() > 0
        for (name in optDsh.list().orEmpty()) {
            if (name in keep) continue
            val removable = when {
                name == "current.tmp" || name == "$PREVIOUS.tmp" -> true
                name.endsWith(".tmp") -> !busy && isVersion(name.removeSuffix(".tmp")) // a running install owns its tmp
                else -> isVersion(name)
            }
            if (!removable) continue
            try {
                RootfsInstaller.deleteTree(File(optDsh, name))
            } catch (e: java.io.IOException) {
                log.append(TAG, "cannot delete old version $name: ${e.message}") // leftovers are retried at the next switch
            }
        }
    }

    private fun requireVersion(v: String) = require(isVersion(v)) { "invalid dsh version" }

    private fun tail(s: String): String = s.trim().takeLast(MAX_LOG).ifEmpty { "(no output)" }

    private companion object {
        const val TAG = "dsh-versions"
        const val PACKAGE = "@deepseek-ai/dsh"
        const val MODULE_DIR = "node_modules/@deepseek-ai/dsh"
        const val CURRENT = "current"
        const val PREVIOUS = ".previous"
        const val VIEW_TIMEOUT_MS = 60_000L
        const val INSTALL_TIMEOUT_MS = 15 * 60_000L
        const val MAX_LOG = 2_000
        val VERSION = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$""")
        fun isVersion(v: String) = VERSION.matches(v)
    }
}
