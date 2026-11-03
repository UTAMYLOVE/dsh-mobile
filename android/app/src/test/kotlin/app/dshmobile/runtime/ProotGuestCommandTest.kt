package app.dshmobile.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse

/** Uses a shell script in place of `libproot.so`, so the real process plumbing runs without proot. */
class ProotGuestCommandTest {
    private val base: File = Files.createTempDirectory("guestcmd").toFile()
    private val nativeLib = File(base, "lib").also { it.mkdirs() }
    private val rootfs = File(base, "rootfs").also { it.mkdirs() }

    @After fun cleanup() { base.deleteRecursively() }

    private fun fakeProot(body: String) {
        val f = File(nativeLib, "libproot.so")
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(true)
    }

    private val marker = "dshm-guest-test-${System.nanoTime()}"
    private val errorLog = app.dshmobile.log.ErrorLog(base)

    private fun command(dns: List<String> = listOf("9.9.9.9")) =
        ProotGuestCommand(ProotRunner(nativeLib, rootfs, File(base, "tmp")), rootfs, { dns }, DesktopSignals.killer(listOf(marker)), errorLog)

    @Test fun runsArgsAfterEnvPrefixAndCapturesOutput() {
        fakeProot("""printf 'out:%s\n' "${'$'}@"; echo "HOME=${'$'}HOME PATH=${'$'}PATH fund=${'$'}npm_config_fund audit=${'$'}npm_config_audit upd=${'$'}npm_config_update_notifier"; echo oops >&2; exit 3""")
        val r = command().run(listOf("npm", "view", "x"), 10_000)
        assertEquals(3, r.exitCode)
        val lines = r.stdout.lines()
        val argv = lines.filter { it.startsWith("out:") }.takeLast(6)
        assertEquals(listOf("out:/usr/bin/env", "out:-u", "out:LD_LIBRARY_PATH", "out:npm", "out:view", "out:x"), argv)
        assertTrue(r.stdout, r.stdout.contains("HOME=/root PATH=/usr/local/bin:/usr/bin:/bin fund=false audit=false upd=false"))
        assertEquals("oops\n", r.stderr)
    }

    @Test fun writesGuestNetworkFilesBeforeEachRun() {
        fakeProot("exit 0")
        command(listOf("9.9.9.9")).run(listOf("true"), 10_000)
        assertEquals("nameserver 9.9.9.9\n", File(rootfs, "etc/resolv.conf").readText())
    }

    @Test fun timeoutKillsProcessAndReturnsMinusOne() {
        fakeProot("echo started; exec sleep 30")
        val t0 = System.nanoTime()
        val r = command().run(listOf("sleep"), 500)
        assertEquals(-1, r.exitCode)
        assertTrue("took too long", (System.nanoTime() - t0) / 1_000_000 < 10_000)
        assertTrue(r.stdout.contains("started"))
    }

    /** Like proot: SIGTERM is ignored and the command runs as a child. A timeout must still end the whole tree. */
    @Test fun timeoutEndsTheWholeTreeOfAProotThatIgnoresTerm() {
        fakeProot("trap '' TERM; sleep 60 & echo child=${'$'}!; wait")
        val r = command().run(listOf("npm", "install"), 1_000)
        assertEquals(-1, r.exitCode)
        val child = Regex("child=(\\d+)").find(r.stdout)!!.groupValues[1].toInt()
        val alive = ProcfsSource().list().any { it.pid == child && it.alive }
        assertFalse("the command's child outlived the timeout", alive)
        assertTrue(errorLog.file().readText(), errorLog.file().readText().contains("[guest] timed out after 1000 ms, stopped: found=2"))
    }

    @Test fun missingProotBinaryReturnsMinusOne() {
        val r = command().run(listOf("true"), 1_000)
        assertEquals(-1, r.exitCode)
    }

    @Test fun largeOutputDoesNotDeadlock() {
        fakeProot("""i=0; while [ ${'$'}i -lt 20000 ]; do echo "line ${'$'}i xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"; echo "err ${'$'}i xxxxxxxxxxxxxxxxxxxxxxxxxxxx" >&2; i=${'$'}((i+1)); done""")
        val r = command().run(listOf("x"), 30_000)
        assertEquals(0, r.exitCode)
        assertEquals(20000, r.stdout.lines().count { it.startsWith("line ") })
        assertEquals(20000, r.stderr.lines().count { it.startsWith("err ") })
    }
}
