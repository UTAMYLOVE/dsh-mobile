package app.dshmobile.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ProcessTreeKillerTest {
    private val fake = FakeProc()

    /** proot 100 → node 101 → a tool 102 (bash) → 103; plus processes that are not dsh's. */
    private fun dshTree() {
        fake.proot(100)
        fake.node(101, 100)
        fake.add(102, 101, "/bin/bash -c sleep 99")
        fake.add(103, 102, "sleep 99")
    }

    @Test fun findsTheWholeTreeUnderTheRoot() {
        dshTree()
        fake.add(200, fake.myPid, "something else of ours")
        val report = fake.killer().stopTree(100)
        assertEquals(4, report.found)
        assertTrue(report.stopped)
        listOf(100, 101, 102, 103).forEach { assertFalse("pid $it alive", fake.alive(it)) }
        assertTrue("an unrelated process of ours was touched", fake.alive(200))
        assertTrue(fake.sent.none { it.first == 200 })
    }

    @Test fun signalOrderIsNodeTermThenProotQuitThenKill() {
        fake.proot(100)
        fake.node(101, 100, diesOn = setOf(Sig.KILL)) // a node that does not end on SIGTERM
        fake.add(102, 101, "a tool", diesOn = setOf(Sig.KILL))
        val report = fake.killer().stopTree(100)
        assertTrue(report.stopped)
        val firstTerm = fake.sent.indexOf(101 to Sig.TERM)
        val quit = fake.sent.indexOf(100 to Sig.QUIT)
        assertEquals("node gets SIGTERM first", 0, firstTerm)
        assertTrue("proot gets SIGQUIT after node's SIGTERM: ${fake.sent}", quit > firstTerm)
        assertTrue("SIGQUIT goes to proot only", fake.sent.filter { it.second == Sig.QUIT }.all { it.first == 100 })
        assertTrue("SIGTERM goes to node only", fake.sent.filter { it.second == Sig.TERM }.all { it.first == 101 })
    }

    @Test fun killIsTheLastResortAfterTermAndQuit() {
        fake.add(100, fake.myPid, "/x/libproot.so", diesOn = setOf(Sig.KILL)) // a root that ignores QUIT too
        fake.add(101, 100, "node bin.js", diesOn = setOf(Sig.KILL))
        val report = fake.killer().stopTree(100)
        assertTrue(report.stopped)
        assertEquals(listOf(101 to Sig.TERM, 100 to Sig.QUIT), fake.sent.take(2))
        assertEquals(setOf(100 to Sig.KILL, 101 to Sig.KILL), fake.sent.drop(2).toSet())
        assertEquals(2, report.killed)
    }

    @Test fun aCleanTermNeedsNoKill() {
        fake.proot(100)
        fake.node(101, 100)
        // proot --kill-on-exit: when node ends, proot ends too. Modelled: node's TERM, then proot's QUIT; no KILL is needed.
        val report = fake.killer().stopTree(100)
        assertTrue(report.stopped)
        assertTrue(fake.sent.none { it.second == Sig.KILL })
        assertEquals(1, report.termed)
    }

    @Test fun aProcessThatSurvivesEverythingIsAFailedStopWithinTheBound() {
        fake.proot(100)
        fake.add(101, 100, "node bin.js", immortal = true)
        val killer = fake.killer()
        val report = killer.stopTree(100)
        assertFalse("a stuck process must never be reported as stopped", report.stopped)
        assertEquals(listOf(101), report.survivors.map { it.pid })
        assertTrue("the wait is bounded: ${fake.now} ms", fake.now in 1..8_000)
        assertTrue(report.describe().contains("still alive: 101"))
    }

    @Test fun progressCountsDownTheTreeStillAlive() {
        dshTree()
        val seen = ArrayList<Int>()
        fake.killer().stopTree(100) { seen += it }
        assertEquals(0, seen.last())
    }

    @Test fun neverSignalsAnotherUidOrItselfOrInit() {
        fake.proot(100)
        fake.add(101, 100, "node bin.js", uid = 0, diesOn = setOf(Sig.KILL)) // not ours (cannot happen on Android, checked anyway)
        fake.add(1, 0, "init /libproot.so")
        fake.add(fake.myPid, 1, "app.dshmobile libproot.so")
        fake.killer().stopTree(100)
        assertTrue(fake.sent.none { it.first == 101 || it.first == 1 || it.first == fake.myPid })
        fake.killer().sweep()
        assertTrue(fake.sent.none { it.first == 101 || it.first == 1 || it.first == fake.myPid })
    }

    @Test fun aReusedPidIsNeverSignalled() {
        fake.proot(100)
        val stale = ProcId(100, 1) // the process we saw had another start time
        val report = fake.killer().sweep(lingering = listOf(stale), keep = setOf(100))
        assertEquals(StopReport.NOTHING, report)
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun aMissingRootIsNothingToStop() {
        assertEquals(StopReport.NOTHING, fake.killer().stopTree(4242))
        assertTrue(fake.sent.isEmpty())
    }

    // ---------- the sweep ----------

    @Test fun sweepKillsStrayDshTreesAndLeavesOtherProcessesAlone() {
        dshTree() // a leaked dsh nobody tracks
        fake.proot(300)          // a second leaked proot …
        fake.node(301, 300)      // … with its node
        fake.add(400, fake.myPid, "/system/bin/sh -c logcat") // ours, not dsh
        fake.add(500, 1, "node /other/app.js", uid = 10999) // another app's
        val report = fake.killer().sweep()
        assertTrue(report.stopped)
        listOf(100, 101, 102, 103, 300, 301).forEach { assertFalse("pid $it alive", fake.alive(it)) }
        assertTrue(fake.alive(400))
        assertTrue(fake.alive(500))
        assertTrue(fake.sent.none { it.first == 400 || it.first == 500 })
        assertEquals(6, report.found)
    }

    @Test fun sweepLeavesTrackedCommandsAndKeptTreesAlone() {
        fake.proot(100) // dsh we run right now
        fake.node(101, 100)
        fake.add(200, fake.myPid, "/x/libproot.so -r rootfs /usr/bin/env npm install", diesOn = setOf(Sig.QUIT, Sig.KILL)) // a guest npm
        fake.add(201, 200, "npm install")
        val killer = fake.killer()
        killer.startTracked({ 200 }, { it })
        val report = killer.sweep(keep = setOf(100))
        assertEquals(StopReport.NOTHING, report)
        assertTrue(fake.sent.isEmpty())
        killer.untrack(200)
        assertTrue(killer.sweep(keep = setOf(100)).stopped)
        assertFalse(fake.alive(200))
        assertTrue(fake.alive(100))
    }

    @Test fun sweepReportsAStrayItCannotEnd() {
        fake.add(100, fake.myPid, "/x/libproot.so bin.js", immortal = true)
        val report = fake.killer().sweep()
        assertFalse(report.stopped)
        assertEquals(listOf(100), report.survivors.map { it.pid })
    }

    @Test fun sweepAlsoEndsLingeringProcessesFromAFailedStop() {
        fake.add(100, fake.myPid, "sleep 99 (a tool left from a failed stop)", diesOn = setOf(Sig.KILL))
        val report = fake.killer().sweep(lingering = listOf(ProcId(100, 1000)))
        assertTrue(report.stopped)
        assertFalse(fake.alive(100))
    }

    // ---------- /proc parsing ----------

    private val procRoot: File = Files.createTempDirectory("fakeproc").toFile()

    @After fun cleanup() { procRoot.deleteRecursively() }

    private fun fakeProcEntry(pid: Int, comm: String, state: Char, ppid: Int, start: Long, uid: Int, cmdline: List<String>) {
        val dir = File(procRoot, pid.toString()).also { it.mkdirs() }
        // 52 fields as the kernel writes them; field 22 is the start time.
        val rest = (4..52).map { i -> when (i) { 4 -> ppid.toString(); 22 -> start.toString(); else -> "0" } }
        dir.resolve("stat").writeText("$pid ($comm) $state ${rest.joinToString(" ")}\n")
        dir.resolve("status").writeText("Name:\t$comm\nState:\t$state\nPid:\t$pid\nPPid:\t$ppid\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        dir.resolve("cmdline").writeBytes(cmdline.joinToString("\u0000", postfix = "\u0000").toByteArray())
    }

    @Test fun procfsReadsPpidStartTimeUidStateAndCommandLine() {
        fakeProcEntry(100, "libproot.so", 'S', 4000, 777, 10123, listOf("/data/lib/libproot.so", "-r", "/data/rootfs"))
        fakeProcEntry(101, "node) (weird name", 'R', 100, 778, 10123, listOf("node", "bin.js"))
        fakeProcEntry(102, "zomb", 'Z', 100, 779, 10123, emptyList())
        File(procRoot, "self").mkdirs()
        File(procRoot, "notapid").mkdirs()
        File(procRoot, "103").mkdirs() // ended while being read: no stat
        val list = ProcfsSource(procRoot).list().sortedBy { it.pid }
        assertEquals(listOf(100, 101, 102), list.map { it.pid })
        assertEquals(ProcInfo(100, 4000, 10123, 777, 'S', "/data/lib/libproot.so -r /data/rootfs"), list[0])
        assertEquals(100, list[1].ppid)
        assertEquals(778L, list[1].startTime)
        assertFalse("a zombie counts as gone", list[2].alive)
    }

    @Test fun parseStatIsNullForGarbage() {
        assertNull(ProcfsSource.parseStat("nonsense"))
        assertNull(ProcfsSource.parseStat("1 (x) S 0"))
    }

    // ---------- a real process tree on this machine ----------

    /** A real `sh` that ignores SIGTERM like proot, with a child: the killer with real /proc and real signals ends both. */
    @Test fun realTreeThatIgnoresTermIsGone() {
        val marker = "dshm-test-${System.nanoTime()}"
        val p = ProcessBuilder("sh", "-c", "trap '' TERM; sleep 60 & wait; : $marker").start()
        val pid = ProcessTreeKiller.pidOf(p)!!
        val self = ProcfsSource.self()!!
        val killer = ProcessTreeKiller(ProcfsSource(), DesktopSignals, self.first, self.second, listOf(marker))
        // wait until the child exists
        val end = System.currentTimeMillis() + 5_000
        while (ProcfsSource().list().none { it.ppid == pid } && System.currentTimeMillis() < end) Thread.sleep(20)
        val report = killer.stopTree(pid)
        assertTrue(report.describe(), report.stopped)
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        assertTrue(ProcfsSource().list().none { it.alive && marker in it.cmdline })
    }

    @Test fun pidOfReadsTheProcessPid() {
        val p = ProcessBuilder("sh", "-c", "exit 0").start()
        assertTrue((ProcessTreeKiller.pidOf(p) ?: 0) > 1)
        p.waitFor()
    }
}
