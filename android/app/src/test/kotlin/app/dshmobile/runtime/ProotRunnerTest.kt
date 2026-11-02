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

    @Test fun baseEnvironmentIsSetAndCallerOverrides() {
        val env = runner.command(listOf("x"), mapOf("LANG" to "en_US.UTF-8", "B" to "2")).environment()
        assertEquals(tmp.path, env["PROOT_TMP_DIR"])
        assertEquals("/root", env["HOME"])
        assertEquals("/usr/local/bin:/usr/bin:/bin", env["PATH"])
        assertEquals(nativeLibDir.path, env["LD_LIBRARY_PATH"])
        assertEquals("en_US.UTF-8", env["LANG"])
        assertEquals("2", env["B"])
    }
}
