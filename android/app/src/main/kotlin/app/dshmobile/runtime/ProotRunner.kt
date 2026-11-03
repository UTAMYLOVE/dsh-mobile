package app.dshmobile.runtime

import java.io.File

/**
 * Builds `ProcessBuilder`s that run a command inside the extracted rootfs via the bundled proot. [binds] (host directory to
 * guest path, see [AppPaths.binds]) are bound in with `-b host:guest`; both ends are created before every start.
 */
class ProotRunner(
    private val nativeLibDir: File,
    private val rootfsDir: File,
    private val tmpDir: File,
    private val binds: List<Pair<File, String>> = emptyList(),
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
}
