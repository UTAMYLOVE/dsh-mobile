package app.dshmobile.runtime

import app.dshmobile.log.ErrorLog
import app.dshmobile.service.DshRuntime
import app.dshmobile.service.RunningDsh
import app.dshmobile.security.SessionToken
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class DshVersionsTest {
    private val base: File = Files.createTempDirectory("dshver").toFile()
    private val optDsh = File(base, "rootfs/opt/dsh").also { it.mkdirs() }
    private val log = ErrorLog(File(base, "files"))
    private val calls = mutableListOf<List<String>>()
    private var npm: (List<String>) -> CommandResult = { CommandResult(0, "", "") }
    private val cmd = GuestCommand { args, timeout -> calls.add(args + "timeout=$timeout"); npm(args) }
    private val versions = DshVersions(optDsh, cmd, log)

    @After fun cleanup() { base.deleteRecursively() }

    private fun mkVersion(v: String, dir: String = v) {
        val pkg = File(optDsh, "$dir/node_modules/@deepseek-ai/dsh/package.json")
        pkg.parentFile.mkdirs()
        pkg.writeText("""{"name":"@deepseek-ai/dsh","version":"$v"}""")
    }

    private fun link(v: String) {
        val cur = File(optDsh, "current").toPath()
        Files.deleteIfExists(cur)
        Files.createSymbolicLink(cur, File("/opt/dsh/$v").toPath())
    }

    private fun setup(current: String, vararg others: String) {
        mkVersion(current); link(current)
        others.forEach { mkVersion(it) }
    }

    private fun dirs() = optDsh.list().orEmpty().sorted()

    /** Simulates `npm install --prefix /opt/dsh/<v>.tmp @deepseek-ai/dsh@<v>`: creates the tree in the host's optDsh. */
    private fun npmInstallsInto(installedVersion: String? = null) {
        npm = { args ->
            if (args.take(2) == listOf("npm", "install")) {
                val prefix = args[args.indexOf("--prefix") + 1]
                val requested = args.first { it.startsWith("@deepseek-ai/dsh@") }.substringAfterLast('@')
                mkVersion(installedVersion ?: requested, File(prefix).name)
            }
            CommandResult(0, "", "")
        }
    }

    // ---------- version string validation ----------

    @Test fun invalidVersionStringsAreRejected() = runBlocking {
        setup("1.0.0", "0.9.0")
        for (bad in listOf("../x", "1.2.3; rm -rf /", "", "1.2", "v1.2.3", "1.2.3/../../x", "1.2.3\n", "1.2.3-", "1.2.3 ")) {
            assertTrue("install '$bad'", versions.install(bad).isFailure)
            try { versions.switchTo(bad); fail("switchTo accepted '$bad'") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(calls.isEmpty())
        assertEquals("1.0.0", versions.current())
    }

    @Test fun prereleaseVersionsAreAccepted() = runBlocking {
        setup("1.0.0")
        npmInstallsInto()
        assertTrue(versions.install("1.1.0-beta.2").isSuccess)
        assertTrue(File(optDsh, "1.1.0-beta.2/node_modules/@deepseek-ai/dsh").isDirectory)
    }

    // ---------- current / previous / latestAvailable ----------

    @Test fun currentReadsLastSegmentOfAbsoluteLinkTarget() {
        setup("1.2.3")
        assertEquals("1.2.3", versions.current())
    }

    @Test fun previousIsNullWithoutFileOrWithMissingDirectory() {
        setup("1.0.0")
        assertNull(versions.previous())
        File(optDsh, ".previous").writeText("0.9.0\n")
        assertNull(versions.previous())
        mkVersion("0.9.0")
        assertEquals("0.9.0", versions.previous())
    }

    @Test fun previousRejectsGarbageInFile() {
        setup("1.0.0")
        File(optDsh, ".previous").writeText("../../etc\n")
        assertNull(versions.previous())
    }

    @Test fun latestAvailableRunsNpmViewAndTrims() = runBlocking {
        npm = { CommandResult(0, "1.4.0\n", "") }
        assertEquals("1.4.0", versions.latestAvailable())
        assertEquals(listOf("npm", "view", "@deepseek-ai/dsh", "version", "timeout=60000"), calls.single())
    }

    @Test fun latestAvailableThrowsOnBadOutputOrExit() = runBlocking {
        for (r in listOf(CommandResult(0, "not a version", ""), CommandResult(0, "", ""), CommandResult(1, "1.0.0", "E404"), CommandResult(-1, "", "timed out"))) {
            npm = { r }
            try { versions.latestAvailable(); fail("accepted $r") } catch (_: IllegalStateException) {}
        }
    }

    // ---------- install ----------

    @Test fun installPlacesNewVersionBesideCurrentWithoutTouchingIt() = runBlocking {
        setup("1.0.0")
        npmInstallsInto()
        assertTrue(versions.install("1.1.0").isSuccess)
        assertEquals(listOf("1.0.0", "1.1.0", "current"), dirs())
        assertEquals("1.0.0", versions.current())
        val args = calls.single()
        assertEquals(
            listOf(
                "npm", "install", "--prefix", "/opt/dsh/1.1.0.tmp", "@deepseek-ai/dsh@1.1.0",
                "--no-audit", "--no-fund", "--loglevel=error", "timeout=900000",
            ),
            args,
        )
    }

    @Test fun installOfExistingVersionSucceedsWithoutRunningNpm() = runBlocking {
        setup("1.0.0", "1.1.0")
        assertTrue(versions.install("1.1.0").isSuccess)
        assertTrue(calls.isEmpty())
    }

    @Test fun installFailureLeavesCurrentUntouchedAndNoTmp() = runBlocking {
        setup("1.0.0")
        npm = { args ->
            // npm got as far as writing some files, then ran out of space
            File(optDsh, "1.1.0.tmp/node_modules/half").mkdirs()
            File(optDsh, "1.1.0.tmp/node_modules/half/x.js").writeText("partial")
            CommandResult(1, "", "npm ERR! code ENOSPC\nnpm ERR! nospc ${args.size}")
        }
        val r = versions.install("1.1.0")
        assertTrue(r.isFailure)
        assertEquals(listOf("1.0.0", "current"), dirs())
        assertEquals("1.0.0", versions.current())
        assertTrue(File(optDsh, "1.0.0/node_modules/@deepseek-ai/dsh/package.json").isFile)
        assertTrue(log.file().readText().contains("ENOSPC"))
    }

    @Test fun installTimeoutCleansUpAndFails() = runBlocking {
        setup("1.0.0")
        npm = {
            File(optDsh, "1.1.0.tmp").mkdirs()
            CommandResult(-1, "", "timed out")
        }
        assertTrue(versions.install("1.1.0").isFailure)
        assertEquals(listOf("1.0.0", "current"), dirs())
    }

    @Test fun installRejectsWrongInstalledVersion() = runBlocking {
        setup("1.0.0")
        npmInstallsInto(installedVersion = "1.0.9")
        val r = versions.install("1.1.0")
        assertTrue(r.isFailure)
        assertEquals(listOf("1.0.0", "current"), dirs())
        assertTrue(log.file().readText().contains("1.0.9"))
    }

    @Test fun installFailsWhenNpmReportsSuccessButWroteNothing() = runBlocking {
        setup("1.0.0")
        assertTrue(versions.install("1.1.0").isFailure)
        assertEquals(listOf("1.0.0", "current"), dirs())
    }

    @Test fun installRemovesStaleTmpFromEarlierAttempt() = runBlocking {
        setup("1.0.0")
        File(optDsh, "1.1.0.tmp/node_modules/stale").mkdirs()
        File(optDsh, "1.1.0.tmp/node_modules/stale/old.js").writeText("old")
        npmInstallsInto()
        assertTrue(versions.install("1.1.0").isSuccess)
        assertFalse(File(optDsh, "1.1.0/node_modules/stale").exists())
    }

    // ---------- switchTo / rollback ----------

    @Test fun switchIsAtomicAndRecordsPrevious() {
        setup("1.0.0", "1.1.0")
        versions.switchTo("1.1.0")
        assertEquals("1.1.0", versions.current())
        assertEquals("1.0.0", versions.previous())
        val target = Files.readSymbolicLink(File(optDsh, "current").toPath())
        assertEquals("/opt/dsh/1.1.0", target.toString())
        assertFalse(Files.exists(File(optDsh, "current.tmp").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(optDsh, ".previous.tmp").exists())
        assertEquals("1.0.0", File(optDsh, ".previous").readText().trim())
    }

    @Test fun switchToSameVersionDoesNothing() {
        setup("1.0.0", "0.9.0")
        File(optDsh, ".previous").writeText("0.9.0\n")
        versions.switchTo("1.0.0")
        assertEquals("0.9.0", versions.previous())
        assertEquals("1.0.0", versions.current())
    }

    @Test fun switchToMissingVersionFailsAndChangesNothing() {
        setup("1.0.0")
        try { versions.switchTo("2.0.0"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("1.0.0", versions.current())
        assertNull(versions.previous())
    }

    @Test fun keepsOnlyTwoVersions() {
        setup("1.0.0", "1.1.0", "0.8.0", "0.9.0")
        File(optDsh, "1.2.0.tmp/node_modules").mkdirs()
        versions.switchTo("1.1.0")
        assertEquals(listOf(".previous", "1.0.0", "1.1.0", "current"), dirs())
        versions.switchTo("1.0.0")
        assertEquals(listOf(".previous", "1.0.0", "1.1.0", "current"), dirs())
    }

    @Test fun cleanupNeverFollowsSymlinksInsideOldVersions() {
        setup("1.0.0", "1.1.0", "0.8.0")
        val outside = File(base, "outside").also { it.mkdirs() }
        File(outside, "precious.txt").writeText("keep")
        Files.createSymbolicLink(File(optDsh, "0.8.0/node_modules/lnk").toPath(), outside.toPath())
        versions.switchTo("1.1.0")
        assertFalse(File(optDsh, "0.8.0").exists())
        assertEquals("keep", File(outside, "precious.txt").readText())
    }

    @Test fun cleanupLeavesUnrelatedEntriesAlone() {
        setup("1.0.0", "1.1.0")
        File(optDsh, "notes").mkdirs()
        versions.switchTo("1.1.0")
        assertTrue(File(optDsh, "notes").isDirectory)
    }

    @Test fun switchDuringRunningInstallKeepsItsTmp() = runBlocking {
        setup("1.0.0", "0.9.0")
        val inInstall = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        npm = { args ->
            mkVersion("1.2.0", "1.2.0.tmp")
            inInstall.countDown()
            release.await()
            CommandResult(0, "", "")
        }
        val job = async(kotlinx.coroutines.Dispatchers.Default) { versions.install("1.2.0") }
        inInstall.await()
        versions.switchTo("0.9.0")
        assertTrue(File(optDsh, "1.2.0.tmp/node_modules").isDirectory)
        release.countDown()
        assertTrue(job.await().isSuccess)
        assertTrue(File(optDsh, "1.2.0/node_modules/@deepseek-ai/dsh").isDirectory)
    }

    @Test fun manualRollbackSwapsCurrentAndPrevious() {
        setup("1.0.0", "1.1.0")
        versions.switchTo("1.1.0")
        assertTrue(versions.rollback())
        assertEquals("1.0.0", versions.current())
        assertEquals("1.1.0", versions.previous())
        assertTrue(versions.rollback())
        assertEquals("1.1.0", versions.current())
        assertEquals("1.0.0", versions.previous())
    }

    @Test fun rollbackWithoutPreviousReturnsFalse() {
        setup("1.0.0")
        assertFalse(versions.rollback())
        assertEquals("1.0.0", versions.current())
    }

    // ---------- updateWithAutoRollback ----------

    private class FakeRuntime(private val versions: DshVersions, private val unhealthy: Set<String>) : DshRuntime {
        val events = mutableListOf<String>()
        override fun stop() { events.add("stop") }
        override suspend fun ensureRunning(): RunningDsh {
            val v = versions.current()
            events.add("start:$v")
            if (v in unhealthy) throw IllegalStateException("dsh $v failed its health check")
            return RunningDsh(1, 2, SessionToken("t"), LaunchUrl("http://127.0.0.1:1/?token=t", 1))
        }
    }

    private val health = HealthCheck(OkHttpClient())

    @Test fun updateInstallsSwitchesAndStartsNewVersion() = runBlocking {
        setup("1.0.0")
        npm = { args -> if (args[1] == "view") CommandResult(0, "1.1.0\n", "") else { npmInstallsInto(); npm(args) } }
        val rt = FakeRuntime(versions, emptySet())
        assertEquals(UpdateOutcome.Updated("1.1.0"), versions.updateWithAutoRollback(rt, health))
        assertEquals(listOf("stop", "start:1.1.0"), rt.events)
        assertEquals("1.1.0", versions.current())
        assertEquals("1.0.0", versions.previous())
    }

    @Test fun unhealthyNewVersionRollsBackAutomatically() = runBlocking {
        setup("1.0.0")
        npm = { args -> if (args[1] == "view") CommandResult(0, "1.1.0\n", "") else { npmInstallsInto(); npm(args) } }
        val rt = FakeRuntime(versions, setOf("1.1.0"))
        val out = versions.updateWithAutoRollback(rt, health)
        assertTrue(out.toString(), out is UpdateOutcome.RolledBack)
        out as UpdateOutcome.RolledBack
        assertEquals("1.1.0", out.version)
        assertTrue(out.reason, out.reason.contains("health check"))
        assertEquals("1.0.0", versions.current())
        assertEquals(listOf("stop", "start:1.1.0", "stop", "start:1.0.0"), rt.events)
        assertTrue(log.file().readText().contains("1.1.0"))
    }

    @Test fun rollbackWhenOldVersionAlsoFailsStillReportsRolledBack() = runBlocking {
        setup("1.0.0")
        npm = { args -> if (args[1] == "view") CommandResult(0, "1.1.0\n", "") else { npmInstallsInto(); npm(args) } }
        val rt = FakeRuntime(versions, setOf("1.1.0", "1.0.0"))
        val out = versions.updateWithAutoRollback(rt, health) as UpdateOutcome.RolledBack
        assertTrue(out.reason, out.reason.contains("1.0.0"))
        assertEquals("1.0.0", versions.current())
        assertEquals(listOf("stop", "start:1.1.0", "stop", "start:1.0.0"), rt.events)
    }

    @Test fun sameVersionIsUpToDate() = runBlocking {
        setup("1.0.0")
        npm = { CommandResult(0, "1.0.0\n", "") }
        val rt = FakeRuntime(versions, emptySet())
        assertEquals(UpdateOutcome.UpToDate, versions.updateWithAutoRollback(rt, health))
        assertTrue(rt.events.isEmpty())
        assertTrue(calls.none { it[1] == "install" })
    }

    @Test fun latestAvailableFailureIsFailedAndDshKeepsRunning() = runBlocking {
        setup("1.0.0")
        npm = { CommandResult(1, "", "network down") }
        val rt = FakeRuntime(versions, emptySet())
        assertTrue(versions.updateWithAutoRollback(rt, health) is UpdateOutcome.Failed)
        assertTrue(rt.events.isEmpty())
        assertEquals("1.0.0", versions.current())
    }

    @Test fun installFailureIsFailedAndDshKeepsRunning() = runBlocking {
        setup("1.0.0")
        npm = { args -> if (args[1] == "view") CommandResult(0, "1.1.0\n", "") else CommandResult(1, "", "ENOSPC") }
        val rt = FakeRuntime(versions, emptySet())
        val out = versions.updateWithAutoRollback(rt, health)
        assertTrue(out.toString(), out is UpdateOutcome.Failed)
        assertTrue(rt.events.isEmpty())
        assertEquals("1.0.0", versions.current())
        assertEquals(listOf("1.0.0", "current"), dirs())
    }
}
