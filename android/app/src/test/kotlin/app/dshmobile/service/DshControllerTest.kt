package app.dshmobile.service

import app.dshmobile.gateway.CostMeter
import app.dshmobile.gateway.InMemorySharedPreferences
import app.dshmobile.gateway.MutableClock
import app.dshmobile.gateway.PriceTable
import app.dshmobile.gateway.Provider
import app.dshmobile.log.ErrorLog
import app.dshmobile.runtime.LaunchUrl
import app.dshmobile.security.KeyVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CountDownLatch
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
    private var script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; exec sleep 30"""
    private var healthy = true
    private var duringHealthCheck: () -> Unit = {}
    private val healthChecked = CopyOnWriteArrayList<LaunchUrl>()
    private var launchTimeoutMs = 10_000L
    private var beforeLaunch: () -> Unit = {}
    /** The controller's own `beforeLaunch` (production: user-data dirs and the dsh version link). */
    private val prepares = AtomicInteger()
    private var prepare: () -> Unit = {}
    private var patchProvider: (String) -> String = { app.dshmobile.config.DshPatch.render(it) }
    private var vault: KeyVault = FakeVault()

    private fun controller() = DshController(
        patchProvider = { patchProvider(it) },
        filesDir = filesDir,
        isRootfsInstalled = { installed },
        launcher = { env, args ->
            starts.incrementAndGet()
            beforeLaunch()
            envs.add(env)
            argLists.add(args)
            ProcessBuilder("sh", "-c", script).start().also { processes.add(it) }
        },
        vault = vault,
        meter = CostMeter(InMemorySharedPreferences(), PriceTable(), clock = MutableClock()),
        errorLog = errorLog,
        healthCheck = { launch -> healthChecked.add(launch); duringHealthCheck(); healthy },
        dnsServers = { listOf("9.9.9.9") },
        onNotify = {},
        onCostAlert = {},
        onRateGuard = {},
        beforeLaunch = { prepares.incrementAndGet(); prepare() },
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

    /**
     * dsh printed its launch line and died while the start was still checking it (found on CI as a DshServiceTest flake: the
     * service never saw the crash). The exit watcher, seeing no published run, took the exit as expected and tore down; the
     * start then published the dead run as running. Now exactly one side reports it: here the start fails.
     */
    @Test fun aDshThatDiesBeforeItIsPublishedIsAFailedStartNotARunningOne() = runBlocking {
        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; sleep 1; exit 7""" // dies once the start is checking it
        val patch = File(rootfs, "opt/dshm/dshm.cordis.yml")
        duringHealthCheck = {
            waitUntil { processes.single().let { !it.isAlive } }
            waitUntil { !patch.exists() } // the exit watcher has torn the run down meanwhile
        }
        val c = newController()
        val exits = CopyOnWriteArrayList<Int>()
        c.onExit { exits.add(it) }

        val failure = runCatching { c.ensureRunning() }.exceptionOrNull()

        assertTrue("a dead dsh was published as running (got $failure)", failure is IllegalStateException)
        assertFalse(c.isRunning.value)
        assertTrue("the failure is the start's to report, not also an exit: $exits", exits.isEmpty())
        // The next start is a normal fresh start.
        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; exec sleep 30"""
        duringHealthCheck = {}
        c.ensureRunning()
        assertTrue(c.isRunning.value)
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

    @Test fun environmentLeavesOutTheClaudeVariableWithoutAClaudeKey() = runBlocking {
        // pi-ai discovers the anthropic provider from ANTHROPIC_API_KEY alone (direct to api.anthropic.com), so no key means no variable.
        vault = object : KeyVault {
            override fun get(p: Provider): String? = if (p == Provider.DEEPSEEK) "sk-real-deepseek" else null
            override fun put(p: Provider, key: String) = Unit
            override fun clear(p: Provider) = Unit
        }
        newController().ensureRunning()
        val env = envs.single()
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"))
        assertEquals("dshm-proxy", env["DEEPSEEK_API_KEY"])
        assertEquals(6, env.size)
    }

    @Test fun patchProviderGetsTheGatewayBaseAndItsTextIsWritten() = runBlocking {
        val bases = CopyOnWriteArrayList<String>()
        patchProvider = { base -> bases.add(base); "- id: from-provider\n  base: $base\n" }
        val running = newController().ensureRunning()
        val expectedBase = "http://127.0.0.1:${running.gatewayPort}/${running.token.value}"
        assertEquals(listOf(expectedBase), bases)
        assertEquals("- id: from-provider\n  base: $expectedBase\n", File(rootfs, "opt/dshm/dshm.cordis.yml").readText())
        val perms = Files.getPosixFilePermissions(File(rootfs, "opt/dshm/dshm.cordis.yml").toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test fun aFailingPatchProviderFailsTheStartAndCleansUp() {
        patchProvider = { throw IllegalStateException("provider broke") }
        val c = newController()
        try {
            runBlocking { c.ensureRunning() }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("provider broke", e.message)
        }
        assertEquals(0, starts.get())
        assertFalse(c.isRunning.value)
        assertFalse(File(rootfs, "opt/dshm/dshm.cordis.yml").exists())
        // the next start asks the provider again
        patchProvider = { app.dshmobile.config.DshPatch.render(it) }
        runBlocking { c.ensureRunning() }
        assertEquals(1, starts.get())
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
        assertEquals("the launch preparation ran once, before the launch", 1, prepares.get())
    }

    @Test fun aFailingLaunchPreparationIsAFailedStartWithoutALaunch() = runBlocking {
        prepare = { throw java.io.IOException("cannot create the user data directories") }
        val c = newController()
        try {
            c.ensureRunning()
            fail("the start succeeded")
        } catch (_: java.io.IOException) {
        }
        assertEquals(0, starts.get())
        assertFalse(c.isRunning.value)
        assertFalse("no patch with the session token is left", File(rootfs, "opt/dshm/dshm.cordis.yml").exists())
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
            assertTrue(e is RootfsNotInstalledException)
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
        script = "echo starting; exec sleep 30"
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
        script = """echo "hello from dsh"; echo "boom token=zzz999" >&2; echo "dsh web: http://127.0.0.1:45678/?token=abcSECRET"; echo "again abcSECRET"; exec sleep 30"""
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

        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; exec sleep 30"""
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
        script = """echo "dsh web: http://127.0.0.1:45678/?token=abc"; exec sleep 30"""
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

    // ---- holders ----

    @Test fun holdersStartEmptyAndRetainReleaseBehaveLikeASet() {
        val c = newController()
        assertTrue(c.holders.isEmpty())
        c.retain("service")
        c.retain("service") // same owner twice is still one hold
        c.retain("heartbeat")
        assertEquals(setOf("service", "heartbeat"), c.holders)
        c.release("service")
        assertEquals(setOf("heartbeat"), c.holders)
        c.release("nobody") // harmless
        c.release("heartbeat")
        assertTrue(c.holders.isEmpty())
    }

    @Test fun holdersIsASnapshotNotALiveView() {
        val c = newController()
        c.retain("a")
        val snapshot = c.holders
        c.retain("b")
        assertEquals(setOf("a"), snapshot)
    }

    @Test fun stopIfUnretainedStopsWhenNobodyHoldsDsh() = runBlocking {
        val c = newController()
        c.ensureRunning()
        val process = processes.single()
        assertTrue(c.stopIfUnretained())
        assertFalse(c.isRunning.value)
        assertFalse(process.isAlive)
    }

    @Test fun stopIfUnretainedLeavesDshAloneWhileHeldAndStopsAfterRelease() = runBlocking {
        val c = newController()
        val running = c.ensureRunning()
        c.retain("service")
        c.retain("heartbeat")
        c.release("heartbeat")
        assertFalse(c.stopIfUnretained())
        assertTrue(c.isRunning.value)
        assertTrue(processes.single().isAlive)
        assertTrue(portIsOpen(running.gatewayPort))
        c.release("service")
        assertTrue(c.stopIfUnretained())
        assertFalse(c.isRunning.value)
        assertFalse(processes.single().isAlive)
    }

    @Test fun stopIfUnretainedWithNothingRunningIsHarmless() {
        val c = newController()
        assertTrue(c.stopIfUnretained())
        c.retain("ui")
        assertFalse(c.stopIfUnretained())
        assertEquals(0, starts.get())
    }

    @Test fun stopStillForcesTheStopWhileHeld() = runBlocking {
        val c = newController()
        c.ensureRunning()
        c.retain("service")
        c.stop()
        assertFalse(c.isRunning.value)
        assertFalse(processes.single().isAlive)
    }

    @Test fun aHolderThatArrivesAfterStopIfUnretainedGetsAFreshDshNotADyingOne() = runBlocking {
        val c = newController()
        val first = c.ensureRunning()
        assertTrue(c.stopIfUnretained())
        c.retain("ui")
        val second = c.ensureRunning()
        assertEquals(2, starts.get())
        assertTrue(second.token.value != first.token.value)
        assertTrue(processes.last().isAlive)
        assertTrue(c.isRunning.value)
    }

    @Test fun concurrentRetainAndReleaseFromManyThreadsEndsConsistent() {
        val c = newController()
        c.retain("service")
        val threads = (1..8).map { t ->
            Thread {
                repeat(500) { i ->
                    c.retain("t$t")
                    c.retain("shared")
                    c.holders // reading while others write must not throw
                    c.release("t$t")
                    if (i % 50 == 0) c.stopIfUnretained()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        assertTrue(threads.none { it.isAlive })
        c.release("shared")
        assertEquals(setOf("service"), c.holders)
        c.release("service")
        assertTrue(c.holders.isEmpty())
    }

    @Test fun stopIfUnretainedRacingWithRetainNeverStopsAHeldDsh() = runBlocking {
        val c = newController()
        c.ensureRunning()
        c.retain("service")
        val stoppers = (1..4).map { Thread { repeat(200) { c.stopIfUnretained() } } }
        val churn = Thread { repeat(500) { c.retain("hb"); c.release("hb") } }
        (stoppers + churn).forEach { it.start() }
        (stoppers + churn).forEach { it.join(20_000) }
        assertTrue(c.isRunning.value)
        assertTrue(processes.single().isAlive)
        assertEquals(1, starts.get())
    }

    // ---- carried-over fixes ----

    @Test fun launcherFailureStillDeletesThePatchFileWithTheToken() {
        val patch = File(rootfs, "opt/dshm/dshm.cordis.yml")
        beforeLaunch = {
            assertTrue("patch must exist when the launcher runs", patch.exists())
            throw java.io.IOException("exec failed")
        }
        val c = newController()
        try {
            runBlocking { c.ensureRunning() }
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("exec failed", e.message)
        }
        assertFalse(patch.exists())
        assertFalse(c.isRunning.value)
        assertEquals(1, starts.get())
    }

    @Test fun stopDuringStartBeforeTheProcessExistsCancelsTheStart() = runBlocking {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        beforeLaunch = { entered.countDown(); gate.await(10, TimeUnit.SECONDS) }
        val c = newController()
        val result = async(Dispatchers.IO) { runCatching { c.ensureRunning() } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        c.stop() // current is not set yet: this stop must not be lost
        gate.countDown()
        val failure = result.await().exceptionOrNull()
        assertTrue("expected CancellationException, got $failure", failure is CancellationException)
        assertFalse(c.isRunning.value)
        waitUntil { !processes.single().isAlive }
        assertFalse(portIsOpen(gatewayPortOf(envs.single())))
        assertFalse(File(rootfs, "opt/dshm/dshm.cordis.yml").exists())
    }

    @Test fun stopWhileWaitingForTheLaunchLineCancelsTheStart() = runBlocking {
        script = "echo starting; exec sleep 30"
        val c = newController()
        val result = async(Dispatchers.IO) { runCatching { c.ensureRunning() } }
        waitUntil { processes.isNotEmpty() }
        c.stop()
        val failure = result.await().exceptionOrNull()
        assertTrue("expected CancellationException, got $failure", failure is CancellationException)
        assertFalse(c.isRunning.value)
        waitUntil { !processes.single().isAlive }
    }

    @Test fun aStopWhileIdleDoesNotPoisonTheNextStart() = runBlocking {
        val c = newController()
        c.stop()
        c.stop()
        val running = c.ensureRunning()
        assertTrue(c.isRunning.value)
        assertEquals(45678, running.dshPort)
    }

    @Test fun startAfterACancelledStartWorks() = runBlocking {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        beforeLaunch = { entered.countDown(); gate.await(10, TimeUnit.SECONDS) }
        val c = newController()
        val first = async(Dispatchers.IO) { runCatching { c.ensureRunning() } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        c.stop()
        gate.countDown()
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        beforeLaunch = {}
        c.ensureRunning()
        assertTrue(c.isRunning.value)
        assertEquals(2, starts.get())
    }

    @Test fun stopIfUnretainedMidStartWithNoHolderCancelsTheStartInsteadOfLeavingAHolderlessDsh() = runBlocking {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        beforeLaunch = { entered.countDown(); gate.await(10, TimeUnit.SECONDS) }
        val c = newController()
        val result = async(Dispatchers.IO) { runCatching { c.ensureRunning() } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(c.holders.isEmpty())
        assertTrue(c.stopIfUnretained()) // nobody holds the dsh that is being started
        gate.countDown()
        val failure = result.await().exceptionOrNull()
        assertTrue("expected CancellationException, got $failure", failure is CancellationException)
        assertFalse(c.isRunning.value)
        waitUntil { !processes.single().isAlive }
        assertFalse(portIsOpen(gatewayPortOf(envs.single())))
        assertFalse(File(rootfs, "opt/dshm/dshm.cordis.yml").exists())
    }

    @Test fun stopIfUnretainedMidStartWhileHeldLeavesTheStartAlone() = runBlocking {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        beforeLaunch = { entered.countDown(); gate.await(10, TimeUnit.SECONDS) }
        val c = newController()
        c.retain("ui")
        val result = async(Dispatchers.IO) { runCatching { c.ensureRunning() } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(c.stopIfUnretained())
        gate.countDown()
        assertTrue(result.await().isSuccess)
        assertTrue(c.isRunning.value)
        assertTrue(processes.single().isAlive)
    }

    @Test fun stopIfUnretainedWhileWaitingForTheLaunchLineWithNoHolderCancelsTheStart() = runBlocking {
        script = "echo starting; exec sleep 30"
        val c = newController()
        val result = async(Dispatchers.IO) { runCatching { c.ensureRunning() } }
        waitUntil { processes.isNotEmpty() }
        assertTrue(c.stopIfUnretained())
        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertFalse(c.isRunning.value)
        waitUntil { !processes.single().isAlive }
    }
}
