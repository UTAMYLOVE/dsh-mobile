package app.dshmobile.runtime

import java.io.File

/** Builds `ProcessBuilder`s that run a command inside the extracted rootfs via the bundled proot. */
class ProotRunner(
    private val nativeLibDir: File,
    private val rootfsDir: File,
    private val tmpDir: File,
) {
    fun command(args: List<String>, env: Map<String, String>): ProcessBuilder {
        tmpDir.mkdirs()
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
            add("-w"); add("/root/workspace")
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
