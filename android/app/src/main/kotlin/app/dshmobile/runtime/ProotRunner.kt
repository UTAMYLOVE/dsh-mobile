package app.dshmobile.runtime

import java.io.File

/**
 * Builds `ProcessBuilder`s that run a command inside the extracted rootfs via the bundled proot. [binds] (host directory to
 * guest path, see [AppPaths.binds]) are bound in with `-b host:guest`; both ends are created before every start.
 *
 * With [niceBinary] (Android's `/system/bin/nice`, when present) proot runs as `nice -n 10 proot ...`: nice execs proot, so the
 * process handle is still proot's, and proot with dsh and everything dsh starts inherit the lower CPU priority. The UI thread
 * and the WebView's renderer win when both want the CPU (the boss reported lag while scrolling and tapping).
 */
class ProotRunner(
    private val nativeLibDir: File,
    private val rootfsDir: File,
    private val tmpDir: File,
    private val binds: List<Pair<File, String>> = emptyList(),
    private val niceBinary: File? = niceIfUsable(File(SYSTEM_NICE)),
) {
    fun command(args: List<String>, env: Map<String, String>): ProcessBuilder {
        tmpDir.mkdirs()
        for ((host, guest) in binds) {
            host.mkdirs()
            // The guest end as an empty directory too, so the guest sees a normal directory there (and `-w` can enter it).
            if (rootfsDir.isDirectory) File(rootfsDir, guest.removePrefix("/")).mkdirs()
        }
        val proot = File(nativeLibDir, "libproot.so")
        val cmd = buildList {
            niceBinary?.let { add(it.path); add("-n"); add(NICENESS.toString()) }
            add(proot.path)
            add("--link2symlink")
            add("--kill-on-exit")
            add("-0")
            add("-r"); add(rootfsDir.path)
            add("-b"); add("/proc")
            add("-b"); add("/dev")
            add("-b"); add("/sys")
            for ((host, guest) in binds) { add("-b"); add("${host.path}:$guest") }
            add("-w"); add(AppPaths.GUEST_WORKSPACE)
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", File(nativeLibDir, "libproot-loader.so").path)
            put("LD_LIBRARY_PATH", nativeLibDir.path)
            put("PROOT_TMP_DIR", tmpDir.path)
            put("HOME", "/root")
            put("PATH", "/usr/local/bin:/usr/bin:/bin")
            put("LANG", "C.UTF-8")
            putAll(env)
        }
        return pb
    }

    companion object {
        const val SYSTEM_NICE = "/system/bin/nice"
        /** Lower priority than the app's own threads (0); 19 would starve dsh on a busy phone. */
        private const val NICENESS = 10
        internal fun niceIfUsable(f: File): File? = f.takeIf { it.isFile && it.canExecute() }
    }
}
