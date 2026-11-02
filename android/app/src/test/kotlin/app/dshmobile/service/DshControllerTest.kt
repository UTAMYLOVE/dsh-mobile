package app.dshmobile.service

import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.security.KeyVault
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DshControllerTest {
    private class FakeVault : KeyVault {
        private val keys = mapOf(Provider.DEEPSEEK to "sk-real-deepseek", Provider.ANTHROPIC to "sk-real-anthropic")
        override fun get(p: Provider): String? = keys[p]
        override fun put(p: Provider, key: String) = Unit
        override fun clear(p: Provider) = Unit
    }

    private val filesDir: File = Files.createTempDirectory("dshctl").toFile()
    private val rootfs = File(filesDir, "rootfs").also { File(it, "etc").mkdirs() }
    private val errorLog = ErrorLog(filesDir)
    private val starts = AtomicInteger()
    private val envs = CopyOnWriteArrayList<Map<String, String>>()
    private val argLists = CopyOnWriteArrayList<List<String>>()
    private val processes = CopyOnWriteArrayList<Process>()
    private var installed = true
    private var script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 30"""
    private var healthy = true
    private val healthChecked = CopyOnWriteArrayList<LaunchUrl>()
    private var launchTimeoutMs = 10_000L

    private fun controller() = DshController(
        filesDir = filesDir,
        isRootfsInstalled = { installed },
        launcher = { env, args ->
            starts.incrementAndGet()
            envs.add(env)
            argLists.add(args)
            ProcessBuilder("sh", "-c", script).start().also { processes.add(it) }
        },
        vault = FakeVault(),
        meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
        errorLog = errorLog,
        healthCheck = { launch -> healthChecked.add(launch); healthy },
        dnsServers = { listOf("9.9.9.9") },
        onNotify = {},
        onCostAlert = {},
        onRateGuard = {},
        launchTimeoutMs = launchTimeoutMs,
        stopGraceMs = 500,
    )

    private val controllers = mutableListOf<DshController>()
    private fun newController() = controller().also { controllers.add(it) }

    @After fun cleanup() {
        controllers.forEach { it.stop() }
        processes.forEach { it.destroyForcibly() }
        filesDir.deleteRecursively()
    }

    private fun gatewayPortOf(env: Map<String, String>) = Regex("""127\.0\.0\.1:(\d+)/""").find(env.getValue("DSHM_GATEWAY"))!!.groupValues[1].toInt()

    private fun portIsOpen(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
    } catch (_: Exception) { false }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("condition not met in time", cond())
    }

    @Test fun concurrentEnsureRunningStartsDshOnce() = runBlocking {
        val c = newController()
        val results = (1..5).map { async { c.ensureRunning() } }.awaitAll()
        assertEquals(1, starts.get())
        assertEquals(1, results.map { it.token.value }.toSet().size)
        assertTrue(c.isRunning.value)
        assertEquals(1, healthChecked.size)
        // A later call just returns the running instance.
        assertEquals(results[0].dshPort, c.ensureRunning().dshPort)
        assertEquals(1, starts.get())
    }

    @Test fun reportsPortsTokenAndLaunchUrlFromTheLaunchLine() = runBlocking {
        val running = newController().ensureRunning()
        assertEquals(45678, running.dshPort)
        assertEquals(LaunchUrl("http://127.0.0.1:45678/?token=abc", 45678), running.launch)
        assertEquals(running.launch, healthChecked.single())
        assertEquals(gatewayPortOf(envs.single()), running.gatewayPort)
        assertEquals(64, running.token.value.length)
    }

    @Test fun environmentIsExactlyTheSevenVariablesAndHasNoRealKey() = runBlocking {
        val running = newController().ensureRunning()
        val env = envs.single()
        assertEquals(
            mapOf(
                "DSHM_GATEWAY" to "http://127.0.0.1:${running.gatewayPort}/${running.token.value}",
                "DSHM_WORKSPACE" to "/root/workspace",
                "DSH_HOME" to "/root/.dsh",
                "DSH_TELEMETRY_MODE" to "DISABLED",
                "DEEPSEEK_API_KEY" to "dshm-proxy",
                "ANTHROPIC_API_KEY" to "dshm-proxy",
                "TZ" to "Asia/Taipei",
            ),
            env,
        )
        assertTrue(env.values.none { it.contains("sk-real") })
    }

    @Test fun launcherFlagsComeBeforeAppFlags() = runBlocking {
        newController().ensureRunning()
        assertEquals(
            listOf(
                "/usr/bin/env", "-u", "LD_LIBRARY_PATH",
                "node", "/opt/dsh/current/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "--profile", "web", "--patch", "/opt/dshm/dshm.cordis.yml",
                "--no-open", "--host", "127.0.0.1", "--port", "0",
            ),
            argLists.single(),
        )
    }

    @Test fun writesOwnerOnlyPatchWithGatewayUrlsAndNoKey() = runBlocking {
        val running = newController().ensureRunning()
        val patch = File(rootfs, "opt/dshm/dshm.cordis.yml")
        val text = patch.readText()
        assertTrue(text.contains("http://127.0.0.1:${running.gatewayPort}/${running.token.value}/deepseek"))
        assertFalse(text.contains("sk-real"))
        val perms = Files.getPosixFilePermissions(patch.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test fun writesGuestNetworkFilesAndCreatesGuestDirs() = runBlocking {
        newController().ensureRunning()
        assertEquals("nameserver 9.9.9.9\n", File(rootfs, "etc/resolv.conf").readText())
        assertTrue(File(rootfs, "etc/hosts").readText().startsWith("127.0.0.1 localhost"))
        assertTrue(File(rootfs, "root/workspace").isDirectory)
    }

    @Test fun deletesLeftoverRootfsOld() = runBlocking {
        val old = File(filesDir, "rootfs.old").also { File(it, "x").mkdirs() }
        newController().ensureRunning()
        assertFalse(old.exists())
    }

    @Test fun rootfsNotInstalledThrowsWithoutStartingAnything() {
        installed = false
        try {
            runBlocking { newController().ensureRunning() }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("rootfs not installed", e.message)
        }
        assertEquals(0, starts.get())
    }

    @Test fun stopEndsProcessClosesGatewayAndIsRepeatable() = runBlocking {
        val c = newController()
        val running = c.ensureRunning()
        assertTrue(portIsOpen(running.gatewayPort))
        val process = processes.single()
        c.stop()
        assertFalse(c.isRunning.value)
        assertFalse(process.isAlive)
        assertFalse(portIsOpen(running.gatewayPort))
        assertFalse(File(rootfs, "opt/dshm/dshm.cordis.yml").exists())
        c.stop() // second call is harmless
        // and a fresh start works with a new token
        val again = c.ensureRunning()
        assertEquals(2, starts.get())
        assertTrue(again.token.value != running.token.value)
    }

    @Test fun stopForceKillsAProcessThatIgnoresTermination() = runBlocking {
        script = """trap '' TERM; echo "dsh web: http://127.0.0.1:45678/?token=abc"; while true; do sleep 1; done"""
        val c = newController()
        c.ensureRunning()
        val process = processes.single()
        c.stop()
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
    }

    @Test fun missingLaunchLineFailsAndCleansUp() {
        script = "echo starting; sleep 30"
        launchTimeoutMs = 600
        val c = newController()
        try {
            runBlocking { c.ensureRunning() }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("launch"))
        }
        assertFalse(c.isRunning.value)
        waitUntil { !processes.single().isAlive }
        assertFalse(portIsOpen(gatewayPortOf(envs.single())))
        assertTrue(errorLog.file().readText().contains("launch"))
    }

    @Test fun exitBeforeLaunchLineFailsFast() {
        script = "echo oops >&2; exit 5"
        val c = newController()
        val started = System.currentTimeMillis()
        try {
            runBlocking { c.ensureRunning() }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("5"))
        }
        assertTrue(System.currentTimeMillis() - started < 5_000)
        waitUntil { errorLog.file().exists() && errorLog.file().readText().contains("[dsh] oops") }
        assertFalse(portIsOpen(gatewayPortOf(envs.single())))
    }

    @Test fun failedHealthCheckStopsEverythingAndLogsWhy() {
        healthy = false
        val c = newController()
        try {
            runBlocking { c.ensureRunning() }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("health"))
        }
        assertFalse(c.isRunning.value)
        waitUntil { !processes.single().isAlive }
        assertFalse(portIsOpen(gatewayPortOf(envs.single())))
        assertTrue(errorLog.file().readText().contains("health"))
    }

    @Test fun stdoutAndStderrGoToTheMaskedErrorLogButNotTheLaunchLine() = runBlocking {
        script = """echo "hello from dsh"; echo "boom token=zzz999" >&2; echo "dsh web: http://127.0.0.1:45678/?token=abcSECRET"; echo "again abcSECRET"; sleep 30"""
        newController().ensureRunning()
        waitUntil { errorLog.file().exists() && errorLog.file().readText().contains("again") }
        val text = errorLog.file().readText()
        assertTrue(text.contains("[dsh] hello from dsh"))
        assertTrue(text.contains("[dsh] boom token=***"))
        assertTrue(text.contains("[dsh] again ***"))
        assertFalse(text.contains("zzz999"))
        assertFalse(text.contains("abcSECRET"))
        assertFalse(text.contains("dsh web:"))
    }

    @Test fun sessionTokenNeverAppearsInTheLog() = runBlocking {
        val c = newController()
        val running = c.ensureRunning()
        errorLog.append("probe", "gateway http://127.0.0.1:1/${running.token.value}/x")
        assertFalse(errorLog.file().readText().contains(running.token.value))
    }

    @Test fun unexpectedExitNotifiesListenersAndStopDoesNot() = runBlocking {
        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 1; exit 3"""
        val c = newController()
        val exits = CopyOnWriteArrayList<Int>()
        c.onExit { exits.add(it) }
        val running = c.ensureRunning()
        waitUntil { exits.isNotEmpty() }
        assertEquals(listOf(3), exits.toList())
        waitUntil { !c.isRunning.value }
        assertFalse(portIsOpen(running.gatewayPort))

        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 30"""
        c.ensureRunning()
        c.stop()
        Thread.sleep(300)
        assertEquals(listOf(3), exits.toList())
    }

    @Test fun ensureRunningAfterACrashStartsAgain() = runBlocking {
        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 1; exit 1"""
        val c = newController()
        c.ensureRunning()
        waitUntil { !c.isRunning.value }
        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 30"""
        c.ensureRunning()
        assertEquals(2, starts.get())
        assertTrue(c.isRunning.value)
    }

    @Test fun closedExitListenerIsNotCalled() = runBlocking {
        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 1; exit 3"""
        val c = newController()
        val exits = CopyOnWriteArrayList<Int>()
        c.onExit { exits.add(it) }.close()
        c.ensureRunning()
        waitUntil { !c.isRunning.value }
        assertTrue(exits.isEmpty())
        assertNotNull(c)
    }
}
