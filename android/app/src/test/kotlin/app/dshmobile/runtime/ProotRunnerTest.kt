package app.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotRunnerTest {
    private val nativeLibDir = File("/native/lib")
    private val rootfsDir = File("/data/files/rootfs")
    private val tmp = File(System.getProperty("java.io.tmpdir"), "dshm-proot-test")
    private val runner = ProotRunner(nativeLibDir, rootfsDir, tmp)

    @Test fun commandUsesBundledProotAndLoader() {
        val pb = runner.command(listOf("node", "-v"), mapOf("A" to "1"))
        assertEquals(File(nativeLibDir, "libproot.so").path, pb.command()[0])
        assertEquals(File(nativeLibDir, "libproot-loader.so").path, pb.environment()["PROOT_LOADER"])
        assertTrue(pb.command().containsAll(listOf("-r", rootfsDir.path, "-0", "-w", "/root/workspace")))
        assertEquals("1", pb.environment()["A"])
    }

    @Test fun commandBindsSystemDirsAndAppendsArgsLast() {
        val cmd = runner.command(listOf("node", "-v"), emptyMap()).command()
        assertTrue(cmd.contains("--link2symlink"))
        for (d in listOf("/proc", "/dev", "/sys")) assertEquals("-b", cmd[cmd.indexOf(d) - 1])
        assertEquals(listOf("node", "-v"), cmd.takeLast(2))
    }

    /** User data lives outside the replaceable rootfs and is bound in at the guest paths dsh and the plugins use. */
    @Test fun userDataIsBoundIntoTheGuestAndBothEndsExist() {
        val base = java.nio.file.Files.createTempDirectory("proot-binds").toFile()
        try {
            val paths = AppPaths(File(base, "files"))
            paths.rootfs.mkdirs()
            val cmd = ProotRunner(nativeLibDir, paths.rootfs, tmp, paths.binds).command(listOf("x"), emptyMap()).command()
            for ((host, guest) in paths.binds) {
                val i = cmd.indexOf("${host.path}:$guest")
                assertTrue("missing bind for $guest in $cmd", i > 0 && cmd[i - 1] == "-b")
                assertTrue(host.isDirectory)
                assertTrue(File(paths.rootfs, guest.removePrefix("/")).isDirectory)
            }
            assertTrue("binds come before the command", cmd.indexOf("${paths.dshHome.path}:/root/.dsh") < cmd.lastIndexOf("x"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun baseEnvironmentIsSetAndCallerOverrides() {
        val env = runner.command(listOf("x"), mapOf("LANG" to "en_US.UTF-8", "B" to "2")).environment()
        assertEquals(tmp.path, env["PROOT_TMP_DIR"])
        assertEquals("/root", env["HOME"])
        assertEquals("/usr/local/bin:/usr/bin:/bin", env["PATH"])
        assertEquals(nativeLibDir.path, env["LD_LIBRARY_PATH"])
        assertEquals("en_US.UTF-8", env["LANG"])
        assertEquals("2", env["B"])
    }

    /** Follow-up 1 (lag): dsh and everything it starts run at a lower CPU priority than the UI, when Android's nice exists. */
    @Test fun withNiceTheWholeGuestRunsAtNiceTen() {
        val nice = java.nio.file.Files.createTempFile("nice", "").toFile().apply { setExecutable(true) }
        try {
            val withNice = ProotRunner(nativeLibDir, rootfsDir, tmp, niceBinary = nice).command(listOf("node", "-v"), emptyMap()).command()
            val plain = ProotRunner(nativeLibDir, rootfsDir, tmp, niceBinary = null).command(listOf("node", "-v"), emptyMap()).command()
            assertEquals(listOf(nice.path, "-n", "10"), withNice.take(3))
            assertEquals(plain, withNice.drop(3)) // nice execs proot with exactly the same arguments
            assertEquals(File(nativeLibDir, "libproot.so").path, plain[0])
        } finally {
            nice.delete()
        }
    }

    @Test fun niceIsOnlyUsedWhenItExistsAndCanRun() {
        assertEquals(null, ProotRunner.niceIfUsable(File("/no/such/dir/nice")))
        val notExecutable = java.nio.file.Files.createTempFile("nice", "").toFile().apply { setExecutable(false) }
        val executable = java.nio.file.Files.createTempFile("nice", "").toFile().apply { setExecutable(true) }
        try {
            assertEquals(null, ProotRunner.niceIfUsable(notExecutable))
            assertEquals(executable, ProotRunner.niceIfUsable(executable))
            assertEquals("/system/bin/nice", ProotRunner.SYSTEM_NICE)
        } finally {
            notExecutable.delete()
            executable.delete()
        }
    }
}
